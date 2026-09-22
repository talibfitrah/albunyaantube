#!/usr/bin/env bash
# Signed Release archive -> App Store export. NOT part of the gate (needs signing + network).
#   BUILD_NUMBER=7 bash ios/scripts/archive.sh            # -> DerivedData-Release/Archive/export/*.ipa
#   BUILD_NUMBER=7 UPLOAD=1 bash ios/scripts/archive.sh   # exports AND uploads to App Store Connect
#   BUILD_NUMBER=7 FITRAH_APP=/path/to/Some.app bash ios/scripts/archive.sh   # preflight only,
#                                            # against that app (its CFBundleVersion must be 7)
# BUILD_NUMBER is REQUIRED and must be higher than every build already uploaded for this version.
# Needs Xcode signed in to team 72PF8SBQR6 (Xcode > Settings > Accounts).
# Preflight prints facts only, never values. Do not add `set -x`.
set -euo pipefail
PATH="$HOME/.local/bin:$PATH"

fail() { echo "archive.sh: PREFLIGHT FAILED -- $1" >&2; exit 1; }
case "${BUILD_NUMBER:-}" in ''|*[!0-9]*) fail "BUILD_NUMBER must be an integer above the last uploaded build";; esac

cd "$(dirname "$0")/.."
OUT="DerivedData-Release/Archive"
ARCHIVE="$OUT/FitrahTube.xcarchive"
APP="${FITRAH_APP:-$ARCHIVE/Products/Applications/FitrahTube.app}"
PB=/usr/libexec/PlistBuddy

preflight() {
    local types
    [ -f "$APP/GoogleService-Info.plist" ] || fail "no GoogleService-Info.plist in the app (every sign-in would be hidden)"
    types="$("$PB" -c 'Print :CFBundleURLTypes' "$APP/Info.plist" 2>/dev/null)" || fail "no CFBundleURLTypes in Info.plist"
    case "$types" in *no-client-id*) fail "placeholder Google callback scheme shipped";; esac
    [ "$("$PB" -c 'Print :API_BASE_URL' "$APP/Info.plist")" = "https://app.fitrahtube.com/" ] || fail "API_BASE_URL is not production"
    [ "$("$PB" -c 'Print :CFBundleVersion' "$APP/Info.plist")" = "$BUILD_NUMBER" ] || fail "CFBundleVersion is not $BUILD_NUMBER"
    local ent
    ent="$(codesign -d --entitlements - --xml "$APP" 2>/dev/null || true)"
    echo "$ent" | grep -q 'com.apple.developer.applesignin' || fail "Sign in with Apple entitlement missing from the signature"
    echo "$ent" | grep -q 'applinks:app.fitrahtube.com' || fail "Associated Domains entitlement missing from the signature"
    [ -f "$APP/PrivacyInfo.xcprivacy" ] || fail "privacy manifest missing"
    echo "archive.sh: preflight OK"
}

if [ -n "${FITRAH_APP:-}" ]; then
    preflight
    exit 0
fi

bash scripts/copy-firebase-plist.sh
bash scripts/write-local-xcconfig.sh
[ -d Vendor/GoogleCast.xcframework ] || ./scripts/fetch-cast-sdk.sh
xcodegen generate

# Only the Archive subtree is recreated; DerivedData-Release/SourcePackages is never deleted.
rm -rf "${OUT:?}"
xcodebuild archive \
    -project FitrahTube.xcodeproj -scheme FitrahTube -configuration Release \
    -destination 'generic/platform=iOS' -archivePath "$ARCHIVE" \
    -derivedDataPath DerivedData-Release -onlyUsePackageVersionsFromResolvedFile \
    -allowProvisioningUpdates CURRENT_PROJECT_VERSION="$BUILD_NUMBER"

preflight

OPTS="$OUT/ExportOptions.plist"
cp ExportOptions.plist "$OPTS"
[ "${UPLOAD:-0}" = "1" ] && "$PB" -c 'Set :destination upload' "$OPTS"
xcodebuild -exportArchive -archivePath "$ARCHIVE" -exportOptionsPlist "$OPTS" \
    -exportPath "$OUT/export" -allowProvisioningUpdates
echo "archive.sh: done -> ios/$OUT/export"
