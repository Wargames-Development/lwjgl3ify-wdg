package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Coordinates discovery, host selection, Change 003 installation, and launch selection. */
public final class AutomaticRuntimeCoordinator {

    public static final String DISABLE_PROPERTY = "lwjgl3ify.relauncher.disableBundledJava";
    public static final String FORCE_SETTINGS_PROPERTY = "lwjgl3ify.relauncher.forceSettings";
    public static final String MANAGED_RUNTIME_PROPERTY = "lwjgl3ify.relauncher.managedRuntime";
    public static final String MANAGED_PLATFORM_PROPERTY = "lwjgl3ify.relauncher.managedPlatform";
    public static final String MANAGED_RUNTIME_VERSION_PROPERTY = "lwjgl3ify.relauncher.managedRuntimeVersion";

    public interface Installer {

        RuntimeInstallResult install(Path bundle, String platformId, Path cacheRoot)
            throws IOException, RuntimeInstallationException;
    }

    private static final class ProductionInstaller implements Installer {

        @Override
        public RuntimeInstallResult install(Path bundle, String platformId, Path cacheRoot)
            throws IOException, RuntimeInstallationException {
            return new RuntimeInstaller().install(bundle, platformId, cacheRoot);
        }
    }

    private final RuntimeBundleLocator bundleLocator;
    private final RuntimeHostDetector hostDetector;
    private final Installer installer;

    public AutomaticRuntimeCoordinator() {
        this(new RuntimeBundleLocator(), new RuntimeHostDetector(), new ProductionInstaller());
    }

    public AutomaticRuntimeCoordinator(RuntimeBundleLocator bundleLocator, RuntimeHostDetector hostDetector,
        Installer installer) {
        if (bundleLocator == null || hostDetector == null || installer == null) {
            throw new NullPointerException("bundleLocator, hostDetector, and installer are required");
        }
        this.bundleLocator = bundleLocator;
        this.hostDetector = hostDetector;
        this.installer = installer;
    }

    public AutomaticRuntimeResult prepare(Path gameDirectory, Path cacheRoot, boolean useBundledJava) {
        Map<String, String> properties = new HashMap<String, String>();
        copyProperty(properties, RuntimeBundleLocator.PROPERTY_NAME);
        copyProperty(properties, DISABLE_PROPERTY);
        copyProperty(properties, FORCE_SETTINGS_PROPERTY);
        return prepare(gameDirectory, cacheRoot, useBundledJava, properties, System.getenv());
    }

    public AutomaticRuntimeResult prepare(Path gameDirectory, Path cacheRoot, boolean useBundledJava,
        Map<String, String> properties, Map<String, String> environment) {
        boolean forceSettings = booleanValue(properties, FORCE_SETTINGS_PROPERTY);
        if (booleanValue(properties, DISABLE_PROPERTY)) {
            return AutomaticRuntimeResult
                .disabled("Automatic packaged Java disabled by -D" + DISABLE_PROPERTY + "=true", forceSettings);
        }
        if (!useBundledJava) {
            return AutomaticRuntimeResult
                .disabled("Automatic packaged Java disabled in relauncher settings", forceSettings);
        }

        RuntimeBundleLocator.Result bundle;
        try {
            bundle = bundleLocator.locate(gameDirectory, properties, environment);
        } catch (RuntimeException exception) {
            return AutomaticRuntimeResult.failed(
                "Packaged Java bundle path is invalid: " + exception.getMessage(),
                exception,
                null,
                null,
                forceSettings);
        }
        if (!bundle.isAvailable()) {
            if (bundle.isFatal()) {
                return AutomaticRuntimeResult.failed(bundle.getMessage(), null, bundle, null, forceSettings);
            }
            return AutomaticRuntimeResult.unavailable(bundle.getMessage(), bundle, null, forceSettings);
        }

        RuntimeHost host;
        try {
            host = hostDetector.detect(
                value(properties, "os.name", System.getProperty("os.name")),
                value(properties, "os.arch", System.getProperty("os.arch")),
                environment);
        } catch (RuntimeHostDetectionException exception) {
            return AutomaticRuntimeResult.unavailable(exception.getMessage(), bundle, null, forceSettings);
        } catch (InterruptedException exception) {
            Thread.currentThread()
                .interrupt();
            return AutomaticRuntimeResult.failed(
                "Interrupted while detecting the host for packaged Java",
                exception,
                bundle,
                null,
                forceSettings);
        }

        try {
            RuntimeManifest manifest = RuntimeManifest.loadCanonical();
            RuntimePlatform platform = manifest.selectPlatform(host);
            RuntimeInstallResult installation = installer.install(bundle.getPath(), platform.getId(), cacheRoot);
            JavaLaunchSelection selection = JavaLaunchSelection.bundled(installation);
            return AutomaticRuntimeResult.ready(bundle, host, installation, selection, forceSettings);
        } catch (RuntimeInstallationException exception) {
            return AutomaticRuntimeResult.failed(
                "Packaged Java validation or installation failed: " + exception.getMessage(),
                exception,
                bundle,
                host,
                forceSettings);
        } catch (IOException exception) {
            return AutomaticRuntimeResult.failed(
                "Packaged Java preparation failed: " + exception.getMessage(),
                exception,
                bundle,
                host,
                forceSettings);
        } catch (RuntimeException exception) {
            return AutomaticRuntimeResult.failed(
                "Packaged Java preparation failed unexpectedly: " + exception.getMessage(),
                exception,
                bundle,
                host,
                forceSettings);
        }
    }

    private static void copyProperty(Map<String, String> properties, String name) {
        String value = System.getProperty(name);
        if (value != null) properties.put(name, value);
    }

    private static boolean booleanValue(Map<String, String> properties, String name) {
        return Boolean.parseBoolean(value(properties, name, "false"));
    }

    private static String value(Map<String, String> properties, String name, String fallback) {
        if (properties == null) return fallback;
        String value = properties.get(name);
        return value == null ? fallback : value;
    }
}
