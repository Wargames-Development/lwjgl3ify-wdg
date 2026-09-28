# WDG Java runtime distribution

## Purpose

Minecraft Forge 1.7.10 launchers commonly start under Java 8, while the maintained lwjgl3ify and Distant Horizons stack requires a modern Java child process. lwjgl3ify-WDG provides a transparent, local, portable Java 21 runtime so players do not need to install or select another system Java manually.

The slim JAR can download the manifest-pinned Temurin archive from the official GitHub release if neither a verified cache nor a matching installed Java nor a local archive is available. It verifies size and SHA-256 before extraction into the lwjgl3ify managed cache. It does not install Java into the operating system, alter `JAVA_HOME`, modify launcher accounts, or read authentication tokens.

## Primary one-JAR matrix

The moderation candidate embeds the original, hash-pinned Eclipse Temurin 21.0.11+10 JRE archives for:

* `windows-x86_64`
* `linux-x86_64`
* `macos-x86_64`
* `macos-aarch64`

They are stored as nested resources in the lwjgl3ify JAR. The nested archive bytes must equal the SHA-256 and size in `java21-runtime-manifest.json`. Their included `LICENSE`, `NOTICE`, and `legal/` contents remain intact.

This is a single lwjgl3ify mod JAR, not a combined Distant Horizons, Angelica, UniMixins, or GTNHLib JAR. Those mods remain separate artifacts.

## Optional architecture extensions

Windows ARM64 and Linux ARM64 are distributed separately because they are uncommon and materially increase the main file size. The exact generated files are:

```text
lwjgl3ify-wdg-java21-windows-aarch64.zip
lwjgl3ify-wdg-java21-linux-aarch64.tar.gz
```

Place one unmodified file in:

```text
<modpack root>/lwjgl3ify/runtime/extensions/
```

Do not extract or rename it. lwjgl3ify computes the exact expected filename from the detected platform and verifies the archive size and SHA-256 before extraction. The extension directory is never scanned for arbitrary executables.

The legacy complete bundle at `lwjgl3ify/runtime/lwjgl3ify-wdg-java21-runtimes.zip` remains supported for recovery and existing Change 004 packages. An explicit `lwjgl3ify.relauncher.runtimeBundle` property or `LWJGL3IFY_RUNTIME_BUNDLE` environment value remains a diagnostic override.

## Selection order

1. An explicit normalized-bundle property or environment override.
2. A verified managed-cache installation for the detected platform.
3. A matching installed Temurin 21 runtime (unless `-Dlwjgl3ify.relauncher.detectInstalledJava=false`).
4. The exact embedded primary runtime, if present in a runtime-bearing JAR.
5. The exact optional extension filename for the detected platform.
6. The legacy complete normalized bundle.
7. The exact Temurin archive from the release pinned in the manifest (unless `-Dlwjgl3ify.relauncher.downloadRuntime=false`).
8. The preserved manual Java selection path if an offline download cannot complete.

A corrupt present local source fails closed. A downloaded archive must match the manifest size and SHA-256 before installation.

## Console and logs

The graphical Java relaunch console is hidden by default for normal players. The Java 21 child process output is appended to:

```text
<modpack root>/logs/lwjgl3ify-java21-child.log
```

On the next launch after the file reaches 10 MiB, it rotates and retains three backups. Minecraft/FML logs continue to use the launcher's normal `logs/` directory. On an abnormal hidden-child exit, lwjgl3ify displays a concise error dialog containing the log path.

For an explicit diagnostic session, add:

```text
-Dlwjgl3ify.wdg.showConsole=true
```

This restores the graphical relaunch console while continuing to mirror its output to the persistent child log.

## Redistribution and moderation transparency

The project page and release notes must identify:

* the exact Eclipse Temurin distribution and build;
* all embedded platforms;
* original archive sizes and SHA-256 values;
* where archives are extracted;
* how the cache and extension files can be removed;
* the upstream lwjgl3ify source and WDG fork source;
* the applicable upstream and Temurin licences and notices;
* that the runtime is portable and not installed system-wide;
* that the slim JAR downloads a hash-pinned Temurin archive on a fresh machine, while the older runtime-bearing distribution embeds archives.

The runtime-bearing JAR must be submitted openly for CurseForge moderation. Approval of one exact SHA-256 does not automatically approve a rebuilt or changed JAR.
