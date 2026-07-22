package me.eigenraven.lwjgl3ify.relauncher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SettingsLaunchControllerTest {

    @Test
    public void closingWithoutRunCancels() {
        assertEquals(LaunchDecision.CANCEL, new SettingsLaunchController().getDecision());
    }

    @Test
    public void runContinues() {
        SettingsLaunchController controller = new SettingsLaunchController();
        controller.proceed();
        assertEquals(LaunchDecision.PROCEED, controller.getDecision());
    }
}
