# Compiling and validating LWJGL3ify — WDG Edition

## Version source

The project version is supplied by the existing GTNH convention/Git-tag workflow. The WDG foundation remains based on upstream tag `3.0.28` at commit `ed6f8e3`. The bundled-runtime contract does not introduce a WDG build-number scheme or alter tagged-release behavior.

## Supported validation sequence

On macOS/Linux:

```bash
chmod +x gradlew
./gradlew --no-daemon --version
./gradlew --no-daemon setupDecompWorkspace
./gradlew --no-daemon -p buildSrc test
./gradlew --no-daemon clean verifyRepository test build
```

On Windows:

```cmd
gradlew.bat --no-daemon --version
gradlew.bat --no-daemon setupDecompWorkspace
gradlew.bat --no-daemon -p buildSrc test
gradlew.bat --no-daemon clean verifyRepository test build
```

`verifyRepository` is also wired into `check`. It validates stable wrapper, source-root, metadata, compatibility-identity, launcher, Forge-patch, relauncher-packaging, bundled-runtime manifest, documentation, and tracked-source hygiene invariants without starting Minecraft or requiring the large external Java bundle. It conditionally checks tracked files when Git metadata is present and remains usable from a clean source archive without `.git`.

Normal Git checkouts retain the commit timestamp used by `versionJson`. In a clean source archive without `.git`, Gradle configuration and verification remain available; `versionJson` uses the reproducible fallback timestamp `1970-01-01T00:00:00Z` rather than failing configuration. Tagged/repository builds keep the existing Git-derived value.

The focused bundled-runtime contract tests live in `buildSrc/src/test` and must be run explicitly with `./gradlew --no-daemon -p buildSrc test`. Since that command treats `buildSrc` as a standalone build root, `buildSrc/gradle/gradle-daemon-jvm.properties` mirrors the repository's Java 25 daemon criteria; `verifyRepository` rejects any drift between the two files. Production installer fixtures and concurrency/recovery tests live under `src/test/java` and run through the root `test` lifecycle. Continue running both suites through Gradle rather than an IDE-native compiler.

## External Java runtime contract tasks

The normal lifecycle does not require the 264 MB external input. Run these tasks explicitly when validating or packaging it:

```bash
./gradlew --no-daemon verifyJavaRuntimeBundle \
  -PwdgJavaRuntimeBundle="/absolute/path/to/Required Java Packages.zip"

./gradlew --no-daemon packageJavaRuntimeBundle \
  -PwdgJavaRuntimeBundle="/absolute/path/to/Required Java Packages.zip"

./gradlew --no-daemon verifyJavaRuntimeInstallation \
  -PwdgJavaRuntimeBundle="/absolute/path/to/Required Java Packages.zip" \
  -PwdgJavaRuntimePlatform=macos-aarch64

./gradlew --no-daemon verifyAutomaticJavaRuntime \
  -PwdgJavaRuntimeBundle="/absolute/path/to/Required Java Packages.zip" \
  -PwdgJavaRuntimePlatform=macos-aarch64

./gradlew --no-daemon packageBundledJavaClient \
  -PwdgJavaRuntimeBundle="/absolute/path/to/Required Java Packages.zip"

./gradlew --no-daemon verifyBundledJavaClientPackage \
  -PwdgJavaRuntimeBundle="/absolute/path/to/Required Java Packages.zip"
```

`verifyJavaRuntimeBundle` performs cross-platform static inspection and never executes a supplied Java binary. `packageJavaRuntimeBundle` creates and verifies:

```text
build/runtime-packages/lwjgl3ify-wdg-java21-runtimes.zip
```

`verifyJavaRuntimeInstallation` is intentionally outside the normal lifecycle. It installs only the explicit platform into `build/runtime-installation-smoke`, validates the static runtime and completed marker, invokes the installer again to prove reuse and identical returned paths, and never executes Java. `verifyAutomaticJavaRuntime` stages the normalized bundle in an isolated game directory, invokes the production coordinator twice, and executes only a host-compatible packaged Java to verify Java 21, `21.0.11`, Temurin/Adoptium, architecture, and reuse. `packageBundledJavaClient` creates the root-overlay distribution under `build/distributions/`; `verifyBundledJavaClientPackage` deeply validates its mod JAR and nested normalized bundle.

Full supported-platform, manifest, installer security, cache, update, and inspection details are in [docs/BUNDLED_JAVA.md](docs/BUNDLED_JAVA.md).

## Focused build and distribution tasks

```bash
# Main compilation and resources
./gradlew --no-daemon classes

# Main shaded mod artifact and normal lifecycle outputs
./gradlew --no-daemon build

# Forge early-classpath/server launcher artifact
./gradlew --no-daemon forgePatchesJar

# MultiMC/Prism instance package
./gradlew --no-daemon mmcInstanceFiles

# Vanilla-launcher metadata generated from launcher-metadata/version.json
./gradlew --no-daemon versionJson

# Produces all normal assembly outputs, including Forge patches,
# MultiMC/Prism package, and launcher version metadata
./gradlew --no-daemon assemble
```

Expected output locations:

* `build/libs/` — the main lwjgl3ify jar, Forge-patches jar, generated launcher `version.json`, and any convention-produced sources/dev/API artifacts.
* `build/distributions/` — `lwjgl3ify-VERSION-multimc.zip` from `mmcInstanceFiles`.
* `build/source-packages/` — clean repository source archives created by the WDG packaging script.
* `build/runtime-packages/` — generated normalized Java runtime bundles; never commit these artifacts.
* `build/wdg-release/lwjgl3ify-<version>.jar` — moderation-ready one-JAR artifact containing the verified production mod plus the four primary desktop Java runtimes.
* `build/runtime-extensions/` — optional Windows ARM64 and Linux ARM64 archives for `lwjgl3ify/runtime/extensions/`.
* `build/distributions/*-client-with-java21.zip` — legacy compatibility overlay containing the base mod JAR and separate normalized six-platform bundle.

Artifact names include the version supplied by the existing Git-tag/convention build. Do not hard-code a WDG preview suffix in local scripts.

Useful archive checks after a build:

```bash
find build/libs build/distributions -maxdepth 1 -type f -print -exec ls -lh {} \;
jar tf build/libs/*lwjgl3ify*.jar | grep -E 'mcmod.info|META-INF/rfb-plugin|mixins\.lwjgl3ify|relauncher/(forgePatches\.zip|version\.json)|Relauncher'
unzip -Z1 build/distributions/*-multimc.zip | grep -E 'mmc-pack.json|patches/|libraries/.*forgePatches'
```

## Development run tasks

```bash
# Deobfuscated development client/server; explicitly launched with Azul Java 21
./gradlew --no-daemon runClient
./gradlew --no-daemon runServer

# Obfuscated development paths, also configured with the Java 21 launcher
./gradlew --no-daemon runObfClient
./gradlew --no-daemon runObfServer

# Legacy Java start followed by automatic packaged-Java relaunch
./gradlew --no-daemon runClientWithRelauncher \
  -PwdgJavaRuntimeBundle="/absolute/path/to/Required Java Packages.zip"
```

The generated `runClient17`, `runClient21`, `runClient25`, `runServer17`, `runServer21`, and `runServer25` variants are deliberately disabled in this repository; the ordinary run tasks already carry the intended launcher configuration.

Change 004 wires the existing installer only into the legacy client relaunch path. A successful automatic preparation skips settings, selects the packaged Java executable process-locally, and transfers to RFB. Missing default bundles or unsupported hosts may use the preserved manual path; a present corrupt bundle or failed installation stops safely. Dedicated-server behavior remains unchanged. Use `-PwdgRelauncherForceSettings=true` for the recovery UI smoke and `-PwdgRelauncherDisableBundledJava=true` for the manual-path override. Distant Horizons remains deferred.

## Clean source package

The source packager uses the actual working tree when Git metadata exists:

```text
git ls-files --cached --others --exclude-standard
```

It copies tracked files plus intentional untracked source files, excludes ignored/local/generated/archive/secret paths, creates one top-level `lwjgl3ify-wdg/` directory, and verifies the ZIP through its archive listing.

```bash
./scripts/package-source.sh
```

Default output:

```text
build/source-packages/lwjgl3ify-wdg-source.zip
```

A custom output path may be supplied:

```bash
./scripts/package-source.sh "$HOME/Downloads/lwjgl3ify-wdg-source.zip"
```

Independent listing check:

```bash
unzip -Z1 build/source-packages/lwjgl3ify-wdg-source.zip
```


## Change 005 runtime-bearing release artifact

Build the transparent one-JAR candidate with the exact Change 004 runtime input:

```bash
./gradlew --no-daemon packageRuntimeBundledModJar verifyRuntimeBundledModJar \
  verifyRuntimeBundledModJarReproducibility \
  -PwdgJavaRuntimeBundle="/absolute/path/to/Required Java Packages.zip"
```

The generated JAR embeds exactly `windows-x86_64`, `linux-x86_64`, `macos-x86_64`, and `macos-aarch64`. It preserves every byte of each original Temurin archive, including its legal directory. It does not embed Windows ARM64 or Linux ARM64. Produce those optional files with:

```bash
./gradlew --no-daemon packageRuntimeExtensionArchives \
  -PwdgJavaRuntimeBundle="/absolute/path/to/Required Java Packages.zip"
```

The graphical relaunch console is disabled by default. Child output remains in `logs/lwjgl3ify-java21-child.log` with launch-time rotation. Support staff can temporarily restore the console with `-Dlwjgl3ify.wdg.showConsole=true`.

## Change 004h production artifact validation

The only production client mod provider is `productionModArtifact`, backed by `reobfJar.archiveFile`. Expected task roles are:

| Task | Role | Classifier |
| --- | --- | --- |
| `jar` | pre-shadow development intermediate | `dev-preshadow` |
| `shadowJar` | shaded development artifact | `dev` |
| `reobfJar` | complete production-reobfuscated client mod | none |

Never launch the first JAR found in `build/libs`, and never substitute `shadowJar` for `reobfJar` when testing the real client. Run:

```bash
./gradlew --no-daemon clean reobfJar verifyProductionModArtifact
```

The verifier compares the generated and packaged refmaps, validates the `searge` mapping for `MixinMinecraft_Display`, follows the generated MCP/SRG/Notch chain, and inspects the production client class structurally. Production-like packaging and `runClientWithRelauncher` depend on this verification and use the same concrete archive provider.

Set `-PwdgRelauncherGameDirectory=/isolated/game` and `-PwdgRelauncherRuntimeCacheRoot=/isolated/cache` for production-like smoke runs; the staged bundle, config, logs, saves, and managed installation then remain isolated from the ordinary `run/` tree and normal cache.

The Gradle smoke uses direct supervision so the task remains attached until Minecraft closes. A normal close succeeds; a Java 21 crash fails the Gradle task. Normal launcher GUI/stub mode has different lifecycle semantics: the stub, not the already-exited legacy process, owns and reports the modern child's final result.

Run the passing regression:

```bash
./gradlew --no-daemon verifyRelauncherChildExitPropagation
```

The raw deliberate-failure task is expected to return nonzero:

```bash
./gradlew --no-daemon runRelauncherChildExitFailure
```

It is a regression only when the child reports exit 37 and Gradle itself fails. Do not add `--continue` or `ignoreExitValue` to the raw task.
