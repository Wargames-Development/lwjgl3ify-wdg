package me.eigenraven.lwjgl3ify.relauncher.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.Assume;
import org.junit.Test;

public class RuntimeInstallerTest {

    private static final String ROOT = "fixture-jre";
    private static final String RELEASE = "IMPLEMENTOR=\"Fixture Vendor\"\n" + "IMPLEMENTOR_VERSION=\"Fixture-21\"\n"
        + "JAVA_VERSION=\"21.0.0\"\n"
        + "JAVA_RUNTIME_VERSION=\"21.0.0+1\"\n"
        + "OS_NAME=\"FixtureOS\"\n"
        + "OS_ARCH=\"fixture\"\n"
        + "JVM_VARIANT=\"Hotspot\"\n"
        + "LIBC=\"default\"\n";

    @Test
    public void canonicalManifestLoadsAndExposesSixPlatforms() throws Exception {
        RuntimeManifest manifest = RuntimeManifest.loadCanonical();
        assertEquals(
            6,
            manifest.getPlatforms()
                .size());
        assertEquals(
            "macos-aarch64",
            manifest.getPlatform("macos-aarch64")
                .getId());
        expectFailure("Unsupported", new ThrowingRunnable() {

            public void run() throws Exception {
                RuntimeManifest.loadCanonical()
                    .getPlatform("not-real");
            }
        });
    }

    @Test
    public void malformedDuplicateAndUnsafeManifestDataAreRejected() throws Exception {
        expectFailure("malformed", new ThrowingRunnable() {

            public void run() throws Exception {
                RuntimeManifest.parse("{".getBytes(StandardCharsets.UTF_8), false);
            }
        });
        final byte[] nested = windowsZip(Collections.<ArchiveItem>emptyList(), true, true, true);
        String json = manifestJson(
            "windows-fixture",
            "windows",
            "fixture",
            "zip",
            nested,
            "runtimes/windows-fixture.zip",
            ".",
            "bin/java.exe",
            "bin/javaw.exe");
        int platformStart = json.indexOf("    {");
        int platformEnd = json.lastIndexOf("  ]");
        String platformObject = json.substring(platformStart, platformEnd)
            .trim();
        final String duplicate = json.replace("\"supportedPlatformCount\": 1", "\"supportedPlatformCount\": 2")
            .replace(platformObject + "\n  ]", platformObject + ",\n" + platformObject + "\n  ]");
        expectFailure("Duplicate", new ThrowingRunnable() {

            public void run() throws Exception {
                RuntimeManifest.parse(duplicate.getBytes(StandardCharsets.UTF_8), false);
            }
        });
        final String unsafe = json.replace("runtimes/windows-fixture.zip", "../outside.zip");
        expectFailure("unsafe", new ThrowingRunnable() {

            public void run() throws Exception {
                RuntimeManifest.parse(unsafe.getBytes(StandardCharsets.UTF_8), false);
            }
        });
        final String unsafeIdentity = json.replace("\"runtimeFamily\": \"fixture-jre\"", "\"runtimeFamily\": \"..\"");
        expectFailure("unsafe", new ThrowingRunnable() {

            public void run() throws Exception {
                RuntimeManifest.parse(unsafeIdentity.getBytes(StandardCharsets.UTF_8), false);
            }
        });
        final String fractionalSize = json.replace("\"sizeBytes\": " + nested.length, "\"sizeBytes\": 1.5");
        expectFailure("integer", new ThrowingRunnable() {

            public void run() throws Exception {
                RuntimeManifest.parse(fractionalSize.getBytes(StandardCharsets.UTF_8), false);
            }
        });
    }

    @Test
    public void validWindowsFixtureInstallsThenReusesWithoutMarkerRewrite() throws Exception {
        Fixture fixture = windowsFixture(Collections.<ArchiveItem>emptyList(), true, true, true);
        RuntimeInstaller installer = new RuntimeInstaller(fixture.manifest);
        Path cache = Files.createTempDirectory("runtime-cache");
        try {
            RuntimeInstallResult first = installer.install(fixture.bundle, fixture.platformId, cache);
            assertTrue(first.wasInstalled());
            assertTrue(Files.isRegularFile(first.getJavaExecutable()));
            assertTrue(Files.isRegularFile(first.getWindowsGuiExecutable()));
            Path marker = first.getInstallationRoot()
                .resolve(RuntimeInstallMarker.FILE_NAME);
            FileTime markerTime = Files.getLastModifiedTime(marker);
            RuntimeInstallResult second = installer.install(fixture.bundle, fixture.platformId, cache);
            assertTrue(second.wasReused());
            assertEquals(first.getJavaHome(), second.getJavaHome());
            assertEquals(first.getJavaExecutable(), second.getJavaExecutable());
            assertEquals(markerTime, Files.getLastModifiedTime(marker));
        } finally {
            deleteTemp(cache);
            Files.deleteIfExists(fixture.bundle);
        }
    }

    @Test
    public void validUnixFixturePreservesExecutableAndSafeSymlink() throws Exception {
        Path symlinkProbe = Files.createTempDirectory("symlink-probe");
        boolean posix = Files.getFileStore(symlinkProbe)
            .supportsFileAttributeView("posix");
        Files.delete(symlinkProbe);
        Assume.assumeTrue(posix);
        List<ArchiveItem> items = Arrays.asList(
            ArchiveItem.directory(ROOT + "/legal/java.base", 0755),
            ArchiveItem.file(ROOT + "/legal/java.base/LICENSE", "license", 0444),
            ArchiveItem.directory(ROOT + "/legal/other", 0755),
            ArchiveItem.symlink(ROOT + "/legal/other/LICENSE", "../java.base/LICENSE"));
        Fixture fixture = unixFixture(items, true, true);
        Path cache = Files.createTempDirectory("runtime-cache");
        try {
            RuntimeInstallResult result = new RuntimeInstaller(fixture.manifest)
                .install(fixture.bundle, fixture.platformId, cache);
            assertTrue(Files.isExecutable(result.getJavaExecutable()));
            Path link = result.getRuntimeArchiveRoot()
                .resolve("legal/other/LICENSE");
            assertTrue(Files.isSymbolicLink(link));
            assertEquals("license", new String(Files.readAllBytes(link), StandardCharsets.UTF_8));
        } finally {
            deleteTemp(cache);
            Files.deleteIfExists(fixture.bundle);
        }
    }

    @Test
    public void bundleMissingManifestMismatchMissingArchiveAndUnexpectedPayloadAreRejected() throws Exception {
        Fixture fixture = windowsFixture(Collections.<ArchiveItem>emptyList(), true, true, true);
        Path cache = Files.createTempDirectory("runtime-cache");
        try {
            assertBundleFailure(fixture, cache, BundleMutation.MISSING_MANIFEST, "missing");
            assertBundleFailure(fixture, cache, BundleMutation.MISMATCH_MANIFEST, "manifest");
            assertBundleFailure(fixture, cache, BundleMutation.MISSING_ARCHIVE, "missing");
            assertBundleFailure(fixture, cache, BundleMutation.UNEXPECTED_PAYLOAD, "unexpected");
            assertBundleFailure(fixture, cache, BundleMutation.CASE_COLLISION, "case");
            assertBundleFailure(fixture, cache, BundleMutation.DUPLICATE_ARCHIVE, "duplicate");
        } finally {
            deleteTemp(cache);
            Files.deleteIfExists(fixture.bundle);
        }
    }

    @Test
    public void bundleSizeAndHashMismatchAreRejected() throws Exception {
        byte[] nested = windowsZip(Collections.<ArchiveItem>emptyList(), true, true, true);
        String json = manifestJson(
            "windows-fixture",
            "windows",
            "fixture",
            "zip",
            nested,
            "runtimes/windows-fixture.zip",
            ".",
            "bin/java.exe",
            "bin/javaw.exe");
        byte[] wrongSizeManifest = json
            .replace("\"sizeBytes\": " + nested.length, "\"sizeBytes\": " + (nested.length + 1))
            .getBytes(StandardCharsets.UTF_8);
        RuntimeManifest wrongSize = RuntimeManifest.parse(wrongSizeManifest, false);
        Path wrongSizeBundle = normalizedBundle(
            wrongSizeManifest,
            nested,
            "runtimes/windows-fixture.zip",
            BundleMutation.NONE);
        final Path wrongSizeCache = Files.createTempDirectory("runtime-cache");
        try {
            expectFailure("size", new ThrowingRunnable() {

                public void run() throws Exception {
                    new RuntimeInstaller(wrongSize).install(wrongSizeBundle, "windows-fixture", wrongSizeCache);
                }
            });
        } finally {
            Files.deleteIfExists(wrongSizeBundle);
            deleteTemp(wrongSizeCache);
        }

        String wrongHashJson = json.replace(sha256(nested), repeat('0', 64));
        RuntimeManifest wrongHash = RuntimeManifest.parse(wrongHashJson.getBytes(StandardCharsets.UTF_8), false);
        Path wrongHashBundle = normalizedBundle(
            wrongHashJson.getBytes(StandardCharsets.UTF_8),
            nested,
            "runtimes/windows-fixture.zip",
            BundleMutation.NONE);
        final Path wrongHashCache = Files.createTempDirectory("runtime-cache");
        try {
            expectFailure("SHA-256", new ThrowingRunnable() {

                public void run() throws Exception {
                    new RuntimeInstaller(wrongHash).install(wrongHashBundle, "windows-fixture", wrongHashCache);
                }
            });
        } finally {
            Files.deleteIfExists(wrongHashBundle);
            deleteTemp(wrongHashCache);
        }
    }

    @Test
    public void zipTraversalAbsoluteDriveUncDuplicateCaseAndMultipleRootsAreRejected() throws Exception {
        List<List<ArchiveItem>> cases = Arrays.asList(
            Arrays.asList(ArchiveItem.file("../escape", "x", 0644)),
            Arrays.asList(ArchiveItem.file("/absolute", "x", 0644)),
            Arrays.asList(ArchiveItem.file("C:/drive", "x", 0644)),
            Arrays.asList(ArchiveItem.file("\\\\server\\share", "x", 0644)),
            Arrays.asList(ArchiveItem.file(ROOT + "/dup", "x", 0644), ArchiveItem.file(ROOT + "/dup", "y", 0644)),
            Arrays.asList(ArchiveItem.file(ROOT + "/Case", "x", 0644), ArchiveItem.file(ROOT + "/case", "y", 0644)),
            Arrays.asList(ArchiveItem.file("other-root/file", "x", 0644)));
        for (List<ArchiveItem> additions : cases) assertWindowsArchiveRejected(additions, true, true, true);
    }

    @Test
    public void windowsMissingJavaJavawAndMalformedReleaseAreRejected() throws Exception {
        assertWindowsArchiveRejected(Collections.<ArchiveItem>emptyList(), false, true, true);
        assertWindowsArchiveRejected(Collections.<ArchiveItem>emptyList(), true, false, true);
        assertWindowsArchiveRejected(Collections.<ArchiveItem>emptyList(), true, true, false);
    }

    @Test
    public void tarAbsoluteEscapingSymlinkHardLinkFifoTraversalMultipleRootsAndMissingJavaAreRejected()
        throws Exception {
        List<List<ArchiveItem>> cases = Arrays.asList(
            Arrays.asList(ArchiveItem.symlink(ROOT + "/bad", "/etc/passwd")),
            Arrays.asList(ArchiveItem.symlink(ROOT + "/bad", "../../outside")),
            Arrays.asList(ArchiveItem.hardlink(ROOT + "/bad", "../../outside")),
            Arrays.asList(ArchiveItem.fifo(ROOT + "/pipe")),
            Arrays.asList(ArchiveItem.file("../escape", "x", 0644)),
            Arrays.asList(ArchiveItem.file("another-root/file", "x", 0644)));
        for (List<ArchiveItem> additions : cases) assertUnixArchiveRejected(additions, true, true);
        assertUnixArchiveRejected(Collections.<ArchiveItem>emptyList(), false, true);
    }

    @Test
    public void invalidMarkerAndMissingExecutableTriggerReplacement() throws Exception {
        Fixture fixture = windowsFixture(Collections.<ArchiveItem>emptyList(), true, true, true);
        RuntimeInstaller installer = new RuntimeInstaller(fixture.manifest);
        Path cache = Files.createTempDirectory("runtime-cache");
        try {
            RuntimeInstallResult first = installer.install(fixture.bundle, fixture.platformId, cache);
            Files.write(
                first.getInstallationRoot()
                    .resolve(RuntimeInstallMarker.FILE_NAME),
                "not-json".getBytes(StandardCharsets.UTF_8));
            RuntimeInstallResult replacedMarker = installer.install(fixture.bundle, fixture.platformId, cache);
            assertTrue(replacedMarker.wasInstalled());
            Files.delete(replacedMarker.getJavaExecutable());
            RuntimeInstallResult replacedExecutable = installer.install(fixture.bundle, fixture.platformId, cache);
            assertTrue(replacedExecutable.wasInstalled());
            assertTrue(Files.isRegularFile(replacedExecutable.getJavaExecutable()));
        } finally {
            deleteTemp(cache);
            Files.deleteIfExists(fixture.bundle);
        }
    }

    @Test
    public void failedExtractionLeavesNoFinalStagingOrTemporaryArchive() throws Exception {
        Fixture fixture = windowsFixture(Arrays.asList(ArchiveItem.file("../escape", "x", 0644)), true, true, true);
        Path cache = Files.createTempDirectory("runtime-cache");
        try {
            expectFailure("unsafe", new ThrowingRunnable() {

                public void run() throws Exception {
                    new RuntimeInstaller(fixture.manifest).install(fixture.bundle, fixture.platformId, cache);
                }
            });
            RuntimeCacheLayout layout = new RuntimeCacheLayout(
                cache,
                fixture.manifest,
                fixture.manifest.getPlatform(fixture.platformId));
            assertFalse(Files.exists(layout.getInstallationRoot(), LinkOption.NOFOLLOW_LINKS));
            if (Files.isDirectory(
                layout.getInstallationRoot()
                    .getParent())) {
                for (Path child : Files.newDirectoryStream(
                    layout.getInstallationRoot()
                        .getParent())) {
                    String name = child.getFileName()
                        .toString();
                    assertFalse(name.contains(".staging-"));
                    assertFalse(name.contains(".archive-"));
                }
            }
        } finally {
            deleteTemp(cache);
            Files.deleteIfExists(fixture.bundle);
        }
    }

    @Test
    public void neighbouringRuntimeIsUntouchedAndDeletionRefusesOutsideCache() throws Exception {
        Fixture fixture = windowsFixture(Collections.<ArchiveItem>emptyList(), true, true, true);
        Path cache = Files.createTempDirectory("runtime-cache");
        Path neighbour = cache.resolve("java-runtimes/other/version/platform/hash/keep.txt");
        Files.createDirectories(neighbour.getParent());
        Files.write(neighbour, "keep".getBytes(StandardCharsets.UTF_8));
        try {
            new RuntimeInstaller(fixture.manifest).install(fixture.bundle, fixture.platformId, cache);
            assertEquals("keep", new String(Files.readAllBytes(neighbour), StandardCharsets.UTF_8));
            Path outside = Files.createTempFile("outside-runtime-cache", ".txt");
            try {
                expectFailure("outside", new ThrowingRunnable() {

                    public void run() throws Exception {
                        RuntimePathSafety.deleteRecursively(outside, cache.resolve("java-runtimes"));
                    }
                });
                assertTrue(Files.exists(outside));
            } finally {
                Files.deleteIfExists(outside);
            }

            Path redirectedCache = Files.createTempDirectory("runtime-cache-redirected");
            Path redirectedOutside = Files.createTempDirectory("runtime-cache-outside");
            try {
                Path runtimes = redirectedCache.resolve("java-runtimes");
                boolean linkCreated = false;
                try {
                    Files.createSymbolicLink(runtimes, redirectedOutside);
                    linkCreated = true;
                } catch (UnsupportedOperationException exception) {
                    // Symbolic links are not available on every test filesystem.
                } catch (SecurityException exception) {
                    // Windows may require an elevated symbolic-link privilege.
                } catch (IOException exception) {
                    // Treat unavailable symbolic-link privileges as an unsupported fixture.
                }
                if (linkCreated) {
                    expectFailure("safe directory", new ThrowingRunnable() {

                        public void run() throws Exception {
                            new RuntimeInstaller(fixture.manifest)
                                .install(fixture.bundle, fixture.platformId, redirectedCache);
                        }
                    });
                    java.nio.file.DirectoryStream<Path> outsideEntries = Files.newDirectoryStream(redirectedOutside);
                    try {
                        assertFalse(
                            outsideEntries.iterator()
                                .hasNext());
                    } finally {
                        outsideEntries.close();
                    }
                }
            } finally {
                deleteTemp(redirectedCache);
                deleteTemp(redirectedOutside);
            }
        } finally {
            deleteTemp(cache);
            Files.deleteIfExists(fixture.bundle);
        }
    }

    @Test
    public void concurrentInstallersProduceOneFinalInstallAndSameJavaPath() throws Exception {
        final Fixture fixture = windowsFixture(Collections.<ArchiveItem>emptyList(), true, true, true);
        final Path cache = Files.createTempDirectory("runtime-cache");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<RuntimeInstallResult> call = new Callable<RuntimeInstallResult>() {

                public RuntimeInstallResult call() throws Exception {
                    start.await();
                    return new RuntimeInstaller(fixture.manifest).install(fixture.bundle, fixture.platformId, cache);
                }
            };
            Future<RuntimeInstallResult> firstFuture = executor.submit(call);
            Future<RuntimeInstallResult> secondFuture = executor.submit(call);
            start.countDown();
            RuntimeInstallResult first = firstFuture.get();
            RuntimeInstallResult second = secondFuture.get();
            assertEquals(first.getJavaExecutable(), second.getJavaExecutable());
            assertTrue(first.wasInstalled() ^ second.wasInstalled());
            assertTrue(
                Files.isRegularFile(
                    first.getInstallationRoot()
                        .resolve(RuntimeInstallMarker.FILE_NAME)));
        } finally {
            executor.shutdownNow();
            deleteTemp(cache);
            Files.deleteIfExists(fixture.bundle);
        }
    }

    @Test
    public void selectedArchiveIsStreamedToTemporaryFile() throws Exception {
        byte[] padding = new byte[8 * 1024 * 1024];
        Arrays.fill(padding, (byte) 7);
        Fixture fixture = windowsFixture(
            Arrays.asList(ArchiveItem.file(ROOT + "/lib/padding.bin", padding, 0644)),
            true,
            true,
            true);
        Path cache = Files.createTempDirectory("runtime-cache");
        try {
            RuntimeInstallResult result = new RuntimeInstaller(fixture.manifest)
                .install(fixture.bundle, fixture.platformId, cache);
            assertTrue(
                Files.size(
                    result.getRuntimeArchiveRoot()
                        .resolve("lib/padding.bin"))
                    == padding.length);
        } finally {
            deleteTemp(cache);
            Files.deleteIfExists(fixture.bundle);
        }
    }

    private static void assertBundleFailure(Fixture fixture, Path cache, BundleMutation mutation, String message)
        throws Exception {
        Path bundle = normalizedBundle(
            fixture.manifestBytes,
            fixture.nestedArchive,
            fixture.manifest.getPlatform(fixture.platformId)
                .getNormalizedBundlePath(),
            mutation);
        try {
            expectFailure(message, new ThrowingRunnable() {

                public void run() throws Exception {
                    new RuntimeInstaller(fixture.manifest).install(bundle, fixture.platformId, cache);
                }
            });
        } finally {
            Files.deleteIfExists(bundle);
        }
    }

    private static void assertWindowsArchiveRejected(List<ArchiveItem> additions, boolean java, boolean javaw,
        boolean release) throws Exception {
        Fixture fixture = windowsFixture(additions, java, javaw, release);
        Path cache = Files.createTempDirectory("runtime-cache");
        try {
            expectFailure("", new ThrowingRunnable() {

                public void run() throws Exception {
                    new RuntimeInstaller(fixture.manifest).install(fixture.bundle, fixture.platformId, cache);
                }
            });
        } finally {
            deleteTemp(cache);
            Files.deleteIfExists(fixture.bundle);
        }
    }

    private static void assertUnixArchiveRejected(List<ArchiveItem> additions, boolean java, boolean release)
        throws Exception {
        Fixture fixture = unixFixture(additions, java, release);
        Path cache = Files.createTempDirectory("runtime-cache");
        try {
            expectFailure("", new ThrowingRunnable() {

                public void run() throws Exception {
                    new RuntimeInstaller(fixture.manifest).install(fixture.bundle, fixture.platformId, cache);
                }
            });
        } finally {
            deleteTemp(cache);
            Files.deleteIfExists(fixture.bundle);
        }
    }

    private static Fixture windowsFixture(List<ArchiveItem> additions, boolean includeJava, boolean includeJavaw,
        boolean goodRelease) throws Exception {
        byte[] nested = windowsZip(additions, includeJava, includeJavaw, goodRelease);
        String platform = "windows-fixture";
        String path = "runtimes/windows-fixture.zip";
        byte[] manifestBytes = manifestJson(
            platform,
            "windows",
            "fixture",
            "zip",
            nested,
            path,
            ".",
            "bin/java.exe",
            "bin/javaw.exe").getBytes(StandardCharsets.UTF_8);
        RuntimeManifest manifest = RuntimeManifest.parse(manifestBytes, false);
        return new Fixture(
            platform,
            manifest,
            manifestBytes,
            nested,
            normalizedBundle(manifestBytes, nested, path, BundleMutation.NONE));
    }

    private static Fixture unixFixture(List<ArchiveItem> additions, boolean includeJava, boolean goodRelease)
        throws Exception {
        byte[] nested = unixTar(additions, includeJava, goodRelease);
        String platform = "linux-fixture";
        String path = "runtimes/linux-fixture.tar.gz";
        byte[] manifestBytes = manifestJson(platform, "linux", "fixture", "tar.gz", nested, path, ".", "bin/java", null)
            .getBytes(StandardCharsets.UTF_8);
        RuntimeManifest manifest = RuntimeManifest.parse(manifestBytes, false);
        return new Fixture(
            platform,
            manifest,
            manifestBytes,
            nested,
            normalizedBundle(manifestBytes, nested, path, BundleMutation.NONE));
    }

    private static byte[] windowsZip(List<ArchiveItem> additions, boolean includeJava, boolean includeJavaw,
        boolean goodRelease) throws IOException {
        List<ArchiveItem> items = new ArrayList<ArchiveItem>();
        items.add(ArchiveItem.directory(ROOT, 0755));
        items.add(ArchiveItem.directory(ROOT + "/bin", 0755));
        if (includeJava) items.add(ArchiveItem.file(ROOT + "/bin/java.exe", "java", 0755));
        if (includeJavaw) items.add(ArchiveItem.file(ROOT + "/bin/javaw.exe", "javaw", 0755));
        items.add(ArchiveItem.file(ROOT + "/release", goodRelease ? RELEASE : "BROKEN", 0644));
        items.addAll(additions);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipArchiveOutputStream zip = new ZipArchiveOutputStream(bytes);
        zip.setUseZip64(Zip64Mode.AsNeeded);
        try {
            for (ArchiveItem item : items) {
                ZipArchiveEntry entry = new ZipArchiveEntry(item.directory ? ensureSlash(item.name) : item.name);
                entry.setUnixMode((item.directory ? 040000 : 0100000) | item.mode);
                zip.putArchiveEntry(entry);
                if (!item.directory) zip.write(item.data);
                zip.closeArchiveEntry();
            }
            zip.finish();
        } finally {
            zip.close();
        }
        return bytes.toByteArray();
    }

    private static byte[] unixTar(List<ArchiveItem> additions, boolean includeJava, boolean goodRelease)
        throws IOException {
        List<ArchiveItem> items = new ArrayList<ArchiveItem>();
        items.add(ArchiveItem.directory(ROOT, 0755));
        items.add(ArchiveItem.directory(ROOT + "/bin", 0755));
        if (includeJava) items.add(ArchiveItem.file(ROOT + "/bin/java", "java", 0755));
        items.add(ArchiveItem.file(ROOT + "/release", goodRelease ? RELEASE : "BROKEN", 0644));
        items.addAll(additions);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip);
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        try {
            for (ArchiveItem item : items) {
                TarArchiveEntry entry;
                if (item.symlink) {
                    entry = new TarArchiveEntry(item.name, TarConstants.LF_SYMLINK);
                    entry.setLinkName(item.target);
                } else if (item.hardlink) {
                    entry = new TarArchiveEntry(item.name, TarConstants.LF_LINK);
                    entry.setLinkName(item.target);
                } else if (item.fifo) {
                    entry = new TarArchiveEntry(item.name, TarConstants.LF_FIFO);
                } else {
                    entry = new TarArchiveEntry(item.directory ? ensureSlash(item.name) : item.name);
                    entry.setSize(item.directory ? 0 : item.data.length);
                }
                entry.setMode(item.mode);
                tar.putArchiveEntry(entry);
                if (!item.directory && !item.symlink && !item.hardlink && !item.fifo) tar.write(item.data);
                tar.closeArchiveEntry();
            }
            tar.finish();
        } finally {
            tar.close();
        }
        return bytes.toByteArray();
    }

    private static Path normalizedBundle(byte[] manifest, byte[] nested, String nestedPath, BundleMutation mutation)
        throws IOException {
        String top = "fixture-bundle";
        Path output = Files.createTempFile("normalized-runtime-bundle", ".zip");
        ZipArchiveOutputStream zip = new ZipArchiveOutputStream(output.toFile());
        zip.setUseZip64(Zip64Mode.AsNeeded);
        try {
            putStored(zip, top + "/", new byte[0], true);
            if (mutation != BundleMutation.MISSING_MANIFEST) {
                putStored(
                    zip,
                    top + "/manifest.json",
                    mutation == BundleMutation.MISMATCH_MANIFEST ? "{}".getBytes(StandardCharsets.UTF_8) : manifest,
                    false);
            }
            putStored(zip, top + "/runtimes/", new byte[0], true);
            if (mutation != BundleMutation.MISSING_ARCHIVE) {
                putStored(zip, top + "/" + nestedPath, nested, false);
                if (mutation == BundleMutation.DUPLICATE_ARCHIVE) putStored(zip, top + "/" + nestedPath, nested, false);
            }
            if (mutation == BundleMutation.UNEXPECTED_PAYLOAD)
                putStored(zip, top + "/unexpected.txt", new byte[] { 1 }, false);
            if (mutation == BundleMutation.CASE_COLLISION)
                putStored(zip, top.toUpperCase(Locale.ROOT) + "/manifest.json", manifest, false);
            zip.finish();
        } finally {
            zip.close();
        }
        return output;
    }

    private static void putStored(ZipArchiveOutputStream zip, String name, byte[] bytes, boolean directory)
        throws IOException {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        entry.setCrc(crc.getValue());
        entry.setUnixMode((directory ? 040000 : 0100000) | (directory ? 0755 : 0644));
        zip.putArchiveEntry(entry);
        if (bytes.length > 0) zip.write(bytes);
        zip.closeArchiveEntry();
    }

    private static String manifestJson(String platformId, String os, String arch, String archiveType, byte[] nested,
        String normalizedPath, String javaHome, String javaExecutable, String gui) throws Exception {
        String guiField = gui == null ? "" : ",\n      \"windowsGuiExecutableRelativePath\": \"" + gui + "\"";
        return "{\n" + "  \"schemaVersion\": 1,\n"
            + "  \"runtimeFamily\": \"fixture-jre\",\n"
            + "  \"vendor\": \"Fixture Vendor\",\n"
            + "  \"implementor\": \"Fixture Vendor\",\n"
            + "  \"distribution\": \"Fixture\",\n"
            + "  \"implementorVersion\": \"Fixture-21\",\n"
            + "  \"javaFeatureVersion\": 21,\n"
            + "  \"javaVersion\": \"21.0.0\",\n"
            + "  \"javaRuntimeVersion\": \"21.0.0+1\",\n"
            + "  \"jvmVariant\": \"Hotspot\",\n"
            + "  \"archiveRoot\": \""
            + ROOT
            + "\",\n"
            + "  \"supportedPlatformCount\": 1,\n"
            + "  \"platforms\": [\n"
            + "    {\n"
            + "      \"id\": \""
            + platformId
            + "\",\n"
            + "      \"operatingSystem\": \""
            + os
            + "\",\n"
            + "      \"architecture\": \""
            + arch
            + "\",\n"
            + ("linux".equals(os) ? "      \"libc\": \"gnu\",\n" : "")
            + "      \"normalizedBundlePath\": \""
            + normalizedPath
            + "\",\n"
            + "      \"archiveType\": \""
            + archiveType
            + "\",\n"
            + "      \"sizeBytes\": "
            + nested.length
            + ",\n"
            + "      \"sha256\": \""
            + sha256(nested)
            + "\",\n"
            + "      \"archiveRoot\": \""
            + ROOT
            + "\",\n"
            + "      \"javaHomeRelativePath\": \""
            + javaHome
            + "\",\n"
            + "      \"javaExecutableRelativePath\": \""
            + javaExecutable
            + "\""
            + guiField
            + ",\n"
            + "      \"releaseFileRelativePath\": \"release\",\n"
            + "      \"expectedReleaseProperties\": {\n"
            + "        \"IMPLEMENTOR\": \"Fixture Vendor\",\n"
            + "        \"IMPLEMENTOR_VERSION\": \"Fixture-21\",\n"
            + "        \"JAVA_VERSION\": \"21.0.0\",\n"
            + "        \"JAVA_RUNTIME_VERSION\": \"21.0.0+1\",\n"
            + "        \"OS_NAME\": \"FixtureOS\",\n"
            + "        \"OS_ARCH\": \"fixture\",\n"
            + "        \"JVM_VARIANT\": \"Hotspot\",\n"
            + "        \"LIBC\": \"default\"\n"
            + "      }\n"
            + "    }\n"
            + "  ]\n"
            + "}\n";
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static void expectFailure(String fragment, ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            fail("Expected RuntimeInstallationException");
        } catch (RuntimeInstallationException expected) {
            if (!fragment.isEmpty()) {
                assertTrue(
                    "Expected message containing '" + fragment + "' but was: " + expected.getMessage(),
                    expected.getMessage()
                        .toLowerCase(Locale.ROOT)
                        .contains(fragment.toLowerCase(Locale.ROOT)));
            }
        }
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static String ensureSlash(String value) {
        return value.endsWith("/") ? value : value + "/";
    }

    private static void deleteTemp(Path path) throws Exception {
        if (path != null && Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Path allowed = path.getParent();
            RuntimePathSafety.deleteRecursively(path, allowed);
        }
    }

    private interface ThrowingRunnable {

        void run() throws Exception;
    }

    private enum BundleMutation {
        NONE,
        MISSING_MANIFEST,
        MISMATCH_MANIFEST,
        MISSING_ARCHIVE,
        UNEXPECTED_PAYLOAD,
        CASE_COLLISION,
        DUPLICATE_ARCHIVE
    }

    private static final class Fixture {

        final String platformId;
        final RuntimeManifest manifest;
        final byte[] manifestBytes;
        final byte[] nestedArchive;
        final Path bundle;

        Fixture(String platformId, RuntimeManifest manifest, byte[] manifestBytes, byte[] nestedArchive, Path bundle) {
            this.platformId = platformId;
            this.manifest = manifest;
            this.manifestBytes = manifestBytes;
            this.nestedArchive = nestedArchive;
            this.bundle = bundle;
        }
    }

    private static final class ArchiveItem {

        final String name;
        final byte[] data;
        final int mode;
        final boolean directory;
        final boolean symlink;
        final boolean hardlink;
        final boolean fifo;
        final String target;

        private ArchiveItem(String name, byte[] data, int mode, boolean directory, boolean symlink, boolean hardlink,
            boolean fifo, String target) {
            this.name = name;
            this.data = data;
            this.mode = mode;
            this.directory = directory;
            this.symlink = symlink;
            this.hardlink = hardlink;
            this.fifo = fifo;
            this.target = target;
        }

        static ArchiveItem directory(String name, int mode) {
            return new ArchiveItem(name, new byte[0], mode, true, false, false, false, null);
        }

        static ArchiveItem file(String name, String data, int mode) {
            return file(name, data.getBytes(StandardCharsets.UTF_8), mode);
        }

        static ArchiveItem file(String name, byte[] data, int mode) {
            return new ArchiveItem(name, data, mode, false, false, false, false, null);
        }

        static ArchiveItem symlink(String name, String target) {
            return new ArchiveItem(name, new byte[0], 0777, false, true, false, false, target);
        }

        static ArchiveItem hardlink(String name, String target) {
            return new ArchiveItem(name, new byte[0], 0777, false, false, true, false, target);
        }

        static ArchiveItem fifo(String name) {
            return new ArchiveItem(name, new byte[0], 0644, false, false, false, true, null);
        }
    }
}
