package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Runtime-side parser and immutable model for the canonical schema-1 manifest. */
public final class RuntimeManifest {

    public static final String RESOURCE_PATH = "/me/eigenraven/lwjgl3ify/relauncher/runtime/java21-runtime-manifest.json";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> REQUIRED_IDS;

    static {
        Set<String> ids = new HashSet<String>();
        Collections.addAll(
            ids,
            "linux-aarch64",
            "linux-x86_64",
            "macos-aarch64",
            "macos-x86_64",
            "windows-aarch64",
            "windows-x86_64");
        REQUIRED_IDS = Collections.unmodifiableSet(ids);
    }

    private final int schemaVersion;
    private final String runtimeFamily;
    private final String javaRuntimeVersion;
    private final byte[] canonicalBytes;
    private final Map<String, RuntimePlatform> platforms;

    private RuntimeManifest(int schemaVersion, String runtimeFamily, String javaRuntimeVersion, byte[] canonicalBytes,
        Map<String, RuntimePlatform> platforms) {
        this.schemaVersion = schemaVersion;
        this.runtimeFamily = runtimeFamily;
        this.javaRuntimeVersion = javaRuntimeVersion;
        this.canonicalBytes = canonicalBytes.clone();
        this.platforms = Collections.unmodifiableMap(new LinkedHashMap<String, RuntimePlatform>(platforms));
    }

    public static RuntimeManifest loadCanonical() throws RuntimeInstallationException {
        InputStream stream = RuntimeManifest.class.getResourceAsStream(RESOURCE_PATH);
        if (stream == null) {
            throw new RuntimeInstallationException("Canonical Java runtime manifest is missing: " + RESOURCE_PATH);
        }
        try {
            return parse(readAll(stream, 1024 * 1024), true);
        } catch (IOException exception) {
            throw new RuntimeInstallationException("Could not read canonical Java runtime manifest", exception);
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {}
        }
    }

    static RuntimeManifest parse(byte[] bytes, boolean requireCanonicalSix) throws RuntimeInstallationException {
        final JsonObject root;
        try {
            JsonElement parsed = new JsonParser()
                .parse(new InputStreamReader(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new RuntimeInstallationException("Runtime manifest root must be a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeInstallationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeInstallationException("Runtime manifest is malformed JSON", exception);
        }

        int schema = requiredInt(root, "schemaVersion");
        if (schema != 1) {
            throw new RuntimeInstallationException("Unsupported runtime manifest schema version: " + schema);
        }
        String family = safeIdentity(requiredString(root, "runtimeFamily"), "runtimeFamily");
        requiredString(root, "vendor");
        requiredString(root, "implementor");
        requiredString(root, "distribution");
        requiredString(root, "implementorVersion");
        int feature = requiredInt(root, "javaFeatureVersion");
        if (feature <= 0) throw new RuntimeInstallationException("javaFeatureVersion must be positive");
        requiredString(root, "javaVersion");
        String runtimeVersion = safeIdentity(requiredString(root, "javaRuntimeVersion"), "javaRuntimeVersion");
        requiredString(root, "jvmVariant");
        String globalArchiveRoot = RuntimePathSafety
            .requireSafeRelative(requiredString(root, "archiveRoot"), "archiveRoot");
        int expectedCount = requiredInt(root, "supportedPlatformCount");
        JsonArray array = requiredArray(root, "platforms");
        if (array.size() != expectedCount) {
            throw new RuntimeInstallationException("supportedPlatformCount does not match platforms array");
        }

        Map<String, RuntimePlatform> platforms = new LinkedHashMap<String, RuntimePlatform>();
        Set<String> tuples = new HashSet<String>();
        Set<String> normalizedPaths = new HashSet<String>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) throw new RuntimeInstallationException("Each platform must be an object");
            JsonObject platform = element.getAsJsonObject();
            String id = safeIdentity(requiredString(platform, "id"), "platform id");
            String os = safeIdentity(requiredString(platform, "operatingSystem"), "operatingSystem");
            String arch = safeIdentity(requiredString(platform, "architecture"), "architecture");
            String tuple = os.toLowerCase(Locale.ROOT) + "/" + arch.toLowerCase(Locale.ROOT);
            if (!tuples.add(tuple)) throw new RuntimeInstallationException("Duplicate OS/architecture tuple: " + tuple);
            if (platforms.containsKey(id)) throw new RuntimeInstallationException("Duplicate platform ID: " + id);

            String normalized = RuntimePathSafety
                .requireSafeRelative(requiredString(platform, "normalizedBundlePath"), "normalizedBundlePath");
            if (!normalized.startsWith("runtimes/")) {
                throw new RuntimeInstallationException("normalizedBundlePath must be below runtimes/: " + normalized);
            }
            if (!normalizedPaths.add(normalized.toLowerCase(Locale.ROOT))) {
                throw new RuntimeInstallationException(
                    "Duplicate or case-colliding normalized bundle path: " + normalized);
            }
            String type = requiredString(platform, "archiveType");
            if (!"zip".equals(type) && !"tar.gz".equals(type)) {
                throw new RuntimeInstallationException("Unsupported archive type for " + id + ": " + type);
            }
            long size = requiredLong(platform, "sizeBytes");
            if (size <= 0L) throw new RuntimeInstallationException("Archive size must be positive for " + id);
            String hash = requiredString(platform, "sha256");
            if (!SHA256.matcher(hash)
                .matches()) throw new RuntimeInstallationException("Malformed SHA-256 for " + id);
            String archiveRoot = RuntimePathSafety
                .requireSafeRelative(requiredString(platform, "archiveRoot"), "platform archiveRoot");
            if (!globalArchiveRoot.equals(archiveRoot)) {
                throw new RuntimeInstallationException(
                    "Platform archive root differs from global archive root for " + id);
            }
            String javaHome = RuntimePathSafety
                .requireSafeRelativeAllowDot(requiredString(platform, "javaHomeRelativePath"), "javaHomeRelativePath");
            String javaExecutable = RuntimePathSafety.requireSafeRelative(
                requiredString(platform, "javaExecutableRelativePath"),
                "javaExecutableRelativePath");
            String gui = optionalString(platform, "windowsGuiExecutableRelativePath");
            if (gui != null) gui = RuntimePathSafety.requireSafeRelative(gui, "windowsGuiExecutableRelativePath");
            if ("windows".equals(os) && gui == null) {
                throw new RuntimeInstallationException("Windows platform lacks javaw path: " + id);
            }
            if (!"windows".equals(os) && gui != null) {
                throw new RuntimeInstallationException("Non-Windows platform defines javaw path: " + id);
            }
            String release = RuntimePathSafety
                .requireSafeRelative(requiredString(platform, "releaseFileRelativePath"), "releaseFileRelativePath");
            JsonObject expected = requiredObject(platform, "expectedReleaseProperties");
            Map<String, String> releaseProperties = new LinkedHashMap<String, String>();
            for (Map.Entry<String, JsonElement> entry : expected.entrySet()) {
                if (!entry.getValue()
                    .isJsonPrimitive()
                    || !entry.getValue()
                        .getAsJsonPrimitive()
                        .isString()) {
                    throw new RuntimeInstallationException("Release property values must be strings for " + id);
                }
                releaseProperties.put(
                    entry.getKey(),
                    entry.getValue()
                        .getAsString());
            }
            if (releaseProperties.isEmpty()) throw new RuntimeInstallationException("No release properties for " + id);

            platforms.put(
                id,
                new RuntimePlatform(
                    id,
                    os,
                    arch,
                    normalized,
                    type,
                    size,
                    hash,
                    archiveRoot,
                    javaHome,
                    javaExecutable,
                    gui,
                    release,
                    releaseProperties));
        }
        if (requireCanonicalSix && (!REQUIRED_IDS.equals(platforms.keySet()) || expectedCount != 6)) {
            throw new RuntimeInstallationException(
                "Canonical manifest must define exactly the six supported platform IDs");
        }
        return new RuntimeManifest(schema, family, runtimeVersion, bytes, platforms);
    }

    public RuntimePlatform getPlatform(String id) throws RuntimeInstallationException {
        RuntimePlatform platform = platforms.get(id);
        if (platform == null) throw new RuntimeInstallationException("Unsupported Java runtime platform ID: " + id);
        return platform;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getRuntimeFamily() {
        return runtimeFamily;
    }

    public String getJavaRuntimeVersion() {
        return javaRuntimeVersion;
    }

    public Map<String, RuntimePlatform> getPlatforms() {
        return platforms;
    }

    byte[] getCanonicalBytes() {
        return canonicalBytes.clone();
    }

    private static String safeIdentity(String value, String field) throws RuntimeInstallationException {
        if (!value.matches("[A-Za-z0-9._+\\-]+")) {
            throw new RuntimeInstallationException(field + " contains unsafe characters: " + value);
        }
        RuntimePathSafety.requireSafeRelative(value, field);
        return value;
    }

    private static String requiredString(JsonObject object, String name) throws RuntimeInstallationException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()
            || !value.getAsJsonPrimitive()
                .isString()) {
            throw new RuntimeInstallationException("Missing or invalid string field: " + name);
        }
        String text = value.getAsString();
        if (text.isEmpty()) throw new RuntimeInstallationException("Manifest field is empty: " + name);
        return text;
    }

    private static String optionalString(JsonObject object, String name) throws RuntimeInstallationException {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive()
            .isString()) {
            throw new RuntimeInstallationException("Invalid optional string field: " + name);
        }
        return value.getAsString();
    }

    private static int requiredInt(JsonObject object, String name) throws RuntimeInstallationException {
        long value = requiredLong(object, name);
        if (value > Integer.MAX_VALUE) throw new RuntimeInstallationException("Integer field is too large: " + name);
        return (int) value;
    }

    private static long requiredLong(JsonObject object, String name) throws RuntimeInstallationException {
        JsonElement value = object.get(name);
        try {
            if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive()
                    .isNumber())
                throw new Exception();
            String text = value.getAsString();
            if (!text.matches("-?[0-9]+")) throw new Exception();
            return Long.parseLong(text);
        } catch (Exception exception) {
            throw new RuntimeInstallationException("Missing or invalid integer field: " + name);
        }
    }

    private static JsonArray requiredArray(JsonObject object, String name) throws RuntimeInstallationException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray())
            throw new RuntimeInstallationException("Missing array field: " + name);
        return value.getAsJsonArray();
    }

    private static JsonObject requiredObject(JsonObject object, String name) throws RuntimeInstallationException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonObject())
            throw new RuntimeInstallationException("Missing object field: " + name);
        return value.getAsJsonObject();
    }

    private static byte[] readAll(InputStream input, int maximum) throws IOException, RuntimeInstallationException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) break;
            total += read;
            if (total > maximum) throw new RuntimeInstallationException("Runtime manifest exceeds size limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
