#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT="${1:-$ROOT/build/source-packages/lwjgl3ify-wdg-source.zip}"
TOP_LEVEL="lwjgl3ify-wdg"

command -v zip >/dev/null 2>&1 || { echo "ERROR: zip is required." >&2; exit 1; }
command -v unzip >/dev/null 2>&1 || { echo "ERROR: unzip is required." >&2; exit 1; }

STAGE="$(mktemp -d "${TMPDIR:-/tmp}/lwjgl3ify-wdg-source.XXXXXX")"
LISTING="$(mktemp "${TMPDIR:-/tmp}/lwjgl3ify-wdg-listing.XXXXXX")"
FILES_LIST="$(mktemp "${TMPDIR:-/tmp}/lwjgl3ify-wdg-files.XXXXXX")"
cleanup() {
    rm -rf "$STAGE"
    rm -f "$LISTING" "$FILES_LIST"
}
trap cleanup EXIT

DEST="$STAGE/$TOP_LEVEL"
mkdir -p "$DEST" "$(dirname "$OUTPUT")"

is_forbidden_path() {
    local path="/${1#./}"
    case "$path" in
        /.git|/.git/*|/.gradle|/.gradle/*|/build|/build/*|/run|/run/*|/eclipse|/eclipse/*|/.idea|/.idea/*|/.vscode|/.vscode/*|/out|/out/*|/bin|/bin/*|/config|/config/*|/saves|/saves/*|/logs|/logs/*|/crash-reports|/crash-reports/*|/__MACOSX|/__MACOSX/*)
            return 0
            ;;
        */.gradle/*|*/build/*|*/run/*|*/eclipse/*|*/.idea/*|*/.vscode/*|*/logs/*|*/crash-reports/*|*/__MACOSX/*)
            return 0
            ;;
        */.DS_Store|*/Thumbs.db|*/Desktop.ini|*/log.txt|*/.env|*/.env.*|*.log|*.zip|*.tar.gz|*.tgz|*.iml|*.ipr|*.iws|*.pem|*.key|*.p12|*.tmp|*.temp|*.swp|*.swo|*~)
            return 0
            ;;
    esac
    return 1
}

copy_path() {
    local relative="$1"
    if is_forbidden_path "$relative"; then
        return 0
    fi
    local source="$ROOT/$relative"
    if [[ ! -e "$source" && ! -L "$source" ]]; then
        # git ls-files also reports tracked paths deleted in the current working tree.
        return 0
    fi
    mkdir -p "$DEST/$(dirname "$relative")"
    cp -Pp "$source" "$DEST/$relative"
}

if git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    git -C "$ROOT" ls-files -z --cached --others --exclude-standard > "$FILES_LIST"
    while IFS= read -r -d '' path; do
        copy_path "$path"
    done < "$FILES_LIST"
else
    echo "Git metadata is absent; packaging the current clean source tree with the same exclusions." >&2
    (cd "$ROOT" && find . \( -type f -o -type l \) -print0) > "$FILES_LIST"
    while IFS= read -r -d '' path; do
        copy_path "${path#./}"
    done < "$FILES_LIST"
fi

required_staged=(
    "gradlew"
    "gradlew.bat"
    "build.gradle.kts"
    "settings.gradle.kts"
    "gradle.properties"
    "gradle/wrapper/gradle-wrapper.jar"
    "gradle/wrapper/gradle-wrapper.properties"
    "src/main/resources/mcmod.info"
    "README.MD"
    "SETUP.md"
    "COMPILING.md"
    "buildSrc/gradle/gradle-daemon-jvm.properties"
    "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/JavaRuntimeManifest.kt"
    "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/JavaRuntimeBundleSupport.kt"
    "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/VerifyJavaRuntimeBundleTask.kt"
    "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/PackageJavaRuntimeBundleTask.kt"
    "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/VerifyJavaRuntimeInstallationTask.kt"
    "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/VerifyRepositoryTask.kt"
    "buildSrc/src/main/kotlin/me/eigenraven/lwjgl3ify/gradle/VersionJsonTask.kt"
    "buildSrc/src/test/kotlin/me/eigenraven/lwjgl3ify/gradle/JavaRuntimeContractTest.kt"
    "src/main/resources/me/eigenraven/lwjgl3ify/relauncher/runtime/java21-runtime-manifest.json"
    "src/main/resources/META-INF/licenses/Apache-2.0.txt"
    "src/main/resources/META-INF/licenses/commons-compress-NOTICE.txt"
    "src/main/resources/META-INF/licenses/commons-io-NOTICE.txt"
    "src/main/resources/META-INF/licenses/commons-codec-NOTICE.txt"
    "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeInstallationException.java"
    "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimePlatform.java"
    "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeManifest.java"
    "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimePathSafety.java"
    "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeCacheLayout.java"
    "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeInstallResult.java"
    "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeInstallMarker.java"
    "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeBundleReader.java"
    "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeArchiveExtractor.java"
    "src/main/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeInstaller.java"
    "src/test/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeInstallerTest.java"
    "src/test/java/me/eigenraven/lwjgl3ify/relauncher/runtime/RuntimeInstallerSmokeMain.java"
    "docs/BUNDLED_JAVA.md"
    "scripts/package-source.sh"
    "scripts/validate-java-runtime-contract.sh"
)
for required in "${required_staged[@]}"; do
    [[ -f "$DEST/$required" ]] || { echo "ERROR: Required staged file is missing: $required" >&2; exit 1; }
done

rm -f "$OUTPUT"
(
    cd "$STAGE"
    zip -q -y -r "$OUTPUT" "$TOP_LEVEL"
)

unzip -Z1 "$OUTPUT" > "$LISTING"

for required in "${required_staged[@]}"; do
    archive_path="$TOP_LEVEL/$required"
    grep -Fqx "$archive_path" "$LISTING" || {
        echo "ERROR: Required archive member is missing: $archive_path" >&2
        exit 1
    }
done

if grep -Ev "^${TOP_LEVEL}/" "$LISTING" | grep -q .; then
    echo "ERROR: Archive contains entries outside $TOP_LEVEL/." >&2
    grep -Ev "^${TOP_LEVEL}/" "$LISTING" >&2
    exit 1
fi

forbidden_listing_regex='(^|/)(\.git|\.gradle|build|run|eclipse|\.idea|\.vscode|out|bin|config|saves|logs|crash-reports|__MACOSX)(/|$)|(^|/)(\.DS_Store|Thumbs\.db|Desktop\.ini|log\.txt|\.env(\..*)?)$|\.(log|zip|tar\.gz|tgz|iml|ipr|iws|pem|key|p12|tmp|temp|swp|swo)$|~$'
if grep -E "$forbidden_listing_regex" "$LISTING" >/dev/null; then
    echo "ERROR: Archive contains forbidden paths:" >&2
    grep -E "$forbidden_listing_regex" "$LISTING" >&2
    exit 1
fi

member_count="$(wc -l < "$LISTING" | tr -d ' ')"
size_bytes="$(wc -c < "$OUTPUT" | tr -d ' ')"
echo "Created and verified: $OUTPUT"
echo "Archive members: $member_count"
echo "Archive bytes: $size_bytes"
