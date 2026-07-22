package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Opt-in real-runtime smoke for the production automatic coordinator. */
public final class AutomaticRuntimeSmokeMain {

    private AutomaticRuntimeSmokeMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected <game-directory> <cache-root> <expected-platform>");
        }
        Path gameDirectory = Paths.get(args[0])
            .toAbsolutePath()
            .normalize();
        Path cacheRoot = Paths.get(args[1])
            .toAbsolutePath()
            .normalize();
        Path cacheParent = cacheRoot.getParent();
        if (cacheParent == null) throw new IllegalArgumentException("Cache root must have a parent directory");
        if (Files.exists(cacheRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            RuntimePathSafety.deleteRecursively(cacheRoot, cacheParent);
        }
        String expectedPlatform = args[2];

        Map<String, String> properties = new HashMap<String, String>();
        properties.put("os.name", System.getProperty("os.name"));
        properties.put("os.arch", System.getProperty("os.arch"));
        AutomaticRuntimeCoordinator coordinator = new AutomaticRuntimeCoordinator();

        AutomaticRuntimeResult first = coordinator.prepare(gameDirectory, cacheRoot, true, properties, System.getenv());
        requireReady(first, "first");
        if (!expectedPlatform.equals(
            first.getSelection()
                .getPlatformId())) {
            throw new IllegalStateException(
                "Expected platform " + expectedPlatform
                    + " but selected "
                    + first.getSelection()
                        .getPlatformId());
        }
        if (!first.getInstallation()
            .wasInstalled()) {
            throw new IllegalStateException("First automatic coordinator run did not install a clean runtime");
        }

        String diagnostics = executeJava(
            first.getSelection()
                .getConsoleExecutable());
        validateDiagnostics(diagnostics, expectedPlatform);

        AutomaticRuntimeResult second = coordinator
            .prepare(gameDirectory, cacheRoot, true, properties, System.getenv());
        requireReady(second, "second");
        if (!second.getInstallation()
            .wasReused()) {
            throw new IllegalStateException("Second automatic coordinator run did not reuse the installed runtime");
        }
        if (!first.getInstallation()
            .getInstallationRoot()
            .equals(
                second.getInstallation()
                    .getInstallationRoot())) {
            throw new IllegalStateException("Coordinator reuse returned a different installation root");
        }

        System.out.println("Automatic Java runtime verification passed");
        System.out.println("Detected host: " + first.getHost());
        System.out.println(
            "Selected platform: " + first.getSelection()
                .getPlatformId());
        System.out.println("Bundle path: " + first.getBundlePath());
        System.out.println(
            "Installation path: " + first.getInstallation()
                .getInstallationRoot());
        System.out.println(
            "Java home: " + first.getSelection()
                .getJavaHome());
        System.out.println(
            "Executable: " + first.getSelection()
                .getConsoleExecutable());
        System.out.println("First result: installed");
        System.out.println("Second result: reused");
        System.out.println("Java diagnostic output:");
        System.out.println(diagnostics);
    }

    private static void requireReady(AutomaticRuntimeResult result, String phase) {
        if (!result.isReady()) {
            Throwable failure = result.getFailure();
            throw new IllegalStateException(
                phase + " automatic coordinator run failed: " + result.getMessage(),
                failure);
        }
    }

    private static String executeJava(String executable) throws Exception {
        Process process = new ProcessBuilder(executable, "-XshowSettings:properties", "-version")
            .redirectErrorStream(true)
            .start();
        final boolean completed;
        try {
            completed = process.waitFor(30L, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            process.destroy();
            Thread.currentThread()
                .interrupt();
            throw exception;
        }
        if (!completed) {
            process.destroy();
            if (!process.waitFor(1L, TimeUnit.SECONDS)) process.destroyForcibly();
            throw new IllegalStateException("Packaged Java diagnostic command timed out");
        }
        try {
            String output = readBounded(process.getInputStream(), 256 * 1024);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Packaged Java diagnostic command failed: " + output);
            }
            return output;
        } finally {
            process.getInputStream()
                .close();
            process.getErrorStream()
                .close();
            process.getOutputStream()
                .close();
        }
    }

    private static void validateDiagnostics(String diagnostics, String platformId) {
        if (!diagnostics.contains("java.version = 21.0.11")) {
            throw new IllegalStateException("Packaged Java did not report Java feature/version 21.0.11");
        }
        if (!diagnostics.contains("java.runtime.version = 21.0.11")) {
            throw new IllegalStateException("Packaged Java did not report runtime version 21.0.11");
        }
        if (!diagnostics.contains("Eclipse Adoptium") && !diagnostics.contains("Temurin")) {
            throw new IllegalStateException("Packaged Java did not report Eclipse Adoptium/Temurin");
        }
        if (platformId.endsWith("aarch64")) {
            if (!diagnostics.contains("os.arch = aarch64") && !diagnostics.contains("os.arch = arm64")) {
                throw new IllegalStateException("Packaged Java architecture mismatch: expected AArch64");
            }
        } else if (!diagnostics.contains("os.arch = x86_64") && !diagnostics.contains("os.arch = amd64")) {
            throw new IllegalStateException("Packaged Java architecture mismatch: expected x86_64/amd64");
        }
    }

    private static String readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int remaining = limit;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) break;
            output.write(buffer, 0, read);
            remaining -= read;
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
