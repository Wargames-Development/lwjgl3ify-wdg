package me.eigenraven.lwjgl3ify.relauncher.runtime;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import me.eigenraven.lwjgl3ify.relauncher.LaunchDecision;
import me.eigenraven.lwjgl3ify.relauncher.LaunchDecisionPolicy;

public class LaunchDecisionPolicyTest {

    @Test
    public void automaticSuccessSkipsSettingsUnlessForced() {
        // A ready fixture exercises the automatic-proceed decision without Swing.
        assertEquals(
            LaunchDecision.PROCEED,
            LaunchDecisionPolicy.decide(status(AutomaticRuntimeResult.Status.READY, false), false, false));
        assertEquals(
            LaunchDecision.SHOW_SETTINGS,
            LaunchDecisionPolicy.decide(status(AutomaticRuntimeResult.Status.READY, true), false, false));
    }

    @Test
    public void hiddenManualModeProceedsOnlyWithValidJava() {
        AutomaticRuntimeResult unavailable = status(AutomaticRuntimeResult.Status.UNAVAILABLE, false);
        assertEquals(LaunchDecision.PROCEED, LaunchDecisionPolicy.decide(unavailable, true, true));
        assertEquals(LaunchDecision.SHOW_SETTINGS, LaunchDecisionPolicy.decide(unavailable, true, false));
        assertEquals(LaunchDecision.SHOW_SETTINGS, LaunchDecisionPolicy.decide(unavailable, false, true));
    }

    @Test
    public void fatalAutomaticFailureCancels() {
        assertEquals(
            LaunchDecision.CANCEL,
            LaunchDecisionPolicy.decide(status(AutomaticRuntimeResult.Status.FAILED, false), true, true));
    }

    private static AutomaticRuntimeResult status(AutomaticRuntimeResult.Status value, boolean forceSettings) {
        switch (value) {
            case READY:
                return new AutomaticRuntimeResultTestFactory().ready(forceSettings);
            case DISABLED:
                return AutomaticRuntimeResult.disabled("disabled", forceSettings);
            case UNAVAILABLE:
                return AutomaticRuntimeResult.unavailable("unavailable", null, null, forceSettings);
            case FAILED:
                return AutomaticRuntimeResult.failed("failed", new Exception("failure"), null, null, forceSettings);
            default:
                throw new AssertionError(value);
        }
    }

    private static final class AutomaticRuntimeResultTestFactory {

        AutomaticRuntimeResult ready(boolean forceSettings) {
            try {
                java.nio.file.Path root = java.nio.file.Files.createTempDirectory("runtime-ready");
                java.nio.file.Path executable = java.nio.file.Files.write(root.resolve("java"), new byte[] { 1 });
                executable.toFile()
                    .setExecutable(true, false);
                RuntimeInstallResult install = new RuntimeInstallResult(
                    false,
                    root,
                    root,
                    root,
                    executable,
                    null,
                    "macos-aarch64",
                    "21.0.11+10-LTS",
                    repeat('a', 64));
                return AutomaticRuntimeResult.ready(
                    null,
                    new RuntimeHost("macos", "aarch64", null, "test"),
                    install,
                    JavaLaunchSelection.bundled(install),
                    forceSettings);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
