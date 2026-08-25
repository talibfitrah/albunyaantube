#!/usr/bin/env bash
# Pre-submission gate for the Google Play build.
#
# Every check here corresponds to something that actually went wrong during the
# 2026-08-25 Play-readiness review, or to a live dependency that cannot be proven
# from source alone. Run it after deploying the backend, installing the real
# Firebase config and creating the upload keystore — and again before any future
# Play release.
#
# Usage:  ./scripts/preflight-play.sh
# Exit 0 = safe to submit. Exit 1 = at least one blocker.

set -uo pipefail
cd "$(dirname "$0")/.."

: "${JAVA_HOME:=$HOME/.local/jdk/jdk-17.0.20.1+1/Contents/Home}"
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
export JAVA_HOME ANDROID_HOME
export PATH="$JAVA_HOME/bin:$PATH"

HOST="https://app.fitrahtube.com"
AAPT="$(ls -d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V | tail -1)aapt2"

pass=0; fail=0; skip=0
ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; printf '        %s\n' "$2"; fail=$((fail+1)); }
warn() { printf '  \033[33mSKIP\033[0m  %s\n' "$1"; printf '        %s\n' "$2"; skip=$((skip+1)); }

echo
echo "=== 1. Live pages Google will open ==="
# A dead privacy URL is an automatic rejection, and the Data Safety form cannot be
# completed without a working account-deletion URL. These 403'd until the backend
# carrying SecurityConfig's permitAll was actually deployed.
for path in privacy terms licenses delete-account; do
  code=$(curl -s -o /tmp/pf_body -w '%{http_code}' --max-time 20 "$HOST/$path" 2>/dev/null)
  if [ "$code" = "200" ]; then
    if grep -qi "TODO" /tmp/pf_body; then
      bad "$HOST/$path" "renders, but the body contains 'TODO' — an internal note is being published"
    else
      ok "$HOST/$path returns 200"
    fi
  else
    bad "$HOST/$path" "returned HTTP $code — deploy the backend (see docs/status/DEPLOYMENT_GUIDE.md)"
  fi
done
rm -f /tmp/pf_body

echo
echo "=== 2. Firebase config ==="
GSJ=android/app/google-services.json
if [ ! -f "$GSJ" ]; then
  bad "google-services.json" "missing — download it from the Firebase console"
elif grep -q "ci-stub" "$GSJ"; then
  bad "google-services.json" "is the CI placeholder; sign-in would fail and the app shows nothing"
else
  missing=""
  for pkg in com.albunyaan.tube com.albunyaan.tube.play; do
    grep -q "\"$pkg\"" "$GSJ" || missing="$missing $pkg"
  done
  if [ -n "$missing" ]; then
    bad "google-services.json" "no Android client for:$missing"
  else
    ok "google-services.json is real and covers both package names"
  fi
fi

echo
echo "=== 3. Upload signing key ==="
if [ -f android/keystore.properties ]; then
  ok "keystore.properties present (release will be properly signed)"
else
  bad "keystore.properties" "absent — the release would build UNSIGNED. Create the key and BACK IT UP."
fi

echo
echo "=== 4. Build the Play bundle ==="
if [ "$fail" -gt 0 ]; then
  warn "bundlePlayRelease" "skipped while earlier checks are failing"
else
  if (cd android && ./gradlew :app:bundlePlayRelease --no-daemon -q >/tmp/pf_build 2>&1); then
    AAB=$(find android/app/build/outputs/bundle/playRelease -name '*.aab' | head -1)
    ok "bundlePlayRelease succeeded ($(du -h "$AAB" | cut -f1))"
    signer=$(keytool -printcert -jarfile "$AAB" 2>/dev/null | awk -F'CN=' '/Owner:/{print $2; exit}')
    case "${signer:-none}" in
      *"Android Debug"*) bad "AAB signature" "signed with the DEBUG key — uploading this permanently poisons your upload key" ;;
      none|"")           bad "AAB signature" "unsigned" ;;
      *)                 ok  "AAB signed by: ${signer%%,*}" ;;
    esac
  else
    bad "bundlePlayRelease" "build failed — see /tmp/pf_build"
  fi
fi

echo
echo "=== 5. What the Play artifact actually contains ==="
APK_DIR=android/app/build/outputs/apk/play
if [ ! -x "$AAPT" ]; then
  warn "artifact inspection" "aapt2 not found under \$ANDROID_HOME/build-tools"
else
  (cd android && ./gradlew :app:assemblePlayDebug :app:assembleSideloadDebug --no-daemon -q >/dev/null 2>&1)
  PLAY=$(find "$APK_DIR" -name '*.apk' 2>/dev/null | head -1)
  SIDE=$(find android/app/build/outputs/apk/sideload -name '*.apk' 2>/dev/null | head -1)
  if [ -z "$PLAY" ]; then
    warn "artifact inspection" "no play APK built"
  else
    n=$("$AAPT" dump permissions "$PLAY" | grep -cE 'REQUEST_INSTALL_PACKAGES|UPDATE_PACKAGES_WITHOUT')
    [ "$n" -eq 0 ] && ok "Play build declares no install permissions" \
                   || bad "Play build" "declares $n install permission(s) — Play forbids self-updating apps"

    d=$(unzip -p "$PLAY" 'classes*.dex' 2>/dev/null | strings | grep -cE 'ApkInstaller|InstallStatusActivity|UpdateChecker' || true)
    [ "$d" -eq 0 ] && ok "Play build contains no self-updater code" \
                   || bad "Play build" "$d reference(s) to updater classes found in dex"

    t=$("$AAPT" dump badging "$PLAY" | grep -oE "targetSdkVersion:'[0-9]+'" | grep -oE '[0-9]+')
    [ "${t:-0}" -ge 36 ] && ok "targetSdk $t" \
                         || bad "targetSdk" "$t — Play requires 36+ for new submissions since 2026-08-31"

    "$AAPT" dump badging "$PLAY" | grep -q "application-debuggable" \
      && bad "Play build" "is debuggable — never upload a debuggable artifact" \
      || ok "Play build is not debuggable"
  fi
  if [ -n "$SIDE" ]; then
    n=$("$AAPT" dump permissions "$SIDE" | grep -cE 'REQUEST_INSTALL_PACKAGES|UPDATE_PACKAGES_WITHOUT')
    [ "$n" -eq 2 ] && ok "Sideload build keeps its updater (2 install permissions)" \
                   || bad "Sideload build" "expected 2 install permissions, found $n — the sideload track lost its updater"
  fi
fi

echo
echo "=== 6. Things only a human can confirm ==="
cat <<'MANUAL'
  [ ] Store listing contains none of: "ad-free", "no ads", "download",
      "background play", "YouTube", "best", "#1", "free" (in the title)
  [ ] Target audience is 13+ or 18+ — NO under-13 age bracket ticked
  [ ] Icon and screenshots show no children and no cartoon characters
  [ ] App Access has a WORKING test account, and you have signed in with it
      yourself on a clean install since the last build
  [ ] The upload keystore is backed up somewhere you will still have in 5 years
  [ ] Foreground service declaration submitted with its demo video
MANUAL

echo
printf 'passed %d   failed %d   skipped %d\n' "$pass" "$fail" "$skip"
if [ "$fail" -gt 0 ]; then
  echo "NOT ready to submit."
  exit 1
fi
echo "Automated checks clear. Work the manual list above, then submit."
