package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Immutable Java executable selection for one relaunch. */
public final class JavaLaunchSelection {

    public enum Source {
        BUNDLED,
        MANUAL,
        SYSTEM_FALLBACK
    }

    private final Source source;
    private final String consoleExecutable;
    private final String guiExecutable;
    private final Path javaHome;
    private final Path installationRoot;
    private final String platformId;
    private final String runtimeVersion;

    private JavaLaunchSelection(Source source, String consoleExecutable, String guiExecutable, Path javaHome,
        Path installationRoot, String platformId, String runtimeVersion) {
        this.source = source;
        this.consoleExecutable = consoleExecutable;
        this.guiExecutable = guiExecutable;
        this.javaHome = javaHome;
        this.installationRoot = installationRoot;
        this.platformId = platformId;
        this.runtimeVersion = runtimeVersion;
    }

    public static JavaLaunchSelection bundled(RuntimeInstallResult result) throws RuntimeInstallationException {
        if (result == null) throw new NullPointerException("result");
        Path root = result.getInstallationRoot()
            .toAbsolutePath()
            .normalize();
        Path javaHome = result.getJavaHome()
            .toAbsolutePath()
            .normalize();
        Path console = validateBundledExecutable(root, result.getJavaExecutable(), "Java executable");
        Path gui = result.getWindowsGuiExecutable() == null ? null
            : validateBundledExecutable(root, result.getWindowsGuiExecutable(), "Windows GUI Java executable");
        return new JavaLaunchSelection(
            Source.BUNDLED,
            console.toString(),
            gui == null ? null : gui.toString(),
            javaHome,
            root,
            result.getPlatformId(),
            result.getRuntimeVersion());
    }

    public static JavaLaunchSelection manual(Path executable, boolean windows) throws RuntimeInstallationException {
        Path normalized = validateManualExecutable(executable);
        Path console = normalized;
        Path gui = normalized;
        if (windows) {
            String filename = normalized.getFileName()
                .toString();
            Path siblingDirectory = normalized.getParent();
            if (filename.equalsIgnoreCase("javaw.exe") || filename.equalsIgnoreCase("javaw")) {
                Path candidate = siblingDirectory.resolve("java.exe");
                if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(candidate)) {
                    console = candidate.toAbsolutePath()
                        .normalize();
                }
            } else if (filename.equalsIgnoreCase("java.exe") || filename.equalsIgnoreCase("java")) {
                Path candidate = siblingDirectory.resolve("javaw.exe");
                if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(candidate)) {
                    gui = candidate.toAbsolutePath()
                        .normalize();
                }
            }
        }
        return new JavaLaunchSelection(
            Source.MANUAL,
            console.toString(),
            windows ? gui.toString() : console.toString(),
            normalized.getParent(),
            null,
            null,
            null);
    }

    public static JavaLaunchSelection systemFallback(boolean windows) {
        return new JavaLaunchSelection(
            Source.SYSTEM_FALLBACK,
            windows ? "java" : "java",
            windows ? "javaw" : "java",
            null,
            null,
            null,
            null);
    }

    private static Path validateBundledExecutable(Path root, Path executable, String label)
        throws RuntimeInstallationException {
        Path normalized = executable.toAbsolutePath()
            .normalize();
        if (!normalized.startsWith(root)) throw new RuntimeInstallationException(label + " escapes packaged runtime");
        if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new RuntimeInstallationException(label + " is missing or unsafe: " + normalized);
        }
        if (!isWindowsPath(normalized) && !Files.isExecutable(normalized)) {
            throw new RuntimeInstallationException(label + " is not executable: " + normalized);
        }
        return normalized;
    }

    private static Path validateManualExecutable(Path executable) throws RuntimeInstallationException {
        if (executable == null) throw new RuntimeInstallationException("Manual Java executable is not selected");
        Path normalized = executable.toAbsolutePath()
            .normalize();
        try {
            normalized = normalized.toRealPath();
        } catch (java.io.IOException exception) {
            throw new RuntimeInstallationException(
                "Manual Java executable is missing or unreadable: " + normalized,
                exception);
        }
        if (!Files.isRegularFile(normalized)) {
            throw new RuntimeInstallationException("Manual Java executable is not a regular file: " + normalized);
        }
        if (!isWindowsPath(normalized) && !Files.isExecutable(normalized)) {
            throw new RuntimeInstallationException("Manual Java executable is not executable: " + normalized);
        }
        return normalized;
    }

    private static boolean isWindowsPath(Path path) {
        String name = path.getFileName()
            .toString()
            .toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".exe");
    }

    public Source getSource() {
        return source;
    }

    public String getConsoleExecutable() {
        return consoleExecutable;
    }

    public String getGuiExecutable() {
        return guiExecutable;
    }

    public String getEffectiveExecutable(boolean forwardLogs) {
        return forwardLogs || guiExecutable == null ? consoleExecutable : guiExecutable;
    }

    public Path getJavaHome() {
        return javaHome;
    }

    public Path getInstallationRoot() {
        return installationRoot;
    }

    public String getPlatformId() {
        return platformId;
    }

    public String getRuntimeVersion() {
        return runtimeVersion;
    }
}
