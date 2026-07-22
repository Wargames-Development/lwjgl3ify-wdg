package me.eigenraven.lwjgl3ify.relauncher.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import me.eigenraven.lwjgl3ify.relauncher.Relauncher;

public class ManagedRuntimePropertiesTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void bundledSelectionAddsOnlyNonSecretManagedIdentityProperties() throws Exception {
        Path root = temporary.newFolder("bundled")
            .toPath();
        Path java = Files.write(root.resolve("java"), new byte[] { 1 });
        java.toFile()
            .setExecutable(true, false);
        RuntimeInstallResult install = new RuntimeInstallResult(
            false,
            root,
            root,
            root,
            java,
            null,
            "macos-aarch64",
            "21.0.11+10-LTS",
            repeat('a', 64));
        JavaLaunchSelection selection = JavaLaunchSelection.bundled(install);
        List<String> command = new ArrayList<String>();
        Relauncher.appendManagedRuntimeProperties(command, selection);
        assertTrue(command.contains("-Dlwjgl3ify.relauncher.managedRuntime=true"));
        assertTrue(command.contains("-Dlwjgl3ify.relauncher.managedPlatform=macos-aarch64"));
        assertTrue(command.contains("-Dlwjgl3ify.relauncher.managedRuntimeVersion=21.0.11+10-LTS"));
        assertFalse(
            command.toString()
                .contains(root.toString()));
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
