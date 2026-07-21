package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Explicit, startup-independent installer for one platform from a normalized Java runtime bundle.
 * No host detection, config mutation, UI integration, Java execution, or relaunch occurs here.
 */
public final class RuntimeInstaller {

    private final RuntimeManifest configuredManifest;

    public RuntimeInstaller() {
        this.configuredManifest = null;
    }

    RuntimeInstaller(RuntimeManifest manifest) {
        this.configuredManifest = manifest;
    }

    private static final long LOCK_WAIT_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final long LOCK_SLEEP_MILLIS = 200L;
    private static final ConcurrentHashMap<String, ReentrantLock> PROCESS_LOCKS = new ConcurrentHashMap<String, ReentrantLock>();

    public RuntimeInstallResult install(Path normalizedBundle, String platformId, Path cacheRoot)
        throws IOException, RuntimeInstallationException {
        if (normalizedBundle == null || platformId == null || cacheRoot == null) {
            throw new NullPointerException("normalizedBundle, platformId, and cacheRoot are required");
        }
        RuntimeManifest manifest = configuredManifest == null ? RuntimeManifest.loadCanonical() : configuredManifest;
        RuntimePlatform platform = manifest.getPlatform(platformId);
        Path normalizedCacheRoot = cacheRoot.toAbsolutePath()
            .normalize();
        Files.createDirectories(normalizedCacheRoot);
        if (Files.isSymbolicLink(normalizedCacheRoot)) {
            throw new RuntimeInstallationException(
                "Explicit runtime cache root must not be a symbolic link: " + cacheRoot);
        }
        Path realCacheRoot = normalizedCacheRoot.toRealPath();
        RuntimeCacheLayout layout = new RuntimeCacheLayout(realCacheRoot, manifest, platform);
        createSafeDirectoryChain(
            layout.getCacheRoot(),
            layout.getInstallationRoot()
                .getParent());

        String processKey = layout.getInstallationRoot()
            .toAbsolutePath()
            .normalize()
            .toString();
        ReentrantLock processLock = PROCESS_LOCKS.get(processKey);
        if (processLock == null) {
            ReentrantLock candidate = new ReentrantLock();
            ReentrantLock prior = PROCESS_LOCKS.putIfAbsent(processKey, candidate);
            processLock = prior == null ? candidate : prior;
        }
        try {
            processLock.lockInterruptibly();
        } catch (InterruptedException exception) {
            Thread.currentThread()
                .interrupt();
            throw new RuntimeInstallationException(
                "Interrupted while waiting for in-process runtime lock for " + platformId
                    + " at "
                    + layout.getInstallationRoot(),
                exception);
        }
        try {
            return installWithOperatingSystemLock(normalizedBundle, manifest, platform, layout);
        } finally {
            processLock.unlock();
            if (!processLock.hasQueuedThreads()) PROCESS_LOCKS.remove(processKey, processLock);
        }
    }

    private RuntimeInstallResult installWithOperatingSystemLock(Path normalizedBundle, RuntimeManifest manifest,
        RuntimePlatform platform, RuntimeCacheLayout layout) throws IOException, RuntimeInstallationException {
        if (Files.isSymbolicLink(layout.getLockFile())) {
            throw new RuntimeInstallationException(
                "Runtime installation lock file must not be a symbolic link: " + layout.getLockFile());
        }
        FileChannel channel = FileChannel
            .open(layout.getLockFile(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock lock = null;
        try {
            lock = acquireFileLock(channel, platform, layout);
            RuntimeInstallResult existing = validateInstallation(
                layout.getInstallationRoot(),
                manifest,
                platform,
                false);
            if (existing != null) return existing;

            cleanupStaleWorkingPaths(layout);
            Path quarantined = quarantineInvalidFinal(layout);
            Path parent = layout.getInstallationRoot()
                .getParent();
            String token = UUID.randomUUID()
                .toString();
            Path staging = parent.resolve("." + platform.getSha256() + ".staging-" + token);
            Path selectedArchive = parent.resolve("." + platform.getSha256() + ".archive-" + token + ".tmp");
            boolean published = false;
            try {
                Files.createDirectory(staging);
                new RuntimeBundleReader().verifyAndCopySelectedArchive(
                    normalizedBundle.toAbsolutePath()
                        .normalize(),
                    manifest,
                    platform,
                    selectedArchive);
                new RuntimeArchiveExtractor().extract(selectedArchive, staging, platform);
                RuntimeInstallResult stagedResult = validateExtractedFiles(staging, manifest, platform, true);
                RuntimeInstallMarker.create(manifest, platform)
                    .writeSafely(staging);
                RuntimeInstallMarker.read(staging.resolve(RuntimeInstallMarker.FILE_NAME))
                    .validate(manifest, platform);
                publish(staging, layout.getInstallationRoot());
                published = true;
                RuntimeInstallResult finalResult = validateInstallation(
                    layout.getInstallationRoot(),
                    manifest,
                    platform,
                    true);
                if (finalResult == null) {
                    throw new RuntimeInstallationException("Published runtime failed final validation");
                }
                if (quarantined != null) {
                    RuntimePathSafety.deleteRecursively(quarantined, layout.getRuntimesRoot());
                }
                return new RuntimeInstallResult(
                    true,
                    finalResult.getInstallationRoot(),
                    finalResult.getRuntimeArchiveRoot(),
                    finalResult.getJavaHome(),
                    finalResult.getJavaExecutable(),
                    finalResult.getWindowsGuiExecutable(),
                    finalResult.getPlatformId(),
                    finalResult.getRuntimeVersion(),
                    finalResult.getArchiveSha256());
            } finally {
                Files.deleteIfExists(selectedArchive);
                if (!published && Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                    RuntimePathSafety.deleteRecursively(staging, layout.getRuntimesRoot());
                }
            }
        } finally {
            if (lock != null && lock.isValid()) lock.release();
            channel.close();
        }
    }

    private static FileLock acquireFileLock(FileChannel channel, RuntimePlatform platform, RuntimeCacheLayout layout)
        throws IOException, RuntimeInstallationException {
        long deadline = System.nanoTime() + LOCK_WAIT_NANOS;
        while (true) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException ignored) {
                // The in-process lock prevents this normally; treating it as busy is safest.
            }
            if (System.nanoTime() >= deadline) {
                throw new RuntimeInstallationException(
                    "Timed out waiting for runtime installation lock for " + platform.getId()
                        + " at "
                        + layout.getInstallationRoot());
            }
            try {
                Thread.sleep(LOCK_SLEEP_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread()
                    .interrupt();
                throw new RuntimeInstallationException(
                    "Interrupted while waiting for runtime installation lock for " + platform.getId()
                        + " at "
                        + layout.getInstallationRoot(),
                    exception);
            }
        }
    }

    private static void publish(Path staging, Path finalPath) throws IOException, RuntimeInstallationException {
        if (Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new RuntimeInstallationException(
                "Refusing to merge runtime staging into existing final directory: " + finalPath);
        }
        try {
            Files.move(staging, finalPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            if (Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new RuntimeInstallationException(
                    "Final runtime path appeared before fallback move: " + finalPath);
            }
            Files.move(staging, finalPath);
        }
    }

    private static Path quarantineInvalidFinal(RuntimeCacheLayout layout)
        throws IOException, RuntimeInstallationException {
        Path finalPath = layout.getInstallationRoot();
        if (!Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)) return null;
        Path quarantine = finalPath.getParent()
            .resolve(
                "." + finalPath.getFileName()
                    + ".invalid-"
                    + UUID.randomUUID()
                        .toString());
        try {
            Files.move(finalPath, quarantine, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(finalPath, quarantine);
        }
        return quarantine;
    }

    private static void cleanupStaleWorkingPaths(RuntimeCacheLayout layout)
        throws IOException, RuntimeInstallationException {
        Path parent = layout.getInstallationRoot()
            .getParent();
        if (!Files.isDirectory(parent)) return;
        String stagingPrefix = "." + layout.getInstallationRoot()
            .getFileName() + ".staging-";
        String archivePrefix = "." + layout.getInstallationRoot()
            .getFileName() + ".archive-";
        String invalidPrefix = "." + layout.getInstallationRoot()
            .getFileName() + ".invalid-";
        DirectoryStream<Path> stream = Files.newDirectoryStream(parent);
        try {
            for (Path child : stream) {
                String name = child.getFileName()
                    .toString();
                if (name.startsWith(stagingPrefix) || name.startsWith(archivePrefix)
                    || name.startsWith(invalidPrefix)) {
                    RuntimePathSafety.deleteRecursively(child, layout.getRuntimesRoot());
                }
            }
        } finally {
            stream.close();
        }
    }

    private static RuntimeInstallResult validateInstallation(Path installationRoot, RuntimeManifest manifest,
        RuntimePlatform platform, boolean failLoudly) throws IOException, RuntimeInstallationException {
        if (!Files.isDirectory(installationRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(installationRoot))
            return null;
        try {
            RuntimeInstallMarker marker = RuntimeInstallMarker
                .read(installationRoot.resolve(RuntimeInstallMarker.FILE_NAME));
            marker.validate(manifest, platform);
            return validateExtractedFiles(installationRoot, manifest, platform, false);
        } catch (RuntimeInstallationException exception) {
            if (failLoudly) throw exception;
            return null;
        } catch (IOException exception) {
            if (failLoudly) throw exception;
            return null;
        }
    }

    private static RuntimeInstallResult validateExtractedFiles(Path installationRoot, RuntimeManifest manifest,
        RuntimePlatform platform, boolean beforeMarker) throws IOException, RuntimeInstallationException {
        Path normalizedInstall = installationRoot.toAbsolutePath()
            .normalize();
        Path archiveRoot = RuntimePathSafety
            .resolveContained(normalizedInstall, platform.getArchiveRoot(), "runtime archive root");
        if (!Files.isDirectory(archiveRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(archiveRoot)) {
            throw new RuntimeInstallationException(
                "Expected runtime archive root is missing or unsafe: " + archiveRoot);
        }
        int rootEntries = 0;
        DirectoryStream<Path> roots = Files.newDirectoryStream(normalizedInstall);
        try {
            for (Path child : roots) {
                String name = child.getFileName()
                    .toString();
                if (!name.equals(RuntimeInstallMarker.FILE_NAME)
                    && !name.equals(RuntimeInstallMarker.FILE_NAME + ".tmp")) {
                    rootEntries++;
                    if (!child.equals(archiveRoot)) {
                        throw new RuntimeInstallationException(
                            "Unexpected entry beside runtime archive root: " + child);
                    }
                }
            }
        } finally {
            roots.close();
        }
        if (rootEntries != 1)
            throw new RuntimeInstallationException("Installation must contain exactly one runtime archive root");

        Path archiveRootReal = archiveRoot.toRealPath();
        Path javaHome = resolveWithinArchiveRoot(archiveRoot, platform.getJavaHomeRelativePath(), "Java home");
        if (!Files.isDirectory(javaHome, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(javaHome)) {
            throw new RuntimeInstallationException("Java home is missing or unsafe: " + javaHome);
        }
        requireRealContainment(archiveRootReal, javaHome, "Java home");
        Path release = resolveWithinArchiveRoot(javaHome, platform.getReleaseFileRelativePath(), "release file");
        requireRegularNonLink(release, "release file");
        requireRealContainment(archiveRootReal, release, "release file");
        Map<String, String> properties = readReleaseProperties(release);
        for (Map.Entry<String, String> expected : platform.getExpectedReleaseProperties()
            .entrySet()) {
            String actual = properties.get(expected.getKey());
            if (!expected.getValue()
                .equals(actual)) {
                throw new RuntimeInstallationException(
                    "Release property mismatch for " + expected
                        .getKey() + "; expected " + expected.getValue() + " but was " + actual);
            }
        }
        Path javaExecutable = resolveWithinArchiveRoot(
            javaHome,
            platform.getJavaExecutableRelativePath(),
            "Java executable");
        requireRegularNonLink(javaExecutable, "Java executable");
        requireRealContainment(archiveRootReal, javaExecutable, "Java executable");
        if (!platform.isWindows() && !Files.isExecutable(javaExecutable)) {
            throw new RuntimeInstallationException("Unix Java executable is not executable: " + javaExecutable);
        }
        Path gui = null;
        if (platform.getWindowsGuiExecutableRelativePath() != null) {
            gui = resolveWithinArchiveRoot(
                javaHome,
                platform.getWindowsGuiExecutableRelativePath(),
                "Windows GUI Java executable");
            requireRegularNonLink(gui, "Windows GUI Java executable");
            requireRealContainment(archiveRootReal, gui, "Windows GUI Java executable");
        }
        return new RuntimeInstallResult(
            false,
            normalizedInstall,
            archiveRoot,
            javaHome,
            javaExecutable,
            gui,
            platform.getId(),
            manifest.getJavaRuntimeVersion(),
            platform.getSha256());
    }

    private static void createSafeDirectoryChain(Path root, Path target)
        throws IOException, RuntimeInstallationException {
        Path normalizedRoot = root.toAbsolutePath()
            .normalize();
        Path normalizedTarget = target.toAbsolutePath()
            .normalize();
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            throw new RuntimeInstallationException("Runtime cache directory escapes explicit cache root: " + target);
        }
        Path current = normalizedRoot;
        Path relative = normalizedRoot.relativize(normalizedTarget);
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException ignored) {
                    // A concurrent installer may have created the same deterministic directory.
                }
            }
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new RuntimeInstallationException(
                    "Runtime cache path component is not a safe directory: " + current);
            }
        }
        Path verified = normalizedRoot;
        for (Path segment : relative) {
            verified = verified.resolve(segment);
            if (Files.isSymbolicLink(verified)) {
                throw new RuntimeInstallationException(
                    "Runtime cache path component became a symbolic link: " + verified);
            }
        }
    }

    private static void requireRealContainment(Path realRoot, Path path, String description)
        throws IOException, RuntimeInstallationException {
        Path real = path.toRealPath();
        if (!real.startsWith(realRoot)) {
            throw new RuntimeInstallationException(
                description + " resolves outside the installed runtime root: " + path);
        }
    }

    private static Path resolveWithinArchiveRoot(Path root, String relative, String context)
        throws RuntimeInstallationException {
        if (".".equals(relative)) return root.toAbsolutePath()
            .normalize();
        return RuntimePathSafety.resolveContained(root, relative, context);
    }

    private static void requireRegularNonLink(Path path, String description) throws RuntimeInstallationException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new RuntimeInstallationException(
                description + " is missing, not regular, or is a symbolic link: " + path);
        }
    }

    private static Map<String, String> readReleaseProperties(Path release)
        throws IOException, RuntimeInstallationException {
        Map<String, String> values = new HashMap<String, String>();
        BufferedReader reader = Files.newBufferedReader(release, StandardCharsets.UTF_8);
        try {
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null) {
                lines++;
                if (lines > 10_000) throw new RuntimeInstallationException("Release file has too many lines");
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int equals = trimmed.indexOf('=');
                if (equals <= 0) throw new RuntimeInstallationException("Malformed release file line: " + trimmed);
                String key = trimmed.substring(0, equals)
                    .trim();
                String value = trimmed.substring(equals + 1)
                    .trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
                }
                if (values.put(key, value) != null) {
                    throw new RuntimeInstallationException("Duplicate release property: " + key);
                }
            }
        } finally {
            reader.close();
        }
        return Collections.unmodifiableMap(values);
    }
}
