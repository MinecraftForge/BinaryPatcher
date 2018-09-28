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
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

public class ConsoleTool
{
    public static void main(String[] args) throws IOException
    {
        OptionParser parser = new OptionParser();
        // Shared arguments
        OptionSpec<File> clean = parser.accepts("clean").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> output = parser.accepts("output").withRequiredArg().ofType(File.class).required();

        // Create arguments
        OptionSpec<File> create = parser.accepts("create").withRequiredArg().ofType(File.class);
        OptionSpec<File> patches = parser.accepts("patches").withRequiredArg().ofType(File.class);
        OptionSpec<File> srg = parser.accepts("srg").withRequiredArg().ofType(File.class);

        // Apply arguments
        OptionSpec<File> apply = parser.accepts("apply").withRequiredArg().ofType(File.class);
        OptionSpec<Void> data = parser.accepts("data");
        OptionSpec<Void> unpatched = parser.accepts("unpatched");

        try {
            OptionSet options = parser.parse(args);

            File clean_jar = options.valueOf(clean);
            File output_jar = options.valueOf(output);

            if (options.has(create) && options.has(apply)) {
                System.out.println("Cannot specify --apply and --create at the same time!");
                return;
            }

            if (options.has(create)) {
                if (options.has(data)) {
                    System.out.println("Connot specify --data and --create at the same time!");
                    return;
                }
                if (options.has(unpatched)) {
                    System.out.println("Connot specify --unpatched and --create at the same time!");
                    return;
                }

                File dirty_jar = options.valueOf(create);
                Generator gen = new Generator(clean_jar, dirty_jar, output_jar);
                log("Generating: ");
                log("  Clean:   " + clean_jar);
                log("  Dirty:   " + dirty_jar);
                log("  Output:  " + output_jar);

                if (options.has(patches)) {
                    for(File dir : options.valuesOf(patches)) {
                        log("  Patches: " + dir);
                        gen.loadPatches(dir);
                    }
                }

                if (options.has(srg)) {
                    for(File file : options.valuesOf(srg)) {
                        log("  SRG:     " + file);
                        gen.loadMappings(file);
                    }
                }

                gen.create();
            } else if (options.has(apply)) {

                if (options.has(srg)) {
                    System.out.println("Connot specify --srg and --apply at the same time!");
                    return;
                }
                if (options.has(patches)) {
                    System.out.println("Connot specify --patches and --apply at the same time!");
                    return;
                }

                Patcher patcher = new Patcher(clean_jar, output_jar);
                log("Applying: ");
                log("  Clean:     " + clean_jar);
                log("  Output:    " + output_jar);
                log("  KeepData:  " + options.has(data));
                patcher.keepData(options.has(data));
                log("  Unpatched: " + options.has(unpatched));
                patcher.includeUnpatched(options.has(unpatched));

                for(File file : options.valuesOf(apply)) {
                    log("  Patches:   " + file);
                    patcher.loadPatches(file);
                }
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
}
