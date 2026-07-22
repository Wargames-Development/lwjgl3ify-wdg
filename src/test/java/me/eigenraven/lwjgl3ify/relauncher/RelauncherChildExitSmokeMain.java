package me.eigenraven.lwjgl3ify.relauncher;

import java.io.File;
import java.util.Arrays;

public final class RelauncherChildExitSmokeMain {

    private RelauncherChildExitSmokeMain() {}

    public static void main(String[] args) throws Exception {
        final int requestedExit = Integer.parseInt(args[0]);
        final String javaBinary = new File(
            System.getProperty("java.home"),
            "bin" + File.separator + (isWindows() ? "java.exe" : "java")).getAbsolutePath();
        final ProcessBuilder builder = new ProcessBuilder(
            Arrays.asList(
                javaBinary,
                "-cp",
                System.getProperty("java.class.path"),
                ProcessExitFixtureMain.class.getName(),
                Integer.toString(requestedExit),
                "0",
                "32"));
        builder.inheritIO();
        final Process child = builder.start();
        final int observedExit = ChildProcessSupervisor.waitFor(child);
        System.out.println(
            "Relauncher child exit propagation fixture: requested=" + requestedExit + " observed=" + observedExit);
        System.exit(observedExit);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT)
            .contains("win");
    }
}
