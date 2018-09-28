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

import java.io.DataInputStream;
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
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.zip.Adler32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.IOUtils;

import com.nothome.delta.GDiffPatcher;

import lzma.sdk.lzma.Decoder;
import lzma.streams.LzmaInputStream;

public class Patcher {
    public static final String EXTENSION = ".lzma";
    private static final byte[] EMPTY_DATA = new byte[0];
    private static final GDiffPatcher PATCHER = new GDiffPatcher();

    private Map<String, List<Patch>> patches = new HashMap<>();

    private final File clean;
    private final File output;
    private boolean keepData = false;
    private boolean patchedOnly = false;

    public Patcher(File clean, File output) {
        this.clean = clean;
        this.output = output;
    }

    public void keepData(boolean value) {
        this.keepData = value;
    }

    public void includeUnpatched(boolean value) {
        this.patchedOnly = !value;
    }

    // This can be called multiple times, if patchsets are built on top of eachother.
    // They will be applied in the order that the patch files were loaded.
    public void loadPatches(File file) throws IOException {
        log("Loading patches file: " + file);


        try (InputStream input = new FileInputStream(file)) {
            LzmaInputStream decompressed = new LzmaInputStream(input, new Decoder());
            JarInputStream jar = new JarInputStream(decompressed);

            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                if (entry.getName().endsWith(".binpatch")) {
                    log("  Reading patch " + entry.getName());
                    Patch patch = Patch.from(jar);
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
                        byte[] data = IOUtils.toByteArray(zclean);
                        for (int x = 0; x < patchlist.size(); x++) {
                            Patch patch = patchlist.get(x);
                            log("  Patching " + patch.srg + "(" + key + ") " + (x+1) + "/" + patchlist.size());
                            data = patch(data, patch);
                        }
                        if (data.length != 0) {
                            zpatched.putNextEntry(getNewEntry(entry));
                            zpatched.write(data);
                        }
                    } else if (!patchedOnly) {
                        log("  Copying " + entry.getName());
                        zpatched.putNextEntry(getNewEntry(entry));
                        IOUtils.copy(zclean, zpatched);
                    }
                } else if (keepData) {
                    log("  Copying " + entry.getName());
                    zpatched.putNextEntry(getNewEntry(entry));
                    IOUtils.copy(zclean, zpatched);
                }
            }
         }
    }

    private byte[] patch(byte[] data, Patch patch) throws IOException {
        if (patch.exists && data.length == 0)
            throw new IOException("Patch expected " + patch.srg + "(" + patch.obf + ") to exist, but received empty data");
        if (!patch.exists && data.length > 0)
            throw new IOException("Patch expected " + patch.srg + "(" + patch.obf + ") to not exist, but received " + data.length + " bytes");

        int checksum = adlerHash(data);
        if (checksum != patch.checksum)
            throw new IOException("Patch expected " + patch.srg + "(" + patch.obf + ") to have the checksum " + Integer.toHexString(patch.checksum) + " but it was " + Integer.toHexString(checksum));

        if (patch.data.length == 0) //File removed
            return EMPTY_DATA;
        else
            return PATCHER.patch(data, patch.data);
    }

    private ZipEntry getNewEntry(ZipEntry old) {
        ZipEntry ret = new ZipEntry(old.getName());
        ret.setTime(0);
        return ret;
    }
    private int adlerHash(byte[] input) {
        Adler32 hasher = new Adler32();
        hasher.update(input);
        return (int)hasher.getValue();
    }

    private void log(String message) {
        System.out.println(message);
    }

    private static class Patch {
        private static Patch from(InputStream stream) throws IOException {
            DataInputStream input = new DataInputStream(stream);
            int version = input.readInt();
            if (version != 1)
                throw new IOException("Unsupported patch format: " + version);
            String obf = input.readUTF();
            String srg = input.readUTF();
            boolean exists = input.readBoolean();
            int checksum = exists ? input.readInt() : 0;
            int length = input.readInt();
            byte[] data = new byte[length];
            input.readFully(data);

            return new Patch(obf, srg, exists, checksum, data);
        }

        public final String obf; //TODO: Getters if I care...
        public final String srg;
        public final boolean exists;
        public final int checksum;
        public final byte[] data;

        private Patch(String obf, String srg, boolean exists, int checksum, byte[] data) {
            this.obf = obf;
            this.srg = srg;
            this.exists = exists;
            this.checksum = checksum;
            this.data = data;
        }
    }

}
