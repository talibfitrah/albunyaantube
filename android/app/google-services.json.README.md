# `google-services.json` provisioning (Plan B / ANDROID-AUTH-01)

The real `google-services.json` is **gitignored** (see `/.gitignore` — `/android/app/google-services.json`). Each developer and CI must provide their own copy.

## Where to get it

Firebase console → Project Settings → Your apps → Android app (package `com.albunyaan.tube`) → download `google-services.json`.

If the Android app isn't registered yet, click "Add app" → Android → use package `com.albunyaan.tube`, SHA-1 of the debug keystore (Google sign-in requires this), then download.

## Where to place it

`android/app/google-services.json` — alongside this README.

## What it looks like

See `google-services.json.template` for the schema. All values are `__REPLACE_WITH_*__` placeholders; the real file fills them with project-specific strings from Firebase console.

## CI

CI must `echo "$GOOGLE_SERVICES_JSON" | base64 -d > android/app/google-services.json` before `./gradlew assembleDebug`. The base64-encoded full JSON lives in the repo's secret store as `GOOGLE_SERVICES_JSON`.

## Why not committed

The file contains the Android API key and OAuth client IDs. Even though Firebase API keys are not bearer credentials (they only identify the project, not authorise actions), committing them lets attackers more easily map abuse to our project ID. Same posture as the firebase-service-account.json scrub (P0, 2026-05-10).
