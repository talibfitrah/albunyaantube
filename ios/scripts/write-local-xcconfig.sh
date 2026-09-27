#!/usr/bin/env bash
# Writes the two untracked build settings the sign-in buttons gate on into ios/Config/Local.xcconfig
# (gitignored), derived from the bundled GoogleService-Info.plist:
#   GID_REVERSED_CLIENT_ID          Google Sign-In's OAuth callback URL scheme (project.yml ->
#                                   CFBundleURLSchemes). Derived from CLIENT_ID by the SDK's own
#                                   rule -- dot-components reversed, lower-cased -- which is exactly
#                                   what SignInCapabilities.googleCallbackSchemeMatches checks, so
#                                   the two agree by construction.
#   FITRAH_APPLE_SIGNIN_REGISTERED  the App ID has Sign in with Apple in the signing team's portal
#                                   (SignInCapabilities.appleSignInIsConfigured).
# NEVER prints a value -- output is key names and lengths only. Do not add `set -x`.
# Idempotent: every other line already in Local.xcconfig is kept; its own two keys are replaced.
#   APPLE_SIGNIN_REGISTERED="" bash ios/scripts/write-local-xcconfig.sh   # a team without it
#   FITRAH_PLIST=... FITRAH_OUT=...   overrides, for checking the script against a synthetic plist
set -euo pipefail
set +x

IOS="$(cd "$(dirname "$0")/.." && pwd)"
PLIST="${FITRAH_PLIST:-$IOS/FitrahTube/Resources/GoogleService-Info.plist}"
OUT="${FITRAH_OUT:-$IOS/Config/Local.xcconfig}"
APPLE="${APPLE_SIGNIN_REGISTERED-1}"
case "$APPLE" in 0|false|no|NO) APPLE="" ;; esac   # off is off, however it is spelled
PB=/usr/libexec/PlistBuddy
me="write-local-xcconfig.sh"

fail() { echo "$me: $1" >&2; exit 1; }

[ -f "$PLIST" ] || fail "no GoogleService-Info.plist in Resources/ -- run ios/scripts/copy-firebase-plist.sh first"

bundle="$("$PB" -c 'Print :BUNDLE_ID' "$PLIST" 2>/dev/null || true)"
[ "$bundle" = "com.albunyaan.tube" ] || fail "the plist's BUNDLE_ID is missing or is not com.albunyaan.tube -- wrong Firebase iOS app"

client="$("$PB" -c 'Print :CLIENT_ID' "$PLIST" 2>/dev/null || true)"
[ -n "$client" ] || fail "the plist has no CLIENT_ID -- enable the Google provider in Firebase, then fetch the plist again"

# OUT must be ignored by whatever work tree holds it: this file names a real OAuth client.
# Resolved through its own directory (not $IOS) so a relative path or one outside ios/ cannot
# slip past the check.
outdir="$(cd "$(dirname "$OUT")" && pwd)"
if git -C "$outdir" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    git -C "$outdir" check-ignore -q "$outdir/$(basename "$OUT")" || fail "$OUT is NOT gitignored -- refusing to write a client id into a trackable file"
fi

reversed="$(printf '%s' "$client" | awk -F. '{ for (i = NF; i > 0; i--) printf "%s%s", $i, (i > 1 ? "." : "") }' | tr '[:upper:]' '[:lower:]')"
# An xcconfig value is cut at `//` and may not span lines. A reversed Google client id is
# [a-z0-9.-] only, so anything else (a `+`, a space, a `/`) means the plist is not what we think.
case "$reversed" in ''|*[!a-z0-9.-]*) fail "the derived scheme has unexpected characters -- refusing to write" ;; esac

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
if [ -f "$OUT" ]; then
    # grep exits 1 for "no line survived" (fine) and 2 for a read error (not fine).
    grep -v -E '^[[:space:]]*(GID_REVERSED_CLIENT_ID|FITRAH_APPLE_SIGNIN_REGISTERED)[[:space:]]*=' "$OUT" > "$tmp" || [ $? -eq 1 ] || fail "cannot read $OUT"
    # A last line with no newline would otherwise swallow the first key appended below.
    [ ! -s "$tmp" ] || [ -z "$(tail -c1 "$tmp")" ] || echo >> "$tmp"
fi
printf 'GID_REVERSED_CLIENT_ID = %s\n' "$reversed" >> "$tmp"
[ -z "$APPLE" ] || printf 'FITRAH_APPLE_SIGNIN_REGISTERED = 1\n' >> "$tmp"
mv "$tmp" "$OUT"
trap - EXIT

echo "$me: wrote GID_REVERSED_CLIENT_ID (${#reversed} chars)${APPLE:+ and FITRAH_APPLE_SIGNIN_REGISTERED} to ${OUT#"$IOS"/}"
