package me.eigenraven.lwjgl3ify.relauncher;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.launchwrapper.Launch;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.fakelwjgl3ify.SafeRuntimeExit;
import me.eigenraven.lwjgl3ify.Tags;
import me.eigenraven.lwjgl3ify.relauncher.runtime.AutomaticRuntimeCoordinator;
import me.eigenraven.lwjgl3ify.relauncher.runtime.AutomaticRuntimeResult;
import me.eigenraven.lwjgl3ify.relauncher.runtime.JavaLaunchSelection;
import me.eigenraven.lwjgl3ify.relauncher.runtime.JavaLaunchSelector;
import me.eigenraven.lwjgl3ify.relauncher.runtime.RuntimeInstallationException;

public class Relauncher {

    static final Logger logger = LogManager.getLogger("Lwjgl3ify/Relauncher");
    static final String[] SYSTEM_PROPERTY_PREFIXES = new String[] { "java.", "os.", "path.", "file.", "line.", "user.",
        "native.", "com.sun.", "sun.", "awt.", };
    static final String[] RECOMMENDED_JAVA_ARGS = Tags.RECOMMENDED_JAVA_ARGS.split("\t");
    static final String RUNTIME_CACHE_ROOT_PROPERTY = "lwjgl3ify.relauncher.runtimeCacheRoot";
    static final String SHOW_CONSOLE_PROPERTY = "lwjgl3ify.wdg.showConsole";
    static final String CHILD_LOG_RELATIVE_PATH = "logs/lwjgl3ify-java21-child.log";
    static final String ADDITIONAL_CLASSPATH_PROPERTY = "lwjgl3ify.relauncher.additionalClasspath";
    static final String ADDITIONAL_TWEAKERS_PROPERTY = "lwjgl3ify.relauncher.additionalTweakers";
    static final String SUPERVISED_LAUNCH_PROPERTY = "lwjgl3ify.relauncher.supervisedLaunch";
    static final String EXPECTED_PRODUCTION_ARTIFACT_PROPERTY = "lwjgl3ify.relauncher.expectedProductionArtifact";
    static final String EXPECTED_PRODUCTION_SHA256_PROPERTY = "lwjgl3ify.relauncher.expectedProductionSha256";
    static final String MIXIN_REMAP_REFMAP_PROPERTY = "mixin.env.remapRefMap";
    static final String MIXIN_REFMAP_REMAP_FILE_PROPERTY = "mixin.env.refMapRemappingFile";
    static final String MIXIN_REFMAP_REMAP_ENV_PROPERTY = "mixin.env.refMapRemappingEnv";
    static final String SUPERVISED_CLIENT_MAIN = "me.eigenraven.lwjgl3ify.relauncherstub.SupervisedRfbClientMain";
    static final String STANDARD_CLIENT_MAIN = "com.gtnewhorizons.retrofuturabootstrap.MainStartOnFirstThread";
    final Path osCache;
    final Path mavenDownloadPath;
    final String[] args;
    final String gameVersion;
    final Downloader downloader;
    final RelauncherUserInterface gui;
    Path forgePatchesJarPath;

    public void runtimeExit(int exitCode) {
        SafeRuntimeExit.exitRuntime(exitCode);
    }

    public Relauncher(String[] args, String gameVersion) {
        this.args = args;
        this.gameVersion = gameVersion;

        final String userHome = System.getProperty("user.home");
        final String system = System.getProperty("os.name")
            .toLowerCase();
        Path cacheDir;
        try {
            if (system.contains("win")) {
                String localAppData = System.getenv("LOCALAPPDATA");
                if (localAppData != null) {
                    cacheDir = Paths.get(localAppData, "lwjgl3ify");
                } else {
                    cacheDir = Paths.get(userHome, "AppData", "Local", "lwjgl3ify");
                }
            } else if (system.contains("mac")) {
                cacheDir = Paths.get(userHome, "Library", "Caches", "lwjgl3ify");
            } else {
                String xdgCacheHome = System.getenv("XDG_CACHE_HOME");
                if (xdgCacheHome == null) {
                    cacheDir = Paths.get(userHome, ".cache", "lwjgl3ify");
                } else {
                    cacheDir = Paths.get(xdgCacheHome, "lwjgl3ify");
                }
            }
        } catch (InvalidPathException e) {
            if (system.contains("win")) {
                cacheDir = Paths.get(userHome, "AppData", "Local", "lwjgl3ify");
            } else {
                cacheDir = Paths.get(userHome, ".cache", "lwjgl3ify");
            }
            logger.warn(
                "An error occurred while the lwjgl3ify relauncher cache path was created. The environment variable TEMP or LOCALAPPDATA could be set incorrectly. Using the default cache location: {}",
                cacheDir,
                e);
        }
        osCache = cacheDir;

        final String mavenDownloadRoot = System.getProperty("lwjgl3ify.relauncher.mavenDownloadRoot", "");
        final Path mavenDownloadPath;
        if (mavenDownloadRoot.isEmpty()) {
            mavenDownloadPath = osCache.resolve("maven");
        } else {
            mavenDownloadPath = Paths.get(mavenDownloadRoot);
        }
        if (!Files.isDirectory(mavenDownloadPath)) {
            try {
                Files.createDirectories(mavenDownloadPath);
            } catch (IOException e) {
                logger.fatal("Could not create Maven download cache at {}", mavenDownloadPath, e);
                FMLCommonHandler.instance()
                    .exitJava(1, false);
            }
        }
        this.mavenDownloadPath = mavenDownloadPath;
        RelauncherConfig.load();

        this.gui = new RelauncherUserInterface(this);

        downloader = new Downloader(mavenDownloadPath);
        downloader.loadTasks();
        final int dlTasks = downloader.remainingTasks();
        if (dlTasks > 0) {
            logger.info("We need to download {} libraries into the cache at {}", dlTasks, mavenDownloadPath);
            gui.downloadWithGui(downloader);
        } else {
            logger.info("All libraries found in the cache at {}", mavenDownloadPath);
        }
    }

    public List<String> createClasspath() {
        final Set<String> classpath = new LinkedHashSet<>();
        try {
            // Extract RFB&libraries jar
            final byte[] forgePatchesData = IOUtils.toByteArray(
                Objects.requireNonNull(
                    Relauncher.class.getResourceAsStream("forgePatches.zip"),
                    "missing bundled forgePatches jar"));
            final String forgePatchesDigest = DigestUtils.sha256Hex(forgePatchesData);
            final Path jarsDir = mavenDownloadPath.getParent()
                .resolve("jars");
            Files.createDirectories(jarsDir);
            final Path jarFile = jarsDir.resolve("forgePatches-" + forgePatchesDigest + ".jar");
            if (!Files.exists(jarFile)) {
                logger.info("Extracting bundled early classpath libraries to {}", jarFile);
                Files.write(jarFile, forgePatchesData, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } else {
                logger.info("Using previously extracted bundled early classpath libraries from {}", jarFile);
            }
            classpath.add(
                jarFile.toAbsolutePath()
                    .normalize()
                    .toString());
            this.forgePatchesJarPath = jarFile;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        final int additional = appendExistingClasspathEntries(
            classpath,
            System.getProperty(ADDITIONAL_CLASSPATH_PROPERTY, ""));
        if (additional > 0) {
            logger.info("Added {} explicitly configured development support classpath entries", additional);
        }

        for (final Path cpFile : downloader.jarPaths()) {
            if (!Files.exists(cpFile)) {
                continue;
            }
            classpath.add(
                cpFile.toAbsolutePath()
                    .normalize()
                    .toString());
        }
        return new ArrayList<>(classpath);
    }

    static int appendExistingClasspathEntries(Set<String> destination, String sourceClasspath) {
        if (sourceClasspath == null || sourceClasspath.isEmpty()) return 0;
        int added = 0;
        for (final String entry : sourceClasspath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry == null || entry.trim()
                .isEmpty()) {
                continue;
            }
            final Path path;
            try {
                path = Paths.get(entry)
                    .toAbsolutePath()
                    .normalize();
            } catch (InvalidPathException exception) {
                logger.warn("Ignoring invalid additional classpath entry {}", entry, exception);
                continue;
            }
            if (!Files.exists(path)) continue;
            if (destination.add(path.toString())) added++;
        }
        return added;
    }

    static int appendConfiguredTweakerArguments(List<String> destination, String configuredTweakers) {
        if (configuredTweakers == null || configuredTweakers.trim()
            .isEmpty()) {
            return 0;
        }
        final Set<String> alreadyPresent = new LinkedHashSet<>();
        for (int index = 0; index + 1 < destination.size(); index++) {
            if ("--tweakClass".equals(destination.get(index))) {
                alreadyPresent.add(destination.get(index + 1));
                index++;
            }
        }
        int added = 0;
        for (final String configured : configuredTweakers.split(",")) {
            final String tweakClass = configured.trim();
            if (tweakClass.isEmpty() || !alreadyPresent.add(tweakClass)) continue;
            destination.add("--tweakClass");
            destination.add(tweakClass);
            added++;
        }
        return added;
    }

    private static long getCurrentPid() {
        final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        // getName is implemented to return getPid() + "@" + hostname
        final String combinedPidHostname = runtime.getName();
        final String[] parts = combinedPidHostname.split("@", 2);
        return Integer.parseInt(parts[0]);
    }

    @SuppressWarnings("deprecation")
    public void run() throws IOException {
        if (Boolean.parseBoolean(System.getProperty(AutomaticRuntimeCoordinator.MANAGED_RUNTIME_PROPERTY, "false"))) {
            throw new IOException(
                "Managed Java child reached the legacy relauncher again; refusing an accidental relaunch loop");
        }

        final Path gameDirectory = (Launch.minecraftHome == null ? Paths.get(".") : Launch.minecraftHome.toPath())
            .toAbsolutePath()
            .normalize();
        final Path runtimeCacheRoot = resolveRuntimeCacheRoot(gameDirectory);
        final AutomaticRuntimeCoordinator coordinator = new AutomaticRuntimeCoordinator();
        AutomaticRuntimeResult automatic = gui.prepareAutomaticRuntime(
            () -> coordinator.prepare(gameDirectory, runtimeCacheRoot, RelauncherConfig.config.useBundledJava));
        logAutomaticRuntime(automatic);
        if (automatic.isFatalFailure()) {
            logger.fatal(automatic.getMessage(), automatic.getFailure());
            gui.showAutomaticRuntimeFailure(automatic);
            runtimeExit(1);
            return;
        }

        final boolean windows = SystemUtils.IS_OS_WINDOWS;
        boolean manualValid = JavaLaunchSelector.hasValidManualSelection(RelauncherConfig.config, windows);
        LaunchDecision decision = LaunchDecisionPolicy
            .decide(automatic, RelauncherConfig.config.hideSettingsOnLaunch, manualValid);
        boolean settingsShown = false;
        if (decision == LaunchDecision.SHOW_SETTINGS) {
            settingsShown = true;
            decision = gui.showSettings(automatic);
        }
        if (decision == LaunchDecision.CANCEL) {
            runtimeExit(0);
            return;
        }

        if (RelauncherConfig.config.useBundledJava && !automatic.isReady()
            && automatic.getStatus() == AutomaticRuntimeResult.Status.DISABLED) {
            automatic = gui.prepareAutomaticRuntime(() -> coordinator.prepare(gameDirectory, runtimeCacheRoot, true));
            logAutomaticRuntime(automatic);
            if (automatic.isFatalFailure()) {
                logger.fatal(automatic.getMessage(), automatic.getFailure());
                gui.showAutomaticRuntimeFailure(automatic);
                runtimeExit(1);
                return;
            }
        }

        final JavaLaunchSelection launchSelection;
        if (RelauncherConfig.config.useBundledJava && automatic.isReady()) {
            launchSelection = automatic.getSelection();
        } else {
            try {
                launchSelection = JavaLaunchSelector.selectManual(RelauncherConfig.config, windows, settingsShown);
            } catch (RuntimeInstallationException exception) {
                logger.fatal("No valid Java executable is available for the modern relaunch", exception);
                runtimeExit(1);
                return;
            }
        }
        final boolean supervisedLaunch = Boolean.parseBoolean(System.getProperty(SUPERVISED_LAUNCH_PROPERTY, "false"));
        final boolean forwardLogs = RelauncherConfig.config.forwardLogs || supervisedLaunch;
        logger.info(
            "Selected Java source={} executable={} supervised={}",
            launchSelection.getSource(),
            launchSelection.getEffectiveExecutable(forwardLogs),
            supervisedLaunch);
        if (launchSelection.getSource() == JavaLaunchSelection.Source.BUNDLED) {
            logger.info(
                "Managed runtime child diagnostics: platform={} runtimeVersion={} javaHome={}",
                launchSelection.getPlatformId(),
                launchSelection.getRuntimeVersion(),
                launchSelection.getJavaHome());
        }

        URL myJarUrl = Relauncher.class.getProtectionDomain()
            .getCodeSource()
            .getLocation();
        while ("jar".equalsIgnoreCase(myJarUrl.getProtocol())) {
            final String str = myJarUrl.toString();
            myJarUrl = new URL(str.substring(4, str.lastIndexOf('!')));
        }
        final Path myJarPath;
        try {
            myJarPath = Paths.get(myJarUrl.toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        final List<String> childClasspath = createClasspath();
        verifyProductionArtifact(myJarPath, childClasspath);
        final List<String> cmd = createRelaunchArguments(launchSelection, childClasspath, supervisedLaunch);
        cmd.add(supervisedLaunch ? SUPERVISED_CLIENT_MAIN : STANDARD_CLIENT_MAIN);
        cmd.addAll(
            Arrays.asList(
                "--version",
                gameVersion,
                "--gameDir",
                gameDirectory.toString(),
                "--assetsDir",
                Launch.assetsDir.toString(),
                "--tweakClass",
                "cpw.mods.fml.common.launcher.FMLTweaker"));
        final int additionalTweakers = appendConfiguredTweakerArguments(
            cmd,
            System.getProperty(ADDITIONAL_TWEAKERS_PROPERTY, ""));
        if (additionalTweakers > 0) {
            logger.info("Added {} explicitly configured development support tweakers", additionalTweakers);
        }
        cmd.addAll(Arrays.asList(args));

        final Path argFile = Files.createTempFile("lwjgl3ify-relaunch-", ".arg");
        Files.write(
            argFile,
            cmd.stream()
                .map(c -> '"' + StringEscapeUtils.escapeJava(c) + '"')
                .collect(Collectors.toList()),
            StandardCharsets.UTF_8);

        final boolean showConsole = Boolean.parseBoolean(System.getProperty(SHOW_CONSOLE_PROPERTY, "false"));
        final Path childLog = gameDirectory.resolve(CHILD_LOG_RELATIVE_PATH)
            .toAbsolutePath()
            .normalize();
        final List<String> bootstrapCmd = createBootstrapCommand(
            launchSelection,
            forwardLogs,
            argFile,
            myJarPath,
            forgePatchesJarPath,
            getCurrentPid(),
            showConsole,
            childLog);

        final ProcessBuilder pb = new ProcessBuilder(bootstrapCmd);
        pb.inheritIO();
        if (!forwardLogs) {
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        }
        logger.info("Starting relaunched process {}", describeLaunch(launchSelection, forwardLogs, argFile));
        final Process p;
        try {
            p = pb.start();
        } catch (IOException exception) {
            throw new IOException(
                "Could not launch modern Java process using " + launchSelection.getEffectiveExecutable(forwardLogs),
                exception);
        }
        if (forwardLogs) {
            try {
                final int exitCode = ChildProcessSupervisor.waitFor(p);
                logger.info("Relaunched Java child exited with code {}", exitCode);
                runtimeExit(exitCode);
            } catch (InterruptedException exception) {
                throw new IOException("Interrupted while waiting for the relaunched process", exception);
            }
        } else {
            try {
                p.getInputStream()
                    .read(new byte[6]);
            } catch (IOException e) {
                logger.warn("Could not read the relauncher stub readiness signal", e);
            }
            runtimeExit(0);
        }
    }

    List<String> createRelaunchArguments(JavaLaunchSelection launchSelection, List<String> childClasspath,
        boolean supervisedLaunch) {
        final List<String> cmd = new ArrayList<>();
        appendConfiguredJvmArguments(cmd, SystemUtils.IS_OS_MAC, RelauncherConfig.config);
        cmd.add("-cp");
        cmd.add(StringUtils.join(childClasspath, File.pathSeparatorChar));
        for (final Map.Entry<Object, Object> prop : System.getProperties()
            .entrySet()) {
            if (prop.getKey() == null || prop.getValue() == null) continue;
            final String key = prop.getKey()
                .toString();
            if (!shouldForwardSystemProperty(key, supervisedLaunch)) continue;
            final String value = prop.getValue()
                .toString();
            cmd.add("-D" + key + "=" + value);
        }
        if (supervisedLaunch) {
            // GradleStart configures these for a deobfuscated development JVM. The isolated child
            // deliberately runs the verified production artifact against production Minecraft, so
            // inheriting the development refmap remapper makes valid SRG selectors miss their target.
            cmd.add("-D" + MIXIN_REMAP_REFMAP_PROPERTY + "=false");
        }
        appendManagedRuntimeProperties(cmd, launchSelection);
        return cmd;
    }

    static boolean shouldForwardSystemProperty(String key, boolean supervisedLaunch) {
        for (final String systemPrefix : SYSTEM_PROPERTY_PREFIXES) {
            if (key.startsWith(systemPrefix)) return false;
        }
        if (key.equals(ADDITIONAL_CLASSPATH_PROPERTY) || key.equals(ADDITIONAL_TWEAKERS_PROPERTY)
            || key.equals(AutomaticRuntimeCoordinator.MANAGED_RUNTIME_PROPERTY)
            || key.equals(AutomaticRuntimeCoordinator.MANAGED_PLATFORM_PROPERTY)
            || key.equals(AutomaticRuntimeCoordinator.MANAGED_RUNTIME_VERSION_PROPERTY)) {
            return false;
        }
        if (supervisedLaunch && (key.equals(MIXIN_REMAP_REFMAP_PROPERTY) || key.equals(MIXIN_REFMAP_REMAP_FILE_PROPERTY)
            || key.equals(MIXIN_REFMAP_REMAP_ENV_PROPERTY))) {
            return false;
        }
        return true;
    }

    static void verifyProductionArtifact(Path currentArtifact, List<String> childClasspath) throws IOException {
        final String configuredPath = System.getProperty(EXPECTED_PRODUCTION_ARTIFACT_PROPERTY, "")
            .trim();
        final String configuredSha256 = System.getProperty(EXPECTED_PRODUCTION_SHA256_PROPERTY, "")
            .trim();
        if (configuredPath.isEmpty() && configuredSha256.isEmpty()) return;
        if (configuredPath.isEmpty() || configuredSha256.isEmpty()) {
            throw new IOException("Both expected production artifact path and SHA-256 must be configured");
        }

        final Path expectedArtifact;
        try {
            expectedArtifact = Paths.get(configuredPath)
                .toAbsolutePath()
                .normalize();
        } catch (InvalidPathException exception) {
            throw new IOException("Invalid expected production artifact path: " + configuredPath, exception);
        }
        final Path actualArtifact = currentArtifact.toAbsolutePath()
            .normalize();
        if (!actualArtifact.equals(expectedArtifact)) {
            throw new IOException(
                "Relauncher loaded the wrong production artifact: expected " + expectedArtifact
                    + ", actual "
                    + actualArtifact);
        }
        final String actualSha256;
        try (java.io.InputStream input = Files.newInputStream(actualArtifact)) {
            actualSha256 = DigestUtils.sha256Hex(input);
        }
        if (!actualSha256.equalsIgnoreCase(configuredSha256)) {
            throw new IOException(
                "Production artifact SHA-256 mismatch for " + actualArtifact
                    + ": expected "
                    + configuredSha256
                    + ", actual "
                    + actualSha256);
        }

        int lwjgl3ifyArtifacts = 0;
        for (final String entry : childClasspath) {
            final Path candidate;
            try {
                candidate = Paths.get(entry)
                    .toAbsolutePath()
                    .normalize();
            } catch (InvalidPathException ignored) {
                continue;
            }
            final Path fileName = candidate.getFileName();
            if (fileName != null && fileName.toString()
                .toLowerCase(java.util.Locale.ROOT)
                .startsWith("lwjgl3ify-")
                && fileName.toString()
                    .toLowerCase(java.util.Locale.ROOT)
                    .endsWith(".jar")) {
                lwjgl3ifyArtifacts++;
                if (!candidate.equals(expectedArtifact)) {
                    throw new IOException("Child classpath contains an unexpected lwjgl3ify artifact: " + candidate);
                }
            }
        }
        if (lwjgl3ifyArtifacts != 1) {
            throw new IOException(
                "Child classpath must contain exactly one verified lwjgl3ify artifact; found " + lwjgl3ifyArtifacts);
        }
        logger.info(
            "Verified production artifact path={} size={} sha256={} childCopies={}",
            actualArtifact,
            Files.size(actualArtifact),
            actualSha256,
            lwjgl3ifyArtifacts);
    }

    static void appendConfiguredJvmArguments(List<String> command, boolean macos,
        RelauncherConfig.ConfigObject config) {
        command.addAll(Arrays.asList(RECOMMENDED_JAVA_ARGS));
        if (macos) command.add("-XstartOnFirstThread");
        command.addAll(config.toJvmArgs());
    }

    public static void appendManagedRuntimeProperties(List<String> command, JavaLaunchSelection launchSelection) {
        if (launchSelection.getSource() != JavaLaunchSelection.Source.BUNDLED) return;
        command.add("-D" + AutomaticRuntimeCoordinator.MANAGED_RUNTIME_PROPERTY + "=true");
        command
            .add("-D" + AutomaticRuntimeCoordinator.MANAGED_PLATFORM_PROPERTY + "=" + launchSelection.getPlatformId());
        command.add(
            "-D" + AutomaticRuntimeCoordinator.MANAGED_RUNTIME_VERSION_PROPERTY
                + "="
                + launchSelection.getRuntimeVersion());
    }

    static String describeLaunch(JavaLaunchSelection launchSelection, boolean forwardLogs, Path argumentFile) {
        return "with Java source=" + launchSelection.getSource()
            + " executable="
            + launchSelection.getEffectiveExecutable(forwardLogs)
            + " argumentFile="
            + argumentFile;
    }

    static List<String> createBootstrapCommand(JavaLaunchSelection launchSelection, boolean forwardLogs, Path argFile,
        Path myJarPath, Path forgePatchesJarPath, long parentPid, boolean showConsole, Path childLog) {
        final List<String> bootstrapCmd = new ArrayList<>();
        final String javaPath = launchSelection.getEffectiveExecutable(forwardLogs);
        bootstrapCmd.add(javaPath);
        if (forwardLogs) {
            bootstrapCmd.add("@" + argFile);
        } else {
            bootstrapCmd.add("-Xms16M");
            bootstrapCmd.add("-Xmx256M");
            bootstrapCmd.add("-cp");
            bootstrapCmd.add(forgePatchesJarPath + File.pathSeparator + myJarPath);
            bootstrapCmd.add("me.eigenraven.lwjgl3ify.relauncherstub.RelauncherStubMain");
            bootstrapCmd.add(Long.toString(parentPid));
            bootstrapCmd.add(Boolean.toString(showConsole));
            bootstrapCmd.add(javaPath);
            bootstrapCmd.add(argFile.toString());
            bootstrapCmd.add(
                childLog.toAbsolutePath()
                    .normalize()
                    .toString());
        }
        return bootstrapCmd;
    }

    private Path resolveRuntimeCacheRoot(Path gameDirectory) throws IOException {
        final String configured = System.getProperty(RUNTIME_CACHE_ROOT_PROPERTY, "")
            .trim();
        if (configured.isEmpty()) return osCache;
        try {
            Path path = Paths.get(configured);
            if (!path.isAbsolute()) path = gameDirectory.resolve(path);
            path = path.toAbsolutePath()
                .normalize();
            Files.createDirectories(path);
            return path;
        } catch (InvalidPathException exception) {
            throw new IOException("Invalid packaged Java cache root: " + configured, exception);
        }
    }

    private static void logAutomaticRuntime(AutomaticRuntimeResult automatic) {
        logger.info("Automatic packaged Java status: {} - {}", automatic.getStatus(), automatic.getMessage());
        if (automatic.getBundle() != null) {
            logger.info(
                "Packaged Java source={} kind={} path={}",
                automatic.getBundle()
                    .getSource(),
                automatic.getBundle()
                    .getKind(),
                automatic.getBundle()
                    .getPath());
        }
        if (automatic.getHost() != null) logger.info("Detected packaged Java host: {}", automatic.getHost());
        if (automatic.getInstallation() != null) {
            logger.info(
                "Packaged Java installation result={} platform={} Java home={} executable={}",
                automatic.getInstallation()
                    .wasInstalled() ? "installed" : "reused",
                automatic.getInstallation()
                    .getPlatformId(),
                automatic.getInstallation()
                    .getJavaHome(),
                automatic.getInstallation()
                    .getJavaExecutable());
        }
    }

}
