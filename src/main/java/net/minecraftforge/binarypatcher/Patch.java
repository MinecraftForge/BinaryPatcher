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
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.Adler32;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
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
        ByteArrayDataOutput out = ByteStreams.newDataOutput(data.length + obf.length() + srg.length() + 1);
        out.writeInt(1); //Version -- Future compatibility
        out.writeUTF(obf); //Obf Name
        out.writeUTF(srg); //SRG Name
        if (exists) {
            out.writeBoolean(false); //Exists in clean
        } else {
            out.writeBoolean(true); //Exists in clean
            out.writeInt(checksum); //Adler32
        }
        out.writeInt(data.length); //If removed, diff.length == 0
        out.write(data);
        return out.toByteArray();
    }

    public static Patch from(InputStream stream) throws IOException {
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
