#!/usr/bin/env bash
# Runs the full iOS Phase 0 test suite under a 300s wall-clock watchdog (AGENTS.md mandate):
#   xcodegen generate -> xcodebuild test (iPhone 17 + iPad Pro 13-inch (M5), one invocation) -> swift test
#   (FitrahAPI package) -> xcodebuild build (Release, simulator SDK -- compiles the non-DEBUG paths).
# Per-test limit: 60s -- XCTest rounds `defaultTestExecutionTimeAllowance` up to 60s and Swift
# Testing's own floor is also one minute, so 60s is the real effective limit regardless of the
# number configured (FitrahTube.xctestplan sets 60 to match); CLAUDE.md's 30s note is a
# cross-platform default this iOS suite can't hit and is amended separately. Wall-clock: 300s.
# $RESULTS (xcresult bundle + watchdog marker) is removed on exit unless KEEP_RESULTS=1 is set.
set -uo pipefail
set -m

cd "$(dirname "$0")/.."

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
    xcodegen generate || return $?

    echo "== iPhone 17 + iPad Pro 13-inch (M5) =="
    xcodebuild test \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        -destination 'platform=iOS Simulator,name=iPhone 17' \
        -destination 'platform=iOS Simulator,name=iPad Pro 13-inch (M5)' \
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

    # Debug is what the test steps above compile; Release flips DEBUG off (AppContainer.swift's
    # #else branch, FitrahTubeApp.swift's #else branch) so it must build too. Simulator SDK ->
    # no code signing required.
    echo "== Release build (simulator SDK) =="
    xcodebuild build \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        -configuration Release \
        -destination 'platform=iOS Simulator,name=iPhone 17' \
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
( sleep 300; touch "$RESULTS/killed"; kill -TERM -- -"$pid" 2>/dev/null ) &
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
