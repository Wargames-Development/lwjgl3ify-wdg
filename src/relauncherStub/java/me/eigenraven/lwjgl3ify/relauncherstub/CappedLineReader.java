package me.eigenraven.lwjgl3ify.relauncherstub;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Keeps draining child output after the display and log limit has been reached. */
final class CappedLineReader {

    private CappedLineReader() {}

    static void drain(BufferedReader reader, long limit, AtomicLong size, AtomicBoolean limitReported,
        Consumer<String> acceptedLine, Runnable reportLimit) throws IOException {
        try (BufferedReader input = reader) {
            String line;
            while ((line = input.readLine()) != null) {
                if (size.addAndGet(line.length() + 1L) > limit) {
                    if (limitReported.compareAndSet(false, true)) reportLimit.run();
                    continue;
                }
                acceptedLine.accept(line);
            }
        }
    }
}
