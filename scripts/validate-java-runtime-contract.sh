#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CURRENT_STEP="initialization"

report_failure() {
    local status=$?
    printf '\nERROR: %s failed with status %s.\n' "$CURRENT_STEP" "$status" >&2
    printf 'The validation script stopped, but your Terminal remains open.\n' >&2
    return "$status"
}
trap report_failure ERR

file_size() {
    local path="$1"
    if stat -f '%z' "$path" >/dev/null 2>&1; then
        stat -f '%z' "$path"
    else
        stat -c '%s' "$path"
    fi
}

resolve_bundle() {
    if [[ $# -gt 1 ]]; then
        printf 'Usage: %s [path-to-Required-Java-Packages.zip]\n' "$0" >&2
        return 2
    fi

    if [[ $# -eq 1 && -n "$1" ]]; then
        printf '%s\n' "$1"
        return
    fi

    if [[ -n "${WDG_JAVA_RUNTIME_BUNDLE:-}" ]]; then
        printf '%s\n' "$WDG_JAVA_RUNTIME_BUNDLE"
        return
    fi

    local repository_parent
    repository_parent="$(cd "$ROOT/.." && pwd -P)"

    local preferred=(
        "$repository_parent/Required Java Packages.zip"
        "$HOME/Downloads/Required Java Packages.zip"
    )
    local candidate
    for candidate in "${preferred[@]}"; do
        if [[ -f "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    done

    local candidates=()
    local search_directory
    for search_directory in "$repository_parent" "$HOME/Downloads"; do
        if [[ -d "$search_directory" ]]; then
            shopt -s nullglob
            candidates+=("$search_directory"/Required\ Java\ Packages*.zip)
            shopt -u nullglob
        fi
    done

    if [[ ${#candidates[@]} -eq 0 ]]; then
        printf 'No Java runtime bundle was supplied.\n' >&2
        printf 'Pass its path as the first argument or set WDG_JAVA_RUNTIME_BUNDLE.\n' >&2
        printf 'Automatic discovery checked:\n' >&2
        printf '  %s/Required Java Packages*.zip\n' "$repository_parent" >&2
        printf '  %s/Required Java Packages*.zip\n' "$HOME/Downloads" >&2
        return 1
    fi

    local newest="${candidates[0]}"
    for candidate in "${candidates[@]:1}"; do
        if [[ "$candidate" -nt "$newest" ]]; then
            newest="$candidate"
        fi
    done

    if [[ ${#candidates[@]} -gt 1 ]]; then
        printf 'Found %s matching bundles; using the newest: %s\n' \
            "${#candidates[@]}" "$newest" >&2
    fi
    printf '%s\n' "$newest"
}

run_step() {
    CURRENT_STEP="$1"
    shift
    printf '\n===== %s =====\n' "$CURRENT_STEP"
    "$@"
}

BUNDLE="$(resolve_bundle "${1:-}")"
if [[ ! -f "$BUNDLE" ]]; then
    printf 'Java runtime bundle does not exist or is not a file: %s\n' "$BUNDLE" >&2
    false
fi
BUNDLE="$(cd "$(dirname "$BUNDLE")" && pwd -P)/$(basename "$BUNDLE")"

cd "$ROOT"
chmod +x gradlew scripts/package-source.sh scripts/validate-java-runtime-contract.sh

printf 'Repository: %s\n' "$ROOT"
printf 'External Java bundle: %s\n' "$BUNDLE"
printf 'External bundle bytes: %s\n' "$(file_size "$BUNDLE")"
printf 'External bundle SHA-256: '
shasum -a 256 "$BUNDLE" | awk '{print $1}'

run_step "Gradle version" ./gradlew --no-daemon --version
run_step "Repository verification" ./gradlew --no-daemon verifyRepository
run_step "Focused buildSrc tests" ./gradlew --no-daemon -p buildSrc test
run_step "Root test lifecycle" ./gradlew --no-daemon test
run_step "Clean build" ./gradlew --no-daemon clean build
run_step "External Java bundle verification" \
    ./gradlew --no-daemon verifyJavaRuntimeBundle "-PwdgJavaRuntimeBundle=$BUNDLE"
run_step "Normalized Java bundle packaging" \
    ./gradlew --no-daemon packageJavaRuntimeBundle "-PwdgJavaRuntimeBundle=$BUNDLE"

NORMALIZED="$ROOT/build/runtime-packages/lwjgl3ify-wdg-java21-runtimes.zip"
CURRENT_STEP="normalized runtime bundle presence check"
[[ -f "$NORMALIZED" ]]

printf '\n===== Normalized runtime bundle =====\n'
printf 'Path: %s\n' "$NORMALIZED"
printf 'Bytes: %s\n' "$(file_size "$NORMALIZED")"
printf 'SHA-256: '
shasum -a 256 "$NORMALIZED" | awk '{print $1}'

CURRENT_STEP="normalized runtime bundle listing"
unzip -Z1 "$NORMALIZED"

printf '\n===== Packaged runtime member SHA-256 values =====\n'
for member in \
    "lwjgl3ify-wdg-java21-runtimes/runtimes/linux-aarch64.tar.gz" \
    "lwjgl3ify-wdg-java21-runtimes/runtimes/linux-x86_64.tar.gz" \
    "lwjgl3ify-wdg-java21-runtimes/runtimes/macos-aarch64.tar.gz" \
    "lwjgl3ify-wdg-java21-runtimes/runtimes/macos-x86_64.tar.gz" \
    "lwjgl3ify-wdg-java21-runtimes/runtimes/windows-aarch64.zip" \
    "lwjgl3ify-wdg-java21-runtimes/runtimes/windows-x86_64.zip"
do
    CURRENT_STEP="hashing normalized member $member"
    member_bytes="$(unzip -p "$NORMALIZED" "$member" | wc -c | tr -d '[:space:]')"
    member_hash="$(unzip -p "$NORMALIZED" "$member" | shasum -a 256 | awk '{print $1}')"
    printf '%s bytes  %s  %s\n' "$member_bytes" "$member_hash" "$member"
done

SOURCE_PACKAGE="$ROOT/build/source-packages/lwjgl3ify-wdg-change-002-validation-source.zip"
run_step "Clean source-package creation" ./scripts/package-source.sh "$SOURCE_PACKAGE"
CURRENT_STEP="clean source-package presence check"
[[ -f "$SOURCE_PACKAGE" ]]

printf '\n===== Validation source package =====\n'
printf 'Path: %s\n' "$SOURCE_PACKAGE"
printf 'Bytes: %s\n' "$(file_size "$SOURCE_PACKAGE")"
printf 'SHA-256: '
shasum -a 256 "$SOURCE_PACKAGE" | awk '{print $1}'

printf '\n===== Final Git status =====\n'
git status --short --untracked-files=all

trap - ERR
printf '\nChange 002 validation completed successfully. Your Terminal remains open.\n'
