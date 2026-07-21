# Compiling and validating LWJGL3ify — WDG Edition

## Version source

The project version is supplied by the existing GTNH convention/Git-tag workflow. The Change 001 baseline is upstream tag `3.0.28` at commit `ed6f8e3`. This change does not introduce a WDG build-number scheme or alter tagged-release behavior.

## Supported validation sequence

On macOS/Linux:

```bash
chmod +x gradlew
./gradlew --no-daemon --version
./gradlew --no-daemon setupDecompWorkspace
./gradlew --no-daemon clean verifyRepository test build
```

On Windows:

```cmd
gradlew.bat --no-daemon --version
gradlew.bat --no-daemon setupDecompWorkspace
gradlew.bat --no-daemon clean verifyRepository test build
```

`verifyRepository` is also wired into `check`. It validates stable wrapper, source-root, metadata, compatibility-identity, launcher, Forge-patch, relauncher-packaging, documentation, and tracked-source hygiene invariants without starting Minecraft. It conditionally checks tracked files when Git metadata is present and remains usable from a clean source archive without `.git`.

Normal Git checkouts retain the commit timestamp used by `versionJson`. In a clean source archive without `.git`, Gradle configuration and verification remain available; `versionJson` uses the reproducible fallback timestamp `1970-01-01T00:00:00Z` rather than failing configuration. Tagged/repository builds keep the existing Git-derived value.

There are currently no repository-owned `src/test` test classes, but the `test` lifecycle task must still configure and complete successfully. Future tests should continue to run through Gradle rather than an IDE-native compiler.

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

Change 001 does not require a Minecraft runtime smoke because it changes no runtime Java source, relauncher behavior, JVM arguments, or launcher metadata templates. A later runtime-affecting change should add appropriate client, server, and relauncher smokes.

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
