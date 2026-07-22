package me.eigenraven.lwjgl3ify.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Verifies stable repository and packaging invariants without starting Minecraft.
 *
 * The repository directory is deliberately internal rather than an input directory: local ignored
 * build/run state must not affect task fingerprints, and this task is intended to run every time.
 */
abstract class VerifyRepositoryTask : DefaultTask() {

    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    init {
        group = "verification"
        description = "Verifies lwjgl3ify repository structure, identities, metadata, and packaging wiring"
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verifyRepository() {
        val root = repositoryDirectory.get().asFile.canonicalFile
        val failures = mutableListOf<String>()

        fun relative(path: String): File = root.resolve(path)

        fun requireFile(path: String) {
            if (!relative(path).isFile) {
                failures += "Missing required file: $path"
            }
        }

        fun requireDirectory(path: String) {
            if (!relative(path).isDirectory) {
                failures += "Missing required directory: $path"
            }
        }

        fun requireJavaSources(path: String) {
            val directory = relative(path)
            if (!directory.isDirectory) {
                failures += "Missing required Java source directory: $path"
            } else if (directory.walkTopDown().none { it.isFile && it.extension == "java" }) {
                failures += "Required Java source directory contains no .java files: $path"
            }
        }

        val requiredFiles = listOf(
            "README.MD",
            "SETUP.md",
            "COMPILING.md",
            "LICENSE",
            "settings.gradle.kts",
            "build.gradle.kts",
            "buildSrc/build.gradle.kts",
            "buildSrc/gradle/gradle-daemon-jvm.properties",
            "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/JavaRuntimeManifest.kt",
            "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/JavaRuntimeBundleSupport.kt",
            "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/VerifyJavaRuntimeBundleTask.kt",
            "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/PackageJavaRuntimeBundleTask.kt",
            "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/VerifyJavaRuntimeInstallationTask.kt",
            "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/StageBundledJavaForRelauncherTask.kt",
            "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/VerifyAutomaticJavaRuntimeTask.kt",
            "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/PackageBundledJavaClientTask.kt",
            "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/VerifyBundledJavaClientPackageTask.kt",
            "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/ProductionArtifactSupport.kt",
            "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/VerifyProductionModArtifactTask.kt",
            "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/VerifyRelauncherChildExitPropagationTask.kt",
            "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/VerifyRepositoryTask.kt",
            "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/VersionJsonTask.kt",
            "buildSrc/src/test/kotlin/me/eigenraven/lwjgl3ify/gradle/JavaRuntimeContractTest.kt",
            "buildSrc/src/test/kotlin/me/eigenraven/lwjgl3ify/gradle/ProductionArtifactSupportTest.kt",
            "docs/BUNDLED_JAVA.md",
            "gradle.properties",
            "dependencies.gradle",
            "repositories.gradle",
            "jitpack.yml",
            "gradlew",
            "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties",
            "gradle/gradle-daemon-jvm.properties",
            "java9args.txt",
            "launcher-metadata/version.json",
            "prism-libraries/mmc-pack.json",
            "prism-libraries/patches/me.eigenraven.lwjgl3ify.forgepatches.json",
            "prism-libraries/patches/me.eigenraven.lwjgl3ify.launchargs.json",
            "prism-libraries/patches/net.minecraft.json",
            "prism-libraries/patches/net.minecraftforge.json",
            "prism-libraries/patches/org.lwjgl3.json",
            "src/main/resources/mcmod.info",
            "src/main/resources/META-INF/rfb-plugin/lwjgl3ify.properties",
            "src/main/resources/mixins.lwjgl3ify.json",
            "src/main/resources/mixins.lwjgl3ify.early.json",
            "src/main/resources/mixins.lwjgl3ify.late.json",
            "src/main/resources/me/eigenraven/lwjgl3ify/relauncher/runtime/java21-runtime-manifest.json",
            "src/main/resources/META-INF/licenses/Apache-2.0.txt",
            "src/main/resources/META-INF/licenses/commons-compress-NOTICE.txt",
            "src/main/resources/META-INF/licenses/commons-io-NOTICE.txt",
            "src/main/resources/META-INF/licenses/commons-codec-NOTICE.txt",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeInstallationException.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimePlatform.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeManifest.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimePathSafety.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeCacheLayout.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeInstallResult.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeInstallMarker.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeBundleReader.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeArchiveExtractor.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeInstaller.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeHost.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeHostDetectionException.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeHostDetector.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeBundleLocator.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/AutomaticRuntimeCoordinator.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/AutomaticRuntimeResult.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/JavaLaunchSelection.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/JavaLaunchSelector.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/ChildProcessSupervisor.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/LaunchDecision.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/LaunchDecisionPolicy.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/RelauncherUserInterface.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/TranslationsBundle.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/TranslationsBundle_pl_PL.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/SettingsLaunchController.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/SettingsDialog.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/SettingsDialog.form",
            "src/test/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeInstallerTest.java",
            "src/test/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeInstallerSmokeMain.java",
            "src/test/java/me/eigenraven/lwjgl3ify/relauncher/runtime/AutomaticRuntimeSmokeMain.java",
            "src/test/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeHostDetectorTest.java",
            "src/test/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeManifestSelectionTest.java",
            "src/test/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeBundleLocatorTest.java",
            "src/test/java/me/eigenraven/lwjgl3ify/relauncher/runtime/AutomaticRuntimeCoordinatorTest.java",
            "src/test/java/me/eigenraven/lwjgl3ify/relauncher/runtime/JavaLaunchSelectionTest.java",
            "src/test/java/me/eigenraven/lwjgl3ify/relauncher/runtime/JavaLaunchSelectorTest.java",
            "src/test/java/me/eigenraven/lwjgl3ify/relauncher/runtime/LaunchDecisionPolicyTest.java",
            "src/test/java/me/eigenraven/lwjgl3ify/relauncher/runtime/ManagedRuntimePropertiesTest.java",
            "src/test/java/me/eigenraven/lwjgl3ify/relauncher/ChildProcessSupervisorTest.java",
            "src/test/java/me/eigenraven/lwjgl3ify/relauncher/ProcessExitFixtureMain.java",
            "src/test/java/me/eigenraven/lwjgl3ify/relauncher/RelauncherChildExitSmokeMain.java",
            "src/test/java/me/eigenraven/lwjgl3ify/relauncher/RelauncherConfigTest.java",
            "src/test/java/me/eigenraven/lwjgl3ify/relauncher/RelauncherCommandTest.java",
            "src/test/java/me/eigenraven/lwjgl3ify/relauncher/SettingsLaunchControllerTest.java",
            "src/forgePatches/ScriptEngineServices.txt",
            "src/forgePatches/lwjgl3ify-forgePatches-version.txt",
            "src/main/java/me/eigenraven/lwjgl3ify/core/Lwjgl3ifyCoremod.java",
            "src/main/java/me/eigenraven/lwjgl3ify/rfb/Lwjgl3ifyRfbPlugin.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/Lwjgl3ifyRelauncherTweaker.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/Relauncher.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/JvmLocator.java",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/RelauncherConfig.java",
            "src/relauncherStub/java/me/eigenraven/lwjgl3ify/relauncherstub/RelauncherStubMain.java",
            "scripts/package-source.sh",
            "scripts/validate-java-runtime-contract.sh",
        )
        requiredFiles.forEach(::requireFile)

        val rootDaemonCriteria = relative("gradle/gradle-daemon-jvm.properties")
        val buildSrcDaemonCriteria = relative("buildSrc/gradle/gradle-daemon-jvm.properties")
        if (rootDaemonCriteria.isFile && buildSrcDaemonCriteria.isFile &&
            !rootDaemonCriteria.readBytes().contentEquals(buildSrcDaemonCriteria.readBytes())
        ) {
            failures += "buildSrc daemon JVM criteria must match the root Gradle daemon JVM criteria"
        }

        listOf(
            "buildSrc/src/main/kotlin",
            "buildSrc/src/test/kotlin",
            "docs",
            "launcher-metadata",
            "prism-libraries/patches",
            "src/main/resources/assets/lwjgl3ify",
            "src/main/java/me/eigenraven/lwjgl3ify/rfb",
            "src/main/java/me/eigenraven/lwjgl3ify/mixins",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher",
            "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime",
            "src/test/java/me/eigenraven/lwjgl3ify/relauncher/runtime",
            "src/main/resources/META-INF/licenses",
            "src/forgePatches",
        ).forEach(::requireDirectory)

        listOf(
            "src/main/java",
            "src/generated/java",
            "src/util/java",
            "src/hotswap/java",
            "src/relauncherStub/java",
            "src/test/java",
        ).forEach(::requireJavaSources)

        val wrapperPropertiesFile = relative("gradle/wrapper/gradle-wrapper.properties")
        if (wrapperPropertiesFile.isFile) {
            val properties = loadProperties(wrapperPropertiesFile, failures)
            val distributionUrl = properties?.getProperty("distributionUrl")?.replace("\\:", ":")
            val expectedUrl = "https://services.gradle.org/distributions/gradle-9.3.1-bin.zip"
            if (distributionUrl != expectedUrl) {
                failures +=
                    "Gradle wrapper distribution must remain $expectedUrl but was ${distributionUrl ?: "missing"}"
            }
        }

        val daemonPropertiesFile = relative("gradle/gradle-daemon-jvm.properties")
        if (daemonPropertiesFile.isFile) {
            val properties = loadProperties(daemonPropertiesFile, failures)
            if (properties?.getProperty("toolchainVersion") != "25") {
                failures += "Gradle daemon JVM criteria must remain Java 25 unless deliberately migrated"
            }
        }

        val gradlePropertiesFile = relative("gradle.properties")
        if (gradlePropertiesFile.isFile) {
            val properties = loadProperties(gradlePropertiesFile, failures)
            if (properties?.getProperty("modId") != "lwjgl3ify") {
                failures += "Public mod ID must remain lwjgl3ify"
            }
            if (properties?.getProperty("modGroup") != "me.eigenraven.lwjgl3ify") {
                failures += "Root Java package identity must remain me.eigenraven.lwjgl3ify"
            }
            if (properties?.getProperty("minecraftVersion") != "1.7.10") {
                failures += "Minecraft target must remain 1.7.10"
            }
            if (properties?.getProperty("forgeVersion") != "10.13.4.1614") {
                failures += "Forge target must remain 10.13.4.1614"
            }
            if (properties?.getProperty("enableModernJavaSyntax") != "jabel") {
                failures += "Normal Java compilation must remain on the GTNH Jabel toolchain path"
            }
            if (properties?.getProperty("coreModClass") != "core.Lwjgl3ifyCoremod") {
                failures += "Coremod class identity must remain core.Lwjgl3ifyCoremod"
            }
            if (properties?.getProperty("useModGroupForPublishing") != "false") {
                failures += "Existing Maven-coordinate behavior must remain useModGroupForPublishing=false"
            }
            val activeLines = gradlePropertiesFile.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
            if (activeLines.any { it.contains("ExampleMod", ignoreCase = true) }) {
                failures += "Active gradle.properties configuration still contains an ExampleMod placeholder identity"
            }
        }

        checkFileContains(
            relative("buildSrc/build.gradle.kts"),
            listOf(
                "compilerOptions.jvmTarget = JvmTarget.JVM_24",
                "options.release = 24",
                "org.apache.commons:commons-compress:1.27.1",
                "testImplementation(kotlin(\"test-junit\"))",
            ),
            "buildSrc JVM boundary",
            failures,
        )
        checkFileContains(
            relative("settings.gradle.kts"),
            listOf("rootProject.name = \"lwjgl3ify\""),
            "settings.gradle.kts",
            failures,
        )
        checkFileContains(
            relative("src/main/resources/mcmod.info"),
            listOf(
                "\"modid\": \"\${modId}\"",
                "https://github.com/Wargames-Development/lwjgl3ify-wdg",
            ),
            "mcmod.info",
            failures,
        )
        checkFileContains(
            relative("src/main/resources/META-INF/rfb-plugin/lwjgl3ify.properties"),
            listOf(
                "name=LWJGL3ify",
                "className=me.eigenraven.lwjgl3ify.rfb.Lwjgl3ifyRfbPlugin",
            ),
            "RFB plugin metadata",
            failures,
        )
        checkFileContains(
            relative("src/main/resources/mixins.lwjgl3ify.json"),
            listOf("\"package\": \"me.eigenraven.lwjgl3ify.mixins\""),
            "main mixin configuration",
            failures,
        )
        checkFileContains(
            relative("src/main/resources/mixins.lwjgl3ify.early.json"),
            listOf("\"package\": \"me.eigenraven.lwjgl3ify.mixins.early\""),
            "early mixin configuration",
            failures,
        )
        checkFileContains(
            relative("src/main/resources/mixins.lwjgl3ify.late.json"),
            listOf("\"package\": \"me.eigenraven.lwjgl3ify.mixins.late\""),
            "late mixin configuration",
            failures,
        )

        checkFileContains(
            relative("build.gradle.kts"),
            listOf(
                "srcDir(\"src/generated/java\")",
                "languageVersion = JavaLanguageVersion.of(17)",
                "languageVersion = JavaLanguageVersion.of(21)",
                "runClientWithRelauncher",
                "import me.eigenraven.lwjgl3ify.gradle.PackageJavaRuntimeBundleTask",
                "import me.eigenraven.lwjgl3ify.gradle.VerifyJavaRuntimeBundleTask",
                "import me.eigenraven.lwjgl3ify.gradle.VerifyJavaRuntimeInstallationTask",
                "import me.eigenraven.lwjgl3ify.gradle.StageBundledJavaForRelauncherTask",
                "import me.eigenraven.lwjgl3ify.gradle.VerifyAutomaticJavaRuntimeTask",
                "import me.eigenraven.lwjgl3ify.gradle.PackageBundledJavaClientTask",
                "import me.eigenraven.lwjgl3ify.gradle.VerifyBundledJavaClientPackageTask",
                "import me.eigenraven.lwjgl3ify.gradle.VersionJsonTask",
                "tasks.register<VerifyJavaRuntimeBundleTask>(\"verifyJavaRuntimeBundle\")",
                "tasks.register<PackageJavaRuntimeBundleTask>(\"packageJavaRuntimeBundle\")",
                "tasks.register<VerifyJavaRuntimeInstallationTask>(\"verifyJavaRuntimeInstallation\")",
                "wdgJavaRuntimeBundle",
                "wdgJavaRuntimePlatform",
                "RuntimeInstallerSmokeMain",
                "runtime-installation-smoke",
                "automatic-runtime-smoke",
                "lwjgl3ify/runtime/lwjgl3ify-wdg-java21-runtimes.zip",
                "wdgRelauncherRuntimeCacheRoot",
                "wdgRelauncherForceSettings",
                "wdgRelauncherDisableBundledJava",
                "lwjgl3ify.relauncher.additionalClasspath",
                "lwjgl3ify.relauncher.additionalTweakers",
                "relauncherSmokeChildSupport",
                "relauncherSmokeRuntime",
                "client-with-java21.zip",
                "modJar.set(productionModArtifact)",
                "runtimeInstallerEmbedded",
                "compileClasspath += runtimeInstallerEmbedded",
                "runtimeClasspath += runtimeInstallerEmbedded",
                "relocate(\"org.apache.commons.compress\", \"me.eigenraven.lwjgl3ify.internal.commonscompress\")",
                "relocate(\"org.apache.commons.io\", \"me.eigenraven.lwjgl3ify.internal.commonsio\")",
                "relocate(\"org.apache.commons.codec\", \"me.eigenraven.lwjgl3ify.internal.commonscodec\")",
                "WDG_JAVA_RUNTIME_BUNDLE",
                "runtime-packages/lwjgl3ify-wdg-java21-runtimes.zip",
                "tasks.register<VersionJsonTask>(\"versionJson\")",
                "forgePatchesArchive.set(forgePatchesJar.flatMap { it.archiveFile })",
                "outputFile.set(versionJsonPath)",
                "isIgnoreExitValue = true",
                "1970-01-01T00:00:00Z",
                "from(relauncherStubSet.output)",
                "src/forgePatches/lwjgl3ify-forgePatches-version.txt",
                "from(project.file(\"prism-libraries/\"))",
                "me/eigenraven/lwjgl3ify/relauncher/forgePatches.zip",
                "me/eigenraven/lwjgl3ify/relauncher/version.json",
                "javaLauncher.set(newJavaLauncher)",
                "RECOMMENDED_JAVA_ARGS",
                "extraJvmArgs = jArgs",
                "TweakClass\", \"me.eigenraven.lwjgl3ify.relauncher.Lwjgl3ifyRelauncherTweaker",
            ),
            "build.gradle.kts packaging/source-set wiring",
            failures,
        )

        checkFileContains(
            relative("buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/VerifyJavaRuntimeInstallationTask.kt"),
            listOf(
                "abstract class VerifyJavaRuntimeInstallationTask",
                "private val execOperations: ExecOperations",
                "private val fileSystemOperations: FileSystemOperations",
                "@TaskAction",
                "execOperations.javaexec",
                "fileSystemOperations.delete",
            ),
            "configuration-cache-safe runtime installation smoke task",
            failures,
        )

        checkFileContains(
            relative("buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/StageBundledJavaForRelauncherTask.kt"),
            listOf("abstract class StageBundledJavaForRelauncherTask", "@get:InputFile", "@get:OutputFile", "@TaskAction"),
            "configuration-cache-safe bundled Java staging task",
            failures,
        )
        checkFileContains(
            relative("buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/VerifyAutomaticJavaRuntimeTask.kt"),
            listOf(
                "abstract class VerifyAutomaticJavaRuntimeTask",
                "ExecOperations",
                "FileSystemOperations",
                "@get:LocalState",
                "@TaskAction",
            ),
            "configuration-cache-safe automatic Java execution task",
            failures,
        )
        checkFileContains(
            relative("buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/PackageBundledJavaClientTask.kt"),
            listOf("abstract class PackageBundledJavaClientTask", "@get:InputFile", "@get:OutputFile", "REPRODUCIBLE_TIME"),
            "configuration-cache-safe client overlay packaging task",
            failures,
        )
        checkFileContains(
            relative("buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/VerifyBundledJavaClientPackageTask.kt"),
            listOf(
                "abstract class VerifyBundledJavaClientPackageTask",
                "AutomaticRuntimeCoordinator.class",
                "JavaRuntimeBundlePackager.verifyPackagedBundle",
                "expectedModJar",
                "Packaged client mod JAR differs from the verified production artifact",
            ),
            "bundled Java client package verifier",
            failures,
        )

        checkFileContains(
            relative("build.gradle.kts"),
            listOf(
                "val productionModArtifact = tasks.reobfJar.flatMap { it.archiveFile }",
                "verifyProductionModArtifact",
                "generatedMixinRefmap",
                "expectedProductionArtifact",
                "expectedProductionSha256",
                "supervisedLaunch",
                "verifyRelauncherChildExitPropagation",
                "runRelauncherChildExitFailure",
            ),
            "verified production artifact and supervised smoke wiring",
            failures,
        )
        checkFileContains(
            relative("buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/VerifyProductionModArtifactTask.kt"),
            listOf(
                "@CacheableTask",
                "ProductionArtifactVerifier.verify",
                "generatedRefmap",
                "productionMinecraftJar",
                "canonicalRuntimeManifest",
                "verificationMetadata",
            ),
            "production artifact verifier task",
            failures,
        )
        checkFileContains(
            relative("buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/ProductionArtifactSupport.kt"),
            listOf(
                "MixinMinecraft_Display",
                "environment=searge",
                "verifyProductionBytecode",
                "fieldTargets != 1",
                "runtime manifest differs from the canonical manifest",
                "must contain exactly one",
                "PRODUCTION_REFMAP",
            ),
            "semantic production refmap verification",
            failures,
        )
        checkFileContains(
            relative("src/main/java/me/eigenraven/lwjgl3ify/relauncher/ChildProcessSupervisor.java"),
            listOf("process.waitFor()", "Thread.currentThread()", "process.destroyForcibly()"),
            "child process supervision seam",
            failures,
        )
        checkFileContains(
            relative("src/relauncherStub/java/me/eigenraven/lwjgl3ify/relauncherstub/RelauncherStubMain.java"),
            listOf("ChildProcessSupervisor.waitFor(child)", "System.exit(new RelauncherStubMain().run(args))"),
            "stub child result propagation",
            failures,
        )
        checkFileContains(
            relative("src/test/java/me/eigenraven/lwjgl3ify/relauncher/ChildProcessSupervisorTest.java"),
            listOf("preservesZeroAndNonzeroExitCodes", "interruptionPreservesFlagAndTerminatesChild"),
            "child process supervision tests",
            failures,
        )

        checkFileContains(
            relative("buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/VersionJsonTask.kt"),
            listOf(
                "@CacheableTask",
                "abstract class VersionJsonTask : DefaultTask()",
                "@TaskAction",
                "abstract val forgePatchesArchive: RegularFileProperty",
                "abstract val outputFile: RegularFileProperty",
            ),
            "configuration-cache-safe versionJson task",
            failures,
        )

        checkFileDoesNotContain(
            relative("build.gradle.kts"),
            listOf("abstract class VersionJsonTask"),
            "build.gradle.kts",
            failures,
        )


        checkFileContains(
            relative("dependencies.gradle"),
            listOf(
                "runtimeInstallerEmbedded(\"org.apache.commons:commons-compress:1.27.1\")",
                "testImplementation(\"junit:junit:4.13.2\")",
            ),
            "early runtime installer dependencies",
            failures,
        )
        checkFileContains(
            relative("src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeInstaller.java"),
            listOf(
                "public RuntimeInstallResult install(Path normalizedBundle, String platformId, Path cacheRoot)",
                "StandardCopyOption.ATOMIC_MOVE",
                "FileLock",
                "RuntimeInstallMarker",
            ),
            "production runtime installer",
            failures,
        )
        checkFileContains(
            relative("src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeArchiveExtractor.java"),
            listOf(
                "TarArchiveInputStream",
                "ZipArchiveInputStream",
                "Files.createSymbolicLink",
                "MAX_ENTRIES",
                "MAX_UNCOMPRESSED_BYTES",
            ),
            "secure runtime extractor",
            failures,
        )
        checkFileContains(
            relative("src/test/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeInstallerTest.java"),
            listOf(
                "concurrentInstallersProduceOneFinalInstallAndSameJavaPath",
                "validUnixFixturePreservesExecutableAndSafeSymlink",
                "validWindowsFixtureInstallsThenReusesWithoutMarkerRewrite",
                "neighbouringRuntimeIsUntouchedAndDeletionRefusesOutsideCache",
            ),
            "focused runtime installer tests",
            failures,
        )
        checkFileContains(
            relative("docs/BUNDLED_JAVA.md"),
            listOf(
                "Change 003 secure installer boundary",
                ".lwjgl3ify-runtime-install.json",
                "## Change 004 automatic client relaunch",
                "lwjgl3ify/runtime/lwjgl3ify-wdg-java21-runtimes.zip",
                "lwjgl3ify.relauncher.disableBundledJava",
                "lwjgl3ify.relauncher.forceSettings",
                "verifyAutomaticJavaRuntime",
                "packageBundledJavaClient",
                "Distant Horizons",
            ),
            "bundled Java automatic-runtime documentation",
            failures,
        )
        checkFileContains(
            relative("src/main/java/me/eigenraven/lwjgl3ify/relauncher/Relauncher.java"),
            listOf(
                "new AutomaticRuntimeCoordinator()",
                "LaunchDecisionPolicy",
                ".decide(",
                "createBootstrapCommand",
                "MANAGED_RUNTIME_PROPERTY",
                "runtimeCacheRoot",
                "Starting relaunched process {}",
                "with Java source=",
            ),
            "production automatic runtime relaunch wiring",
            failures,
        )
        checkFileContains(
            relative("src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeBundleLocator.java"),
            listOf(
                "lwjgl3ify.relauncher.runtimeBundle",
                "LWJGL3IFY_RUNTIME_BUNDLE",
                "lwjgl3ify/runtime/lwjgl3ify-wdg-java21-runtimes.zip",
            ),
            "canonical runtime bundle locator",
            failures,
        )
        checkFileContains(
            relative("src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeHostDetector.java"),
            listOf(
                "/usr/sbin/sysctl",
                "PROCESSOR_ARCHITEW6432",
                "PROCESSOR_ARCHITECTURE",
                "getconf",
                "GNU_LIBC_VERSION",
            ),
            "bounded cross-platform host detector",
            failures,
        )
        checkFileContains(
            relative("src/main/java/me/eigenraven/lwjgl3ify/relauncher/RelauncherConfig.java"),
            listOf("useBundledJava = true", "javaInstallationsCache == null", "garbageCollector == null"),
            "backward-compatible relauncher config",
            failures,
        )
        checkFileContains(
            relative("src/main/java/me/eigenraven/lwjgl3ify/relauncher/SettingsDialog.form"),
            listOf("optUseBundledJava", "lblBundledStatus"),
            "packaged Java settings form",
            failures,
        )
        checkFileDoesNotContain(
            relative("src/main/java/me/eigenraven/lwjgl3ify/relauncher/Lwjgl3ifyRelauncherTweaker.java"),
            listOf("RuntimeInstaller", "AutomaticRuntimeCoordinator"),
            "dedicated tweaker boundary",
            failures,
        )
        listOf("build.gradle.kts", "dependencies.gradle").forEach { dependencyPath ->
            checkFileDoesNotContain(
                relative(dependencyPath),
                listOf("DistantHorizons", "Distant Horizons", "distanthorizons"),
                "deferred Distant Horizons dependency boundary in $dependencyPath",
                failures,
            )
        }

        checkFileContains(
            relative("build.gradle.kts"),
            listOf(
                "classpath(relauncherSmokeRuntime)",
                "files(productionModArtifact, relauncherSmokeChildSupport)",
                "ProviderToStringWrapper(smokeChildClasspathString)",
            ),
            "production-like relauncher smoke isolation",
            failures,
        )
        checkFileContains(
            relative("dependencies.gradle"),
            listOf("relauncherSmokeChildSupport('io.github.legacymoddingmc:unimixins:0.3.1:dev') { transitive = false }"),
            "required UniMixins relauncher smoke support",
            failures,
        )
        checkFileDoesNotContain(
            relative("build.gradle.kts"),
            listOf(
                "lwjgl3ify.relauncher.inheritParentClasspath",
                "lwjgl3ify.relauncher.inheritParentTweakers",
            ),
            "removed full parent development inheritance",
            failures,
        )
        checkFileDoesNotContain(
            relative("src/main/java/me/eigenraven/lwjgl3ify/mixins/early/game/MixinMinecraft_Display.java"),
            listOf("require = 0", "require=0"),
            "required MixinMinecraft_Display injection",
            failures,
        )
        checkFileDoesNotContain(
            relative("src/main/java/me/eigenraven/lwjgl3ify/relauncher/Relauncher.java"),
            listOf(
                "System.getProperty(\"java.class.path\"",
                "Launch.blackboard.get(\"Tweaks\")",
            ),
            "isolated production-like child launch",
            failures,
        )

        checkFileContains(
            relative("src/main/java/me/eigenraven/lwjgl3ify/relauncher/Relauncher.java"),
            listOf(
                "ADDITIONAL_CLASSPATH_PROPERTY",
                "appendExistingClasspathEntries",
                "explicitly configured development support classpath entries",
                "ADDITIONAL_TWEAKERS_PROPERTY",
                "appendConfiguredTweakerArguments",
                "explicitly configured development support tweakers",
            ),
            "production-like development relaunch support",
            failures,
        )
        checkFileContains(
            relative("src/test/java/me/eigenraven/lwjgl3ify/relauncher/RelauncherCommandTest.java"),
            listOf(
                "developmentClasspathInheritanceIsGenericOrderedAndDeduplicated",
                "appendExistingClasspathEntries",
                "configuredDevelopmentTweakersAreOrderedAndDeduplicated",
                "appendConfiguredTweakerArguments",
            ),
            "production-like development relaunch regression test",
            failures,
        )

        checkFileContains(
            relative("src/main/java/me/eigenraven/lwjgl3ify/relauncher/Downloader.java"),
            listOf(
                "isBundledLwjgl3ifyArtifact(elGav)",
                "static boolean isBundledLwjgl3ifyArtifact(final String coordinates)",
            ),
            "embedded lwjgl3ify artifact download exclusion",
            failures,
        )
        checkFileContains(
            relative("src/test/java/me/eigenraven/lwjgl3ify/relauncher/DownloaderTest.java"),
            listOf(
                "bundledLwjgl3ifyArtifactsNeverRequireNetworkDownload",
                "com.github.GTNewHorizons:lwjgl3ify:3.0.28-master.3+local:forgePatches",
            ),
            "embedded lwjgl3ify artifact download regression test",
            failures,
        )

        checkFileContains(
            relative("launcher-metadata/version.json"),
            listOf(
                "@version@",
                "@lwjglDownloads@",
                "1.7.10-Forge10.13.4.1614-1.7.10-lwjgl3ify-@version@",
                "com.github.GTNewHorizons:lwjgl3ify:@version@:forgePatches",
                "\"majorVersion\": 25",
            ),
            "launcher metadata template",
            failures,
        )
        checkFileContains(
            relative("prism-libraries/mmc-pack.json"),
            listOf(
                "me.eigenraven.lwjgl3ify.forgepatches",
                "me.eigenraven.lwjgl3ify.launchargs",
                "org.lwjgl3",
            ),
            "MultiMC/Prism package metadata",
            failures,
        )

        checkFileContains(
            relative("scripts/validate-java-runtime-contract.sh"),
            listOf(
                "Required Java Packages*.zip",
                "verifyJavaRuntimeBundle",
                "packageJavaRuntimeBundle",
                "scripts/package-source.sh",
                "Your Terminal remains open",
            ),
            "Java runtime contract validation helper",
            failures,
        )

        val runtimeManifestFile = relative(
            "src/main/resources/me/eigenraven/lwjgl3ify/relauncher/runtime/java21-runtime-manifest.json",
        )
        if (runtimeManifestFile.isFile) {
            try {
                JavaRuntimeManifestIO.loadAndValidate(runtimeManifestFile)
            } catch (exception: RuntimeContractException) {
                failures += exception.message ?: "Canonical Java runtime manifest validation failed"
            }
        }

        verifyTrackedSourceHygiene(root, failures)

        if (failures.isNotEmpty()) {
            throw GradleException(
                "lwjgl3ify repository verification failed:\n" + failures.joinToString("\n") { " - $it" },
            )
        }

        logger.lifecycle("lwjgl3ify repository verification passed.")
    }

    private fun loadProperties(file: File, failures: MutableList<String>): Properties? = try {
        Properties().also { properties ->
            file.inputStream().use { input -> properties.load(input) }
        }
    } catch (exception: IOException) {
        failures += "Could not read ${file.name}: ${exception.message}"
        null
    }

    private fun checkFileContains(
        file: File,
        requiredText: List<String>,
        description: String,
        failures: MutableList<String>,
    ) {
        if (!file.isFile) {
            return
        }
        val contents = file.readText()
        requiredText.forEach { expected ->
            if (!contents.contains(expected)) {
                failures += "$description is missing required invariant: $expected"
            }
        }
    }

    private fun checkFileDoesNotContain(
        file: File,
        forbiddenText: List<String>,
        description: String,
        failures: MutableList<String>,
    ) {
        if (!file.isFile) {
            return
        }
        val contents = file.readText()
        forbiddenText.forEach { forbidden ->
            if (contents.contains(forbidden)) {
                failures += "$description contains forbidden invariant: $forbidden"
            }
        }
    }

    private fun verifyTrackedSourceHygiene(root: File, failures: MutableList<String>) {
        if (!root.resolve(".git").exists()) {
            logger.lifecycle("Git metadata is absent; tracked-source hygiene check skipped.")
            return
        }

        val outputFile = File.createTempFile("lwjgl3ify-git-files", ".txt")
        val process = try {
            ProcessBuilder("git", "-C", root.absolutePath, "ls-files", "-z")
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
                .start()
        } catch (exception: IOException) {
            outputFile.delete()
            failures += "Git metadata exists but tracked-source hygiene could not start git: ${exception.message}"
            return
        }

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            outputFile.delete()
            failures += "Timed out while reading tracked files for repository hygiene"
            return
        }
        val output = outputFile.readBytes()
        outputFile.delete()
        if (process.exitValue() != 0) {
            failures +=
                "git ls-files failed while checking tracked-source hygiene: " +
                output.toString(Charsets.UTF_8).trim()
            return
        }

        val forbiddenSegments = setOf(
            ".gradle",
            "build",
            "run",
            "eclipse",
            ".idea",
            ".vscode",
            "logs",
            "crash-reports",
            "saves",
            "config",
            "__MACOSX",
        )
        val forbiddenNames = setOf(
            ".DS_Store",
            "Thumbs.db",
            "Desktop.ini",
            "log.txt",
            ".env",
        )
        val forbiddenSuffixes = listOf(
            ".log",
            ".zip",
            ".tar.gz",
            ".tgz",
            ".iml",
            ".ipr",
            ".iws",
            ".pem",
            ".key",
            ".p12",
        )

        output.toString(Charsets.UTF_8)
            .split('\u0000')
            .filter { it.isNotEmpty() }
            .forEach { trackedPath ->
                if (!root.resolve(trackedPath).exists()) {
                    return@forEach
                }
                val normalized = trackedPath.replace('\\', '/')
                val segments = normalized.split('/')
                val fileName = segments.last()
                val forbidden = segments.any(forbiddenSegments::contains) ||
                    forbiddenNames.contains(fileName) ||
                    forbiddenSuffixes.any { normalized.endsWith(it, ignoreCase = true) } ||
                    fileName.startsWith(".env.")
                if (forbidden) {
                    failures += "Tracked local/generated/archive file is not allowed: $normalized"
                }
            }
    }
}
