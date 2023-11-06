/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.binarypatcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Pack200;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.nothome.delta.GDiffPatcher;

import lzma.sdk.lzma.Decoder;
import lzma.streams.LzmaInputStream;

public class Patcher {
    public static final String EXTENSION = ".lzma";
    private static final byte[] EMPTY_DATA = new byte[0];
    private static final GDiffPatcher PATCHER = new GDiffPatcher();

    private Map<String, List<Patch>> patches = new TreeMap<>();

    private final File clean;
    private final File output;
    private boolean keepData = false;
    private boolean patchedOnly = false;
    private boolean pack200 = false;
    private boolean legacy = false;

    public Patcher(File clean, File output) {
        this.clean = clean;
        this.output = output;
    }

    public Patcher keepData(boolean value) {
        this.keepData = value;
        return this;
    }

    public Patcher includeUnpatched(boolean value) {
        this.patchedOnly = !value;
        return this;
    }

    public Patcher pack200() {
        return pack200(true);
    }

    public Patcher pack200(boolean value) {
        this.pack200 = value;
        return this;
    }

    public Patcher legacy() {
        return this.legacy(true);
    }

    public Patcher legacy(boolean value) {
        this.legacy = value;
        return this;
    }

    // This can be called multiple times, if patchsets are built on top of eachother.
    // They will be applied in the order that the patch files were loaded.
    public void loadPatches(File file, String prefix) throws IOException {
        log("Loading patches file: " + file);

        try (InputStream input = new FileInputStream(file)) {
            InputStream stream = new LzmaInputStream(input, new Decoder());

            if (pack200) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (JarOutputStream jos = new JarOutputStream(bos)) {
                    Pack200.newUnpacker().unpack(stream, jos);
                }
                stream = new ByteArrayInputStream(bos.toByteArray());
            }

            JarInputStream jar = new JarInputStream(stream);

            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith(".binpatch") && (prefix == null || name.startsWith(prefix + '/'))) {
                    log("  Reading patch " + entry.getName());
                    Patch patch = Patch.from(jar, this.legacy);
                    log("    Checksum: " + Integer.toHexString(patch.checksum) + " Exists: " + patch.exists);
                    patches.computeIfAbsent(patch.obf, k -> new ArrayList<>()).add(patch);
                }
            }
        }
    }

    public void process() throws IOException {
        log("Processing: " + clean);
        if (output.exists() && !output.delete())
            throw new IOException("Failed to delete existing output file: " + output);


        try (ZipInputStream zclean = new ZipInputStream(new FileInputStream(clean));
             ZipOutputStream zpatched = new ZipOutputStream(new FileOutputStream(output))) {

            Set<String> processed = new HashSet<>();
            ZipEntry entry;
            while ((entry = zclean.getNextEntry()) != null) {
                if (entry.getName().endsWith(".class")) {
                    String key = entry.getName().substring(0, entry.getName().length() - 6); //String .class
                    List<Patch> patchlist  = patches.get(key);
                    if (patchlist != null) {
                        processed.add(key);
                        byte[] data = Util.toByteArray(zclean);
                        for (int x = 0; x < patchlist.size(); x++) {
                            Patch patch = patchlist.get(x);
                            log("  Patching " + patch.getName() + " " + (x+1) + "/" + patchlist.size());
                            data = patch(data, patch);
                        }
                        if (data.length != 0) {
                            zpatched.putNextEntry(getNewEntry(entry.getName()));
                            zpatched.write(data);
                        }
                    } else if (!patchedOnly) {
                        log("  Copying " + entry.getName());
                        zpatched.putNextEntry(getNewEntry(entry.getName()));
                        Util.copy(zclean, zpatched);
                    }
                } else if (keepData) {
                    log("  Copying " + entry.getName());
                    zpatched.putNextEntry(getNewEntry(entry.getName()));
                    Util.copy(zclean, zpatched);
                }
            }

            // Add new files
            for (Entry<String, List<Patch>> e : patches.entrySet()) {
                String key = e.getKey();
                List<Patch> patchlist = e.getValue();

                if (processed.contains(key))
                    continue;

                byte[] data = new byte[0];
                for (int x = 0; x < patchlist.size(); x++) {
                    Patch patch = patchlist.get(x);
                    log("  Patching " + patch.getName() + " " + (x+1) + "/" + patchlist.size());
                    data = patch(data, patch);
                }
                if (data.length != 0) {
                    zpatched.putNextEntry(getNewEntry(key + ".class"));
                    zpatched.write(data);
                }
            }
         }
    }

    private byte[] patch(byte[] data, Patch patch) throws IOException {
        if (patch.exists && data.length == 0)
            throw new IOException("Patch expected " + patch.getName() + " to exist, but received empty data");
        if (!patch.exists && data.length > 0)
            throw new IOException("Patch expected " + patch.getName() + " to not exist, but received " + data.length + " bytes");

        int checksum = patch.checksum(data);
        if (checksum != patch.checksum)
            throw new IOException("Patch expected " + patch.getName() + " to have the checksum " + Integer.toHexString(patch.checksum) + " but it was " + Integer.toHexString(checksum));

        if (patch.data.length == 0) //File removed
            return EMPTY_DATA;
        else
            return PATCHER.patch(data, patch.data);
    }

    private ZipEntry getNewEntry(String name) {
        ZipEntry ret = new ZipEntry(name);
        ret.setTime(ConsoleTool.ZIPTIME);
        return ret;
    }

    private void log(String message) {
        System.out.println(message);
    }

    // Public for testing
    public Map<String, List<Patch>> getPatches() {
        Map<String, List<Patch>> ret = new HashMap<>();
        patches.forEach((k,v) ->
            ret.computeIfAbsent(k, a -> new ArrayList<>()).addAll(v)
        );
        return ret;
    }

}
