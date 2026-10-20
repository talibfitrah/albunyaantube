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
# Same pre-stage as test.sh (Phase 4 Task 1): stage the git-ignored GoogleService-Info.plist before
# `xcodegen generate` so the sources glob sees it. No-ops with a notice when it is absent.
bash scripts/copy-firebase-plist.sh || exit $?
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
# Plan B4 (docs/superpowers/plans/2026-08-27-ios-phase2b4-shorts.md): the Shorts surface. One
# block per leg, each naming its own destination -- the device argument above does NOT scope
# anything down here.

# Task 3 (chrome) + Task 4 (acceptance matrix): iPhone leg -- en/ar/a11y3, loop, tap, scrub, rail,
# kebab, Back, edge swipe, portrait lock, status states. The iPad leg follows and shares
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

# Task 4 step 2 (live Shorts): OPT-IN (it talks to the live service) -- approved-catalog ids only.
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

# Task 4 step 2 (live playlist): OPT-IN, same contract as SHORTS_LIVE -- the approved
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

# Phase 3 Task 8 + action row 1.0.3: the player toolbar with all five slots (favorite / share / report
# / save / cast), en + ar, phone AND iPad. `testPlayerSaveAndCastToolbarPhase3` also asserts the
# equal-width columns on one top edge, and on a tablet the 600 pt cap + centring in the player column
# -- that branch runs ONLY on the iPad leg (`UIDevice` idiom), so both legs are needed. FitrahTubeUITests
# is outside test.sh's gate, so this block is where those assertions run. One `-only-testing:` per
# invocation (the Plan C scoping trap).
CAST_OUT="$ROOT/.superpowers/sdd/2026-09-01-ios-phase3-offline-cast/screenshots/phase3-player-save-cast"
for device in "iPhone 17" "iPad Pro 13-inch (M5)"; do
    slug=$(echo "$device" | tr '[:upper:]' '[:lower:]' | sed -e 's/[^a-z0-9]\{1,\}/-/g' -e 's/^-//' -e 's/-$//')
    echo "== phase3-player-save-cast ($device) -> $CAST_OUT/$slug =="
    rm -rf "${CAST_OUT:?}/$slug"
    TEST_RUNNER_FITRAH_SHOTS_DIR="$CAST_OUT/$slug" xcodebuild test \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        -testPlan FitrahTubeUITests \
        -only-testing:FitrahTubeUITests/ScreenshotTests/testPlayerSaveAndCastToolbarPhase3 \
        -destination "platform=iOS Simulator,name=$device" \
        -derivedDataPath DerivedData \
        2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
    [ "${PIPESTATUS[0]}" -ne 0 ] && status=1
    xcrun simctl shutdown "$device" >/dev/null 2>&1
done

# Phase 4 Task 19 (docs/superpowers/plans/2026-09-02-ios-phase4-accounts.md): the account screens
# the R-G matrix cannot reach -- the two splash-router ROOT destinations picked from the fixture
# `/me` status (pending-profile, the blocked terminal alert) and the pushed Profile route. Same
# one-`-only-testing:`-per-invocation shape as the phase3-saved block above.
PHASE4_OUT="$ROOT/.superpowers/sdd/2026-09-02-ios-phase4-accounts/screenshots/phase4-accounts"
for device in "iPhone 17" "iPad Pro 13-inch (M5)"; do
    slug=$(echo "$device" | tr '[:upper:]' '[:lower:]' | sed -e 's/[^a-z0-9]\{1,\}/-/g' -e 's/^-//' -e 's/-$//')
    echo "== phase4-accounts ($device) -> $PHASE4_OUT/$slug =="
    rm -rf "${PHASE4_OUT:?}/$slug"
    TEST_RUNNER_FITRAH_SHOTS_DIR="$PHASE4_OUT/$slug" xcodebuild test \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        -testPlan FitrahTubeUITests \
        -only-testing:FitrahTubeUITests/ScreenshotTests/testAccountScreensPhase4 \
        -destination "platform=iOS Simulator,name=$device" \
        -derivedDataPath DerivedData \
        2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
    [ "${PIPESTATUS[0]}" -ne 0 ] && status=1
    xcrun simctl shutdown "$device" >/dev/null 2>&1
done

# Phase 4 Task 30 (fork F14) + Task 29: the two Part B screens the R-G matrix cannot reach -- the
# Me tab's Pending tab (needs AWAITING rows AND a way to select a tab the rig cannot tap) and the
# import review screen (needs a token and canned googleapis pages; `-fitrah-seed-import-review` is
# the one fixture state that supplies both, and nothing in that run leaves the device). Its own
# invocation rather than rows on `testAccountScreensPhase4`, so a Part B re-run does not re-shoot
# Part A's eight account screens.
PARTB_OUT="$ROOT/.superpowers/sdd/2026-09-02-ios-phase4-accounts/screenshots/phase4-partb"
for device in "iPhone 17" "iPad Pro 13-inch (M5)"; do
    slug=$(echo "$device" | tr '[:upper:]' '[:lower:]' | sed -e 's/[^a-z0-9]\{1,\}/-/g' -e 's/^-//' -e 's/-$//')
    echo "== phase4-partb ($device) -> $PARTB_OUT/$slug =="
    rm -rf "${PARTB_OUT:?}/$slug"
    TEST_RUNNER_FITRAH_SHOTS_DIR="$PARTB_OUT/$slug" xcodebuild test \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        -testPlan FitrahTubeUITests \
        -only-testing:FitrahTubeUITests/ScreenshotTests/testPartBScreensPhase4 \
        -only-testing:FitrahTubeUITests/ScreenshotTests/testImportCautionContinueStartsTheRun \
        -destination "platform=iOS Simulator,name=$device" \
        -derivedDataPath DerivedData \
        2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
    [ "${PIPESTATUS[0]}" -ne 0 ] && status=1
    xcrun simctl shutdown "$device" >/dev/null 2>&1
done

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

# Playback hardening Phase 1 (local-docs/plans/2026-10-14-ios-playback-hardening.md, Task 7): rung 1
# answered with a REAL HTTP 401 by a localhost server, the step-down to rung 2 still playing, and the
# not-available card -- en + ar, iPhone and iPad. Same localhost technique as the C task 6 live leg.
# Final review M3: the production shape. Playlists answer 200; rung 1 serves two 1 s segments and
# answers 401 for the rest (AVPlayer is READY first, so the failure is `.playbackError`, which steps
# down only through the 401 rule); rung 2 serves every segment. Synthetic ffmpeg test-pattern media
# in scripts/fixtures/hardening-hls (no YouTube content).
HARDENING_OUT="$ROOT/local-docs/screens/playback-hardening"
HARDENING_PORT="${HARDENING_PORT:-8766}"
python3 -I -c '
import http.server, os, sys
port, media = int(sys.argv[1]), sys.argv[2]
playlist = ("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-PLAYLIST-TYPE:VOD\n"
            + "".join("#EXTINF:1.0,\nseg%d.ts\n" % i for i in range(6)) + "#EXT-X-ENDLIST\n").encode()
class Rig(http.server.BaseHTTPRequestHandler):
    def reply(self, status, body=b"", kind="text/plain"):
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def do_GET(self):
        rung, _, name = self.path.lstrip("/").partition("/")
        if rung not in ("rung1", "rung2") or os.path.basename(name) != name:
            return self.reply(404)
        if name == "master.m3u8":
            return self.reply(200, playlist, "application/vnd.apple.mpegurl")
        if not (name.startswith("seg") and name.endswith(".ts") and name[3:-3].isdigit()):
            return self.reply(404)
        if rung == "rung1" and int(name[3:-3]) >= 2:
            return self.reply(401)
        if not os.path.isfile(os.path.join(media, name)):
            return self.reply(404)
        with open(os.path.join(media, name), "rb") as segment:
            self.reply(200, segment.read(), "video/mp2t")
    def log_message(self, *args):
        pass
http.server.ThreadingHTTPServer(("127.0.0.1", port), Rig).serve_forever()
' "$HARDENING_PORT" "$ROOT/ios/scripts/fixtures/hardening-hls" &
HARDENING_SERVER=$!
# A Ctrl-C or a killed xcodebuild must not leave the 401 server bound to the port (review M3).
trap 'kill "$HARDENING_SERVER" 2>/dev/null' EXIT
for device in "iPhone 17" "iPad Pro 13-inch (M5)"; do
    slug=$(echo "$device" | tr '[:upper:]' '[:lower:]' | sed -e 's/[^a-z0-9]\{1,\}/-/g' -e 's/^-//' -e 's/-$//')
    echo "== playback hardening: $device -> $HARDENING_OUT/$slug =="
    rm -rf "${HARDENING_OUT:?}/$slug"
    TEST_RUNNER_FITRAH_SHOTS_DIR="$HARDENING_OUT/$slug" \
        TEST_RUNNER_FITRAH_RUNG1_401_URL="http://localhost:$HARDENING_PORT/rung1/master.m3u8" xcodebuild test \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        -testPlan FitrahTubeUITests \
        -only-testing:FitrahTubeUITests/ScreenshotTests/testPlaybackHardeningStates \
        -destination "platform=iOS Simulator,name=$device" \
        -derivedDataPath DerivedData \
        2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
    [ "${PIPESTATUS[0]}" -ne 0 ] && status=1
    xcrun simctl shutdown "$device" >/dev/null 2>&1
done
kill "$HARDENING_SERVER" 2>/dev/null

# Playback Phase 2 (local-docs/plans/2026-10-17-ios-playback-phase2.md, Task 19; spec R34): 360p starts, the synthetic HD
# (fitrah-hls loader, byte ranges from this Range server; python3 -m http.server ignores Range) is swapped in at the same
# position -- en + ar, iPhone and iPad. Synthetic ffmpeg test-pattern media from InnerTubeKit's test fixtures (no YouTube
# content; nothing ships).
PHASE2_OUT="$ROOT/local-docs/screens/playback-phase2"
PHASE2_PORT="${PHASE2_PORT:-8767}"
python3 -I -c '
import http.server, os, re, sys
port, root = int(sys.argv[1]), sys.argv[2]
class Rig(http.server.BaseHTTPRequestHandler):
    def answer(self, send_body):
        name = self.path.lstrip("/").split("?")[0]
        path = os.path.join(root, name)
        if os.path.basename(name) != name or not os.path.isfile(path):
            self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers(); return
        data = open(path, "rb").read()
        match = re.fullmatch(r"bytes=(\d+)-(\d*)", self.headers.get("Range", ""))
        start = int(match.group(1)) if match else 0
        end = min(int(match.group(2)), len(data) - 1) if match and match.group(2) else len(data) - 1
        body = data[start:end + 1]
        self.send_response(206 if match else 200)
        if match: self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, len(data)))
        self.send_header("Content-Type", "application/json" if name.endswith(".json") else "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if send_body: self.wfile.write(body)
    def do_GET(self): self.answer(True)
    def do_HEAD(self): self.answer(False)
    def log_message(self, *args): pass
http.server.ThreadingHTTPServer(("127.0.0.1", port), Rig).serve_forever()
' "$PHASE2_PORT" "$ROOT/ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/Fixtures/synthetic" &
PHASE2_SERVER=$!
trap 'kill "$PHASE2_SERVER" 2>/dev/null' EXIT
for device in "iPhone 17" "iPad Pro 13-inch (M5)"; do
    slug=$(echo "$device" | tr '[:upper:]' '[:lower:]' | sed -e 's/[^a-z0-9]\{1,\}/-/g' -e 's/^-//' -e 's/-$//')
    echo "== playback phase 2: $device -> $PHASE2_OUT/$slug =="
    rm -rf "${PHASE2_OUT:?}/$slug"
    TEST_RUNNER_FITRAH_SHOTS_DIR="$PHASE2_OUT/$slug" \
        TEST_RUNNER_FITRAH_SYNTHETIC_BASE_URL="http://localhost:$PHASE2_PORT/" xcodebuild test \
        -project FitrahTube.xcodeproj \
        -scheme FitrahTube \
        -testPlan FitrahTubeUITests \
        -only-testing:FitrahTubeUITests/ScreenshotTests/testPlaybackPhase2States \
        -destination "platform=iOS Simulator,name=$device" \
        -derivedDataPath DerivedData \
        2>&1 | grep -E "Test case .* (passed|failed)|XCTAssert|TEST (SUCCEEDED|FAILED)|error:"
    [ "${PIPESTATUS[0]}" -ne 0 ] && status=1
    xcrun simctl shutdown "$device" >/dev/null 2>&1
done
kill "$PHASE2_SERVER" 2>/dev/null

exit "$status"
