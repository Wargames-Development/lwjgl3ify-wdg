package me.eigenraven.lwjgl3ify.relauncher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RelauncherConfigTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void oldConfigGainsBundledDefaultWithoutLosingValues() throws Exception {
        Path config = temporary.newFolder("config")
            .toPath()
            .resolve("lwjgl3ify-relauncher.json");
        Files.write(
            config,
            ("{\n" + "  \"javaInstallationsCache\": [\"/one/java\", \"/two/java\"],\n"
                + "  \"javaInstallation\": 1,\n"
                + "  \"minMemoryMB\": 1024,\n"
                + "  \"maxMemoryMB\": 6144,\n"
                + "  \"customOptions\": [\"-Dexample=value\"],\n"
                + "  \"hideSettingsOnLaunch\": true\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        byte[] original = Files.readAllBytes(config);

        RelauncherConfig.load(config);

        assertTrue(RelauncherConfig.config.useBundledJava);
        assertArrayEquals(new String[] { "/one/java", "/two/java" }, RelauncherConfig.config.javaInstallationsCache);
        assertEquals(1, RelauncherConfig.config.javaInstallation);
        assertEquals(1024, RelauncherConfig.config.minMemoryMB);
        assertEquals(6144, RelauncherConfig.config.maxMemoryMB);
        assertArrayEquals(new String[] { "-Dexample=value" }, RelauncherConfig.config.customOptions);
        assertTrue(RelauncherConfig.config.hideSettingsOnLaunch);
        assertArrayEquals(original, Files.readAllBytes(config));
    }

    @Test
    public void nullArraysGcAndInvalidIndexAreNormalized() throws Exception {
        Path config = temporary.newFolder("unusual")
            .toPath()
            .resolve("lwjgl3ify-relauncher.json");
        Files.write(
            config,
            ("{\"javaInstallationsCache\":null,\"customOptions\":null,\"garbageCollector\":null,"
                + "\"javaInstallation\":99,\"useBundledJava\":false}").getBytes(StandardCharsets.UTF_8));
        RelauncherConfig.load(config);
        assertEquals(0, RelauncherConfig.config.javaInstallationsCache.length);
        assertEquals(0, RelauncherConfig.config.customOptions.length);
        assertEquals(-1, RelauncherConfig.config.javaInstallation);
        assertEquals(RelauncherConfig.GCOption.G1GC, RelauncherConfig.config.garbageCollector);
        assertFalse(RelauncherConfig.config.useBundledJava);
    }

    @Test
    public void malformedJsonFailsWithoutReplacingFile() throws Exception {
        Path config = temporary.newFolder("broken")
            .toPath()
            .resolve("lwjgl3ify-relauncher.json");
        byte[] broken = "{ not json".getBytes(StandardCharsets.UTF_8);
        Files.write(config, broken);
        try {
            RelauncherConfig.load(config);
            fail();
        } catch (RuntimeException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("Malformed relauncher configuration"));
        }
        assertArrayEquals(broken, Files.readAllBytes(config));
    }
}
