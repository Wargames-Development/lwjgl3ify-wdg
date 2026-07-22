package me.eigenraven.lwjgl3ify.relauncher;

/** Small state holder that keeps dialog cancellation separate from an intentional Run action. */
final class SettingsLaunchController {

    private LaunchDecision decision = LaunchDecision.CANCEL;

    LaunchDecision getDecision() {
        return decision;
    }

    void proceed() {
        decision = LaunchDecision.PROCEED;
    }
}
