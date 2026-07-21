package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.io.IOException;

/** A checked failure raised while verifying or installing a packaged Java runtime. */
public final class RuntimeInstallationException extends IOException {

    public RuntimeInstallationException(String message) {
        super(message);
    }

    public RuntimeInstallationException(String message, Throwable cause) {
        super(message, cause);
    }
}
