package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Coordinates source selection, host selection, secure installation, and launch selection. */
public final class AutomaticRuntimeCoordinator {

    public static final String DISABLE_PROPERTY = "lwjgl3ify.relauncher.disableBundledJava";
    public static final String FORCE_SETTINGS_PROPERTY = "lwjgl3ify.relauncher.forceSettings";
    public static final String MANAGED_RUNTIME_PROPERTY = "lwjgl3ify.relauncher.managedRuntime";
    public static final String MANAGED_PLATFORM_PROPERTY = "lwjgl3ify.relauncher.managedPlatform";
    public static final String MANAGED_RUNTIME_VERSION_PROPERTY = "lwjgl3ify.relauncher.managedRuntimeVersion";

    public interface Installer {

        RuntimeInstallResult install(RuntimeBundleLocator.Result source, String platformId, Path cacheRoot)
            throws IOException, RuntimeInstallationException;
    }

    private static final class ProductionInstaller implements Installer {

        @Override
        public RuntimeInstallResult install(RuntimeBundleLocator.Result source, String platformId, Path cacheRoot)
            throws IOException, RuntimeInstallationException {
            RuntimeInstaller installer = new RuntimeInstaller();
            if (source.getKind() == RuntimeBundleLocator.Kind.DIRECT_ARCHIVE) {
                return installer.installArchive(source.getPath(), platformId, cacheRoot);
            }
            return installer.install(source.getPath(), platformId, cacheRoot);
        }
    }

    private final RuntimeBundleLocator bundleLocator;
    private final RuntimeHostDetector hostDetector;
    private final Installer installer;
    private final EmbeddedRuntimeArchiveProvider embeddedProvider;
    private final RuntimeExtensionLocator extensionLocator;

    public AutomaticRuntimeCoordinator() {
        this(
            new RuntimeBundleLocator(),
            new RuntimeHostDetector(),
            new ProductionInstaller(),
            new EmbeddedRuntimeArchiveProvider(),
            new RuntimeExtensionLocator());
    }

    public AutomaticRuntimeCoordinator(RuntimeBundleLocator bundleLocator, RuntimeHostDetector hostDetector,
        Installer installer) {
        this(
            bundleLocator,
            hostDetector,
            installer,
            new EmbeddedRuntimeArchiveProvider(),
            new RuntimeExtensionLocator());
    }

    AutomaticRuntimeCoordinator(RuntimeBundleLocator bundleLocator, RuntimeHostDetector hostDetector,
        Installer installer, EmbeddedRuntimeArchiveProvider embeddedProvider,
        RuntimeExtensionLocator extensionLocator) {
        if (bundleLocator == null || hostDetector == null
            || installer == null
            || embeddedProvider == null
            || extensionLocator == null) {
            throw new NullPointerException("Runtime coordinator collaborators are required");
        }
        this.bundleLocator = bundleLocator;
        this.hostDetector = hostDetector;
        this.installer = installer;
        this.embeddedProvider = embeddedProvider;
        this.extensionLocator = extensionLocator;
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

        RuntimeHost host;
        try {
            host = hostDetector.detect(
                value(properties, "os.name", System.getProperty("os.name")),
                value(properties, "os.arch", System.getProperty("os.arch")),
                environment);
        } catch (RuntimeHostDetectionException exception) {
            return AutomaticRuntimeResult.unavailable(exception.getMessage(), null, null, forceSettings);
        } catch (InterruptedException exception) {
            Thread.currentThread()
                .interrupt();
            return AutomaticRuntimeResult
                .failed("Interrupted while detecting the host for packaged Java", exception, null, null, forceSettings);
        }

        RuntimeManifest manifest;
        RuntimePlatform platform;
        try {
            manifest = RuntimeManifest.loadCanonical();
            platform = manifest.selectPlatform(host);
        } catch (RuntimeInstallationException exception) {
            return AutomaticRuntimeResult.unavailable(exception.getMessage(), null, host, forceSettings);
        }

        RuntimeBundleLocator.Result source;
        try {
            source = bundleLocator.locateExplicit(gameDirectory, properties, environment);
            if (source != null && !source.isAvailable()) {
                return AutomaticRuntimeResult.failed(source.getMessage(), null, source, host, forceSettings);
            }
            if (source == null && embeddedProvider.isAvailable(platform)) {
                source = embeddedProvider.materialize(manifest, platform, cacheRoot);
            }
            if (source == null) {
                source = extensionLocator.locate(gameDirectory, platform);
                if (source != null && !source.isAvailable()) {
                    return AutomaticRuntimeResult.failed(source.getMessage(), null, source, host, forceSettings);
                }
            }
            if (source == null) {
                RuntimeBundleLocator.Result legacy = bundleLocator.locateDefault(gameDirectory);
                if (legacy.isAvailable()) {
                    source = legacy;
                } else if (legacy.isFatal()) {
                    return AutomaticRuntimeResult.failed(legacy.getMessage(), null, legacy, host, forceSettings);
                }
            }
        } catch (RuntimeException exception) {
            return AutomaticRuntimeResult.failed(
                "Packaged Java source path is invalid: " + exception.getMessage(),
                exception,
                null,
                host,
                forceSettings);
        } catch (RuntimeInstallationException exception) {
            return AutomaticRuntimeResult.failed(
                "Embedded Java runtime validation failed: " + exception.getMessage(),
                exception,
                null,
                host,
                forceSettings);
        } catch (IOException exception) {
            return AutomaticRuntimeResult.failed(
                "Embedded Java runtime preparation failed: " + exception.getMessage(),
                exception,
                null,
                host,
                forceSettings);
        }

        if (source == null) {
            return AutomaticRuntimeResult.unavailable(
                "No embedded, extension, or legacy Java runtime source is available for " + platform.getId()
                    + ". Optional extensions belong in "
                    + RuntimeExtensionLocator.CANONICAL_DIRECTORY
                    + "/"
                    + RuntimeExtensionLocator.canonicalFileName(platform),
                null,
                host,
                forceSettings);
        }

        try {
            RuntimeInstallResult installation = installer.install(source, platform.getId(), cacheRoot);
            JavaLaunchSelection selection = JavaLaunchSelection.bundled(installation);
            return AutomaticRuntimeResult.ready(source, host, installation, selection, forceSettings);
        } catch (RuntimeInstallationException exception) {
            return AutomaticRuntimeResult.failed(
                "Packaged Java validation or installation failed: " + exception.getMessage(),
                exception,
                source,
                host,
                forceSettings);
        } catch (IOException exception) {
            return AutomaticRuntimeResult.failed(
                "Packaged Java preparation failed: " + exception.getMessage(),
                exception,
                source,
                host,
                forceSettings);
        } catch (RuntimeException exception) {
            return AutomaticRuntimeResult.failed(
                "Packaged Java preparation failed unexpectedly: " + exception.getMessage(),
                exception,
                source,
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
