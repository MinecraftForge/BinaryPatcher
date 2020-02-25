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
package net.minecraftforge.binarypatcher.tests;

import net.minecraftforge.binarypatcher.Generator;
import net.minecraftforge.binarypatcher.Patch;
import net.minecraftforge.binarypatcher.Patcher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.powermock.reflect.Whitebox;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * This test uses example data from the client.lzma of the forge 1.15.2-31.0.14 installer jar
 */
public class BinPatchGeneratorTest {
    private static File exampleFile;
    private static Map<String, byte[]> examplePatches;

    /**
     * Loads example data and sets the timezone to produce identical conditions
     */
    @BeforeAll
    public static void setup() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT"));
        exampleFile = new File("src/test/resources/example_data.lzma");
        Assertions.assertTrue(exampleFile.exists(), "Example data missing!");
        examplePatches = loadPatches(exampleFile);
    }

    private static Map<String, byte[]> loadPatches(File file) throws Exception {
        Patcher patcher = new Patcher(null, null); //To read to example patches
        Whitebox.setInternalState(patcher, "patches", new TreeMap<>()); //Set to a tree map to preserve order to rebuild an identical file
        Whitebox.invokeMethod(patcher, "loadPatches", file, null);
        Map<String, List<Patch>> patches = Whitebox.getInternalState(patcher, "patches");
        Map<String, byte[]> patchesProcessed = new TreeMap<>(); //preserve order
        for (Map.Entry<String, List<Patch>> entry : patches.entrySet()) {
            List<Patch> patchesForEntry = entry.getValue();
            Assertions.assertEquals(1, patchesForEntry.size(), "For this set, it should be one patch per class!");
            patchesProcessed.put(entry.getKey(), patchesForEntry.get(0).toBytes()); //add binpatch extension for each file
        }
        Assertions.assertEquals(patches.size(), patchesProcessed.size());
        return patchesProcessed;
    }

    private static byte[] writePatches(Map<String, byte[]> patches) throws Exception {
        Map<String, byte[]> sortedMap = new TreeMap<>(); //preserve order
        Generator generator = new Generator(null); //dummy for writing patches
        for (Map.Entry<String, byte[]> entry : patches.entrySet())
            sortedMap.put(Whitebox.invokeMethod(generator, "toJarName", entry.getKey()), entry.getValue());
        byte[] jarData = Whitebox.invokeMethod(generator, "createJar", sortedMap);
        return Whitebox.invokeMethod(generator, "lzma", (Object) jarData);
    }

    /**
     * Validates the binpachtes are still written/read correctly
     */
    @Test
    public void testReadWrite() throws Exception {
        //Write example patches to file
        byte[] compressedPatches = writePatches(examplePatches);
        Path path = Files.createTempFile("binpachter-tests", ".lzma");
        Files.write(path, compressedPatches);

        //Try and read the written patches from disk
        Map<String, byte[]> newPatches = loadPatches(path.toFile());

        //Validate they didn't change
        System.out.println("Validating patches");
        Assertions.assertEquals(examplePatches.size(), newPatches.size(), "Wrong count of patch entries!");
        for (Map.Entry<String, byte[]> entry : examplePatches.entrySet()) {
            String name = entry.getKey();
            byte[] inOldFile = entry.getValue();
            byte[] inNewFile = newPatches.get(name);
            Assertions.assertNotNull(inNewFile, name + " is missing in new patch set!");
            Assertions.assertArrayEquals(inOldFile, inNewFile, "Unpacked patches differ!");
        }
    }

    /**
     * Validates the new file is compressed at least as strong as before
     */
    @Test
    public void testCompression() throws Exception {
        byte[] processedData = writePatches(examplePatches);
        System.out.println("Old size: " + exampleFile.length());
        System.out.println("New size: " + processedData.length);
        Assertions.assertTrue(exampleFile.length() >= processedData.length, "New data is bigger than old data!");
    }
}
