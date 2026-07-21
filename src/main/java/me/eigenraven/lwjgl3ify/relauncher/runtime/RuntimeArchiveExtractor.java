package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/** Secure extractor for the two canonical nested runtime archive types. */
final class RuntimeArchiveExtractor {

    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final int MAX_ENTRIES = 10_000;
    private static final long MAX_UNCOMPRESSED_BYTES = 4L * 1024L * 1024L * 1024L;
    private static final int MAX_LINK_BYTES = 16 * 1024;

    void extract(Path archive, Path stagingRoot, RuntimePlatform platform)
        throws IOException, RuntimeInstallationException {
        ExtractionState state = new ExtractionState(stagingRoot, platform);
        InputStream raw = new BufferedInputStream(Files.newInputStream(archive), BUFFER_SIZE);
        try {
            if ("zip".equals(platform.getArchiveType())) {
                ZipArchiveInputStream zip = new ZipArchiveInputStream(raw, StandardCharsets.UTF_8.name(), true, true);
                try {
                    while (true) {
                        ZipArchiveEntry entry = zip.getNextZipEntry();
                        if (entry == null) break;
                        state.handleZipEntry(zip, entry);
                    }
                } finally {
                    zip.close();
                }
            } else if ("tar.gz".equals(platform.getArchiveType())) {
                GzipCompressorInputStream gzip = new GzipCompressorInputStream(raw, true);
                TarArchiveInputStream tar = new TarArchiveInputStream(gzip, StandardCharsets.UTF_8.name());
                try {
                    while (true) {
                        TarArchiveEntry entry = tar.getNextTarEntry();
                        if (entry == null) break;
                        state.handleTarEntry(tar, entry);
                    }
                } finally {
                    tar.close();
                }
            } else {
                throw new RuntimeInstallationException(
                    "Unsupported runtime archive type: " + platform.getArchiveType());
            }
            state.finish();
        } catch (RuntimeInstallationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeInstallationException("Runtime extraction failed", exception);
        } finally {
            try {
                raw.close();
            } catch (IOException ignored) {}
        }
    }

    private static final class ExtractionState {

        private final Path stagingRoot;
        private final RuntimePlatform platform;
        private final String expectedRoot;
        private final Set<String> paths = new HashSet<String>();
        private final Set<String> casePaths = new HashSet<String>();
        private final List<PendingLink> links = new ArrayList<PendingLink>();
        private final Map<Path, Integer> directoryModes = new HashMap<Path, Integer>();
        private int entries;
        private long totalBytes;
        private boolean sawExpectedRoot;

        ExtractionState(Path stagingRoot, RuntimePlatform platform) {
            this.stagingRoot = stagingRoot.toAbsolutePath()
                .normalize();
            this.platform = platform;
            this.expectedRoot = platform.getArchiveRoot();
        }

        void handleZipEntry(InputStream input, ZipArchiveEntry entry) throws IOException, RuntimeInstallationException {
            String relative = register(entry.getName(), entry.isDirectory());
            if (entry.isUnixSymlink()) {
                throw new RuntimeInstallationException(
                    "ZIP symbolic links are not supported for packaged Windows runtimes: " + relative);
            }
            int unixMode = entry.getUnixMode();
            if (unixMode != 0) {
                int fileType = unixMode & UnixStat.FILE_TYPE_FLAG;
                if (fileType != 0 && fileType != UnixStat.FILE_FLAG && fileType != UnixStat.DIR_FLAG) {
                    throw new RuntimeInstallationException("Unsupported ZIP special filesystem entry: " + relative);
                }
            }
            Path output = RuntimePathSafety.resolveContained(stagingRoot, relative, "ZIP output");
            if (entry.isDirectory()) {
                createDirectory(output, entry.getUnixMode());
            } else {
                writeRegularFile(input, output, entry.getSize(), entry.getUnixMode());
            }
        }

        void handleTarEntry(InputStream input, TarArchiveEntry entry) throws IOException, RuntimeInstallationException {
            String relative = register(entry.getName(), entry.isDirectory());
            Path output = RuntimePathSafety.resolveContained(stagingRoot, relative, "TAR output");
            if (entry.isDirectory()) {
                createDirectory(output, entry.getMode());
            } else if (entry.isSymbolicLink()) {
                registerSymbolicLink(relative, output, entry.getLinkName());
            } else if (entry.isLink()) {
                throw new RuntimeInstallationException(
                    "TAR hard links are rejected: " + relative + " -> " + entry.getLinkName());
            } else if (entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()) {
                throw new RuntimeInstallationException("Unsupported TAR special entry: " + relative);
            } else if (entry.isFile()) {
                writeRegularFile(input, output, entry.getSize(), entry.getMode());
            } else {
                throw new RuntimeInstallationException("Unsupported TAR special entry: " + relative);
            }
        }

        private String register(String rawName, boolean directory) throws RuntimeInstallationException {
            entries++;
            if (entries > MAX_ENTRIES)
                throw new RuntimeInstallationException("Runtime archive exceeds entry-count limit");
            String name = rawName == null ? "" : rawName.replace('\\', '/');
            while (name.endsWith("/") && name.length() > 0) name = name.substring(0, name.length() - 1);
            String relative = RuntimePathSafety.requireSafeRelative(name, "Nested runtime archive entry");
            if (!relative.equals(expectedRoot) && !relative.startsWith(expectedRoot + "/")) {
                throw new RuntimeInstallationException(
                    "Archive entry is outside expected root " + expectedRoot + ": " + relative);
            }
            if (relative.equals(expectedRoot)) sawExpectedRoot = true;
            if (!paths.add(relative))
                throw new RuntimeInstallationException("Duplicate normalized archive output path: " + relative);
            if (!casePaths.add(RuntimePathSafety.caseKey(relative))) {
                throw new RuntimeInstallationException("Case-folding archive output collision: " + relative);
            }
            return relative;
        }

        private void createDirectory(Path output, int mode) throws IOException, RuntimeInstallationException {
            RuntimePathSafety.requireNoSymbolicParent(stagingRoot, output);
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
                throw new RuntimeInstallationException("Archive directory conflicts with existing path: " + output);
            }
            Files.createDirectories(output);
            directoryModes.put(output, Integer.valueOf(mode));
        }

        private void writeRegularFile(InputStream input, Path output, long declaredSize, int mode)
            throws IOException, RuntimeInstallationException {
            if (declaredSize < -1L)
                throw new RuntimeInstallationException("Archive file has invalid negative size: " + output);
            if (declaredSize >= 0L && declaredSize > MAX_UNCOMPRESSED_BYTES - totalBytes) {
                throw new RuntimeInstallationException("Runtime archive exceeds uncompressed-size limit");
            }
            Path parent = output.getParent();
            RuntimePathSafety.requireNoSymbolicParent(stagingRoot, output);
            Files.createDirectories(parent);
            RuntimePathSafety.requireNoSymbolicParent(stagingRoot, output);
            OutputStream file = new BufferedOutputStream(
                Files.newOutputStream(
                    output,
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE),
                BUFFER_SIZE);
            long written = 0L;
            try {
                byte[] buffer = new byte[BUFFER_SIZE];
                while (declaredSize < 0L || written < declaredSize) {
                    int requested = declaredSize < 0L ? buffer.length
                        : (int) Math.min(buffer.length, declaredSize - written);
                    int read = input.read(buffer, 0, requested);
                    if (read < 0) {
                        if (declaredSize >= 0L && written != declaredSize) {
                            throw new RuntimeInstallationException("Archive entry ended early: " + output);
                        }
                        break;
                    }
                    written += read;
                    totalBytes += read;
                    if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                        throw new RuntimeInstallationException("Runtime archive exceeds uncompressed-size limit");
                    }
                    file.write(buffer, 0, read);
                }
            } finally {
                file.close();
            }
            applyMode(output, mode, false);
        }

        private void registerSymbolicLink(String relative, Path output, String target)
            throws RuntimeInstallationException {
            if (target == null || target.length() > MAX_LINK_BYTES) {
                throw new RuntimeInstallationException("Symbolic-link target is empty or too long: " + relative);
            }
            String normalizedTarget = target.replace('\\', '/');
            if (normalizedTarget.indexOf('\0') >= 0 || normalizedTarget.startsWith("/")
                || normalizedTarget.startsWith("//")
                || normalizedTarget.matches("^[A-Za-z]:.*")
                || normalizedTarget.contains("://")) {
                throw new RuntimeInstallationException(
                    "Symbolic-link target is not relative: " + relative + " -> " + target);
            }
            Path parent = output.getParent();
            Path resolved = parent.resolve(normalizedTarget.replace('/', java.io.File.separatorChar))
                .normalize();
            Path expectedRootPath = stagingRoot.resolve(expectedRoot)
                .normalize();
            if (!resolved.startsWith(expectedRootPath)) {
                throw new RuntimeInstallationException(
                    "Symbolic link escapes runtime root: " + relative + " -> " + target);
            }
            links.add(new PendingLink(output, normalizedTarget));
        }

        void finish() throws IOException, RuntimeInstallationException {
            if (!sawExpectedRoot || !Files.isDirectory(stagingRoot.resolve(expectedRoot), LinkOption.NOFOLLOW_LINKS)) {
                throw new RuntimeInstallationException(
                    "Runtime archive is missing expected root directory: " + expectedRoot);
            }
            Collections.sort(links, new Comparator<PendingLink>() {

                public int compare(PendingLink left, PendingLink right) {
                    return Integer.compare(left.output.getNameCount(), right.output.getNameCount());
                }
            });
            for (PendingLink link : links) {
                RuntimePathSafety.requireNoSymbolicParent(stagingRoot, link.output);
                Files.createDirectories(link.output.getParent());
                RuntimePathSafety.requireNoSymbolicParent(stagingRoot, link.output);
                try {
                    Files.createSymbolicLink(link.output, java.nio.file.Paths.get(link.target));
                } catch (UnsupportedOperationException exception) {
                    throw new RuntimeInstallationException(
                        "Filesystem does not support required symbolic link: " + link.output,
                        exception);
                } catch (FileAlreadyExistsException exception) {
                    throw new RuntimeInstallationException(
                        "Symbolic-link output already exists: " + link.output,
                        exception);
                }
            }
            Path expectedRootReal = stagingRoot.resolve(expectedRoot)
                .toRealPath();
            for (PendingLink link : links) {
                Path resolvedReal;
                try {
                    resolvedReal = link.output.toRealPath();
                } catch (IOException exception) {
                    throw new RuntimeInstallationException(
                        "Symbolic link does not resolve to an installed runtime file: " + link.output,
                        exception);
                }
                if (!resolvedReal.startsWith(expectedRootReal)) {
                    throw new RuntimeInstallationException("Symbolic-link chain escapes runtime root: " + link.output);
                }
            }
            List<Map.Entry<Path, Integer>> directories = new ArrayList<Map.Entry<Path, Integer>>(
                directoryModes.entrySet());
            Collections.sort(directories, new Comparator<Map.Entry<Path, Integer>>() {

                public int compare(Map.Entry<Path, Integer> left, Map.Entry<Path, Integer> right) {
                    return Integer.compare(
                        right.getKey()
                            .getNameCount(),
                        left.getKey()
                            .getNameCount());
                }
            });
            for (Map.Entry<Path, Integer> directory : directories)
                applyMode(directory.getKey(), directory.getValue(), true);
        }
    }

    private static void applyMode(Path path, int rawMode, boolean directory) throws IOException {
        if (rawMode == 0) return;
        int mode = rawMode & 0777;
        try {
            Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
            if ((mode & 0400) != 0) permissions.add(PosixFilePermission.OWNER_READ);
            if ((mode & 0200) != 0) permissions.add(PosixFilePermission.OWNER_WRITE);
            if ((mode & 0100) != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE);
            if ((mode & 0040) != 0) permissions.add(PosixFilePermission.GROUP_READ);
            if ((mode & 0020) != 0) permissions.add(PosixFilePermission.GROUP_WRITE);
            if ((mode & 0010) != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE);
            if ((mode & 0004) != 0) permissions.add(PosixFilePermission.OTHERS_READ);
            if ((mode & 0002) != 0) permissions.add(PosixFilePermission.OTHERS_WRITE);
            if ((mode & 0001) != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            if (directory) permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException exception) {
            java.io.File file = path.toFile();
            if ((mode & 0400) != 0) file.setReadable(true, true);
            if ((mode & 0200) != 0) file.setWritable(true, true);
            if ((mode & 0100) != 0 || directory) file.setExecutable(true, true);
        }
    }

    private static final class PendingLink {

        final Path output;
        final String target;

        PendingLink(Path output, String target) {
            this.output = output;
            this.target = target;
        }
    }
}
