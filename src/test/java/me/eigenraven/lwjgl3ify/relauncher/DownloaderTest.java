package me.eigenraven.lwjgl3ify.relauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DownloaderTest {

    @Test
    public void bundledLwjgl3ifyArtifactsNeverRequireNetworkDownload() {
        assertTrue(
            Downloader
                .isBundledLwjgl3ifyArtifact("com.github.GTNewHorizons:lwjgl3ify:3.0.28-master.3+local:forgePatches"));
        assertTrue(Downloader.isBundledLwjgl3ifyArtifact("com.github.GTNewHorizons:lwjgl3ify:3.0.28"));
        assertFalse(Downloader.isBundledLwjgl3ifyArtifact("com.github.GTNewHorizons:GTNHLib:0.11.0"));
        assertFalse(Downloader.isBundledLwjgl3ifyArtifact("org.lwjgl:lwjgl:3.4.2"));
    }
}
