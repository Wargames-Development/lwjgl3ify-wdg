package me.eigenraven.lwjgl3ify.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Generates the vanilla launcher metadata without capturing Gradle script objects. */
@CacheableTask
abstract class VersionJsonTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val templateFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val forgePatchesArchive: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val lwjglDownloadsFile: RegularFileProperty

    @get:Input
    abstract val versionString: Property<String>

    @get:Input
    abstract val jvmArgumentsJson: Property<String>

    @get:Input
    abstract val lwjglVersionString: Property<String>

    @get:Input
    abstract val commitTime: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    init {
        group = "lwjgl3ify"
        description = "Generates the vanilla launcher version.json file"
    }

    @TaskAction
    fun generateVersionJson() {
        val patchesJar = forgePatchesArchive.get().asFile
        val patchesHash = sha1(patchesJar.readBytes())
        val patchesSize = patchesJar.length()
        val lwjglDownloads = lwjglDownloadsFile.get().asFile.readText(StandardCharsets.UTF_8)

        val tokens = mapOf(
            "version" to versionString.get(),
            "patchesJarSize" to patchesSize.toString(),
            "patchesJarHash" to patchesHash,
            "jvmArgs" to jvmArgumentsJson.get(),
            "lwjglVersion" to lwjglVersionString.get(),
            "lwjglDownloads" to lwjglDownloads,
            "time" to commitTime.get(),
        )

        var rendered = templateFile.get().asFile.readText(StandardCharsets.UTF_8)
        for ((name, value) in tokens) {
            rendered = rendered.replace("@$name@", value)
        }

        val destination = outputFile.get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(rendered, StandardCharsets.UTF_8)
    }

    private fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
