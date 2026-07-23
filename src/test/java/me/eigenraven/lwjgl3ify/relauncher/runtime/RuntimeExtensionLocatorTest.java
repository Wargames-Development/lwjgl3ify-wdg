package me.eigenraven.lwjgl3ify.relauncher.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class RuntimeExtensionLocatorTest {

    @Test
    public void exactPlatformExtensionNameIsUsedWithoutDirectoryScanning() throws Exception {
        RuntimePlatform platform = RuntimeManifest.loadCanonical()
            .getPlatform("windows-aarch64");
        Path game = Files.createTempDirectory("runtime-extension-game");
        try {
            RuntimeExtensionLocator locator = new RuntimeExtensionLocator();
            assertNull(locator.locate(game, platform));
            Path extension = game.resolve(RuntimeExtensionLocator.CANONICAL_DIRECTORY)
                .resolve(RuntimeExtensionLocator.canonicalFileName(platform));
            Files.createDirectories(extension.getParent());
            Files.write(extension, new byte[] { 1 });
            RuntimeBundleLocator.Result result = locator.locate(game, platform);
            assertTrue(result.isAvailable());
            assertEquals(RuntimeBundleLocator.Kind.DIRECT_ARCHIVE, result.getKind());
            assertEquals(RuntimeBundleLocator.Source.EXTENSION_DIRECTORY, result.getSource());
            assertEquals(
                extension.toAbsolutePath()
                    .normalize(),
                result.getPath());
        } finally {
            RuntimePathSafety.deleteRecursively(game, game.getParent());
        }
    }

    @Test
    public void canonicalNamesDescribeArchitectureRatherThanThirtyTwoBitSupport() throws Exception {
        RuntimeManifest manifest = RuntimeManifest.loadCanonical();
        assertEquals(
            "lwjgl3ify-wdg-java21-linux-aarch64.tar.gz",
            RuntimeExtensionLocator.canonicalFileName(manifest.getPlatform("linux-aarch64")));
        assertEquals(
            "lwjgl3ify-wdg-java21-windows-aarch64.zip",
            RuntimeExtensionLocator.canonicalFileName(manifest.getPlatform("windows-aarch64")));
    }
}
