# Azure AD App Registration for FitrahTube Mail

> **Plan F (ADMIN-USER-01).** One-time setup required before flipping
> `MAIL_ENABLED=true` in production.

## Prerequisites
- Microsoft 365 tenant with a real `noreply@fitrahtube.com` mailbox.
- Global Administrator role on the tenant (required for step 6 PowerShell scoping).
- Exchange Online PowerShell module installed locally.

## Step 1 — Create the app registration
1. Azure Portal → **Microsoft Entra ID** → **App registrations** → **New registration**.
2. Name: `FitrahTube Backend`.
3. Supported account types: **Accounts in this organizational directory only (single tenant)**.
4. Redirect URI: leave blank.
5. Click **Register**.

## Step 2 — Record IDs
On the new app's Overview page, copy:
- **Application (client) ID** → save as `AZURE_CLIENT_ID`.
- **Directory (tenant) ID** → save as `AZURE_TENANT_ID`.

## Step 3 — Create the client secret
1. **Certificates & secrets** → **Client secrets** → **New client secret**.
2. Description: `FitrahTube backend (rotates YYYY-MM-DD)`.
3. Expires: **24 months**.
4. Click **Add**.
5. **Copy the Value field immediately** — it never displays again.
6. Save as `AZURE_CLIENT_SECRET` in the deployment secret vault.

## Step 4 — Grant Mail.Send (Application permission)
1. **API permissions** → **Add a permission** → **Microsoft Graph** → **Application permissions**.
2. Search `Mail.Send`, check it, click **Add permissions**.
3. Click **Grant admin consent for <tenant>** (requires Global Admin).

## Step 5 — Restrict scope to the noreply mailbox (DEFERRED — Path A, 2026-05-12)

> **Status:** DEFERRED. The original Plan F spec marked this CRITICAL; we accepted
> running unscoped because connecting Exchange Online PowerShell from the only
> available host (Linux, unregistered device) is blocked by Microsoft Entra
> Conditional Access / Security Defaults (`AADSTS53003`).
>
> **What this means:** `Mail.Send` (Application permission) currently allows the
> backend to send mail as ANY mailbox in the tenant. Blast radius is bounded:
> single-admin tenant, ≤2 mailboxes total, no end-user mailboxes.
>
> **MUST DO BEFORE:** adding additional staff mailboxes, before onboarding any
> user-facing mailbox, before rotating the secret to a longer-lived one. Single
> PowerShell command, no code change.

When ready (from a registered Windows device or after CA exclusions are configured),
open Exchange Online PowerShell:

```powershell
Connect-ExchangeOnline

New-DistributionGroup -Name "FitrahTubeAppSenders" `
    -Members "noreply@fitrahtube.com"

New-ApplicationAccessPolicy `
    -AppId <AZURE_CLIENT_ID> `
    -PolicyScopeGroupId FitrahTubeAppSenders `
    -AccessRight RestrictAccess `
    -Description "FitrahTube Backend may only send from noreply mailbox"
```

## Step 6 — Verify the policy (after Step 5 is eventually applied)
```powershell
Test-ApplicationAccessPolicy -AppId <AZURE_CLIENT_ID> -Identity noreply@fitrahtube.com
# AccessCheckResult: Granted

Test-ApplicationAccessPolicy -AppId <AZURE_CLIENT_ID> -Identity some-other@fitrahtube.com
# AccessCheckResult: Denied
```

## Step 7 — Populate environment variables on the prod VM
| Variable | Value |
|---|---|
| `AZURE_TENANT_ID` | from step 2 |
| `AZURE_CLIENT_ID` | from step 2 |
| `AZURE_CLIENT_SECRET` | from step 3 |
| `MAIL_FROM_ADDRESS` | `noreply@fitrahtube.com` |
| `MAIL_FROM_DISPLAY_NAME` | `FitrahTube` |
| `MAIL_ENABLED` | `true` |

## Step 8 — Restart the backend
The backend runs a startup health check that calls `graph.users(fromAddress).get()`.
If the mailbox isn't reachable (wrong tenant, wrong scope, expired secret),
the backend refuses to start with a clear error. Investigate before retrying.

## Step 9 — Smoke test
From the admin UI, pick a throwaway test account and click **Reset password**.
The email should arrive within ~30 seconds. Inspect the `From:` header:

```
FitrahTube <noreply@fitrahtube.com>
```

If you see anything else, re-check step 5 (the scoping policy).
