package me.eigenraven.lwjgl3ify.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Stages only the normalized bundle in the canonical development game-directory location. */
abstract class StageBundledJavaForRelauncherTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val normalizedBundle: RegularFileProperty

    @get:OutputFile
    abstract val stagedBundle: RegularFileProperty

    init {
        group = "lwjgl3ify"
        description = "Stages the normalized Java 21 bundle for the legacy relauncher development smoke"
    }

    @TaskAction
    fun stage() {
        val source = normalizedBundle.get().asFile.toPath()
        val target = stagedBundle.get().asFile.toPath()
        Files.createDirectories(target.parent)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        val sourceHash = sha256(source)
        val targetHash = sha256(target)
        if (sourceHash != targetHash) {
            throw GradleException("Staged runtime bundle hash mismatch: $sourceHash != $targetHash")
        }
        logger.lifecycle("Staged packaged Java bundle: $target (${Files.size(target)} bytes, sha256=$targetHash)")
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
