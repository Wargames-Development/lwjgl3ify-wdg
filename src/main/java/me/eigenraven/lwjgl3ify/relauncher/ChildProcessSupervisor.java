package me.eigenraven.lwjgl3ify.relauncher;

import java.util.concurrent.TimeUnit;

/** Waits for a child process while preserving interruption and avoiding orphaned children. */
public final class ChildProcessSupervisor {

    private static final long TERMINATION_GRACE_SECONDS = 5L;

    private ChildProcessSupervisor() {}

    public static int waitFor(Process process) throws InterruptedException {
        try {
            return process.waitFor();
        } catch (InterruptedException exception) {
            terminate(process);
            Thread.currentThread()
                .interrupt();
            throw exception;
        }
    }

    public static void terminate(Process process) {
        if (!process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(TERMINATION_GRACE_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(TERMINATION_GRACE_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread()
                .interrupt();
        }
    }
}
