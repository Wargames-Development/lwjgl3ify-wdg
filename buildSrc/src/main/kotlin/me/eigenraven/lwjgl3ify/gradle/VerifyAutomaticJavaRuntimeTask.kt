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
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/** Executes the production coordinator twice and runs the selected packaged Java diagnostic command. */
@DisableCachingByDefault(because = "Executes an external packaged Java runtime")
abstract class VerifyAutomaticJavaRuntimeTask @Inject constructor(
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

    @get:LocalState
    abstract val cacheRoot: DirectoryProperty

    @get:LocalState
    abstract val gameDirectory: DirectoryProperty

    @get:Input
    abstract val mainClassName: Property<String>

    init {
        group = "verification"
        description = "Verifies automatic packaged-Java detection, install, execution, and second-launch reuse"
        platformId.convention("")
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verifyRuntime() {
        val selectedPlatform = platformId.orNull?.trim()
        if (selectedPlatform.isNullOrEmpty()) {
            throw GradleException("Specify -PwdgJavaRuntimePlatform=<platform-id>")
        }
        fileSystemOperations.delete {
            delete(cacheRoot.get().asFile)
            delete(gameDirectory.get().asFile)
        }
        val staged = gameDirectory.file("lwjgl3ify/runtime/lwjgl3ify-wdg-java21-runtimes.zip").get().asFile
        staged.parentFile.mkdirs()
        normalizedBundle.get().asFile.copyTo(staged, overwrite = true)

        execOperations.javaexec {
            classpath(runtimeClasspath)
            mainClass.set(mainClassName)
            args(
                gameDirectory.get().asFile.absolutePath,
                cacheRoot.get().asFile.absolutePath,
                selectedPlatform,
            )
        }
    }
}
