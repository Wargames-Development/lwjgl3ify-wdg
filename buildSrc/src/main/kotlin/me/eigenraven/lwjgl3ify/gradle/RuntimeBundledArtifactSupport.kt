package me.eigenraven.lwjgl3ify.gradle

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal const val EMBEDDED_RUNTIME_PREFIX =
    "me/eigenraven/lwjgl3ify/relauncher/runtime/embedded/"

internal val PRIMARY_RUNTIME_PLATFORM_IDS = linkedSetOf(
    "windows-x86_64",
    "linux-x86_64",
    "macos-x86_64",
    "macos-aarch64",
)

internal data class RuntimeBundledArtifactReport(
    val outputSize: Long,
    val outputSha256: String,
    val runtimeHashes: Map<String, String>,
)

internal object RuntimeBundledArtifactSupport {

    private const val fixedTimestamp = 315532800000L
    private const val distributionManifestEntry = "META-INF/lwjgl3ify-wdg/runtime-distribution.json"
    private const val distributionNoticeEntry = "META-INF/lwjgl3ify-wdg/RUNTIME-NOTICE.txt"

    fun packageArtifact(baseJar: File, normalizedBundle: File, manifestFile: File, outputFile: File): RuntimeBundledArtifactReport {
        require(baseJar.isFile) { "Base production mod JAR is missing: ${baseJar.path}" }
        require(normalizedBundle.isFile) { "Normalized Java runtime bundle is missing: ${normalizedBundle.path}" }
        val manifest = JavaRuntimeManifestIO.loadAndValidate(manifestFile)
        val primary = manifest.platforms.filter { it.id in PRIMARY_RUNTIME_PLATFORM_IDS }
        require(primary.size == PRIMARY_RUNTIME_PLATFORM_IDS.size) { "Primary runtime matrix is incomplete" }

        val runtimeBytes = linkedMapOf<String, ByteArray>()
        ZipFile(normalizedBundle).use { bundle ->
            val root = bundle.entries().asSequence()
                .map { it.name }
                .firstOrNull { it.endsWith("/manifest.json") }
                ?.substringBefore('/')
                ?: error("Normalized runtime bundle lacks manifest.json")
            primary.forEach { platform ->
                val entryName = "$root/${platform.normalizedBundlePath}"
                val entry = bundle.getEntry(entryName) ?: error("Normalized runtime bundle lacks $entryName")
                require(!entry.isDirectory) { "Runtime entry is a directory: $entryName" }
                val bytes = bundle.getInputStream(entry).use { it.readBytesBounded(platform.sizeBytes) }
                require(bytes.size.toLong() == platform.sizeBytes) { "Runtime size mismatch for ${platform.id}" }
                require(sha256(bytes) == platform.sha256) { "Runtime SHA-256 mismatch for ${platform.id}" }
                runtimeBytes[EMBEDDED_RUNTIME_PREFIX + platform.normalizedBundlePath] = bytes
            }
        }

        val metadataEntries = linkedMapOf(
            distributionManifestEntry to distributionManifest(manifest).toByteArray(Charsets.UTF_8),
            distributionNoticeEntry to distributionNotice().toByteArray(Charsets.UTF_8),
        )

        val baseEntries = linkedMapOf<String, ByteArray>()
        ZipFile(baseJar).use { jar ->
            val duplicates = jar.entries().asSequence().groupBy { it.name }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "Base mod JAR contains duplicate entries: ${duplicates.sorted().joinToString()}" }
            jar.entries().asSequence().forEach { entry ->
                require(!entry.name.startsWith(EMBEDDED_RUNTIME_PREFIX)) {
                    "Base production JAR already contains embedded runtime entries"
                }
                baseEntries[entry.name] = if (entry.isDirectory) ByteArray(0) else jar.getInputStream(entry).use { it.readBytes() }
            }
        }

        outputFile.parentFile.mkdirs()
        val temporary = File(outputFile.parentFile, ".${outputFile.name}.tmp")
        temporary.delete()
        ZipOutputStream(temporary.outputStream().buffered()).use { output ->
            val names = (baseEntries.keys + runtimeBytes.keys + metadataEntries.keys).distinct().sortedWith(
                compareBy<String> { if (it == "META-INF/MANIFEST.MF") 0 else 1 }.thenBy { it },
            )
            names.forEach { name ->
                val bytes = runtimeBytes[name] ?: metadataEntries[name] ?: baseEntries.getValue(name)
                writeStoredEntry(output, name, bytes)
            }
        }
        try {
            java.nio.file.Files.move(
                temporary.toPath(),
                outputFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                temporary.toPath(),
                outputFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
        return verifyArtifact(outputFile, baseJar, manifestFile)
    }

    fun verifyArtifact(runtimeJar: File, baseJar: File, manifestFile: File): RuntimeBundledArtifactReport {
        require(runtimeJar.isFile) { "Runtime-bearing mod JAR is missing: ${runtimeJar.path}" }
        require(baseJar.isFile) { "Base production mod JAR is missing: ${baseJar.path}" }
        val manifest = JavaRuntimeManifestIO.loadAndValidate(manifestFile)
        val primary = manifest.platforms.filter { it.id in PRIMARY_RUNTIME_PLATFORM_IDS }

        ZipFile(baseJar).use { base ->
            ZipFile(runtimeJar).use { bundled ->
                val bundledNames = bundled.entries().asSequence().map { it.name }.toList()
                val duplicateNames = bundledNames.groupBy { it }.filterValues { it.size > 1 }.keys
                require(duplicateNames.isEmpty()) { "Runtime-bearing JAR contains duplicate entries" }

                base.entries().asSequence().forEach { baseEntry ->
                    val bundledEntry = bundled.getEntry(baseEntry.name)
                        ?: error("Runtime-bearing JAR lost base entry ${baseEntry.name}")
                    if (!baseEntry.isDirectory) {
                        val baseHash = base.getInputStream(baseEntry).use(::sha256)
                        val bundledHash = bundled.getInputStream(bundledEntry).use(::sha256)
                        require(baseHash == bundledHash) { "Runtime-bearing JAR changed base entry ${baseEntry.name}" }
                    }
                }

                val expectedDistributionManifest = distributionManifest(manifest).toByteArray(Charsets.UTF_8)
                val expectedDistributionNotice = distributionNotice().toByteArray(Charsets.UTF_8)
                require(bundled.getEntry(distributionManifestEntry) != null) { "Runtime-bearing JAR lacks distribution manifest" }
                require(bundled.getEntry(distributionNoticeEntry) != null) { "Runtime-bearing JAR lacks runtime notice" }
                require(bundled.getInputStream(bundled.getEntry(distributionManifestEntry)).use { it.readBytes() }
                    .contentEquals(expectedDistributionManifest)) { "Runtime distribution manifest is not deterministic" }
                require(bundled.getInputStream(bundled.getEntry(distributionNoticeEntry)).use { it.readBytes() }
                    .contentEquals(expectedDistributionNotice)) { "Runtime distribution notice is not deterministic" }

                val expectedEntries = primary.associateBy(
                    { EMBEDDED_RUNTIME_PREFIX + it.normalizedBundlePath },
                    { it },
                )
                val actualEntries = bundledNames.filter { it.startsWith(EMBEDDED_RUNTIME_PREFIX) }.toSet()
                require(actualEntries == expectedEntries.keys) {
                    "Embedded runtime entries differ from primary matrix; expected=${expectedEntries.keys.sorted()} actual=${actualEntries.sorted()}"
                }
                val hashes = linkedMapOf<String, String>()
                expectedEntries.forEach { (entryName, platform) ->
                    val entry = bundled.getEntry(entryName) ?: error("Missing embedded runtime $entryName")
                    require(entry.size == platform.sizeBytes) {
                        "Embedded runtime size mismatch for ${platform.id}: ${entry.size} != ${platform.sizeBytes}"
                    }
                    val hash = bundled.getInputStream(entry).use(::sha256)
                    require(hash == platform.sha256) { "Embedded runtime SHA-256 mismatch for ${platform.id}" }
                    hashes[platform.id] = hash
                }
                return RuntimeBundledArtifactReport(runtimeJar.length(), sha256(runtimeJar), hashes)
            }
        }
    }

    private fun distributionManifest(manifest: JavaRuntimeManifest): String {
        val embedded = manifest.platforms.filter { it.id in PRIMARY_RUNTIME_PLATFORM_IDS }
        val extensions = manifest.platforms.filter { it.id !in PRIMARY_RUNTIME_PLATFORM_IDS }
        fun platformJson(platform: JavaRuntimePlatform, embeddedRuntime: Boolean): String = buildString {
            append('{')
            append("\"id\":\"${platform.id}\",")
            append("\"operatingSystem\":\"${platform.operatingSystem}\",")
            append("\"architecture\":\"${platform.architecture}\",")
            append("\"originalFilename\":\"${platform.inputFilename}\",")
            append("\"archiveType\":\"${platform.archiveType}\",")
            append("\"sizeBytes\":${platform.sizeBytes},")
            append("\"sha256\":\"${platform.sha256}\",")
            if (embeddedRuntime) {
                append("\"packagedResource\":\"$EMBEDDED_RUNTIME_PREFIX${platform.normalizedBundlePath}\"")
            } else {
                append(
                    "\"extensionFilename\":\"lwjgl3ify-wdg-java21-${platform.id}.${platform.archiveType}\"",
                )
            }
            append('}')
        }
        return buildString {
            append('{')
            append("\"schemaVersion\":1,")
            append("\"artifactType\":\"lwjgl3ify-wdg-runtime-bundled\",")
            append("\"vendor\":\"${manifest.vendor}\",")
            append("\"implementor\":\"${manifest.implementor}\",")
            append("\"distribution\":\"${manifest.distribution}\",")
            append("\"implementorVersion\":\"${manifest.implementorVersion}\",")
            append("\"javaVersion\":\"${manifest.javaVersion}\",")
            append("\"javaRuntimeVersion\":\"${manifest.javaRuntimeVersion}\",")
            append("\"runtimeLicence\":\"GPL-2.0-only WITH Classpath-exception-2.0\",")
            append("\"sourceProject\":\"https://adoptium.net/temurin/\",")
            append("\"legalContentsPreserved\":true,")
            append("\"systemInstallation\":false,")
            append("\"networkRuntimeDownload\":false,")
            append("\"embeddedPlatforms\":[${embedded.joinToString(",") { platformJson(it, true) }}],")
            append("\"extensionPlatforms\":[${extensions.joinToString(",") { platformJson(it, false) }}]")
            append('}')
            append('\n')
        }
    }

    private fun distributionNotice(): String =
        """
        lwjgl3ify-WDG portable Java runtime notice

        This JAR contains unmodified, hash-pinned Eclipse Temurin Java 21 JRE archives for four common desktop platforms.
        They are extracted only to the lwjgl3ify managed cache and are not installed into the operating system.
        No runtime is downloaded from the network. Optional architecture extensions are validated against the same manifest.

        lwjgl3ify upstream: https://github.com/GTNewHorizons/lwjgl3ify
        WDG fork source: https://github.com/Wargames-Development/lwjgl3ify-wdg
        Eclipse Temurin: https://adoptium.net/temurin/

        The complete upstream and runtime licence and legal contents remain in the source repository and nested runtime archives.
        """.trimIndent() + "\n"


    private fun writeStoredEntry(output: ZipOutputStream, name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }
        val entry = ZipEntry(name).apply {
            time = fixedTimestamp
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
        }
        output.putNextEntry(entry)
        if (!name.endsWith('/')) output.write(bytes)
        output.closeEntry()
    }

    private fun InputStream.readBytesBounded(expected: Long): ByteArray {
        require(expected <= Int.MAX_VALUE) { "Runtime archive is too large for deterministic in-memory packaging" }
        val output = java.io.ByteArrayOutputStream(expected.toInt())
        val buffer = ByteArray(1024 * 1024)
        var total = 0L
        BufferedInputStream(this).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= expected) { "Runtime archive exceeds pinned size" }
                output.write(buffer, 0, read)
            }
        }
        require(total == expected) { "Runtime archive ended at $total bytes instead of $expected" }
        return output.toByteArray()
    }

    private fun sha256(file: File): String = file.inputStream().buffered().use(::sha256)

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}
