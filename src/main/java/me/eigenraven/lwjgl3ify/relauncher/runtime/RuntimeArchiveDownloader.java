package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Downloads only the exact Temurin release pinned by the packaged manifest. */
final class RuntimeArchiveDownloader {

    private static final int MAX_REDIRECTS = 5;

    Path download(RuntimeManifest manifest, RuntimePlatform platform, Path cacheRoot)
        throws IOException, RuntimeInstallationException {
        Path root = cacheRoot.toAbsolutePath()
            .normalize();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new RuntimeInstallationException("Java runtime cache directory is unsafe: " + root);
        }
        Path temporaryDirectory = Files.createTempDirectory(root.toRealPath(), "temurin-download-");
        Path archive = temporaryDirectory.resolve(platform.getInputFilename());
        boolean complete = false;
        try {
            URL url = releaseUrl(manifest, platform);
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                requireAllowed(url);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(30000);
                try {
                    int status = connection.getResponseCode();
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        String location = connection.getHeaderField("Location");
                        if (location == null) throw new IOException("Temurin download redirected without a location");
                        url = new URL(url, location);
                        continue;
                    }
                    if (status != 200) throw new IOException("Temurin download returned HTTP " + status);
                    long length = connection.getContentLengthLong();
                    if (length >= 0 && length != platform.getSizeBytes()) {
                        throw new RuntimeInstallationException("Temurin download has an unexpected archive size");
                    }
                    try (InputStream input = connection.getInputStream()) {
                        RuntimeArchiveIntegrity.copyVerified(input, archive, platform, "Temurin download");
                    }
                    complete = true;
                    return archive;
                } finally {
                    connection.disconnect();
                }
            }
            throw new IOException("Temurin download exceeded the redirect limit");
        } finally {
            if (!complete) cleanup(archive);
        }
    }

    static URL releaseUrl(RuntimeManifest manifest, RuntimePlatform platform) throws IOException {
        if (platform.getInputFilename() == null) {
            throw new IOException("Runtime manifest does not name a downloadable archive");
        }
        String release = manifest.getJavaRuntimeVersion()
            .replace("-LTS", "");
        if (!release.matches("[0-9]+(\\.[0-9]+){2,3}\\+[0-9]+")) {
            throw new IOException("Unexpected pinned Temurin release version");
        }
        return new URL(
            "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-" + release.replace("+", "%2B")
                + "/"
                + platform.getInputFilename());
    }

    static void requireAllowed(URL url) throws IOException {
        String host = url.getHost();
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getPort() != -1
            || url.getUserInfo() != null
            || !("github.com".equalsIgnoreCase(host)
                || "release-assets.githubusercontent.com".equalsIgnoreCase(host))) {
            throw new IOException("Temurin download redirected to an untrusted origin");
        }
    }

    static void cleanup(Path archive) throws IOException {
        Files.deleteIfExists(archive);
        Files.deleteIfExists(archive.getParent());
    }
}
