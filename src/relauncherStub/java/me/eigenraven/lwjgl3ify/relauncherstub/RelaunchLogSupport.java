package me.eigenraven.lwjgl3ify.relauncherstub;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Rotating persistent diagnostics for the hidden Java 21 child process. */
final class RelaunchLogSupport {

    static final long MAX_LOG_BYTES = 10L * 1024L * 1024L;
    static final int BACKUP_COUNT = 3;

    private RelaunchLogSupport() {}

    static Path prepare(Path logFile) throws IOException {
        Path normalized = logFile.toAbsolutePath()
            .normalize();
        Path parent = normalized.getParent();
        if (parent == null) throw new IOException("Relaunch log path lacks a parent directory");
        Files.createDirectories(parent);
        if (Files.exists(normalized) && Files.size(normalized) >= MAX_LOG_BYTES) rotate(normalized);
        if (!Files.exists(normalized)) Files.createFile(normalized);
        return normalized;
    }

    static BufferedWriter openWriter(Path logFile) throws IOException {
        return Files.newBufferedWriter(
            prepare(logFile),
            StandardCharsets.UTF_8,
            StandardOpenOption.APPEND,
            StandardOpenOption.WRITE);
    }

    static void append(Path logFile, String message) {
        if (logFile == null) return;
        try (BufferedWriter writer = openWriter(logFile)) {
            writer.write(message);
            writer.newLine();
        } catch (IOException ignored) {}
    }

    private static void rotate(Path current) throws IOException {
        Files.deleteIfExists(sibling(current, BACKUP_COUNT));
        for (int index = BACKUP_COUNT - 1; index >= 1; index--) {
            Path source = sibling(current, index);
            if (Files.exists(source)) {
                Files.move(source, sibling(current, index + 1), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(current, sibling(current, 1), StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path sibling(Path current, int index) {
        return current.resolveSibling(
            current.getFileName()
                .toString() + "."
                + index);
    }
}
