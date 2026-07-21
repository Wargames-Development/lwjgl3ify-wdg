package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.nio.file.Path;

/** Immutable result of an explicit runtime installation or validated cache reuse. */
public final class RuntimeInstallResult {

    private final boolean installed;
    private final Path installationRoot;
    private final Path runtimeArchiveRoot;
    private final Path javaHome;
    private final Path javaExecutable;
    private final Path windowsGuiExecutable;
    private final String platformId;
    private final String runtimeVersion;
    private final String archiveSha256;

    RuntimeInstallResult(boolean installed, Path installationRoot, Path runtimeArchiveRoot, Path javaHome,
        Path javaExecutable, Path windowsGuiExecutable, String platformId, String runtimeVersion,
        String archiveSha256) {
        this.installed = installed;
        this.installationRoot = installationRoot.toAbsolutePath()
            .normalize();
        this.runtimeArchiveRoot = runtimeArchiveRoot.toAbsolutePath()
            .normalize();
        this.javaHome = javaHome.toAbsolutePath()
            .normalize();
        this.javaExecutable = javaExecutable.toAbsolutePath()
            .normalize();
        this.windowsGuiExecutable = windowsGuiExecutable == null ? null
            : windowsGuiExecutable.toAbsolutePath()
                .normalize();
        this.platformId = platformId;
        this.runtimeVersion = runtimeVersion;
        this.archiveSha256 = archiveSha256;
    }

    public boolean wasInstalled() {
        return installed;
    }

    public boolean wasReused() {
        return !installed;
    }

    public Path getInstallationRoot() {
        return installationRoot;
    }

    public Path getRuntimeArchiveRoot() {
        return runtimeArchiveRoot;
    }

    public Path getJavaHome() {
        return javaHome;
    }

    public Path getJavaExecutable() {
        return javaExecutable;
    }

    public Path getWindowsGuiExecutable() {
        return windowsGuiExecutable;
    }

    public String getPlatformId() {
        return platformId;
    }

    public String getRuntimeVersion() {
        return runtimeVersion;
    }

    public String getArchiveSha256() {
        return archiveSha256;
    }
}
