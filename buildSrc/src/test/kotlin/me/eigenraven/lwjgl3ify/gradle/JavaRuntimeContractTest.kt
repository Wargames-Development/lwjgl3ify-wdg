package me.eigenraven.lwjgl3ify.gradle

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JavaRuntimeContractTest {

    @Test
    fun canonicalOperatingSystemMapping() {
        assertEquals("linux", RuntimePlatformNames.canonicalOperatingSystem("Linux"))
        assertEquals("macos", RuntimePlatformNames.canonicalOperatingSystem("Mac OS X"))
        assertEquals("macos", RuntimePlatformNames.canonicalOperatingSystem("Darwin"))
        assertEquals("windows", RuntimePlatformNames.canonicalOperatingSystem("Windows"))
        assertEquals("windows", RuntimePlatformNames.canonicalOperatingSystem("Windows 11"))
    }

    @Test
    fun canonicalArchitectureMapping() {
        assertEquals("x86_64", RuntimePlatformNames.canonicalArchitecture("amd64"))
        assertEquals("x86_64", RuntimePlatformNames.canonicalArchitecture("x86_64"))
        assertEquals("x86_64", RuntimePlatformNames.canonicalArchitecture("x64"))
        assertEquals("aarch64", RuntimePlatformNames.canonicalArchitecture("aarch64"))
        assertEquals("aarch64", RuntimePlatformNames.canonicalArchitecture("arm64"))
    }

    @Test
    fun duplicatePlatformIsRejected() {
        val manifest = canonicalManifest()
        val duplicated = manifest.copy(
            platforms = listOf(manifest.platforms.first()) + manifest.platforms.dropLast(1),
        )
        assertFailsWith<RuntimeContractException> {
            JavaRuntimeManifestIO.validate(duplicated)
        }
    }

    @Test
    fun malformedSha256IsRejected() {
        val manifest = canonicalManifest()
        val malformed = manifest.copy(
            platforms = manifest.platforms.mapIndexed { index, platform ->
                if (index == 0) platform.copy(sha256 = "ABC123") else platform
            },
        )
        assertFailsWith<RuntimeContractException> {
            JavaRuntimeManifestIO.validate(malformed)
        }
    }

    @Test
    fun missingRequiredManifestFieldIsRejected() {
        val source = canonicalManifestFile().readText()
        val missingVendor = source.replace(Regex("""\s*"vendor":\s*"[^"]+",\n"""), "\n")
        val temp = Files.createTempFile("runtime-manifest-missing-field", ".json").toFile()
        try {
            temp.writeText(missingVendor)
            assertFailsWith<RuntimeContractException> {
                JavaRuntimeManifestIO.loadAndValidate(temp)
            }
        } finally {
            temp.delete()
        }
    }

    @Test
    fun absolutePathIsRejected() {
        assertFailsWith<RuntimeContractException> {
            RuntimePathSafety.requireSafeRelative("/tmp/runtime.zip", "test path")
        }
    }

    @Test
    fun traversalPathIsRejected() {
        assertFailsWith<RuntimeContractException> {
            RuntimePathSafety.requireSafeRelative("runtimes/../runtime.zip", "test path")
        }
    }

    @Test
    fun windowsDrivePathIsRejected() {
        assertFailsWith<RuntimeContractException> {
            RuntimePathSafety.requireSafeRelative("C:/runtime.zip", "test path")
        }
    }

    @Test
    fun validTinyBundleIsAccepted() {
        val nested = tinyLinuxRuntime()
        val platform = fixturePlatform(nested)
        val outer = outerBundle(platform, nested)
        try {
            val verified = JavaRuntimeBundleVerifier.verify(fixtureManifest(platform), outer)
            assertEquals(1, verified.runtimes.size)
            assertEquals(platform.sha256, verified.runtimes.single().sha256)
        } finally {
            outer.delete()
        }
    }

    @Test
    fun validTinyWindowsBundleIsAccepted() {
        val nested = tinyWindowsRuntime()
        val platform = fixtureWindowsPlatform(nested)
        val outer = outerBundle(platform, nested)
        try {
            val verified = JavaRuntimeBundleVerifier.verify(fixtureManifest(platform), outer)
            assertEquals(1, verified.runtimes.size)
            assertEquals(platform.sha256, verified.runtimes.single().sha256)
        } finally {
            outer.delete()
        }
    }

    @Test
    fun missingWindowsGuiExecutableIsRejected() {
        val nested = tinyWindowsRuntime(includeJavaw = false)
        val platform = fixtureWindowsPlatform(nested)
        val outer = outerBundle(platform, nested)
        try {
            val exception = assertFailsWith<RuntimeContractException> {
                JavaRuntimeBundleVerifier.verify(fixtureManifest(platform), outer)
            }
            assertTrue(exception.message.orEmpty().contains("GUI executable"))
        } finally {
            outer.delete()
        }
    }

    @Test
    fun nestedArchiveRootMismatchIsRejected() {
        val nested = tinyLinuxRuntime(root = "wrong-root")
        val platform = fixturePlatform(nested)
        val outer = outerBundle(platform, nested)
        try {
            assertFailsWith<RuntimeContractException> {
                JavaRuntimeBundleVerifier.verify(fixtureManifest(platform), outer)
            }
        } finally {
            outer.delete()
        }
    }

    @Test
    fun unexpectedOuterPayloadIsRejected() {
        val nested = tinyLinuxRuntime()
        val platform = fixturePlatform(nested)
        val outer = outerBundle(platform, nested, extraPayload = "unexpected.txt")
        try {
            assertFailsWith<RuntimeContractException> {
                JavaRuntimeBundleVerifier.verify(fixtureManifest(platform), outer)
            }
        } finally {
            outer.delete()
        }
    }

    @Test
    fun missingJavaExecutableIsRejected() {
        val nested = tinyLinuxRuntime(includeJava = false)
        val platform = fixturePlatform(nested)
        val outer = outerBundle(platform, nested)
        try {
            assertFailsWith<RuntimeContractException> {
                JavaRuntimeBundleVerifier.verify(fixtureManifest(platform), outer)
            }
        } finally {
            outer.delete()
        }
    }

    @Test
    fun releasePropertyMismatchIsRejected() {
        val nested = tinyLinuxRuntime(implementor = "Substituted Vendor")
        val platform = fixturePlatform(nested)
        val outer = outerBundle(platform, nested)
        try {
            val exception = assertFailsWith<RuntimeContractException> {
                JavaRuntimeBundleVerifier.verify(fixtureManifest(platform), outer)
            }
            assertTrue(exception.message.orEmpty().contains("IMPLEMENTOR"))
        } finally {
            outer.delete()
        }
    }

    @Test
    fun checksumMismatchIsRejected() {
        val nested = tinyLinuxRuntime()
        val platform = fixturePlatform(nested).copy(sha256 = "0".repeat(64))
        val outer = outerBundle(platform, nested)
        try {
            val exception = assertFailsWith<RuntimeContractException> {
                JavaRuntimeBundleVerifier.verify(fixtureManifest(platform), outer)
            }
            assertTrue(exception.message.orEmpty().contains("SHA-256 mismatch"))
        } finally {
            outer.delete()
        }
    }

    private fun canonicalManifestFile(): File {
        val relativePath =
            "src/main/resources/me/eigenraven/lwjgl3ify/relauncher/runtime/java21-runtime-manifest.json"
        return generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .map { it.resolve(relativePath) }
            .firstOrNull { it.isFile }
            ?: error("Could not locate canonical runtime manifest from ${System.getProperty("user.dir")}")
    }

    private fun canonicalManifest(): JavaRuntimeManifest =
        JavaRuntimeManifestIO.loadAndValidate(canonicalManifestFile())

    private fun fixtureManifest(platform: JavaRuntimePlatform): JavaRuntimeManifest = JavaRuntimeManifest(
        schemaVersion = 1,
        runtimeFamily = "java21-jre",
        vendor = "Eclipse Adoptium",
        implementor = "Eclipse Adoptium",
        distribution = "Temurin",
        implementorVersion = "Temurin-21.0.11+10",
        javaFeatureVersion = 21,
        javaVersion = "21.0.11",
        javaRuntimeVersion = "21.0.11+10-LTS",
        jvmVariant = "Hotspot",
        archiveRoot = "jdk-21.0.11+10-jre",
        supportedPlatformCount = 1,
        platforms = listOf(platform),
    )

    private fun fixturePlatform(nested: ByteArray): JavaRuntimePlatform = JavaRuntimePlatform(
        id = "linux-x86_64",
        operatingSystem = "linux",
        architecture = "x86_64",
        operatingSystemAliases = listOf("Linux"),
        architectureAliases = listOf("amd64", "x86_64", "x64"),
        inputPath = "Required Java Packages/Linux/runtime.tar.gz",
        inputFilename = "runtime.tar.gz",
        normalizedBundlePath = "runtimes/linux-x86_64.tar.gz",
        archiveType = "tar.gz",
        sizeBytes = nested.size.toLong(),
        sha256 = sha256(nested),
        archiveRoot = "jdk-21.0.11+10-jre",
        javaHomeRelativePath = ".",
        javaExecutableRelativePath = "bin/java",
        releaseFileRelativePath = "release",
        libc = "gnu",
        expectedReleaseProperties = linkedMapOf(
            "IMPLEMENTOR" to "Eclipse Adoptium",
            "IMPLEMENTOR_VERSION" to "Temurin-21.0.11+10",
            "JAVA_VERSION" to "21.0.11",
            "JAVA_RUNTIME_VERSION" to "21.0.11+10-LTS",
            "OS_NAME" to "Linux",
            "OS_ARCH" to "x86_64",
            "JVM_VARIANT" to "Hotspot",
            "LIBC" to "gnu",
        ),
    )

    private fun fixtureWindowsPlatform(nested: ByteArray): JavaRuntimePlatform =
        JavaRuntimePlatform(
            id = "windows-x86_64",
            operatingSystem = "windows",
            architecture = "x86_64",
            operatingSystemAliases = listOf("Windows"),
            architectureAliases = listOf("amd64", "x86_64", "x64"),
            inputPath = "Required Java Packages/Windows/runtime.zip",
            inputFilename = "runtime.zip",
            normalizedBundlePath = "runtimes/windows-x86_64.zip",
            archiveType = "zip",
            sizeBytes = nested.size.toLong(),
            sha256 = sha256(nested),
            archiveRoot = "jdk-21.0.11+10-jre",
            javaHomeRelativePath = ".",
            javaExecutableRelativePath = "bin/java.exe",
            windowsGuiExecutableRelativePath = "bin/javaw.exe",
            releaseFileRelativePath = "release",
            expectedReleaseProperties = linkedMapOf(
                "IMPLEMENTOR" to "Eclipse Adoptium",
                "IMPLEMENTOR_VERSION" to "Temurin-21.0.11+10",
                "JAVA_VERSION" to "21.0.11",
                "JAVA_RUNTIME_VERSION" to "21.0.11+10-LTS",
                "OS_NAME" to "Windows",
                "OS_ARCH" to "x86_64",
                "JVM_VARIANT" to "Hotspot",
                "LIBC" to "default",
            ),
        )

    private fun tinyWindowsRuntime(
        includeJavaw: Boolean = true,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            addZipFile(zip, "jdk-21.0.11+10-jre/bin/java.exe", byteArrayOf(0x4d, 0x5a))
            if (includeJavaw) {
                addZipFile(zip, "jdk-21.0.11+10-jre/bin/javaw.exe", byteArrayOf(0x4d, 0x5a))
            }
            val release = """
                IMPLEMENTOR="Eclipse Adoptium"
                IMPLEMENTOR_VERSION="Temurin-21.0.11+10"
                JAVA_VERSION="21.0.11"
                JAVA_RUNTIME_VERSION="21.0.11+10-LTS"
                OS_NAME="Windows"
                OS_ARCH="x86_64"
                JVM_VARIANT="Hotspot"
                LIBC="default"
            """.trimIndent().plus("\n").toByteArray(StandardCharsets.UTF_8)
            addZipFile(zip, "jdk-21.0.11+10-jre/release", release)
        }
        return output.toByteArray()
    }

    private fun tinyLinuxRuntime(
        root: String = "jdk-21.0.11+10-jre",
        includeJava: Boolean = true,
        implementor: String = "Eclipse Adoptium",
    ): ByteArray {
        val output = ByteArrayOutputStream()
        GzipCompressorOutputStream(output).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                if (includeJava) {
                    addTarFile(tar, "$root/bin/java", "#!/bin/sh\n".toByteArray(), mode = 493)
                }
                val release = """
                    IMPLEMENTOR="$implementor"
                    IMPLEMENTOR_VERSION="Temurin-21.0.11+10"
                    JAVA_VERSION="21.0.11"
                    JAVA_RUNTIME_VERSION="21.0.11+10-LTS"
                    OS_NAME="Linux"
                    OS_ARCH="x86_64"
                    JVM_VARIANT="Hotspot"
                    LIBC="gnu"
                """.trimIndent().plus("\n").toByteArray(StandardCharsets.UTF_8)
                addTarFile(tar, "$root/release", release, mode = 420)
                tar.finish()
            }
        }
        return output.toByteArray()
    }

    private fun addTarFile(
        tar: TarArchiveOutputStream,
        name: String,
        bytes: ByteArray,
        mode: Int,
    ) {
        val entry = TarArchiveEntry(name)
        entry.size = bytes.size.toLong()
        entry.mode = mode
        tar.putArchiveEntry(entry)
        tar.write(bytes)
        tar.closeArchiveEntry()
    }

    private fun addZipFile(
        zip: ZipOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun outerBundle(
        platform: JavaRuntimePlatform,
        nested: ByteArray,
        extraPayload: String? = null,
    ): File {
        val file = Files.createTempFile("runtime-bundle-fixture", ".zip").toFile()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(platform.inputPath))
            zip.write(nested)
            zip.closeEntry()
            extraPayload?.let {
                zip.putNextEntry(ZipEntry(it))
                zip.write("unexpected".toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
