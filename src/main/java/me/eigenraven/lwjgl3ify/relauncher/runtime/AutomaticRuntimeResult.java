package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.nio.file.Path;

/** Immutable outcome of one automatic packaged-Java preparation attempt. */
public final class AutomaticRuntimeResult {

    public enum Status {
        READY,
        DISABLED,
        UNAVAILABLE,
        FAILED
    }

    private final Status status;
    private final String message;
    private final Throwable failure;
    private final RuntimeBundleLocator.Result bundle;
    private final RuntimeHost host;
    private final RuntimeInstallResult installation;
    private final JavaLaunchSelection selection;
    private final boolean forceSettings;

    private AutomaticRuntimeResult(Status status, String message, Throwable failure, RuntimeBundleLocator.Result bundle,
        RuntimeHost host, RuntimeInstallResult installation, JavaLaunchSelection selection, boolean forceSettings) {
        this.status = status;
        this.message = message;
        this.failure = failure;
        this.bundle = bundle;
        this.host = host;
        this.installation = installation;
        this.selection = selection;
        this.forceSettings = forceSettings;
    }

    static AutomaticRuntimeResult ready(RuntimeBundleLocator.Result bundle, RuntimeHost host,
        RuntimeInstallResult installation, JavaLaunchSelection selection, boolean forceSettings) {
        String action = installation.wasInstalled() ? "installed" : "reused";
        return new AutomaticRuntimeResult(
            Status.READY,
            "Packaged Java ready: " + selection.getPlatformId() + " (" + action + ") at " + selection.getJavaHome(),
            null,
            bundle,
            host,
            installation,
            selection,
            forceSettings);
    }

    static AutomaticRuntimeResult disabled(String message, boolean forceSettings) {
        return new AutomaticRuntimeResult(Status.DISABLED, message, null, null, null, null, null, forceSettings);
    }

    static AutomaticRuntimeResult unavailable(String message, RuntimeBundleLocator.Result bundle, RuntimeHost host,
        boolean forceSettings) {
        return new AutomaticRuntimeResult(Status.UNAVAILABLE, message, null, bundle, host, null, null, forceSettings);
    }

    static AutomaticRuntimeResult failed(String message, Throwable failure, RuntimeBundleLocator.Result bundle,
        RuntimeHost host, boolean forceSettings) {
        return new AutomaticRuntimeResult(Status.FAILED, message, failure, bundle, host, null, null, forceSettings);
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public Throwable getFailure() {
        return failure;
    }

    public RuntimeBundleLocator.Result getBundle() {
        return bundle;
    }

    public RuntimeHost getHost() {
        return host;
    }

    public RuntimeInstallResult getInstallation() {
        return installation;
    }

    public JavaLaunchSelection getSelection() {
        return selection;
    }

    public boolean isForceSettings() {
        return forceSettings;
    }

    public boolean isReady() {
        return status == Status.READY;
    }

    public boolean isFatalFailure() {
        return status == Status.FAILED;
    }

    public Path getBundlePath() {
        return bundle == null ? null : bundle.getPath();
    }
}
