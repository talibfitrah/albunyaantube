#!/usr/bin/env bash
# Phase 4 Task 1: stages the Firebase config into the app's Resources/ so `xcodegen generate`'s
# `sources` glob picks it up. The file is git-ignored (.gitignore:208 `**/GoogleService-Info.plist`)
# and USER-BLOCKED, so its absence is normal and must never fail a build -- this script exits 0
# either way.
#
#   Xcode does not run this. A developer opening the project directly gets no plist until they
#   run this script or `ios/scripts/test.sh`.
#
# Run it from anywhere: both the source and the destination are absolute.
# NEVER read or copy anything else out of $HOME/.config/albunyaan/ -- the backend's admin
# credential (firebase-service-account.json) lives in that same folder.
set -uo pipefail

SRC="$HOME/.config/albunyaan/GoogleService-Info.plist"
DEST="$(cd "$(dirname "$0")/.." && pwd)/FitrahTube/Resources/GoogleService-Info.plist"

if [ ! -f "$SRC" ]; then
    echo "copy-firebase-plist.sh: no $SRC -- skipping (Firebase-backed screens stay unconfigured)"
    exit 0
fi

# `-nt` is true when DEST is missing OR when a refreshed SRC is newer, which is the whole contract:
# a `[ -f "$DEST" ] ||` guard (the Cast heal's shape) would pin the first copy forever and silently
# ignore a re-downloaded config. Copying only when newer keeps the resource's mtime stable across
# gate runs, so Xcode does not re-copy it into the bundle every build.
if [ "$SRC" -nt "$DEST" ]; then
    cp "$SRC" "$DEST" || exit $?
    echo "copy-firebase-plist.sh: copied $SRC -> $DEST"
else
    echo "copy-firebase-plist.sh: $DEST already current"
fi
