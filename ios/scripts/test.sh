#!/usr/bin/env bash
# Runs the full iOS Phase 0 test suite under a 300s wall-clock watchdog (AGENTS.md mandate):
#   convert-strings.py --check (catalog must already be up to date) -> xcodegen generate ->
#   xcodebuild test (iPhone 17 + iPad Pro 13-inch (M5), one invocation) -> swift test
#   (FitrahAPI + InnerTubeKit packages) -> xcodebuild build (Release, simulator SDK -- compiles the
#   non-DEBUG paths).
# Per-test limit: 60s -- XCTest rounds `defaultTestExecutionTimeAllowance` up to 60s and Swift
# Testing's own floor is also one minute, so 60s is the real effective limit regardless of the
# number configured (FitrahTube.xctestplan sets 60 to match); CLAUDE.md's 30s note is a
# cross-platform default this iOS suite can't hit and is amended separately. Wall-clock: 300s.
# $RESULTS (xcresult bundle + watchdog marker) is removed on exit unless KEEP_RESULTS=1 is set.
# Override simulators with IPHONE_SIM / IPAD_SIM env vars, e.g. IPHONE_SIM="iPhone 16" ./test.sh.
# Invoke from the repo root (`ios/scripts/test.sh`) -- the first stage's path is repo-root-relative.
set -uo pipefail
set -m

# XcodeGen lives in ~/.local/bin on this machine, same as `screenshots.sh` (gate wave-4 V10) --
# without this the gate script fails to find `xcodegen` in any shell whose profile didn't add it,
# while its sibling script succeeds.
PATH="$HOME/.local/bin:$PATH"

# Plan C: validates the published ios-remote-config.json through the real decoder + sanitizer
# (RemoteConfigTests.thePublishedRepoRootConfigSurvivesSanitizing). Absent -> the test skips, so a
# checkout without the file (or before the merge to main) is green.
export IOS_REMOTE_CONFIG_PATH="$(cd "$(dirname "$0")/../.." && pwd)/ios-remote-config.json"

IPHONE_SIM="${IPHONE_SIM:-iPhone 17}"
IPAD_SIM="${IPAD_SIM:-iPad Pro 13-inch (M5)}"

# Alternatives that actually fire on this Xcode: swift test's summary, xcodebuild's per-destination and per-test lines, and generic TEST SUCCEEDED/FAILED / error: lines.
SUMMARY='Test run with|Testing (passed|failed) on|Test case .* failed|TEST (SUCCEEDED|FAILED)|error:'

# G2: the grep'd summary above only says a device failed, not which test or why. On a non-zero
# xcodebuild exit, walk the .xcresult bundle and print every failed node's name plus any
# "Failure Message" children (the Swift Testing expectation text) beneath it.
report_failures() {
    local bundle="$1"
    [ -d "$bundle" ] || return 0
    xcrun xcresulttool get test-results tests --path "$bundle" 2>/dev/null | python3 -c '
import json, sys

def messages(node):
    for child in node.get("children", []):
        if child.get("nodeType") == "Failure Message":
            yield child.get("name", "")
        else:
            yield from messages(child)

def walk(nodes):
    for node in nodes:
        if node.get("nodeType") == "Test Case" and node.get("result") == "Failed":
            print("FAILED: " + str(node.get("name")))
            for msg in messages(node):
                print(f"  {msg}")
        walk(node.get("children", []))

try:
    data = json.load(sys.stdin)
except ValueError:
    sys.exit(0)
walk(data.get("testNodes", []))
'
}

run_all() {
    # Phase 4 Task 1: must precede `xcodegen generate` below -- that is what makes the app target's
    # `sources` glob pick the plist up. Exits 0 with a notice when the (USER-BLOCKED, git-ignored)
    # source file is absent, so a checkout without it gates green.
    echo "== copy-firebase-plist.sh =="
    bash ios/scripts/copy-firebase-plist.sh || return $?

    # Repo-root-relative: this must run before the `cd` below (invoke test.sh from the repo root).
    echo "== convert-strings.py --check =="
    python3 ios/scripts/convert-strings.py --check || return $?

    cd "$(dirname "$0")/.."

    # CF-D-7: the Cast SDK is vendored, not committed (ios/Vendor/ is gitignored), so a fresh
    # checkout heals itself here. This is a ONE-TIME ~40 MB network fetch that counts against the
    # 300s watchdog on that first run only -- every later run finds the xcframework and no-ops.
    [ -d Vendor/GoogleCast.xcframework ] || ./scripts/fetch-cast-sdk.sh || return $?

    xcodegen generate || return $?

    echo "== $IPHONE_SIM + $IPAD_SIM =="
    xcodebuild test \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        -destination "platform=iOS Simulator,name=$IPHONE_SIM" \
        -destination "platform=iOS Simulator,name=$IPAD_SIM" \
        -resultBundlePath "$RESULTS/FitrahTube.xcresult" \
        -derivedDataPath DerivedData \
        2>&1 | grep -E "$SUMMARY"
    local xcodebuild_status=${PIPESTATUS[0]}
    if [ "$xcodebuild_status" -ne 0 ]; then
        report_failures "$RESULTS/FitrahTube.xcresult"
        return "$xcodebuild_status"
    fi

    echo "== FitrahAPI package =="
    (cd Packages/FitrahAPI && swift test) 2>&1 | grep -E "$SUMMARY"
    local package_status=${PIPESTATUS[0]}
    if [ "$package_status" -ne 0 ]; then
        return "$package_status"
    fi

    # InnerTubeKit's own 90 package tests were never run by this gate -- `xcodebuild test` builds the
    # package as a dependency but runs only the app's test targets, so a parser/resolver regression
    # got through green. `LiveResolveTests` stays off (it is `.enabled(if: INNERTUBE_LIVE == 1)`), so
    # this adds no network calls.
    echo "== InnerTubeKit package =="
    (cd Packages/InnerTubeKit && swift test) 2>&1 | grep -E "$SUMMARY"
    local innertube_status=${PIPESTATUS[0]}
    if [ "$innertube_status" -ne 0 ]; then
        return "$innertube_status"
    fi

    # Debug is what the test steps above compile; Release flips DEBUG off (AppContainer.swift's
    # #else branch, FitrahTubeApp.swift's #else branch) so it must build too. Simulator SDK ->
    # no code signing required.
    echo "== Release build (simulator SDK) =="
    xcodebuild build \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        -configuration Release \
        -destination "platform=iOS Simulator,name=$IPHONE_SIM" \
        -derivedDataPath DerivedData \
        2>&1 | grep -E "$SUMMARY"
    local release_status=${PIPESTATUS[0]}
    return "$release_status"
}

RESULTS=$(mktemp -d)
if [ "${KEEP_RESULTS:-0}" != "1" ]; then
    trap 'rm -rf "$RESULTS"' EXIT
fi

run_all &
pid=$!
# A-M13: re-check the job group is still alive before claiming a timeout. If `sleep 300` expires in
# the window between `wait "$pid"` returning and the `kill` on the watchdog below, the marker was
# still written and a passing run exited 124 -- a sub-millisecond race, but a non-deterministic CI
# failure is expensive to chase.
( sleep 300; kill -0 -"$pid" 2>/dev/null || exit 0; touch "$RESULTS/killed"; kill -TERM -- -"$pid" 2>/dev/null ) &
wd=$!

trap 'kill -- -"$pid" -"$wd" 2>/dev/null; exit 130' INT TERM

wait "$pid"
rc=$?
kill -- -"$wd" 2>/dev/null || true

if [ -e "$RESULTS/killed" ]; then
    echo "test.sh: 300s wall-clock watchdog killed the run" >&2
    trap - EXIT
    echo "results kept at $RESULTS"
    exit 124
fi

exit "$rc"
