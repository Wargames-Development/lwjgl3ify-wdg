package me.eigenraven.lwjgl3ify.relauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.launchwrapper.Launch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class RelauncherConfig {

    public enum GCOption {

        G1GC(new String[] { "-XX:+UseG1GC" }),
        ZGC(new String[] { "-XX:+UseZGC" }),
        Shenandoah(new String[] { "-XX:+UseShenandoahGC" }),
        GenerationalZGC(new String[] { "-XX:+UseZGC", "-XX:+ZGenerational" }),
        Custom(new String[] {});

        public final String[] FLAGS;

        GCOption(String[] flags) {
            FLAGS = flags;
        }
    }

    public static class ConfigObject {

        public ConfigObject() {}

        // Basic
        public String[] javaInstallationsCache = new String[0];
        public int javaInstallation = 0;
        public int minMemoryMB = 512;
        public int maxMemoryMB = 4096;
        public GCOption garbageCollector = GCOption.G1GC;
        public String[] customOptions = new String[0];
        public boolean hideSettingsOnLaunch = false;
        public boolean useBundledJava = true;
        // Advanced
        public boolean forwardLogs = false;
        public boolean allowDebugger = false;
        public boolean waitForDebugger = false;
        public boolean mixinDebug = false;
        public boolean mixinDebugExport = false;
        public boolean mixinDebugCount = false;
        public boolean fmlDebugAts = false;
        public boolean rfbDumpClasses = false;
        public boolean rfbDumpPerTransformer = false;

        public void setCustomOptionsFromQuotedString(final String input) {
            final List<String> tokens = new ArrayList<>();
            final StringBuilder tokenBuilder = new StringBuilder();
            boolean inQuotes = false;
            for (int i = 0; i < input.length(); i++) {
                final char c = input.charAt(i);
                final char peek = (i == input.length() - 1) ? '\0' : input.charAt(i + 1);
                if (!inQuotes && Character.isWhitespace(c)) {
                    if (tokenBuilder.length() != 0) {
                        tokens.add(tokenBuilder.toString());
                        tokenBuilder.delete(0, tokenBuilder.length());
                    }
                } else if (c == '\\' && peek != '\0') {
                    tokenBuilder.append(peek);
                    i++;
                } else if (c == '"') {
                    inQuotes = !inQuotes;
                } else {
                    tokenBuilder.append(c);
                }
            }
            if (tokenBuilder.length() != 0) {
                tokens.add(tokenBuilder.toString());
                tokenBuilder.delete(0, tokenBuilder.length());
            }
            this.customOptions = tokens.toArray(new String[0]);
        }

        public String customOptionsToQuotedString() {
            final StringBuilder out = new StringBuilder();
            final String[] selectedCustomOptions = this.customOptions == null ? new String[0] : this.customOptions;
            for (final String arg : selectedCustomOptions) {
                if (arg == null) continue;
                final String escaped = arg.replace("\\", "\\\\")
                    .replace("\"", "\\\"");
                if (escaped.chars()
                    .anyMatch(Character::isWhitespace)) {
                    out.append('"');
                    out.append(escaped);
                    out.append('"');
                } else {
                    out.append(escaped);
                }
                out.append('\n');
            }
            return out.toString();
        }

        public List<String> toJvmArgs() {
            final List<String> out = new ArrayList<>();
            if (minMemoryMB > 0) {
                out.add("-Xms" + minMemoryMB + "M");
            }
            if (maxMemoryMB > 0) {
                out.add("-Xmx" + maxMemoryMB + "M");
            }
            GCOption selectedGarbageCollector = garbageCollector == null ? GCOption.G1GC : garbageCollector;
            out.addAll(Arrays.asList(selectedGarbageCollector.FLAGS));
            if (allowDebugger) {
                out.add(
                    "-agentlib:jdwp=transport=dt_socket,server=y,suspend=" + (waitForDebugger ? 'y' : 'n')
                        + ",address=5005");
            }
            if (mixinDebug) {
                out.add("-Dmixin.debug=true");
            }
            if (mixinDebugExport) {
                out.add("-Dmixin.debug.export=true");
            }
            if (mixinDebugCount) {
                out.add("-Dmixin.debug.countInjections=true");
            }
            if (fmlDebugAts) {
                out.add("-Dfml.debugAccessTransformer=true");
            }
            if (rfbDumpClasses) {
                out.add("-Drfb.dumpLoadedClasses=true");
            }
            if (rfbDumpPerTransformer) {
                out.add("-Drfb.dumpLoadedClassesPerTransformer=true");
            }
            String[] selectedCustomOptions = customOptions == null ? new String[0] : customOptions;
            for (final String arg : selectedCustomOptions) {
                if (arg == null || arg.trim()
                    .isEmpty()) {
                    continue;
                }
                out.add(arg.trim());
            }
            return out;
        }
    }

    public static ConfigObject config = new ConfigObject();
    private static final Gson gson = new GsonBuilder().setPrettyPrinting()
        .create();

    public static void load() {
        load(configPath());
    }

    static void load(Path earlyConfigPath) {
        ConfigObject loaded = new ConfigObject();
        final boolean existingConfig = Files.exists(earlyConfigPath);
        if (existingConfig) {
            try {
                final String configContents = new String(Files.readAllBytes(earlyConfigPath), StandardCharsets.UTF_8);
                loaded = gson.fromJson(configContents, ConfigObject.class);
                if (loaded == null) {
                    throw new IllegalArgumentException("configuration root is null");
                }
            } catch (IOException e) {
                throw new RuntimeException("Could not read relauncher configuration at " + earlyConfigPath, e);
            } catch (RuntimeException e) {
                throw new RuntimeException(
                    "Malformed relauncher configuration at " + earlyConfigPath
                        + "; fix or move the file rather than losing existing settings",
                    e);
            }
        }
        normalize(loaded);
        config = loaded;
        if (!existingConfig) save(earlyConfigPath);
    }

    private static void normalize(ConfigObject loaded) {
        if (loaded.javaInstallationsCache == null) loaded.javaInstallationsCache = new String[0];
        if (loaded.customOptions == null) loaded.customOptions = new String[0];
        if (loaded.garbageCollector == null) loaded.garbageCollector = GCOption.G1GC;
        if (loaded.javaInstallationsCache.length == 0) {
            loaded.javaInstallation = -1;
        } else {
            loaded.javaInstallation = Math
                .max(0, Math.min(loaded.javaInstallation, loaded.javaInstallationsCache.length - 1));
        }
    }

    public static void save() {
        save(configPath());
    }

    static void save(Path earlyConfigPath) {
        normalize(config);
        final String jsonCfg = gson.toJson(config);
        try {
            // Allow the config directory itself to be a launcher-managed symlink.
            if (!Files.isDirectory(earlyConfigPath.getParent())) {
                Files.createDirectories(earlyConfigPath.getParent());
            }
            Files.write(earlyConfigPath, jsonCfg.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("Could not write relauncher configuration at " + earlyConfigPath, e);
        }
    }

    private static Path configPath() {
        final Path gamePath = (Launch.minecraftHome == null) ? Paths.get(".") : Launch.minecraftHome.toPath();
        return gamePath.resolve("config")
            .resolve("lwjgl3ify-relauncher.json");
    }
}
