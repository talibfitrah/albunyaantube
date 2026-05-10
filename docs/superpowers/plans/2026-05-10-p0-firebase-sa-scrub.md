# P0: Firebase Service Account Scrub (Level 2)

> **Status:** Approved 2026-05-10 (user is sole repo contributor; rotation skipped, user accepts residual risk).

**Goal:** Remove `firebase-service-account.json` from every commit on every branch, prevent re-introduction via `.gitignore` + a pre-commit guard, relocate the runtime credential to a path outside the repo, and force-push the rewritten history to the remote.

**Why before Plan A:** Plan A modifies backend code in the same tree. Doing the history rewrite first means Plan A commits land on the clean history; doing it after creates a much larger force-push and risks losing in-flight work.

**Tools:** `git filter-repo` (preferred over `git filter-branch` per Git project guidance).

---

## Pre-flight checks

- [ ] **Step 1: Confirm working tree is clean except known untracked files**

```bash
git status --short
```

Expected: only the three known untracked items (`beta8_error.jpeg`, `dialog_not_showing_buttons.jpeg`, `logs_202652_234432.txt`). If anything else is dirty, commit or stash before proceeding.

- [ ] **Step 2: Confirm git-filter-repo is installed**

```bash
which git-filter-repo || pipx install git-filter-repo || sudo apt install git-filter-repo
git filter-repo --version
```

Expected: prints a version number.

- [ ] **Step 3: Take a safety backup of the credential** (it's still needed at runtime by the dev backend)

```bash
mkdir -p ~/.config/albunyaan
cp backend/src/main/resources/firebase-service-account.json ~/.config/albunyaan/firebase-service-account.json
chmod 600 ~/.config/albunyaan/firebase-service-account.json
ls -l ~/.config/albunyaan/firebase-service-account.json
```

Expected: file exists at `~/.config/albunyaan/firebase-service-account.json` with mode `-rw-------`.

- [ ] **Step 4: Take a safety backup of the repo itself** (in case the rewrite goes wrong)

```bash
cd ..
tar --exclude='albunyaantube/.git/objects/pack/*' -czf albunyaantube-pre-scrub-$(date +%Y%m%d-%H%M).tar.gz albunyaantube/
ls -lh albunyaantube-pre-scrub-*.tar.gz
cd albunyaantube
```

Expected: tarball created next to the repo. Keep until the scrub is verified.

- [ ] **Step 5: Capture the list of branches and tags that will need force-push**

```bash
git branch -a > /tmp/branches-before-scrub.txt
git tag -l > /tmp/tags-before-scrub.txt
git log --oneline --all -- backend/src/main/resources/firebase-service-account.json | wc -l
```

Expected: prints non-zero count of commits touching the file (so we know it's actually in history).

---

## History rewrite

- [ ] **Step 6: Run filter-repo to remove the file from all history**

```bash
git filter-repo --invert-paths --path backend/src/main/resources/firebase-service-account.json --force
```

Expected: progress output, "New history written," and a fresh `.git/filter-repo/` directory.

- [ ] **Step 7: Verify the file is gone from history**

```bash
git log --all --oneline -- backend/src/main/resources/firebase-service-account.json
```

Expected: empty output. If not empty, do not proceed — investigate.

- [ ] **Step 8: Verify the working tree no longer contains the file**

```bash
ls backend/src/main/resources/firebase-service-account.json 2>&1
```

Expected: `No such file or directory`.

---

## Prevent re-introduction

- [ ] **Step 9: Add `.gitignore` rules**

Append to `.gitignore` (create if missing):

```
# Firebase service account credentials — NEVER commit
firebase-service-account*.json
*-service-account*.json
backend/src/main/resources/firebase-service-account*.json
```

- [ ] **Step 10: Add a pre-commit hook that blocks committed private keys**

Create `.git/hooks/pre-commit` (this is local to the dev machine — for project-wide enforcement use a tool like `gitleaks` or `pre-commit` framework, captured in Plan A's tooling task):

```bash
#!/usr/bin/env bash
set -euo pipefail
if git diff --cached --name-only -z | xargs -0 -r grep -lE 'BEGIN (RSA |OPENSSH |EC |DSA |PGP |)PRIVATE KEY' 2>/dev/null; then
  echo "ERROR: attempting to commit a file containing a private key. Aborting." >&2
  exit 1
fi
if git diff --cached --name-only | grep -E '(^|/)firebase-service-account[^/]*\.json$|service-account\.json$' >/dev/null; then
  echo "ERROR: attempting to commit a Firebase/GCP service account file. Aborting." >&2
  exit 1
fi
exit 0
```

```bash
chmod +x .git/hooks/pre-commit
```

- [ ] **Step 11: Update CLAUDE.md credential path**

Edit `CLAUDE.md` — find the line `export GOOGLE_APPLICATION_CREDENTIALS=backend/src/main/resources/firebase-service-account.json` and replace with:

```
export GOOGLE_APPLICATION_CREDENTIALS=$HOME/.config/albunyaan/firebase-service-account.json
```

Add a note next to it:

```
# NEVER commit the service account file. It lives outside the repo.
```

- [ ] **Step 12: Verify the dev backend still starts with the relocated credential**

```bash
export GOOGLE_APPLICATION_CREDENTIALS=$HOME/.config/albunyaan/firebase-service-account.json
cd backend
./gradlew bootRun &
sleep 30
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/actuator/health
kill %1
cd ..
```

Expected: `200` from the health endpoint. If not, the backend lost access to the credential — check the env var and the file exists at the new path.

- [ ] **Step 13: Stage the .gitignore + CLAUDE.md changes**

```bash
git add .gitignore CLAUDE.md
git status
```

Expected: shows `.gitignore` and `CLAUDE.md` staged; no other files.

- [ ] **Step 14: Commit on the rewritten history**

```bash
git commit -m "[CHORE]: relocate Firebase service account; add SA gitignore + pre-commit guard"
```

---

## Force-push

- [ ] **Step 15: Show the user this list and confirm before force-push**

```bash
echo "Branches to force-push:"
cat /tmp/branches-before-scrub.txt | grep -v 'remotes/' | sed 's/^[* ]*//'
echo
echo "Tags that may be invalidated:"
cat /tmp/tags-before-scrub.txt
```

The user must confirm explicitly before Step 16 — force-push to `develop` is irreversible from the remote's point of view.

- [ ] **Step 16: Force-push develop with lease**

```bash
git push --force-with-lease origin develop
```

Expected: push succeeds. If it fails because lease is stale, the remote has changes since the rewrite — investigate before retrying.

- [ ] **Step 17: Force-push other local branches if any**

```bash
for branch in $(git branch --format='%(refname:short)' | grep -v '^develop$'); do
  echo "Pushing $branch"
  git push --force-with-lease origin "$branch" || true
done
```

- [ ] **Step 18: Re-push tags that were rewritten**

```bash
git push --force --tags origin
```

Tags that pointed at rewritten commits get new SHAs; re-pushing replaces the remote tag refs.

---

## Verification

- [ ] **Step 19: Fresh-clone and confirm the file is absent**

```bash
cd /tmp
git clone <repo-url> albunyaantube-verify
cd albunyaantube-verify
git log --all --oneline -- backend/src/main/resources/firebase-service-account.json
ls backend/src/main/resources/firebase-service-account.json 2>&1
cd /home/farouq/Development/albunyaantube
rm -rf /tmp/albunyaantube-verify
```

Expected: empty `git log` output; `ls` says `No such file or directory`.

- [ ] **Step 20: Delete the safety tarball after a sanity period**

After 7 days of confirmed working dev environment, delete `../albunyaantube-pre-scrub-*.tar.gz`. Until then, keep it in case anything was lost.

---

## Residual-risk reminder

The credential **was in git history** and has not been rotated. The risk this scrub does not address:
- Anyone with a clone before today still has the file on their disk.
- The credential itself is still valid in GCP.

The user explicitly accepted this risk because they are the sole holder of the codebase. If at any point another collaborator joins, or if the repo is ever made public, the SA must be rotated in GCP first.
