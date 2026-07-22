package me.eigenraven.lwjgl3ify.relauncher.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JavaLaunchSelectionTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void manualUnixSelectionResolvesSafeSymlink() throws Exception {
        Path root = temporary.newFolder("unix")
            .toPath();
        Path real = Files.write(root.resolve("java-real"), new byte[] { 1 });
        real.toFile()
            .setExecutable(true, false);
        Path link = root.resolve("java");
        try {
            Files.createSymbolicLink(link, real.getFileName());
        } catch (UnsupportedOperationException exception) {
            link = real;
        }
        JavaLaunchSelection selection = JavaLaunchSelection.manual(link, false);
        assertEquals(
            real.toRealPath()
                .toString(),
            selection.getConsoleExecutable());
        assertEquals(selection.getConsoleExecutable(), selection.getEffectiveExecutable(false));
    }

    @Test
    public void windowsSelectionUsesJavaForLogsAndJavawForStub() throws Exception {
        Path bin = temporary.newFolder("windows-bin")
            .toPath();
        Path java = Files.write(bin.resolve("java.exe"), new byte[] { 1 });
        Path javaw = Files.write(bin.resolve("javaw.exe"), new byte[] { 1 });
        JavaLaunchSelection selection = JavaLaunchSelection.manual(java, true);
        assertEquals(
            java.toRealPath()
                .toString(),
            selection.getEffectiveExecutable(true));
        assertEquals(
            javaw.toRealPath()
                .toString(),
            selection.getEffectiveExecutable(false));
    }

    @Test
    public void bundledSelectionCannotEscapeValidatedRoot() throws Exception {
        Path root = temporary.newFolder("root")
            .toPath();
        Path outside = Files.write(
            temporary.newFile("outside-java")
                .toPath(),
            new byte[] { 1 });
        outside.toFile()
            .setExecutable(true, false);
        try {
            JavaLaunchSelection.bundled(
                new RuntimeInstallResult(
                    true,
                    root,
                    root,
                    root,
                    outside,
                    null,
                    "macos-aarch64",
                    "21.0.11+10-LTS",
                    repeat('a', 64)));
        } catch (RuntimeInstallationException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("escapes"));
            return;
        }
        throw new AssertionError("escaped executable was accepted");
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
