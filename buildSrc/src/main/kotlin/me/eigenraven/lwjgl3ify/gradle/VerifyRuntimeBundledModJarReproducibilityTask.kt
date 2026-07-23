package me.eigenraven.lwjgl3ify.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files

@CacheableTask
abstract class VerifyRuntimeBundledModJarReproducibilityTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseModJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val normalizedBundle: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @get:OutputFile
    abstract val verificationMetadata: RegularFileProperty

    init {
        group = "verification"
        description = "Builds the runtime-bearing release JAR twice and requires byte-identical output"
    }

    @TaskAction
    fun verifyReproducibility() {
        val temporaryRoot = temporaryDir.resolve("runtime-bundled-reproducibility")
        temporaryRoot.deleteRecursively()
        temporaryRoot.mkdirs()
        val first = temporaryRoot.resolve("first.jar")
        val second = temporaryRoot.resolve("second.jar")
        val firstReport = RuntimeBundledArtifactSupport.packageArtifact(
            baseModJar.get().asFile,
            normalizedBundle.get().asFile,
            manifestFile.get().asFile,
            first,
        )
        val secondReport = RuntimeBundledArtifactSupport.packageArtifact(
            baseModJar.get().asFile,
            normalizedBundle.get().asFile,
            manifestFile.get().asFile,
            second,
        )
        val mismatch = Files.mismatch(first.toPath(), second.toPath())
        require(mismatch == -1L) { "Runtime-bearing release JARs differ at byte offset $mismatch" }
        require(firstReport.outputSha256 == secondReport.outputSha256) {
            "Runtime-bearing release JAR SHA-256 values differ"
        }
        verificationMetadata.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("reproducible=true")
                    appendLine("artifactSize=${firstReport.outputSize}")
                    appendLine("artifactSha256=${firstReport.outputSha256}")
                    appendLine("embeddedPlatformCount=${firstReport.runtimeHashes.size}")
                },
                Charsets.UTF_8,
            )
        }
        logger.lifecycle(
            "Runtime-bearing lwjgl3ify JAR reproducibility passed: sha256={}",
            firstReport.outputSha256,
        )
    }
}
