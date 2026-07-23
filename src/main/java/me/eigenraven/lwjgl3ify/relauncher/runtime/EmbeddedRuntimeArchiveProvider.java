package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Materializes only the selected primary runtime archive embedded in the release mod JAR. */
public final class EmbeddedRuntimeArchiveProvider {

    public static final String RESOURCE_PREFIX = "/me/eigenraven/lwjgl3ify/relauncher/runtime/embedded/";
    private static final Set<String> PRIMARY_PLATFORM_IDS = Collections.unmodifiableSet(
        new LinkedHashSet<String>(Arrays.asList("windows-x86_64", "linux-x86_64", "macos-x86_64", "macos-aarch64")));

    public boolean isPrimaryPlatform(String platformId) {
        return PRIMARY_PLATFORM_IDS.contains(platformId);
    }

    public boolean isAvailable(RuntimePlatform platform) {
        if (platform == null || !isPrimaryPlatform(platform.getId())) return false;
        try (InputStream input = EmbeddedRuntimeArchiveProvider.class.getResourceAsStream(resourcePath(platform))) {
            return input != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    public RuntimeBundleLocator.Result materialize(RuntimeManifest manifest, RuntimePlatform platform, Path cacheRoot)
        throws IOException, RuntimeInstallationException {
        if (manifest == null || platform == null || cacheRoot == null) {
            throw new NullPointerException("manifest, platform, and cacheRoot are required");
        }
        if (!isPrimaryPlatform(platform.getId())) {
            throw new RuntimeInstallationException(
                "Platform is not part of the embedded primary set: " + platform.getId());
        }
        String resourcePath = resourcePath(platform);
        Path normalizedCacheRoot = cacheRoot.toAbsolutePath()
            .normalize();
        Files.createDirectories(normalizedCacheRoot);
        if (Files.isSymbolicLink(normalizedCacheRoot)) {
            throw new RuntimeInstallationException("Runtime cache root must not be a symbolic link: " + cacheRoot);
        }
        Path realCacheRoot = normalizedCacheRoot.toRealPath();
        Path sourceDirectory = createSafeDirectoryChain(
            realCacheRoot,
            realCacheRoot.resolve("runtime-sources")
                .resolve("embedded")
                .resolve(manifest.getRuntimeFamily())
                .resolve(platform.getSha256()));
        Path destination = sourceDirectory.resolve(fileName(platform));
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            RuntimeArchiveIntegrity.verify(destination, platform, "Cached embedded runtime archive");
            return directResult(platform, destination);
        }

        Path temporary = sourceDirectory.resolve("." + fileName(platform) + ".tmp-" + UUID.randomUUID());
        try (InputStream resource = EmbeddedRuntimeArchiveProvider.class.getResourceAsStream(resourcePath)) {
            if (resource == null) {
                throw new RuntimeInstallationException("Embedded runtime resource is missing: " + resourcePath);
            }
            RuntimeArchiveIntegrity.copyVerified(resource, temporary, platform, "Embedded runtime archive");
            publish(temporary, destination);
            RuntimeArchiveIntegrity.verify(destination, platform, "Published embedded runtime archive");
        } finally {
            Files.deleteIfExists(temporary);
        }
        return directResult(platform, destination);
    }

    static String resourcePath(RuntimePlatform platform) {
        return RESOURCE_PREFIX + platform.getNormalizedBundlePath();
    }

    private static RuntimeBundleLocator.Result directResult(RuntimePlatform platform, Path destination) {
        return RuntimeBundleLocator.Result.directArchive(
            RuntimeBundleLocator.Source.EMBEDDED_JAR,
            destination,
            "Embedded Java runtime selected for " + platform.getId());
    }

    private static String fileName(RuntimePlatform platform) {
        return "lwjgl3ify-wdg-java21-" + platform.getId() + "." + platform.getArchiveType();
    }

    private static Path createSafeDirectoryChain(Path root, Path destination)
        throws IOException, RuntimeInstallationException {
        Path normalizedRoot = root.toAbsolutePath()
            .normalize();
        Path normalizedDestination = destination.toAbsolutePath()
            .normalize();
        if (!normalizedDestination.startsWith(normalizedRoot)) {
            throw new RuntimeInstallationException("Embedded runtime source path escapes the managed cache root");
        }
        Path current = normalizedRoot;
        for (Path segment : normalizedRoot.relativize(normalizedDestination)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new RuntimeInstallationException("Embedded runtime source directory is unsafe: " + current);
                }
            } else {
                Files.createDirectory(current);
            }
        }
        return normalizedDestination;
    }

    private static void publish(Path temporary, Path destination) throws IOException {
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            try {
                Files.move(temporary, destination);
            } catch (FileAlreadyExistsException concurrent) {
                Files.deleteIfExists(temporary);
            }
        } catch (FileAlreadyExistsException concurrent) {
            Files.deleteIfExists(temporary);
        }
    }
}
