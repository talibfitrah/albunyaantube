#!/usr/bin/env python3
"""Fail if any index declared in firestore.indexes.json is not live in Firestore.

Nothing deploys that file — no gradle task, no CI step. On 2026-08-19 all six
declared COLLECTION_GROUP indexes were missing from production, which silently
broke ImportGraduationService's approval fan-out: its two-equality
collection-group query returned FAILED_PRECONDITION, the service swallowed it as
best-effort, and every importer's content stayed AWAITING forever.

Usage:
    python3 scripts/check-firestore-indexes.py

Credentials: GOOGLE_APPLICATION_CREDENTIALS, or
~/.config/albunyaan/firebase-service-account.json. Read-only (indexes.list).
Requires PyJWT + cryptography.

Comparison is deliberately loose: Firestore reorders an index's fields when it
stores one (equality fields first) and appends __name__, so an exact sequence
match would report false drift. Indexes are compared as {collectionGroup,
queryScope, set of (fieldPath, direction)} with __name__ dropped. The blind spot
is two indexes over the same fields in a different order; the failure this
catches — declared but never deployed — is the one that actually happened.
"""
import json
import os
import pathlib
import sys
import time
import urllib.parse
import urllib.request

import jwt

DECLARED = (pathlib.Path(__file__).resolve().parent.parent
            / "backend/src/main/resources/firestore.indexes.json")


def credentials():
    # GOOGLE_APPLICATION_CREDENTIALS is often exported pointing at a path that
    # does not exist on this machine, so treat it as a preference, not a promise.
    candidates = [os.environ.get("GOOGLE_APPLICATION_CREDENTIALS"),
                  os.path.expanduser("~/.config/albunyaan/firebase-service-account.json")]
    for path in candidates:
        if path and os.path.exists(path):
            return json.load(open(path))
    sys.exit("No service account found. Set GOOGLE_APPLICATION_CREDENTIALS to a "
             "readable key, or place one at ~/.config/albunyaan/firebase-service-account.json.")


def access_token(sa):
    now = int(time.time())
    assertion = jwt.encode({
        "iss": sa["client_email"],
        "scope": "https://www.googleapis.com/auth/datastore",
        "aud": "https://oauth2.googleapis.com/token",
        "iat": now, "exp": now + 3600,
    }, sa["private_key"], algorithm="RS256")
    req = urllib.request.Request(
        "https://oauth2.googleapis.com/token",
        data=urllib.parse.urlencode({
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion": assertion}).encode(),
        headers={"Content-Type": "application/x-www-form-urlencoded"})
    return json.load(urllib.request.urlopen(req))["access_token"]


def key(collection_group, index):
    """Order-insensitive identity for one index. See module docstring."""
    fields = frozenset(
        (f["fieldPath"], f.get("order") or f.get("arrayConfig"))
        for f in index.get("fields", []) if f["fieldPath"] != "__name__")
    return (collection_group, index.get("queryScope", "COLLECTION"), fields)


def live_indexes(project, token):
    """Every composite index in the project, following pagination.

    The endpoint is nominally per-collection-group but returns the whole
    project, so the group in the path is a placeholder.
    """
    base = (f"https://firestore.googleapis.com/v1/projects/{project}"
            f"/databases/(default)/collectionGroups/_/indexes")
    out, page = {}, None
    while True:
        url = base + (f"?pageToken={urllib.parse.quote(page)}" if page else "")
        req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
        body = json.load(urllib.request.urlopen(req))
        for index in body.get("indexes", []):
            # name: projects/P/databases/D/collectionGroups/<cg>/indexes/<id>
            cg = index["name"].split("/collectionGroups/")[1].split("/indexes/")[0]
            out[key(cg, index)] = index.get("state", "UNKNOWN")
        # Without this the first unlisted index reads as MISSING once the project
        # outgrows one page.
        page = body.get("nextPageToken")
        if not page:
            return out


def main():
    declared = json.loads(DECLARED.read_text())["indexes"]
    sa = credentials()
    token = access_token(sa)
    live = live_indexes(sa["project_id"], token)

    missing, building, checked = [], [], 0
    for index in declared:
        # Firestore never materialises a composite index over a single field: the
        # automatic per-field index already serves it (with __name__ in the same
        # direction). Such a declaration is a no-op, not drift.
        if len([f for f in index["fields"] if f["fieldPath"] != "__name__"]) < 2:
            continue
        checked += 1
        state = live.get(key(index["collectionGroup"], index))
        if state is None:
            missing.append(index)
        elif state != "READY":
            building.append((index, state))

    for index, state in building:
        fields = ", ".join(f["fieldPath"] for f in index["fields"])
        print(f"BUILDING  {index['collectionGroup']} [{fields}] — {state}")
    for index in missing:
        fields = ", ".join(f["fieldPath"] for f in index["fields"])
        print(f"MISSING   {index['collectionGroup']} ({index.get('queryScope','COLLECTION')}) [{fields}]")

    print(f"\n{checked} of {len(declared)} declared indexes checked "
          f"(single-field ones are served automatically), "
          f"{len(missing)} missing, {len(building)} still building.")
    if building and not missing:
        print("\nIndexes still building serve FAILED_PRECONDITION until they are READY. "
              "Re-run once the build finishes.")
    if missing or building:
        print("\nDeclared indexes are not live. Queries needing them fail with "
              "FAILED_PRECONDITION, which callers may swallow silently. Create them "
              "in the Firebase console, or deploy with the Firebase CLI (review its "
              "delete prompt — it offers to remove live indexes absent from the file).")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
