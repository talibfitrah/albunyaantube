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

run_all() {
    xcodegen generate || return $?

    echo "== iPhone 17 + iPad Pro 13-inch (M5) =="
    xcodebuild test \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        -destination 'platform=iOS Simulator,name=iPhone 17' \
        -destination 'platform=iOS Simulator,name=iPad Pro 13-inch (M5)' \
        2>&1 | grep -E "$SUMMARY"
    local xcodebuild_status=${PIPESTATUS[0]}
    [ "$xcodebuild_status" -eq 0 ] || return "$xcodebuild_status"

    echo "== FitrahAPI package =="
    (cd Packages/FitrahAPI && swift test) 2>&1 | grep -E "$SUMMARY"
    local package_status=${PIPESTATUS[0]}
    return "$package_status"
}

run_all &
pid=$!
( sleep 300; kill -TERM -- -"$pid" 2>/dev/null ) &
wd=$!

trap 'kill -- -"$pid" -"$wd" 2>/dev/null; exit 130' INT TERM

wait "$pid"
rc=$?
kill -- -"$wd" 2>/dev/null || true

if [ "$rc" -eq 143 ]; then
    echo "test.sh: 300s wall-clock watchdog killed the run" >&2
    exit 124
fi

exit "$rc"
