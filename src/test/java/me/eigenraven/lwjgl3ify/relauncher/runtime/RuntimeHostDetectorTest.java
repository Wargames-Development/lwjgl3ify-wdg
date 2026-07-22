package me.eigenraven.lwjgl3ify.relauncher.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

import org.junit.Test;

public class RuntimeHostDetectorTest {

    @Test
    public void canonicalizesOperatingSystemAliases() throws Exception {
        assertEquals("windows", RuntimeHostDetector.canonicalOperatingSystem("Windows 10"));
        assertEquals("windows", RuntimeHostDetector.canonicalOperatingSystem("Windows 11"));
        assertEquals("macos", RuntimeHostDetector.canonicalOperatingSystem("Mac OS X"));
        assertEquals("macos", RuntimeHostDetector.canonicalOperatingSystem("macOS"));
        assertEquals("macos", RuntimeHostDetector.canonicalOperatingSystem("Darwin"));
        assertEquals("linux", RuntimeHostDetector.canonicalOperatingSystem("Linux"));
    }

    @Test
    public void rejectsUnknownOperatingSystems() throws Exception {
        for (String value : new String[] { "FreeBSD", "Android", "Solaris", "AIX", "Plan9" }) {
            try {
                RuntimeHostDetector.canonicalOperatingSystem(value);
                fail(value);
            } catch (RuntimeHostDetectionException expected) {
                assertTrue(
                    expected.getMessage()
                        .contains("Unsupported operating system"));
            }
        }
    }

    @Test
    public void canonicalizesOnlySupportedArchitectures() throws Exception {
        for (String value : new String[] { "amd64", "x86_64", "x64" }) {
            assertEquals("x86_64", RuntimeHostDetector.canonicalArchitecture(value));
        }
        for (String value : new String[] { "aarch64", "arm64" }) {
            assertEquals("aarch64", RuntimeHostDetector.canonicalArchitecture(value));
        }
        for (String value : new String[] { "x86", "i386", "i686", "arm", "armv7", "ppc", "ppc64", "ppc64le", "riscv64",
            "ia64", "unknown" }) {
            try {
                RuntimeHostDetector.canonicalArchitecture(value);
                fail(value);
            } catch (RuntimeHostDetectionException expected) {
                assertTrue(
                    expected.getMessage()
                        .contains("Unsupported architecture"));
            }
        }
    }

    @Test
    public void detectsNativeAppleSiliconWithoutProbe() throws Exception {
        RuntimeHost host = detector().detect("Mac OS X", "arm64", Collections.<String, String>emptyMap());
        assertEquals("macos-aarch64", host.getPlatformTuple());
    }

    @Test
    public void detectsRosettaAndIntelMacs() throws Exception {
        QueueProbe rosetta = new QueueProbe(RuntimeHostDetector.ProbeResult.success("1"));
        assertEquals(
            "macos-aarch64",
            new RuntimeHostDetector(rosetta).detect("macOS", "x86_64", Collections.<String, String>emptyMap())
                .getPlatformTuple());

        QueueProbe intel = new QueueProbe(
            RuntimeHostDetector.ProbeResult.success("0"),
            RuntimeHostDetector.ProbeResult.success("0"));
        assertEquals(
            "macos-x86_64",
            new RuntimeHostDetector(intel).detect("Darwin", "amd64", Collections.<String, String>emptyMap())
                .getPlatformTuple());
    }

    @Test
    public void macProbeFailureTimeoutAndMalformedOutputFallbackConservatively() throws Exception {
        QueueProbe failed = new QueueProbe(
            RuntimeHostDetector.ProbeResult.unavailable(),
            RuntimeHostDetector.ProbeResult.unavailable());
        assertEquals(
            "macos-x86_64",
            new RuntimeHostDetector(failed).detect("Mac OS X", "x64", Collections.<String, String>emptyMap())
                .getPlatformTuple());

        QueueProbe timeout = new QueueProbe(
            RuntimeHostDetector.ProbeResult.timedOut(),
            RuntimeHostDetector.ProbeResult.timedOut());
        assertTrue(
            new RuntimeHostDetector(timeout).detect("Mac OS X", "x86_64", Collections.<String, String>emptyMap())
                .getDiagnostic()
                .contains("timed out"));

        QueueProbe malformed = new QueueProbe(RuntimeHostDetector.ProbeResult.success("maybe"));
        assertEquals(
            "macos-x86_64",
            new RuntimeHostDetector(malformed).detect("Mac OS X", "x86_64", Collections.<String, String>emptyMap())
                .getPlatformTuple());
    }

    @Test
    public void detectsWindowsAmd64AndArm64Environment() throws Exception {
        RuntimeHost amd = detector().detect("Windows 11", "amd64", Collections.<String, String>emptyMap());
        assertEquals("windows-x86_64", amd.getPlatformTuple());

        Map<String, String> nativeArm = new HashMap<String, String>();
        nativeArm.put("PROCESSOR_ARCHITECTURE", "ARM64");
        assertEquals(
            "windows-aarch64",
            detector().detect("Windows 10", "amd64", nativeArm)
                .getPlatformTuple());

        Map<String, String> emulated = new HashMap<String, String>();
        emulated.put("PROCESSOR_ARCHITEW6432", "ARM64");
        emulated.put("PROCESSOR_ARCHITECTURE", "AMD64");
        RuntimeHost arm = detector().detect("Windows 11", "x86_64", emulated);
        assertEquals("windows-aarch64", arm.getPlatformTuple());
        assertTrue(
            arm.getDiagnostic()
                .contains("conflicting"));

        Map<String, String> native32 = new HashMap<String, String>();
        native32.put("PROCESSOR_ARCHITECTURE", "x86");
        assertWindowsUnavailable("amd64", native32, "32-bit Windows");

        Map<String, String> ia64 = new HashMap<String, String>();
        ia64.put("PROCESSOR_ARCHITECTURE", "IA64");
        assertWindowsUnavailable("amd64", ia64, "IA64");
    }

    @Test
    public void detectsOnlyGlibcLinux() throws Exception {
        RuntimeHost x64 = new RuntimeHostDetector(new QueueProbe(RuntimeHostDetector.ProbeResult.success("glibc 2.39")))
            .detect("Linux", "amd64", Collections.<String, String>emptyMap());
        assertEquals("linux-x86_64", x64.getPlatformTuple());
        assertEquals("gnu", x64.getLibc());

        RuntimeHost arm = new RuntimeHostDetector(
            new QueueProbe(RuntimeHostDetector.ProbeResult.success("GNU libc 2.31")))
                .detect("Linux", "aarch64", Collections.<String, String>emptyMap());
        assertEquals("linux-aarch64", arm.getPlatformTuple());

        assertLinuxUnavailable(RuntimeHostDetector.ProbeResult.success("musl libc 1.2.5"));
        assertLinuxUnavailable(RuntimeHostDetector.ProbeResult.unavailable());
        assertLinuxUnavailable(RuntimeHostDetector.ProbeResult.timedOut());
        assertLinuxUnavailable(RuntimeHostDetector.ProbeResult.success("unknown"));
    }

    @Test
    public void selectsAllSixCanonicalManifestPlatforms() throws Exception {
        RuntimeManifest manifest = RuntimeManifest.loadCanonical();
        assertEquals(
            "windows-x86_64",
            manifest.selectPlatform(new RuntimeHost("windows", "x86_64", null, "test"))
                .getId());
        assertEquals(
            "windows-aarch64",
            manifest.selectPlatform(new RuntimeHost("windows", "aarch64", null, "test"))
                .getId());
        assertEquals(
            "macos-x86_64",
            manifest.selectPlatform(new RuntimeHost("macos", "x86_64", null, "test"))
                .getId());
        assertEquals(
            "macos-aarch64",
            manifest.selectPlatform(new RuntimeHost("macos", "aarch64", null, "test"))
                .getId());
        assertEquals(
            "linux-x86_64",
            manifest.selectPlatform(new RuntimeHost("linux", "x86_64", "gnu", "test"))
                .getId());
        assertEquals(
            "linux-aarch64",
            manifest.selectPlatform(new RuntimeHost("linux", "aarch64", "gnu", "test"))
                .getId());
    }

    private static void assertWindowsUnavailable(String osArch, Map<String, String> environment, String message)
        throws Exception {
        try {
            detector().detect("Windows 11", osArch, environment);
            fail();
        } catch (RuntimeHostDetectionException expected) {
            assertTrue(
                expected.getMessage()
                    .contains(message));
        }
    }

    private static void assertLinuxUnavailable(RuntimeHostDetector.ProbeResult result) throws Exception {
        try {
            new RuntimeHostDetector(new QueueProbe(result))
                .detect("Linux", "amd64", Collections.<String, String>emptyMap());
            fail();
        } catch (RuntimeHostDetectionException expected) {
            assertTrue(
                expected.getMessage()
                    .toLowerCase()
                    .contains("libc"));
        }
    }

    private static RuntimeHostDetector detector() {
        return new RuntimeHostDetector(new QueueProbe());
    }

    private static final class QueueProbe implements RuntimeHostDetector.CommandProbe {

        private final Queue<RuntimeHostDetector.ProbeResult> results = new ArrayDeque<RuntimeHostDetector.ProbeResult>();

        QueueProbe(RuntimeHostDetector.ProbeResult... results) {
            Collections.addAll(this.results, results);
        }

        @Override
        public RuntimeHostDetector.ProbeResult execute(String... command) {
            RuntimeHostDetector.ProbeResult result = results.poll();
            return result == null ? RuntimeHostDetector.ProbeResult.unavailable() : result;
        }
    }
}
