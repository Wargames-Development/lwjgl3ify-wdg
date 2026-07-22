package me.eigenraven.lwjgl3ify.relauncher.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RuntimeBundleLocatorTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void usesPropertyEnvironmentThenCanonicalPathPrecedence() throws Exception {
        Path game = temporary.newFolder("game with spaces")
            .toPath();
        Path property = Files.write(
            temporary.newFile("property bundle.zip")
                .toPath(),
            new byte[] { 1 });
        Path environment = Files.write(
            temporary.newFile("environment.zip")
                .toPath(),
            new byte[] { 2 });
        Path canonical = game.resolve(RuntimeBundleLocator.CANONICAL_RELATIVE_PATH);
        Files.createDirectories(canonical.getParent());
        Files.write(canonical, new byte[] { 3 });
        Map<String, String> properties = new HashMap<String, String>();
        properties.put(RuntimeBundleLocator.PROPERTY_NAME, property.toString());
        Map<String, String> env = new HashMap<String, String>();
        env.put(RuntimeBundleLocator.ENVIRONMENT_NAME, environment.toString());

        RuntimeBundleLocator.Result result = new RuntimeBundleLocator().locate(game, properties, env);
        assertEquals(RuntimeBundleLocator.Source.SYSTEM_PROPERTY, result.getSource());
        assertEquals(
            property.toAbsolutePath()
                .normalize(),
            result.getPath());

        properties.clear();
        result = new RuntimeBundleLocator().locate(game, properties, env);
        assertEquals(RuntimeBundleLocator.Source.ENVIRONMENT, result.getSource());
        assertEquals(
            environment.toAbsolutePath()
                .normalize(),
            result.getPath());

        env.clear();
        result = new RuntimeBundleLocator().locate(game, properties, env);
        assertEquals(RuntimeBundleLocator.Source.GAME_DIRECTORY, result.getSource());
        assertEquals(
            canonical.toAbsolutePath()
                .normalize(),
            result.getPath());
    }

    @Test
    public void resolvesRelativeOverridesAgainstGameDirectory() throws Exception {
        Path game = temporary.newFolder("relative game")
            .toPath();
        Path bundle = game.resolve("payload/runtime.zip");
        Files.createDirectories(bundle.getParent());
        Files.write(bundle, new byte[] { 1 });
        Map<String, String> properties = Collections
            .singletonMap(RuntimeBundleLocator.PROPERTY_NAME, "payload/../payload/runtime.zip");
        RuntimeBundleLocator.Result result = new RuntimeBundleLocator()
            .locate(game, properties, Collections.<String, String>emptyMap());
        assertTrue(result.isAvailable());
        assertEquals(
            bundle.toAbsolutePath()
                .normalize(),
            result.getPath());
    }

    @Test
    public void distinguishesMissingDefaultFromInvalidExplicitPath() throws Exception {
        Path game = temporary.newFolder("empty")
            .toPath();
        RuntimeBundleLocator.Result absent = new RuntimeBundleLocator()
            .locate(game, Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap());
        assertEquals(RuntimeBundleLocator.Status.DEFAULT_ABSENT, absent.getStatus());
        assertFalse(absent.isFatal());

        RuntimeBundleLocator.Result missingOverride = new RuntimeBundleLocator().locate(
            game,
            Collections.singletonMap(RuntimeBundleLocator.PROPERTY_NAME, "missing.zip"),
            Collections.<String, String>emptyMap());
        assertEquals(RuntimeBundleLocator.Status.INVALID_OVERRIDE, missingOverride.getStatus());
        assertTrue(missingOverride.isFatal());
    }

    @Test
    public void rejectsDirectoriesAndDoesNotSearchOtherZips() throws Exception {
        Path game = temporary.newFolder("no scan")
            .toPath();
        Files.write(game.resolve("some-other.zip"), new byte[] { 1 });
        RuntimeBundleLocator.Result result = new RuntimeBundleLocator()
            .locate(game, Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap());
        assertEquals(RuntimeBundleLocator.Status.DEFAULT_ABSENT, result.getStatus());

        Path directory = temporary.newFolder("bundle.zip")
            .toPath();
        result = new RuntimeBundleLocator().locate(
            game,
            Collections.singletonMap(RuntimeBundleLocator.PROPERTY_NAME, directory.toString()),
            Collections.<String, String>emptyMap());
        assertEquals(RuntimeBundleLocator.Status.INVALID_OVERRIDE, result.getStatus());
        assertTrue(
            result.getMessage()
                .contains("directory"));
    }
}
