#!/usr/bin/env bash
# Runs the full iOS Phase 0 test suite under a 300s wall-clock watchdog (AGENTS.md mandate):
#   xcodegen generate -> xcodebuild test (iPhone 17, iPad Pro 13-inch (M5)) -> swift test (FitrahAPI package).
# Per-test timeout (30s) is enforced by FitrahTube.xctestplan's defaultTestExecutionTimeAllowance.
set -uo pipefail
set -m

cd "$(dirname "$0")/.."

SUMMARY='Test run with|TEST (SUCCEEDED|FAILED)|error:'

run_all() {
    xcodegen generate || return $?

    echo "== iPhone 17 =="
    xcodebuild test \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        -destination 'platform=iOS Simulator,name=iPhone 17' \
        2>&1 | grep -E "$SUMMARY"
    local iphone_status=${PIPESTATUS[0]}
    [ "$iphone_status" -eq 0 ] || return "$iphone_status"

    echo "== iPad Pro 13-inch (M5) =="
    xcodebuild test \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        -destination 'platform=iOS Simulator,name=iPad Pro 13-inch (M5)' \
        2>&1 | grep -E "$SUMMARY"
    local ipad_status=${PIPESTATUS[0]}
    [ "$ipad_status" -eq 0 ] || return "$ipad_status"

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
kill "$wd" 2>/dev/null || true

if [ "$rc" -eq 143 ]; then
    echo "test.sh: 300s wall-clock watchdog killed the run" >&2
    exit 124
fi

exit "$rc"
