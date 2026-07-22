package me.eigenraven.lwjgl3ify.relauncher.runtime;

/** Host is unsupported or cannot be detected confidently for automatic packaged Java. */
public final class RuntimeHostDetectionException extends Exception {

    public RuntimeHostDetectionException(String message) {
        super(message);
    }
}
