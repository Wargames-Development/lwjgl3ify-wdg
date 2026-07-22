package me.eigenraven.lwjgl3ify.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Creates a reproducible client-instance root overlay with one complete mod JAR and one normalized bundle. */
abstract class PackageBundledJavaClientTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val modJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val normalizedBundle: RegularFileProperty

    @get:Input
    abstract val topLevelDirectory: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    init {
        group = "lwjgl3ify"
        description = "Packages the complete lwjgl3ify client mod with the separate normalized Java 21 bundle"
        topLevelDirectory.convention("lwjgl3ify-wdg-client")
    }

    @TaskAction
    fun packageClient() {
        val mod = modJar.get().asFile
        val bundle = normalizedBundle.get().asFile
        validateModJar(mod)
        if (!bundle.isFile) throw GradleException("Normalized runtime bundle is missing: $bundle")
        val top = topLevelDirectory.get().trim('/').also {
            if (it.isEmpty() || it.contains("..") || it.contains('\\')) {
                throw GradleException("Unsafe top-level client directory: $it")
            }
        }
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        val readme = """
            lwjgl3ify WDG client overlay

            Copy the contents of this directory into the client instance root.
            The mod JAR belongs in mods/. The Java 21 runtime bundle deliberately remains under
            lwjgl3ify/runtime/ so Forge never scans it as a mod.
        """.trimIndent().plus("\n").toByteArray(StandardCharsets.UTF_8)

        ZipOutputStream(BufferedOutputStream(Files.newOutputStream(output.toPath()))).use { zip ->
            addDirectory(zip, "$top/")
            addDirectory(zip, "$top/mods/")
            addStoredFile(zip, "$top/mods/${mod.name}", mod.toPath())
            addDirectory(zip, "$top/lwjgl3ify/")
            addDirectory(zip, "$top/lwjgl3ify/runtime/")
            addStoredFile(zip, "$top/lwjgl3ify/runtime/lwjgl3ify-wdg-java21-runtimes.zip", bundle.toPath())
            addStoredBytes(zip, "$top/INSTALL.txt", readme)
        }

        val members = ZipFile(output).use { it.entries().asSequence().count() }
        logger.lifecycle(
            "Packaged bundled-Java client: ${output.absolutePath} (${output.length()} bytes, members=$members, " +
                "mod=${mod.name}, runtimeSha256=${sha256(bundle.toPath())})",
        )
    }

    private fun validateModJar(mod: java.io.File) {
        if (!mod.isFile) throw GradleException("Complete client mod JAR is missing: $mod")
        if (mod.name.endsWith("-dev.jar") || mod.name.endsWith("-dev-preshadow.jar")) {
            throw GradleException("Client package must use the reobfuscated primary mod JAR, not ${mod.name}")
        }
        val required = setOf(
            "me/eigenraven/lwjgl3ify/relauncher/Lwjgl3ifyRelauncherTweaker.class",
            "me/eigenraven/lwjgl3ify/relauncher/Relauncher.class",
            "me/eigenraven/lwjgl3ify/relauncher/runtime/AutomaticRuntimeCoordinator.class",
            "me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeInstaller.class",
            "me/eigenraven/lwjgl3ify/relauncher/runtime/java21-runtime-manifest.json",
            "me/eigenraven/lwjgl3ify/relauncher/forgePatches.zip",
            "me/eigenraven/lwjgl3ify/relauncher/version.json",
        )
        ZipFile(mod).use { zip ->
            val missing = required.filter { zip.getEntry(it) == null }
            if (missing.isNotEmpty()) {
                throw GradleException("Client mod JAR is incomplete; missing: ${missing.joinToString()}")
            }
            val names = zip.entries().asSequence().map { it.name }.toList()
            if (names.none { it.startsWith("me/eigenraven/lwjgl3ify/internal/commonscompress/") }) {
                throw GradleException("Client mod JAR lacks relocated Commons Compress support")
            }
            if (names.any { it.endsWith("java21-runtimes.zip") || it.startsWith("runtimes/") }) {
                throw GradleException("Client mod JAR must not embed the normalized runtime bundle")
            }
        }
    }

    private fun addDirectory(zip: ZipOutputStream, name: String) {
        val entry = ZipEntry(name)
        entry.setTimeLocal(REPRODUCIBLE_TIME)
        entry.method = ZipEntry.STORED
        entry.size = 0
        entry.compressedSize = 0
        entry.crc = 0
        zip.putNextEntry(entry)
        zip.closeEntry()
    }

    private fun addStoredFile(zip: ZipOutputStream, name: String, path: java.nio.file.Path) {
        val crc = CRC32()
        BufferedInputStream(Files.newInputStream(path)).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
        val entry = ZipEntry(name)
        entry.setTimeLocal(REPRODUCIBLE_TIME)
        entry.method = ZipEntry.STORED
        entry.size = Files.size(path)
        entry.compressedSize = entry.size
        entry.crc = crc.value
        zip.putNextEntry(entry)
        BufferedInputStream(Files.newInputStream(path)).use { input -> input.copyTo(zip, 1024 * 1024) }
        zip.closeEntry()
    }

    private fun addStoredBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }
        val entry = ZipEntry(name)
        entry.setTimeLocal(REPRODUCIBLE_TIME)
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        entry.crc = crc.value
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
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

    companion object {
        private val REPRODUCIBLE_TIME = LocalDateTime.of(1980, 1, 1, 0, 0)
    }
}
