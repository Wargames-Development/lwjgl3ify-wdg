# Bundled Java 21 runtime contract

## Purpose and boundary

The WDG fork is preparing verified packaged Java 21 runtimes so a later bounded change can select and relaunch with an architecture-correct runtime without asking the user to manage Java manually.

Change 002 defines the source-controlled contract, validates the external runtime input bundle, and packages a normalized generated bundle. Change 003 adds production secure extraction and cache-installation machinery that can be called only with an explicit normalized bundle path, explicit platform ID, and explicit cache root. Production Minecraft startup does **not** invoke that installer yet: host detection, automatic selection, `JvmLocator` changes, Java-selection UI changes, `Relauncher.run` changes, and automatic relaunch remain deferred.

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

The installer does not read `os.name` or `os.arch`, inspect `RelauncherConfig`, change the selected Java index, add entries to the existing Java drop-down, execute a packaged Java binary, or invoke `Relauncher.run`. Those integration decisions belong to a later bounded change. The runtime-side parser loads the canonical classpath manifest instead of importing `buildSrc` classes or maintaining a second set of hashes in Java source.

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

Apache Commons Compress is available on the early runtime classpath through the narrow `runtimeInstallerEmbedded` configuration. The release shadow JAR relocates Commons Compress and its private Commons IO/Codec dependencies beneath `me.eigenraven.lwjgl3ify.internal`, while the existing nested Forge-patches artifact remains intact. Apache licence and notice resources are retained under `META-INF/licenses/`.

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

Secure extraction, deterministic cache installation, scoped locking, marker validation, atomic publication, and interrupted-install recovery are implemented by Change 003. Automatic host selection, packaged-bundle discovery, launcher integration, configuration migration, Java-list changes, and relaunch behavior remain separate later changes.
