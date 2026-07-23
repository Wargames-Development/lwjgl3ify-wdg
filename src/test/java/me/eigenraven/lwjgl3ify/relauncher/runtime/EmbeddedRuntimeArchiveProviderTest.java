package me.eigenraven.lwjgl3ify.relauncher.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Test;

public class EmbeddedRuntimeArchiveProviderTest {

    @Test
    public void primaryMatrixContainsOnlyCommonDesktopPlatforms() {
        EmbeddedRuntimeArchiveProvider provider = new EmbeddedRuntimeArchiveProvider();
        assertTrue(provider.isPrimaryPlatform("windows-x86_64"));
        assertTrue(provider.isPrimaryPlatform("linux-x86_64"));
        assertTrue(provider.isPrimaryPlatform("macos-x86_64"));
        assertTrue(provider.isPrimaryPlatform("macos-aarch64"));
        assertFalse(provider.isPrimaryPlatform("windows-aarch64"));
        assertFalse(provider.isPrimaryPlatform("linux-aarch64"));
        assertFalse(provider.isPrimaryPlatform("windows-x86"));
    }

    @Test
    public void resourcePathUsesCanonicalNestedRuntimeLocation() {
        RuntimePlatform platform = new RuntimePlatform(
            "linux-x86_64",
            "linux",
            "x86_64",
            Collections.singleton("Linux"),
            Collections.singleton("amd64"),
            "gnu",
            "runtimes/linux-x86_64.tar.gz",
            "tar.gz",
            1L,
            repeat('0', 64),
            "jdk",
            ".",
            "bin/java",
            null,
            "release",
            Collections.<String, String>emptyMap());
        assertTrue(
            EmbeddedRuntimeArchiveProvider.resourcePath(platform)
                .equals("/me/eigenraven/lwjgl3ify/relauncher/runtime/embedded/" + "runtimes/linux-x86_64.tar.gz"));
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int index = 0; index < count; index++) out.append(value);
        return out.toString();
    }
}
