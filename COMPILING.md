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

The focused bundled-runtime contract tests live in `buildSrc/src/test` and must be run explicitly with `./gradlew --no-daemon -p buildSrc test`. Since that command treats `buildSrc` as a standalone build root, `buildSrc/gradle/gradle-daemon-jvm.properties` mirrors the repository's Java 25 daemon criteria; `verifyRepository` rejects any drift between the two files. The root `test` lifecycle must also configure and complete successfully. Continue running both through Gradle rather than an IDE-native compiler.

## External Java runtime contract tasks

The normal lifecycle does not require the 264 MB external input. Run these tasks explicitly when validating or packaging it:

```bash
./gradlew --no-daemon verifyJavaRuntimeBundle \
  -PwdgJavaRuntimeBundle="/absolute/path/to/Required Java Packages.zip"

./gradlew --no-daemon packageJavaRuntimeBundle \
  -PwdgJavaRuntimeBundle="/absolute/path/to/Required Java Packages.zip"
```

`verifyJavaRuntimeBundle` performs cross-platform static inspection and never executes a supplied Java binary. `packageJavaRuntimeBundle` creates and verifies:

```text
build/runtime-packages/lwjgl3ify-wdg-java21-runtimes.zip
```

Full supported-platform, manifest, security, update, and inspection details are in [docs/BUNDLED_JAVA.md](docs/BUNDLED_JAVA.md).

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

# Existing experimental legacy-start/relauncher path
./gradlew --no-daemon runClientWithRelauncher
```

The generated `runClient17`, `runClient21`, `runClient25`, `runServer17`, `runServer21`, and `runServer25` variants are deliberately disabled in this repository; the ordinary run tasks already carry the intended launcher configuration.

The bundled-runtime contract does not require a Minecraft runtime smoke because it changes no production Java source, relauncher behavior, JVM arguments, or launcher metadata templates. A later runtime-affecting change must add appropriate client, server, extraction, installation, and relauncher smokes.

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
