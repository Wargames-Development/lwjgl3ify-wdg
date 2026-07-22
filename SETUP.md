# LWJGL3ify — WDG Edition development setup

This repository keeps the upstream RetroFuturaGradle/GTNH convention build. It is **not** a Gradle 4.5 or all-Java-8 Forge project. Do not replace the wrapper, import it with Java 8 as the Gradle JVM, or flatten its toolchains.

## Required tools and Java roles

The source of truth is the checked-in build configuration:

| Role | Required Java | Where it is declared |
| --- | --- | --- |
| Gradle wrapper client | Java 17 or newer can start Gradle 9.3.1 | Gradle 9 runtime requirement |
| Gradle daemon and `buildSrc` | Java 25; use this as the IntelliJ Gradle JVM | `gradle/gradle-daemon-jvm.properties`, `buildSrc/build.gradle.kts` |
| Main, generated, utility, and test Java compilation | Azul Java 17; Jabel permits modern syntax while `--release 8` keeps the normal output on the Java 8 API/bytecode boundary | `enableModernJavaSyntax = jabel` in `gradle.properties` and GTNH convention 2.0.25 |
| `hotswap` and `relauncherStub` source sets | Azul Java 17 | `build.gradle.kts` |
| Normal development `runClient`, `runServer`, `runObfClient`, and `runObfServer` tasks | Azul Java 21 | `build.gradle.kts` |
| Generated vanilla-launcher metadata | Requests Java 25 (`java-runtime-epsilon`) | `launcher-metadata/version.json` |
| `runClientWithRelauncher` initial legacy launch path | Azul Java 8, inherited from RetroFuturaGradle’s Minecraft toolchain before the existing relauncher transfers execution | RetroFuturaGradle run-task setup and the existing relauncher design |

The release workflow installs Java 8, 17, 21, and the daemon JVM version. Gradle toolchain provisioning may download missing toolchains on the first run. Internet access is therefore required for a clean setup unless the matching toolchains and dependencies are already cached.

For the least surprising setup, install an architecture-native JDK 25 and use it for the shell and IntelliJ Gradle JVM. Let Gradle provision the project-specific Azul Java 8, 17, and 21 toolchains.

The separately supplied Eclipse Temurin Java 21 packages are runtime JRE inputs for bundle generation, automatic-runtime verification, client-overlay packaging, and the legacy-start relaunch smoke; they are not replacements for the development JDKs above. They are not required by ordinary `check`, `build`, `runClient`, or `runServer`. Keep the external ZIP outside the repository and supply it only to the opt-in tasks documented in [docs/BUNDLED_JAVA.md](docs/BUNDLED_JAVA.md). `runClientWithRelauncher` stages the normalized bundle into the development game directory only when `wdgJavaRuntimeBundle` is supplied and uses an isolated build cache by default.

## Packaged Java development input

The canonical client-instance location is:

```text
<game-directory>/lwjgl3ify/runtime/lwjgl3ify-wdg-java21-runtimes.zip
```

For development, pass the external package bundle to the opt-in staging or relaunch task:

```bash
./gradlew --no-daemon stageBundledJavaForRelauncher \
  -PwdgJavaRuntimeBundle="/absolute/path/to/Required Java Packages.zip"

./gradlew --no-daemon runClientWithRelauncher \
  -PwdgJavaRuntimeBundle="/absolute/path/to/Required Java Packages.zip"
```

The production locator checks the explicit `lwjgl3ify.relauncher.runtimeBundle` system property, then `LWJGL3IFY_RUNTIME_BUNDLE`, then the canonical game-directory path. Recovery properties are `-Dlwjgl3ify.relauncher.disableBundledJava=true` and `-Dlwjgl3ify.relauncher.forceSettings=true`.

## Platform notes

### macOS Apple Silicon

Use an AArch64 JDK 25 for Gradle. Do not run IntelliJ under Rosetta merely to imitate an Intel setup. The checked-in daemon criteria and LWJGL native matrix include macOS AArch64 support. If a dependency or launcher-specific smoke later requires Rosetta, keep that as a separate runtime test rather than changing the project toolchain.

### macOS Intel

Use an x86_64 JDK 25 for Gradle. The daemon criteria include a macOS x86_64 provisioning entry.

### Windows x64

Install a 64-bit JDK 25 and select it as IntelliJ's Gradle JVM. Run `gradlew.bat`; Git for Windows is recommended because source packaging uses Git's working-tree file list. The packaging script can be run from Git Bash or WSL.

### Linux x64 and ARM64

Use an architecture-matching JDK 25. The daemon criteria and LWJGL native declarations include Linux x86_64 and AArch64 entries. Native runtime support still depends on the selected launcher and complete modpack.

## First setup

From the repository root:

```bash
chmod +x gradlew
./gradlew --no-daemon --version
./gradlew --no-daemon setupDecompWorkspace
```

On Windows Command Prompt:

```cmd
gradlew.bat --no-daemon --version
gradlew.bat --no-daemon setupDecompWorkspace
```

`setupDecompWorkspace` prepares the RetroFuturaGradle decompiled/patched workspace. Re-run it after relevant Forge, mapping, convention-plugin, or generated-workspace changes; it is not necessary before every ordinary source edit.

## IntelliJ IDEA

1. Install/configure a JDK 25 matching the host architecture.
2. Open the repository root containing `settings.gradle.kts`; do not import it as a legacy ForgeGradle 1.2 project.
3. Select **Gradle from: Wrapper**.
4. Set **Gradle JVM** to JDK 25.
5. Keep **Build and run using Gradle** and **Run tests using Gradle** enabled. Delegated builds preserve the custom source sets, generated sources, remapping, shading, and packaging behavior.
6. Run `setupDecompWorkspace`, then reload the Gradle project.

`src/generated/java` is deliberately added to the main source set. IntelliJ should also show the `util`, `hotswap`, and `relauncherStub` source sets plus RetroFuturaGradle-managed source sets such as patched Minecraft, Forge patches, injected tags, and launcher sources. Do not manually move or reclassify these directories to make the IDE look like a simple Forge mod.

## Common Java/toolchain failures

* **“Unsupported class file major version” while configuring `buildSrc`:** Gradle is not using the Java 25 daemon criteria or an old IDE is intercepting the build. Set IntelliJ's Gradle JVM to JDK 25 and reload.
* **The wrapper will not start under Java 8:** expected. Gradle 9.3.1 needs a modern wrapper JVM. Use JDK 25 for Gradle; Java 8 is only a managed legacy toolchain/runtime role.
* **No matching Azul Java 17/21 toolchain:** allow toolchain downloads, install matching Azul JDKs, or repair network/proxy access. Do not replace the declared vendor/version to hide the failure.
* **Toolchain download fails:** confirm HTTPS access to the Gradle distribution, GTNH Maven, Maven Central/Sonatype, and the Foojay provisioning URLs recorded in `gradle/gradle-daemon-jvm.properties`.
* **Generated or Forge classes are unresolved in IntelliJ:** run `setupDecompWorkspace`, reload Gradle, and confirm delegated Gradle builds remain enabled.

See [COMPILING.md](COMPILING.md) for validation, packaging, run tasks, and output locations.

## Production-like relaunch smoke

Before using `runClientWithRelauncher`, run `verifyProductionModArtifact`. Supply `-PwdgRelauncherGameDirectory` and `-PwdgRelauncherRuntimeCacheRoot` when an isolated production-like smoke is required. The smoke uses the exact unclassified `reobfJar` output and rejects a development/pre-shadow JAR, a stale refmap, a second lwjgl3ify JAR on the child classpath, or a production artifact whose SHA-256 differs from the verified task output. The smoke deliberately stays attached until the Java 21 Minecraft process exits so failures propagate to Gradle.
