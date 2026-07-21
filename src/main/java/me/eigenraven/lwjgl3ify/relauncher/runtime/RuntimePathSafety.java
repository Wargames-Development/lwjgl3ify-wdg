package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

final class RuntimePathSafety {

    static final int MAXIMUM_PATH_LENGTH = 4096;

    private RuntimePathSafety() {}

    static String requireSafeRelative(String value, String context) throws RuntimeInstallationException {
        return requireSafeRelativeInternal(value, context, false);
    }

    static String requireSafeRelativeAllowDot(String value, String context) throws RuntimeInstallationException {
        return requireSafeRelativeInternal(value, context, true);
    }

    private static String requireSafeRelativeInternal(String value, String context, boolean allowDot)
        throws RuntimeInstallationException {
        if (value == null || value.indexOf('\0') >= 0 || value.length() > MAXIMUM_PATH_LENGTH) {
            throw new RuntimeInstallationException(context + " is empty, too long, or contains a null byte");
        }
        String normalizedSlashes = value.replace('\\', '/');
        if (allowDot && ".".equals(normalizedSlashes)) return ".";
        if (normalizedSlashes.isEmpty() || normalizedSlashes.startsWith("/")
            || normalizedSlashes.startsWith("//")
            || normalizedSlashes.matches("^[A-Za-z]:.*")
            || normalizedSlashes.contains("://")) {
            throw new RuntimeInstallationException(context + " is not a safe relative path: " + value);
        }
        String[] parts = normalizedSlashes.split("/", -1);
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new RuntimeInstallationException(context + " contains an unsafe path segment: " + value);
            }
            if (part.indexOf(':') >= 0) {
                throw new RuntimeInstallationException(context + " contains a URI or drive-like segment: " + value);
            }
            if (result.length() > 0) result.append('/');
            result.append(part);
        }
        return result.toString();
    }

    static Path resolveContained(Path root, String relative, String context) throws RuntimeInstallationException {
        Path normalizedRoot = root.toAbsolutePath()
            .normalize();
        Path resolved = normalizedRoot.resolve(relative.replace('/', java.io.File.separatorChar))
            .normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new RuntimeInstallationException(context + " escapes its root: " + relative);
        }
        return resolved;
    }

    static void requireNoSymbolicParent(Path root, Path output) throws IOException, RuntimeInstallationException {
        Path normalizedRoot = root.toAbsolutePath()
            .normalize();
        Path current = output.toAbsolutePath()
            .normalize()
            .getParent();
        while (current != null && current.startsWith(normalizedRoot)) {
            if (Files.isSymbolicLink(current)) {
                throw new RuntimeInstallationException("Refusing to write through symbolic-link parent: " + current);
            }
            if (current.equals(normalizedRoot)) return;
            current = current.getParent();
        }
        throw new RuntimeInstallationException("Output path is outside extraction root: " + output);
    }

    static String caseKey(String relative) {
        return relative.toLowerCase(Locale.ROOT);
    }

    static void deleteRecursively(Path path, Path allowedRoot) throws IOException, RuntimeInstallationException {
        Path normalized = path.toAbsolutePath()
            .normalize();
        Path allowed = allowedRoot.toAbsolutePath()
            .normalize();
        if (normalized.equals(allowed) || !normalized.startsWith(allowed)) {
            throw new RuntimeInstallationException(
                "Refusing recursive deletion outside runtime cache subtree: " + path);
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(normalized);
            return;
        }
        DirectoryStream<Path> stream = Files.newDirectoryStream(normalized);
        try {
            for (Path child : stream) deleteRecursively(child, allowed);
        } finally {
            stream.close();
        }
        Files.deleteIfExists(normalized);
    }
}
