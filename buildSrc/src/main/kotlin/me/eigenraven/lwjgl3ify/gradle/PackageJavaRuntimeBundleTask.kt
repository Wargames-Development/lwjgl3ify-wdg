package me.eigenraven.lwjgl3ify.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "The generated 250+ MB bundle is explicitly reproducible and locally verified")
abstract class PackageJavaRuntimeBundleTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bundleFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    init {
        group = "lwjgl3ify"
        description = "Packages the validated Java 21 runtimes into a deterministic normalized ZIP"
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun packageBundle() {
        val externalBundle = if (bundleFile.isPresent) {
            bundleFile.get().asFile
        } else {
            throw GradleException(
                "Specify the external bundle with " +
                    "-PwdgJavaRuntimeBundle=/absolute/path/to/Required Java Packages.zip " +
                    "or WDG_JAVA_RUNTIME_BUNDLE.",
            )
        }

        try {
            val canonicalManifest = manifestFile.get().asFile
            val manifest = JavaRuntimeManifestIO.loadAndValidate(canonicalManifest)
            val packaged = JavaRuntimeBundlePackager.packageBundle(
                manifest,
                canonicalManifest,
                externalBundle,
                outputFile.get().asFile,
            )
            logger.lifecycle("Created and verified normalized Java runtime bundle: {}", packaged.outputFile)
            logger.lifecycle("Archive members: {}", packaged.memberCount)
            logger.lifecycle("Archive bytes: {}", packaged.outputBytes)
            packaged.runtimeHashes.forEach { (platformId, sha256) ->
                logger.lifecycle("Packaged {} SHA-256: {}", platformId, sha256)
            }
        } catch (exception: Exception) {
            val message = if (exception is RuntimeContractException) {
                exception.message ?: "Java runtime bundle packaging failed"
            } else {
                "Could not package the Java runtime bundle: ${exception.message}"
            }
            throw GradleException(message, exception)
        }
    }
}
