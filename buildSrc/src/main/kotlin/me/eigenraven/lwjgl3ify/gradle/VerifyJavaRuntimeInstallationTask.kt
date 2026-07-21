package me.eigenraven.lwjgl3ify.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/** Runs the explicit real-runtime installation and reuse smoke without configuration-cache-unsafe project access. */
@DisableCachingByDefault(because = "Runs an explicit external-runtime installation and reuse smoke")
abstract class VerifyJavaRuntimeInstallationTask @Inject constructor(
    private val execOperations: ExecOperations,
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {

    @get:Classpath
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val normalizedBundle: RegularFileProperty

    @get:Input
    abstract val platformId: Property<String>

    @get:OutputDirectory
    abstract val cacheRoot: DirectoryProperty

    @get:Input
    abstract val mainClassName: Property<String>

    init {
        platformId.convention("")
        group = "lwjgl3ify"
        description = "Installs one explicitly selected real packaged Java runtime twice and verifies cache reuse"
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verifyInstallation() {
        val selectedPlatform = platformId.orNull?.trim()
        if (selectedPlatform.isNullOrEmpty()) {
            throw GradleException(
                "Specify the explicit runtime platform with -PwdgJavaRuntimePlatform=<platform-id>",
            )
        }

        fileSystemOperations.delete {
            delete(cacheRoot.get().asFile)
        }

        execOperations.javaexec {
            classpath(runtimeClasspath)
            mainClass.set(mainClassName)
            args(
                normalizedBundle.get().asFile.absolutePath,
                selectedPlatform,
                cacheRoot.get().asFile.absolutePath,
            )
        }
    }
}
