package net.neoforged.snowblower.tasks;

import net.neoforged.snowblower.data.Version;
import net.neoforged.snowblower.util.Cache;
import net.neoforged.snowblower.util.DependencyHashCache;
import net.neoforged.snowblower.util.Tools;
import net.neoforged.snowblower.util.Util;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public class DecompileTask {
    private static final Logger LOGGER = LoggerFactory.getLogger(DecompileTask.class);
    public static final List<String> DECOMPILE_ARGS = List.of(
            // For comparison, see NeoForm parameters for 26.1-snapshot-1 here:
            // https://github.com/neoforged/NeoForm/blob/64142f5933f3e68a0d73abc60f1672b1ec90d17a/settings.gradle#L36-L51
            "--decompile-inner",
            "--remove-bridge",
            "--decompile-generics",
            "--ascii-strings",
            "--remove-synthetic",
            "--include-classpath",
            "--ignore-invalid-bytecode",
            "--bytecode-source-mapping",
            "--indent-string=    ",
            "--dump-code-lines");

    public static void decompileJar(Path cache, List<Path> libs, Path dedupedJar,
            @Nullable Path duplicatesJar, Path outputJar) throws IOException {
        LOGGER.debug("Decompiling joined.jar");

        var cfg = cache.resolve("joined-libraries.cfg");

        var libsStream = libs.stream();
        if (duplicatesJar != null)
            libsStream = Stream.concat(libsStream, Stream.of(duplicatesJar));
        Util.writeLines(cfg, libsStream.map(l -> "-e=" + l.toString()).toArray(String[]::new));

        ConsoleDecompiler.main(Stream.concat(DECOMPILE_ARGS.stream(), Stream.of(
                "-log=ERROR", // IFernflowerLogger.Severity
                "-cfg", cfg.toString(),
                dedupedJar.toString(),
                outputJar.toString()
        )).toArray(String[]::new));
    }

    public static Cache getKey(Version version, Path joined, Path libCache, List<Path> libs,
            DependencyHashCache depCache, boolean partialCache) throws IOException {
        var key = new Cache()
                .put(Tools.VINEFLOWER, depCache)
                .put(Tools.VINEFLOWER_PLUGINS, depCache)
                .put("joined", joined);

        if (partialCache) {
            key.put("downloads-client", version.downloads().get("client").sha1());
            key.put("downloads-client_mappings", version.downloads().get("client_mappings").sha1());
            key.put("downloads-server", version.downloads().get("server").sha1());
            key.put("downloads-server", version.downloads().get("server_mappings").sha1());
        }

        key.put("decompileArgs", String.join(" ", DECOMPILE_ARGS));

        for (var lib : libs) {
            var relative = libCache.relativize(lib);
            key.put(relative.toString(), lib);
        }

        return key;
    }
}
