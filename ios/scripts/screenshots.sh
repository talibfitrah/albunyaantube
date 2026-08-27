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
    # cso-F5: an argument with no alphanumerics (e.g. "!!!") slugs to the empty string, and the
    # `rm -rf "$OUT_ROOT/$slug"` below would then wipe *every* device's captures. `${OUT_ROOT:?}`
    # guards the variable being empty; it does not guard the slug.
    [ -n "$slug" ] || { echo "bad device name: $device" >&2; exit 1; }
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

# Plan B1 (docs/superpowers/plans/2026-08-24-ios-phase2b1-player-core.md): one player screenshot,
# no locale/rotation matrix -- proof the AVPlayer host decodes and plays the bundled local fixture
# clip with no network. Later B1 tasks (quality menu, captions, recovery pill, metadata) add their
# own `-only-testing:` case here rather than a new script.
PLAYER_OUT="$ROOT/.superpowers/sdd/2026-08-24-ios-phase2b1-player-core/screenshots/b1-task3"
echo "== player screenshot -> $PLAYER_OUT =="
rm -rf "${PLAYER_OUT:?}"
TEST_RUNNER_FITRAH_SHOTS_DIR="$PLAYER_OUT" xcodebuild test \
    -project FitrahTube.xcodeproj \
    -scheme FitrahTube \
    -testPlan FitrahTubeUITests \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerScreen \
    -destination "platform=iOS Simulator,name=iPhone 17" \
    -derivedDataPath DerivedData \
    2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
[ "${PIPESTATUS[0]}" -ne 0 ] && status=1
xcrun simctl shutdown "iPhone 17" >/dev/null 2>&1

# Task 4 (quality ceiling): the FitrahTube quality menu open, anchored on our own SwiftUI control
# (player.qualityMenu.button / player.qualityOption.*) -- never AVKit chrome.
QUALITY_OUT="$ROOT/.superpowers/sdd/2026-08-24-ios-phase2b1-player-core/screenshots/b1-task4"
echo "== player quality menu screenshot -> $QUALITY_OUT =="
rm -rf "${QUALITY_OUT:?}"
TEST_RUNNER_FITRAH_SHOTS_DIR="$QUALITY_OUT" xcodebuild test \
    -project FitrahTube.xcodeproj \
    -scheme FitrahTube \
    -testPlan FitrahTubeUITests \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerQualityMenu \
    -destination "platform=iOS Simulator,name=iPhone 17" \
    -derivedDataPath DerivedData \
    2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
[ "${PIPESTATUS[0]}" -ne 0 ] && status=1
xcrun simctl shutdown "iPhone 17" >/dev/null 2>&1

# Task 5 (audio-language menu): the local fixture has one audio track, so this proves the HIDDEN
# state (player.audioLanguageMenu.button absent) -- see testPlayerAudioLanguageMenuHiddenForSingleTrackFixture.
# An open-menu shot needs a real multi-audio stream (deferred to a later live pass).
AUDIO_LANGUAGE_OUT="$ROOT/.superpowers/sdd/2026-08-24-ios-phase2b1-player-core/screenshots/b1-task5"
echo "== player audio-language menu (hidden) screenshot -> $AUDIO_LANGUAGE_OUT =="
rm -rf "${AUDIO_LANGUAGE_OUT:?}"
TEST_RUNNER_FITRAH_SHOTS_DIR="$AUDIO_LANGUAGE_OUT" xcodebuild test \
    -project FitrahTube.xcodeproj \
    -scheme FitrahTube \
    -testPlan FitrahTubeUITests \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerAudioLanguageMenuHiddenForSingleTrackFixture \
    -destination "platform=iOS Simulator,name=iPhone 17" \
    -derivedDataPath DerivedData \
    2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
[ "${PIPESTATUS[0]}" -ne 0 ] && status=1
xcrun simctl shutdown "iPhone 17" >/dev/null 2>&1

# Task 6 (captions): the fixture HLS has no caption tracks, so this proves the HIDDEN state
# (player.captionsMenu.button absent) -- see testPlayerCaptionsMenuHiddenForFixtureWithNoTracks.
CAPTIONS_OUT="$ROOT/.superpowers/sdd/2026-08-24-ios-phase2b1-player-core/screenshots/b1-task6"
echo "== player captions menu (hidden) screenshot -> $CAPTIONS_OUT =="
rm -rf "${CAPTIONS_OUT:?}"
TEST_RUNNER_FITRAH_SHOTS_DIR="$CAPTIONS_OUT" xcodebuild test \
    -project FitrahTube.xcodeproj \
    -scheme FitrahTube \
    -testPlan FitrahTubeUITests \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerCaptionsMenuHiddenForFixtureWithNoTracks \
    -destination "platform=iOS Simulator,name=iPhone 17" \
    -derivedDataPath DerivedData \
    2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
[ "${PIPESTATUS[0]}" -ne 0 ] && status=1
xcrun simctl shutdown "iPhone 17" >/dev/null 2>&1

# Task 7 (recovery + rung 2): the persistent "Standard quality (360p)" pill, with the quality
# control hidden -- `-fitrah-fake-player` resolves `.progressive`, i.e. rung 2.
RUNG2_OUT="$ROOT/.superpowers/sdd/2026-08-24-ios-phase2b1-player-core/screenshots/b1-task7"
echo "== player rung-2 pill screenshot -> $RUNG2_OUT =="
rm -rf "${RUNG2_OUT:?}"
TEST_RUNNER_FITRAH_SHOTS_DIR="$RUNG2_OUT" xcodebuild test \
    -project FitrahTube.xcodeproj \
    -scheme FitrahTube \
    -testPlan FitrahTubeUITests \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerRung2Pill \
    -destination "platform=iOS Simulator,name=iPhone 17" \
    -derivedDataPath DerivedData \
    2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
[ "${PIPESTATUS[0]}" -ne 0 ] && status=1
xcrun simctl shutdown "iPhone 17" >/dev/null 2>&1

# Task 8 (metadata panel + toolbar): favorite/share/report row + title/channel/views/description
# below the player, on the fixture HLS stream so the rung-1 (quality control) layout is what's
# captured.
METADATA_OUT="$ROOT/.superpowers/sdd/2026-08-24-ios-phase2b1-player-core/screenshots/b1-task8"
echo "== player metadata + toolbar screenshot -> $METADATA_OUT =="
rm -rf "${METADATA_OUT:?}"
TEST_RUNNER_FITRAH_SHOTS_DIR="$METADATA_OUT" xcodebuild test \
    -project FitrahTube.xcodeproj \
    -scheme FitrahTube \
    -testPlan FitrahTubeUITests \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerMetadataAndToolbar \
    -destination "platform=iOS Simulator,name=iPhone 17" \
    -derivedDataPath DerivedData \
    2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
[ "${PIPESTATUS[0]}" -ne 0 ] && status=1
xcrun simctl shutdown "iPhone 17" >/dev/null 2>&1

# Task 9 (state UI + VoiceOver): error/unavailable/cooldown/recoveryExhausted, each a distinct
# StreamState the fixture resolvers (or the recoveryExhausted debug hook) force with no network.
STATE_OUT="$ROOT/.superpowers/sdd/2026-08-24-ios-phase2b1-player-core/screenshots/b1-task9"
echo "== player state UI screenshots -> $STATE_OUT =="
rm -rf "${STATE_OUT:?}"
TEST_RUNNER_FITRAH_SHOTS_DIR="$STATE_OUT" xcodebuild test \
    -project FitrahTube.xcodeproj \
    -scheme FitrahTube \
    -testPlan FitrahTubeUITests \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerErrorState \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerContentUnavailableState \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerCooldownState \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerRecoveryExhaustedState \
    -destination "platform=iOS Simulator,name=iPhone 17" \
    -derivedDataPath DerivedData \
    2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
[ "${PIPESTATUS[0]}" -ne 0 ] && status=1
xcrun simctl shutdown "iPhone 17" >/dev/null 2>&1

exit "$status"
