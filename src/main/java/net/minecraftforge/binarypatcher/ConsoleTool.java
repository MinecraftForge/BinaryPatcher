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

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

public class ConsoleTool {
    public static final long ZIPTIME = 628041600000L;
    public static void main(String[] args) throws IOException {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT")); //Fix Java stupidity that causes timestamps in zips to depend on user's timezone!
        OptionParser parser = new OptionParser();
        // Shared arguments
        OptionSpec<File> cleanO = parser.accepts("clean").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> outputO = parser.accepts("output").withRequiredArg().ofType(File.class).required();
        OptionSpec<String> prefixO = parser.accepts("prefix").withRequiredArg();
        OptionSpec<Void> packO = parser.accepts("pack200");
        OptionSpec<Void> legacyO = parser.accepts("legacy", "Uses the legacy patch header format, also implies --pack200. NOT RECOMENDED.");

        // Create arguments
        OptionSpec<File> createO = parser.acceptsAll(Arrays.asList("dirty", "create")).withRequiredArg().ofType(File.class);
        OptionSpec<File> patchesO = parser.accepts("patches").withRequiredArg().ofType(File.class);
        OptionSpec<File> srgO = parser.accepts("srg").withRequiredArg().ofType(File.class);

        // Apply arguments
        OptionSpec<File> applyO = parser.accepts("apply").withRequiredArg().ofType(File.class);
        OptionSpec<Void> dataO = parser.accepts("data");
        OptionSpec<Void> unpatchedO = parser.accepts("unpatched");

        try {
            OptionSet options = parser.parse(args);

            File output = options.valueOf(outputO).getAbsoluteFile();
            boolean legacy = options.has(legacyO);
            boolean pack200 = legacy || options.has(packO);

            if (output.exists() && !output.delete())
                err("Could not delete output file: " + output);

            if (!output.getParentFile().exists() && !output.getParentFile().mkdirs())
                err("Could not make output folders: " + output.getParentFile());

            if (options.has(createO) && options.has(applyO))
                err("Cannot specify --apply and --create at the same time!");

            if (options.has(createO)) {
                if (options.has(dataO))      err("Connot specify --create/--dirty and --data at the same time!");
                if (options.has(unpatchedO)) err("Connot specify --create/--dirty and --unpatched at the same time!");

                List<File> clean = options.valuesOf(cleanO);
                List<File> dirty = options.valuesOf(createO);
                List<String> prefixes = options.valuesOf(prefixO);

                log("Generating: ");
                log("  Output:  " + output);
                log("  Pack200: " + pack200);
                log("  Legacy:    " + legacy);

                Generator gen = new Generator(output).pack200(pack200).legacy(legacy);

                if (clean.size() > 1 || dirty.size() > 1 || prefixes.size() > 1) {
                    if (clean.size() != dirty.size() || dirty.size() != prefixes.size()) {
                        log("When specifying multiple patchsets, you must have the same number of --clean, --dirty, and --prefix arguments");
                        int max = Math.max(clean.size(), Math.max(dirty.size(), prefixes.size()));
                        for (int x = 0; x < max; x++) {
                            log("Set #" + x + ':');
                            log("  Prefix: " + (x < prefixes.size() ? prefixes.get(x) : null));
                            log("  Clean:  " + (x < clean.size() ? clean.get(x) : null));
                            log("  Dirty:  " + (x < dirty.size() ? dirty.get(x) : null));
                        }
                        err("Unbalanced patchset arguments, see log for details");
                    }

                    for (int x = 0; x < clean.size(); x++) {
                        log("  " + prefixes.get(x));
                        log("    Clean: " + clean.get(x));
                        log("    Dirty: " + dirty.get(x));
                        gen.addSet(clean.get(x), dirty.get(x), prefixes.get(x));
                    }
                } else {
                    if (!prefixes.isEmpty()) {
                        log("  " + prefixes.get(0));
                        log("    Clean: " + clean.get(0));
                        log("    Dirty: " + dirty.get(0));
                        gen.addSet(clean.get(0), dirty.get(0), prefixes.get(0));
                    } else {
                        log("  Clean: " + clean.get(0));
                        log("  Dirty: " + dirty.get(0));
                        gen.addSet(clean.get(0), dirty.get(0), null);
                    }
                }

                if (options.has(patchesO)) {
                    for (File dir : options.valuesOf(patchesO)) {
                        log("  Patches: " + dir);
                        gen.loadPatches(dir);
                    }
                }

                if (options.has(srgO)) {
                    for (File file : options.valuesOf(srgO)) {
                        log("  SRG:     " + file);
                        gen.loadMappings(file);
                    }
                }

                gen.create();
            } else if (options.has(applyO)) {
                File clean_jar = options.valueOf(cleanO);

                if (options.has(srgO))     err("Connot specify --apply and --srg at the same time!");
                if (options.has(patchesO)) err("Connot specify --apply and --patches at the same time!");

                Patcher patcher = new Patcher(clean_jar, output)
                    .keepData(options.has(dataO))
                    .includeUnpatched(options.has(unpatchedO))
                    .pack200(pack200)
                    .legacy(legacy);

                log("Applying: ");
                log("  Clean:     " + clean_jar);
                log("  Output:    " + output);
                log("  KeepData:  " + options.has(dataO));
                log("  Unpatched: " + options.has(unpatchedO));
                log("  Pack200:   " + pack200);
                log("  Legacy:    " + legacy);

                List<File> patches = options.valuesOf(applyO);
                List<String> prefixes = options.valuesOf(prefixO);

                if (!prefixes.isEmpty() && patches.size() != prefixes.size())
                    err("Patches and prefixes arguments must be paird if they are used together. Use NULL to specify an empty prefix.");

                for (int x = 0; x < patches.size(); x++)
                    patcher.loadPatches(patches.get(x), x >= prefixes.size() || "NULL".equals(prefixes.get(x)) ? null : prefixes.get(x));

                patcher.process();

            } else {
                parser.printHelpOn(System.out);
            }
        } catch (OptionException e) {
            parser.printHelpOn(System.out);
            e.printStackTrace();
        }
    }

    public static void log(String message) {
        System.out.println(message);
    }
    public static void err(String message) {
        System.out.println(message);
        throw new IllegalStateException(message);
    }
}
