package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import me.eigenraven.lwjgl3ify.relauncher.JvmLocator;
import me.eigenraven.lwjgl3ify.relauncher.RelauncherConfig;

/** Finds a working Temurin 21 installation matching the detected host. No network access. */
public final class InstalledJavaLocator {

    private InstalledJavaLocator() {}

    public static JavaLaunchSelection findCompatible(RuntimeHost host) {
        if (host == null) return null;
        final boolean windows = "windows".equals(host.getOperatingSystem());
        final LinkedHashSet<Path> candidates = new LinkedHashSet<Path>();
        String home = System.getenv("JAVA_HOME");
        if (home != null && !home.trim()
            .isEmpty()) {
            try {
                candidates.add(Paths.get(home, "bin", windows ? "java.exe" : "java"));
            } catch (RuntimeException ignored) {}
        }
        String[] cached = RelauncherConfig.config.javaInstallationsCache;
        List<String> configured = cached == null ? Collections.<String>emptyList() : java.util.Arrays.asList(cached);
        candidates.addAll(JvmLocator.detectJavaInstalls(configured));
        String path = System.getenv("PATH");
        if (path != null) for (String entry : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (entry.isEmpty()) continue;
            try {
                candidates.add(Paths.get(entry, windows ? "java.exe" : "java"));
            } catch (RuntimeException ignored) {}
        }
        for (Path candidate : candidates) {
            try {
                Path executable = candidate.toRealPath();
                if (windows && executable.getFileName()
                    .toString()
                    .equalsIgnoreCase("javaw.exe")) {
                    executable = executable.resolveSibling("java.exe");
                }
                if (!Files.isRegularFile(executable) || (!windows && !Files.isExecutable(executable))) continue;
                if (!matches(probe(executable), host)) continue;
                return JavaLaunchSelection.detected(executable, windows);
            } catch (IOException | RuntimeException ignored) {
                // A broken installation must not prevent the next candidate or packaged fallback.
            }
        }
        return null;
    }

    static boolean matches(String output, RuntimeHost host) {
        if (output == null) return false;
        String version = property(output, "java.version");
        String vendor = property(output, "java.vendor");
        String arch = property(output, "os.arch").toLowerCase(Locale.ROOT);
        boolean sameArch = "aarch64".equals(host.getArchitecture()) ? arch.equals("aarch64") || arch.equals("arm64")
            : arch.equals("amd64") || arch.equals("x86_64");
        return version.startsWith("21.") && sameArch
            && (vendor.toLowerCase(Locale.ROOT)
                .contains("adoptium")
                || vendor.toLowerCase(Locale.ROOT)
                    .contains("temurin"));
    }

    private static String property(String output, String name) {
        for (String line : output.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(name + " = ")) return trimmed.substring(name.length() + 3)
                .trim();
        }
        return "";
    }

    private static String probe(Path executable) throws IOException {
        Process process = new ProcessBuilder(executable.toString(), "-XshowSettings:properties", "-version")
            .redirectErrorStream(true)
            .start();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[2048];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() < 65536) bytes.write(buffer, 0, Math.min(count, 65536 - bytes.size()));
                }
            } catch (IOException ignored) {}
        }, "lwjgl3ify-java-probe");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            reader.join(1000);
            return process.exitValue() == 0 ? new String(bytes.toByteArray(), StandardCharsets.UTF_8) : "";
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread()
                .interrupt();
            return "";
        }
    }
}
