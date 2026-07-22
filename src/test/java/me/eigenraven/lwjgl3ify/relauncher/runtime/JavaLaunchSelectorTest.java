package me.eigenraven.lwjgl3ify.relauncher.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import me.eigenraven.lwjgl3ify.relauncher.RelauncherConfig;

public class JavaLaunchSelectorTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void preservesConfiguredManualPath() throws Exception {
        Path java = Files.write(
            temporary.newFile("java")
                .toPath(),
            new byte[] { 1 });
        java.toFile()
            .setExecutable(true, false);
        RelauncherConfig.ConfigObject config = new RelauncherConfig.ConfigObject();
        config.javaInstallationsCache = new String[] { java.toString() };
        config.javaInstallation = 0;
        JavaLaunchSelection selection = JavaLaunchSelector.selectManual(config, false, false);
        assertEquals(JavaLaunchSelection.Source.MANUAL, selection.getSource());
        assertEquals(
            java.toRealPath()
                .toString(),
            selection.getConsoleExecutable());
    }

    @Test
    public void invalidIndexUsesSystemFallbackOnlyWhenExplicitlyAllowed() throws Exception {
        RelauncherConfig.ConfigObject config = new RelauncherConfig.ConfigObject();
        config.javaInstallationsCache = new String[0];
        config.javaInstallation = 99;
        try {
            JavaLaunchSelector.selectManual(config, false, false);
            fail();
        } catch (RuntimeInstallationException expected) {
            // Expected: hidden settings cannot silently use the system command.
        }
        assertEquals(
            JavaLaunchSelection.Source.SYSTEM_FALLBACK,
            JavaLaunchSelector.selectManual(config, false, true)
                .getSource());
    }
}
