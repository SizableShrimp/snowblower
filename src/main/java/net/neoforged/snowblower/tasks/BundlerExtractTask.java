/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.snowblower.tasks;

import net.neoforged.installertools.BundlerExtract;
import net.neoforged.snowblower.util.Cache;
import net.neoforged.snowblower.util.DependencyHashCache;
import net.neoforged.snowblower.util.Tools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

public class BundlerExtractTask {
    private static final Logger LOGGER = LoggerFactory.getLogger(BundlerExtractTask.class);
    private static final Attributes.Name FORMAT = new Attributes.Name("Bundler-Format");

    public static Path getExtractedServerJar(Path cache, Path serverJar, DependencyHashCache depCache) throws IOException {
        try (FileSystem fs = FileSystems.newFileSystem(serverJar, Map.of())) {
            Path mfp = fs.getPath("META-INF", "MANIFEST.MF");
            if (!Files.exists(mfp))
                return serverJar; // We assume already extracted

            Manifest mf;
            try (InputStream is = Files.newInputStream(mfp)) {
                mf = new Manifest(is);
            }

            String format = mf.getMainAttributes().getValue(FORMAT);
            if (format == null)
                return serverJar; // We assume already extracted
        }

        var key = new Cache()
                .put(Tools.INSTALLERTOOLS, depCache)
                .put("server", serverJar);
        var keyF = cache.resolve("server-extracted.jar.cache");
        var extractedServerJar = cache.resolve("server-extracted.jar");

        if (!Files.exists(extractedServerJar) || !key.isValid(keyF)) {
            LOGGER.debug("Extracting server jar");
            var stdout = System.out;
            try (var ps = new PrintStream(OutputStream.nullOutputStream())) {
                System.setOut(ps); // Turn off installertools log output

                new BundlerExtract().process(new String[]{"--input", serverJar.toString(), "--output", extractedServerJar.toString(), "--jar-only"});
            } finally {
                System.setOut(stdout);
            }

            key.write(keyF);
        }

        return extractedServerJar;
    }
}
