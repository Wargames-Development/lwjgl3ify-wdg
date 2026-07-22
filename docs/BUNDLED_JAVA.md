# Bundled Java 21 runtime contract

## Purpose and boundary

The WDG fork provides verified packaged Java 21 runtimes so supported clients can begin on the legacy Java path and relaunch automatically with an architecture-correct runtime without manual Java selection.

Change 002 defines the source-controlled contract, validates the external runtime input bundle, and packages a normalized generated bundle. Change 003 provides secure extraction and deterministic cache installation with explicit inputs. Change 004 adds bounded production client coordination: canonical bundle discovery, host detection, manifest-driven platform selection, installer invocation, explicit launch decisions, process-local Java selection, settings recovery, real Java execution verification, and a client root-overlay package. Distant Horizons and dedicated-server automatic relaunch remain deferred.

The supplied packages are Eclipse Temurin **JREs**, not development JDKs:

* vendor and implementor: Eclipse Adoptium;
* distribution: Temurin;
* implementation version: `Temurin-21.0.11+10`;
* Java version: `21.0.11`;
* Java runtime version: `21.0.11+10-LTS`;
* JVM variant: Hotspot;
* archive root: `jdk-21.0.11+10-jre`.

## Supported platform matrix

The contract contains exactly six platforms:

| Platform ID | Operating system | Architecture | Archive |
| --- | --- | --- | --- |
| `linux-x86_64` | Linux with GNU libc | `x86_64` | TAR.GZ |
| `linux-aarch64` | Linux with GNU libc | `aarch64` | TAR.GZ |
| `macos-x86_64` | macOS | `x86_64` | TAR.GZ |
| `macos-aarch64` | macOS | `aarch64` | TAR.GZ |
| `windows-x86_64` | Windows | `x86_64` | ZIP |
| `windows-aarch64` | Windows | `aarch64` | ZIP |

Canonical architecture names are `x86_64` and `aarch64`. Detection aliases such as `amd64`, `x64`, and `arm64` are recorded separately and do not replace the canonical names.

This contract does not claim support for 32-bit x86, FreeBSD, musl Linux, PowerPC, RISC-V, Android, or any other operating system or architecture. Later runtime selection must fail clearly for unsupported hosts rather than choosing an approximate package.

## Canonical manifest

The single source of truth is:

```text
src/main/resources/me/eigenraven/lwjgl3ify/relauncher/runtime/java21-runtime-manifest.json
```

Schema version `1` records:

* global runtime family, vendor, distribution, Java version, JVM variant, archive root, and platform count;
* stable platform IDs and canonical OS/architecture values;
* accepted future detection aliases;
* original external-bundle paths and filenames;
* normalized generated-bundle paths;
* exact archive formats, byte sizes, and lowercase SHA-256 values;
* Java-home, executable, optional `javaw.exe`, and `release` paths;
* expected release-file properties;
* GNU libc requirements for Linux.

`verifyRepository` parses and validates this JSON without requiring the large external bundle. It rejects malformed schema, duplicate IDs or OS/architecture tuples, unsafe paths, bad hashes, incorrect layouts, unsupported platform claims, and metadata that does not match this Java release.

## External input bundle

Keep `Required Java Packages.zip` outside the Git repository. Do not rename, edit, recompress, or commit its nested runtime archives.

The validator accepts only the six paths recorded in the manifest. It ignores only directory entries and known Finder metadata:

* `__MACOSX/`;
* `.DS_Store`;
* AppleDouble `._*` entries.

Any other payload file causes validation to fail.

## Verify the external bundle

For the complete local validation sequence, use the repository helper. It accepts an explicit path or the `WDG_JAVA_RUNTIME_BUNDLE` environment variable. Without either, it first checks for an unnumbered `Required Java Packages.zip` beside the repository and in `~/Downloads`, then selects the newest numbered match from those locations:

```bash
./scripts/validate-java-runtime-contract.sh \
  "$HOME/Documents/GitHub/Required Java Packages.zip"
```

Shell variable names are case-sensitive: use `$HOME`, not `$Home`. When the repository is `/Users/rhysh/Documents/GitHub/lwjgl3ify-wdg`, the helper can discover `/Users/rhysh/Documents/GitHub/Required Java Packages.zip` automatically, so this also works:

```bash
./scripts/validate-java-runtime-contract.sh
```

Running the script as shown starts a child process only. A success or failure returns to the existing Terminal session; it does not close the window or shell.

The focused tests invoke `buildSrc` as a standalone Gradle build. Because standalone builds have their own project root, `buildSrc/gradle/gradle-daemon-jvm.properties` mirrors the root Java 25 daemon criteria. `verifyRepository` requires both files to remain byte-for-byte identical. This prevents Gradle 9 from falling back to a Java 8 shell launcher for the standalone test build.

macOS/Linux:

```bash
./gradlew --no-daemon verifyJavaRuntimeBundle \
  -PwdgJavaRuntimeBundle="/absolute/path/to/Required Java Packages.zip"
```

Environment-variable alternative:

```bash
export WDG_JAVA_RUNTIME_BUNDLE="/absolute/path/to/Required Java Packages.zip"
./gradlew --no-daemon verifyJavaRuntimeBundle
```

Windows Command Prompt:

```cmd
gradlew.bat --no-daemon verifyJavaRuntimeBundle -PwdgJavaRuntimeBundle="C:\absolute\path\Required Java Packages.zip"
```

The JVM-based validator does not execute any supplied Java binary. It streams SHA-256 calculation, inspects nested ZIP and TAR.GZ members, checks archive roots and path safety, validates contained symlinks/hard links, verifies executable metadata where available, parses each `release` file, and reports one concise success line per platform. Nested Windows ZIPs are staged only to a uniquely named system-temporary file so their central-directory link metadata can be checked; that file is deleted on success or failure.

Substitution or corruption must fail. A file with the right name but the wrong size, checksum, layout, executable, vendor, version, OS, architecture, JVM variant, or libc metadata is not accepted.

## Package the normalized runtime bundle

```bash
./gradlew --no-daemon packageJavaRuntimeBundle \
  -PwdgJavaRuntimeBundle="/absolute/path/to/Required Java Packages.zip"
```

The task depends on external-bundle verification and performs its own verification before writing:

```text
build/runtime-packages/lwjgl3ify-wdg-java21-runtimes.zip
```

The generated ZIP has one top-level directory:

```text
lwjgl3ify-wdg-java21-runtimes/
├── manifest.json
└── runtimes/
    ├── linux-aarch64.tar.gz
    ├── linux-x86_64.tar.gz
    ├── macos-aarch64.tar.gz
    ├── macos-x86_64.tar.gz
    ├── windows-aarch64.zip
    └── windows-x86_64.zip
```

The six nested runtime archives are copied byte-for-byte. They are not decompressed or recompressed, so their manifest SHA-256 values remain unchanged. The outer bundle uses stable member ordering and reproducible timestamps and omits the source wrapper, Finder metadata, repository files, caches, logs, and development-machine paths.

The normalized bundle is a generated 250+ MB build artifact. It is ignored by Git and is not part of the clean source package.

## Inspect the generated bundle

```bash
BUNDLE="build/runtime-packages/lwjgl3ify-wdg-java21-runtimes.zip"

unzip -Z1 "$BUNDLE"
if unzip -Z1 "$BUNDLE" | grep -E '__MACOSX|\.DS_Store|/\._'; then
  echo "Unexpected Finder metadata found" >&2
  false
else
  echo "No Finder metadata found."
fi
```

To recalculate the six embedded hashes on macOS without extracting the runtime contents:

```bash
BUNDLE="build/runtime-packages/lwjgl3ify-wdg-java21-runtimes.zip"

for member in \
  lwjgl3ify-wdg-java21-runtimes/runtimes/linux-aarch64.tar.gz \
  lwjgl3ify-wdg-java21-runtimes/runtimes/linux-x86_64.tar.gz \
  lwjgl3ify-wdg-java21-runtimes/runtimes/macos-aarch64.tar.gz \
  lwjgl3ify-wdg-java21-runtimes/runtimes/macos-x86_64.tar.gz \
  lwjgl3ify-wdg-java21-runtimes/runtimes/windows-aarch64.zip \
  lwjgl3ify-wdg-java21-runtimes/runtimes/windows-x86_64.zip
do
  hash="$(unzip -p "$BUNDLE" "$member" | shasum -a 256 | awk '{print $1}')"
  printf '%s  %s\n' "$hash" "$member"
done
```

## Change 003 secure installer boundary

Production installer code lives under:

```text
src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/
```

Its public operation accepts exactly three caller-supplied values:

* the normalized runtime-bundle ZIP path;
* one explicit manifest platform ID;
* one explicit cache root.

The installer itself still does not read `os.name` or `os.arch`, inspect `RelauncherConfig`, change the selected Java index, or invoke `Relauncher.run`. Change 004 keeps those decisions in a separate coordinator and launch-selection layer. The runtime-side parser loads the canonical classpath manifest instead of importing `buildSrc` classes or maintaining a second set of hashes in Java source.

### Deterministic cache layout

Each runtime is isolated by family, exact runtime version, platform, and full archive SHA-256:

```text
<cache-root>/java-runtimes/<runtime-family>/<runtime-version>/<platform-id>/<archive-sha256>/
```

The Temurin archive root remains beneath that final directory. Java home, console executable, optional Windows GUI executable, and `release` paths are resolved from the canonical manifest. Another Java update, platform, or archive revision therefore cannot collide with the current installation.

### Completed-install marker

A reusable installation contains:

```text
.lwjgl3ify-runtime-install.json
```

Marker schema version `1` records the manifest schema, runtime family and version, platform ID, pinned archive size and SHA-256, archive root, Java-home path, console executable path, optional Windows GUI executable path, and completion state. It contains no external bundle path, user-home path, staging path, secret, or identity timestamp. The marker is written only after extraction and static runtime validation succeed. Missing, malformed, mismatched, or incomplete markers never qualify for reuse.

### Bundle and archive security

Before extraction, the installer verifies that the outer normalized ZIP has one safe top-level directory, one byte-identical bundled manifest, exactly the six canonical nested archives, no duplicate or case-folding collisions, and no unexpected payload. The selected nested archive is streamed to a unique temporary file while its exact byte count and SHA-256 are calculated; it is not loaded into one large byte array.

ZIP and TAR.GZ extraction reject absolute, drive-letter, UNC, URI-like, empty, traversal, overlong, duplicate, case-colliding, outside-root, excessive-entry, and excessive-uncompressed-size paths. Writes are refused through symbolic-link parents. TAR hard links, devices, FIFOs, sockets, and unsupported special entries are rejected. Extraction never calls `tar`, `unzip`, a shell, PowerShell, or `cmd.exe`.

Apache Commons Compress is available on the early runtime classpath through the narrow `runtimeInstallerEmbedded` configuration. The development shadow JAR relocates Commons Compress and its private Commons IO/Codec dependencies beneath `me.eigenraven.lwjgl3ify.internal`, while the existing nested Forge-patches artifact remains intact. Apache licence and notice resources are retained under `META-INF/licenses/`.

### Symbolic links and permissions

The real Linux Temurin archives contain legitimate relative symbolic links in their legal-document trees. Change 003 preserves only links whose path and normalized target remain inside the expected archive root. Links are created after ordinary archive entries, their parents are revalidated through real paths, and later entries cannot write through them. Absolute, drive, UNC, escaping, or unsafe chained targets fail. Filesystems that cannot create a required symbolic link fail clearly rather than replacing it with a text file.

For TAR.GZ runtimes, reasonable owner/group/other permission bits are preserved while setuid, setgid, sticky, ownership, and group metadata are ignored. Unix `bin/java` must remain executable. Windows ZIP installations instead require both `bin/java.exe` and `bin/javaw.exe` and do not invent POSIX requirements. No supplied executable is launched during validation.

### Locking, staging, publication, and recovery

Installation is coordinated by both a JVM-local lock and an operating-system file lock scoped to the deterministic runtime identity. Different platforms or versions do not share one global installation lock. A bounded waiter revalidates the final installation after acquiring the lock and returns it unchanged when valid. Cache hits do not reopen the normalized bundle, rewrite the marker, or replace the final directory.

New extraction occurs in a uniquely named sibling staging directory on the same filesystem as the final path. After static validation and marker creation, publication first attempts `ATOMIC_MOVE`; when the filesystem does not support atomic directory moves, it performs a non-merging same-filesystem move while still holding the lock and then revalidates the published result. Extraction never occurs directly into the final directory.

An incomplete or corrupt final directory is quarantined under the same narrowly scoped parent while the lock is held. Unique stale staging, selected-archive, and quarantine entries for the same runtime identity are removed without following symbolic links. Cleanup verifies containment beneath that runtime cache subtree and refuses paths outside it. A valid neighbouring version or platform is not touched. Failed installation leaves no completed final marker.

## Real installation smoke

The large real-runtime smoke remains separate from ordinary `check` and `build`:

```bash
./gradlew --no-daemon verifyJavaRuntimeInstallation \
  -PwdgJavaRuntimeBundle="/absolute/path/to/Required Java Packages.zip" \
  -PwdgJavaRuntimePlatform=macos-aarch64
```

The task validates and packages the external bundle, compiles the production installer, deletes only its generated location under `build/runtime-installation-smoke`, installs the explicit platform, validates the marker, `release` file, Java paths, and executable permission, then calls the installer a second time. It requires the first result to report installation, the second to report reuse, and both to return identical paths. It prints the installation root, Java home, executable paths, marker path, installed size, and both call outcomes. It never executes Java. Unsupported or omitted platform IDs fail clearly.

Generated normalized bundles and smoke installations stay under `build/`, are ignored by Git, and are excluded from clean source packages.

## Updating to a later Java 21 security release

A Java update is a deliberate contract migration:

1. obtain all six matching Eclipse Temurin JRE packages from the same release family;
2. inspect every outer and nested archive path;
3. confirm one expected root and platform-correct Java-home layout;
4. confirm `java`, and `java.exe` plus `javaw.exe` on Windows;
5. confirm Unix executable permission metadata;
6. inspect all symlink and hard-link targets for root containment;
7. parse and verify each `release` file;
8. recalculate each nested archive’s exact byte size and SHA-256;
9. update the one canonical manifest and its schema only when necessary;
10. update focused fixtures and documentation;
11. run `verifyRepository`, `buildSrc:test`, `test`, `clean build`, external-bundle verification, normalized packaging, archive listing, and embedded hash checks.

Do not accept a new archive merely because its filename resembles the previous package. Unverified substitution weakens the later installation and relaunch boundary.

Secure extraction, deterministic cache installation, scoped locking, marker validation, atomic publication, and interrupted-install recovery remain implemented by Change 003. Change 004 consumes that boundary without weakening it; managed runtime paths remain separate from the preserved manual Java list.

## Change 004 automatic client relaunch

### Bundle discovery

The canonical client-instance payload is:

```text
<game-directory>/lwjgl3ify/runtime/lwjgl3ify-wdg-java21-runtimes.zip
```

Discovery precedence is:

1. `-Dlwjgl3ify.relauncher.runtimeBundle=<path>`;
2. `LWJGL3IFY_RUNTIME_BUNDLE=<path>`;
3. the canonical game-directory path above.

Explicit relative paths resolve against the actual Minecraft game directory. Paths are normalized and must be readable regular files. There is no recursive ZIP scan. A missing default bundle leaves automatic mode unavailable; an invalid explicit override is fatal. The bundle remains outside `mods/`, the mod JAR, Forge patches, launcher metadata, configs, and saves.

### Host selection

Only Windows, macOS, and Linux are canonicalized. Architectures are limited to `x86_64` and `aarch64`; 32-bit x86/ARM, IA64, PowerPC, RISC-V, and unknown values are rejected. The detector produces canonical host values and then asks the canonical manifest for exactly one matching platform. Hashes, archive paths, and runtime versions are never duplicated in host-detection code.

On macOS, an AArch64 process selects `macos-aarch64` directly. An x86_64 process performs bounded, shell-free `/usr/sbin/sysctl` probes for Rosetta translation and ARM64 hardware. Confirmed Rosetta or Apple Silicon selects AArch64; confirmed Intel selects x86_64; unavailable or inconclusive probes conservatively retain the process architecture and log the reason.

On Windows, `PROCESSOR_ARCHITEW6432` and `PROCESSOR_ARCHITECTURE` are inspected through an injectable environment map. Explicit ARM64 evidence selects `windows-aarch64`, including an AMD64 process under emulation. IA64 and 32-bit Windows are unsupported; conflicting non-ARM evidence falls back conservatively to the supported process architecture with a diagnostic.

On Linux, `getconf GNU_LIBC_VERSION` is executed directly with bounded output and timeout. Only confidently established glibc hosts are eligible. musl, missing probes, timeouts, and malformed output leave automatic mode unavailable so the manual Java path remains available.

### Installation and launch selection

When enabled and available, `AutomaticRuntimeCoordinator` loads the canonical classpath manifest, detects the host, selects the manifest platform, and invokes the unchanged Change 003 `RuntimeInstaller` with the normalized bundle, explicit platform ID, and the existing lwjgl3ify OS cache root. `lwjgl3ify.relauncher.runtimeCacheRoot` exists only as a development/diagnostic cache override. The result is validated again by `JavaLaunchSelection`. Bundled executables must remain inside the validated installation, be regular files, and be executable on Unix. Windows direct-log mode uses `java.exe`; the existing GUI/stub mode uses `javaw.exe`. macOS and Linux use `bin/java`.

The effective bundled selection is held only for the current process. `javaInstallationsCache` and `javaInstallation` remain the user's manual recovery list and index; no managed path is inserted or persisted there. Child diagnostics include only `lwjgl3ify.relauncher.managedRuntime`, `lwjgl3ify.relauncher.managedPlatform`, and `lwjgl3ify.relauncher.managedRuntimeVersion`. They contain no user path or secret and do not replace installer validation. The existing RFB guard remains authoritative, with an additional fatal legacy-loop diagnostic.

### Settings and recovery

`useBundledJava` defaults to `true` for old configurations that lack the field. Existing memory, GC, custom JVM option, debug, logging, hidden-settings, and manual Java fields remain intact. Null arrays, a null GC, and an out-of-range Java index are normalized without discarding valid user settings. Malformed JSON fails with its path and is not silently replaced.

A normal successful automatic launch skips the settings dialog. The explicit decision model distinguishes proceed, show settings, and cancel, so `hideSettingsOnLaunch=true` can continue with a valid manual selection instead of silently exiting. Closing settings cancels; Run proceeds. Forced settings shows packaged-runtime status and keeps all manual controls usable.

Recovery overrides:

```text
-Dlwjgl3ify.relauncher.disableBundledJava=true
-Dlwjgl3ify.relauncher.forceSettings=true
```

The disable override bypasses discovery/installation for one launch without deleting a managed runtime or rewriting unrelated config. The force override opens settings even when the managed runtime is ready. A present corrupt/mismatched bundle, checksum failure, extraction failure, lock timeout, invalid installation, unavailable Java executable, or process launch failure fails closed with a recovery hint; it does not silently launch an arbitrary Java.

### Progress and failure reporting

Automatic preparation runs off the Swing event-dispatch thread. A delayed modeless indeterminate dialog appears only when preparation takes long enough to be noticeable, so cache reuse does not flash a progress window. Worker failures return to the main error path. Logs report bundle source, detected host, selected platform, installed/reused state, Java home, and executable without listing extracted files or printing complete Minecraft authentication arguments.

### Development and verification tasks

The large payload remains opt-in:

```bash
./gradlew --no-daemon stageBundledJavaForRelauncher \
  -PwdgJavaRuntimeBundle="/absolute/path/to/Required Java Packages.zip"

./gradlew --no-daemon verifyAutomaticJavaRuntime \
  -PwdgJavaRuntimeBundle="/absolute/path/to/Required Java Packages.zip" \
  -PwdgJavaRuntimePlatform=macos-aarch64

./gradlew --no-daemon packageBundledJavaClient \
  -PwdgJavaRuntimeBundle="/absolute/path/to/Required Java Packages.zip"

./gradlew --no-daemon verifyBundledJavaClientPackage \
  -PwdgJavaRuntimeBundle="/absolute/path/to/Required Java Packages.zip"
```

`stageBundledJavaForRelauncher` copies the normalized bundle byte-for-byte to the canonical development game-directory path without deleting unrelated run data. `verifyAutomaticJavaRuntime` uses isolated build directories, invokes production coordination twice, executes only a runtime compatible with the current host, and checks Java feature 21, runtime `21.0.11`, Temurin/Adoptium identity, selected architecture, and reuse. All six platforms remain statically validated; cross-platform binaries are not falsely reported as executed.

`runClientWithRelauncher` depends on staging only when `wdgJavaRuntimeBundle` is supplied. Optional smoke properties are `wdgRelauncherGameDirectory`, `wdgRelauncherRuntimeCacheRoot`, `wdgRelauncherForceSettings`, and `wdgRelauncherDisableBundledJava`. Set the first two to isolated paths so the production-like smoke does not touch the ordinary development game directory or managed-runtime cache. Ordinary `build`, `check`, `test`, `runClient`, and `runServer` do not generate the 283 MB normalized bundle.

### Client root-overlay package

`packageBundledJavaClient` uses the complete reobfuscated primary mod JAR produced from the shaded intermediate and creates:

```text
build/distributions/lwjgl3ify-wdg-<version>-client-with-java21.zip
└── lwjgl3ify-wdg-client/
    ├── INSTALL.txt
    ├── mods/
    │   └── <complete lwjgl3ify client mod jar>
    └── lwjgl3ify/
        └── runtime/
            └── lwjgl3ify-wdg-java21-runtimes.zip
```

The `-dev.jar` shadow intermediate is never packaged. The normalized bundle is stored byte-for-byte and its six nested runtime archives remain packed. The verifier requires one top-level directory, one complete mod JAR, one normalized bundle, no duplicate/case-colliding paths, no Finder/build/source metadata, all required relauncher/installer/manifest/metadata classes and resources, relocated Commons Compress/IO/Codec, and no Temurin payload in the mod JAR. A generated client package is normally 280+ MB; a macOS AArch64 extracted validation runtime is roughly 158 MB. The ordinary mod JAR remains small.

This overlay is not a complete Distant Horizons modpack. Distant Horizons source, dependencies, configuration, gameplay, publishing, and server work remain later bounded changes.

## Production-like development relaunch

`runClientWithRelauncher` deliberately does not inherit the complete Gradle development runtime. Its Java 8 parent uses a minimal classpath containing lwjgl3ify, Minecraft development bootstrap support, and the UniMixins dependency required by lwjgl3ify itself. Optional test mods such as NEI, CodeChickenCore, GTNHLib, and GTNHExtLib are excluded from this smoke and remain optional development conveniences.

The Java 21 child uses the same relauncher-owned Minecraft, Forge, LWJGL, and launcher libraries as a packaged launch. Only the local reobfuscated dirty lwjgl3ify JAR and the explicit UniMixins smoke support JAR are added to that child, together with the Mixin tweaker. This avoids leaking deobfuscated Minecraft JARs, ForgeGradle tweakers, or alternate LWJGL versions across the Java handoff while still testing the current local build.

## Change 004h verified production artifact and supervised smoke

The complete client mod is the single unclassified output of `reobfJar`. The plain `jar` output is the `dev-preshadow` intermediate and `shadowJar` is the `dev` development shadow; neither is valid against the real obfuscated Minecraft client. `productionModArtifact` is the one explicit Gradle provider reused by `verifyProductionModArtifact`, `runClientWithRelauncher`, `packageBundledJavaClient`, and packaged-client verification. No consumer scans `build/libs`, sorts filenames, or chooses a JAR by timestamp.

RetroFuturaGradle's Mixin annotation processor writes `build/tmp/mixins/mixins.lwjgl3ify.refmap.json` and `build/tmp/mixins/mixins.srg`. Change 004h declares the generated refmap as a semantic input of `processResources`, `shadowJar`, and `reobfJar`, preventing an old final JAR from remaining up to date after the refmap changes. `verifyProductionModArtifact` then requires the final JAR refmap to be byte-identical to the current generated refmap. It parses the `searge` environment, resolves `MixinMinecraft_Display`, derives the MCP-to-SRG and Notch-to-SRG chain from RFG's generated mappings, and inspects the real production Minecraft client bytecode. The mapped owner and display-update method must exist, and that method must contain exactly one access to the mapped boolean fullscreen field. The injection remains required; it is not weakened or bypassed.

Run the verifier directly:

```bash
./gradlew --no-daemon verifyProductionModArtifact
```

Its metadata is written to `build/verification/production-mod-artifact.properties`. The task logs the exact artifact path, archive size, production JAR SHA-256, refmap SHA-256, resolved SRG method/field, and mapped production owner. `verifyBundledJavaClientPackage` additionally proves the packaged mod JAR has the same SHA-256 as this verified output.

`runClientWithRelauncher` forces the direct supervised mode only for this Gradle smoke. The Java 8 parent remains attached, streams stay inherited, and it waits for the actual Java 21 Minecraft child. Normal close returns zero; any child failure is returned through `runtimeExit` so Gradle fails instead of printing a false success. The normal GUI/stub mode remains non-circular: the legacy parent exits after the readiness handshake, while the Java 17 stub waits for its actual Java 21 child and exits with that result. The already-exited legacy parent cannot retroactively receive the stub result.

The deterministic process regression is:

```bash
./gradlew --no-daemon verifyRelauncherChildExitPropagation
```

`runRelauncherChildExitFailure` is deliberately expected to fail with child exit code 37. Use the documented wrapper in `COMPILING.md`; a raw invocation must not report `BUILD SUCCESSFUL`.
