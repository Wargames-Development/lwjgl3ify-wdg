package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.nio.file.Path;

/** Deterministic cache layout derived only from canonical manifest identity. */
public final class RuntimeCacheLayout {

    private final Path cacheRoot;
    private final Path runtimesRoot;
    private final Path installationRoot;
    private final Path lockFile;

    public RuntimeCacheLayout(Path cacheRoot, RuntimeManifest manifest, RuntimePlatform platform) {
        this.cacheRoot = cacheRoot.toAbsolutePath()
            .normalize();
        this.runtimesRoot = this.cacheRoot.resolve("java-runtimes")
            .normalize();
        this.installationRoot = runtimesRoot.resolve(manifest.getRuntimeFamily())
            .resolve(manifest.getJavaRuntimeVersion())
            .resolve(platform.getId())
            .resolve(platform.getSha256())
            .normalize();
        this.lockFile = installationRoot.getParent()
            .resolve(platform.getSha256() + ".lock");
    }

    public Path getCacheRoot() {
        return cacheRoot;
    }

    public Path getRuntimesRoot() {
        return runtimesRoot;
    }

    public Path getInstallationRoot() {
        return installationRoot;
    }

    public Path getLockFile() {
        return lockFile;
    }
}
