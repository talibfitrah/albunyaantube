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
SUMMARY='Test run with|Testing (passed|failed) on|TEST (SUCCEEDED|FAILED)|error:'

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

wait "$pid"
rc=$?
kill -- -"$wd" 2>/dev/null || true

if [ "$rc" -eq 143 ]; then
    echo "test.sh: 300s wall-clock watchdog killed the run" >&2
    exit 124
fi

exit "$rc"
