# CurseForge moderation disclosure for the WDG runtime-bearing JAR

This document is a technical disclosure for reviewers and pack maintainers. It does not claim or imply that a file has been approved before CurseForge has reviewed that exact file.

## Why the JAR contains Java runtimes

Minecraft Forge 1.7.10 launchers frequently start under Java 8, while the maintained lwjgl3ify and Distant Horizons client stack requires Java 21. The WDG artifact removes a confusing manual Java-selection step by carrying portable Eclipse Temurin JRE archives inside the lwjgl3ify mod that performs the relaunch.

The runtime is not installed into Windows, macOS, or Linux. lwjgl3ify extracts only the archive matching the detected operating system and architecture into its own modpack-local managed cache. It does not modify `JAVA_HOME`, the system path, launcher accounts, authentication tokens, or another Minecraft instance.

## Embedded primary platforms

The ordinary JAR embeds exact, hash-pinned Eclipse Temurin 21.0.11+10 JRE archives for:

* Windows x86-64;
* Linux x86-64;
* macOS x86-64;
* macOS AArch64.

Windows AArch64 and Linux AArch64 are not hidden in the primary JAR. They are separately generated optional extension archives and are accepted only under their exact canonical filenames in `lwjgl3ify/runtime/extensions/`.

## Security properties

* No runtime is fetched from the network.
* No executable is disguised, renamed to evade review, or generated from downloaded bytes.
* The exact nested archive size and SHA-256 must match the checked-in runtime manifest.
* The original archive licence, notice, and `legal/` contents remain intact.
* Archive extraction rejects path traversal, absolute paths, unsafe links, duplicate paths, and unexpected roots.
* Existing valid installations are reused by a hash-addressed cache.
* A corrupt present runtime fails closed rather than silently falling back to an unverified download.
* The graphical console is hidden only as a user-interface improvement; child output remains in `logs/lwjgl3ify-java21-child.log`.

## Source and attribution

* WDG fork source: `https://github.com/Wargames-Development/lwjgl3ify-wdg`
* Upstream lwjgl3ify source: `https://github.com/GTNewHorizons/lwjgl3ify`
* Eclipse Temurin project: `https://adoptium.net/temurin/`

The repository retains upstream authorship and licensing. Runtime package creation records the exact Java distribution, version, platform matrix, original sizes, original SHA-256 values, and final JAR SHA-256. The nested runtime archives preserve their supplied licensing and legal files.

## Review and release procedure

1. Build the base production-reobfuscated lwjgl3ify JAR.
2. Verify the exact six-platform external runtime input.
3. Create the normalized runtime bundle reproducibly.
4. Create the one-JAR artifact with only the four primary platforms.
5. Verify that every base-JAR entry is byte-identical.
6. Verify all embedded runtime sizes and hashes.
7. Build the one-JAR artifact twice and require byte-identical output.
8. Record the final SHA-256.
9. Submit that exact file openly for moderation with this disclosure.
10. Do not treat a rebuilt file as approved merely because an earlier hash was approved.

The same approved JAR should be reused unchanged by CurseForge, Technic, WDG Solder, and manual pack distribution.
