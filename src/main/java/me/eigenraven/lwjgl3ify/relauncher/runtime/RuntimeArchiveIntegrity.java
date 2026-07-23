package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Shared size and SHA-256 verification for direct and embedded runtime archives. */
final class RuntimeArchiveIntegrity {

    private static final int BUFFER_SIZE = 1024 * 1024;

    private RuntimeArchiveIntegrity() {}

    static void verify(Path archive, RuntimePlatform platform, String description)
        throws IOException, RuntimeInstallationException {
        if (archive == null || platform == null) throw new NullPointerException("archive and platform are required");
        Path normalized = archive.toAbsolutePath()
            .normalize();
        if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
            || !Files.isReadable(normalized)) {
            throw new RuntimeInstallationException(description + " is missing, unreadable, or unsafe: " + normalized);
        }
        long size = Files.size(normalized);
        if (size != platform.getSizeBytes()) {
            throw new RuntimeInstallationException(
                description + " size mismatch for "
                    + platform.getId()
                    + "; expected "
                    + platform.getSizeBytes()
                    + " but was "
                    + size);
        }
        try (InputStream input = new BufferedInputStream(Files.newInputStream(normalized))) {
            String hash = digest(input, null, platform.getSizeBytes(), description);
            if (!platform.getSha256()
                .equals(hash)) {
                throw new RuntimeInstallationException(
                    description + " SHA-256 mismatch for "
                        + platform.getId()
                        + "; expected "
                        + platform.getSha256()
                        + " but was "
                        + hash);
            }
        }
    }

    static void copyVerified(InputStream input, Path destination, RuntimePlatform platform, String description)
        throws IOException, RuntimeInstallationException {
        if (input == null || destination == null || platform == null) {
            throw new NullPointerException("input, destination, and platform are required");
        }
        Path parent = destination.toAbsolutePath()
            .normalize()
            .getParent();
        if (parent == null) throw new RuntimeInstallationException("Runtime archive destination lacks a parent");
        Files.createDirectories(parent);
        try (InputStream buffered = new BufferedInputStream(input);
            OutputStream output = Files
                .newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            String hash = digest(buffered, output, platform.getSizeBytes(), description);
            long size = Files.size(destination);
            if (size != platform.getSizeBytes()) {
                throw new RuntimeInstallationException(
                    description + " size mismatch for "
                        + platform.getId()
                        + "; expected "
                        + platform.getSizeBytes()
                        + " but was "
                        + size);
            }
            if (!platform.getSha256()
                .equals(hash)) {
                throw new RuntimeInstallationException(
                    description + " SHA-256 mismatch for "
                        + platform.getId()
                        + "; expected "
                        + platform.getSha256()
                        + " but was "
                        + hash);
            }
        } catch (IOException exception) {
            Files.deleteIfExists(destination);
            throw exception;
        }
    }

    private static String digest(InputStream input, OutputStream output, long maximumBytes, String description)
        throws IOException, RuntimeInstallationException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0L;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) break;
            total += read;
            if (total > maximumBytes) {
                throw new RuntimeInstallationException(description + " exceeds the pinned archive size");
            }
            digest.update(buffer, 0, read);
            if (output != null) output.write(buffer, 0, read);
        }
        if (total != maximumBytes) {
            throw new RuntimeInstallationException(
                description + " ended at " + total + " bytes instead of " + maximumBytes);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }
}
