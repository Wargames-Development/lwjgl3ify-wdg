package me.eigenraven.lwjgl3ify.relauncher.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URL;

import org.junit.Test;

public class RuntimeArchiveDownloaderTest {

    @Test
    public void downloadUrlUsesExactManifestReleaseAndPlatformArchive() throws Exception {
        RuntimeManifest manifest = RuntimeManifest.loadCanonical();
        RuntimePlatform platform = manifest.getPlatform("macos-aarch64");
        URL url = RuntimeArchiveDownloader.releaseUrl(manifest, platform);
        assertEquals("https", url.getProtocol());
        assertEquals("github.com", url.getHost());
        assertTrue(
            url.toExternalForm()
                .contains("/jdk-21.0.11%2B10/"));
        assertTrue(
            url.toExternalForm()
                .endsWith("/" + platform.getInputFilename()));
        RuntimeArchiveDownloader.requireAllowed(url);
    }

    @Test
    public void redirectsCannotUseHttpOrUnrelatedHosts() throws Exception {
        RuntimeArchiveDownloader.requireAllowed(new URL("https://release-assets.githubusercontent.com/file"));
        assertRejected("http://github.com/file");
        assertRejected("https://github.com.evil.example/file");
        assertRejected("https://github.com:8443/file");
        assertRejected("https://user@github.com/file");
    }

    private static void assertRejected(String address) throws Exception {
        try {
            RuntimeArchiveDownloader.requireAllowed(new URL(address));
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Unexpectedly accepted " + address);
    }
}
