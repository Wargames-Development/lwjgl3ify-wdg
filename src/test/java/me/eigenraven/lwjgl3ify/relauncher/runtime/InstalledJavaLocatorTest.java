package me.eigenraven.lwjgl3ify.relauncher.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InstalledJavaLocatorTest {

    private static final RuntimeHost ARM_MAC = new RuntimeHost("macos", "aarch64", null, "test");
    private static final RuntimeHost X64_WINDOWS = new RuntimeHost("windows", "x86_64", null, "test");

    @Test
    public void requiresMatchingVersionVendorAndArchitecture() {
        String temurin = "    java.version = 21.0.11\n    java.vendor = Eclipse Adoptium\n    os.arch = amd64\n";
        assertTrue(InstalledJavaLocator.matches(temurin, X64_WINDOWS));
        assertFalse(InstalledJavaLocator.matches(temurin, ARM_MAC));
        assertFalse(InstalledJavaLocator.matches(temurin.replace("21.0.11", "17.0.20"), X64_WINDOWS));
        assertFalse(InstalledJavaLocator.matches(temurin.replace("Eclipse Adoptium", "Unknown Vendor"), X64_WINDOWS));
        assertTrue(InstalledJavaLocator.matches(temurin.replace("amd64", "aarch64"), ARM_MAC));
    }
}
