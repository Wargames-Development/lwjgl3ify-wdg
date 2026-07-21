package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Verifies the normalized outer bundle and streams one pinned nested archive to disk. */
final class RuntimeBundleReader {

    private static final int BUFFER_SIZE = 1024 * 1024;

    void verifyAndCopySelectedArchive(Path bundlePath, RuntimeManifest manifest, RuntimePlatform selected,
        Path destination) throws IOException, RuntimeInstallationException {
        if (!Files.isRegularFile(bundlePath)) {
            throw new RuntimeInstallationException(
                "Normalized Java runtime bundle is not a readable file: " + bundlePath);
        }
        ZipFile zip;
        try {
            zip = new ZipFile(bundlePath.toFile());
        } catch (IOException exception) {
            throw new RuntimeInstallationException("Normalized Java runtime bundle is not a readable ZIP", exception);
        }
        try {
            List<? extends ZipEntry> entries = entries(zip);
            if (entries.isEmpty()) throw new RuntimeInstallationException("Normalized Java runtime bundle is empty");
            Map<String, Integer> counts = new HashMap<String, Integer>();
            Set<String> caseNames = new HashSet<String>();
            String topLevel = null;
            for (ZipEntry entry : entries) {
                String name = normalizeBundleName(entry.getName());
                Integer count = counts.get(name);
                counts.put(name, count == null ? 1 : count + 1);
                if (!caseNames.add(name.toLowerCase(Locale.ROOT))) {
                    throw new RuntimeInstallationException(
                        "Normalized bundle contains duplicate or case-colliding path: " + name);
                }
                int slash = name.indexOf('/');
                String currentTop = slash < 0 ? name : name.substring(0, slash);
                if (topLevel == null) topLevel = currentTop;
                if (!topLevel.equals(currentTop)) {
                    throw new RuntimeInstallationException("Normalized bundle contains multiple top-level roots");
                }
            }
            for (Map.Entry<String, Integer> count : counts.entrySet()) {
                if (count.getValue()
                    .intValue() != 1) {
                    throw new RuntimeInstallationException(
                        "Normalized bundle contains duplicate ZIP member: " + count.getKey());
                }
            }
            if (topLevel == null || topLevel.isEmpty()) {
                throw new RuntimeInstallationException("Normalized bundle lacks a top-level directory");
            }

            Set<String> expected = new HashSet<String>();
            expected.add(topLevel + "/");
            expected.add(topLevel + "/manifest.json");
            expected.add(topLevel + "/runtimes/");
            for (RuntimePlatform platform : manifest.getPlatforms()
                .values()) {
                expected.add(topLevel + "/" + platform.getNormalizedBundlePath());
            }
            Set<String> actual = new HashSet<String>();
            for (ZipEntry entry : entries) actual.add(normalizeBundleName(entry.getName()));
            if (!expected.equals(actual)) {
                Set<String> missing = new HashSet<String>(expected);
                missing.removeAll(actual);
                Set<String> unexpected = new HashSet<String>(actual);
                unexpected.removeAll(expected);
                throw new RuntimeInstallationException(
                    "Normalized bundle contents differ from the canonical contract; missing=" + missing
                        + ", unexpected="
                        + unexpected);
            }

            ZipEntry manifestEntry = uniqueEntry(zip, topLevel + "/manifest.json");
            byte[] bundledManifest = readLimited(zip.getInputStream(manifestEntry), 1024 * 1024);
            if (!Arrays.equals(bundledManifest, manifest.getCanonicalBytes())) {
                throw new RuntimeInstallationException(
                    "Bundled manifest does not match the canonical classpath manifest byte-for-byte");
            }

            String selectedName = topLevel + "/" + selected.getNormalizedBundlePath();
            ZipEntry selectedEntry = uniqueEntry(zip, selectedName);
            if (selectedEntry.isDirectory())
                throw new RuntimeInstallationException("Selected runtime archive is a directory");
            if (selectedEntry.getSize() != selected.getSizeBytes()) {
                throw new RuntimeInstallationException(
                    "Selected runtime archive size mismatch; expected " + selected.getSizeBytes()
                        + " but ZIP reports "
                        + selectedEntry.getSize());
            }
            if (selectedEntry.getMethod() != ZipEntry.STORED
                || selectedEntry.getCompressedSize() != selected.getSizeBytes()) {
                throw new RuntimeInstallationException(
                    "Selected normalized runtime member must be stored without outer recompression");
            }

            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (Exception exception) {
                throw new RuntimeInstallationException("SHA-256 is unavailable", exception);
            }
            long bytes = 0L;
            InputStream input = new BufferedInputStream(zip.getInputStream(selectedEntry), BUFFER_SIZE);
            OutputStream output = Files
                .newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                byte[] buffer = new byte[BUFFER_SIZE];
                while (true) {
                    int read = input.read(buffer);
                    if (read < 0) break;
                    bytes += read;
                    if (bytes > selected.getSizeBytes()) {
                        throw new RuntimeInstallationException("Selected runtime archive exceeds canonical size");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            } finally {
                try {
                    input.close();
                } finally {
                    output.close();
                }
            }
            if (bytes != selected.getSizeBytes()) {
                throw new RuntimeInstallationException(
                    "Selected runtime archive byte count mismatch; expected " + selected.getSizeBytes()
                        + " but read "
                        + bytes);
            }
            String hash = hex(digest.digest());
            if (!selected.getSha256()
                .equals(hash)) {
                throw new RuntimeInstallationException(
                    "Selected runtime archive SHA-256 mismatch; expected " + selected.getSha256() + " but was " + hash);
            }
        } finally {
            zip.close();
        }
    }

    private static List<? extends ZipEntry> entries(ZipFile zip) throws RuntimeInstallationException {
        List<ZipEntry> result = new ArrayList<ZipEntry>();
        Enumeration<? extends ZipEntry> enumeration = zip.entries();
        while (enumeration.hasMoreElements()) {
            result.add(enumeration.nextElement());
            if (result.size() > 128) {
                throw new RuntimeInstallationException("Normalized Java runtime bundle exceeds entry-count limit");
            }
        }
        return result;
    }

    private static ZipEntry uniqueEntry(ZipFile zip, String name) throws RuntimeInstallationException {
        ZipEntry found = null;
        int count = 0;
        Enumeration<? extends ZipEntry> enumeration = zip.entries();
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            if (name.equals(entry.getName())) {
                found = entry;
                count++;
            }
        }
        if (count != 1 || found == null) {
            throw new RuntimeInstallationException(
                "Expected exactly one normalized bundle member " + name + " but found " + count);
        }
        return found;
    }

    private static String normalizeBundleName(String name) throws RuntimeInstallationException {
        boolean directory = name.endsWith("/");
        String raw = directory ? name.substring(0, name.length() - 1) : name;
        String safe = RuntimePathSafety.requireSafeRelative(raw, "Normalized bundle ZIP member");
        return directory ? safe + "/" : safe;
    }

    private static byte[] readLimited(InputStream input, int maximum) throws IOException, RuntimeInstallationException {
        try {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            while (true) {
                int read = input.read(buffer);
                if (read < 0) break;
                total += read;
                if (total > maximum) throw new RuntimeInstallationException("Bundle manifest exceeds size limit");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}
