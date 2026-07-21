package me.eigenraven.lwjgl3ify.gradle

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

@Serializable
data class JavaRuntimeManifest(
    val schemaVersion: Int,
    val runtimeFamily: String,
    val vendor: String,
    val implementor: String,
    val distribution: String,
    val implementorVersion: String,
    val javaFeatureVersion: Int,
    val javaVersion: String,
    val javaRuntimeVersion: String,
    val jvmVariant: String,
    val archiveRoot: String,
    val supportedPlatformCount: Int,
    val platforms: List<JavaRuntimePlatform>,
)

@Serializable
data class JavaRuntimePlatform(
    val id: String,
    val operatingSystem: String,
    val architecture: String,
    val operatingSystemAliases: List<String>,
    val architectureAliases: List<String>,
    val inputPath: String,
    val inputFilename: String,
    val normalizedBundlePath: String,
    val archiveType: String,
    val sizeBytes: Long,
    val sha256: String,
    val archiveRoot: String,
    val javaHomeRelativePath: String,
    val javaExecutableRelativePath: String,
    val windowsGuiExecutableRelativePath: String? = null,
    val releaseFileRelativePath: String,
    val libc: String? = null,
    val expectedReleaseProperties: Map<String, String>,
) {
    fun archiveEntry(relativeToJavaHome: String): String {
        val javaHome = if (javaHomeRelativePath == ".") archiveRoot else "$archiveRoot/$javaHomeRelativePath"
        return "$javaHome/$relativeToJavaHome"
    }

    val javaExecutableEntry: String
        get() = archiveEntry(javaExecutableRelativePath)

    val windowsGuiExecutableEntry: String?
        get() = windowsGuiExecutableRelativePath?.let(::archiveEntry)

    val releaseFileEntry: String
        get() = archiveEntry(releaseFileRelativePath)
}

class RuntimeContractException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object RuntimePlatformNames {
    fun canonicalOperatingSystem(value: String): String? = when (value.trim().lowercase()) {
        "linux" -> "linux"
        "mac os x", "macos", "darwin" -> "macos"
        else -> if (value.trim().lowercase().startsWith("windows")) "windows" else null
    }

    fun canonicalArchitecture(value: String): String? = when (value.trim().lowercase()) {
        "amd64", "x86_64", "x64", "x86-64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> null
    }
}

object RuntimePathSafety {
    private val drivePath = Regex("^[A-Za-z]:")
    private val uriScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    fun requireSafeRelative(path: String, description: String, allowDot: Boolean = false) {
        if (path.isBlank()) {
            throw RuntimeContractException("$description must not be blank")
        }
        if ('\u0000' in path) {
            throw RuntimeContractException("$description contains a NUL character: $path")
        }
        if ('\\' in path) {
            throw RuntimeContractException("$description must use forward slashes only: $path")
        }
        if (path.startsWith('/')) {
            throw RuntimeContractException("$description must be relative, not absolute: $path")
        }
        if (drivePath.containsMatchIn(path)) {
            throw RuntimeContractException("$description must not use a Windows drive path: $path")
        }
        if (uriScheme.containsMatchIn(path)) {
            throw RuntimeContractException("$description must not use a URI scheme: $path")
        }
        val segments = path.split('/')
        if (segments.any { it == ".." }) {
            throw RuntimeContractException("$description must not contain '..': $path")
        }
        if (segments.any { it.isEmpty() }) {
            throw RuntimeContractException("$description must not contain empty path segments: $path")
        }
        if (!allowDot && segments.any { it == "." }) {
            throw RuntimeContractException("$description must not contain '.' path segments: $path")
        }
        if (allowDot && path != "." && segments.any { it == "." }) {
            throw RuntimeContractException("$description may use '.' only as the complete path: $path")
        }
    }

    fun normalizedArchiveEntry(path: String, description: String): String {
        val normalized = path.trimEnd('/')
        requireSafeRelative(normalized, description)
        return normalized
    }

    fun resolveLink(linkEntry: String, linkTarget: String, hardLink: Boolean): String {
        if (linkTarget.isBlank() || '\u0000' in linkTarget || '\\' in linkTarget) {
            throw RuntimeContractException("Unsafe archive link target: $linkEntry -> $linkTarget")
        }
        if (linkTarget.startsWith('/') || drivePath.containsMatchIn(linkTarget) || uriScheme.containsMatchIn(linkTarget)) {
            throw RuntimeContractException("Absolute archive link target: $linkEntry -> $linkTarget")
        }

        val resolved = ArrayDeque<String>()
        if (!hardLink) {
            linkEntry.substringBeforeLast('/', "")
                .split('/')
                .filter { it.isNotEmpty() }
                .forEach(resolved::addLast)
        }
        for (segment in linkTarget.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    if (resolved.isEmpty()) {
                        throw RuntimeContractException("Archive link escapes its root: $linkEntry -> $linkTarget")
                    }
                    resolved.removeLast()
                }
                else -> resolved.addLast(segment)
            }
        }
        if (resolved.isEmpty()) {
            throw RuntimeContractException("Archive link resolves outside or to an empty root: $linkEntry -> $linkTarget")
        }
        return resolved.joinToString("/")
    }
}

object JavaRuntimeManifestIO {
    const val supportedSchemaVersion = 1

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    private val expectedMatrix = setOf(
        "linux-aarch64",
        "linux-x86_64",
        "macos-aarch64",
        "macos-x86_64",
        "windows-aarch64",
        "windows-x86_64",
    )

    fun loadAndValidate(file: File): JavaRuntimeManifest {
        if (!file.isFile) {
            throw RuntimeContractException("Runtime manifest is missing: ${file.path}")
        }
        val manifest = try {
            json.decodeFromString<JavaRuntimeManifest>(file.readText(StandardCharsets.UTF_8))
        } catch (exception: SerializationException) {
            throw RuntimeContractException("Runtime manifest is not valid schema-compatible JSON: ${exception.message}", exception)
        } catch (exception: IllegalArgumentException) {
            throw RuntimeContractException("Runtime manifest could not be parsed: ${exception.message}", exception)
        }
        validate(manifest)
        return manifest
    }

    fun validate(manifest: JavaRuntimeManifest) {
        val failures = mutableListOf<String>()
        fun requireInvariant(condition: Boolean, message: String) {
            if (!condition) failures += message
        }
        fun safePath(path: String, description: String, allowDot: Boolean = false) {
            try {
                RuntimePathSafety.requireSafeRelative(path, description, allowDot)
            } catch (exception: RuntimeContractException) {
                failures += exception.message ?: description
            }
        }

        requireInvariant(manifest.schemaVersion == supportedSchemaVersion, "Unsupported runtime manifest schema version: ${manifest.schemaVersion}")
        requireInvariant(manifest.runtimeFamily == "java21-jre", "Runtime family must be java21-jre")
        requireInvariant(manifest.vendor == "Eclipse Adoptium", "Runtime vendor must be Eclipse Adoptium")
        requireInvariant(manifest.implementor == "Eclipse Adoptium", "Runtime implementor must be Eclipse Adoptium")
        requireInvariant(manifest.distribution == "Temurin", "Runtime distribution must be Temurin")
        requireInvariant(manifest.implementorVersion == "Temurin-21.0.11+10", "Runtime implementor version must be Temurin-21.0.11+10")
        requireInvariant(manifest.javaFeatureVersion == 21, "Java feature version must be 21")
        requireInvariant(manifest.javaVersion == "21.0.11", "Java version must be 21.0.11")
        requireInvariant(manifest.javaRuntimeVersion == "21.0.11+10-LTS", "Java runtime version must be 21.0.11+10-LTS")
        requireInvariant(manifest.jvmVariant == "Hotspot", "JVM variant must be Hotspot")
        requireInvariant(manifest.archiveRoot == "jdk-21.0.11+10-jre", "Archive root must be jdk-21.0.11+10-jre")
        safePath(manifest.archiveRoot, "Global archive root")
        requireInvariant(manifest.supportedPlatformCount == 6, "Supported platform count must be 6")
        requireInvariant(manifest.platforms.size == manifest.supportedPlatformCount, "Manifest platform count does not match supportedPlatformCount")

        val ids = manifest.platforms.map { it.id }
        requireInvariant(ids.toSet().size == ids.size, "Runtime platform IDs must be unique")
        requireInvariant(ids.toSet() == expectedMatrix, "Runtime platform matrix must be exactly ${expectedMatrix.sorted().joinToString()}")
        val tuples = manifest.platforms.map { it.operatingSystem to it.architecture }
        requireInvariant(tuples.toSet().size == tuples.size, "Runtime OS/architecture tuples must be unique")
        val inputPaths = manifest.platforms.map { it.inputPath }
        requireInvariant(inputPaths.toSet().size == inputPaths.size, "Runtime input paths must be unique")
        val bundlePaths = manifest.platforms.map { it.normalizedBundlePath }
        requireInvariant(bundlePaths.toSet().size == bundlePaths.size, "Normalized runtime bundle paths must be unique")

        manifest.platforms.forEach { platform ->
            val prefix = "Platform ${platform.id}:"
            requireInvariant(platform.id.isNotBlank(), "$prefix ID is required")
            requireInvariant(platform.operatingSystem in setOf("linux", "macos", "windows"), "$prefix canonical operating system is invalid")
            requireInvariant(platform.architecture in setOf("x86_64", "aarch64"), "$prefix canonical architecture is invalid")
            requireInvariant(platform.id == "${platform.operatingSystem}-${platform.architecture}", "$prefix ID must be derived from canonical OS and architecture")
            requireInvariant(platform.operatingSystemAliases.isNotEmpty(), "$prefix operating-system aliases are required")
            requireInvariant(platform.architectureAliases.isNotEmpty(), "$prefix architecture aliases are required")
            platform.operatingSystemAliases.forEach { alias ->
                requireInvariant(RuntimePlatformNames.canonicalOperatingSystem(alias) == platform.operatingSystem, "$prefix OS alias '$alias' does not map to ${platform.operatingSystem}")
            }
            platform.architectureAliases.forEach { alias ->
                requireInvariant(RuntimePlatformNames.canonicalArchitecture(alias) == platform.architecture, "$prefix architecture alias '$alias' does not map to ${platform.architecture}")
            }

            safePath(platform.inputPath, "$prefix input path")
            safePath(platform.inputFilename, "$prefix input filename")
            safePath(platform.normalizedBundlePath, "$prefix normalized bundle path")
            safePath(platform.archiveRoot, "$prefix archive root")
            safePath(platform.javaHomeRelativePath, "$prefix Java-home path", allowDot = true)
            safePath(platform.javaExecutableRelativePath, "$prefix Java executable path")
            platform.windowsGuiExecutableRelativePath?.let { safePath(it, "$prefix Windows GUI executable path") }
            safePath(platform.releaseFileRelativePath, "$prefix release-file path")

            requireInvariant(platform.inputFilename == platform.inputPath.substringAfterLast('/'), "$prefix input filename must match input path")
            requireInvariant(platform.archiveRoot == manifest.archiveRoot, "$prefix archive root must match the global archive root")
            requireInvariant(platform.sizeBytes > 0, "$prefix archive size must be positive")
            requireInvariant(Regex("^[0-9a-f]{64}$").matches(platform.sha256), "$prefix SHA-256 must be 64 lowercase hexadecimal characters")

            val expectedExtension = if (platform.operatingSystem == "windows") "zip" else "tar.gz"
            requireInvariant(platform.archiveType == expectedExtension, "$prefix archive type must be $expectedExtension")
            requireInvariant(platform.inputFilename.endsWith(".$expectedExtension"), "$prefix input filename extension must match $expectedExtension")
            requireInvariant(platform.normalizedBundlePath == "runtimes/${platform.id}.$expectedExtension", "$prefix normalized bundle path is not canonical")

            when (platform.operatingSystem) {
                "linux" -> {
                    requireInvariant(platform.javaHomeRelativePath == ".", "$prefix Linux Java home must be the archive root")
                    requireInvariant(platform.javaExecutableRelativePath == "bin/java", "$prefix Linux Java executable must be bin/java")
                    requireInvariant(platform.windowsGuiExecutableRelativePath == null, "$prefix Linux must not define javaw.exe")
                    requireInvariant(platform.releaseFileRelativePath == "release", "$prefix Linux release file must be release")
                    requireInvariant(platform.libc == "gnu", "$prefix Linux must require GNU libc")
                }
                "macos" -> {
                    requireInvariant(platform.javaHomeRelativePath == "Contents/Home", "$prefix macOS Java home must be Contents/Home")
                    requireInvariant(platform.javaExecutableRelativePath == "bin/java", "$prefix macOS Java executable must be bin/java")
                    requireInvariant(platform.windowsGuiExecutableRelativePath == null, "$prefix macOS must not define javaw.exe")
                    requireInvariant(platform.releaseFileRelativePath == "release", "$prefix macOS release file must be release")
                    requireInvariant(platform.libc == null, "$prefix macOS must not advertise a libc contract")
                }
                "windows" -> {
                    requireInvariant(platform.javaHomeRelativePath == ".", "$prefix Windows Java home must be the archive root")
                    requireInvariant(platform.javaExecutableRelativePath == "bin/java.exe", "$prefix Windows console executable must be bin/java.exe")
                    requireInvariant(platform.windowsGuiExecutableRelativePath == "bin/javaw.exe", "$prefix Windows GUI executable must be bin/javaw.exe")
                    requireInvariant(platform.releaseFileRelativePath == "release", "$prefix Windows release file must be release")
                    requireInvariant(platform.libc == null, "$prefix Windows must not advertise a libc contract")
                }
            }

            val expectedOsName = when (platform.operatingSystem) {
                "linux" -> "Linux"
                "macos" -> "Darwin"
                else -> "Windows"
            }
            val requiredReleaseProperties = mapOf(
                "IMPLEMENTOR" to manifest.implementor,
                "IMPLEMENTOR_VERSION" to manifest.implementorVersion,
                "JAVA_VERSION" to manifest.javaVersion,
                "JAVA_RUNTIME_VERSION" to manifest.javaRuntimeVersion,
                "OS_NAME" to expectedOsName,
                "OS_ARCH" to platform.architecture,
                "JVM_VARIANT" to manifest.jvmVariant,
                "LIBC" to (platform.libc ?: "default"),
            )
            requiredReleaseProperties.forEach { (name, value) ->
                requireInvariant(platform.expectedReleaseProperties[name] == value, "$prefix expected release property $name must be '$value'")
            }
        }

        if (failures.isNotEmpty()) {
            throw RuntimeContractException("Runtime manifest validation failed:\n" + failures.joinToString("\n") { " - $it" })
        }
    }
}
