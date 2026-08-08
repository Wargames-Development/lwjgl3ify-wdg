package me.eigenraven.lwjgl3ify.relauncher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extracts the small, Java-version-stable subset of JVM arguments that controls heap sizing.
 *
 * Launcher JVM arguments are otherwise intentionally not copied wholesale: many Java 8 flags are invalid on Java 21,
 * and launcher/private system properties are already handled by the relauncher separately.
 */
final class JvmMemoryArgumentSupport {

    private static final String[] MEMORY_PREFIXES = new String[] { "-xms", "-xmx", "-xx:initialheapsize=",
        "-xx:maxheapsize=", "-xx:initialrampercentage=", "-xx:maxrampercentage=", "-xx:minrampercentage=",
        "-xx:initialramfraction=", "-xx:maxramfraction=", "-xx:minramfraction=" };

    private JvmMemoryArgumentSupport() {}

    static List<String> extractExplicitMemoryArguments(List<String> inputArguments) {
        if (inputArguments == null || inputArguments.isEmpty()) return Collections.emptyList();
        final Map<String, String> lastArgumentByKind = new LinkedHashMap<String, String>();
        for (final String argument : inputArguments) {
            if (!isMemorySizingArgument(argument)) continue;
            final String trimmed = argument.trim();
            final String kind = memoryArgumentKind(trimmed);
            // Remove/reinsert so the output retains the order of the final effective occurrences.
            lastArgumentByKind.remove(kind);
            lastArgumentByKind.put(kind, trimmed);
        }
        return new ArrayList<String>(lastArgumentByKind.values());
    }

    static boolean isMemorySizingArgument(String argument) {
        if (argument == null) return false;
        final String normalized = argument.trim()
            .toLowerCase(Locale.ROOT);
        for (final String prefix : MEMORY_PREFIXES) {
            if (normalized.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String memoryArgumentKind(String argument) {
        final String normalized = argument.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("-xms")) return "-xms";
        if (normalized.startsWith("-xmx")) return "-xmx";
        final int equals = normalized.indexOf('=');
        return equals < 0 ? normalized : normalized.substring(0, equals + 1);
    }

    static List<String> removeMemorySizingArguments(String[] customOptions) {
        if (customOptions == null || customOptions.length == 0) return Collections.emptyList();
        final List<String> result = new ArrayList<String>();
        for (final String option : customOptions) {
            if (option == null) continue;
            final String trimmed = option.trim();
            if (trimmed.isEmpty() || isMemorySizingArgument(trimmed)) continue;
            result.add(trimmed);
        }
        return result;
    }

    static String describe(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) return "none";
        return String.join(" ", arguments);
    }
}
