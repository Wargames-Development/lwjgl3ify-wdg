package me.eigenraven.lwjgl3ify.relauncher;

import me.eigenraven.lwjgl3ify.relauncher.runtime.AutomaticRuntimeResult;

/** Testable policy separating launch decisions from Swing presentation. */
public final class LaunchDecisionPolicy {

    private LaunchDecisionPolicy() {}

    public static LaunchDecision decide(AutomaticRuntimeResult automatic, boolean hideSettings, boolean manualValid) {
        if (automatic == null) throw new NullPointerException("automatic");
        if (automatic.isFatalFailure()) return LaunchDecision.CANCEL;
        if (automatic.isForceSettings()) return LaunchDecision.SHOW_SETTINGS;
        if (automatic.isReady()) return LaunchDecision.PROCEED;
        if (hideSettings && manualValid) return LaunchDecision.PROCEED;
        return LaunchDecision.SHOW_SETTINGS;
    }
}
