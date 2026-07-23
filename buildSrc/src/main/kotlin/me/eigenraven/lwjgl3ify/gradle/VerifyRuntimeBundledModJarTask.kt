package me.eigenraven.lwjgl3ify.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class VerifyRuntimeBundledModJarTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeBundledJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseModJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @get:OutputFile
    abstract val verificationMetadata: RegularFileProperty

    init {
        group = "verification"
        description = "Verifies the one-JAR WDG artifact and its exact four embedded primary runtimes"
    }

    @TaskAction
    fun verify() {
        val report = RuntimeBundledArtifactSupport.verifyArtifact(
            runtimeBundledJar.get().asFile,
            baseModJar.get().asFile,
            manifestFile.get().asFile,
        )
        verificationMetadata.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("artifactName=${runtimeBundledJar.get().asFile.name}")
                    appendLine("artifactSize=${report.outputSize}")
                    appendLine("artifactSha256=${report.outputSha256}")
                    appendLine("embeddedPlatformCount=${report.runtimeHashes.size}")
                    report.runtimeHashes.forEach { (platform, hash) ->
                        appendLine("runtime.$platform.sha256=$hash")
                    }
                },
                Charsets.UTF_8,
            )
        }
        logger.lifecycle(
            "Runtime-bearing lwjgl3ify artifact verification passed: {} (sha256={})",
            runtimeBundledJar.get().asFile,
            report.outputSha256,
        )
    }
}
