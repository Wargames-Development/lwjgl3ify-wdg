package me.eigenraven.lwjgl3ify.relauncher.runtime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import me.eigenraven.lwjgl3ify.relauncher.RelauncherConfig;

public class AutomaticRuntimeCoordinatorTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void successfulPreparationPassesExplicitInputsAndPreservesManualList() throws Exception {
        Path game = temporary.newFolder("game")
            .toPath();
        Path cache = temporary.newFolder("cache")
            .toPath();
        Path bundle = game.resolve(RuntimeBundleLocator.CANONICAL_RELATIVE_PATH);
        Files.createDirectories(bundle.getParent());
        Files.write(bundle, new byte[] { 1 });
        Path root = temporary.newFolder("installed")
            .toPath();
        Path home = root.resolve("runtime/Contents/Home");
        Path java = home.resolve("bin/java");
        Files.createDirectories(java.getParent());
        Files.write(java, new byte[] { 1 });
        java.toFile()
            .setExecutable(true, false);

        AtomicReference<Path> passedBundle = new AtomicReference<Path>();
        AtomicReference<Path> passedCache = new AtomicReference<Path>();
        AtomicReference<String> passedPlatform = new AtomicReference<String>();
        java.util.concurrent.atomic.AtomicInteger installCalls = new java.util.concurrent.atomic.AtomicInteger();
        AutomaticRuntimeCoordinator.Installer installer = (selectedSource, platform, cacheRoot) -> {
            passedBundle.set(selectedSource.getPath());
            passedPlatform.set(platform);
            passedCache.set(cacheRoot);
            return new RuntimeInstallResult(
                installCalls.getAndIncrement() == 0,
                root,
                root.resolve("runtime"),
                home,
                java,
                null,
                platform,
                "21.0.11+10-LTS",
                repeat('a', 64));
        };
        AutomaticRuntimeCoordinator coordinator = new AutomaticRuntimeCoordinator(
            new RuntimeBundleLocator(),
            new RuntimeHostDetector(command -> RuntimeHostDetector.ProbeResult.unavailable()),
            installer);
        Map<String, String> properties = new HashMap<String, String>();
        properties.put("os.name", "Mac OS X");
        properties.put("os.arch", "aarch64");

        RelauncherConfig.ConfigObject config = new RelauncherConfig.ConfigObject();
        config.javaInstallationsCache = new String[] { "/manual/java", "/another/java" };
        String[] before = config.javaInstallationsCache.clone();
        AutomaticRuntimeResult result = coordinator
            .prepare(game, cache, config.useBundledJava, properties, Collections.<String, String>emptyMap());

        assertTrue(result.isReady());
        assertEquals(
            "macos-aarch64",
            result.getSelection()
                .getPlatformId());
        assertEquals(
            JavaLaunchSelection.Source.BUNDLED,
            result.getSelection()
                .getSource());
        assertEquals(
            bundle.toAbsolutePath()
                .normalize(),
            passedBundle.get());
        assertEquals(
            cache.toAbsolutePath()
                .normalize(),
            passedCache.get()
                .toAbsolutePath()
                .normalize());
        assertEquals("macos-aarch64", passedPlatform.get());
        assertArrayEquals(before, config.javaInstallationsCache);

        AutomaticRuntimeResult reused = coordinator
            .prepare(game, cache, config.useBundledJava, properties, Collections.<String, String>emptyMap());
        assertTrue(reused.isReady());
        assertTrue(
            reused.getInstallation()
                .wasReused());
        assertEquals(
            result.getSelection()
                .getConsoleExecutable(),
            reused.getSelection()
                .getConsoleExecutable());
        assertEquals(2, installCalls.get());
        assertArrayEquals(before, config.javaInstallationsCache);
    }

    @Test
    public void missingDefaultAndUnsupportedHostRemainManualFallbackConditions() throws Exception {
        Path game = temporary.newFolder("empty-game")
            .toPath();
        Path cache = temporary.newFolder("empty-cache")
            .toPath();
        AutomaticRuntimeCoordinator coordinator = coordinator(
            (source, platform, root) -> { throw new AssertionError("installer should not run"); });
        AutomaticRuntimeResult missing = coordinator
            .prepare(game, cache, true, properties("Mac OS X", "aarch64"), Collections.<String, String>emptyMap());
        assertEquals(AutomaticRuntimeResult.Status.UNAVAILABLE, missing.getStatus());

        Path bundle = game.resolve(RuntimeBundleLocator.CANONICAL_RELATIVE_PATH);
        Files.createDirectories(bundle.getParent());
        Files.write(bundle, new byte[] { 1 });
        AutomaticRuntimeResult unsupported = coordinator
            .prepare(game, cache, true, properties("FreeBSD", "amd64"), Collections.<String, String>emptyMap());
        assertEquals(AutomaticRuntimeResult.Status.UNAVAILABLE, unsupported.getStatus());
    }

    @Test
    public void configAndRecoveryPropertiesDisableWithoutInstalling() throws Exception {
        Path game = temporary.newFolder("disabled-game")
            .toPath();
        Path cache = temporary.newFolder("disabled-cache")
            .toPath();
        AtomicReference<Boolean> invoked = new AtomicReference<Boolean>(false);
        AutomaticRuntimeCoordinator coordinator = coordinator((source, platform, root) -> {
            invoked.set(true);
            throw new AssertionError();
        });
        AutomaticRuntimeResult configDisabled = coordinator.prepare(
            game,
            cache,
            false,
            Collections.<String, String>emptyMap(),
            Collections.<String, String>emptyMap());
        assertEquals(AutomaticRuntimeResult.Status.DISABLED, configDisabled.getStatus());

        Map<String, String> properties = new HashMap<String, String>();
        properties.put(AutomaticRuntimeCoordinator.DISABLE_PROPERTY, "true");
        properties.put(AutomaticRuntimeCoordinator.FORCE_SETTINGS_PROPERTY, "true");
        AutomaticRuntimeResult overrideDisabled = coordinator
            .prepare(game, cache, true, properties, Collections.<String, String>emptyMap());
        assertEquals(AutomaticRuntimeResult.Status.DISABLED, overrideDisabled.getStatus());
        assertTrue(overrideDisabled.isForceSettings());
        assertFalse(invoked.get());
    }

    @Test
    public void invalidExplicitBundleAndInstallerFailureFailClosed() throws Exception {
        Path game = temporary.newFolder("failure-game")
            .toPath();
        Path cache = temporary.newFolder("failure-cache")
            .toPath();
        Map<String, String> invalidOverride = properties("Mac OS X", "aarch64");
        invalidOverride.put(RuntimeBundleLocator.PROPERTY_NAME, "missing.zip");
        AutomaticRuntimeResult missing = coordinator((source, platform, root) -> { throw new AssertionError(); })
            .prepare(game, cache, true, invalidOverride, Collections.<String, String>emptyMap());
        assertEquals(AutomaticRuntimeResult.Status.FAILED, missing.getStatus());

        Path bundle = game.resolve(RuntimeBundleLocator.CANONICAL_RELATIVE_PATH);
        Files.createDirectories(bundle.getParent());
        Files.write(bundle, new byte[] { 1 });
        RuntimeInstallationException failure = new RuntimeInstallationException("checksum mismatch");
        AutomaticRuntimeResult failed = coordinator((selected, platform, root) -> { throw failure; })
            .prepare(game, cache, true, properties("Mac OS X", "aarch64"), Collections.<String, String>emptyMap());
        assertEquals(AutomaticRuntimeResult.Status.FAILED, failed.getStatus());
        assertSame(failure, failed.getFailure());
        assertTrue(
            failed.getMessage()
                .contains("checksum mismatch"));
    }

    private static AutomaticRuntimeCoordinator coordinator(AutomaticRuntimeCoordinator.Installer installer) {
        return new AutomaticRuntimeCoordinator(
            new RuntimeBundleLocator(),
            new RuntimeHostDetector(command -> RuntimeHostDetector.ProbeResult.unavailable()),
            installer);
    }

    private static Map<String, String> properties(String osName, String osArch) {
        Map<String, String> values = new HashMap<String, String>();
        values.put("os.name", osName);
        values.put("os.arch", osArch);
        return values;
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
