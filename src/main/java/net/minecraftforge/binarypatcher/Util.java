/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.binarypatcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class Util {
    public static byte[] toByteArray(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int nRead;
        byte[] data = new byte[256];

        while ((nRead = stream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        buffer.flush();
        return buffer.toByteArray();
    }

    public static void copy(InputStream source, OutputStream target) throws IOException {
        byte[] buf = new byte[256];
        int length;
        while ((length = source.read(buf)) != -1) {
            target.write(buf, 0, length);
        }
    }

    public static void store(ZipOutputStream out, String name, byte[] data) throws IOException {
        ZipEntry entry = getNewEntry(name);
        entry.setSize(data.length);
        entry.setCrc(crc32(data));
        out.putNextEntry(entry);
        out.write(data);
    }

    private static long crc32(byte[] data) {
        CRC32 crc32 = new CRC32();
        crc32.update(data);
        return crc32.getValue();
    }

    public static boolean isSignature(String name) {
        if (!name.startsWith("META-INF/"))
            return false;

        return name.endsWith(".RSA") || name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".EC");
    }

    public static void cleanManifest(ZipInputStream zin, ZipOutputStream zout, String name, boolean store) throws IOException {
        byte[] data = Util.toByteArray(zin);

        final Manifest manifest = new Manifest(new ByteArrayInputStream(data));
        boolean modified = false;
        for (final Iterator<Map.Entry<String, Attributes>> it = manifest.getEntries().entrySet().iterator(); it.hasNext();) {
            final Map.Entry<String, Attributes> section = it.next();
            for (final Iterator<Map.Entry<Object, Object>> attrIter = section.getValue().entrySet().iterator(); attrIter.hasNext();) {
                final Map.Entry<Object, Object> attribute = attrIter.next();
                final String key = attribute.getKey().toString().toLowerCase(Locale.ROOT);
                if (key.endsWith("-digest")) { // assume that this is a signature entry
                    attrIter.remove();
                    modified = true;
                }
            }

            if (section.getValue().isEmpty())
                it.remove();
        }

        if (modified) {
            try (final ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                manifest.write(os);
                data = os.toByteArray();
            }
        }

        if (store)
            store(zout, name, data);
        else {
            zout.putNextEntry(getNewEntry(name));
            zout.write(data);
        }
    }

    private static ZipEntry getNewEntry(String name) {
        ZipEntry ret = new ZipEntry(name);
        ret.setTime(ConsoleTool.ZIPTIME);
        return ret;
    }
}
