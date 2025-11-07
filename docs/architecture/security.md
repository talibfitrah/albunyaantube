# Threat Model & Security Controls

This document applies STRIDE analysis to Albunyaan Tube and enumerates required mitigations. Refer to [`../architecture/solution-architecture.md`](../architecture/solution-architecture.md#security-architecture) for structural context and [`../testing/test-strategy.md`](../testing/test-strategy.md#security-testing) for validation plans.

## Assets
- Allow-listed content registry (channels, playlists, videos).
- Admin accounts and JWT tokens.
- Audit logs and moderation decisions.
- Downloaded media (policy-restricted).
- Localization strings and halal policy metadata.

## STRIDE Analysis
| Threat | Surface | Impact | Mitigation |
| --- | --- | --- | --- |
| **Spoofing** | JWT authentication, admin login | Unauthorized access to moderation | Spring Security with Argon2id password hashing, JWT signing keys stored in HSM/Secrets Manager, refresh token rotation + device binding. |
| **Tampering** | Registry mutations, Flyway migrations | Corruption of allow-list | Role-based access (ADMIN/MODERATOR), validation via JSON Schemas, Flyway checksum enforcement, immutable audit trail with append-only table. |
| **Repudiation** | Moderation approvals/rejections | Disputed actions | Audit logging for all POST/PUT/DELETE, including IP/device metadata; logs retained 7 years. |
| **Information Disclosure** | Download URLs, user emails | Leakage of private links | Signed stream URLs expire within 5 minutes; downloads require policy flag `ENABLED`; TLS 1.2+, secrets rotated quarterly. |
| **Denial of Service** | API rate abuse, cache stampede | Service degradation | Redis-based sliding window rate limiting (per IP + account), circuit breakers for NewPipeExtractor, autoscaling thresholds, backpressure in Paging 3. |
| **Elevation of Privilege** | Privilege escalation via JWT | Gain ADMIN rights | JWT contains role claim validated against DB; refresh rotation invalidates previous token; Redis blacklist for revoked tokens; periodic permission audits (monthly). |

## Additional Controls
- **CSRF**: Admin SPA uses double-submit cookie for state-changing requests; backend validates tokens.
- **Input Validation**: Bean Validation + JSON Schema alignment; slug patterns enforced.
- **Transport Security**: Enforce HTTPS (HSTS), disable TLS <1.2.
- **Secrets Handling**: Use Vault/SM for DB creds, JWT secrets; never commit secrets.
- **Dependency Management**: OWASP Dependency-Check in CI; SLSA provenance targets.
- **Rate Limits**: Default 60 req/min per user for write endpoints; 600 req/min per IP for read.
- **Token Storage**: Admin SPA stores access token in memory, refresh token as HTTP-only secure cookie.
- **Audit Log Integrity**: Use write-only Postgres table with partitioning; daily export to immutable storage (S3 with Object Lock).
- **Download Policy Enforcement**: Backend verifies `downloadPolicy=ENABLED` before issuing download manifest; offline EULA acceptance recorded per device.
- **Download Policy Enforcement**: Backend verifies `downloadPolicy=ENABLED` before issuing download manifest; offline EULA acceptance recorded per device and the Android client now blocks queueing WorkManager requests until the user accepts the dialog.
- **Data Retention**: Prune audit logs >7 years; purge inactive downloads after 30 days.

## Incident Response
1. Detect via monitoring (Prometheus alerts, ELK).
2. Contain by revoking JWT signing keys (rotate) and disabling affected accounts.
3. Eradicate threat by patching vulnerability, running malware scans on servers.
4. Recover by redeploying clean images (immutable infrastructure).
5. Post-incident review logged in audit system and backlog.

## Compliance
- Align with Islamic content governance board decisions.
- GDPR considerations: store minimal personal data (admin emails); provide account deletion within 30 days.
- Offline media rights: enforce EULA acceptance, expose policy in-app (see [`../acceptance/criteria.md`](../acceptance/criteria.md#downloads-and-offline)).

## Traceability
Security requirements map to acceptance scenarios in [`../acceptance/criteria.md`](../acceptance/criteria.md#security) and test strategy coverage in [`../testing/test-strategy.md`](../testing/test-strategy.md#security-testing).
