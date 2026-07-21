# Bundled Java 21 runtime contract

## Purpose and boundary

The WDG fork is preparing verified packaged Java 21 runtimes so a later bounded change can install and select an architecture-correct runtime without asking the user to manage Java manually.

Change 002 defines the source-controlled contract, validates the external runtime input bundle, and packages a normalized generated bundle. It does **not** extract a runtime during Minecraft startup, install one into a cache, detect the current host, modify `JvmLocator`, modify `Relauncher.run`, bypass the current Java-selection UI, or automatically relaunch Minecraft with a packaged runtime.

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

Automatic extraction, atomic installation, host selection, cache locking, crash recovery, launcher integration, and relaunch behavior remain separate later changes.
