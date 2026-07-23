package me.eigenraven.lwjgl3ify.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "The 180+ MB artifact is explicitly deterministic and verified after packaging")
abstract class PackageRuntimeBundledModJarTask : DefaultTask() {

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
    abstract val outputFile: RegularFileProperty

    init {
        group = "lwjgl3ify"
        description = "Packages the verified production mod and four primary Java runtimes into one deterministic JAR"
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun packageJar() {
        val report = RuntimeBundledArtifactSupport.packageArtifact(
            baseModJar.get().asFile,
            normalizedBundle.get().asFile,
            manifestFile.get().asFile,
            outputFile.get().asFile,
        )
        logger.lifecycle(
            "Created runtime-bearing lwjgl3ify JAR: {} ({} bytes, sha256={})",
            outputFile.get().asFile,
            report.outputSize,
            report.outputSha256,
        )
        report.runtimeHashes.forEach { (platform, hash) ->
            logger.lifecycle("Embedded primary runtime {} SHA-256: {}", platform, hash)
        }
    }
}
