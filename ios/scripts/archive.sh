#!/usr/bin/env bash
# Signed Release archive -> App Store export. NOT part of the gate (needs signing + network).
#   BUILD_NUMBER=7 bash ios/scripts/archive.sh            # -> DerivedData-Release/Archive/export/*.ipa
#   BUILD_NUMBER=7 UPLOAD=1 bash ios/scripts/archive.sh   # exports AND uploads to App Store Connect
#   BUILD_NUMBER=7 FITRAH_APP=/path/to/Some.app bash ios/scripts/archive.sh   # preflight only,
#                                            # against that app (its CFBundleVersion must be 7)
# BUILD_NUMBER is REQUIRED and must be higher than every build already uploaded for this version.
# Signing auth: an App Store Connect API key when $HOME/.appstoreconnect/fitrahtube.env exists
# (ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH), else Xcode signed in to team 72PF8SBQR6.
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
    # SignInCapabilities.appleSignInIsConfigured: empty hides the Apple button (guideline 4.8).
    [ -n "$("$PB" -c 'Print :FITRAH_APPLE_SIGNIN_REGISTERED' "$APP/Info.plist" 2>/dev/null)" ] || fail "FITRAH_APPLE_SIGNIN_REGISTERED is empty (no Sign in with Apple button)"
    local ent
    ent="$(codesign -d --entitlements - --xml "$APP" 2>/dev/null || true)"
    echo "$ent" | grep -q 'com.apple.developer.applesignin' || fail "Sign in with Apple entitlement missing from the signature"
    echo "$ent" | grep -q 'applinks:app.fitrahtube.com' || fail "Associated Domains entitlement missing from the signature"
    [ -f "$APP/PrivacyInfo.xcprivacy" ] || fail "privacy manifest missing"
    # Apple parses privacy manifests as strict XML (ITMS-91056); plutil accepts e.g. "--" inside a
    # comment, xmllint does not. Check every manifest the app ships, SDK bundles included.
    local manifest
    while IFS= read -r manifest; do
        xmllint --noout "$manifest" 2>/dev/null || fail "privacy manifest is not well-formed XML: ${manifest#"$APP"/}"
    done < <(find "$APP" -name PrivacyInfo.xcprivacy)
    echo "archive.sh: preflight OK"
}

if [ -n "${FITRAH_APP:-}" ]; then
    preflight
    exit 0
fi

# This script echoes none of these values, but xcodebuild prints its own command line, so the key
# id, issuer id and key PATH appear in its log (never the .p8's contents). The file is sourced as
# shell, so it must be the user's own and closed to group/other.
AUTH=()
ASC_ENV="$HOME/.appstoreconnect/fitrahtube.env"
if [ -f "$ASC_ENV" ]; then
    [ "$(stat -f '%Su' "$ASC_ENV")" = "$(id -un)" ] || fail "$ASC_ENV is not owned by $(id -un)"
    case "$(stat -f '%Lp' "$ASC_ENV")" in *00) ;; *) fail "$ASC_ENV is group/other accessible -- chmod 600 it";; esac
    # shellcheck disable=SC1090
    . "$ASC_ENV"
    [ -n "${ASC_KEY_ID:-}" ] && [ -n "${ASC_ISSUER_ID:-}" ] && [ -f "${ASC_KEY_PATH:-}" ] || fail "$ASC_ENV needs ASC_KEY_ID, ASC_ISSUER_ID and an existing ASC_KEY_PATH"
    AUTH=(-authenticationKeyPath "$ASC_KEY_PATH" -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID")
    echo "archive.sh: signing with the App Store Connect API key"
fi

bash scripts/copy-firebase-plist.sh
bash scripts/write-local-xcconfig.sh
[ -d Vendor/GoogleCast.xcframework ] || ./scripts/fetch-cast-sdk.sh
xcodegen generate

# Same trap test.sh documents: -onlyUsePackageVersionsFromResolvedFile on a -derivedDataPath whose
# SourcePackages cache is EMPTY (a fresh checkout) blocks the package checkout instead of fetching
# it, and xcodebuild fails with no obvious cause. One-time bootstrap for that directory
# (~44 s, ~1.6 GB); never delete an existing SourcePackages. `workspace-state.json` is present
# once SPM has finished a resolve; absent on a fresh checkout.
[ -f DerivedData-Release/SourcePackages/workspace-state.json ] || \
    xcodebuild -resolvePackageDependencies -project FitrahTube.xcodeproj -derivedDataPath DerivedData-Release

# Only the Archive subtree is recreated; DerivedData-Release/SourcePackages is never deleted.
rm -rf "${OUT:?}"
xcodebuild archive \
    -project FitrahTube.xcodeproj -scheme FitrahTube -configuration Release \
    -destination 'generic/platform=iOS' -archivePath "$ARCHIVE" \
    -derivedDataPath DerivedData-Release -onlyUsePackageVersionsFromResolvedFile \
    -allowProvisioningUpdates ${AUTH[@]+"${AUTH[@]}"} CURRENT_PROJECT_VERSION="$BUILD_NUMBER"

preflight

OPTS="$OUT/ExportOptions.plist"
cp ExportOptions.plist "$OPTS"
[ "${UPLOAD:-0}" = "1" ] && "$PB" -c 'Set :destination upload' "$OPTS"
xcodebuild -exportArchive -archivePath "$ARCHIVE" -exportOptionsPlist "$OPTS" \
    -exportPath "$OUT/export" -allowProvisioningUpdates ${AUTH[@]+"${AUTH[@]}"}
echo "archive.sh: done -> ios/$OUT/export"
