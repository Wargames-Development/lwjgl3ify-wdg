package me.eigenraven.lwjgl3ify.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

@DisableCachingByDefault(because = "Large extension archives are copied byte-for-byte and verified each run")
abstract class PackageRuntimeExtensionArchivesTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val normalizedBundle: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        group = "lwjgl3ify"
        description = "Produces verified Windows ARM64 and Linux ARM64 extension archives for manual installation"
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun packageExtensions() {
        val manifest = JavaRuntimeManifestIO.loadAndValidate(manifestFile.get().asFile)
        val extensions = manifest.platforms.filter { it.id in setOf("windows-aarch64", "linux-aarch64") }
        require(extensions.size == 2) { "The extension runtime matrix is incomplete" }
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()

        ZipFile(normalizedBundle.get().asFile).use { bundle ->
            val root = bundle.entries().asSequence()
                .map { it.name }
                .firstOrNull { it.endsWith("/manifest.json") }
                ?.substringBefore('/')
                ?: error("Normalized runtime bundle lacks manifest.json")
            extensions.forEach { platform ->
                val source = bundle.getEntry("$root/${platform.normalizedBundlePath}")
                    ?: error("Normalized bundle lacks ${platform.normalizedBundlePath}")
                val name = "lwjgl3ify-wdg-java21-${platform.id}.${platform.archiveType}"
                val destination = output.resolve(name)
                bundle.getInputStream(source).use { input ->
                    destination.outputStream().buffered().use { destinationStream ->
                        input.copyTo(destinationStream)
                    }
                }
                require(destination.length() == platform.sizeBytes) { "Extension size mismatch for ${platform.id}" }
                require(sha256(destination) == platform.sha256) { "Extension hash mismatch for ${platform.id}" }
            }
        }
        output.resolve("README.txt").writeText(
            """
            lwjgl3ify-WDG optional Java 21 architecture extensions

            Place exactly the archive for the target system in:
              <modpack root>/lwjgl3ify/runtime/extensions/

            Do not extract or rename it. The mod validates the exact size and SHA-256 from its canonical runtime manifest.
            These files extend support for Windows ARM64 and Linux ARM64; the ordinary release JAR embeds the four common desktop runtimes.
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
        output.resolve("SHA256SUMS").writeText(
            extensions.joinToString("\n", postfix = "\n") { platform ->
                "${platform.sha256}  lwjgl3ify-wdg-java21-${platform.id}.${platform.archiveType}"
            },
            Charsets.UTF_8,
        )
        logger.lifecycle("Created optional architecture extensions in {}", output)
    }

    private fun sha256(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }
}
