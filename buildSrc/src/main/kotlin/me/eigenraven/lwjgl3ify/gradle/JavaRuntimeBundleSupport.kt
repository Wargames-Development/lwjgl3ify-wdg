package me.eigenraven.lwjgl3ify.gradle

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.LocalDateTime
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile as JdkZipFile

private const val bufferSize = 1024 * 1024
private const val maximumArchiveEntries = 10_000
private const val maximumUncompressedBytes = 4L * 1024L * 1024L * 1024L
private const val maximumReleaseBytes = 2 * 1024 * 1024
private const val maximumLinkTargetBytes = 16 * 1024
private val reproducibleZipTime = LocalDateTime.of(1980, 1, 1, 0, 0)

internal data class VerifiedRuntimeArchive(
    val platform: JavaRuntimePlatform,
    val sizeBytes: Long,
    val sha256: String,
    val archiveEntryCount: Int,
)

internal data class VerifiedJavaRuntimeBundle(
    val runtimes: List<VerifiedRuntimeArchive>,
)

internal data class PackagedJavaRuntimeBundle(
    val outputFile: File,
    val memberCount: Int,
    val outputBytes: Long,
    val runtimeHashes: Map<String, String>,
)

internal object JavaRuntimeBundleVerifier {
    fun verify(manifest: JavaRuntimeManifest, bundleFile: File): VerifiedJavaRuntimeBundle {
        if (!bundleFile.isFile) {
            throw RuntimeContractException("Java runtime bundle does not exist or is not a file: ${bundleFile.path}")
        }

        JdkZipFile(bundleFile).use { outerZip ->
            val entries = outerZip.entries().asSequence().toList()
            val entriesByName = entries.groupBy { it.name }
            val duplicateNames = entriesByName.filterValues { it.size > 1 }.keys.sorted()
            if (duplicateNames.isNotEmpty()) {
                throw RuntimeContractException(
                    "External Java runtime bundle contains duplicate ZIP entries: ${duplicateNames.joinToString()}",
                )
            }

            entries.forEach { entry ->
                RuntimePathSafety.normalizedArchiveEntry(entry.name, "Outer ZIP entry")
            }

            val expectedPaths = manifest.platforms.associateBy { it.inputPath }
            val unexpectedPayload = entries
                .filterNot { it.isDirectory }
                .filterNot { isKnownFinderMetadata(it.name) }
                .filterNot { it.name in expectedPaths }
                .map { it.name }
                .sorted()
            if (unexpectedPayload.isNotEmpty()) {
                throw RuntimeContractException(
                    "External Java runtime bundle contains unexpected payload files: ${unexpectedPayload.joinToString()}",
                )
            }

            val verified = manifest.platforms.sortedBy { it.id }.map { platform ->
                try {
                    val matching = entriesByName[platform.inputPath].orEmpty()
                    if (matching.isEmpty()) {
                        throw RuntimeContractException(
                            "Platform ${platform.id}: required runtime archive is missing: ${platform.inputPath}",
                        )
                    }
                    if (matching.size != 1) {
                        throw RuntimeContractException(
                            "Platform ${platform.id}: runtime archive appears ${matching.size} times: ${platform.inputPath}",
                        )
                    }
                    val entry = matching.single()
                    if (entry.isDirectory) {
                        throw RuntimeContractException(
                            "Platform ${platform.id}: runtime archive path is a directory: ${platform.inputPath}",
                        )
                    }
                    if (entry.size != platform.sizeBytes) {
                        throw RuntimeContractException(
                            "Platform ${platform.id}: expected ${platform.sizeBytes} bytes but outer ZIP reports ${entry.size}",
                        )
                    }

                    val hash = outerZip.getInputStream(entry).use(::sha256)
                    if (hash.sizeBytes != platform.sizeBytes) {
                        throw RuntimeContractException(
                            "Platform ${platform.id}: expected ${platform.sizeBytes} bytes but read ${hash.sizeBytes}",
                        )
                    }
                    if (hash.hex != platform.sha256) {
                        throw RuntimeContractException(
                            "Platform ${platform.id}: SHA-256 mismatch; expected ${platform.sha256} but was ${hash.hex}",
                        )
                    }

                    val archiveEntryCount = outerZip.getInputStream(entry).use { nestedInput ->
                        inspectNestedArchive(platform, nestedInput)
                    }
                    VerifiedRuntimeArchive(platform, hash.sizeBytes, hash.hex, archiveEntryCount)
                } catch (exception: RuntimeContractException) {
                    throw exception
                } catch (exception: Exception) {
                    throw RuntimeContractException(
                        "Platform ${platform.id}: runtime archive validation failed: ${exception.message}",
                        exception,
                    )
                }
            }

            return VerifiedJavaRuntimeBundle(verified)
        }
    }

    private fun inspectNestedArchive(platform: JavaRuntimePlatform, nestedInput: InputStream): Int =
        when (platform.archiveType) {
            "tar.gz" -> inspectTarGz(platform, nestedInput)
            "zip" -> inspectZip(platform, nestedInput)
            else -> throw RuntimeContractException(
                "Platform ${platform.id}: unsupported nested archive type ${platform.archiveType}",
            )
        }

    private fun inspectTarGz(platform: JavaRuntimePlatform, nestedInput: InputStream): Int {
        val seen = linkedSetOf<String>()
        val regularFiles = linkedSetOf<String>()
        val executableModes = mutableMapOf<String, Int>()
        val links = mutableListOf<ArchiveLink>()
        var releaseBytes: ByteArray? = null
        var entryCount = 0
        var totalBytes = 0L

        GzipCompressorInputStream(BufferedInputStream(nestedInput)).use { gzip ->
            TarArchiveInputStream(gzip).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    entryCount += 1
                    if (entryCount > maximumArchiveEntries) {
                        throw RuntimeContractException(
                            "Platform ${platform.id}: nested archive contains too many entries",
                        )
                    }
                    val name = RuntimePathSafety.normalizedArchiveEntry(
                        entry.name,
                        "Platform ${platform.id} TAR entry",
                    )
                    requireExpectedRoot(platform, name)
                    if (!seen.add(name)) {
                        throw RuntimeContractException(
                            "Platform ${platform.id}: duplicate nested archive entry: $name",
                        )
                    }

                    when {
                        entry.isDirectory -> Unit
                        entry.isFile -> {
                            regularFiles += name
                            executableModes[name] = entry.mode
                            val captureLimit = if (name == platform.releaseFileEntry) {
                                maximumReleaseBytes
                            } else {
                                null
                            }
                            val consumed = consumeEntry(
                                archive,
                                captureLimit,
                                platform.id,
                                name,
                                totalBytes,
                            )
                            totalBytes = consumed.totalBytes
                            if (name == platform.releaseFileEntry) {
                                releaseBytes = consumed.captured
                            }
                        }
                        entry.isSymbolicLink -> links += ArchiveLink(name, entry.linkName, hardLink = false)
                        entry.isLink -> links += ArchiveLink(name, entry.linkName, hardLink = true)
                        else -> throw RuntimeContractException(
                            "Platform ${platform.id}: unsupported TAR entry type for $name",
                        )
                    }
                }
            }
        }

        validateNestedContents(platform, seen, regularFiles, executableModes, links, releaseBytes)
        return entryCount
    }

    private fun inspectZip(platform: JavaRuntimePlatform, nestedInput: InputStream): Int {
        val temporaryZip = Files.createTempFile(
            "lwjgl3ify-wdg-${platform.id}-",
            ".zip",
        )
        var pendingFailure: Throwable? = null
        try {
            val copied = Files.newOutputStream(temporaryZip).use { output ->
                copyBounded(nestedInput, output)
            }
            if (copied != platform.sizeBytes) {
                throw RuntimeContractException(
                    "Platform ${platform.id}: staged nested ZIP has $copied bytes instead of ${platform.sizeBytes}",
                )
            }

            val seen = linkedSetOf<String>()
            val regularFiles = linkedSetOf<String>()
            val executableModes = mutableMapOf<String, Int>()
            val links = mutableListOf<ArchiveLink>()
            var releaseBytes: ByteArray? = null
            var entryCount = 0
            var totalBytes = 0L

            CommonsZipFile.builder().setPath(temporaryZip).get().use { archive ->
                val entries = archive.entries.asSequence().toList()
                val duplicateNames = entries.groupBy { it.name }
                    .filterValues { it.size > 1 }
                    .keys
                    .sorted()
                if (duplicateNames.isNotEmpty()) {
                    throw RuntimeContractException(
                        "Platform ${platform.id}: duplicate nested ZIP entries: ${duplicateNames.joinToString()}",
                    )
                }

                entries.forEach { entry ->
                    if (!archive.canReadEntryData(entry)) {
                        throw RuntimeContractException(
                            "Platform ${platform.id}: nested ZIP entry uses an unsupported or encrypted format: ${entry.name}",
                        )
                    }
                    entryCount += 1
                    if (entryCount > maximumArchiveEntries) {
                        throw RuntimeContractException(
                            "Platform ${platform.id}: nested archive contains too many entries",
                        )
                    }
                    val name = RuntimePathSafety.normalizedArchiveEntry(
                        entry.name,
                        "Platform ${platform.id} ZIP entry",
                    )
                    requireExpectedRoot(platform, name)
                    if (!seen.add(name)) {
                        throw RuntimeContractException(
                            "Platform ${platform.id}: duplicate nested archive entry: $name",
                        )
                    }

                    when {
                        entry.isDirectory -> Unit
                        entry.isUnixSymlink -> {
                            val consumed = archive.getInputStream(entry).use { input ->
                                consumeEntry(
                                    input,
                                    maximumLinkTargetBytes,
                                    platform.id,
                                    name,
                                    totalBytes,
                                )
                            }
                            totalBytes = consumed.totalBytes
                            val target = consumed.captured?.toString(StandardCharsets.UTF_8)
                                ?: throw RuntimeContractException(
                                    "Platform ${platform.id}: unreadable ZIP symlink target for $name",
                                )
                            links += ArchiveLink(name, target, hardLink = false)
                        }
                        else -> {
                            regularFiles += name
                            executableModes[name] = entry.unixMode
                            val captureLimit = if (name == platform.releaseFileEntry) {
                                maximumReleaseBytes
                            } else {
                                null
                            }
                            val consumed = archive.getInputStream(entry).use { input ->
                                consumeEntry(
                                    input,
                                    captureLimit,
                                    platform.id,
                                    name,
                                    totalBytes,
                                )
                            }
                            totalBytes = consumed.totalBytes
                            if (name == platform.releaseFileEntry) {
                                releaseBytes = consumed.captured
                            }
                        }
                    }
                }
            }

            validateNestedContents(
                platform,
                seen,
                regularFiles,
                executableModes,
                links,
                releaseBytes,
            )
            return entryCount
        } catch (exception: Exception) {
            val failure = if (exception is RuntimeContractException) {
                exception
            } else {
                RuntimeContractException(
                    "Platform ${platform.id}: could not inspect nested ZIP: ${exception.message}",
                    exception,
                )
            }
            pendingFailure = failure
            throw failure
        } finally {
            try {
                Files.deleteIfExists(temporaryZip)
            } catch (cleanupFailure: Exception) {
                val originalFailure = pendingFailure
                if (originalFailure != null) {
                    originalFailure.addSuppressed(cleanupFailure)
                } else {
                    throw RuntimeContractException(
                        "Platform ${platform.id}: could not delete temporary nested ZIP ${temporaryZip.fileName}",
                        cleanupFailure,
                    )
                }
            }
        }
    }

    private fun requireExpectedRoot(platform: JavaRuntimePlatform, name: String) {
        val root = name.substringBefore('/')
        if (root != platform.archiveRoot) {
            throw RuntimeContractException(
                "Platform ${platform.id}: nested archive root '$root' does not match '${platform.archiveRoot}'",
            )
        }
    }

    private fun validateNestedContents(
        platform: JavaRuntimePlatform,
        seen: Set<String>,
        regularFiles: Set<String>,
        executableModes: Map<String, Int>,
        links: List<ArchiveLink>,
        releaseBytes: ByteArray?,
    ) {
        if (platform.javaExecutableEntry !in regularFiles) {
            throw RuntimeContractException(
                "Platform ${platform.id}: Java executable is missing: ${platform.javaExecutableEntry}",
            )
        }
        platform.windowsGuiExecutableEntry?.let { guiExecutable ->
            if (guiExecutable !in regularFiles) {
                throw RuntimeContractException(
                    "Platform ${platform.id}: Windows GUI executable is missing: $guiExecutable",
                )
            }
        }
        val verifiedReleaseBytes = releaseBytes ?: throw RuntimeContractException(
            "Platform ${platform.id}: release file is missing: ${platform.releaseFileEntry}",
        )
        if (platform.releaseFileEntry !in regularFiles) {
            throw RuntimeContractException(
                "Platform ${platform.id}: release file is missing: ${platform.releaseFileEntry}",
            )
        }

        if (platform.operatingSystem != "windows") {
            val mode = executableModes[platform.javaExecutableEntry] ?: 0
            if (mode and 73 == 0) {
                throw RuntimeContractException(
                    "Platform ${platform.id}: Java executable does not retain an executable permission bit",
                )
            }
        }

        links.forEach { link ->
            val resolvedTarget = RuntimePathSafety.resolveLink(link.name, link.target, link.hardLink)
            if (
                resolvedTarget != platform.archiveRoot &&
                !resolvedTarget.startsWith("${platform.archiveRoot}/")
            ) {
                throw RuntimeContractException(
                    "Platform ${platform.id}: archive link escapes root: ${link.name} -> ${link.target}",
                )
            }
            if (resolvedTarget !in seen) {
                throw RuntimeContractException(
                    "Platform ${platform.id}: archive link target does not exist: ${link.name} -> ${link.target}",
                )
            }
        }

        val properties = parseReleaseFile(platform.id, verifiedReleaseBytes)
        platform.expectedReleaseProperties.forEach { (name, expected) ->
            val actual = properties[name]
            if (actual != expected) {
                throw RuntimeContractException(
                    "Platform ${platform.id}: release property $name expected '$expected' but was '${actual ?: "missing"}'",
                )
            }
        }
    }

    private fun parseReleaseFile(platformId: String, bytes: ByteArray): Map<String, String> {
        val properties = linkedMapOf<String, String>()
        bytes.toString(StandardCharsets.UTF_8).lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachIndexed
            val separator = line.indexOf('=')
            if (separator <= 0) {
                throw RuntimeContractException(
                    "Platform $platformId: malformed release file line ${index + 1}",
                )
            }
            val name = line.substring(0, separator)
            var value = line.substring(separator + 1).trim()
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
                value = value.substring(1, value.length - 1)
            }
            if (properties.put(name, value) != null) {
                throw RuntimeContractException(
                    "Platform $platformId: duplicate release property $name",
                )
            }
        }
        return properties
    }

    private fun isKnownFinderMetadata(path: String): Boolean {
        val segments = path.split('/')
        val filename = segments.lastOrNull().orEmpty()
        return segments.firstOrNull() == "__MACOSX" ||
            filename == ".DS_Store" ||
            filename.startsWith("._")
    }

    private data class ArchiveLink(val name: String, val target: String, val hardLink: Boolean)

    private data class ConsumedEntry(val totalBytes: Long, val captured: ByteArray?)

    private fun consumeEntry(
        input: InputStream,
        captureLimit: Int?,
        platformId: String,
        entryName: String,
        initialTotal: Long,
    ): ConsumedEntry {
        var total = initialTotal
        val captureLimitBytes = captureLimit ?: 0
        val capture = captureLimit?.let { ByteArrayOutputStream(minOf(it, 64 * 1024)) }
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maximumUncompressedBytes) {
                throw RuntimeContractException(
                    "Platform $platformId: nested archive expands beyond the validation limit",
                )
            }
            if (capture != null) {
                if (capture.size() + read > captureLimitBytes) {
                    throw RuntimeContractException(
                        "Platform $platformId: archive entry is unexpectedly large: $entryName",
                    )
                }
                capture.write(buffer, 0, read)
            }
        }
        return ConsumedEntry(total, capture?.toByteArray())
    }
}

internal object JavaRuntimeBundlePackager {
    private const val topLevel = "lwjgl3ify-wdg-java21-runtimes"

    fun packageBundle(
        manifest: JavaRuntimeManifest,
        manifestFile: File,
        externalBundle: File,
        outputFile: File,
    ): PackagedJavaRuntimeBundle {
        JavaRuntimeBundleVerifier.verify(manifest, externalBundle)
        outputFile.parentFile.mkdirs()
        if (outputFile.exists() && !outputFile.delete()) {
            throw RuntimeContractException(
                "Could not replace generated runtime bundle: ${outputFile.path}",
            )
        }

        try {
            JdkZipFile(externalBundle).use { outerZip ->
                val sourceEntries = outerZip.entries().asSequence().groupBy { it.name }
                ZipArchiveOutputStream(outputFile).use { output ->
                    output.setUseZip64(Zip64Mode.AsNeeded)
                    output.setEncoding(StandardCharsets.UTF_8.name())

                    writeDirectory(output, "$topLevel/")
                    val manifestBytes = manifestFile.readBytes()
                    writeStoredBytes(output, "$topLevel/manifest.json", manifestBytes)
                    writeDirectory(output, "$topLevel/runtimes/")

                    manifest.platforms.sortedBy { it.normalizedBundlePath }.forEach { platform ->
                        val sourceEntry = sourceEntries[platform.inputPath]?.singleOrNull()
                            ?: throw RuntimeContractException(
                                "Platform ${platform.id}: source archive disappeared during packaging",
                            )
                        outerZip.getInputStream(sourceEntry).use { input ->
                            writeStoredStream(
                                output = output,
                                path = "$topLevel/${platform.normalizedBundlePath}",
                                input = input,
                                size = platform.sizeBytes,
                                crc = sourceEntry.crc,
                            )
                        }
                    }
                    output.finish()
                }
            }

            return verifyPackagedBundle(manifest, manifestFile, outputFile)
        } catch (exception: Exception) {
            outputFile.delete()
            if (exception is RuntimeContractException) {
                throw exception
            }
            throw RuntimeContractException(
                "Could not create normalized Java runtime bundle: ${exception.message}",
                exception,
            )
        }
    }

    private fun verifyPackagedBundle(
        manifest: JavaRuntimeManifest,
        manifestFile: File,
        outputFile: File,
    ): PackagedJavaRuntimeBundle {
        JdkZipFile(outputFile).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val names = entries.map { it.name }
            if (names.toSet().size != names.size) {
                throw RuntimeContractException(
                    "Generated runtime bundle contains duplicate members",
                )
            }
            val expectedNames = buildList {
                add("$topLevel/")
                add("$topLevel/manifest.json")
                add("$topLevel/runtimes/")
                manifest.platforms.sortedBy { it.normalizedBundlePath }.forEach {
                    add("$topLevel/${it.normalizedBundlePath}")
                }
            }
            if (names != expectedNames) {
                throw RuntimeContractException(
                    "Generated runtime bundle member ordering or contents are incorrect: ${names.joinToString()}",
                )
            }
            if (
                names.any {
                    it.contains("__MACOSX") ||
                        it.endsWith("/.DS_Store") ||
                        it.substringAfterLast('/').startsWith("._")
                }
            ) {
                throw RuntimeContractException(
                    "Generated runtime bundle contains Finder metadata",
                )
            }
            if (names.any { it.substringBefore('/') != topLevel }) {
                throw RuntimeContractException(
                    "Generated runtime bundle contains entries outside $topLevel/",
                )
            }

            val manifestEntry = zip.getEntry("$topLevel/manifest.json")
                ?: throw RuntimeContractException("Generated runtime bundle is missing manifest.json")
            val embeddedManifest = zip.getInputStream(manifestEntry).use { it.readBytes() }
            if (!embeddedManifest.contentEquals(manifestFile.readBytes())) {
                throw RuntimeContractException(
                    "Generated runtime bundle manifest does not match the canonical manifest",
                )
            }

            val hashes = linkedMapOf<String, String>()
            manifest.platforms.sortedBy { it.id }.forEach { platform ->
                val path = "$topLevel/${platform.normalizedBundlePath}"
                val entry = zip.getEntry(path)
                    ?: throw RuntimeContractException("Generated runtime bundle is missing $path")
                val hash = zip.getInputStream(entry).use(::sha256)
                if (hash.sizeBytes != platform.sizeBytes || hash.hex != platform.sha256) {
                    throw RuntimeContractException(
                        "Generated runtime bundle changed ${platform.id}; " +
                            "expected ${platform.sizeBytes}/${platform.sha256}, " +
                            "got ${hash.sizeBytes}/${hash.hex}",
                    )
                }
                hashes[platform.id] = hash.hex
            }

            return PackagedJavaRuntimeBundle(
                outputFile,
                entries.size,
                outputFile.length(),
                hashes,
            )
        }
    }

    private fun writeDirectory(output: ZipArchiveOutputStream, path: String) {
        val entry = deterministicEntry(path, isDirectory = true)
        entry.method = ZipEntry.STORED
        entry.size = 0
        entry.compressedSize = 0
        entry.crc = 0
        output.putArchiveEntry(entry)
        output.closeArchiveEntry()
    }

    private fun writeStoredBytes(
        output: ZipArchiveOutputStream,
        path: String,
        bytes: ByteArray,
    ) {
        val crc = CRC32().apply { update(bytes) }.value
        ByteArrayInputStream(bytes).use { input ->
            writeStoredStream(output, path, input, bytes.size.toLong(), crc)
        }
    }

    private fun writeStoredStream(
        output: ZipArchiveOutputStream,
        path: String,
        input: InputStream,
        size: Long,
        crc: Long,
    ) {
        val entry = deterministicEntry(path, isDirectory = false)
        entry.method = ZipEntry.STORED
        entry.size = size
        entry.compressedSize = size
        entry.crc = crc
        output.putArchiveEntry(entry)
        val copied = copyBounded(input, output)
        if (copied != size) {
            throw RuntimeContractException(
                "Generated runtime member $path copied $copied bytes instead of $size",
            )
        }
        output.closeArchiveEntry()
    }

    private fun deterministicEntry(
        path: String,
        isDirectory: Boolean,
    ): ZipArchiveEntry = ZipArchiveEntry(path).apply {
        setTimeLocal(reproducibleZipTime)
        unixMode = if (isDirectory) {
            UnixStat.DIR_FLAG or 493
        } else {
            UnixStat.FILE_FLAG or 420
        }
    }
}

private data class StreamHash(val sizeBytes: Long, val hex: String)

private fun sha256(input: InputStream): StreamHash {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(bufferSize)
    var size = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
        size += read
    }
    return StreamHash(
        size,
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) },
    )
}

private fun copyBounded(input: InputStream, output: OutputStream): Long {
    val buffer = ByteArray(bufferSize)
    var copied = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        copied += read
    }
    return copied
}
