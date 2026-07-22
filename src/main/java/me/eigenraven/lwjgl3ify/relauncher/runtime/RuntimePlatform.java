package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Immutable platform record from the canonical packaged-runtime manifest. */
public final class RuntimePlatform {

    private final String id;
    private final String operatingSystem;
    private final String architecture;
    private final Set<String> operatingSystemAliases;
    private final Set<String> architectureAliases;
    private final String libc;
    private final String normalizedBundlePath;
    private final String archiveType;
    private final long sizeBytes;
    private final String sha256;
    private final String archiveRoot;
    private final String javaHomeRelativePath;
    private final String javaExecutableRelativePath;
    private final String windowsGuiExecutableRelativePath;
    private final String releaseFileRelativePath;
    private final Map<String, String> expectedReleaseProperties;

    RuntimePlatform(String id, String operatingSystem, String architecture, Set<String> operatingSystemAliases,
        Set<String> architectureAliases, String libc, String normalizedBundlePath, String archiveType, long sizeBytes,
        String sha256, String archiveRoot, String javaHomeRelativePath, String javaExecutableRelativePath,
        String windowsGuiExecutableRelativePath, String releaseFileRelativePath,
        Map<String, String> expectedReleaseProperties) {
        this.id = id;
        this.operatingSystem = operatingSystem;
        this.architecture = architecture;
        this.operatingSystemAliases = Collections
            .unmodifiableSet(new java.util.LinkedHashSet<String>(operatingSystemAliases));
        this.architectureAliases = Collections
            .unmodifiableSet(new java.util.LinkedHashSet<String>(architectureAliases));
        this.libc = libc;
        this.normalizedBundlePath = normalizedBundlePath;
        this.archiveType = archiveType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.archiveRoot = archiveRoot;
        this.javaHomeRelativePath = javaHomeRelativePath;
        this.javaExecutableRelativePath = javaExecutableRelativePath;
        this.windowsGuiExecutableRelativePath = windowsGuiExecutableRelativePath;
        this.releaseFileRelativePath = releaseFileRelativePath;
        this.expectedReleaseProperties = Collections
            .unmodifiableMap(new LinkedHashMap<String, String>(expectedReleaseProperties));
    }

    public String getId() {
        return id;
    }

    public String getOperatingSystem() {
        return operatingSystem;
    }

    public String getArchitecture() {
        return architecture;
    }

    public Set<String> getOperatingSystemAliases() {
        return operatingSystemAliases;
    }

    public Set<String> getArchitectureAliases() {
        return architectureAliases;
    }

    public String getLibc() {
        return libc;
    }

    public String getNormalizedBundlePath() {
        return normalizedBundlePath;
    }

    public String getArchiveType() {
        return archiveType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public String getArchiveRoot() {
        return archiveRoot;
    }

    public String getJavaHomeRelativePath() {
        return javaHomeRelativePath;
    }

    public String getJavaExecutableRelativePath() {
        return javaExecutableRelativePath;
    }

    public String getWindowsGuiExecutableRelativePath() {
        return windowsGuiExecutableRelativePath;
    }

    public String getReleaseFileRelativePath() {
        return releaseFileRelativePath;
    }

    public Map<String, String> getExpectedReleaseProperties() {
        return expectedReleaseProperties;
    }

    public boolean isWindows() {
        return "windows".equals(operatingSystem);
    }
}
