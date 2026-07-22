package me.eigenraven.lwjgl3ify.relauncher.runtime;

/** Immutable canonical host description used for manifest platform selection. */
public final class RuntimeHost {

    private final String operatingSystem;
    private final String architecture;
    private final String libc;
    private final String diagnostic;

    RuntimeHost(String operatingSystem, String architecture, String libc, String diagnostic) {
        this.operatingSystem = operatingSystem;
        this.architecture = architecture;
        this.libc = libc;
        this.diagnostic = diagnostic;
    }

    public String getOperatingSystem() {
        return operatingSystem;
    }

    public String getArchitecture() {
        return architecture;
    }

    public String getLibc() {
        return libc;
    }

    public String getDiagnostic() {
        return diagnostic;
    }

    public String getPlatformTuple() {
        return operatingSystem + "-" + architecture;
    }

    @Override
    public String toString() {
        return getPlatformTuple() + (libc == null ? "" : " (libc=" + libc + ")") + "; " + diagnostic;
    }
}
