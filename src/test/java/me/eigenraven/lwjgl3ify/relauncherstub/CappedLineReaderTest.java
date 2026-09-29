package me.eigenraven.lwjgl3ify.relauncherstub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

public class CappedLineReaderTest {

    @Test(timeout = 10000)
    public void stillDrainsTheChildPipeAfterOutputLimit() throws Exception {
        final PipedInputStream input = new PipedInputStream(1024);
        final PipedOutputStream output = new PipedOutputStream(input);
        final AtomicBoolean writerFinished = new AtomicBoolean(false);
        final Thread writer = new Thread(() -> {
            try (PipedOutputStream stream = output) {
                byte[] line = "child output\n".getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < 10000; i++) stream.write(line);
                writerFinished.set(true);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        writer.setDaemon(true);
        writer.start();

        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger notices = new AtomicInteger();
        CappedLineReader.drain(
            new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)),
            130,
            new AtomicLong(),
            new AtomicBoolean(),
            line -> accepted.incrementAndGet(),
            notices::incrementAndGet);
        writer.join(1000);

        assertFalse(writer.isAlive());
        assertEquals(true, writerFinished.get());
        assertEquals(10, accepted.get());
        assertEquals(1, notices.get());
    }
}
