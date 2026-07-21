package me.eigenraven.lwjgl3ify.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "External runtime verification is an explicit, streaming integrity check")
abstract class VerifyJavaRuntimeBundleTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bundleFile: RegularFileProperty

    init {
        group = "verification"
        description = "Validates the external packaged Java 21 runtime bundle without executing its binaries"
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verifyBundle() {
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
            val manifest = JavaRuntimeManifestIO.loadAndValidate(manifestFile.get().asFile)
            val verified = JavaRuntimeBundleVerifier.verify(manifest, externalBundle)
            verified.runtimes.forEach { runtime ->
                logger.lifecycle(
                    "Verified {}: {} bytes, SHA-256 {}, {} nested entries",
                    runtime.platform.id,
                    runtime.sizeBytes,
                    runtime.sha256,
                    runtime.archiveEntryCount,
                )
            }
            logger.lifecycle(
                "Java runtime bundle verification passed for {} supported platforms.",
                verified.runtimes.size,
            )
        } catch (exception: Exception) {
            val message = if (exception is RuntimeContractException) {
                exception.message ?: "Java runtime bundle verification failed"
            } else {
                "Could not verify the Java runtime bundle: ${exception.message}"
            }
            throw GradleException(message, exception)
        }
    }
}
