# Azure Client Secret Rotation

> **Plan F (ADMIN-USER-01)** risk §11.2 — Azure client secrets expire after at
> most 24 months. Rotate **at least 30 days before expiry** to avoid silent
> password-reset failures.

## When to rotate
- Schedule a calendar reminder for the 1st of the month, 30 days before the
  recorded expiry date.
- If the daily `mail.startup-check` ever logs token-acquisition errors, treat
  it as an emergency rotation and follow this procedure now.

## Zero-downtime rotation (recommended)

1. **Create the new secret in Azure Portal** (do not delete the old one yet):
   - **Certificates & secrets** → **New client secret** → 24-month expiry.
   - Description: `FitrahTube backend (rotates YYYY-MM-DD)`.
   - Copy the **Value** field immediately.

2. **Update the deployment vault**:
   - Add the new value alongside the old one (`AZURE_CLIENT_SECRET_NEW`).

3. **Deploy with the new secret**:
   - SSH to the prod VM.
   - `export AZURE_CLIENT_SECRET=<new value>` (or update the env-file).
   - Restart the backend. Watch logs for `mail.startup-check.ok`.

4. **Verify**:
   - Trigger a password reset on a throwaway account; confirm email arrives.

5. **Delete the old secret in Azure**:
   - Only after step 4 confirms the new secret works.
   - **Certificates & secrets** → click the trash can next to the old entry.

6. **Update CALENDAR + this doc** with the next rotation date.

## Emergency rotation (secret compromised)

1. Immediately delete the compromised secret in Azure Portal.
2. Create a new secret following the standard procedure (steps 1–4 above).
3. Audit `audit_logs` for `USER_PASSWORD_RESET_EMAIL_FAILED` entries in the
   compromise window — those may be the attacker's reconnaissance.

## Verification commands

```bash
# Confirm backend is using the new secret
ssh prod 'sudo journalctl -u fitrahtube-backend | grep mail.startup-check.ok | tail -1'

# Trigger a Graph call manually to test token acquisition
ssh prod 'curl -s http://localhost:8080/actuator/health | jq .components.mail'
```

## Rotation log

| Rotation date | Old secret expiry | New secret expiry | Operator |
|---|---|---|---|
| YYYY-MM-DD | YYYY-MM-DD | YYYY-MM-DD | name |
