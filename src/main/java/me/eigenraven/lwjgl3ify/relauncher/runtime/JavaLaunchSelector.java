package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.nio.file.InvalidPathException;
import java.nio.file.Paths;

import me.eigenraven.lwjgl3ify.relauncher.RelauncherConfig;

/** Resolves the existing manual Java list without mutating it. */
public final class JavaLaunchSelector {

    private JavaLaunchSelector() {}

    public static JavaLaunchSelection selectManual(RelauncherConfig.ConfigObject config, boolean windows,
        boolean allowSystemFallback) throws RuntimeInstallationException {
        if (config == null) throw new NullPointerException("config");
        String[] installations = config.javaInstallationsCache == null ? new String[0] : config.javaInstallationsCache;
        int index = config.javaInstallation;
        if (index >= 0 && index < installations.length
            && installations[index] != null
            && !installations[index].trim()
                .isEmpty()) {
            try {
                return JavaLaunchSelection.manual(Paths.get(installations[index]), windows);
            } catch (InvalidPathException exception) {
                throw new RuntimeInstallationException("Configured Java path is invalid", exception);
            }
        }
        if (allowSystemFallback) return JavaLaunchSelection.systemFallback(windows);
        throw new RuntimeInstallationException("No valid manual Java executable is selected");
    }

    public static boolean hasValidManualSelection(RelauncherConfig.ConfigObject config, boolean windows) {
        try {
            selectManual(config, windows, false);
            return true;
        } catch (RuntimeInstallationException exception) {
            return false;
        }
    }
}
