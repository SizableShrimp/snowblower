/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.snowblower;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import net.minecraftforge.snowblower.util.DependencyHashCache;
import net.minecraftforge.snowblower.util.Util;
import net.minecraftforge.srgutils.MinecraftVersion;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class Main {
    private static final MinecraftVersion V1_14_4 = MinecraftVersion.from("1.14.4");

    public static void main(String[] args) throws IOException, GitAPIException, URISyntaxException {
        OptionParser parser = new OptionParser();
        OptionSpec<File> outputO = parser.accepts("output", "Output directory to put the git directory in").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> cacheO = parser.accepts("cache", "Cache directory to hold all files related to a version. If omitted, goes to ./cache.")
                .withRequiredArg().ofType(File.class);
        OptionSpec<File> extraMappingsO = parser.accepts("extra-mappings", "When set, points to a directory with extra mappings files").withRequiredArg().ofType(File.class);
        OptionSpec<String> startVerO = parser.accepts("start-ver", "The starting Minecraft version to generate from (inclusive)").withRequiredArg().ofType(String.class).defaultsTo(V1_14_4.toString());
        OptionSpec<String> targetVerO = parser.accepts("target-ver", "The target Minecraft version to generate up to (inclusive). If omitted, defaults to \"latest\" while respecting --releases-only")
                .withRequiredArg().ofType(String.class).defaultsTo("latest");
        OptionSpec<String> branchNameO = parser.acceptsAll(List.of("branch-name", "branch"), "The Git branch name, creating an orphan branch if it does not exist. Uses checked out branch if omitted")
                .withRequiredArg().ofType(String.class);
        OptionSpec<Void> releasesOnlyO = parser.accepts("releases-only", "When set, only release versions will be considered");
        OptionSpec<Void> startOverO = parser.accepts("start-over", "Whether to start over by deleting the target branch");

        OptionSet options;
        try {
            options = parser.parse(args);
        } catch (OptionException ex) {
            System.err.println("Error: " + ex.getMessage());
            System.err.println();
            parser.printHelpOn(System.err);
            System.exit(1);
            return;
        }

        File output = options.valueOf(outputO);
        File cache = options.valueOf(cacheO);
        Path cachePath = cache == null ? Paths.get("cache"): cache.toPath();
        File extraMappings = options.valueOf(extraMappingsO);
        Path extraMappingsPath = extraMappings == null ? null : extraMappings.toPath();
        boolean startOver = options.has(startOverO);
        MinecraftVersion startVer = MinecraftVersion.from(options.valueOf(startVerO));
        // if (startVer.compareTo(V1_14_4) < 0)
        //     throw new IllegalArgumentException("Start version must be greater than or equal to 1.14.4");
        String targetVerStr = options.valueOf(targetVerO);
        MinecraftVersion targetVer = targetVerStr.equalsIgnoreCase("latest") ? null : MinecraftVersion.from(targetVerStr);
        if (targetVer != null && targetVer.compareTo(startVer) < 0)
            throw new IllegalArgumentException("Target version must be greater than or equal to start version");
        String branchName = options.valueOf(branchNameO);
        boolean releasesOnly = options.has(releasesOnlyO);
        URL depHashCacheUrl = Main.class.getResource("/dependency_hashes.txt");
        if (depHashCacheUrl == null)
            throw new IllegalStateException("Could not find dependency_hashes.txt on classpath");
        DependencyHashCache depCache = DependencyHashCache.load(Util.getPath(depHashCacheUrl.toURI()));

        new Generator(output.toPath(), cachePath, extraMappingsPath, startVer, targetVer, branchName, startOver, releasesOnly, depCache, System.out::println).run();
    }
}
