package me.eigenraven.lwjgl3ify.relauncherstub;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Human-readable native process exit diagnostics without guessing the faulting mod or driver. */
final class NativeExitDiagnostics {

    private NativeExitDiagnostics() {}

    static String describe(int exitCode) {
        final long unsigned = Integer.toUnsignedLong(exitCode);
        final String hex = String.format(Locale.ROOT, "0x%08X", unsigned);
        if (unsigned == 0xC000041DL) {
            return exitCode + " / "
                + hex
                + " (STATUS_FATAL_USER_CALLBACK_EXCEPTION: an exception escaped a native Windows callback)";
        }
        if (unsigned == 0xC0000005L) {
            return exitCode + " / " + hex + " (STATUS_ACCESS_VIOLATION: native code accessed invalid memory)";
        }
        if (unsigned == 0xC0000017L) {
            return exitCode + " / " + hex + " (STATUS_NO_MEMORY: Windows could not satisfy a native allocation)";
        }
        if (exitCode == 134) {
            return "134 (native abort/SIGABRT; inspect hs_err_pid*.log when present)";
        }
        return exitCode < 0 ? exitCode + " / " + hex + " (unrecognised Windows native status)"
            : Integer.toString(exitCode);
    }

    static String describeHeapArguments(Path argumentFile) {
        if (argumentFile == null || !Files.isRegularFile(argumentFile)) return "none (argument file unavailable)";
        final List<String> heapArguments = new ArrayList<String>();
        try {
            for (String line : Files.readAllLines(argumentFile)) {
                line = line.trim();
                if (line.length() >= 2 && line.startsWith("\"") && line.endsWith("\"")) {
                    line = line.substring(1, line.length() - 1);
                }
                final String normalized = line.toLowerCase(Locale.ROOT);
                if (normalized.startsWith("-xms") || normalized.startsWith("-xmx")
                    || normalized.startsWith("-xx:initialrampercentage=")
                    || normalized.startsWith("-xx:maxrampercentage=")) {
                    heapArguments.add(line);
                }
            }
        } catch (IOException ignored) {
            return "none (argument file could not be read)";
        }
        return heapArguments.isEmpty() ? "none" : String.join(" ", heapArguments);
    }

    static String buildFailureMessage(int exitCode, Path childLog) {
        final StringBuilder message = new StringBuilder();
        message.append("The managed Java game process exited with ")
            .append(describe(exitCode))
            .append(".\n\nDiagnostic log:\n")
            .append(childLog);

        final Path gameDirectory = inferGameDirectory(childLog);
        final Path fatalErrorLog = findNewest(gameDirectory, "hs_err_pid*.log");
        if (fatalErrorLog != null) {
            message.append("\n\nJVM fatal-error report:\n")
                .append(fatalErrorLog);
        } else if (Integer.toUnsignedLong(exitCode) == 0xC000041DL) {
            message.append("\n\nNo hs_err_pid log was found. For this Windows status, also collect the latest ")
                .append("Event Viewer > Windows Logs > Application entry for java.exe/javaw.exe, including the ")
                .append("faulting module and exception code.");
        }
        return message.toString();
    }

    static Path inferGameDirectory(Path childLog) {
        if (childLog == null) return null;
        final Path normalized = childLog.toAbsolutePath()
            .normalize();
        final Path logsDirectory = normalized.getParent();
        return logsDirectory == null ? null : logsDirectory.getParent();
    }

    private static Path findNewest(Path directory, String glob) {
        if (directory == null || !Files.isDirectory(directory)) return null;
        Path newest = null;
        long newestTime = Long.MIN_VALUE;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, glob)) {
            for (final Path entry : entries) {
                if (!Files.isRegularFile(entry)) continue;
                final long modified = Files.getLastModifiedTime(entry)
                    .toMillis();
                if (newest == null || modified > newestTime) {
                    newest = entry.toAbsolutePath()
                        .normalize();
                    newestTime = modified;
                }
            }
        } catch (IOException ignored) {}
        return newest;
    }
}
