package me.eigenraven.lwjgl3ify.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/** Verifies the canonical root-overlay distribution and both embedded payloads. */
@DisableCachingByDefault(because = "Performs deep nested archive verification")
abstract class VerifyBundledJavaClientPackageTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val packageFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val normalizedBundle: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val expectedModJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifestFile: RegularFileProperty

    init {
        group = "verification"
        description = "Verifies the bundled-Java client overlay, complete mod JAR, and normalized runtime bundle"
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verifyPackage() {
        val packagePath = packageFile.get().asFile.toPath()
        val expectedBundle = normalizedBundle.get().asFile.toPath()
        val manifest = JavaRuntimeManifestIO.loadAndValidate(manifestFile.get().asFile)
        if (!Files.isRegularFile(packagePath)) throw GradleException("Client package is missing: $packagePath")

        val extractedMod = temporaryDir.resolve("client-mod.jar")
        val extractedBundle = temporaryDir.resolve("normalized-runtimes.zip")
        ZipFile(packagePath.toFile()).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val names = entries.map { it.name }
            val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (duplicates.isNotEmpty()) throw GradleException("Client package contains duplicate paths: $duplicates")
            val caseCollisions = names.groupBy { it.lowercase(Locale.ROOT) }.filterValues { it.distinct().size > 1 }
            if (caseCollisions.isNotEmpty()) {
                throw GradleException(
                    "Client package contains case-folding collisions: ${caseCollisions.values}",
                )
            }
            val topLevels = names.map { it.substringBefore('/') }.filter { it.isNotEmpty() }.toSet()
            if (topLevels.size != 1) {
                throw GradleException("Client package must contain one top-level directory: $topLevels")
            }
            val top = topLevels.single()
            val modEntries = entries.filter {
                !it.isDirectory && it.name.startsWith("$top/mods/") && it.name.endsWith(".jar")
            }
            val bundleEntries = entries.filter {
                !it.isDirectory &&
                    it.name == "$top/lwjgl3ify/runtime/lwjgl3ify-wdg-java21-runtimes.zip"
            }
            if (modEntries.size != 1) {
                throw GradleException(
                    "Client package must contain exactly one mod JAR; found ${modEntries.map { it.name }}",
                )
            }
            if (modEntries.single().name.endsWith("-dev.jar") ||
                modEntries.single().name.endsWith("-dev-preshadow.jar")
            ) {
                throw GradleException("Client package contains a development JAR instead of the reobfuscated mod JAR")
            }
            if (bundleEntries.size != 1) {
                throw GradleException("Client package must contain exactly one normalized runtime bundle")
            }
            if (names.any { it.startsWith("$top/mods/") && it.endsWith("java21-runtimes.zip") }) {
                throw GradleException("Runtime bundle must not be placed in mods/")
            }
            val forbidden = names.filter {
                it.contains("/.git/") || it.contains("/.gradle/") || it.contains("/build/") ||
                    it.contains("__MACOSX") || it.endsWith(".DS_Store") || it.endsWith(".java") ||
                    it.contains("Required Java Packages")
            }
            if (forbidden.isNotEmpty()) throw GradleException("Client package contains forbidden members: $forbidden")
            zip.getInputStream(modEntries.single()).use { input ->
                Files.copy(
                    input,
                    extractedMod.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
            zip.getInputStream(bundleEntries.single()).use { input ->
                Files.copy(
                    input,
                    extractedBundle.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }

        val expectedMod = expectedModJar.get().asFile.toPath()
        if (sha256(extractedMod.toPath()) != sha256(expectedMod)) {
            throw GradleException(
                "Packaged client mod JAR differs from the verified production artifact: " +
                    "packaged=${sha256(extractedMod.toPath())}, expected=${sha256(expectedMod)}",
            )
        }
        if (sha256(extractedBundle.toPath()) != sha256(expectedBundle)) {
            throw GradleException("Embedded normalized runtime bundle differs from packageJavaRuntimeBundle output")
        }
        JavaRuntimeBundlePackager.verifyPackagedBundle(
            manifest,
            manifestFile.get().asFile,
            extractedBundle,
        )
        verifyModJar(extractedMod)
        logger.lifecycle(
            "Verified bundled-Java client package: $packagePath (${Files.size(packagePath)} bytes, " +
                "modSha256=${sha256(extractedMod.toPath())}, runtimeSha256=${sha256(extractedBundle.toPath())})",
        )
    }

    private fun verifyModJar(mod: java.io.File) {
        val required = setOf(
            "me/eigenraven/lwjgl3ify/relauncher/Lwjgl3ifyRelauncherTweaker.class",
            "me/eigenraven/lwjgl3ify/relauncher/Relauncher.class",
            "me/eigenraven/lwjgl3ify/relauncher/runtime/AutomaticRuntimeCoordinator.class",
            "me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeHostDetector.class",
            "me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeBundleLocator.class",
            "me/eigenraven/lwjgl3ify/relauncher/runtime/JavaLaunchSelection.class",
            "me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeInstaller.class",
            "me/eigenraven/lwjgl3ify/relauncher/runtime/java21-runtime-manifest.json",
            "me/eigenraven/lwjgl3ify/relauncher/forgePatches.zip",
            "me/eigenraven/lwjgl3ify/relauncher/version.json",
            "META-INF/rfb-plugin/lwjgl3ify.properties",
            "mixins.lwjgl3ify.json",
            "META-INF/MANIFEST.MF",
            "META-INF/licenses/Apache-2.0.txt",
            "META-INF/licenses/commons-compress-NOTICE.txt",
            "META-INF/licenses/commons-io-NOTICE.txt",
            "META-INF/licenses/commons-codec-NOTICE.txt",
        )
        ZipFile(mod).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            val missing = required.filter { zip.getEntry(it) == null }
            if (missing.isNotEmpty()) {
                throw GradleException("Complete client mod JAR is missing: ${missing.joinToString()}")
            }
            if (names.none { it.startsWith("me/eigenraven/lwjgl3ify/internal/commonscompress/") }) {
                throw GradleException("Complete client mod JAR lacks relocated Commons Compress")
            }
            if (names.none { it.startsWith("me/eigenraven/lwjgl3ify/internal/commonsio/") }) {
                throw GradleException("Complete client mod JAR lacks relocated Commons IO")
            }
            if (names.none { it.startsWith("me/eigenraven/lwjgl3ify/internal/commonscodec/") }) {
                throw GradleException("Complete client mod JAR lacks relocated Commons Codec")
            }
            if (names.any {
                    it.startsWith("runtimes/") || it.endsWith("java21-runtimes.zip") ||
                        it.contains("OpenJDK21U-jre")
                }
            ) {
                throw GradleException("Complete client mod JAR embeds a Java runtime payload")
            }
            val forbiddenModMembers = names.filter {
                (it.startsWith("me/eigenraven/lwjgl3ify/") &&
                    (it.endsWith("Test.class") || it.endsWith("SmokeMain.class"))) ||
                    it.contains("/test-fixtures/") || it.contains("/fixtures/") ||
                    it.endsWith("lwjgl3ify-relauncher.json") || it.contains("runtime-installation-smoke")
            }
            if (forbiddenModMembers.isNotEmpty()) {
                throw GradleException(
                    "Complete client mod JAR contains tests, fixtures, config, or local cache data: " +
                        forbiddenModMembers.joinToString(),
                )
            }
            listOf(
                "me/eigenraven/lwjgl3ify/relauncher/Relauncher.class",
                "me/eigenraven/lwjgl3ify/relauncher/runtime/AutomaticRuntimeCoordinator.class",
                "me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeInstaller.class",
            ).forEach { className ->
                val entry = zip.getEntry(className) ?: throw GradleException("Missing early-runtime class: $className")
                zip.getInputStream(entry).use { input ->
                    val header = ByteArray(8)
                    if (input.read(header) != header.size) throw GradleException("Truncated class file: $className")
                    val major = ((header[6].toInt() and 0xff) shl 8) or (header[7].toInt() and 0xff)
                    if (major > 52) {
                        throw GradleException(
                            "Early-runtime class is not Java 8 compatible: $className major=$major",
                        )
                    }
                }
            }
        }
    }

    private fun sha256(path: java.nio.file.Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
