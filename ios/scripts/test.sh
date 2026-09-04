#!/usr/bin/env bash
# Runs the iOS per-task gate under a 300s wall-clock watchdog (AGENTS.md mandate):
#   convert-strings.py --check (catalog must already be up to date) -> xcodegen generate ->
#   xcodebuild test (Debug, iPhone 17 + iPad Pro 13-inch (M5), one invocation) -> swift test
#   (FitrahAPI + InnerTubeKit packages). Ordinary tasks stop here.
# RELEASE=1 bash ios/scripts/test.sh additionally builds Release (simulator SDK -- compiles the
# non-DEBUG paths) after the gate passes, in its own separate 300s watchdog window: the
# Debug->Release flip invalidates the ~320 SPM package compile units the gate just built, and
# charging that recompile to the gate's window is what pushed a combined run past 300s once
# Firebase/GoogleSignIn landed (Phase 4 Task 1). REQUIRED for the Phase 4 gate tasks (19, 31) and
# any task touching ios/project.yml, an xcconfig, entitlements, or Info.plist keys; ordinary
# tasks run the Debug gate only. Prints "RELEASE: built", "RELEASE: failed" or "RELEASE: skipped
# (set RELEASE=1)".
# Per-test limit: 60s -- XCTest rounds `defaultTestExecutionTimeAllowance` up to 60s and Swift
# Testing's own floor is also one minute, so 60s is the real effective limit regardless of the
# number configured (FitrahTube.xctestplan sets 60 to match); CLAUDE.md's 30s note is a
# cross-platform default this iOS suite can't hit and is amended separately. Wall-clock: 300s per
# watchdog window (gate; Release gets its own when requested).
# FAILURE MODE of -onlyUsePackageVersionsFromResolvedFile (used on both xcodebuild invocations
# below): on a -derivedDataPath whose SourcePackages cache is EMPTY -- a fresh checkout, or after
# deleting ios/DerivedData or ios/DerivedData-Release -- the pin blocks the package checkout instead
# of fetching it, and xcodebuild fails with no obvious cause. One-time bootstrap for that directory:
#   xcodebuild -resolvePackageDependencies -project ios/FitrahTube.xcodeproj -derivedDataPath <dir>
# (~44 s, ~1.6 GB per directory). See also: never delete ios/DerivedData/SourcePackages.
# $RESULTS (xcresult bundle + watchdog marker) is removed on exit unless KEEP_RESULTS=1 is set.
# Override simulators with IPHONE_SIM / IPAD_SIM env vars, e.g. IPHONE_SIM="iPhone 16" ./test.sh.
# IPAD_SIM="" (explicitly empty, not just unset) drops the iPad destination and runs iPhone only.
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
# ":-" would also substitute the default on an explicitly EMPTY value, making IPAD_SIM="" a no-op;
# "-" substitutes only when unset, so an explicit empty string survives to the skip check below.
IPAD_SIM="${IPAD_SIM-iPad Pro 13-inch (M5)}"

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

run_gate() {
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

    local destinations=(-destination "platform=iOS Simulator,name=$IPHONE_SIM")
    if [ -n "$IPAD_SIM" ]; then
        destinations+=(-destination "platform=iOS Simulator,name=$IPAD_SIM")
    fi

    echo "== $IPHONE_SIM${IPAD_SIM:+ + $IPAD_SIM} =="
    xcodebuild test \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        "${destinations[@]}" \
        -resultBundlePath "$RESULTS/FitrahTube.xcresult" \
        -derivedDataPath DerivedData \
        -onlyUsePackageVersionsFromResolvedFile \
        2>&1 | grep -E "$SUMMARY"
    local xcodebuild_status=${PIPESTATUS[0]}
    if [ "$xcodebuild_status" -ne 0 ]; then
        report_failures "$RESULTS/FitrahTube.xcresult"
        return "$xcodebuild_status"
    fi

    # --disable-automatic-resolution (Stage 3 / I6): ios/Packages/FitrahAPI/Package.resolved is the
    # TRACKED resolved graph, and `xcodebuild test` above writes the APP's whole 27-pin resolution
    # into it (with -derivedDataPath, Xcode uses the root local package's file as the workspace's).
    # Without this flag a plain `swift test` here re-resolves against FitrahAPI's OWN four-dependency
    # manifest and rewrites that same file down to 10 pins, stripping every Firebase/GoogleSignIn
    # transitive revision -- the ping-pong that made the file un-trackable. With it the build and
    # the tests are identical and the file is left alone.
    echo "== FitrahAPI package =="
    (cd Packages/FitrahAPI && swift test --disable-automatic-resolution) 2>&1 | grep -E "$SUMMARY"
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

    return 0
}

# Split out of run_gate (2026-09-02 gate-restructure): the Debug->Release configuration flip
# invalidates the SPM package targets run_gate just built, so running this in the same watchdog
# window as the gate recompiles ~320 compile units on top of an already-full window. Only invoked
# when RELEASE=1 -- see the header comment. Runs in its own background job, so it re-does the same
# repo-root -> ios/ cd run_gate did (that cd does not survive across separate background jobs).
run_release() {
    cd "$(dirname "$0")/.."

    # Debug is what the gate compiles; Release flips DEBUG off (AppContainer.swift's #else branch,
    # FitrahTubeApp.swift's #else branch) so it must build too. Simulator SDK -> no code signing
    # required.
    echo "== Release build (simulator SDK) =="
    xcodebuild build \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        -configuration Release \
        -destination "platform=iOS Simulator,name=$IPHONE_SIM" \
        -derivedDataPath DerivedData-Release \
        -onlyUsePackageVersionsFromResolvedFile \
        2>&1 | grep -E "$SUMMARY"
    local release_status=${PIPESTATUS[0]}
    return "$release_status"
}

RESULTS=$(mktemp -d)
if [ "${KEEP_RESULTS:-0}" != "1" ]; then
    trap 'rm -rf "$RESULTS"' EXIT
fi

run_gate &
pid=$!
# A-M13: re-check the job group is still alive before claiming a timeout. If `sleep 300` expires in
# the window between `wait "$pid"` returning and the `kill` on the watchdog below, the marker was
# still written and a passing run exited 124 -- a sub-millisecond race, but a non-deterministic CI
# failure is expensive to chase.
( sleep 300; kill -0 -"$pid" 2>/dev/null || exit 0; touch "$RESULTS/killed-gate"; kill -TERM -- -"$pid" 2>/dev/null ) &
wd=$!

trap 'kill -- -"$pid" -"$wd" 2>/dev/null; exit 130' INT TERM

wait "$pid"
rc=$?
kill -- -"$wd" 2>/dev/null || true

if [ -e "$RESULTS/killed-gate" ]; then
    echo "test.sh: 300s wall-clock watchdog killed the run" >&2
    trap - EXIT
    echo "results kept at $RESULTS"
    exit 124
fi

if [ "$rc" -ne 0 ]; then
    exit "$rc"
fi

if [ "${RELEASE:-0}" != "1" ]; then
    echo "RELEASE: skipped (set RELEASE=1)"
    exit 0
fi

# Release build: same race-safe watchdog shape as the gate above, but its own separate 300s
# window -- see header comment for why it can't share the gate's window. Own marker filename
# (M1): the gate's watchdog above and this one otherwise touch the same $RESULTS/killed path, so a
# stale gate-watchdog subshell could in principle be misread by this leg's check below -- safe
# today only because control flow always reaps the gate's watchdog (`kill -- -"$wd"` above) before
# this leg starts, but a distinct name removes the class of bug rather than relying on ordering.
run_release &
pid=$!
( sleep 300; kill -0 -"$pid" 2>/dev/null || exit 0; touch "$RESULTS/killed-release"; kill -TERM -- -"$pid" 2>/dev/null ) &
wd=$!

trap 'kill -- -"$pid" -"$wd" 2>/dev/null; exit 130' INT TERM

wait "$pid"
rc=$?
kill -- -"$wd" 2>/dev/null || true

if [ -e "$RESULTS/killed-release" ]; then
    echo "test.sh: 300s wall-clock watchdog killed the Release build" >&2
    trap - EXIT
    echo "results kept at $RESULTS"
    exit 124
fi

if [ "$rc" -eq 0 ]; then
    echo "RELEASE: built"
else
    echo "RELEASE: failed"
fi

exit "$rc"
