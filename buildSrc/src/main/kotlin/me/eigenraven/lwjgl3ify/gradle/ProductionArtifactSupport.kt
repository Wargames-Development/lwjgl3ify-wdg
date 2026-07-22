package me.eigenraven.lwjgl3ify.gradle

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

internal const val PRODUCTION_REFMAP = "mixins.lwjgl3ify.refmap.json"
internal const val MAIN_MIXIN_CONFIG = "mixins.lwjgl3ify.json"
internal const val DISPLAY_MIXIN_CLASS =
    "me/eigenraven/lwjgl3ify/mixins/early/game/MixinMinecraft_Display.class"
internal const val DISPLAY_MIXIN_KEY =
    "me.eigenraven.lwjgl3ify.mixins.early.game.MixinMinecraft_Display"

@Serializable
private data class RefmapDocument(
    val mappings: Map<String, Map<String, String>> = emptyMap(),
    val data: Map<String, Map<String, Map<String, String>>> = emptyMap(),
)

internal data class ProductionArtifactReport(
    val artifactSha256: String,
    val artifactSize: Long,
    val refmapSha256: String,
    val resolvedMethod: String,
    val resolvedField: String,
    val notchOwner: String,
    val notchMethod: String,
    val notchField: String,
)

internal object ProductionArtifactVerifier {

    private val json = Json { ignoreUnknownKeys = true }

    fun verify(
        artifact: File,
        mcpToSrgFile: File,
        notchToSrgFile: File,
        productionMinecraftJar: File,
        generatedRefmapFile: File,
        canonicalRuntimeManifestFile: File,
    ): ProductionArtifactReport {
        validateCandidateName(artifact.name)
        requireFile(artifact, "Production mod artifact")
        requireFile(mcpToSrgFile, "MCP-to-SRG mapping")
        requireFile(notchToSrgFile, "Notch-to-SRG mapping")
        requireFile(productionMinecraftJar, "Production Minecraft client")
        requireFile(generatedRefmapFile, "Generated Mixin refmap")
        requireFile(canonicalRuntimeManifestFile, "Canonical Java runtime manifest")
        JavaRuntimeManifestIO.loadAndValidate(canonicalRuntimeManifestFile)

        val mcpToSrg = SrgMappings.parse(mcpToSrgFile.toPath())
        val notchToSrg = SrgMappings.parse(notchToSrgFile.toPath())
        val expectedMethod = mcpToSrg.findDestinationMethod(
            "net/minecraft/client/Minecraft",
            "func_147120_f",
            "()V",
        ) ?: error("MCP-to-SRG mappings do not define Minecraft.func_147120_f()V")
        val expectedField = mcpToSrg.findFieldBySource(
            "net/minecraft/client/Minecraft",
            "fullscreen",
        ) ?: error("MCP-to-SRG mappings do not define Minecraft.fullscreen")
        if (expectedField.destinationOwner != "net/minecraft/client/Minecraft") {
            error("Unexpected SRG owner for Minecraft.fullscreen: ${expectedField.destinationOwner}")
        }

        val notchMethod = notchToSrg.findSourceMethodByDestination(
            expectedMethod.destinationOwner,
            expectedMethod.destinationName,
            expectedMethod.destinationDescriptor,
        ) ?: error("Notch-to-SRG mappings do not define the production target for func_147120_f()V")
        val notchField = notchToSrg.findSourceFieldByDestination(
            expectedField.destinationOwner,
            expectedField.destinationName,
        ) ?: error("Notch-to-SRG mappings do not define the production target for fullscreen")
        if (notchMethod.sourceOwner != notchField.sourceOwner) {
            error(
                "Production method and field owners differ: " +
                    "${notchMethod.sourceOwner} vs ${notchField.sourceOwner}",
            )
        }

        val archive = inspectArchive(artifact)
        val generatedRefmapBytes = Files.readAllBytes(generatedRefmapFile.toPath())
        if (!archive.refmapBytes.contentEquals(generatedRefmapBytes)) {
            error(
                "Production artifact refmap differs from the current generated refmap: " +
                    "artifactSha256=${sha256(archive.refmapBytes)}, " +
                    "generatedSha256=${sha256(generatedRefmapBytes)}",
            )
        }
        val canonicalRuntimeManifestBytes = Files.readAllBytes(canonicalRuntimeManifestFile.toPath())
        if (!archive.runtimeManifestBytes.contentEquals(canonicalRuntimeManifestBytes)) {
            error(
                "Production artifact runtime manifest differs from the canonical manifest: " +
                    "artifactSha256=${sha256(archive.runtimeManifestBytes)}, " +
                    "canonicalSha256=${sha256(canonicalRuntimeManifestBytes)}, " +
                    "artifact=${artifact.absolutePath}",
            )
        }
        val mixinReferences = DisplayMixinReferences.read(archive.mixinClassBytes)
        val refmap = parseRefmap(archive.refmapBytes)
        val resolvedMethod = refmap.resolve(mixinReferences.methodReference)
        val resolvedField = refmap.resolve(mixinReferences.fieldReference)
        verifyResolvedMethod(
            resolvedMethod,
            expectedMethod,
            artifact,
        )
        verifyResolvedField(
            resolvedField,
            expectedField,
            artifact,
        )
        verifyProductionBytecode(
            productionMinecraftJar,
            notchMethod,
            notchField,
        )

        return ProductionArtifactReport(
            artifactSha256 = sha256(artifact.toPath()),
            artifactSize = Files.size(artifact.toPath()),
            refmapSha256 = sha256(archive.refmapBytes),
            resolvedMethod = resolvedMethod,
            resolvedField = resolvedField,
            notchOwner = notchMethod.sourceOwner,
            notchMethod = notchMethod.sourceName + notchMethod.sourceDescriptor,
            notchField = notchField.sourceName + ":Z",
        )
    }

    fun validateCandidateName(name: String) {
        val lower = name.lowercase(Locale.ROOT)
        val rejected = listOf("-dev.jar", "-dev-preshadow.jar", "-sources.jar", "-api.jar")
        if (rejected.any(lower::endsWith)) {
            error("Production artifact provider resolved a non-production JAR: $name")
        }
    }

    private fun inspectArchive(artifact: File): ArchiveContents {
        ZipFile(artifact).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val names = entries.map { it.name }
            val exactDuplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }
            if (exactDuplicates.isNotEmpty()) error("Production artifact contains duplicate paths: ${exactDuplicates.keys}")
            val caseDuplicates = names.groupBy { it.lowercase(Locale.ROOT) }
                .filterValues { values -> values.distinct().size > 1 }
            if (caseDuplicates.isNotEmpty()) {
                error("Production artifact contains case-folded path collisions: ${caseDuplicates.values}")
            }
            val refmaps = entries.filter { !it.isDirectory && it.name == PRODUCTION_REFMAP }
            if (refmaps.size != 1) {
                error("Production artifact must contain exactly one $PRODUCTION_REFMAP; found ${refmaps.size}")
            }
            val mixinConfigs = entries.filter { !it.isDirectory && it.name == MAIN_MIXIN_CONFIG }
            if (mixinConfigs.size != 1) {
                error("Production artifact must contain exactly one $MAIN_MIXIN_CONFIG; found ${mixinConfigs.size}")
            }

            val required = setOf(
                DISPLAY_MIXIN_CLASS,
                "me/eigenraven/lwjgl3ify/relauncher/Lwjgl3ifyRelauncherTweaker.class",
                "me/eigenraven/lwjgl3ify/relauncher/Relauncher.class",
                "me/eigenraven/lwjgl3ify/relauncher/ChildProcessSupervisor.class",
                "me/eigenraven/lwjgl3ify/relauncher/runtime/AutomaticRuntimeCoordinator.class",
                "me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeInstaller.class",
                "me/eigenraven/lwjgl3ify/relauncher/runtime/java21-runtime-manifest.json",
                "me/eigenraven/lwjgl3ify/relauncher/forgePatches.zip",
                "me/eigenraven/lwjgl3ify/relauncher/version.json",
                "META-INF/rfb-plugin/lwjgl3ify.properties",
                "META-INF/MANIFEST.MF",
                "META-INF/licenses/Apache-2.0.txt",
                "META-INF/licenses/commons-compress-NOTICE.txt",
                "META-INF/licenses/commons-io-NOTICE.txt",
                "META-INF/licenses/commons-codec-NOTICE.txt",
            )
            val missing = required.filter { zip.getEntry(it) == null }
            if (missing.isNotEmpty()) error("Production artifact is incomplete; missing: ${missing.joinToString()}")
            if (names.none { it.startsWith("me/eigenraven/lwjgl3ify/internal/commonscompress/") }) {
                error("Production artifact lacks relocated Commons Compress")
            }
            if (names.none { it.startsWith("me/eigenraven/lwjgl3ify/internal/commonsio/") }) {
                error("Production artifact lacks relocated Commons IO")
            }
            if (names.none { it.startsWith("me/eigenraven/lwjgl3ify/internal/commonscodec/") }) {
                error("Production artifact lacks relocated Commons Codec")
            }
            val forbidden = names.filter { name ->
                name.startsWith("runtimes/") ||
                    name.endsWith("java21-runtimes.zip") ||
                    name.contains("OpenJDK21U-jre") ||
                    name.contains("recompiled_minecraft") ||
                    name.startsWith("net/minecraft/client/") ||
                    name.startsWith("net/minecraftforge/gradle/") ||
                    name.contains("NotEnoughItems", ignoreCase = true) ||
                    name.contains("CodeChickenCore", ignoreCase = true) ||
                    name.contains("GTNHLib", ignoreCase = true) ||
                    name.contains("GTNHExtLib", ignoreCase = true) ||
                    (name.startsWith("me/eigenraven/lwjgl3ify/") && name.endsWith("Test.class")) ||
                    (name.startsWith("me/eigenraven/lwjgl3ify/") && name.endsWith("SmokeMain.class")) ||
                    (name.startsWith("me/eigenraven/lwjgl3ify/") && name.endsWith("FixtureMain.class")) ||
                    name.contains("/test-fixtures/") ||
                    name.contains("/fixtures/")
            }
            if (forbidden.isNotEmpty()) {
                error("Production artifact contains forbidden development/runtime members: ${forbidden.joinToString()}")
            }
            val refmapBytes = zip.getInputStream(refmaps.single()).use { it.readBytes() }
            val mixinBytes = zip.getInputStream(zip.getEntry(DISPLAY_MIXIN_CLASS)).use { it.readBytes() }
            val runtimeManifestBytes = zip.getInputStream(
                zip.getEntry("me/eigenraven/lwjgl3ify/relauncher/runtime/java21-runtime-manifest.json"),
            ).use { it.readBytes() }
            return ArchiveContents(refmapBytes, mixinBytes, runtimeManifestBytes)
        }
    }

    private fun parseRefmap(bytes: ByteArray): ResolvedRefmap {
        val document = try {
            json.decodeFromString<RefmapDocument>(bytes.toString(Charsets.UTF_8))
        } catch (exception: Exception) {
            error("Malformed $PRODUCTION_REFMAP: ${exception.message}")
        }
        val keys = listOf(DISPLAY_MIXIN_KEY, DISPLAY_MIXIN_KEY.replace('.', '/'))
        val base = keys.asSequence().mapNotNull(document.mappings::get).firstOrNull().orEmpty()
        val searge = document.data["searge"].orEmpty()
        val contextual = keys.asSequence().mapNotNull(searge::get).firstOrNull().orEmpty()
        if (base.isEmpty() && contextual.isEmpty()) {
            error("$PRODUCTION_REFMAP lacks mappings for $DISPLAY_MIXIN_KEY")
        }
        val merged = LinkedHashMap<String, String>()
        for (source in listOf(base, contextual)) {
            for ((key, value) in source) {
                val normalized = key.filterNot(Char::isWhitespace)
                val normalizedValue = value.filterNot(Char::isWhitespace)
                val previous = merged.put(normalized, normalizedValue)
                if (previous != null && previous != normalizedValue) {
                    error("Conflicting refmap values for $key: $previous vs $value")
                }
            }
        }
        return ResolvedRefmap(merged)
    }

    private fun verifyResolvedMethod(
        reference: String,
        expected: MethodMapping,
        artifact: File,
    ) {
        val normalized = reference.filterNot(Char::isWhitespace)
        val expectedBare = expected.destinationName
        val expectedDescriptor = expected.destinationName + expected.destinationDescriptor
        val expectedOwned = "L${expected.destinationOwner};$expectedDescriptor"
        if (normalized !in setOf(expectedBare, expectedDescriptor, expectedOwned)) {
            error(
                "MixinMinecraft_Display method mapping is not production SRG: " +
                    "expected=$expectedDescriptor, actual=$reference, artifact=${artifact.absolutePath}, " +
                    "refmap=$PRODUCTION_REFMAP, environment=searge",
            )
        }
    }

    private fun verifyResolvedField(
        reference: String,
        expected: FieldMapping,
        artifact: File,
    ) {
        val normalized = reference.filterNot(Char::isWhitespace)
        val expectedReference = "L${expected.destinationOwner};${expected.destinationName}:Z"
        if (normalized != expectedReference) {
            error(
                "MixinMinecraft_Display field mapping is not production SRG: " +
                    "expected=$expectedReference, actual=$reference, artifact=${artifact.absolutePath}, " +
                    "refmap=$PRODUCTION_REFMAP, environment=searge",
            )
        }
    }

    private fun verifyProductionBytecode(
        minecraftJar: File,
        notchMethod: MethodMapping,
        notchField: FieldMapping,
    ) {
        ZipFile(minecraftJar).use { zip ->
            val classEntry = zip.getEntry(notchMethod.sourceOwner + ".class")
                ?: error("Mapped production owner is absent: ${notchMethod.sourceOwner}")
            val bytes = zip.getInputStream(classEntry).use { it.readBytes() }
            var methods = 0
            var fieldTargets = 0
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (name != notchMethod.sourceName || descriptor != notchMethod.sourceDescriptor) return null
                    methods++
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                            if (owner == notchField.sourceOwner && name == notchField.sourceName && descriptor == "Z") {
                                fieldTargets++
                            }
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            if (methods != 1) {
                error(
                    "Mapped production method ${notchMethod.sourceOwner}.${notchMethod.sourceName}" +
                        "${notchMethod.sourceDescriptor} occurs $methods times",
                )
            }
            if (fieldTargets != 1) {
                error(
                    "Mapped fullscreen field ${notchField.sourceOwner}.${notchField.sourceName}:Z " +
                        "occurs $fieldTargets times in the production display-update method",
                )
            }
        }
    }

    private fun requireFile(file: File, role: String) {
        if (!file.isFile) error("$role is missing: $file")
    }
}

private data class ArchiveContents(
    val refmapBytes: ByteArray,
    val mixinClassBytes: ByteArray,
    val runtimeManifestBytes: ByteArray,
)

private data class ResolvedRefmap(val mappings: Map<String, String>) {
    fun resolve(reference: String): String {
        val normalized = reference.filterNot(Char::isWhitespace)
        return mappings[normalized] ?: normalized
    }
}

internal data class FieldMapping(
    val sourceOwner: String,
    val sourceName: String,
    val destinationOwner: String,
    val destinationName: String,
)

internal data class MethodMapping(
    val sourceOwner: String,
    val sourceName: String,
    val sourceDescriptor: String,
    val destinationOwner: String,
    val destinationName: String,
    val destinationDescriptor: String,
)

private data class SrgMappings(
    val fields: List<FieldMapping>,
    val methods: List<MethodMapping>,
) {
    fun findFieldBySource(owner: String, name: String) =
        fields.singleOrNull { it.sourceOwner == owner && it.sourceName == name }

    fun findDestinationMethod(owner: String, name: String, descriptor: String) =
        methods.singleOrNull {
            it.destinationOwner == owner &&
                it.destinationName == name &&
                it.destinationDescriptor == descriptor
        }

    fun findSourceFieldByDestination(owner: String, name: String) =
        fields.singleOrNull { it.destinationOwner == owner && it.destinationName == name }

    fun findSourceMethodByDestination(owner: String, name: String, descriptor: String) =
        methods.singleOrNull {
            it.destinationOwner == owner &&
                it.destinationName == name &&
                it.destinationDescriptor == descriptor
        }

    companion object {
        fun parse(path: Path): SrgMappings {
            val fields = ArrayList<FieldMapping>()
            val methods = ArrayList<MethodMapping>()
            Files.readAllLines(path, Charsets.UTF_8).forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val parts = line.split(Regex("\\s+"))
                when (parts.first()) {
                    "FD:" -> {
                        if (parts.size != 3) error("Malformed FD mapping at $path:${index + 1}: $line")
                        val source = splitMember(parts[1], path, index + 1)
                        val destination = splitMember(parts[2], path, index + 1)
                        fields += FieldMapping(source.first, source.second, destination.first, destination.second)
                    }
                    "MD:" -> {
                        if (parts.size != 5) error("Malformed MD mapping at $path:${index + 1}: $line")
                        val source = splitMember(parts[1], path, index + 1)
                        val destination = splitMember(parts[3], path, index + 1)
                        methods += MethodMapping(
                            source.first,
                            source.second,
                            parts[2],
                            destination.first,
                            destination.second,
                            parts[4],
                        )
                    }
                }
            }
            return SrgMappings(fields, methods)
        }

        private fun splitMember(value: String, path: Path, line: Int): Pair<String, String> {
            val separator = value.lastIndexOf('/')
            if (separator <= 0 || separator == value.lastIndex) {
                error("Malformed member mapping at $path:$line: $value")
            }
            return value.substring(0, separator) to value.substring(separator + 1)
        }
    }
}

private data class DisplayMixinReferences(val methodReference: String, val fieldReference: String) {
    companion object {
        fun read(bytes: ByteArray): DisplayMixinReferences {
            var methodReference: String? = null
            var fieldReference: String? = null
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                        if (descriptor != "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;") return null
                        fun fieldAtVisitor(): AnnotationVisitor {
                            var atValue: String? = null
                            var atTarget: String? = null
                            return object : AnnotationVisitor(Opcodes.ASM9) {
                                override fun visit(name: String?, value: Any?) {
                                    when {
                                        name == "value" && value is String -> atValue = value
                                        name == "target" && value is String -> atTarget = value
                                    }
                                }

                                override fun visitEnd() {
                                    if (atValue == "FIELD" && atTarget != null && fieldReference == null) {
                                        fieldReference = atTarget
                                    }
                                }
                            }
                        }

                        return object : AnnotationVisitor(Opcodes.ASM9) {
                            override fun visit(name: String?, value: Any?) {
                                if (name == "method" && value is String) methodReference = value
                            }

                            override fun visitArray(name: String?): AnnotationVisitor? {
                                if (name == "method") {
                                    return object : AnnotationVisitor(Opcodes.ASM9) {
                                        override fun visit(name: String?, value: Any?) {
                                            if (value is String && methodReference == null) methodReference = value
                                        }
                                    }
                                }
                                if (name == "at") {
                                    return object : AnnotationVisitor(Opcodes.ASM9) {
                                        override fun visitAnnotation(
                                            name: String?,
                                            descriptor: String?,
                                        ): AnnotationVisitor? {
                                            if (descriptor != "Lorg/spongepowered/asm/mixin/injection/At;") return null
                                            return fieldAtVisitor()
                                        }
                                    }
                                }
                                return null
                            }

                            override fun visitAnnotation(name: String?, descriptor: String?): AnnotationVisitor? {
                                if (name != "at" || descriptor != "Lorg/spongepowered/asm/mixin/injection/At;") return null
                                return fieldAtVisitor()
                            }
                        }
                    }
                }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            return DisplayMixinReferences(
                methodReference ?: error("MixinMinecraft_Display lacks a method selector"),
                fieldReference ?: error("MixinMinecraft_Display lacks a FIELD target"),
            )
        }
    }
}

internal fun sha256(path: Path): String = Files.newInputStream(path).use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1024 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
