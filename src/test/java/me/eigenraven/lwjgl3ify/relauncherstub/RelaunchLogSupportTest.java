package me.eigenraven.lwjgl3ify.relauncherstub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RelaunchLogSupportTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void hiddenChildDiagnosticsArePersistedAndRotated() throws Exception {
        Path log = temporary.getRoot()
            .toPath()
            .resolve("logs/lwjgl3ify-java21-child.log");
        RelaunchLogSupport.append(log, "first diagnostic line");
        assertEquals("first diagnostic line\n", new String(Files.readAllBytes(log), StandardCharsets.UTF_8));

        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "rw")) {
            file.setLength(RelaunchLogSupport.MAX_LOG_BYTES);
        }
        RelaunchLogSupport.append(log, "new log after rotation");

        assertTrue(Files.isRegularFile(log.resolveSibling(log.getFileName() + ".1")));
        assertEquals("new log after rotation\n", new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
    }
}
