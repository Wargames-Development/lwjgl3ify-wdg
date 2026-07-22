package me.eigenraven.lwjgl3ify.relauncher.runtime;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/** Resolves the packaged-runtime bundle without scanning uncontrolled directories. */
public final class RuntimeBundleLocator {

    public static final String PROPERTY_NAME = "lwjgl3ify.relauncher.runtimeBundle";
    public static final String ENVIRONMENT_NAME = "LWJGL3IFY_RUNTIME_BUNDLE";
    public static final String CANONICAL_RELATIVE_PATH = "lwjgl3ify/runtime/lwjgl3ify-wdg-java21-runtimes.zip";

    public enum Source {
        SYSTEM_PROPERTY,
        ENVIRONMENT,
        GAME_DIRECTORY
    }

    public enum Status {
        AVAILABLE,
        DEFAULT_ABSENT,
        INVALID_OVERRIDE
    }

    public static final class Result {

        private final Status status;
        private final Source source;
        private final Path path;
        private final String message;

        Result(Status status, Source source, Path path, String message) {
            this.status = status;
            this.source = source;
            this.path = path;
            this.message = message;
        }

        public Status getStatus() {
            return status;
        }

        public Source getSource() {
            return source;
        }

        public Path getPath() {
            return path;
        }

        public String getMessage() {
            return message;
        }

        public boolean isAvailable() {
            return status == Status.AVAILABLE;
        }

        public boolean isFatal() {
            return status == Status.INVALID_OVERRIDE;
        }
    }

    public Result locate(Path gameDirectory, Map<String, String> properties, Map<String, String> environment) {
        if (gameDirectory == null) throw new NullPointerException("gameDirectory");
        Path normalizedGameDirectory = gameDirectory.toAbsolutePath()
            .normalize();
        String propertyValue = value(properties, PROPERTY_NAME);
        if (propertyValue != null) {
            return validate(resolve(normalizedGameDirectory, propertyValue), Source.SYSTEM_PROPERTY, true);
        }
        String environmentValue = value(environment, ENVIRONMENT_NAME);
        if (environmentValue != null) {
            return validate(resolve(normalizedGameDirectory, environmentValue), Source.ENVIRONMENT, true);
        }
        return validate(normalizedGameDirectory.resolve(CANONICAL_RELATIVE_PATH), Source.GAME_DIRECTORY, false);
    }

    private static String value(Map<String, String> values, String key) {
        if (values == null) return null;
        String value = values.get(key);
        if (value == null || value.trim()
            .isEmpty()) return null;
        return value.trim();
    }

    private static Path resolve(Path gameDirectory, String configured) {
        Path path = Paths.get(configured);
        if (!path.isAbsolute()) path = gameDirectory.resolve(path);
        return path.toAbsolutePath()
            .normalize();
    }

    private static Result validate(Path path, Source source, boolean explicit) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return new Result(
                explicit ? Status.INVALID_OVERRIDE : Status.DEFAULT_ABSENT,
                source,
                path,
                explicit ? "Explicit packaged Java bundle does not exist: " + path
                    : "Packaged Java bundle was not found at " + path);
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return new Result(
                Status.INVALID_OVERRIDE,
                source,
                path,
                "Packaged Java bundle path is a directory: " + path);
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return new Result(
                Status.INVALID_OVERRIDE,
                source,
                path,
                "Packaged Java bundle is not a safe regular file: " + path);
        }
        if (!Files.isReadable(path)) {
            return new Result(Status.INVALID_OVERRIDE, source, path, "Packaged Java bundle is not readable: " + path);
        }
        return new Result(Status.AVAILABLE, source, path, "Packaged Java bundle selected from " + source);
    }
}
