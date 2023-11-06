/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.binarypatcher;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.Adler32;

import com.nothome.delta.Delta;

public class Patch {
    private static final byte[] EMPTY_DATA = new byte[0];
    private static final Delta DELTA = new Delta();

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

    public static Patch from(String obf, String srg, byte[] clean, byte[] dirty) throws IOException {
        byte[] diff = dirty.length == 0 ? EMPTY_DATA : DELTA.compute(clean, dirty);
        int checksum = clean.length == 0 ? 0 : adlerHash(clean);
        return new Patch(obf, srg, clean.length != 0, checksum, diff);
    }

    public byte[] toBytes() {
        return toBytes(false);
    }
    public byte[] toBytes(boolean legacy) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length + obf.length() + srg.length() + 1);
        DataOutputStream out = new DataOutputStream(bos);
        try {
            if (legacy) {
                if (data.length == 0)
                    return null; //Legacy doesn't support deleting, so just skip
                out.writeUTF(obf);
                out.writeUTF(obf.replace('/', '.'));
                out.writeUTF(srg.replace('/', '.'));
            } else {
                out.writeByte(1); //Version -- Future compatibility
                out.writeUTF(obf);
                out.writeUTF(srg);
            }
            out.writeBoolean(exists); //Exists in clean
            if (exists)
                out.writeInt(checksum); //Adler32
            out.writeInt(data.length); //If removed, diff.length == 0
            out.write(data);
            out.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }

    public static Patch from(InputStream stream) throws IOException {
        return from(stream, false);
    }
    public static Patch from(InputStream stream, boolean legacy) throws IOException {
        DataInputStream input = new DataInputStream(stream);
        int version = -1;
        String obf, srg;

        if (legacy) {
            obf = input.readUTF();
            input.readUTF(); //Useless repeat of obf
            srg = input.readUTF().replace('.', '/');
        } else {
            version = input.readByte() & 0xFF;
            if (version != 1)
                throw new IOException("Unsupported patch format: " + version);
            obf = input.readUTF();
            srg = input.readUTF();
        }

        boolean exists = input.readBoolean();
        int checksum = exists ? input.readInt() : 0;
        int length = input.readInt();
        byte[] data = new byte[length];
        input.readFully(data);

        return new Patch(obf, srg, exists, checksum, data);
    }

    public String getName() {
        if (srg.equals(obf))
            return srg;
        else
            return srg + "(" + obf + ")";
    }

    public int checksum(byte[] data) {
        return data.length == 0 ? 0 : adlerHash(data); //This is a instance method so we can check the version and do the proper hash, for now just adler
    }

    private static int adlerHash(byte[] input) {
        Adler32 hasher = new Adler32();
        hasher.update(input);
        return (int)hasher.getValue();
    }
}
