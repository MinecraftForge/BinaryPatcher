/*
 * BinaryPatcher
 * Copyright (c) 2016-2018.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package net.minecraftforge.binarypatcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Map.Entry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.io.IOUtils;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import joptsimple.internal.Strings;
import lzma.streams.LzmaOutputStream;

public class Generator {
    public static final String EXTENSION = ".lzma";
    private static final byte[] EMPTY_DATA = new byte[0];

    private final BiMap<String, String> classes = HashBiMap.create();
    private final Set<String> patches = new TreeSet<>();

    private final File clean;
    private final File dirty;
    private final File output;


    public Generator(File clean, File dirty, File output) {
        this.clean = clean;
        this.dirty = dirty;
        this.output = output;
    }

    public void loadMappings(File srg) throws IOException {
        List<String> lines = com.google.common.io.Files.readLines(srg, StandardCharsets.UTF_8).stream()
                .map(line -> line.split("#")[0]).filter(l -> !Strings.isNullOrEmpty(l.trim())).collect(Collectors.toList()); //Strip comments and empty lines
        lines.stream()
        .filter(line -> !line.startsWith("\t") || (line.indexOf(':') != -1 && line.startsWith("CL:"))) // Class lines only
        .map(line -> line.indexOf(':') != -1 ? line.substring(4).split(" ") : line.split(" ")) //Convert to: OBF SRG
        .filter(pts -> pts.length == 2 && !pts[0].endsWith("/")) //Skip packages
        .forEach(pts -> classes.put(pts[0], pts[1]));
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
        try (ZipFile zclean = new ZipFile(clean);
             ZipFile zdirty = new ZipFile(dirty)){

            log("Gathering class names");
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

            log("Creating patches");
            if (patches.isEmpty()) { //No patches, assume full set!
                for (String cls : entries.keySet()) {
                    String srg = classes.inverse().getOrDefault(cls, cls);
                    byte[] clean = getData(zclean, cls);
                    byte[] dirty = getData(zdirty, cls);
                    if (!Arrays.equals(clean, dirty)) {
                        byte[] patch = process(cls, srg, clean, dirty);
                        binpatches.put(srg.replace('/', '.') + ".binpatch", patch);
                    }
                }
            } else {
                for (String path : patches) {
                    String obf = classes.getOrDefault(path, path);
                    if (entries.containsKey(obf)) {
                        for (String cls : entries.get(obf)) {
                            String srg = classes.inverse().get(cls);
                            if (srg == null) {
                                int idx = cls.indexOf('$');
                                srg = path + '$' + cls.substring(idx + 1);
                            }

                            byte[] clean = getData(zclean, cls);
                            byte[] dirty = getData(zdirty, cls);
                            if (!Arrays.equals(clean, dirty)) {
                                byte[] patch = process(cls, srg, clean, dirty);
                                binpatches.put(srg.replace('/', '.') + ".binpatch", patch);
                            }
                        }
                    } else {
                        log("Failed: no source for patch? " + path + " " + obf);
                    }
                }
            }
        }

        byte[] data = createJar(binpatches);
        data = lzma(data);
        try (FileOutputStream fos = new FileOutputStream(output)) {
            IOUtils.write(data, fos);
        }
    }

    private byte[] getData(ZipFile zip, String cls) throws IOException {
        ZipEntry entry = zip.getEntry(cls + ".class");
        return entry == null ? EMPTY_DATA : IOUtils.toByteArray(zip.getInputStream(entry));
    }
    private byte[] createJar(Map<String, byte[]> patches) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream zout = new JarOutputStream(out)) {
            for (Entry<String, byte[]> e : patches.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(0);
                zout.putNextEntry(entry);
                zout.write(e.getValue());
                zout.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private byte[] lzma(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (LzmaOutputStream lzma = new LzmaOutputStream.Builder(out).useEndMarkerMode(true).build()) {
            lzma.write(data);
        }
        return out.toByteArray();
    }

    private byte[] process(String obf, String srg, byte[] clean, byte[] dirty) throws IOException {
        if (srg.equals(obf))
            log("Processing " + srg);
        else
            log("Processing " + srg + "(" + obf + ")");

        Patch patch = Patch.from(obf, srg, clean, dirty);
        log("  Clean: " + Integer.toHexString(patch.checksum(clean)) + " Dirty: " + Integer.toHexString(patch.checksum(dirty)));
        return patch.toBytes();
    }

    private void log(String message) {
        ConsoleTool.log(message);
    }

}
