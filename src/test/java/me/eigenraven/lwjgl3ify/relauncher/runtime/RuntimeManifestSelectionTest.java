package me.eigenraven.lwjgl3ify.relauncher.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class RuntimeManifestSelectionTest {

    @Test
    public void canonicalManifestCarriesValidatedAliasesAndLibc() throws Exception {
        RuntimeManifest manifest = RuntimeManifest.loadCanonical();
        RuntimePlatform windows = manifest.getPlatform("windows-x86_64");
        assertTrue(
            windows.getOperatingSystemAliases()
                .contains("windows 11"));
        assertTrue(
            windows.getArchitectureAliases()
                .contains("amd64"));
        RuntimePlatform linux = manifest.getPlatform("linux-aarch64");
        assertEquals("gnu", linux.getLibc());
        assertTrue(
            linux.getArchitectureAliases()
                .contains("arm64"));
    }

    @Test
    public void noManifestPlatformMatchFailsClearly() throws Exception {
        try {
            RuntimeManifest.loadCanonical()
                .selectPlatform(new RuntimeHost("linux", "x86_64", "musl", "test"));
            fail();
        } catch (RuntimeInstallationException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("no platform"));
        }
    }

    @Test
    public void ambiguousAndMalformedAliasesAreRejected() throws Exception {
        String canonical = new String(
            RuntimeManifest.loadCanonical()
                .getCanonicalBytes(),
            StandardCharsets.UTF_8);
        String ambiguous = canonical.replaceFirst("\"arm64\"", "\"amd64\"");
        assertManifestFailure(ambiguous, "Ambiguous architecture alias");

        String malformed = canonical.replace("Windows 11", "Windows/11");
        assertManifestFailure(malformed, "Malformed alias");

        String duplicateTuple = canonical.replace(
            "\"id\": \"linux-x86_64\",\n" + "      \"operatingSystem\": \"linux\",\n"
                + "      \"architecture\": \"x86_64\",\n"
                + "      \"operatingSystemAliases\": [\n"
                + "        \"Linux\",\n"
                + "        \"linux\"\n"
                + "      ],\n"
                + "      \"architectureAliases\": [\n"
                + "        \"amd64\",\n"
                + "        \"x86_64\",\n"
                + "        \"x64\"\n"
                + "      ]",
            "\"id\": \"linux-x86_64\",\n" + "      \"operatingSystem\": \"linux\",\n"
                + "      \"architecture\": \"aarch64\",\n"
                + "      \"operatingSystemAliases\": [\n"
                + "        \"Linux\",\n"
                + "        \"linux\"\n"
                + "      ],\n"
                + "      \"architectureAliases\": [\n"
                + "        \"aarch64\",\n"
                + "        \"arm64\"\n"
                + "      ]");
        assertManifestFailure(duplicateTuple, "Duplicate OS/architecture tuple");
    }

    private static void assertManifestFailure(String json, String expectedMessage) throws Exception {
        try {
            RuntimeManifest.parse(json.getBytes(StandardCharsets.UTF_8), true);
            fail();
        } catch (RuntimeInstallationException expected) {
            assertTrue(
                expected.getMessage(),
                expected.getMessage()
                    .contains(expectedMessage));
        }
    }
}
