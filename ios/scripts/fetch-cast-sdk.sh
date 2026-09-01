#!/usr/bin/env bash
# Fetches the Google Cast iOS Sender SDK (dynamic XCFramework) into ios/Vendor/.
# Download link taken from https://developers.google.com/cast/docs/ios_sender ("Dynamic library").
set -euo pipefail

VERSION=4.8.6
URL="https://dl.google.com/dl/chromecast/sdk/ios/GoogleCastSDK-ios-${VERSION}_dynamic.zip"
# SHA-256 of the archive at $URL, computed 2026-09-01 from the fetched file.
SHA256=55f6c21291a1315c68063f07e7d76225564bff70f2fd38caad135c71d66eb310

VENDOR="$(cd "$(dirname "$0")/.." && pwd)/Vendor"
XCF="$VENDOR/GoogleCast.xcframework"

if [ -d "$XCF" ]; then
  echo "already present: $XCF"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
ZIP="$TMP/cast.zip"

echo "downloading $URL"
curl -fsSL -o "$ZIP" "$URL"
ACTUAL="$(shasum -a 256 "$ZIP" | cut -d' ' -f1)"
if [ "$ACTUAL" != "$SHA256" ]; then
  echo "SHA-256 mismatch for $URL" >&2
  echo "  expected $SHA256" >&2
  echo "  actual   $ACTUAL" >&2
  exit 1
fi

echo "zip top-level layout:"
unzip -Z1 "$ZIP" | cut -d/ -f1-2 | sort -u

unzip -q "$ZIP" -d "$TMP/x"
mkdir -p "$VENDOR"
# The zip nests everything under GoogleCastSDK-ios-<ver>_dynamic_xcframework/; flatten into Vendor/.
mv "$TMP"/x/GoogleCastSDK-ios-*_xcframework/* "$VENDOR"/

[ -d "$XCF" ] || { echo "expected $XCF after extraction" >&2; exit 1; }
echo "xcframeworks in $VENDOR:"
find "$VENDOR" -maxdepth 1 -name '*.xcframework'
