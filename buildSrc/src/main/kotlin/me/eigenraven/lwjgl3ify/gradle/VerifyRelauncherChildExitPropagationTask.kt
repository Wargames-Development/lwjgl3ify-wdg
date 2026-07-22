package me.eigenraven.lwjgl3ify.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/** Proves that the relaunch supervision seam preserves a deterministic nonzero child result. */
@DisableCachingByDefault(because = "Executes a fixture JVM and validates its exit code")
abstract class VerifyRelauncherChildExitPropagationTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:Classpath
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val javaExecutable: RegularFileProperty

    @get:Input
    abstract val mainClassName: Property<String>

    @get:Input
    abstract val expectedExitCode: Property<Int>

    init {
        group = "verification"
        description = "Verifies that a deterministic child failure is preserved through the relaunch runner"
        expectedExitCode.convention(37)
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verifyPropagation() {
        val expected = expectedExitCode.get()
        val result = execOperations.javaexec {
            executable(javaExecutable.get().asFile)
            classpath(runtimeClasspath)
            mainClass.set(mainClassName)
            args(expected.toString())
            isIgnoreExitValue = true
        }
        if (result.exitValue != expected) {
            throw GradleException(
                "Relaunch child exit propagation mismatch: expected $expected, actual ${result.exitValue}",
            )
        }
        logger.lifecycle("CHILD EXIT PROPAGATION PASSED: observed exit code $expected")
    }
}
