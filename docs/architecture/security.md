# Threat Model & Security Controls

This document applies STRIDE analysis to Albunyaan Tube and enumerates security controls for the MVP release. Refer to [`overview.md`](overview.md#security-mvp-scope) for architectural context and [`../status/TESTING_GUIDE.md`](../status/TESTING_GUIDE.md) for security testing procedures.

---

## Security Posture: MVP vs. Future

### MVP Scope (Current Implementation)
The MVP focuses on **foundational security** with minimal complexity:
- Firebase Authentication for admin dashboard (email/password)
- Role-based access control (ADMIN, MODERATOR) via Firebase custom claims
- HTTPS/TLS for production (localhost HTTP acceptable for dev)
- Mobile app: public access only, no user authentication
- Downloaded videos: app-private storage with Android OS-level encryption
- Basic input validation (reject empty/null required fields)

### Deferred to v1.1+ (Out of Scope for MVP)
Advanced security features are explicitly deferred per PRD section "Security & Privacy":
- Advanced rate limiting (abuse protection)
- Token refresh rotation
- Account lockout after failed login attempts
- CSRF protection (double-submit cookies)
- Comprehensive JSON schema validation
- Firestore security rules hardening
- Audit log immutable storage
- Signed download URLs
- HSTS headers
- Argon2id password hashing (Firebase default bcrypt is sufficient for MVP)

**Rationale**: MVP prioritizes rapid launch with core functionality. Advanced security hardens the platform post-launch based on real-world threat intelligence.

---

## Assets

### Critical Assets (High Value)
1. **Approved Content Registry** - Channels, playlists, videos with approval metadata
2. **Admin Accounts** - Firebase Authentication credentials (email/password)
3. **Firebase Custom Claims** - ADMIN and MODERATOR role assignments
4. **Firebase Service Account JSON** - Backend authentication to Firestore

### Important Assets (Medium Value)
5. **Audit Logs** - Immutable record of all approval actions
6. **Category Hierarchy** - Hierarchical category structure with translations
7. **Exclusion Lists** - Excluded videos/playlists within approved channels
8. **Download Token Secret** - Backend secret for generating download tokens

### Low-Risk Assets
9. **Downloaded Media** - App-private storage, user-specific
10. **User Preferences** - Locale, safe mode settings (non-sensitive)

---

## STRIDE Threat Analysis

| Threat Category | Attack Surface | Impact | MVP Mitigation | Deferred Mitigation |
|----------------|---------------|--------|---------------|---------------------|
| **Spoofing** | Admin login, Firebase tokens | Unauthorized access to moderation | Firebase Authentication (bcrypt), JWT validation via Firebase Admin SDK | Token refresh rotation, device binding, Argon2id |
| **Tampering** | Firestore documents, approval flags | Corruption of allow-list, unapproved content visible | Firebase custom claims for RBAC, Spring Security `@PreAuthorize`, basic input validation | JSON Schema validation, Firestore security rules, immutable audit logs |
| **Repudiation** | Approval/rejection actions | Disputed moderation decisions | Audit logs record all actions (actorUid, timestamp, entityId) | Append-only audit table with retention policy, immutable storage (S3 Object Lock) |
| **Information Disclosure** | Firebase service account, download token secret | Unauthorized Firestore access, token forgery | Service account JSON not committed to git, secrets in environment variables | Secrets Manager (AWS/GCP), quarterly secret rotation, least-privilege IAM roles |
| **Denial of Service** | API rate abuse, cache stampede | Service degradation, backend unavailable | Basic Spring Boot rate limiting, cache stampede protection (locking) | Redis sliding window rate limiter (60 req/min per user), circuit breakers, autoscaling |
| **Elevation of Privilege** | JWT role claim manipulation | Gain ADMIN rights without authorization | JWT validated against Firebase (immutable), custom claims checked on every request | Redis token blacklist for revocation, periodic permission audits (monthly) |

---

## Security Controls (MVP Implementation)

### 1. Authentication & Authorization

#### Admin Dashboard
- **Authentication**: Firebase Authentication (email/password)
  - Passwords hashed by Firebase (bcrypt with salt, 10 rounds)
  - No custom password storage in backend
- **Authorization**: Firebase custom claims (`role: "ADMIN" | "MODERATOR"`)
  - Claims set during user creation via Firebase Admin SDK
  - Validated on every API request via Spring Security
- **Session Management**: Firebase ID tokens (JWT) with 1-hour expiration
  - Tokens sent in `Authorization: Bearer <token>` header
  - Backend validates signature using Firebase Admin SDK
- **Role Enforcement**: Spring Security `@PreAuthorize("hasRole('ADMIN')")` annotations
  - Example: Only ADMIN can approve content, MODERATOR can only submit for review

#### Mobile App
- **No Authentication**: Public content access only
- **No User Accounts**: No login, no profiles, no personal data collection
- **Privacy**: NewPipeExtractor used instead of official YouTube API (no tracking)

### 2. Transport Security

#### Production (HTTPS/TLS)
- **Requirement**: All connections over HTTPS (TLS 1.2+)
- **Implementation**: Nginx reverse proxy with Let's Encrypt certificates
- **HSTS Headers**: Deferred to v1.1 (not in MVP scope)

#### Development (HTTP Localhost)
- **Acceptable**: Localhost HTTP for local development
  - Backend: `http://localhost:8080`
  - Frontend: `http://localhost:5173`
  - Android Emulator: `http://10.0.2.2:8080`

### 3. Data Protection

#### At Rest
- **Firestore Encryption**: Automatic encryption at rest (Google Cloud Platform default)
- **Downloaded Videos**: Android app-private storage with OS-level encryption (Android 10+)
- **Secrets Management**:
  - Firebase service account JSON: `backend/src/main/resources/firebase-service-account.json` (not committed to git)
  - Download token secret: Environment variable `DOWNLOAD_TOKEN_SECRET_KEY`
  - **Note**: No YouTube API key required (using NewPipeExtractor)

#### In Transit
- **HTTPS/TLS**: All API calls encrypted in production
- **JWT Tokens**: Signed by Firebase, validated by backend on every request

### 4. Input Validation (Basic)

#### MVP Scope
- **Required Fields**: Reject null/empty values for required fields (category name, YouTube IDs)
- **Type Checking**: Spring Boot `@Valid` annotations on request DTOs
- **String Length**: Basic limits (category name ≤ 100 chars, YouTube ID = 11 chars)

#### Deferred to v1.1+
- **Comprehensive JSON Schema Validation**: OpenAPI-driven schema validation
- **XSS Prevention**: Sanitize user-provided text (category names, exclusion reasons)
- **SQL Injection**: Not applicable (Firestore NoSQL, no raw queries)

### 5. Audit Logging

#### MVP Implementation
- **Collection**: `audit_logs` in Firestore
- **Fields**: `action`, `actorUid`, `entityType`, `entityId`, `metadata`, `timestamp`
- **Actions Logged**: All approval/rejection actions, category assignments, user creation
- **Retention**: No automatic deletion (manual cleanup required)

#### Deferred to v1.1+
- **Immutable Storage**: Append-only table with partitioning
- **Long-Term Retention**: Export to S3 with Object Lock (7-year retention per compliance)
- **Audit Queries**: Indexed queries for actor, action, date range

### 6. Rate Limiting (Basic)

#### MVP Scope
- **Spring Boot Default**: No explicit rate limiting (relies on Firestore quotas)
- **Cache Stampede Protection**: Locking mechanism in Spring Cache for YouTube API calls

#### Deferred to v1.1+
- **Redis Sliding Window**: 60 req/min per user for write endpoints, 600 req/min per IP for read
- **IP-Based Throttling**: Block abusive IPs after 1000 requests/minute
- **Account Lockout**: Disable account after 10 failed login attempts in 15 minutes

### 7. CORS (Cross-Origin Resource Sharing)

#### Configuration (`application.yml`)
```yaml
app:
  security:
    cors:
      allowed-origins: "http://localhost:5173,http://127.0.0.1:5173"
```

- **Allowed Origins**: Admin dashboard only (Vue dev server)
- **Production**: Update to `https://admin.albunyaan.tube`
- **Mobile App**: Not subject to CORS (native app, not browser)

### 8. Download Policy Enforcement

#### MVP Scope
- **Backend Check**: `DownloadService` verifies video is approved before issuing download token
- **Token Expiration**: Download tokens expire after 24 hours
- **Storage Management**: Android app shows available device storage; Android OS manages low storage warnings
- **EULA Acceptance**: Android app requires user acceptance before first download (DataStore flag)
- **Note**: No artificial storage quota - user's device storage is the natural limit

#### Deferred to v1.1+
- **Signed URLs**: Pre-signed URLs with expiration (e.g., AWS S3 presigned URLs)
- **Rate Limiting**: Max concurrent downloads per device (e.g., 10) to prevent bandwidth abuse

---

## Threat Scenarios & Mitigations

### Scenario 1: Unauthorized Content Approval
**Threat**: Attacker gains ADMIN role, approves inappropriate content

**MVP Mitigation**:
- Firebase custom claims validated on every request
- Audit logs record all approval actions with `actorUid` (traceable)
- Manual review of audit logs by super-admin

**Deferred**: Periodic permission audits (monthly), automated alerts for suspicious activity

### Scenario 2: Firebase Service Account Leakage
**Threat**: Service account JSON committed to git, exposed in GitHub repo

**MVP Mitigation**:
- Service account JSON path in environment variable (`FIREBASE_SERVICE_ACCOUNT_PATH`), file not committed
- `.gitignore` excludes `firebase-service-account.json`, `.env` files
- GitHub secret scanning alerts (enabled by default for public repos)
- **Note**: No YouTube API key exists (NewPipeExtractor is API-free)

**Deferred**: Secrets Manager (AWS Secrets Manager or GCP Secret Manager), quarterly secret rotation

### Scenario 3: JWT Token Theft
**Threat**: Attacker intercepts JWT token, impersonates admin

**MVP Mitigation**:
- Tokens transmitted over HTTPS in production
- Token expiration (1 hour), requires re-authentication after expiry
- Firebase validates token signature on every request

**Deferred**: Token refresh rotation (invalidate previous refresh token), Redis blacklist for revoked tokens

### Scenario 4: Firestore Document Manipulation
**Threat**: Attacker directly modifies Firestore documents (bypassing backend)

**MVP Mitigation**:
- Firebase Authentication required for Firestore access (SDK validates user)
- Custom claims checked by backend before document updates
- Audit logs track all changes

**Deferred**: Firestore Security Rules hardening (deny direct client access, enforce schema)

### Scenario 5: Download Bandwidth Abuse
**Threat**: User downloads excessive content, impacting backend bandwidth

**MVP Mitigation**:
- Device storage is natural limit (Android OS manages low storage)
- 30-day expiry automatically cleans up old downloads
- Estimated file sizes shown before download (user self-regulation)

**Deferred**: Server-side rate limiting (max 10 concurrent downloads per device) if abuse detected

---

## Compliance & Privacy

### GDPR Considerations (Future)
- **Minimal Data Collection**: Admin emails only (no end-user data)
- **Right to Deletion**: Account deletion within 30 days (manual process in MVP)
- **Data Export**: Admin can export own audit logs via backend API

### Islamic Content Governance
- **Human Moderation**: All content manually reviewed by admins (no AI/ML moderation)
- **Exclusion Rationale**: Reasons tracked (Contains Music, Theological Issue, Duplicate, Other)
- **Audit Trail**: Immutable record of all moderation decisions

### Offline Media Rights
- **EULA Requirement**: Android app requires acceptance before first download
- **No DRM**: Videos downloaded as plain files (no encryption, no expiration enforcement beyond 30 days)
- **Policy Compliance**: Backend checks `approved=true` before issuing download token

---

## Incident Response Plan (MVP)

### Detection
1. **Manual Monitoring**: Admin reviews audit logs daily
2. **User Reports**: Email support for inappropriate content reports
3. **Firebase Console**: Monitor authentication anomalies (unusual login locations)

### Containment
1. **Revoke Firebase Tokens**: Rotate JWT signing keys in Firebase Console (invalidates all tokens)
2. **Disable Account**: Set `role` custom claim to `null` or delete user in Firebase
3. **Remove Content**: Backend API to bulk-delete unapproved content

### Eradication
1. **Patch Vulnerability**: Deploy backend hotfix via VPS SSH
2. **Rotate Secrets**: Rotate Firebase service account credentials, update `DOWNLOAD_TOKEN_SECRET_KEY` if compromised

### Recovery
1. **Redeploy Backend**: `./gradlew bootJar && sudo systemctl restart albunyaan-backend`
2. **Verify Integrity**: Check audit logs for unauthorized actions, restore from Firestore backup if needed

### Post-Incident
1. **Document in Audit Log**: Record incident details, resolution steps
2. **Update Security Controls**: Add to backlog for v1.1 hardening

**Deferred to v1.1+**: Automated detection (Prometheus alerts), runbook automation, disaster recovery procedures

---

## Security Testing

### MVP Testing Scope
- **Manual Penetration Testing**: Admin attempts to bypass RBAC (e.g., MODERATOR tries to approve content)
- **Authentication Testing**: Verify Firebase token validation rejects invalid tokens
- **Input Validation Testing**: Submit invalid YouTube IDs, empty category names
- **Audit Log Verification**: Confirm all actions logged with correct `actorUid` and `timestamp`

### Deferred to v1.1+
- **Automated Security Scans**: OWASP Dependency-Check in CI/CD
- **Fuzzing**: Random input generation for API endpoints
- **Load Testing**: Verify rate limiting under 200 RPS sustained load

See [../status/TESTING_GUIDE.md](../status/TESTING_GUIDE.md#security-testing) for detailed test procedures.

---

## Known Security Limitations (MVP)

1. **No CSRF Protection**: Admin dashboard vulnerable to cross-site request forgery (low risk, requires social engineering)
2. **No Rate Limiting**: Backend relies on Firestore quotas, no explicit rate limits
3. **No Account Lockout**: Unlimited login attempts (brute-force risk mitigated by Firebase's built-in protections)
4. **Basic Input Validation**: No comprehensive JSON schema validation, XSS risk in admin UI
5. **Firestore Direct Access**: Client SDK can access Firestore directly (mitigated by custom claims, but not fully hardened)
6. **No Audit Log Immutability**: Audit logs can be deleted by ADMIN users (no append-only enforcement)

**Mitigation Strategy**: MVP accepts these risks for rapid launch. v1.1 hardens platform based on real-world threat intelligence and user feedback.

---

## Security Roadmap

### v1.1 (Months 4-6)
- [ ] Redis sliding window rate limiter (60 req/min per user)
- [ ] CSRF protection (double-submit cookie)
- [ ] Account lockout after 10 failed login attempts
- [ ] Firestore Security Rules hardening (deny direct client access)
- [ ] Secrets Manager integration (AWS/GCP)
- [ ] Audit log immutable storage (S3 with Object Lock)

### v1.2+ (Future)
- [ ] HSTS headers + TLS 1.3
- [ ] JSON Schema validation for all API requests
- [ ] Token refresh rotation
- [ ] Automated security scans in CI/CD (OWASP Dependency-Check, Snyk)
- [ ] Bug bounty program
- [ ] Third-party security audit (penetration testing firm)

---

## Traceability

- **PRD**: [../PRD.md](../PRD.md#security--privacy) - Security requirements
- **Architecture**: [overview.md](overview.md#security-mvp-scope) - Security architecture
- **Testing**: [../status/TESTING_GUIDE.md](../status/TESTING_GUIDE.md#security-testing) - Test procedures
- **Deployment**: [../status/DEPLOYMENT_GUIDE.md](../status/DEPLOYMENT_GUIDE.md#security-hardening) - Production security checklist

---

**Last Updated**: November 10, 2025
**Status**: MVP security controls implemented, advanced features deferred to v1.1+
