package me.eigenraven.lwjgl3ify.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Semantically verifies the one reobfuscated mod artifact used by every production-like consumer. */
@CacheableTask
abstract class VerifyProductionModArtifactTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val productionModJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mcpToSrgMapping: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val notchToSrgMapping: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val productionMinecraftJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val generatedRefmap: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val canonicalRuntimeManifest: RegularFileProperty

    @get:OutputFile
    abstract val verificationMetadata: RegularFileProperty

    init {
        group = "verification"
        description = "Verifies the complete reobfuscated lwjgl3ify artifact and production Mixin refmap"
    }

    @TaskAction
    fun verifyArtifact() {
        val artifact = productionModJar.get().asFile
        val report = ProductionArtifactVerifier.verify(
            artifact,
            mcpToSrgMapping.get().asFile,
            notchToSrgMapping.get().asFile,
            productionMinecraftJar.get().asFile,
            generatedRefmap.get().asFile,
            canonicalRuntimeManifest.get().asFile,
        )
        val metadata = verificationMetadata.get().asFile
        metadata.parentFile.mkdirs()
        metadata.writeText(
            buildString {
                appendLine("artifactName=${artifact.name}")
                appendLine("artifactSha256=${report.artifactSha256}")
                appendLine("artifactSize=${report.artifactSize}")
                appendLine("refmapSha256=${report.refmapSha256}")
                appendLine("mappingEnvironment=searge")
                appendLine("mixinMethod=${report.resolvedMethod}")
                appendLine("mixinField=${report.resolvedField}")
                appendLine("notchOwner=${report.notchOwner}")
                appendLine("notchMethod=${report.notchMethod}")
                appendLine("notchField=${report.notchField}")
            },
            Charsets.UTF_8,
        )
        logger.lifecycle(
            "Verified production mod artifact: ${artifact.absolutePath} " +
                "(${report.artifactSize} bytes, sha256=${report.artifactSha256}, " +
                "refmapSha256=${report.refmapSha256}, method=${report.resolvedMethod}, " +
                "field=${report.resolvedField}, productionOwner=${report.notchOwner})",
        )
    }
}
