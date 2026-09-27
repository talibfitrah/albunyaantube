#!/bin/bash
# Generates ios/Packages/FitrahAPI/Sources/FitrahAPI/GeneratedSources from the OpenAPI spec.
# The spec is copied next to the config only for the duration of the run: the SwiftPM plugin
# sandbox cannot read files outside the package directory.
# SwiftPM refuses to run the plugin on an empty target: if GeneratedSources/ has been deleted,
# a temporary placeholder .swift file must exist in Sources/FitrahAPI/ before regenerating.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PKG="$ROOT_DIR/ios/Packages/FitrahAPI"
SRC="$PKG/Sources/FitrahAPI"
cp "$ROOT_DIR/docs/architecture/api-specification.yaml" "$SRC/openapi.yaml"
trap 'rm -f "$SRC/openapi.yaml"' EXIT
cd "$PKG"
swift package plugin --allow-writing-to-package-directory generate-code-from-openapi --target FitrahAPI
echo "✅ Swift client generated to: ios/Packages/FitrahAPI/Sources/FitrahAPI/GeneratedSources/"
