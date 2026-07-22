package me.eigenraven.lwjgl3ify.relauncherstub;

import java.util.concurrent.atomic.AtomicReference;

import com.gtnewhorizons.retrofuturabootstrap.MainStartOnFirstThread;

/**
 * Converts an uncaught failure on RFB's delegated main thread into a nonzero JVM result.
 */
public final class SupervisedRfbClientMain {

    private SupervisedRfbClientMain() {}

    public static void main(String[] args) {
        final AtomicReference<Throwable> delegatedFailure = new AtomicReference<Throwable>();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if ("RFB-Main".equals(thread.getName())) {
                delegatedFailure.compareAndSet(null, failure);
                return;
            }
            if (previous != null) {
                previous.uncaughtException(thread, failure);
            } else {
                failure.printStackTrace(System.err);
            }
        });
        try {
            MainStartOnFirstThread.main(args);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
        final Throwable failure = delegatedFailure.get();
        if (failure != null) {
            throw new RuntimeException("RFB delegated client main failed", failure);
        }
    }
}
