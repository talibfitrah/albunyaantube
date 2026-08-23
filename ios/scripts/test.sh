#!/usr/bin/env bash
# Runs the full iOS Phase 0 test suite under a 300s wall-clock watchdog (AGENTS.md mandate):
#   xcodegen generate -> xcodebuild test (iPhone 17 + iPad Pro 13-inch (M5), one invocation) -> swift test (FitrahAPI package).
# Per-test limit: 60s (Swift Testing minute granularity; CLAUDE.md asks 30s — not expressible), wall-clock: 300s.
set -uo pipefail
set -m

cd "$(dirname "$0")/.."

# "Test run with ..." is swift test's (SwiftPM) Swift Testing summary line. xcodebuild test with
# multiple -destination flags never prints that line -- it reports "Testing (passed|failed) on
# '<device>'" per destination instead; both are matched so both destinations' results are visible.
# The ✘/"Expectation failed"/"recorded an issue" lines are Swift Testing's per-failure detail —
# `swift test` (single process) prints them, but xcodebuild test with two -destination flags runs
# them concurrently and falls back to its older per-test reporter instead ("Test case '<name>'
# failed on '<device>'"), so that pattern is matched too or a dual-destination failure would only
# ever show "Testing failed on '<device>'" with no indication of which test or why.
SUMMARY='Test run with|Testing (passed|failed) on|TEST (SUCCEEDED|FAILED)|error:|✘|Expectation failed|recorded an issue|Test case .* failed'

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
    # An external `kill "$pid"` (or this script's own trap/timeout path) only reaches this
    # subshell's own process; without forwarding TERM to the whole process group, xcodebuild/swift
    # would keep running as orphans. `kill 0` re-signals the whole group (set -m gives this
    # backgrounded subshell its own group, matching -"$pid" below).
    trap 'kill 0' TERM

    xcodegen generate || return $?

    echo "== iPhone 17 + iPad Pro 13-inch (M5) =="
    xcodebuild test \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        -destination 'platform=iOS Simulator,name=iPhone 17' \
        -destination 'platform=iOS Simulator,name=iPad Pro 13-inch (M5)' \
        -resultBundlePath "$RESULTS/FitrahTube.xcresult" \
        2>&1 | grep -E "$SUMMARY"
    local xcodebuild_status=${PIPESTATUS[0]}
    if [ "$xcodebuild_status" -ne 0 ]; then
        report_failures "$RESULTS/FitrahTube.xcresult"
        return "$xcodebuild_status"
    fi
    rm -rf "$RESULTS"

    echo "== FitrahAPI package =="
    (cd Packages/FitrahAPI && swift test) 2>&1 | grep -E "$SUMMARY"
    local package_status=${PIPESTATUS[0]}
    return "$package_status"
}

RESULTS=$(mktemp -d)

WD_MARK=$(mktemp)
rm -f "$WD_MARK"

run_all &
pid=$!
( sleep 300; touch "$WD_MARK"; kill -TERM -- -"$pid" 2>/dev/null ) &
wd=$!

trap 'kill -- -"$pid" -"$wd" 2>/dev/null; exit 130' INT TERM

wait "$pid"
rc=$?
kill -- -"$wd" 2>/dev/null || true

if [ -e "$WD_MARK" ]; then
    rm -f "$WD_MARK"
    echo "test.sh: 300s wall-clock watchdog killed the run" >&2
    exit 124
fi
rm -f "$WD_MARK"

exit "$rc"
