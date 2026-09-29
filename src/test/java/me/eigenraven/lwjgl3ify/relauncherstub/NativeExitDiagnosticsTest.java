package me.eigenraven.lwjgl3ify.relauncherstub;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NativeExitDiagnosticsTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void attributesOnlyTheCurrentChildsFatalReport() throws Exception {
        Path game = temporary.newFolder("game")
            .toPath();
        Path logs = Files.createDirectory(game.resolve("logs"));
        Path oldReport = Files.createFile(logs.resolve("lwjgl3ify-hs_err_pid123.log"));
        Path currentReport = Files.createFile(logs.resolve("lwjgl3ify-hs_err_pid456.log"));
        Path childLog = logs.resolve("lwjgl3ify-java21-child.log");
        long start = System.currentTimeMillis();
        Files.setLastModifiedTime(oldReport, FileTime.fromMillis(start - 60_000L));

        String message = NativeExitDiagnostics.buildFailureMessage(134, childLog, 456, start);
        assertTrue(message.contains(currentReport.toString()));
        assertTrue(!message.contains(oldReport.toString()));
        assertTrue(
            !NativeExitDiagnostics.buildFailureMessage(134, childLog, 789, start)
                .contains(oldReport.toString()));
        assertTrue(
            !NativeExitDiagnostics.buildFailureMessage(134, childLog, 123, start)
                .contains(oldReport.toString()));
    }

    @Test
    public void decodesWindowsFatalCallbackStatus() {
        assertTrue(
            NativeExitDiagnostics.describe(-1073740771)
                .contains("0xC000041D"));
        assertTrue(
            NativeExitDiagnostics.describe(-1073740771)
                .contains("STATUS_FATAL_USER_CALLBACK_EXCEPTION"));
    }

    @Test
    public void explainsUnixAbortExit() {
        assertTrue(
            NativeExitDiagnostics.describe(134)
                .contains("SIGABRT"));
    }

    @Test
    public void leavesUnknownExitUnclassified() {
        assertTrue(
            NativeExitDiagnostics.describe(-805306369)
                .contains("0xCFFFFFFF"));
        assertTrue(
            NativeExitDiagnostics.describe(-805306369)
                .contains("cause undetermined"));
    }
}
