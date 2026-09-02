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
# Same pre-stage as test.sh (CF-D-7): project.yml names the gitignored Vendor/ Cast SDK, so a
# fresh checkout would fail at build time with a missing framework instead of fetching it once.
[ -d Vendor/GoogleCast.xcframework ] || ./scripts/fetch-cast-sdk.sh || exit $?
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

# Plan B2 task 3 (audio-only): the audio-only status surface with the quality/captions/language
# controls gone -- `-fitrah-fake-player-audio-only` resolves the bundled fixture WITH an
# `audioOnlyURL`, which is what gates the toggle.
AUDIO_ONLY_OUT="$ROOT/.superpowers/sdd/2026-08-27-ios-phase2b2-background-audio/screenshots/b2-task3"
echo "== player audio-only screenshot -> $AUDIO_ONLY_OUT =="
rm -rf "${AUDIO_ONLY_OUT:?}"
TEST_RUNNER_FITRAH_SHOTS_DIR="$AUDIO_ONLY_OUT" xcodebuild test \
    -project FitrahTube.xcodeproj \
    -scheme FitrahTube \
    -testPlan FitrahTubeUITests \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerAudioOnly \
    -destination "platform=iOS Simulator,name=iPhone 17" \
    -derivedDataPath DerivedData \
    2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
[ "${PIPESTATUS[0]}" -ne 0 ] && status=1
xcrun simctl shutdown "iPhone 17" >/dev/null 2>&1

# Task 10 (iPad / RTL / Dynamic Type / VoiceOver pass): iPhone leg (en portrait, en landscape
# with metadata hidden, ar portrait, en .accessibility3 portrait) then the iPad leg (en portrait,
# en landscape, proving the content column stays capped at Size.playerMaxWidth). Both legs share
# ONE output directory -- only the first `rm -rf` below clears it, so the iPad run's shots don't
# wipe the iPhone run's.
TASK10_OUT="$ROOT/.superpowers/sdd/2026-08-24-ios-phase2b1-player-core/screenshots/b1-task10"
echo "== player iPad/RTL/a11y pass (iPhone) -> $TASK10_OUT =="
rm -rf "${TASK10_OUT:?}"
TEST_RUNNER_FITRAH_SHOTS_DIR="$TASK10_OUT" xcodebuild test \
    -project FitrahTube.xcodeproj \
    -scheme FitrahTube \
    -testPlan FitrahTubeUITests \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerB1Task10IPhone \
    -destination "platform=iOS Simulator,name=iPhone 17" \
    -derivedDataPath DerivedData \
    2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
[ "${PIPESTATUS[0]}" -ne 0 ] && status=1
xcrun simctl shutdown "iPhone 17" >/dev/null 2>&1

echo "== player iPad/RTL/a11y pass (iPad) -> $TASK10_OUT =="
TEST_RUNNER_FITRAH_SHOTS_DIR="$TASK10_OUT" xcodebuild test \
    -project FitrahTube.xcodeproj \
    -scheme FitrahTube \
    -testPlan FitrahTubeUITests \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerB1Task10IPad \
    -destination "platform=iOS Simulator,name=iPad Pro 13-inch (M5)" \
    -derivedDataPath DerivedData \
    2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
[ "${PIPESTATUS[0]}" -ne 0 ] && status=1
xcrun simctl shutdown "iPad Pro 13-inch (M5)" >/dev/null 2>&1

# ---------------------------------------------------------------------------------------------
# Plan B3 (docs/superpowers/plans/2026-08-27-ios-phase2b3-embed-safemode.md): the embed rung, the
# open-in-YouTube rung and Safe Mode. Same one-block-per-task shape as the B1/B2 blocks above.

# No task-2 block: rung 4 (the "Open in YouTube" hand-off card and its confirmation) was removed
# outright by owner directive 2026-08-27 -- the app never redirects to YouTube -- so both of its
# screenshot cases are gone with it.

# Task 4 (rung 3): the caption above the frame with every FitrahTube playback control gone, and the
# Replay/Back cover over the end screen.
B3_TASK4_OUT="$ROOT/.superpowers/sdd/2026-08-27-ios-phase2b3-embed-safemode/screenshots/b3-task4"
echo "== B3 task 4 embed rung screenshots -> $B3_TASK4_OUT =="
rm -rf "${B3_TASK4_OUT:?}"
TEST_RUNNER_FITRAH_SHOTS_DIR="$B3_TASK4_OUT" xcodebuild test \
    -project FitrahTube.xcodeproj \
    -scheme FitrahTube \
    -testPlan FitrahTubeUITests \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerEmbedRung \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerEmbedEnded \
    -destination "platform=iOS Simulator,name=iPhone 17" \
    -derivedDataPath DerivedData \
    2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
[ "${PIPESTATUS[0]}" -ne 0 ] && status=1
xcrun simctl shutdown "iPhone 17" >/dev/null 2>&1

# Task 5 (acceptance matrix): the embed rung at Dynamic Type .accessibility3 and in ar (RTL), then
# the iPad in both orientations -- each asserting plan §6.4's 200x200 pt floor on the web view's
# own frame. Both legs share ONE directory, so only the iPhone leg clears it.
B3_TASK5_OUT="$ROOT/.superpowers/sdd/2026-08-27-ios-phase2b3-embed-safemode/screenshots/b3-task5"
echo "== B3 task 5 embed a11y/RTL pass (iPhone) -> $B3_TASK5_OUT =="
rm -rf "${B3_TASK5_OUT:?}"
TEST_RUNNER_FITRAH_SHOTS_DIR="$B3_TASK5_OUT" xcodebuild test \
    -project FitrahTube.xcodeproj \
    -scheme FitrahTube \
    -testPlan FitrahTubeUITests \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerEmbedB3Task5IPhone \
    -destination "platform=iOS Simulator,name=iPhone 17" \
    -derivedDataPath DerivedData \
    2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
[ "${PIPESTATUS[0]}" -ne 0 ] && status=1
xcrun simctl shutdown "iPhone 17" >/dev/null 2>&1

echo "== B3 task 5 embed a11y/RTL pass (iPad) -> $B3_TASK5_OUT =="
TEST_RUNNER_FITRAH_SHOTS_DIR="$B3_TASK5_OUT" xcodebuild test \
    -project FitrahTube.xcodeproj \
    -scheme FitrahTube \
    -testPlan FitrahTubeUITests \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerEmbedB3Task5IPad \
    -destination "platform=iOS Simulator,name=iPad Pro 13-inch (M5)" \
    -derivedDataPath DerivedData \
    2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
[ "${PIPESTATUS[0]}" -ne 0 ] && status=1
xcrun simctl shutdown "iPad Pro 13-inch (M5)" >/dev/null 2>&1

# Task 5 step 2 (live IFrame pass): OPT-IN, because these talk to youtube.com and a machine with no
# network would fail them for the wrong reason -- the same contract InnerTubeKit's LiveResolveTests
# has with INNERTUBE_LIVE. Run with:  EMBED_LIVE=1 ios/scripts/screenshots.sh
if [ "${EMBED_LIVE:-0}" = "1" ]; then
    B3_LIVE_OUT="$ROOT/.superpowers/sdd/2026-08-27-ios-phase2b3-embed-safemode/screenshots/b3-task5-live"
    echo "== B3 task 5 LIVE IFrame checks -> $B3_LIVE_OUT =="
    rm -rf "${B3_LIVE_OUT:?}"
    TEST_RUNNER_FITRAH_SHOTS_DIR="$B3_LIVE_OUT" TEST_RUNNER_EMBED_LIVE=1 xcodebuild test \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        -testPlan FitrahTubeUITests \
        -only-testing:FitrahTubeUITests/ScreenshotTests/testEmbedLivePlaysAndAutoplays \
        -only-testing:FitrahTubeUITests/ScreenshotTests/testEmbedLiveNavigationLockCancelsEveryEscape \
        -only-testing:FitrahTubeUITests/ScreenshotTests/testEmbedLiveEndedRaisesCoverAndReplayRestarts \
        -only-testing:FitrahTubeUITests/ScreenshotTests/testEmbedLiveRemovedCard \
        -destination "platform=iOS Simulator,name=iPhone 17" \
        -derivedDataPath DerivedData \
        2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|EMBED_LIVE|TEST (SUCCEEDED|FAILED)|error:"
    [ "${PIPESTATUS[0]}" -ne 0 ] && status=1
    xcrun simctl shutdown "iPhone 17" >/dev/null 2>&1
fi

# ---------------------------------------------------------------------------------------------
# Plan B4 (docs/superpowers/plans/2026-08-27-ios-phase2b4-shorts.md): the Shorts surface. One
# block per leg, each naming its own destination -- the device argument above does NOT scope
# anything down here.

# Task 3 (chrome) + Task 4 (acceptance matrix): iPhone leg -- en/ar/a11y3, loop, tap, scrub, rail,
# kebab, Back, edge swipe, portrait lock, embed arm, status states. The iPad leg follows and shares
# the directory, so only the iPhone leg clears it. Frame measurements land in
# `b4-task4-*-measurements.txt` beside the PNGs.
B4_TASK4_OUT="$ROOT/.superpowers/sdd/2026-08-27-ios-phase2b4-shorts/screenshots/b4-task4"
echo "== B4 task 4 shorts matrix (iPhone) -> $B4_TASK4_OUT =="
rm -rf "${B4_TASK4_OUT:?}"
TEST_RUNNER_FITRAH_SHOTS_DIR="$B4_TASK4_OUT" xcodebuild test \
    -project FitrahTube.xcodeproj \
    -scheme FitrahTube \
    -testPlan FitrahTubeUITests \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testShortsScreen \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testShortsKebab \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testShortsB4Task4IPhone \
    -destination "platform=iOS Simulator,name=iPhone 17" \
    -derivedDataPath DerivedData \
    2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
[ "${PIPESTATUS[0]}" -ne 0 ] && status=1
xcrun simctl shutdown "iPhone 17" >/dev/null 2>&1

echo "== B4 task 4 shorts matrix (iPad) -> $B4_TASK4_OUT =="
TEST_RUNNER_FITRAH_SHOTS_DIR="$B4_TASK4_OUT" xcodebuild test \
    -project FitrahTube.xcodeproj \
    -scheme FitrahTube \
    -testPlan FitrahTubeUITests \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testShortsB4Task4IPad \
    -destination "platform=iOS Simulator,name=iPad Pro 13-inch (M5)" \
    -derivedDataPath DerivedData \
    2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
[ "${PIPESTATUS[0]}" -ne 0 ] && status=1
xcrun simctl shutdown "iPad Pro 13-inch (M5)" >/dev/null 2>&1

# Task 4 step 2 (live Shorts): OPT-IN, same contract as EMBED_LIVE -- approved-catalog ids only.
# Run with:  SHORTS_LIVE=1 ios/scripts/screenshots.sh
if [ "${SHORTS_LIVE:-0}" = "1" ]; then
    B4_LIVE_OUT="$ROOT/.superpowers/sdd/2026-08-27-ios-phase2b4-shorts/screenshots/b4-task4-live"
    echo "== B4 task 4 LIVE shorts checks -> $B4_LIVE_OUT =="
    rm -rf "${B4_LIVE_OUT:?}"
    TEST_RUNNER_FITRAH_SHOTS_DIR="$B4_LIVE_OUT" TEST_RUNNER_SHORTS_LIVE=1 xcodebuild test \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        -testPlan FitrahTubeUITests \
        -only-testing:FitrahTubeUITests/ScreenshotTests/testShortsB4Task4Live \
        -destination "platform=iOS Simulator,name=iPhone 17" \
        -derivedDataPath DerivedData \
        2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|SHORTS_LIVE|TEST (SUCCEEDED|FAILED)|error:"
    [ "${PIPESTATUS[0]}" -ne 0 ] && status=1
    xcrun simctl shutdown "iPhone 17" >/dev/null 2>&1
fi

# ---------------------------------------------------------------------------------------------
# Plan B5 (docs/superpowers/plans/2026-08-27-ios-phase2b5-fullscreen-queue.md): fullscreen + the
# queue. Same shape as the B4 blocks: one leg per destination, the device argument above does NOT
# scope these. Measurements land in `b5-task4-*-measurements.txt` beside the PNGs.
B5_TASK4_OUT="$ROOT/.superpowers/sdd/2026-08-27-ios-phase2b5-fullscreen-queue/screenshots/b5-task4"
echo "== B5 task 4 fullscreen/queue matrix (iPhone) -> $B5_TASK4_OUT =="
rm -rf "${B5_TASK4_OUT:?}"
TEST_RUNNER_FITRAH_SHOTS_DIR="$B5_TASK4_OUT" xcodebuild test \
    -project FitrahTube.xcodeproj \
    -scheme FitrahTube \
    -testPlan FitrahTubeUITests \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerB5Task4IPhone \
    -destination "platform=iOS Simulator,name=iPhone 17" \
    -derivedDataPath DerivedData \
    2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
[ "${PIPESTATUS[0]}" -ne 0 ] && status=1
xcrun simctl shutdown "iPhone 17" >/dev/null 2>&1

echo "== B5 task 4 fullscreen/queue matrix (iPad) -> $B5_TASK4_OUT =="
TEST_RUNNER_FITRAH_SHOTS_DIR="$B5_TASK4_OUT" xcodebuild test \
    -project FitrahTube.xcodeproj \
    -scheme FitrahTube \
    -testPlan FitrahTubeUITests \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerB5Task4IPad \
    -destination "platform=iOS Simulator,name=iPad Pro 13-inch (M5)" \
    -derivedDataPath DerivedData \
    2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
[ "${PIPESTATUS[0]}" -ne 0 ] && status=1
xcrun simctl shutdown "iPad Pro 13-inch (M5)" >/dev/null 2>&1

# Task 4 step 2 (live playlist): OPT-IN, same contract as EMBED_LIVE/SHORTS_LIVE -- the approved
# catalog playlist only. Run with:  B5_LIVE=1 ios/scripts/screenshots.sh
if [ "${B5_LIVE:-0}" = "1" ]; then
    B5_LIVE_OUT="$ROOT/.superpowers/sdd/2026-08-27-ios-phase2b5-fullscreen-queue/screenshots/b5-task4-live"
    echo "== B5 task 4 LIVE playlist checks -> $B5_LIVE_OUT =="
    rm -rf "${B5_LIVE_OUT:?}"
    TEST_RUNNER_FITRAH_SHOTS_DIR="$B5_LIVE_OUT" TEST_RUNNER_B5_LIVE=1 xcodebuild test \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        -testPlan FitrahTubeUITests \
        -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerB5Task4Live \
        -destination "platform=iOS Simulator,name=iPhone 17" \
        -derivedDataPath DerivedData \
        2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|B5_LIVE|TEST (SUCCEEDED|FAILED)|error:"
    [ "${PIPESTATUS[0]}" -ne 0 ] && status=1
    xcrun simctl shutdown "iPhone 17" >/dev/null 2>&1
fi

# ---------------------------------------------------------------------------------------------
# Plan C (docs/superpowers/plans/2026-08-27-ios-phase2c-detail-report-share.md): the channel and
# playlist detail screens, the kebab and the report sheet. Same shape as the B4/B5 blocks: one leg
# per destination, the device argument above does NOT scope these. Measurements land in
# `c-task6-*-measurements.txt` beside the PNGs.
C_TASK6_OUT="$ROOT/.superpowers/sdd/2026-08-27-ios-phase2c-detail-report-share/screenshots/c-task6"
echo "== C task 6 detail/report matrix (iPhone) -> $C_TASK6_OUT =="
rm -rf "${C_TASK6_OUT:?}"
TEST_RUNNER_FITRAH_SHOTS_DIR="$C_TASK6_OUT" xcodebuild test \
    -project FitrahTube.xcodeproj \
    -scheme FitrahTube \
    -testPlan FitrahTubeUITests \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testDetailCTask6IPhone \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testDetailCTask6IPhonePlaylist \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testDetailCTask6IPhoneLocales \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testDetailCTask6PaginationPastTheFold \
    -destination "platform=iOS Simulator,name=iPhone 17" \
    -derivedDataPath DerivedData \
    2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
[ "${PIPESTATUS[0]}" -ne 0 ] && status=1
xcrun simctl shutdown "iPhone 17" >/dev/null 2>&1

echo "== C task 6 detail/report matrix (iPad) -> $C_TASK6_OUT =="
TEST_RUNNER_FITRAH_SHOTS_DIR="$C_TASK6_OUT" xcodebuild test \
    -project FitrahTube.xcodeproj \
    -scheme FitrahTube \
    -testPlan FitrahTubeUITests \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testDetailCTask6IPad \
    -destination "platform=iOS Simulator,name=iPad Pro 13-inch (M5)" \
    -derivedDataPath DerivedData \
    2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
[ "${PIPESTATUS[0]}" -ne 0 ] && status=1
xcrun simctl shutdown "iPad Pro 13-inch (M5)" >/dev/null 2>&1

# Phase 3 Task 6 (docs/superpowers/plans/2026-09-01-ios-phase3-offline-cast.md): the Saved screen
# with `-fitrah-seed-offline` rows across all six statuses, en + ar, phone + iPad. One
# `-only-testing:` per invocation (the Plan C scoping trap).
SAVED_OUT="$ROOT/.superpowers/sdd/2026-09-01-ios-phase3-offline-cast/screenshots/phase3-saved"
for device in "iPhone 17" "iPad Pro 13-inch (M5)"; do
    slug=$(echo "$device" | tr '[:upper:]' '[:lower:]' | sed -e 's/[^a-z0-9]\{1,\}/-/g' -e 's/^-//' -e 's/-$//')
    echo "== phase3-saved ($device) -> $SAVED_OUT/$slug =="
    rm -rf "${SAVED_OUT:?}/$slug"
    TEST_RUNNER_FITRAH_SHOTS_DIR="$SAVED_OUT/$slug" xcodebuild test \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        -testPlan FitrahTubeUITests \
        -only-testing:FitrahTubeUITests/ScreenshotTests/testSavedScreenPhase3 \
        -destination "platform=iOS Simulator,name=$device" \
        -derivedDataPath DerivedData \
        2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
    [ "${PIPESTATUS[0]}" -ne 0 ] && status=1
    xcrun simctl shutdown "$device" >/dev/null 2>&1
done

# Phase 3 Task 8: the player toolbar with all five slots (favorite / share / report / save / cast),
# en + ar. Phone only -- the toolbar row is the subject and it is identical on iPad.
CAST_OUT="$ROOT/.superpowers/sdd/2026-09-01-ios-phase3-offline-cast/screenshots/phase3-player-save-cast"
echo "== phase3-player-save-cast (iPhone 17) -> $CAST_OUT =="
rm -rf "${CAST_OUT:?}"
TEST_RUNNER_FITRAH_SHOTS_DIR="$CAST_OUT" xcodebuild test \
    -project FitrahTube.xcodeproj \
    -scheme FitrahTube \
    -testPlan FitrahTubeUITests \
    -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerSaveAndCastToolbarPhase3 \
    -destination "platform=iOS Simulator,name=iPhone 17" \
    -derivedDataPath DerivedData \
    2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
[ "${PIPESTATUS[0]}" -ne 0 ] && status=1
xcrun simctl shutdown "iPhone 17" >/dev/null 2>&1

# Task 6 step 5 (live acceptance): OPT-IN, same contract as B5_LIVE -- talks to youtube.com AND the
# backend named by C_LIVE_API_BASE_URL (default: production), and files exactly ONE real content
# report per run (reason OTHER, "iOS Plan C acceptance test - safe to dismiss"). Step 8 needs a real
# remote-config document the branch cannot publish, so a marked copy of ios-remote-config.json is
# served from a temp dir on localhost for the run. Run with:  C_LIVE=1 ios/scripts/screenshots.sh
if [ "${C_LIVE:-0}" = "1" ]; then
    C_LIVE_OUT="$ROOT/.superpowers/sdd/2026-08-27-ios-phase2c-detail-report-share/screenshots/c-task6-live"
    C_LIVE_PORT="${C_LIVE_PORT:-8765}"
    C_LIVE_SERVE=$(mktemp -d)
    python3 - "$ROOT/ios-remote-config.json" "$C_LIVE_SERVE/ios-remote-config.json" <<'EOF'
import json, sys
config = json.load(open(sys.argv[1]))
config["featuredCategoryId"] = "c-task6-live-marker"
json.dump(config, open(sys.argv[2], "w"))
EOF
    python3 -m http.server "$C_LIVE_PORT" --bind 127.0.0.1 --directory "$C_LIVE_SERVE" >/dev/null 2>&1 &
    C_LIVE_SERVER=$!
    # A Ctrl-C mid-xcodebuild otherwise orphans the server (next run's bind fails silently) and the dir.
    trap 'kill "$C_LIVE_SERVER" 2>/dev/null; rm -rf "$C_LIVE_SERVE"' EXIT
    echo "== C task 6 LIVE acceptance checks -> $C_LIVE_OUT =="
    rm -rf "${C_LIVE_OUT:?}"
    TEST_RUNNER_FITRAH_SHOTS_DIR="$C_LIVE_OUT" TEST_RUNNER_C_LIVE=1 \
        TEST_RUNNER_C_LIVE_API_BASE_URL="${C_LIVE_API_BASE_URL:-https://app.fitrahtube.com/}" \
        TEST_RUNNER_C_LIVE_CONFIG_URL="http://localhost:$C_LIVE_PORT/ios-remote-config.json" xcodebuild test \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        -testPlan FitrahTubeUITests \
        -only-testing:FitrahTubeUITests/ScreenshotTests/testDetailCTask6Live \
        -destination "platform=iOS Simulator,name=iPhone 17" \
        -derivedDataPath DerivedData \
        2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|C_LIVE|TEST (SUCCEEDED|FAILED)|error:"
    [ "${PIPESTATUS[0]}" -ne 0 ] && status=1
    kill "$C_LIVE_SERVER" 2>/dev/null
    rm -rf "$C_LIVE_SERVE"
    xcrun simctl shutdown "iPhone 17" >/dev/null 2>&1
fi

exit "$status"
