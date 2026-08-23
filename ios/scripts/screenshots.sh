#!/usr/bin/env bash
# Task-14 capture rig. Runs FitrahTubeUITests (NOT part of ios/scripts/test.sh's 300s gate -- see
# FitrahTubeUITests.xctestplan) and drops full-resolution PNGs under
#   .superpowers/sdd/2026-08-23-ios-phase1-catalog-ui/screenshots/task-14/<device-slug>/
# The path reaches the test process as TEST_RUNNER_FITRAH_SHOTS_DIR (xcodebuild strips the prefix),
# which ScreenshotTests reads via ProcessInfo.processInfo.environment.
#
#   ios/scripts/screenshots.sh                 # full R-G matrix: both iPads + the iPhone a11y pass
#   ios/scripts/screenshots.sh "iPhone 17"     # one device (test selection follows the device kind)
#
# Per R-G: iPads capture every screen x {en-light, ar-dark} x {portrait, landscape}; the iPhone
# captures Home/Videos/Settings at Dynamic Type .accessibility3 and runs the R-C label assertions.
# bash 3.2 compatible (macOS system bash).
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_ROOT="$ROOT/.superpowers/sdd/2026-08-23-ios-phase1-catalog-ui/screenshots/task-14"
PATH="$HOME/.local/bin:$PATH"

if [ "$#" -gt 0 ]; then
    DEVICES=("$@")
else
    DEVICES=("iPad mini (A17 Pro)" "iPad Pro 13-inch (M5)" "iPhone 17")
fi

cd "$ROOT/ios" || exit 1
xcodegen generate >/dev/null || exit $?

status=0
for device in "${DEVICES[@]}"; do
    # "iPad Pro 13-inch (M5)" -> "ipad-pro-13-inch-m5"
    slug=$(echo "$device" | tr '[:upper:]' '[:lower:]' | sed -e 's/[^a-z0-9]\{1,\}/-/g' -e 's/^-//' -e 's/-$//')
    case "$device" in
        iPhone*) tests=(-only-testing:FitrahTubeUITests/ScreenshotTests/testAccessibilityTextSizes
                        -only-testing:FitrahTubeUITests/ScreenshotTests/testOfflineBanner
                        -only-testing:FitrahTubeUITests/ScreenshotTests/testListRowAccessibilityLabels) ;;
        *)       tests=(-only-testing:FitrahTubeUITests/ScreenshotTests/testCatalogScreens
                        -only-testing:FitrahTubeUITests/ScreenshotTests/testOfflineBanner) ;;
    esac

    echo "== $device -> $OUT_ROOT/$slug =="
    rm -rf "${OUT_ROOT:?}/$slug"
    TEST_RUNNER_FITRAH_SHOTS_DIR="$OUT_ROOT/$slug" xcodebuild test \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        -testPlan FitrahTubeUITests \
        "${tests[@]}" \
        -destination "platform=iOS Simulator,name=$device" \
        -derivedDataPath DerivedData \
        2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
    [ "${PIPESTATUS[0]}" -ne 0 ] && status=1
    xcrun simctl shutdown "$device" >/dev/null 2>&1
done

exit "$status"
