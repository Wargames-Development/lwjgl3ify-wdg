#!/usr/bin/env bash
set +e

repository="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
runtime_bundle="${2:-}"
log_root="${3:-$repository/validation-logs/change005-static}"
expected_head="7500f19e88a47e6ecc587f33789766bbac365d19"

if [[ ! -d "$repository/.git" ]]; then
    echo "ERROR: Repository not found: $repository" >&2
    exit 1
fi
if [[ ! -f "$runtime_bundle" ]]; then
    echo "ERROR: Exact Required Java Packages ZIP not found: $runtime_bundle" >&2
    exit 1
fi
if [[ "$(git -C "$repository" branch --show-current)" != "master" ]]; then
    echo "ERROR: Expected branch master." >&2
    exit 1
fi
if [[ "$(git -C "$repository" rev-parse HEAD)" != "$expected_head" ]]; then
    echo "ERROR: Expected completed Change 004 HEAD $expected_head." >&2
    exit 1
fi

mkdir -p "$log_root"
rm -f "$log_root"/*.log "$log_root/summary.txt"

passed=0
failed=0
index=0

run_check() {
    local label="$1"
    shift
    index=$((index + 1))
    local safe_label
    safe_label="$(printf '%s' "$label" | tr -cs 'A-Za-z0-9._-' '-')"
    local log_file
    log_file="$(printf '%s/%02d-%s.log' "$log_root" "$index" "$safe_label")"

    echo
    echo "==> $label"
    (
        cd "$repository"
        "$@"
    ) >"$log_file" 2>&1
    local rc=$?
    if [[ $rc -eq 0 ]]; then
        echo "PASS ($rc) $log_file"
        passed=$((passed + 1))
    else
        echo "FAIL ($rc) $log_file"
        tail -n 120 "$log_file"
        failed=$((failed + 1))
    fi
}

run_check "gradle-version" ./gradlew --no-daemon --version
run_check "spotless-apply" ./gradlew --no-daemon spotlessApply
run_check "spotless-check" ./gradlew --no-daemon spotlessCheck
run_check "buildsrc-tests" ./gradlew --no-daemon -p buildSrc test
run_check "checkstyle" ./gradlew --no-daemon checkstyleMain checkstyleTest
run_check "verify-repository" ./gradlew --no-daemon verifyRepository
run_check "root-tests" ./gradlew --no-daemon test
run_check "clean-build" ./gradlew --no-daemon clean build
run_check "verify-production-mod" ./gradlew --no-daemon verifyProductionModArtifact
run_check "verify-runtime-inputs" ./gradlew --no-daemon verifyJavaRuntimeBundle "-PwdgJavaRuntimeBundle=$runtime_bundle"
run_check "package-normalized-runtime" ./gradlew --no-daemon packageJavaRuntimeBundle "-PwdgJavaRuntimeBundle=$runtime_bundle"
run_check "package-and-verify-one-jar" ./gradlew --no-daemon packageRuntimeBundledModJar verifyRuntimeBundledModJar "-PwdgJavaRuntimeBundle=$runtime_bundle"
run_check "verify-one-jar-reproducibility" ./gradlew --no-daemon verifyRuntimeBundledModJarReproducibility "-PwdgJavaRuntimeBundle=$runtime_bundle"
run_check "package-extension-architectures" ./gradlew --no-daemon packageRuntimeExtensionArchives "-PwdgJavaRuntimeBundle=$runtime_bundle"
run_check "configuration-cache-first" ./gradlew --no-daemon --configuration-cache verifyRuntimeBundledModJar "-PwdgJavaRuntimeBundle=$runtime_bundle"
run_check "configuration-cache-reuse" ./gradlew --no-daemon --configuration-cache verifyRuntimeBundledModJar "-PwdgJavaRuntimeBundle=$runtime_bundle"
run_check "source-package" ./scripts/package-source.sh "$repository/build/source-packages/lwjgl3ify-wdg-change005-source.zip"

{
    echo "passed=$passed"
    echo "failed=$failed"
    echo "head=$(git -C "$repository" rev-parse HEAD)"
    echo "branch=$(git -C "$repository" branch --show-current)"
    echo "runtimeBundle=$(cd "$(dirname "$runtime_bundle")" && pwd)/$(basename "$runtime_bundle")"
    echo "runtimeBundledJar=$repository/build/wdg-release"
    echo "extensionArchives=$repository/build/runtime-extensions"
} | tee "$log_root/summary.txt"

echo
echo "Git status:"
git -C "$repository" status --short

echo
if [[ $failed -eq 0 ]]; then
    echo "LWJGL3IFY-WDG CHANGE 005 STATIC AND PACKAGE VALIDATION PASSED"
    exit 0
fi

echo "LWJGL3IFY-WDG CHANGE 005 VALIDATION FAILED: $failed check(s)"
exit 1
