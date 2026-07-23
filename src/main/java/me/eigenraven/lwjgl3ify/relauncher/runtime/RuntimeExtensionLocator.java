package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Resolves one exact optional architecture archive from the modpack root. */
public final class RuntimeExtensionLocator {

    public static final String CANONICAL_DIRECTORY = "lwjgl3ify/runtime/extensions";

    public RuntimeBundleLocator.Result locate(Path gameDirectory, RuntimePlatform platform) {
        if (gameDirectory == null || platform == null) {
            throw new NullPointerException("gameDirectory and platform are required");
        }
        Path path = gameDirectory.toAbsolutePath()
            .normalize()
            .resolve(CANONICAL_DIRECTORY)
            .resolve(canonicalFileName(platform))
            .normalize();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            || !Files.isReadable(path)) {
            return new RuntimeBundleLocator.Result(
                RuntimeBundleLocator.Status.INVALID_OVERRIDE,
                RuntimeBundleLocator.Source.EXTENSION_DIRECTORY,
                RuntimeBundleLocator.Kind.DIRECT_ARCHIVE,
                path,
                "Optional Java runtime extension is missing, unreadable, or unsafe: " + path);
        }
        return RuntimeBundleLocator.Result.directArchive(
            RuntimeBundleLocator.Source.EXTENSION_DIRECTORY,
            path,
            "Optional Java runtime extension selected for " + platform.getId());
    }

    public static String canonicalFileName(RuntimePlatform platform) {
        return "lwjgl3ify-wdg-java21-" + platform.getId() + "." + platform.getArchiveType();
    }
}
