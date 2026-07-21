package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Gradle-only real archive smoke driver. It never executes the installed Java binary. */
public final class RuntimeInstallerSmokeMain {

    private RuntimeInstallerSmokeMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected: <normalized-bundle> <platform-id> <cache-root>");
        }
        Path bundle = Paths.get(args[0])
            .toAbsolutePath()
            .normalize();
        String platform = args[1];
        Path cache = Paths.get(args[2])
            .toAbsolutePath()
            .normalize();
        RuntimeInstaller installer = new RuntimeInstaller();
        RuntimeInstallResult first = installer.install(bundle, platform, cache);
        RuntimeInstallResult second = installer.install(bundle, platform, cache);
        if (!first.wasInstalled()) throw new IllegalStateException("First installation call did not install");
        if (!second.wasReused()) throw new IllegalStateException("Second installation call did not reuse");
        if (!first.getJavaExecutable()
            .equals(second.getJavaExecutable())) {
            throw new IllegalStateException("Installer calls returned different Java executable paths");
        }
        Path marker = first.getInstallationRoot()
            .resolve(RuntimeInstallMarker.FILE_NAME);
        if (!Files.isRegularFile(marker)) throw new IllegalStateException("Install marker is missing");
        if (!Files.isRegularFile(first.getJavaExecutable()))
            throw new IllegalStateException("Java executable is missing");
        if (!first.getPlatformId()
            .startsWith("windows-") && !Files.isExecutable(first.getJavaExecutable())) {
            throw new IllegalStateException("Unix Java executable is not executable");
        }

        System.out.println("Java runtime installation verification passed.");
        System.out.println("Platform: " + platform);
        System.out.println("First call: installed=" + first.wasInstalled() + ", reused=" + first.wasReused());
        System.out.println("Second call: installed=" + second.wasInstalled() + ", reused=" + second.wasReused());
        System.out.println("Installation root: " + first.getInstallationRoot());
        System.out.println("Runtime archive root: " + first.getRuntimeArchiveRoot());
        System.out.println("Java home: " + first.getJavaHome());
        System.out.println("Java executable: " + first.getJavaExecutable());
        System.out.println("Windows GUI executable: " + first.getWindowsGuiExecutable());
        System.out.println("Marker: " + marker);
        System.out.println(
            "Release file: " + first.getJavaHome()
                .resolve("release"));
        System.out.println("Installed bytes: " + directorySize(first.getInstallationRoot()));
        System.out.println("No Java executable was run.");
    }

    private static long directorySize(Path root) throws Exception {
        final long[] total = new long[] { 0L };
        java.nio.file.Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                java.nio.file.attribute.BasicFileAttributes attrs) {
                total[0] += attrs.size();
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }
}
