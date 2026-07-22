package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Bounded, shell-free host detector for the six manifest platforms. */
public final class RuntimeHostDetector {

    private static final long PROBE_TIMEOUT_MILLIS = 2000L;
    private static final int MAX_PROBE_BYTES = 4096;

    public interface CommandProbe {

        ProbeResult execute(String... command) throws InterruptedException;
    }

    public static final class ProbeResult {

        private final boolean started;
        private final boolean timedOut;
        private final int exitCode;
        private final String output;

        public ProbeResult(boolean started, boolean timedOut, int exitCode, String output) {
            this.started = started;
            this.timedOut = timedOut;
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }

        public static ProbeResult unavailable() {
            return new ProbeResult(false, false, -1, "");
        }

        public static ProbeResult success(String output) {
            return new ProbeResult(true, false, 0, output);
        }

        public static ProbeResult timedOut() {
            return new ProbeResult(true, true, -1, "");
        }

        public boolean isSuccessful() {
            return started && !timedOut && exitCode == 0;
        }

        public boolean isStarted() {
            return started;
        }

        public boolean isTimedOut() {
            return timedOut;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getOutput() {
            return output;
        }
    }

    public static final class SystemCommandProbe implements CommandProbe {

        @Override
        public ProbeResult execute(String... command) throws InterruptedException {
            Process process;
            try {
                process = new ProcessBuilder(Arrays.asList(command)).redirectErrorStream(true)
                    .start();
            } catch (IOException exception) {
                return ProbeResult.unavailable();
            }
            try {
                boolean completed = process.waitFor(PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                if (!completed) {
                    process.destroy();
                    if (!process.waitFor(250L, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                    return ProbeResult.timedOut();
                }
                return new ProbeResult(true, false, process.exitValue(), readBounded(process.getInputStream()));
            } catch (InterruptedException exception) {
                process.destroy();
                Thread.currentThread()
                    .interrupt();
                throw exception;
            } catch (IOException exception) {
                return new ProbeResult(true, false, process.isAlive() ? -1 : process.exitValue(), "");
            } finally {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                closeQuietly(process.getOutputStream());
            }
        }

        private static String readBounded(InputStream stream) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int remaining = MAX_PROBE_BYTES;
            while (remaining > 0) {
                int read = stream.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) break;
                output.write(buffer, 0, read);
                remaining -= read;
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        }

        private static void closeQuietly(java.io.Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException ignored) {}
        }
    }

    private final CommandProbe commandProbe;

    public RuntimeHostDetector() {
        this(new SystemCommandProbe());
    }

    public RuntimeHostDetector(CommandProbe commandProbe) {
        if (commandProbe == null) throw new NullPointerException("commandProbe");
        this.commandProbe = commandProbe;
    }

    public RuntimeHost detect() throws RuntimeHostDetectionException, InterruptedException {
        return detect(System.getProperty("os.name"), System.getProperty("os.arch"), System.getenv());
    }

    public RuntimeHost detect(String osName, String osArch, Map<String, String> environment)
        throws RuntimeHostDetectionException, InterruptedException {
        String os = canonicalOperatingSystem(osName);
        Map<String, String> safeEnvironment = environment == null ? Collections.<String, String>emptyMap()
            : environment;
        if ("macos".equals(os)) return detectMac(osArch);
        if ("windows".equals(os)) return detectWindows(osArch, safeEnvironment);
        if ("linux".equals(os)) return detectLinux(osArch);
        throw new RuntimeHostDetectionException("Unsupported operating system: " + safe(osName));
    }

    static String canonicalOperatingSystem(String value) throws RuntimeHostDetectionException {
        String normalized = safe(value).trim()
            .toLowerCase(Locale.ROOT);
        if (normalized.startsWith("windows")) return "windows";
        if ("mac os x".equals(normalized) || "macos".equals(normalized) || "darwin".equals(normalized)) return "macos";
        if ("linux".equals(normalized)) return "linux";
        throw new RuntimeHostDetectionException("Unsupported operating system: " + safe(value));
    }

    static String canonicalArchitecture(String value) throws RuntimeHostDetectionException {
        String normalized = safe(value).trim()
            .toLowerCase(Locale.ROOT);
        if ("amd64".equals(normalized) || "x86_64".equals(normalized) || "x64".equals(normalized)) return "x86_64";
        if ("aarch64".equals(normalized) || "arm64".equals(normalized)) return "aarch64";
        throw new RuntimeHostDetectionException("Unsupported architecture: " + safe(value));
    }

    private RuntimeHost detectMac(String osArch) throws RuntimeHostDetectionException, InterruptedException {
        String processArchitecture = canonicalArchitecture(osArch);
        if ("aarch64".equals(processArchitecture)) {
            return new RuntimeHost("macos", "aarch64", null, "native Apple Silicon process architecture");
        }
        ProbeResult translated = commandProbe.execute("/usr/sbin/sysctl", "-in", "sysctl.proc_translated");
        String translatedDiagnostic = null;
        if (translated.isSuccessful()) {
            String value = translated.getOutput()
                .trim();
            if ("1".equals(value)) {
                return new RuntimeHost("macos", "aarch64", null, "x86_64 Java process translated by Rosetta");
            }
            if (!"0".equals(value)) translatedDiagnostic = "malformed sysctl.proc_translated output";
        }
        ProbeResult arm64 = commandProbe.execute("/usr/sbin/sysctl", "-in", "hw.optional.arm64");
        if (arm64.isSuccessful()) {
            String value = arm64.getOutput()
                .trim();
            if ("1".equals(value)) {
                return new RuntimeHost("macos", "aarch64", null, "ARM64 hardware confirmed by sysctl");
            }
            if ("0".equals(value)) {
                return new RuntimeHost("macos", "x86_64", null, "Intel hardware confirmed by sysctl");
            }
            return macFallback(processArchitecture, "malformed hw.optional.arm64 output");
        }
        String reason = translated.isTimedOut() || arm64.isTimedOut() ? "native sysctl probe timed out"
            : translatedDiagnostic == null ? "native sysctl probe unavailable or inconclusive" : translatedDiagnostic;
        return macFallback(processArchitecture, reason);
    }

    private static RuntimeHost macFallback(String processArchitecture, String reason) {
        return new RuntimeHost(
            "macos",
            processArchitecture,
            null,
            reason + "; using process architecture conservatively");
    }

    private RuntimeHost detectWindows(String osArch, Map<String, String> environment)
        throws RuntimeHostDetectionException {
        String processArchitecture = canonicalArchitecture(osArch);
        String wow = normalizeEnvironmentArchitecture(environment.get("PROCESSOR_ARCHITEW6432"));
        String nativeArchitecture = normalizeEnvironmentArchitecture(environment.get("PROCESSOR_ARCHITECTURE"));
        if ("aarch64".equals(wow) || "aarch64".equals(nativeArchitecture)) {
            String detail = "Windows ARM64 environment evidence";
            if ((wow != null && !"aarch64".equals(wow))
                || (nativeArchitecture != null && !"aarch64".equals(nativeArchitecture))) {
                detail += " with conflicting emulated process value";
            }
            return new RuntimeHost("windows", "aarch64", null, detail);
        }
        if ("x86".equals(nativeArchitecture) && wow == null) {
            throw new RuntimeHostDetectionException("32-bit Windows is unsupported by the packaged Java runtimes");
        }
        if (wow != null && nativeArchitecture != null && !wow.equals(nativeArchitecture)) {
            return new RuntimeHost(
                "windows",
                processArchitecture,
                null,
                "conflicting Windows architecture environment; using process architecture conservatively");
        }
        return new RuntimeHost("windows", processArchitecture, null, "Windows process/native environment architecture");
    }

    private static String normalizeEnvironmentArchitecture(String value) throws RuntimeHostDetectionException {
        if (value == null || value.trim()
            .isEmpty()) return null;
        String normalized = value.trim()
            .toLowerCase(Locale.ROOT);
        if ("arm64".equals(normalized) || "aarch64".equals(normalized)) return "aarch64";
        if ("amd64".equals(normalized) || "x86_64".equals(normalized) || "x64".equals(normalized)) return "x86_64";
        if ("x86".equals(normalized) || "i386".equals(normalized) || "i686".equals(normalized)) return "x86";
        if ("ia64".equals(normalized)) throw new RuntimeHostDetectionException("Unsupported Windows IA64 environment");
        return null;
    }

    private RuntimeHost detectLinux(String osArch) throws RuntimeHostDetectionException, InterruptedException {
        String architecture = canonicalArchitecture(osArch);
        ProbeResult libc = commandProbe.execute("getconf", "GNU_LIBC_VERSION");
        if (!libc.isSuccessful()) {
            String reason = libc.isTimedOut() ? "GNU libc probe timed out" : "GNU libc probe unavailable";
            throw new RuntimeHostDetectionException(reason + "; automatic Linux packaged Java requires glibc");
        }
        String output = libc.getOutput()
            .trim()
            .toLowerCase(Locale.ROOT);
        if (output.contains("musl")) {
            throw new RuntimeHostDetectionException("musl libc is unsupported by the packaged Java runtimes");
        }
        if (!output.startsWith("glibc ") && !output.startsWith("gnu libc ")) {
            throw new RuntimeHostDetectionException(
                "Could not establish GNU libc from getconf output; automatic Linux packaged Java is unavailable");
        }
        return new RuntimeHost("linux", architecture, "gnu", "GNU libc confirmed by getconf");
    }

    private static String safe(String value) {
        return value == null ? "<missing>" : value;
    }
}
