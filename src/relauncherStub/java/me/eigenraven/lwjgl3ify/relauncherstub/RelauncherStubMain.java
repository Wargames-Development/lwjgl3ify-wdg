package me.eigenraven.lwjgl3ify.relauncherstub;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import me.eigenraven.lwjgl3ify.relauncher.ChildProcessSupervisor;

/**
 * Run with arguments of [parentPid, show console [true/false], java binary, java arguments, child log path].
 * Needs modern Java to access the ProcessHandle API.
 */
public class RelauncherStubMain {

    public RelauncherStubMain() {}

    public int run(String[] args) throws Throwable {
        if (args.length < 5) {
            System.err.println("Missing arguments");
            return 2;
        }
        final long parentPid = Long.parseLong(args[0]);
        final boolean showConsole = Boolean.parseBoolean(args[1]);
        final String javaBinary = args[2];
        final String javaArgFile = args[3];
        final Path childLog = RelaunchLogSupport.prepare(Paths.get(args[4]));
        final String[] javaCmdline = new String[] { javaBinary, "@" + javaArgFile };
        final ProcessHandle myProcess = ProcessHandle.current();
        final ProcessHandle parentProcess = ProcessHandle.of(parentPid)
            .orElse(
                myProcess.parent()
                    .orElse(null));

        RelaunchLogSupport.append(
            childLog,
            "[lwjgl3ify-wdg] " + Instant.now() + " waiting for Java 8 parent " + parentPid + " to exit");
        System.out.println("quit"); // notify the parent that we're ready to wait on it
        if (parentProcess != null) {
            parentProcess.onExit()
                .get();
        } else {
            Thread.sleep(1000);
        }

        final ProcessBuilder childBuilder = new ProcessBuilder(javaCmdline);
        final Process child;
        RelaunchLogSupport.append(
            childLog,
            "[lwjgl3ify-wdg] launching managed Java child; graphicalConsole=" + showConsole
                + " executable="
                + javaBinary);
        if (showConsole) {
            childBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE);
            childBuilder.redirectError(ProcessBuilder.Redirect.PIPE);
            child = childBuilder.start();
            new GraphicalConsole(child.getInputStream(), child.getErrorStream(), child, childLog);
        } else {
            childBuilder.redirectErrorStream(true);
            childBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(childLog.toFile()));
            child = childBuilder.start();
        }
        final int exitCode = ChildProcessSupervisor.waitFor(child);
        RelaunchLogSupport.append(childLog, "[lwjgl3ify-wdg] managed Java child exited with code " + exitCode);
        System.out.println("Relaunched Java child exited with code " + exitCode);
        if (exitCode != 0 && !showConsole) {
            showFailure(
                "The managed Java game process exited with code " + exitCode + ".\n\nDiagnostic log:\n" + childLog,
                "lwjgl3ify managed Java failure");
        }
        return exitCode;
    }

    public static void main(String[] args) throws Throwable {
        Path childLog = null;
        try {
            if (args.length >= 5) childLog = Paths.get(args[4])
                .toAbsolutePath()
                .normalize();
            System.exit(new RelauncherStubMain().run(args));
        } catch (Throwable exception) {
            final StringWriter traceWriter = new StringWriter();
            exception.printStackTrace(new PrintWriter(traceWriter));
            RelaunchLogSupport.append(
                childLog,
                "[lwjgl3ify-wdg] relauncher stub failure: " + exception + System.lineSeparator() + traceWriter);
            final String message = childLog == null ? exception.toString()
                : exception + "\n\nDiagnostic log:\n" + childLog;
            showFailure(message, "lwjgl3ify relauncher failure");
            System.exit(1);
        }
    }

    private static void showFailure(String message, String title) throws Exception {
        SwingUtilities
            .invokeAndWait(() -> { JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE); });
    }
}
