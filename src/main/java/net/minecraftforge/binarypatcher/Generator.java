/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.binarypatcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Map.Entry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Pack200;
import java.util.jar.Pack200.Packer;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import lzma.streams.LzmaOutputStream;
import net.minecraftforge.srgutils.IMappingFile;

public class Generator {
    public static final String EXTENSION = ".lzma";
    private static final byte[] EMPTY_DATA = new byte[0];

    private final Map<String, String> o2m = new HashMap<>();
    private final Map<String, String> m2o = new HashMap<>();
    private final Set<String> patches = new TreeSet<>();

    private final File output;
    private final List<PatchSet> sets = new ArrayList<>();
    private boolean pack200 = false;
    private boolean legacy = false;

    public Generator(File output) {
        this.output = output;
    }

    public Generator addSet(File clean, File dirty, String prefix) {
        if (!sets.isEmpty()) {
            String oldPre = sets.get(0).prefix;
            if (oldPre == null || oldPre.isEmpty() || prefix == null || prefix.isEmpty())
                throw new IllegalArgumentException("Must specify a prefix when creating multiple patchsets in a single output");
            if (sets.stream().map(e -> e.prefix).anyMatch(prefix::equals))
                throw new IllegalArgumentException("Invalid duplicate prefix " + prefix);
        }
        if (prefix != null && prefix.isEmpty())
            throw new IllegalArgumentException("Invalid empty prefix");

        sets.add(new PatchSet(clean, dirty, prefix));
        return this;
    }

    public Generator pack200() {
        return this.pack200(true);
    }

    public Generator pack200(boolean value) {
        this.pack200 = value;
        return this;
    }

    public Generator legacy() {
        return this.legacy(true);
    }

    public Generator legacy(boolean value) {
        this.legacy = value;
        return this;
    }

    public void loadMappings(File srg) throws IOException {
        IMappingFile map = IMappingFile.load(srg);
        map.getClasses().forEach(cls -> {
            o2m.put(cls.getOriginal(), cls.getMapped());
            m2o.put(cls.getOriginal(), cls.getMapped());
        });
    }

    public void loadPatches(File root) throws IOException {
        int base = root.getAbsolutePath().length();
        int suffix = ".java.patch".length();
        Files.walk(root.toPath()).filter(Files::isRegularFile).map(p -> p.toAbsolutePath().toString()).filter(p -> p.endsWith(".java.patch")).forEach(path -> {
            String relative = path.substring(base+1).replace('\\', '/');
            patches.add(relative.substring(0, relative.length() - suffix));
        });
    }

    public void create() throws IOException {
        Map<String, byte[]> binpatches = new TreeMap<>();
        for (PatchSet set : sets) {
            Map<String, byte[]> tmp = gatherPatches(set.clean, set.dirty);
            if (set.prefix == null)
                binpatches.putAll(tmp);
            else
                tmp.forEach((key,value) -> binpatches.put(set.prefix + '/' + key, value));
        }

        byte[] data = createJar(binpatches);
        if (pack200)
            data = pack200(data);
        data = lzma(data);
        try (FileOutputStream fos = new FileOutputStream(output)) {
            fos.write(data);
        }
    }

    private Map<String, byte[]> gatherPatches(File clean, File dirty) throws IOException {
        Map<String, byte[]> binpatches = new TreeMap<>();
        try (ZipFile zclean = new ZipFile(clean);
            ZipFile zdirty = new ZipFile(dirty)){

            Map<String, Set<String>> entries = new HashMap<>();
            Collections.list(zclean.entries()).stream().map(e -> e.getName()).filter(e -> e.endsWith(".class")).map(e -> e.substring(0, e.length() - 6)).forEach(e -> {
                int idx = e.indexOf('$');
                if (idx != -1)
                    entries.computeIfAbsent(e.substring(0, idx), k -> new HashSet<>()).add(e);
                else
                    entries.computeIfAbsent(e, k -> new HashSet<>()).add(e);
            });
            Collections.list(zdirty.entries()).stream().map(e -> e.getName()).filter(e -> e.endsWith(".class")).map(e -> e.substring(0, e.length() - 6)).forEach(e -> {
                int idx = e.indexOf('$');
                if (idx != -1)
                    entries.computeIfAbsent(e.substring(0, idx), k -> new HashSet<>()).add(e);
                else
                    entries.computeIfAbsent(e, k -> new HashSet<>()).add(e);
            });

            log("Creating patches:");
            log("  Clean: " + clean);
            log("  Dirty: " + dirty);
            if (patches.isEmpty()) { //No patches, assume full set!
                for (String cls : entries.keySet()) {
                   String srg = m2o.getOrDefault(cls, cls);
                    byte[] cleanData = getData(zclean, cls);
                    byte[] dirtyData = getData(zdirty, cls);
                    if (!Arrays.equals(cleanData, dirtyData)) {
                        byte[] patch = process(cls, srg, cleanData, dirtyData);
                        if (patch != null)
                            binpatches.put(toJarName(srg), patch);
                    }
                }
            } else {
                for (String path : patches) {
                    String obf = o2m.getOrDefault(path, path);
                    if (entries.containsKey(obf)) {
                        for (String cls : entries.get(obf)) {
                            String srg = m2o.get(cls);
                            if (srg == null) {
                                int idx = cls.indexOf('$');
                                srg = path + '$' + cls.substring(idx + 1);
                            }

                            byte[] cleanData = getData(zclean, cls);
                            byte[] dirtyData = getData(zdirty, cls);
                            if (!Arrays.equals(cleanData, dirtyData)) {
                                byte[] patch = process(cls, srg, cleanData, dirtyData);
                                if (patch != null)
                                    binpatches.put(toJarName(srg), patch);
                            }
                        }
                    } else {
                        log("  Failed: no source for patch? " + path + " " + obf);
                    }
                }
            }
        }
        return binpatches;
    }

    // public for testing
    public String toJarName(String original) {
        return original.replace('/', '.') + ".binpatch";
    }

    private byte[] getData(ZipFile zip, String cls) throws IOException {
        ZipEntry entry = zip.getEntry(cls + ".class");
        return entry == null ? EMPTY_DATA : Util.toByteArray(zip.getInputStream(entry));
    }
    // public for testing
    public byte[] createJar(Map<String, byte[]> patches) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream zout = new JarOutputStream(out)) {
            zout.setLevel(Deflater.NO_COMPRESSION); //Don't deflate-compress, otherwise LZMA won't be as effective
            for (Entry<String, byte[]> e : patches.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(ConsoleTool.ZIPTIME);
                zout.putNextEntry(entry);
                zout.write(e.getValue());
                zout.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private byte[] pack200(byte[] data) throws IOException {
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Packer packer = Pack200.newPacker();

            SortedMap<String, String> props = packer.properties();
            props.put(Packer.EFFORT, "9");
            props.put(Packer.KEEP_FILE_ORDER, Packer.TRUE);
            props.put(Packer.UNKNOWN_ATTRIBUTE, Packer.PASS);

            final PrintStream err = new PrintStream(System.err);
            System.setErr(new PrintStream(NULL));
            packer.pack(in, out);
            System.setErr(err);

            out.flush();

            byte[] ret = out.toByteArray();
            log("Pack: " + data.length + " -> " + ret.length);
            return ret;
        }
    }

    // public for testing
    public byte[] lzma(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (LzmaOutputStream lzma = new LzmaOutputStream.Builder(out).useEndMarkerMode(true).build()) {
            lzma.write(data);
        }
        byte[] ret = out.toByteArray();
        log("LZMA: " + data.length + " -> " + ret.length);
        return ret;
    }

    private byte[] process(String obf, String srg, byte[] clean, byte[] dirty) throws IOException {
        if (srg.equals(obf))
            log("  Processing " + srg);
        else
            log("  Processing " + srg + "(" + obf + ")");

        Patch patch = Patch.from(obf, srg, clean, dirty);
        log("    Clean: " + Integer.toHexString(patch.checksum(clean)) + " Dirty: " + Integer.toHexString(patch.checksum(dirty)));
        return patch.toBytes(this.legacy);
    }

    private void log(String message) {
        ConsoleTool.log(message);
    }

    private static class PatchSet {
        private final String prefix;
        private final File clean;
        private final File dirty;

        private PatchSet(File clean, File dirty, String prefix) {
            this.clean = clean;
            this.dirty = dirty;
            this.prefix = prefix;
        }
    }

    private static OutputStream NULL = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
        }
    };
}
