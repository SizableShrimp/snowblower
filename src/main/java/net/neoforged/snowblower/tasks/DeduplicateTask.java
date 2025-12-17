package net.neoforged.snowblower.tasks;

import net.neoforged.snowblower.util.Cache;
import net.neoforged.snowblower.util.DependencyHashCache;
import net.neoforged.snowblower.util.HashFunction;
import net.neoforged.snowblower.util.Tools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipException;

/**
 * Minimize decompilation by skipping decompilation of classes that are unchanged,
 * by taking the SHA1 hash of the class bytes as a key in a big lookup.
 */
public class DeduplicateTask {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeduplicateTask.class);
    private static final String CLASS_MAP_FILENAME = "classmap.jar";

    /**
     * @return a deduplication result, including the deduplicated jar, a corresponding jar of all removed duplicate entries,
     * and a decompiled jar of all removed duplicate entries.
     * Together, the first 2 jars exactly partition the entries of the original jar.
     * If the class map does not exist, only the deduplicate jar will exist, and the other 2 entries will be {@code null}.
     */
    public static DeduplicationResult deduplicateJar(Path rootCache, Path versionCache, Path inputJar, DependencyHashCache depCache) throws IOException {
        var key = getKey(depCache);

        Path dedupedJar = versionCache.resolve(inputJar.getFileName().toString().replace(".jar", "-deduplicated.jar"));
        Path dupesJar = versionCache.resolve(inputJar.getFileName().toString().replace(".jar", "-duplicates.jar"));
        Path dupesDecompJar = versionCache.resolve(inputJar.getFileName().toString().replace(".jar", "-duplicates-decompiled.jar"));
        Path classMapJar = rootCache.resolve(CLASS_MAP_FILENAME);
        Path keyF = rootCache.resolve(CLASS_MAP_FILENAME + ".cache");

        if (!Files.exists(classMapJar))
            return new DeduplicationResult(inputJar, null, null);

        if (!key.isValid(keyF)) {
            // Invalidate the class map if Vineflower or the decompilation args changed
            Files.delete(classMapJar);

            return new DeduplicationResult(inputJar, null, null);
        }

        try (var classMapFs = FileSystems.newFileSystem(classMapJar)) {} catch (ZipException ignored) {
            // Invalidate the class map if the zip is corrupted
            Files.delete(classMapJar);

            return new DeduplicationResult(inputJar, null, null);
        }

        LOGGER.debug("Deduplicating class files present in global class map");

        Files.deleteIfExists(dedupedJar);
        Files.deleteIfExists(dupesJar);
        Files.deleteIfExists(dupesDecompJar);
        int reused = 0;
        int toDecompile = 0;

        try (var classMapFs = FileSystems.newFileSystem(classMapJar);
                var inputFs = FileSystems.newFileSystem(inputJar);
                var dedupedFs = FileSystems.newFileSystem(dedupedJar, Map.of("create", true));
                var dupesFs = FileSystems.newFileSystem(dupesJar, Map.of("create", true));
                var dupesDecompFs = FileSystems.newFileSystem(dupesDecompJar, Map.of("create", true))) {
            Map<String, List<Path>> topLevelClasses = new HashMap<>();

            try (var walker = Files.walk(inputFs.getPath("/"))) {
                Iterable<Path> iterable = () -> walker.filter(Files::isRegularFile).iterator();

                for (Path path : iterable) {
                    Path filename = path.getFileName();
                    if (!filename.toString().endsWith(".class")) {
                        copyToFs(path, dedupedFs);
                        continue;
                    }

                    populateClasses(path, topLevelClasses);
                }
            }

            for (var entry : topLevelClasses.entrySet()) {
                String topLevelClassname = entry.getKey();
                List<Path> thisAndSubClasses = entry.getValue();
                String javaPath = topLevelClassname + ".java";
                String javaFilename = javaPath.substring(javaPath.lastIndexOf('/') + 1);

                String sha1 = HashFunction.SHA1.hashPaths(thisAndSubClasses);

                // TODO: Delete this
                if ("0a1ce03ee295bf554611de1c5ece06535036547b".equals(sha1)) {
                    boolean b = false;
                }

                // TODO: The class reusing is not working for 1.21.11_unobfuscated <-> 26.1-snapshot-1
                if ("DismountOrSkipMounting.java".equals(javaFilename)) {
                    boolean b = false;
                }

                Path cachedPath = getCachedPath(classMapFs, sha1, javaFilename);

                if (Files.exists(cachedPath)) {
                    for (Path path : thisAndSubClasses)
                        copyToFs(path, dupesFs);
                    copyTo(cachedPath, dupesDecompFs.getPath(javaPath));
                    reused++;
                } else {
                    for (Path path : thisAndSubClasses)
                        copyToFs(path, dedupedFs);
                    toDecompile++;
                }
            }
        }

        LOGGER.debug("Finished deduplication; reused {} top-level classes, need to decompile {}", reused, toDecompile);

        return new DeduplicationResult(dedupedJar, dupesJar, dupesDecompJar);
    }

    private static void populateClasses(Path path, Map<String, List<Path>> topLevelClasses) {
        String fullClassname = path.toString();
        fullClassname = fullClassname.substring(0, fullClassname.length() - ".class".length());

        int dollarIdx = fullClassname.indexOf('$');
        String topLevelClassname = dollarIdx == -1 ? fullClassname : fullClassname.substring(0, dollarIdx);

        topLevelClasses.computeIfAbsent(topLevelClassname, k -> new ArrayList<>()).add(path);
    }

    public static void cacheDecompilation(Path rootCache, Path compiledJar, Path decompiledJar, DependencyHashCache depCache) throws IOException {
        Files.createDirectories(rootCache);

        var key = getKey(depCache);
        Path classMapJar = rootCache.resolve(CLASS_MAP_FILENAME);
        Path keyF = rootCache.resolve(CLASS_MAP_FILENAME + ".cache");
        int totalCached = 0;

        try (var classMapFs = FileSystems.newFileSystem(classMapJar, Map.of("create", true));
                var compiledFs = FileSystems.newFileSystem(compiledJar);
                var decompFs = FileSystems.newFileSystem(decompiledJar)) {
            Map<String, List<Path>> topLevelClasses = new HashMap<>();

            try (var walker = Files.walk(compiledFs.getPath("/"))) {
                Iterable<Path> iterable = () -> walker.filter(Files::isRegularFile).iterator();

                for (Path path : iterable) {
                    Path filename = path.getFileName();
                    if (!filename.toString().endsWith(".class"))
                        continue;

                    populateClasses(path, topLevelClasses);
                }
            }

            for (var entry : topLevelClasses.entrySet()) {
                String topLevelClassname = entry.getKey();
                List<Path> thisAndSubClasses = entry.getValue();
                String javaPathStr = topLevelClassname + ".java";
                Path javaPath = decompFs.getPath(javaPathStr);

                if (!Files.exists(javaPath))
                    continue;

                String javaFilename = javaPathStr.substring(javaPathStr.lastIndexOf('/') + 1);
                String sha1 = HashFunction.SHA1.hashPaths(thisAndSubClasses);

                // TODO: Delete this
                if ("DismountOrSkipMounting.java".equals(javaFilename)) {
                    boolean b = false;
                }

                Path cachedPath = getCachedPath(classMapFs, sha1, javaFilename);

                copyTo(javaPath, cachedPath);
                totalCached++;
            }
        }

        LOGGER.debug("Cached {} decompiled files to global class map", totalCached);

        key.write(keyF);
    }

    public static void merge(Path decompJar, DeduplicationResult dedupeResult) throws IOException {
        if (dedupeResult.duplicatesDecompiledJar() == null || !Files.exists(dedupeResult.duplicatesDecompiledJar()))
            return;

        try (var decompFs = FileSystems.newFileSystem(decompJar);
                var dupesDecompFs = FileSystems.newFileSystem(dedupeResult.duplicatesDecompiledJar())) {
            try (var walker = Files.walk(dupesDecompFs.getPath("/"))) {
                Iterable<Path> iterable = () -> walker.filter(Files::isRegularFile).iterator();

                for (Path path : iterable) {
                    copyToFs(path, decompFs);
                }
            }
        }
    }

    private static Path getCachedPath(FileSystem classMapFs, String sha1, String javaFilename) {
        return classMapFs.getPath("/" + sha1 + "/" + javaFilename);
    }

    private static void copyToFs(Path source, FileSystem fs) throws IOException {
        copyTo(source, fs.getPath(source.toString()));
    }

    private static void copyTo(Path source, Path output) throws IOException {
        Files.createDirectories(output.getParent());

        Files.copy(source, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static Cache getKey(DependencyHashCache depCache) {
        return new Cache()
                .put(Tools.VINEFLOWER, depCache)
                .put(Tools.VINEFLOWER_PLUGINS, depCache)
                .put("decompileArgs", String.join(" ", DecompileTask.DECOMPILE_ARGS));
    }

    public record DeduplicationResult(Path deduplicatedJar, Path duplicatesJar, Path duplicatesDecompiledJar) {}
}
