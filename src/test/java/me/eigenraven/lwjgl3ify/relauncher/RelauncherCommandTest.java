package me.eigenraven.lwjgl3ify.relauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import me.eigenraven.lwjgl3ify.relauncher.runtime.JavaLaunchSelection;
import me.eigenraven.lwjgl3ify.relauncher.runtime.RuntimeInstallResult;

public class RelauncherCommandTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void configuredArgumentsPreserveMemoryGcCustomAndMacFirstThread() {
        RelauncherConfig.ConfigObject config = new RelauncherConfig.ConfigObject();
        config.minMemoryMB = 768;
        config.maxMemoryMB = 6144;
        config.garbageCollector = RelauncherConfig.GCOption.G1GC;
        config.customOptions = new String[] { "-Dcustom.path=/path with spaces", "-XX:+AlwaysPreTouch" };
        List<String> command = new ArrayList<String>();
        Relauncher.appendConfiguredJvmArguments(command, true, config);
        for (String recommended : Relauncher.RECOMMENDED_JAVA_ARGS) {
            assertTrue(command.contains(recommended));
        }
        assertTrue(command.contains("-XstartOnFirstThread"));
        assertTrue(command.contains("-Xms768M"));
        assertTrue(command.contains("-Xmx6144M"));
        assertTrue(command.contains("-XX:+UseG1GC"));
        assertTrue(command.contains("-Dcustom.path=/path with spaces"));
        assertTrue(command.contains("-XX:+AlwaysPreTouch"));
    }

    @Test
    public void developmentClasspathInheritanceIsGenericOrderedAndDeduplicated() throws Exception {
        Path first = temporary.newFile("first optional mod.jar")
            .toPath();
        Path second = temporary.newFolder("second optional mod classes")
            .toPath();
        Path missing = temporary.getRoot()
            .toPath()
            .resolve("missing.jar");
        Set<String> destination = new LinkedHashSet<String>();
        destination.add(
            first.toAbsolutePath()
                .normalize()
                .toString());

        String parentClasspath = first + File.pathSeparator + second + File.pathSeparator + missing;
        int added = Relauncher.appendExistingClasspathEntries(destination, parentClasspath);

        assertEquals(1, added);
        assertEquals(2, destination.size());
        assertEquals(
            first.toAbsolutePath()
                .normalize()
                .toString(),
            new ArrayList<String>(destination).get(0));
        assertEquals(
            second.toAbsolutePath()
                .normalize()
                .toString(),
            new ArrayList<String>(destination).get(1));
    }

    @Test
    public void configuredDevelopmentTweakersAreOrderedAndDeduplicated() {
        List<String> command = new ArrayList<String>();
        command.add("--tweakClass");
        command.add("cpw.mods.fml.common.launcher.FMLTweaker");

        int added = Relauncher.appendConfiguredTweakerArguments(
            command,
            "org.spongepowered.asm.launch.MixinTweaker, example.OptionalTweaker, "
                + "org.spongepowered.asm.launch.MixinTweaker");

        assertEquals(2, added);
        assertEquals(
            java.util.Arrays.asList(
                "--tweakClass",
                "cpw.mods.fml.common.launcher.FMLTweaker",
                "--tweakClass",
                "org.spongepowered.asm.launch.MixinTweaker",
                "--tweakClass",
                "example.OptionalTweaker"),
            command);
    }

    @Test
    public void supervisedProductionChildDropsDevelopmentMixinRemapProperties() {
        assertTrue(!Relauncher.shouldForwardSystemProperty(Relauncher.MIXIN_REMAP_REFMAP_PROPERTY, true));
        assertTrue(!Relauncher.shouldForwardSystemProperty(Relauncher.MIXIN_REFMAP_REMAP_FILE_PROPERTY, true));
        assertTrue(!Relauncher.shouldForwardSystemProperty(Relauncher.MIXIN_REFMAP_REMAP_ENV_PROPERTY, true));
        assertTrue(Relauncher.shouldForwardSystemProperty("mixin.debug.verbose", true));
    }

    @Test
    public void normalLauncherChildPreservesUserMixinRemapProperties() {
        assertTrue(Relauncher.shouldForwardSystemProperty(Relauncher.MIXIN_REMAP_REFMAP_PROPERTY, false));
        assertTrue(Relauncher.shouldForwardSystemProperty(Relauncher.MIXIN_REFMAP_REMAP_FILE_PROPERTY, false));
        assertTrue(Relauncher.shouldForwardSystemProperty(Relauncher.MIXIN_REFMAP_REMAP_ENV_PROPERTY, false));
    }

    @Test
    public void bootstrapUsesConsoleJavaForForwardedLogsAndGuiJavaForStub() throws Exception {
        Path bin = temporary.newFolder("windows")
            .toPath();
        Path java = Files.write(bin.resolve("java.exe"), new byte[] { 1 });
        Path javaw = Files.write(bin.resolve("javaw.exe"), new byte[] { 1 });
        JavaLaunchSelection selection = JavaLaunchSelection.manual(java, true);
        Path args = temporary.newFile("arguments with spaces.arg")
            .toPath();
        Path mod = temporary.newFile("mod.jar")
            .toPath();
        Path patches = temporary.newFile("patches.jar")
            .toPath();

        List<String> direct = Relauncher.createBootstrapCommand(
            selection,
            true,
            args,
            mod,
            patches,
            42L,
            false,
            temporary.getRoot()
                .toPath()
                .resolve("logs/child.log"));
        assertEquals(
            java.toRealPath()
                .toString(),
            direct.get(0));
        assertEquals("@" + args, direct.get(1));

        List<String> stub = Relauncher.createBootstrapCommand(
            selection,
            false,
            args,
            mod,
            patches,
            42L,
            false,
            temporary.getRoot()
                .toPath()
                .resolve("logs/child.log"));
        assertEquals(
            javaw.toRealPath()
                .toString(),
            stub.get(0));
        assertTrue(stub.contains(args.toString()));
        assertTrue(stub.contains("me.eigenraven.lwjgl3ify.relauncherstub.RelauncherStubMain"));
        assertTrue(stub.contains("false"));
        assertTrue(
            stub.contains(
                temporary.getRoot()
                    .toPath()
                    .resolve("logs/child.log")
                    .toAbsolutePath()
                    .normalize()
                    .toString()));

        List<String> diagnosticStub = Relauncher.createBootstrapCommand(
            selection,
            false,
            args,
            mod,
            patches,
            42L,
            true,
            temporary.getRoot()
                .toPath()
                .resolve("logs/diagnostic-child.log"));
        assertEquals(
            "true",
            diagnosticStub
                .get(diagnosticStub.indexOf("me.eigenraven.lwjgl3ify.relauncherstub.RelauncherStubMain") + 2));
    }

    @Test
    public void bundledUnixSelectionIsUsedAndLaunchSummaryCannotIncludeMinecraftSecrets() throws Exception {
        Path root = temporary.newFolder("managed unix")
            .toPath();
        Path java = Files.write(root.resolve("java"), new byte[] { 1 });
        java.toFile()
            .setExecutable(true, false);
        RuntimeInstallResult install = runtimeInstallResult(root, java);
        JavaLaunchSelection selection = JavaLaunchSelection.bundled(install);
        Path arguments = temporary.newFile("arguments with spaces and accessToken.arg")
            .toPath();
        List<String> bootstrap = Relauncher.createBootstrapCommand(
            selection,
            true,
            arguments,
            temporary.newFile("mod-unix.jar")
                .toPath(),
            temporary.newFile("patches-unix.jar")
                .toPath(),
            11L,
            false,
            temporary.getRoot()
                .toPath()
                .resolve("logs/child.log"));
        assertEquals(
            java.toAbsolutePath()
                .normalize()
                .toString(),
            bootstrap.get(0));

        String summary = Relauncher.describeLaunch(selection, true, arguments);
        assertTrue(summary.contains("source=BUNDLED"));
        assertTrue(!summary.contains("--accessToken"));
        assertTrue(!summary.contains("--uuid"));
    }

    @Test
    public void verifiedProductionArtifactMustBeTheOnlyLwjgl3ifyJarOnChildClasspath() throws Exception {
        Path artifact = temporary.newFile("lwjgl3ify-production.jar")
            .toPath()
            .toAbsolutePath()
            .normalize();
        Files.write(artifact, new byte[] { 1, 2, 3, 4 });
        System.setProperty(Relauncher.EXPECTED_PRODUCTION_ARTIFACT_PROPERTY, artifact.toString());
        System.setProperty(Relauncher.EXPECTED_PRODUCTION_SHA256_PROPERTY, sha256(Files.readAllBytes(artifact)));
        try {
            Relauncher.verifyProductionArtifact(artifact, java.util.Collections.singletonList(artifact.toString()));

            Path duplicate = temporary.newFile("lwjgl3ify-unexpected.jar")
                .toPath();
            try {
                Relauncher.verifyProductionArtifact(
                    artifact,
                    java.util.Arrays.asList(artifact.toString(), duplicate.toString()));
                throw new AssertionError("Unexpected duplicate lwjgl3ify artifact was accepted");
            } catch (java.io.IOException expected) {
                assertTrue(
                    expected.getMessage()
                        .contains("unexpected lwjgl3ify artifact"));
            }
        } finally {
            System.clearProperty(Relauncher.EXPECTED_PRODUCTION_ARTIFACT_PROPERTY);
            System.clearProperty(Relauncher.EXPECTED_PRODUCTION_SHA256_PROPERTY);
        }
    }

    private static RuntimeInstallResult runtimeInstallResult(Path root, Path java) throws Exception {
        Constructor<RuntimeInstallResult> constructor = RuntimeInstallResult.class.getDeclaredConstructor(
            boolean.class,
            Path.class,
            Path.class,
            Path.class,
            Path.class,
            Path.class,
            String.class,
            String.class,
            String.class);
        constructor.setAccessible(true);
        return constructor
            .newInstance(false, root, root, root, java, null, "linux-x86_64", "21.0.11+10-LTS", repeat('a', 64));
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes);
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte value : digest) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
