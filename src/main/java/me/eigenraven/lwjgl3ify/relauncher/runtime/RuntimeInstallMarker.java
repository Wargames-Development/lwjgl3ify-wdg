package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Versioned completed-install marker; presence alone is never considered sufficient. */
public final class RuntimeInstallMarker {

    public static final String FILE_NAME = ".lwjgl3ify-runtime-install.json";
    private static final int MARKER_SCHEMA = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    int markerSchemaVersion;
    int manifestSchemaVersion;
    String runtimeFamily;
    String javaRuntimeVersion;
    String platformId;
    String archiveSha256;
    long archiveSizeBytes;
    String archiveRoot;
    String javaHomeRelativePath;
    String javaExecutableRelativePath;
    String windowsGuiExecutableRelativePath;
    String completionState;

    static RuntimeInstallMarker create(RuntimeManifest manifest, RuntimePlatform platform) {
        RuntimeInstallMarker marker = new RuntimeInstallMarker();
        marker.markerSchemaVersion = MARKER_SCHEMA;
        marker.manifestSchemaVersion = manifest.getSchemaVersion();
        marker.runtimeFamily = manifest.getRuntimeFamily();
        marker.javaRuntimeVersion = manifest.getJavaRuntimeVersion();
        marker.platformId = platform.getId();
        marker.archiveSha256 = platform.getSha256();
        marker.archiveSizeBytes = platform.getSizeBytes();
        marker.archiveRoot = platform.getArchiveRoot();
        marker.javaHomeRelativePath = platform.getJavaHomeRelativePath();
        marker.javaExecutableRelativePath = platform.getJavaExecutableRelativePath();
        marker.windowsGuiExecutableRelativePath = platform.getWindowsGuiExecutableRelativePath();
        marker.completionState = "complete";
        return marker;
    }

    static RuntimeInstallMarker read(Path markerPath) throws IOException, RuntimeInstallationException {
        if (!Files.isRegularFile(markerPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(markerPath)) {
            throw new RuntimeInstallationException(
                "Completed-install marker is missing or not a regular file: " + markerPath);
        }
        BufferedReader reader = Files.newBufferedReader(markerPath, StandardCharsets.UTF_8);
        try {
            RuntimeInstallMarker marker = GSON.fromJson(reader, RuntimeInstallMarker.class);
            if (marker == null) throw new RuntimeInstallationException("Completed-install marker is empty");
            return marker;
        } catch (RuntimeInstallationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeInstallationException("Completed-install marker is malformed", exception);
        } finally {
            reader.close();
        }
    }

    void validate(RuntimeManifest manifest, RuntimePlatform platform) throws RuntimeInstallationException {
        if (markerSchemaVersion != MARKER_SCHEMA || manifestSchemaVersion != manifest.getSchemaVersion()
            || !manifest.getRuntimeFamily()
                .equals(runtimeFamily)
            || !manifest.getJavaRuntimeVersion()
                .equals(javaRuntimeVersion)
            || !platform.getId()
                .equals(platformId)
            || !platform.getSha256()
                .equals(archiveSha256)
            || platform.getSizeBytes() != archiveSizeBytes
            || !platform.getArchiveRoot()
                .equals(archiveRoot)
            || !platform.getJavaHomeRelativePath()
                .equals(javaHomeRelativePath)
            || !platform.getJavaExecutableRelativePath()
                .equals(javaExecutableRelativePath)
            || !equalsNullable(platform.getWindowsGuiExecutableRelativePath(), windowsGuiExecutableRelativePath)
            || !"complete".equals(completionState)) {
            throw new RuntimeInstallationException(
                "Completed-install marker does not match the canonical runtime identity");
        }
    }

    void writeSafely(Path installationRoot) throws IOException, RuntimeInstallationException {
        Path marker = installationRoot.resolve(FILE_NAME);
        Path temporary = installationRoot.resolve(FILE_NAME + ".tmp");
        BufferedWriter writer = Files.newBufferedWriter(
            temporary,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
        try {
            GSON.toJson(this, writer);
            writer.newLine();
        } finally {
            writer.close();
        }
        java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE);
        try {
            channel.force(true);
        } finally {
            channel.close();
        }
        try {
            Files.move(temporary, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, marker);
        }
        read(marker);
    }

    private static boolean equalsNullable(Object first, Object second) {
        return first == null ? second == null : first.equals(second);
    }
}
