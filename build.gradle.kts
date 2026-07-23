import com.gtnewhorizons.retrofuturagradle.mcp.GenSrgMappingsTask
import com.gtnewhorizons.retrofuturagradle.minecraft.MinecraftTasks
import com.gtnewhorizons.retrofuturagradle.minecraft.RunMinecraftTask
import com.gtnewhorizons.retrofuturagradle.shadow.org.apache.commons.lang3.SystemUtils
import com.gtnewhorizons.retrofuturagradle.util.Distribution
import com.gtnewhorizons.retrofuturagradle.util.ProviderToStringWrapper
import com.modrinth.minotaur.ModrinthExtension
import de.undercouch.gradle.tasks.download.Download
import me.eigenraven.lwjgl3ify.gradle.PackageBundledJavaClientTask
import me.eigenraven.lwjgl3ify.gradle.PackageJavaRuntimeBundleTask
import me.eigenraven.lwjgl3ify.gradle.PackageRuntimeBundledModJarTask
import me.eigenraven.lwjgl3ify.gradle.PackageRuntimeExtensionArchivesTask
import me.eigenraven.lwjgl3ify.gradle.StageBundledJavaForRelauncherTask
import me.eigenraven.lwjgl3ify.gradle.VerifyAutomaticJavaRuntimeTask
import me.eigenraven.lwjgl3ify.gradle.VerifyBundledJavaClientPackageTask
import me.eigenraven.lwjgl3ify.gradle.VerifyJavaRuntimeBundleTask
import me.eigenraven.lwjgl3ify.gradle.VerifyJavaRuntimeInstallationTask
import me.eigenraven.lwjgl3ify.gradle.VerifyProductionModArtifactTask
import me.eigenraven.lwjgl3ify.gradle.VerifyRuntimeBundledModJarReproducibilityTask
import me.eigenraven.lwjgl3ify.gradle.VerifyRuntimeBundledModJarTask
import me.eigenraven.lwjgl3ify.gradle.VerifyRelauncherChildExitPropagationTask
import me.eigenraven.lwjgl3ify.gradle.VerifyRepositoryTask
import me.eigenraven.lwjgl3ify.gradle.VersionJsonTask
import org.gradle.api.tasks.PathSensitivity
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.streams.toList

plugins {
    id("com.gtnewhorizons.gtnhconvention")
    id("lwjgl3ify-helper")
}

val taskGroup = "lwjgl3ify"

val verifyRepository = tasks.register<VerifyRepositoryTask>("verifyRepository") {
    repositoryDirectory.set(layout.projectDirectory)
}

tasks.named("check") {
    dependsOn(verifyRepository)
}

val javaRuntimeManifestFile = layout.projectDirectory.file(
    "src/main/resources/me/eigenraven/lwjgl3ify/relauncher/runtime/java21-runtime-manifest.json",
)
val javaRuntimeBundlePath = providers.gradleProperty("wdgJavaRuntimeBundle")
    .orElse(providers.environmentVariable("WDG_JAVA_RUNTIME_BUNDLE"))
val javaRuntimeBundleFile = layout.file(
    javaRuntimeBundlePath.map { configuredPath -> File(configuredPath) },
)

val verifyJavaRuntimeBundle = tasks.register<VerifyJavaRuntimeBundleTask>("verifyJavaRuntimeBundle") {
    manifestFile.set(javaRuntimeManifestFile)
    bundleFile.set(javaRuntimeBundleFile)
}

val packageJavaRuntimeBundle = tasks.register<PackageJavaRuntimeBundleTask>("packageJavaRuntimeBundle") {
    dependsOn(verifyJavaRuntimeBundle)
    manifestFile.set(javaRuntimeManifestFile)
    bundleFile.set(javaRuntimeBundleFile)
    outputFile.set(
        layout.buildDirectory.file(
            "runtime-packages/lwjgl3ify-wdg-java21-runtimes.zip",
        ),
    )
}

val packageRuntimeExtensionArchives =
    tasks.register<PackageRuntimeExtensionArchivesTask>("packageRuntimeExtensionArchives") {
        dependsOn(packageJavaRuntimeBundle)
        normalizedBundle.set(packageJavaRuntimeBundle.flatMap { it.outputFile })
        manifestFile.set(javaRuntimeManifestFile)
        outputDirectory.set(layout.buildDirectory.dir("runtime-extensions"))
    }

val javaRuntimePlatform = providers.gradleProperty("wdgJavaRuntimePlatform")
val javaRuntimeInstallationSmokeRoot = layout.buildDirectory.dir("runtime-installation-smoke")

val verifyJavaRuntimeInstallation =
    tasks.register<VerifyJavaRuntimeInstallationTask>("verifyJavaRuntimeInstallation") {
        dependsOn(packageJavaRuntimeBundle, tasks.testClasses)
        runtimeClasspath.from(sourceSets.test.get().runtimeClasspath)
        normalizedBundle.set(packageJavaRuntimeBundle.flatMap { it.outputFile })
        platformId.set(javaRuntimePlatform)
        cacheRoot.set(javaRuntimeInstallationSmokeRoot)
        mainClassName.set("me.eigenraven.lwjgl3ify.relauncher.runtime.RuntimeInstallerSmokeMain")
    }

val developmentGameDirectoryPath = providers.gradleProperty("wdgRelauncherGameDirectory")
    .orElse(layout.projectDirectory.dir("run").asFile.absolutePath)
val developmentGameDirectory = layout.dir(developmentGameDirectoryPath.map(::File))
val stageBundledJavaForRelauncher =
    tasks.register<StageBundledJavaForRelauncherTask>("stageBundledJavaForRelauncher") {
        dependsOn(packageJavaRuntimeBundle)
        normalizedBundle.set(packageJavaRuntimeBundle.flatMap { it.outputFile })
        stagedBundle.set(
            developmentGameDirectory.map { directory ->
                directory.file("lwjgl3ify/runtime/lwjgl3ify-wdg-java21-runtimes.zip")
            },
        )
    }

val verifyAutomaticJavaRuntime =
    tasks.register<VerifyAutomaticJavaRuntimeTask>("verifyAutomaticJavaRuntime") {
        dependsOn(packageJavaRuntimeBundle, tasks.testClasses)
        runtimeClasspath.from(sourceSets.test.get().runtimeClasspath)
        normalizedBundle.set(packageJavaRuntimeBundle.flatMap { it.outputFile })
        platformId.set(javaRuntimePlatform)
        cacheRoot.set(layout.buildDirectory.dir("automatic-runtime-smoke/cache"))
        gameDirectory.set(layout.buildDirectory.dir("automatic-runtime-smoke/game"))
        mainClassName.set("me.eigenraven.lwjgl3ify.relauncher.runtime.AutomaticRuntimeSmokeMain")
    }

val newJavaToolchainSpec: JavaToolchainSpec.() -> Unit = {
    vendor = JvmVendorSpec.AZUL
    languageVersion = JavaLanguageVersion.of(17)
}

val addOpens = listOf(
    "java.base/java.io=ALL-UNNAMED",
    "java.base/java.lang.invoke=ALL-UNNAMED",
    "java.base/java.lang.ref=ALL-UNNAMED",
    "java.base/java.lang.reflect=ALL-UNNAMED",
    "java.base/java.lang=ALL-UNNAMED",
    "java.base/java.net.spi=ALL-UNNAMED",
    "java.base/java.net=ALL-UNNAMED",
    "java.base/java.nio.channels=ALL-UNNAMED",
    "java.base/java.nio.charset=ALL-UNNAMED",
    "java.base/java.nio.file=ALL-UNNAMED",
    "java.base/java.nio=ALL-UNNAMED",
    "java.base/java.text=ALL-UNNAMED",
    "java.base/java.time.chrono=ALL-UNNAMED",
    "java.base/java.time.format=ALL-UNNAMED",
    "java.base/java.time.temporal=ALL-UNNAMED",
    "java.base/java.time.zone=ALL-UNNAMED",
    "java.base/java.time=ALL-UNNAMED",
    "java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "java.base/java.util.concurrent.locks=ALL-UNNAMED",
    "java.base/java.util.jar=ALL-UNNAMED",
    "java.base/java.util.zip=ALL-UNNAMED",
    "java.base/java.util=ALL-UNNAMED",
    "java.base/jdk.internal.loader=ALL-UNNAMED",
    "java.base/jdk.internal.misc=ALL-UNNAMED",
    "java.base/jdk.internal.ref=ALL-UNNAMED",
    "java.base/jdk.internal.reflect=ALL-UNNAMED",
    "java.base/sun.nio.ch=ALL-UNNAMED",
    "java.desktop/com.sun.imageio.plugins.png=ALL-UNNAMED",
    "java.desktop/sun.awt.image=ALL-UNNAMED",
    "java.desktop/sun.awt=ALL-UNNAMED",
    "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
    "java.sql.rowset/javax.sql.rowset.serial=ALL-UNNAMED",
    "jdk.dynalink/jdk.dynalink.beans=ALL-UNNAMED",
    "jdk.naming.dns/com.sun.jndi.dns=ALL-UNNAMED,java.naming",
)

val extraJavaArgs = mutableListOf(
    "-Dfile.encoding=UTF-8",
    "-Djava.system.class.loader=com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader",
    "--enable-native-access",
    "ALL-UNNAMED"
)
for (openSpec in addOpens) {
    extraJavaArgs += listOf("--add-opens", openSpec)
}

tasks.register("updateJava9ArgsTxt") {
    group = taskGroup
    description = "Updates java9args.txt with the current argument list"
    outputs.file("java9args.txt")
    val writtenText = extraJavaArgs.joinToString("\n") + "\n"
    doLast {
        File("java9args.txt").writeText(writtenText, Charsets.UTF_8)
    }
}

minecraft {
    injectedTags.put("RECOMMENDED_JAVA_ARGS", extraJavaArgs.joinToString("\t"))
}

lateinit var forgePatchesEmbedded: Configuration
lateinit var runtimeInstallerEmbedded: Configuration
lateinit var relauncherSmokeChildSupport: Configuration
lateinit var relauncherSmokeRuntime: Configuration
lateinit var versionJsonElements: Configuration

configurations {
    forgePatchesEmbedded = create("forgePatchesEmbedded") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    runtimeInstallerEmbedded = create("runtimeInstallerEmbedded") {
        isCanBeConsumed = false
        isCanBeResolved = true
        description = "Private early-runtime archive support embedded and relocated into the release shadow JAR"
    }
    relauncherSmokeChildSupport = create("relauncherSmokeChildSupport") {
        isCanBeConsumed = false
        isCanBeResolved = true
        description = "Required mod support added explicitly to the production-like relauncher smoke child"
    }
    relauncherSmokeRuntime = create("relauncherSmokeRuntime") {
        isCanBeConsumed = false
        isCanBeResolved = true
        description = "Minimal parent runtime for the production-like relauncher smoke"
        extendsFrom(
            named("api").get(),
            named("implementation").get(),
            named("runtimeOnly").get(),
            runtimeInstallerEmbedded,
            relauncherSmokeChildSupport,
        )
    }
    named("shadowImplementation") {
        extendsFrom(runtimeInstallerEmbedded)
    }
    versionJsonElements = create("versionJsonElements") {
        isCanBeConsumed = false
        isCanBeResolved = false
    }
    patchedMinecraft { extendsFrom(forgePatchesEmbedded) }
}

lateinit var hotswapSet: SourceSet
lateinit var relauncherStubSet: SourceSet

sourceSets {
    create("util")
    hotswapSet = create("hotswap")
    relauncherStubSet = create("relauncherStub") {
        compileClasspath +=
            sourceSets.main.get().output + forgePatchesEmbedded + configurations.shadowImplementation.get()
    }
    main {
        java {
            srcDir("src/generated/java")
        }
        compileClasspath += runtimeInstallerEmbedded
        runtimeClasspath += runtimeInstallerEmbedded
    }
    test {
        compileClasspath += runtimeInstallerEmbedded + relauncherStubSet.output
        runtimeClasspath += runtimeInstallerEmbedded + relauncherStubSet.output
    }
}

tasks.named<JavaCompile>(hotswapSet.compileJavaTaskName).configure {
    javaCompiler = javaToolchains.compilerFor(newJavaToolchainSpec)
    sourceCompatibility = JavaVersion.VERSION_17.majorVersion
    targetCompatibility = JavaVersion.VERSION_17.majorVersion
}

tasks.named<JavaCompile>(relauncherStubSet.compileJavaTaskName).configure {
    javaCompiler = javaToolchains.compilerFor(newJavaToolchainSpec)
    sourceCompatibility = JavaVersion.VERSION_17.majorVersion
    targetCompatibility = JavaVersion.VERSION_17.majorVersion
    options.release = 17
}

tasks.createMcLauncherFiles {
    // Override main class
    replacementTokens.put("@@BOUNCERCLIENT@@", "com.gtnewhorizons.retrofuturabootstrap.MainStartOnFirstThread")
    replacementTokens.put("@@BOUNCERSERVER@@", "com.gtnewhorizons.retrofuturabootstrap.Main")
}

val forgePatchesJar = tasks.register<Jar>("forgePatchesJar") {
    dependsOn(tasks.compileJava)
    group = taskGroup
    description = "Packages the forgePatches jar"
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    // Bootleg shadow jar
    forgePatchesEmbedded.resolve().forEach { dep ->
        from(zipTree(dep)) {
            filesMatching("META-INF/*") {
                this.name = "${dep.name}-${this.name}"
            }
            filesMatching("META-INF/services/javax.script.ScriptEngineFactory") {
                this.exclude()
            }
        }
    }
    from(project.file("src/forgePatches/ScriptEngineServices.txt")) {
        rename { return@rename "META-INF/services/javax.script.ScriptEngineFactory" }
    }
    exclude("module-info.class")
    exclude("META-INF/versions/9/module-info.class")
    exclude("log4j2.xml")
    from(sourceSets.main.map { it.output.classesDirs }) {
        include("me/eigenraven/lwjgl3ify/rfb/entry/ServerMain.class")
    }
    from(relauncherStubSet.output)
    val versionString = project.version.toString()
    inputs.property("version", versionString)
    from("src/forgePatches/lwjgl3ify-forgePatches-version.txt") {
        expand(mapOf("version" to versionString))
        rename { return@rename "META-INF/lwjgl3ify-forgePatches-version.txt" }
    }
    archiveClassifier.set("forgePatches")
    manifest {
        val libraryList = listOf(
            "libraries/com/typesafe/akka/akka-actor_2.11/2.3.3/akka-actor_2.11-2.3.3.jar",
            "libraries/com/typesafe/config/1.2.1/config-1.2.1.jar",
            "libraries/org/scala-lang/scala-actors-migration_2.11/1.1.0/scala-actors-migration_2.11-1.1.0.jar",
            "libraries/org/scala-lang/scala-compiler/2.11.1/scala-compiler-2.11.1.jar",
            "libraries/org/scala-lang/plugins/scala-continuations-library_2.11/1.0.2/scala-continuations-library_2.11-1.0.2.jar",
            "libraries/org/scala-lang/plugins/scala-continuations-plugin_2.11.1/1.0.2/scala-continuations-plugin_2.11.1-1.0.2.jar",
            "libraries/org/scala-lang/scala-library/2.11.1/scala-library-2.11.1.jar",
            "libraries/org/scala-lang/scala-parser-combinators_2.11/1.0.1/scala-parser-combinators_2.11-1.0.1.jar",
            "libraries/org/scala-lang/scala-reflect/2.11.1/scala-reflect-2.11.1.jar",
            "libraries/org/scala-lang/scala-swing_2.11/1.0.1/scala-swing_2.11-1.0.1.jar",
            "libraries/org/scala-lang/scala-xml_2.11/1.0.2/scala-xml_2.11-1.0.2.jar",
            "libraries/lzma/lzma/0.0.1/lzma-0.0.1.jar",
            "libraries/net/sf/jopt-simple/jopt-simple/4.5/jopt-simple-4.5.jar",
            "libraries/com/google/guava/guava/17.0/guava-17.0.jar",
            "forge-1.7.10-10.13.4.1614-1.7.10-universal.jar",
            "minecraft_server.1.7.10.jar"
        )
        attributes(
            mapOf(
                "Class-Path" to libraryList.joinToString(" "),
                "Add-Opens" to addOpens.stream().map{ it.split("=")[0]}.toList().joinToString(" "),
                "Main-Class" to "me.eigenraven.lwjgl3ify.rfb.entry.ServerMain"
            )
        )
    }
}

abstract class MmcZip: Zip() {
    @get:InputFile
    abstract val lwjgl3Json: RegularFileProperty
}

val mmcInstanceFilesZip = tasks.register<MmcZip>("mmcInstanceFiles") {
    group = taskGroup
    description = "Packages the MultiMC patches"
    dependsOn(forgePatchesJar, tasks.makeLwjgl3Json)
    lwjgl3Json = tasks.makeLwjgl3Json.flatMap { it.outputFile }
    archiveClassifier.set("multimc")
    from(project.file("prism-libraries/"))
    from(forgePatchesJar) {
        into("libraries/")
    }
    exclude("META-INF", "META-INF/**")
    val projVersion = project.version
    val jvmArgs = extraJavaArgs.joinToString(", ") { '"' + it + '"' }
    val lwjglVersion = libs.versions.lwjgl.get()
    val lwjglDownloadsFile = lwjgl3Json.asFile.get().absolutePath
    filesMatching(
        listOf(
            "mmc-pack.json",
            "patches/me.eigenraven.lwjgl3ify.forgepatches.json",
            "patches/me.eigenraven.lwjgl3ify.launchargs.json",
            "patches/org.lwjgl3.json"
        )
    ) {
        expand(
            mapOf(
                "version" to projVersion,
                "jvmArgs" to jvmArgs,
                "lwjglVersion" to lwjglVersion,
                "lwjglDownloadsFile" to lwjglDownloadsFile
            )
        )
    }
    filesMatching("patches/net.minecraft.json") {
        filter {
            it.replace("\${lwjglVersion}", lwjglVersion)
        }
    }
}

val versionJsonPath = layout.buildDirectory.file("libs/version.json")

val gitLastCommitDate = providers.exec {
    commandLine("git", "log", "-1", "--format=%aI", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().ifEmpty { "1970-01-01T00:00:00Z" }

val versionJsonFile = tasks.register<VersionJsonTask>("versionJson") {
    dependsOn(tasks.makeLwjgl3Json)
    dependsOn(forgePatchesJar)
    templateFile.set(layout.projectDirectory.file("launcher-metadata/version.json"))
    forgePatchesArchive.set(forgePatchesJar.flatMap { it.archiveFile })
    lwjglDownloadsFile.set(tasks.makeLwjgl3Json.flatMap { it.outputFile })
    versionString.set(project.version.toString())
    jvmArgumentsJson.set(extraJavaArgs.joinToString(", ") { '"' + it + '"' })
    lwjglVersionString.set(libs.versions.lwjgl)
    commitTime.set(gitLastCommitDate)
    outputFile.set(versionJsonPath)
}

val generatedMixinRefmap = layout.buildDirectory.file("tmp/mixins/mixins.lwjgl3ify.refmap.json")
val productionModArtifact = tasks.reobfJar.flatMap { it.archiveFile }
val forgeMappings = tasks.named<GenSrgMappingsTask>("generateForgeSrgMappings")
val minecraftTasks = extensions.getByType<MinecraftTasks>()

// The generated refmap is a semantic input to both archive stages. RFG already copies it through
// processResources; these declarations prevent a stale shadow/reobf artifact from surviving a refmap change.
tasks.processResources {
    inputs.file(generatedMixinRefmap)
        .withPropertyName("generatedMixinRefmap")
        .withPathSensitivity(PathSensitivity.NONE)
}
tasks.shadowJar {
    dependsOn(tasks.processResources)
    inputs.file(generatedMixinRefmap)
        .withPropertyName("generatedMixinRefmap")
        .withPathSensitivity(PathSensitivity.NONE)
}
tasks.reobfJar {
    dependsOn(tasks.processResources)
    inputs.file(generatedMixinRefmap)
        .withPropertyName("generatedMixinRefmap")
        .withPathSensitivity(PathSensitivity.NONE)
}

val verifyProductionModArtifact =
    tasks.register<VerifyProductionModArtifactTask>("verifyProductionModArtifact") {
        dependsOn(tasks.reobfJar, forgeMappings, minecraftTasks.taskDownloadVanillaJars)
        productionModJar.set(productionModArtifact)
        mcpToSrgMapping.set(tasks.reobfJar.flatMap { it.srg })
        notchToSrgMapping.set(forgeMappings.flatMap { it.notchToSrg })
        productionMinecraftJar.set(minecraftTasks.vanillaClientLocation)
        generatedRefmap.set(generatedMixinRefmap)
        canonicalRuntimeManifest.set(javaRuntimeManifestFile)
        verificationMetadata.set(
            layout.buildDirectory.file("verification/production-mod-artifact.properties"),
        )
    }

val packageRuntimeBundledModJar =
    tasks.register<PackageRuntimeBundledModJarTask>("packageRuntimeBundledModJar") {
        dependsOn(verifyProductionModArtifact, packageJavaRuntimeBundle)
        baseModJar.set(productionModArtifact)
        normalizedBundle.set(packageJavaRuntimeBundle.flatMap { it.outputFile })
        manifestFile.set(javaRuntimeManifestFile)
        outputFile.set(
            layout.buildDirectory.file(
                "wdg-release/lwjgl3ify-${project.version}.jar",
            ),
        )
    }

val verifyRuntimeBundledModJar =
    tasks.register<VerifyRuntimeBundledModJarTask>("verifyRuntimeBundledModJar") {
        dependsOn(packageRuntimeBundledModJar)
        runtimeBundledJar.set(packageRuntimeBundledModJar.flatMap { it.outputFile })
        baseModJar.set(productionModArtifact)
        manifestFile.set(javaRuntimeManifestFile)
        verificationMetadata.set(
            layout.buildDirectory.file("verification/runtime-bundled-mod-artifact.properties"),
        )
    }

val verifyRuntimeBundledModJarReproducibility =
    tasks.register<VerifyRuntimeBundledModJarReproducibilityTask>(
        "verifyRuntimeBundledModJarReproducibility",
    ) {
        dependsOn(verifyProductionModArtifact, packageJavaRuntimeBundle)
        baseModJar.set(productionModArtifact)
        normalizedBundle.set(packageJavaRuntimeBundle.flatMap { it.outputFile })
        manifestFile.set(javaRuntimeManifestFile)
        verificationMetadata.set(
            layout.buildDirectory.file(
                "verification/runtime-bundled-mod-reproducibility.properties",
            ),
        )
    }

val productionArtifactPath = productionModArtifact.map { it.asFile.absoluteFile.toPath().normalize().toString() }
val productionArtifactSha256 = providers.fileContents(
    verifyProductionModArtifact.flatMap { it.verificationMetadata },
).asText.map { metadata ->
    metadata.lineSequence()
        .first { it.startsWith("artifactSha256=") }
        .substringAfter('=')
        .trim()
}

tasks.shadowJar {
    dependsOn(forgePatchesJar, versionJsonFile)
    relocate("org.apache.commons.compress", "me.eigenraven.lwjgl3ify.internal.commonscompress")
    relocate("org.apache.commons.io", "me.eigenraven.lwjgl3ify.internal.commonsio")
    relocate("org.apache.commons.codec", "me.eigenraven.lwjgl3ify.internal.commonscodec")
    from(hotswapSet.output)
    // Use .zip because shadow unpacks .jar archives into the parent jar
    from(forgePatchesJar) {
        rename { "me/eigenraven/lwjgl3ify/relauncher/forgePatches.zip" }
    }
    from(versionJsonFile) {
        rename { "me/eigenraven/lwjgl3ify/relauncher/version.json" }
    }
}

val packageBundledJavaClient =
    tasks.register<PackageBundledJavaClientTask>("packageBundledJavaClient") {
        dependsOn(verifyProductionModArtifact, packageJavaRuntimeBundle)
        modJar.set(productionModArtifact)
        normalizedBundle.set(packageJavaRuntimeBundle.flatMap { it.outputFile })
        topLevelDirectory.set("lwjgl3ify-wdg-client")
        outputFile.set(
            layout.buildDirectory.file(
                "distributions/lwjgl3ify-wdg-${project.version}-client-with-java21.zip",
            ),
        )
    }

val verifyBundledJavaClientPackage =
    tasks.register<VerifyBundledJavaClientPackageTask>("verifyBundledJavaClientPackage") {
        dependsOn(packageBundledJavaClient, verifyProductionModArtifact)
        packageFile.set(packageBundledJavaClient.flatMap { it.outputFile })
        expectedModJar.set(productionModArtifact)
        normalizedBundle.set(packageJavaRuntimeBundle.flatMap { it.outputFile })
        manifestFile.set(javaRuntimeManifestFile)
    }

val versionJsonArtifact = artifacts.add("versionJsonElements", versionJsonPath) {
    type = "json"
    classifier = "version"
    builtBy(versionJsonFile)
}

tasks.named("assemble").configure {
    dependsOn(forgePatchesJar)
    dependsOn(mmcInstanceFilesZip)
    dependsOn(versionJsonFile)
}

val runComparisonTool = tasks.register<JavaExec>("runComparisonTool") {
    group = taskGroup
    description = "Runs the 2<->3 mapping generation"
    classpath(sourceSets.named("util").map { it.runtimeClasspath })
    dependsOn("jar")
    mainClass.set("me.eigenraven.lwjgl3ify.ComparisonTool")
    minHeapSize = "2G"
    maxHeapSize = "2G"
}

tasks.processResources {
    inputs.property("version", project.version.toString())
    val projVersion = project.version.toString()
    filesMatching("META-INF/rfb-plugin/*") {
        expand("version" to projVersion)
    }
}

publishing.publications.named<MavenPublication>("maven") {
    artifact(forgePatchesJar)
    artifact(mmcInstanceFilesZip)
    artifact(versionJsonArtifact)
}

runComparisonTool.configure {
    val lwjgl2Zips = configurations.lwjgl2Classpath.get().resolve()
        .filter { !it.name.contains("natives") && !it.path.contains("net.java.j") }
    val lwjgl3Zips = configurations.lwjgl3Classpath.get().resolve().filter { !it.name.contains("natives") }
    val lwjgl2Args = lwjgl2Zips.map { "--2:" + it }
    val lwjgl3Args = lwjgl3Zips.map { "--3:" + it }
    val allArgs = lwjgl2Args + lwjgl3Args + listOf("--M:" + tasks.jar.get().archiveFile.get().asFile.path)
    args(allArgs)
}

val veryNewJavaToolchainSpec: JavaToolchainSpec.() -> Unit = {
    vendor = JvmVendorSpec.AZUL
    languageVersion = JavaLanguageVersion.of(21)
}

val newJavaLauncher = javaToolchains.launcherFor(veryNewJavaToolchainSpec)

for (jarTask in listOf(tasks.jar, tasks.shadowJar, forgePatchesJar, tasks.apiJar)) {
    jarTask.configure {
        manifest {
            attributes("Multi-Release" to true, "Implementation-Title" to "lwjgl3ify", "Implementation-Version" to project.version.toString())
        }
    }
}

for (runTask in listOf(tasks.runClient, tasks.runServer, tasks.runObfClient, tasks.runObfServer)) {
    runTask.configure {
        classpath = files(forgePatchesJar) + classpath
        val jArgs = mutableListOf<String>()
        jArgs.addAll(extraJavaArgs)
        if (this.side == Distribution.CLIENT && SystemUtils.IS_OS_MAC) {
            jArgs += "-XstartOnFirstThread"
        }
        extraJvmArgs = jArgs
        javaLauncher.set(newJavaLauncher)
    }
}

val originalLaunchWrapperPath = project.layout.buildDirectory.file("launchwrapper-1.12.jar").get().asFile

val dlOriginalLaunchwrapper = tasks.register<Download>("dlOriginalLaunchwrapper") {
    src("https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar")
    dest(originalLaunchWrapperPath)
    overwrite(false)
}

val runWithRelauncher = tasks.register<RunMinecraftTask>("runClientWithRelauncher", Distribution.CLIENT, gradle)
runWithRelauncher.configure {
    setup(project)
    group = "lwjgl3ify"
    description = "Runs a production-like client smoke while triggering the Java relauncher"
    dependsOn(
        sourceSets.mcLauncher.map { it.classesTaskName },
        tasks.downloadVanillaAssets,
        tasks.packagePatchedMc,
        verifyProductionModArtifact,
        dlOriginalLaunchwrapper,
        "jar"
    )

    username = minecraft.username
    userUUID = minecraft.userUUID
    lwjglVersion = 2
    workingDir(developmentGameDirectory.get().asFile)

    systemProperty("gradlestart.bouncerClient", "net.minecraft.launchwrapper.Launch")
    // Keep this smoke production-like. The Java 8 parent must only contain lwjgl3ify and its
    // required bootstrap support; optional development mods such as NEI are deliberately absent.
    // The Java 21 child receives the local dirty lwjgl3ify JAR and required UniMixins support
    // explicitly, while Minecraft, Forge, LWJGL, and launcher libraries remain relauncher-owned.
    val smokeChildClasspath = files(productionModArtifact, relauncherSmokeChildSupport)
    val smokeChildClasspathString = providers.provider {
        smokeChildClasspath.files
            .map { it.absoluteFile.toPath().normalize().toString() }
            .sorted()
            .joinToString(File.pathSeparator)
    }
    systemProperty(
        "lwjgl3ify.relauncher.additionalClasspath",
        ProviderToStringWrapper(smokeChildClasspathString),
    )
    systemProperty(
        "lwjgl3ify.relauncher.additionalTweakers",
        "org.spongepowered.asm.launch.MixinTweaker",
    )

    systemProperty("lwjgl3ify.relauncher.supervisedLaunch", "true")
    systemProperty(
        "lwjgl3ify.relauncher.expectedProductionArtifact",
        ProviderToStringWrapper(productionArtifactPath),
    )
    systemProperty(
        "lwjgl3ify.relauncher.expectedProductionSha256",
        ProviderToStringWrapper(productionArtifactSha256),
    )

    classpath(tasks.packageMcLauncher)
    classpath(tasks.packagePatchedMc)
    classpath(originalLaunchWrapperPath)
    classpath(configurations.patchedMinecraft)
    classpath(productionModArtifact)
    classpath(relauncherSmokeRuntime)
    mainClass = "GradleStart"
    if (javaRuntimeBundlePath.isPresent) {
        dependsOn(stageBundledJavaForRelauncher)
        val smokeCacheRoot = providers.gradleProperty("wdgRelauncherRuntimeCacheRoot")
            .orElse(layout.buildDirectory.dir("relauncher-runtime-cache").map { it.asFile.absolutePath })
        systemProperty(
            "lwjgl3ify.relauncher.runtimeCacheRoot",
            ProviderToStringWrapper(smokeCacheRoot),
        )
        systemProperty(
            "lwjgl3ify.relauncher.forceSettings",
            ProviderToStringWrapper(providers.gradleProperty("wdgRelauncherForceSettings").orElse("false")),
        )
        systemProperty(
            "lwjgl3ify.relauncher.disableBundledJava",
            ProviderToStringWrapper(providers.gradleProperty("wdgRelauncherDisableBundledJava").orElse("false")),
        )
    }
}

val childExitJavaLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(17)
}
val verifyRelauncherChildExitPropagation =
    tasks.register<VerifyRelauncherChildExitPropagationTask>("verifyRelauncherChildExitPropagation") {
        dependsOn(tasks.testClasses)
        runtimeClasspath.from(sourceSets.test.get().runtimeClasspath)
        javaExecutable.set(childExitJavaLauncher.map { it.executablePath })
        mainClassName.set("me.eigenraven.lwjgl3ify.relauncher.RelauncherChildExitSmokeMain")
        expectedExitCode.set(37)
    }

// This task is intentionally expected to fail with exit code 37. The user-facing wrapper treats that
// nonzero Gradle result as a passing regression check.
tasks.register<JavaExec>("runRelauncherChildExitFailure") {
    group = "verification"
    description = "Deliberately fails after proving the raw JavaExec path preserves child exit code 37"
    dependsOn(tasks.testClasses)
    javaLauncher.set(childExitJavaLauncher)
    classpath(sourceSets.test.get().runtimeClasspath)
    mainClass.set("me.eigenraven.lwjgl3ify.relauncher.RelauncherChildExitSmokeMain")
    args("37")
}

tasks.runObfClient {
    mainClass.set("com.gtnewhorizons.retrofuturabootstrap.MainStartOnFirstThread")
}

tasks.runObfServer {
    tweakClasses.set(listOf())
}

// Regular runClient/runServer tasks use the explicit Azul Java 21 launcher above.
tasks.runClient17 { enabled = false }
tasks.runClient21 { enabled = false }
tasks.runClient25 { enabled = false }
tasks.runServer17 { enabled = false }
tasks.runServer21 { enabled = false }
tasks.runServer25 { enabled = false }

tasks.jar {
    manifest.attributes.put("TweakClass", "me.eigenraven.lwjgl3ify.relauncher.Lwjgl3ifyRelauncherTweaker")
}

tasks.shadowJar {
    manifest.attributes.put("TweakClass", "me.eigenraven.lwjgl3ify.relauncher.Lwjgl3ifyRelauncherTweaker")
}

apply(from = "repositories.gradle")
apply(from = "dependencies.gradle")

pluginManager.withPlugin("com.modrinth.minotaur") {
    val modrinth = project.extensions.getByType<ModrinthExtension>()
    modrinth.additionalFiles.add(forgePatchesJar)
    tasks.named("modrinth") {
        dependsOn(forgePatchesJar)
    }
}

tasks.publishCurseforge {
    dependsOn(forgePatchesJar)
    val mainArtifact = this.uploadArtifacts[0]
    mainArtifact.withAdditionalFile(forgePatchesJar.get().archiveFile.get().asFile)
    mainArtifact.additionalArtifacts.forEach { additionalArtifact ->
        additionalArtifact.changelogType = mainArtifact.changelogType
        additionalArtifact.changelog = mainArtifact.changelog
    }
}
