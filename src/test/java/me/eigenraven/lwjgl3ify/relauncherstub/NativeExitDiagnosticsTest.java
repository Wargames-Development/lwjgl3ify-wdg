package me.eigenraven.lwjgl3ify.relauncherstub;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NativeExitDiagnosticsTest {

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
}
