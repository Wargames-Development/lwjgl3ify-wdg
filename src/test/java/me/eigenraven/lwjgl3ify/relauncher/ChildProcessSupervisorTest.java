package me.eigenraven.lwjgl3ify.relauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class ChildProcessSupervisorTest {

    @Test
    public void preservesZeroAndNonzeroExitCodes() throws Exception {
        assertEquals(0, ChildProcessSupervisor.waitFor(startFixture(0, 0L, 0)));
        assertEquals(1, ChildProcessSupervisor.waitFor(startFixture(1, 0L, 0)));
        assertEquals(37, ChildProcessSupervisor.waitFor(startFixture(37, 0L, 0)));
    }

    @Test
    public void inheritedOutputDoesNotDeadlockAndCompletionIsObserved() throws Exception {
        final long started = System.nanoTime();
        final int exitCode = ChildProcessSupervisor.waitFor(startFixture(0, 150L, 256));
        final long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        assertEquals(0, exitCode);
        assertTrue("Supervisor returned before child completion", elapsedMillis >= 100L);
    }

    @Test
    public void interruptionPreservesFlagAndTerminatesChild() throws Exception {
        final Process child = startFixture(0, 30_000L, 0);
        final AtomicBoolean interrupted = new AtomicBoolean(false);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final Thread waiter = new Thread(() -> {
            try {
                ChildProcessSupervisor.waitFor(child);
                failure.set(new AssertionError("waitFor unexpectedly completed"));
            } catch (InterruptedException expected) {
                interrupted.set(
                    Thread.currentThread()
                        .isInterrupted());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "child-supervisor-interruption-test");
        waiter.start();
        Thread.sleep(150L);
        waiter.interrupt();
        waiter.join(10_000L);
        assertFalse("Waiter thread remained alive", waiter.isAlive());
        assertTrue("Interrupted state was not preserved", interrupted.get());
        assertFalse("Child process was left alive", child.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static Process startFixture(int exitCode, long delayMillis, int lines) throws Exception {
        final String javaBinary = new File(
            System.getProperty("java.home"),
            "bin" + File.separator + (isWindows() ? "java.exe" : "java")).getAbsolutePath();
        final ProcessBuilder builder = new ProcessBuilder(
            Arrays.asList(
                javaBinary,
                "-cp",
                System.getProperty("java.class.path"),
                ProcessExitFixtureMain.class.getName(),
                Integer.toString(exitCode),
                Long.toString(delayMillis),
                Integer.toString(lines)));
        builder.inheritIO();
        return builder.start();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT)
            .contains("win");
    }
}
