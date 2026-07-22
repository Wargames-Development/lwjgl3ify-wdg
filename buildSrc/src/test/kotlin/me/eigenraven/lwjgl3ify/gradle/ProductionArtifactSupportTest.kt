package me.eigenraven.lwjgl3ify.gradle

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProductionArtifactSupportTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun candidateSelectionRejectsDevelopmentAndSourcesArtifacts() {
        listOf("mod-dev.jar", "mod-dev-preshadow.jar", "mod-sources.jar", "mod-api.jar").forEach { name ->
            val failure = runCatching { ProductionArtifactVerifier.validateCandidateName(name) }.exceptionOrNull()
            assertTrue("Expected rejection for $name", failure is IllegalStateException)
        }
        ProductionArtifactVerifier.validateCandidateName("lwjgl3ify-production.jar")
    }

    @Test
    fun correctProductionMappingAndBytecodeAreAccepted() {
        val fixture = fixture()
        val report = verify(fixture)
        assertEquals("func_147120_f", report.resolvedMethod)
        assertEquals("Lnet/minecraft/client/Minecraft;field_71431_Q:Z", report.resolvedField)
        assertEquals("bao", report.notchOwner)
        assertEquals("ag()V", report.notchMethod)
        assertEquals("a:Z", report.notchField)
    }

    @Test
    fun legitimateDependencyClassesWhoseNamesEndInTestAreAccepted() {
        val fixture = fixture(
            additionalEntries = setOf(
                "org/lwjgl/opengl/HPOcclusionTest.class",
                "org/lwjglx/opengl/EXTDepthBoundsTest.class",
            ),
        )
        verify(fixture)
    }

    @Test
    fun projectTestSmokeAndFixtureClassesAreRejected() {
        listOf(
            "me/eigenraven/lwjgl3ify/relauncher/RelauncherCommandTest.class",
            "me/eigenraven/lwjgl3ify/relauncher/RelauncherChildExitSmokeMain.class",
            "me/eigenraven/lwjgl3ify/relauncher/ProcessExitFixtureMain.class",
        ).forEach { entry ->
            assertFailure(fixture(additionalEntries = setOf(entry)), "forbidden development/runtime members")
        }
    }

    @Test
    fun duplicateRefmapIsRejected() {
        val fixture = fixture()
        val duplicateArtifact = File(fixture.artifact.parentFile, "lwjgl3ify-production-duplicate-refmap.jar")
        java.util.zip.ZipFile(fixture.artifact).use { source ->
            ZipArchiveOutputStream(duplicateArtifact).use { destination ->
                val entries = source.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    destination.putArchiveEntry(ZipArchiveEntry(entry.name))
                    if (!entry.isDirectory) source.getInputStream(entry).use { it.copyTo(destination) }
                    destination.closeArchiveEntry()
                }
                destination.putArchiveEntry(ZipArchiveEntry(PRODUCTION_REFMAP))
                source.getInputStream(source.getEntry(PRODUCTION_REFMAP)).use { it.copyTo(destination) }
                destination.closeArchiveEntry()
            }
        }
        assertFailure(fixture.copy(artifact = duplicateArtifact), "duplicate paths")
    }

    @Test
    fun missingMalformedAndMissingClassRefmapsAreRejected() {
        val missing = fixture(refmap = null)
        assertFailure(missing, "exactly one")

        val malformed = fixture(refmap = "{not-json")
        assertFailure(malformed, "Malformed")

        val missingClass = fixture(refmap = """{"mappings":{},"data":{"searge":{}}}""")
        assertFailure(missingClass, "lacks mappings")
    }

    @Test
    fun mcpOnlyAndIncorrectDescriptorsAreRejected() {
        val mcpOnly = fixture(fieldValue = "Lnet/minecraft/client/Minecraft;fullscreen:Z")
        assertFailure(mcpOnly, "field mapping is not production SRG")

        val wrongMethodDescriptor = fixture(methodValue = "func_147120_f()I")
        assertFailure(wrongMethodDescriptor, "method mapping is not production SRG")

        val wrongFieldDescriptor = fixture(fieldValue = "Lnet/minecraft/client/Minecraft;field_71431_Q:I")
        assertFailure(wrongFieldDescriptor, "field mapping is not production SRG")
    }


    @Test
    fun nonCanonicalRuntimeManifestIsRejected() {
        val fixture = fixture(artifactRuntimeManifestOverride = "{}")
        assertFailure(fixture, "runtime manifest differs from the canonical manifest")
    }

    @Test
    fun missingProductionTargetAndStaleGeneratedRefmapAreRejected() {
        val missingTarget = fixture(includeTargetField = false)
        assertFailure(missingTarget, "occurs 0 times")

        val stale = fixture(generatedRefmapOverride = """{"mappings":{}}""")
        assertFailure(stale, "differs from the current generated refmap")
    }

    private fun verify(fixture: Fixture): ProductionArtifactReport = ProductionArtifactVerifier.verify(
        fixture.artifact,
        fixture.mcpToSrg,
        fixture.notchToSrg,
        fixture.minecraft,
        fixture.generatedRefmap,
        fixture.canonicalRuntimeManifest,
    )

    private fun assertFailure(fixture: Fixture, message: String) {
        val failure = runCatching { verify(fixture) }.exceptionOrNull()
        assertTrue("Expected failure containing '$message', actual=$failure", failure?.message?.contains(message) == true)
    }

    private fun fixture(
        refmap: String? = refmapJson(
            "func_147120_f",
            "Lnet/minecraft/client/Minecraft;field_71431_Q:Z",
        ),
        methodValue: String = "func_147120_f",
        fieldValue: String = "Lnet/minecraft/client/Minecraft;field_71431_Q:Z",
        includeTargetField: Boolean = true,
        generatedRefmapOverride: String? = null,
        artifactRuntimeManifestOverride: String? = null,
        additionalEntries: Set<String> = emptySet(),
    ): Fixture {
        val root = temporary.newFolder()
        val mcpToSrg = File(root, "mcp-srg.srg").apply {
            writeText(
                """
                FD: net/minecraft/client/Minecraft/fullscreen net/minecraft/client/Minecraft/field_71431_Q
                MD: net/minecraft/client/Minecraft/func_147120_f ()V net/minecraft/client/Minecraft/func_147120_f ()V
                """.trimIndent() + "\n",
            )
        }
        val notchToSrg = File(root, "notch-srg.srg").apply {
            writeText(
                """
                FD: bao/a net/minecraft/client/Minecraft/field_71431_Q
                MD: bao/ag ()V net/minecraft/client/Minecraft/func_147120_f ()V
                """.trimIndent() + "\n",
            )
        }
        val actualRefmap = when {
            refmap == null -> null
            refmap.startsWith("{not-json") -> refmap
            else -> refmapJson(methodValue, fieldValue).takeIf { refmap.contains(DISPLAY_MIXIN_KEY) } ?: refmap
        }
        val generatedRefmap = File(root, "generated-refmap.json").apply {
            writeText(generatedRefmapOverride ?: actualRefmap ?: "{}")
        }
        val canonicalRuntimeManifest = File(root, "java21-runtime-manifest.json").apply {
            writeText(canonicalRuntimeManifestJson())
        }
        val minecraft = File(root, "client.jar")
        ZipOutputStream(minecraft.outputStream()).use { zip ->
            add(zip, "bao.class", minecraftClass(includeTargetField))
        }
        val artifact = File(root, "lwjgl3ify-production.jar")
        ZipOutputStream(artifact.outputStream()).use { zip ->
            requiredEntries().forEach { entry ->
                val bytes = if (entry == "me/eigenraven/lwjgl3ify/relauncher/runtime/java21-runtime-manifest.json") {
                    artifactRuntimeManifestOverride?.toByteArray(StandardCharsets.UTF_8)
                        ?: canonicalRuntimeManifest.readBytes()
                } else {
                    byteArrayOf(1)
                }
                add(zip, entry, bytes)
            }
            add(zip, DISPLAY_MIXIN_CLASS, displayMixinClass())
            additionalEntries.forEach { entry -> add(zip, entry, byteArrayOf(1)) }
            if (actualRefmap != null) add(zip, PRODUCTION_REFMAP, actualRefmap.toByteArray(StandardCharsets.UTF_8))
        }
        return Fixture(artifact, mcpToSrg, notchToSrg, minecraft, generatedRefmap, canonicalRuntimeManifest)
    }

    private fun displayMixinClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            DISPLAY_MIXIN_CLASS.removeSuffix(".class"),
            null,
            "java/lang/Object",
            null,
        )
        val method = writer.visitMethod(
            Opcodes.ACC_PRIVATE,
            "lwjgl3ify${'$'}alwaysUpdateScreenSize",
            "(Z)Z",
            null,
            null,
        )
        val injection = method.visitAnnotation(
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
            true,
        )
        injection.visit("method", "func_147120_f")
        val atArray = injection.visitArray("at")
        val at = atArray.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;")
        at.visit("value", "FIELD")
        at.visit("target", "Lnet/minecraft/client/Minecraft;fullscreen:Z")
        at.visitEnd()
        atArray.visitEnd()
        injection.visitEnd()
        method.visitCode()
        method.visitInsn(Opcodes.ICONST_0)
        method.visitInsn(Opcodes.IRETURN)
        method.visitMaxs(1, 2)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun minecraftClass(includeTargetField: Boolean): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_7, Opcodes.ACC_PUBLIC, "bao", null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PRIVATE, "a", "Z", null, null).visitEnd()
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC, "ag", "()V", null, null)
        method.visitCode()
        if (includeTargetField) {
            method.visitVarInsn(Opcodes.ALOAD, 0)
            method.visitFieldInsn(Opcodes.GETFIELD, "bao", "a", "Z")
            method.visitInsn(Opcodes.POP)
        }
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(1, 1)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun requiredEntries(): Set<String> = setOf(
        "me/eigenraven/lwjgl3ify/relauncher/Lwjgl3ifyRelauncherTweaker.class",
        "me/eigenraven/lwjgl3ify/relauncher/Relauncher.class",
        "me/eigenraven/lwjgl3ify/relauncher/ChildProcessSupervisor.class",
        "me/eigenraven/lwjgl3ify/relauncher/runtime/AutomaticRuntimeCoordinator.class",
        "me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeInstaller.class",
        "me/eigenraven/lwjgl3ify/relauncher/runtime/java21-runtime-manifest.json",
        "me/eigenraven/lwjgl3ify/relauncher/forgePatches.zip",
        "me/eigenraven/lwjgl3ify/relauncher/version.json",
        "me/eigenraven/lwjgl3ify/internal/commonscompress/Dummy.class",
        "me/eigenraven/lwjgl3ify/internal/commonsio/Dummy.class",
        "me/eigenraven/lwjgl3ify/internal/commonscodec/Dummy.class",
        "META-INF/rfb-plugin/lwjgl3ify.properties",
        "META-INF/MANIFEST.MF",
        "META-INF/licenses/Apache-2.0.txt",
        "META-INF/licenses/commons-compress-NOTICE.txt",
        "META-INF/licenses/commons-io-NOTICE.txt",
        "META-INF/licenses/commons-codec-NOTICE.txt",
        MAIN_MIXIN_CONFIG,
    )

    private fun add(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private data class Fixture(
        val artifact: File,
        val mcpToSrg: File,
        val notchToSrg: File,
        val minecraft: File,
        val generatedRefmap: File,
        val canonicalRuntimeManifest: File,
    )

    companion object {
        private fun canonicalRuntimeManifestJson(): String {
            var directory = File(System.getProperty("user.dir")).absoluteFile
            repeat(4) {
                val candidate = File(
                    directory,
                    "src/main/resources/me/eigenraven/lwjgl3ify/relauncher/runtime/java21-runtime-manifest.json",
                )
                if (candidate.isFile) return candidate.readText()
                directory = directory.parentFile ?: return@repeat
            }
            error("Could not locate the canonical Java runtime manifest from ${System.getProperty("user.dir")}")
        }

        private fun refmapJson(method: String, field: String): String {
            val entries = """"func_147120_f":"$method","Lnet/minecraft/client/Minecraft;fullscreen:Z":"$field""""
            return """{"mappings":{"$DISPLAY_MIXIN_KEY":{$entries}},"data":{"searge":{"$DISPLAY_MIXIN_KEY":{$entries}}}}"""
        }
    }
}
