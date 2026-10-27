# FitrahTube 1.0.4 — Machine Handoff (2026-10-27)

Use this file to resume the 1.0.4 release on another machine. The repo is **public**, so this file holds no server addresses, logins or open security details. Those live in the private handoff bundle (below), which you copy across yourself.

---

## 1. Set up the new machine

1. **Code**
   ```bash
   git fetch origin && git checkout feature/104-release && git pull
   ```
   HEAD is the commit that adds this file; the last code commit is `bbe874ff`. Every local branch's work is in this branch: the side branches' commits were cherry-picked into it.
2. **Private bundle**: copy `fitrahtube-handoff-2026-10-27.tar.gz` from the old machine (its home folder) by AirDrop or USB. **Never commit or upload it.** Then:
   ```bash
   tar xzf fitrahtube-handoff-2026-10-27.tar.gz           # -> fitrahtube-handoff/
   rsync -a fitrahtube-handoff/repo/ <repo>/              # restores .superpowers/ and local-docs/
   echo '.superpowers/' >> <repo>/.git/info/exclude       # .superpowers/ is NOT in .gitignore: without this it shows as untracked and could be pushed to the public repo
   mkdir -p ~/.claude/projects/<repo-path-with-slashes-as-dashes>/memory
   rsync -a fitrahtube-handoff/claude-memory/ ~/.claude/projects/<repo-path-with-slashes-as-dashes>/memory/
   ```
   - `fitrahtube-handoff/scripts/` holds the deploy and verification scripts: `t17.sh` (backend jar swap), `t18s.sh` (solver swap), `r11-final.sh` (backend unit + IT + jar hash) and `it-session.sh`. They used a session scratch path. Fix the `S=` line at the top before using them.
   - `fitrahtube-handoff/git-info-exclude` is a copy of the old machine's `.git/info/exclude`.
3. **Secrets and local config** are not in the bundle. Copy them yourself if the new machine lacks them:
   - repo-root `.env` (holds `BACKEND_PROD_SERVER_SUDO_PASSWORD`);
   - the prod SSH key;
   - the Firebase service account (outside the repo, via `GOOGLE_APPLICATION_CREDENTIALS`; never committed);
   - `android/keystore.properties` and keystores;
   - `android/app/google-services.json` and `ios/Config/Local.xcconfig`;
   - the App Store Connect API key;
   - the test-account Keychain item.
4. **Toolchain**:
   - JDK 17: point `JAVA_HOME` at the JDK's `Contents/Home`. A bare `~/.local/jdk` path fails.
   - Android SDK, Xcode 26.3, XcodeGen and Node 22.
   - `cubic` CLI (logged in) and `graphify`.
   - macOS has no `timeout`, so the frontend uses `npx vitest run`.
5. **Read next**:
   - `.superpowers/sdd/2026-10-16-release-1.0.4/HANDOFF.md`, from §17 to the end (§17.16 is the latest);
   - then `progress.md` in the same folder;
   - then `pipeline/stage9-cubic-r12-triage.md`.

---

## 2. Where things stand

**The review stage is done.** The mandatory 9-stage review pipeline is complete for the 1.0.4 branch. Its stage 9 exit rule (two consecutive Cubic rounds with no P0/P1) was met by rounds 11b and 12.

**Last gates, at `bbe874ff`:**

| Area | Result |
|------|--------|
| Backend unit | 1960 / 0 failures |
| Backend integration (Firebase emulator) | 296 / 0 |
| iOS (iPhone + iPad) | 2024 / 2024 each, Release build alive |
| Android unit | 1680 |
| Admin frontend (vitest) | 337 passed / 4 skipped |
| Solver | 57 / 57 |

**Prod:**

| Item | State |
|------|-------|
| Backend | Hotfix jar #6, built from `bbe874ff` (SHA-256 `79e37242…5461`), live since 2026-10-27 05:15Z. Its backup is the previous jar. |
| Solver sidecar | The S19 build, live (not used by released apps: HD is off by remote config) |
| YouTube circuit breaker | **CLOSED at 06:30:01Z**, still closed at 12:14Z |
| Live-channels migration | **Not run yet** (the live endpoint returns 404) |
| Admin dashboard | F18–F20 not deployed; they ship with the release |

---

## 3. Next, in order

**Code (one fix batch per platform, test-first, then one Cubic round):**
1. **B24 (backend)**
   - Cubic r12 #1: the conditional save (`saveIfStatus` in the Video, Channel and Playlist repositories) writes back the whole doc it read. An approve or note edit in flight therefore overwrites a take-down's `ARCHIVED` (made reachable again by `bbe874ff`).
     - Fix it in the shared save: in the transaction, keep a stored `ARCHIVED` unless the stored status is `REJECTED`.
     - It has 16 callers, listed in the r12 triage. `reAdd` over a take-down record must still win.
   - Cubic r12 #3: `LiveTabReader.failure()` must treat the "not a bot" check as a rate limit, as the shared breaker does.
2. **I21 (iOS)**: Cubic r12 #4. The live branch of `PlayerViewModel.playToEnd()` needs the same `resolvedVideoId == args.videoId` guard as `handleRecoveryEvent`. Include r12 #10 (a failed silent refresh on a hidden screen drops the parked trigger) if it is cheap.
3. **A16 (Android)**: Cubic r12 #11. A saved copy must not bypass live-channel mode: guard on `liveChannelId == null` before `savedCopyOf` in `PlayerViewModel.loadVideo`.
4. Run the backend unit + IT suites, deploy hotfix jar #7, then Cubic r13: `cubic review -b 8ba6d6ad -j`.
   - The JSON key is `issues`. Also check the `error` key: rc 1 with `issues: []` can be a failed run.
   - Run `graphify update .` after code changes.

**Owner:**
- **Run the live-channels migration now** that the breaker is closed: from the admin dashboard, or supply an admin token.
- Check the admin Activity log for moderator re-adds between 21:11Z and 22:36Z on 2026-10-26.
- Run the live tests on the emulator and the iPhone (live-channels plan tasks 28b and 33–36) and the soaks (at least 6.5 h per platform, including K12 on the iPhone).
- Run Phase 2 Task 23 on the iPhone.
- Decide the data items: the 10 empty playlists, the 4 wrongly archived channels, and the `/import/simple` route.

**Release** (after the above):
- the 24-hour prod check (live Task 17);
- Phase 2 Task 24 (HD remote-config publish and the kill-switch drill);
- live Task 37 (debug-hook deletes);
- `RELEASE=1 bash ios/scripts/test.sh` (mandatory before any iOS release build);
- release builds (Play flavor only);
- check Apple's 1.0.3 review state (`minAppVersion`, App Store button);
- store submissions and the tag.

Versions are already bumped: Android 66 / 1.0.4, iOS 1.0.4, and `releases-meta.json` has 1.0.4.

**Before the HD rollout (Task 24)**, the solver hardening items in the private handoff (§17.15 and the r11b/r12 triage) must be fixed. Inner search stays off in prod until its own blocker list is cleared.

---

## 4. Working rules (full set in `CLAUDE.md`, `AGENTS.md` and the private memory)

- **Process**
  - Every code change gets the 9-stage pipeline (`AGENTS.md`), test-first, with the root cause fixed.
  - Run `graphify update .` after code changes.
- **Never push or upload**: `.env`, the service account, keystores, `.superpowers/`, `local-docs/`.
- **Commits**: `[PREFIX]: Description (≤50 chars)`, ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Heavy jobs**: run every gradle, xcodebuild and emulator job through `.superpowers/sdd/2026-10-16-release-1.0.4/heavy.sh run <label> <cmd>`, one at a time.
- **Product**
  - No YouTube player, embed, hand-off or link-out.
  - No music-video ids in tests.
  - No YouTube in reviewer notes.
  - Testers stay internal.
- **Tests**: 300 s wall, 30 s per method.
- **UI**: check UI changes on phone, sw600dp, sw720dp and RTL (Android), and on iPhone and iPad in English and Arabic.
