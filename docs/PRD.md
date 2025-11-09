# Product Requirements Document: Albunyaan Tube

## Overview & Goals

Albunyaan Tube is an ad-free, admin-curated YouTube client delivering safe
Islamic content to Muslim families and students of knowledge through native
mobile apps and a web-based moderation dashboard.

**Vision**: Become the trusted global platform for halal YouTube content where
every Muslim can confidently access safe Islamic content without compromising
values or exposure to inappropriate material.

**Key Outcomes**:
- Launch with 500+ curated videos across 20 Islamic categories
- Achieve zero inappropriate content incidents through human moderation
- Support 3 languages (English, Arabic RTL, Dutch) from launch
- Deliver API p95 latency < 200ms and mobile cold start < 2.5s
- Reach 1,000 monthly active users within 6 months of launch

---

## Problem Statement

Muslim families seeking Islamic content on YouTube face critical challenges:

1. **Content Safety**: Algorithm surfaces inappropriate suggestions alongside
   halal videos; autoplay leads to unsafe content
2. **Advertising**: Ads often contain haram elements (music, immodest imagery,
   gambling promotions)
3. **Curation Burden**: Parents must manually verify every video; no way to
   "lock" YouTube to specific channels
4. **Platform Distractions**: Infinite scroll and autoplay encourage excessive
   screen time without parental controls

Result: Families cannot confidently allow unsupervised access, limiting
children's ability to learn Islam independently.

---

## Target Users & Personas

**Persona 1: Aisha (Parent, Age 35)**
- Mother of 3 children (ages 6-14), works part-time
- Goals: Find safe Islamic bedtime stories; ensure children aren't exposed to
  inappropriate ads or recommendations
- Pain: Must supervise every YouTube session; worried about suggested videos
- Quote: "I want my children to learn Islam without worrying what plays next"

**Persona 2: Ahmed (Student, Age 24)**
- University student studying online with scholars
- Goals: Build structured playlists for Aqeedah/Tafsir; download lectures for
  offline commute listening
- Pain: YouTube's algorithm suggests unvetted content; must verify every
  channel's credibility
- Quote: "I need a platform where every scholar has been vetted and content is
  organized properly"

**Persona 3: Fatima (Moderator, Age 42)**
- Islamic studies graduate, former teacher, volunteers for Muslim organizations
- Goals: Review pending submissions efficiently; exclude inappropriate videos
  from otherwise good channels
- Pain: Time-consuming manual verification; difficult to track re-review needs
- Quote: "I want tools that help me moderate faster without compromising
  Islamic integrity"

---

## In Scope

**Content Curation**:
- Admin search of YouTube via NewpipeExtractor (https://github.com/TeamNewPipe/NewPipeExtractor)
- Three-stage approval workflow: submission → pending → approved/rejected
- Category assignment (hierarchical: parent/child structure, max 2 levels)
- Granular exclusions (specific videos/playlists within approved channels and specific videos within approved playlists)
- Audit trail of all moderation decisions with actor, timestamp, reason

**Mobile Experience (Android)**:
- Browse approved content by category, channel, playlist, video
- Video player with quality selection (144p-1080p), audio-only mode, captions
- Offline downloads with storage quota (500MB default), 30-day expiry
- Search across approved content
- Multi-language support: English, Arabic (RTL), Dutch

**Admin Dashboard (Web)**:
- YouTube content search and preview
- Pending approvals queue with inline category assignment
- Content library management (approved items)
- Exclusions editor
- User management (create/assign admin/moderator roles)
- Metrics dashboard (pending queue depth, content totals, moderator activity)
- Audit log with filtering by actor, action, date range

**Technical Requirements**:
- Role-based access control (ADMIN, MODERATOR roles)
- NoSQL document database with composite indexes
- Response caching (5-minute TTL) with cache stampede protection
- Cursor-based pagination for infinite scroll
- Internationalization with ICU MessageFormat plurals

---

## Out of Scope

**For MVP**:
- iOS application (Android-only for launch)
- User accounts for mobile viewers (public access, no login)
- Community content suggestions from end-users
- Video uploads (YouTube-only sourcing)
- Premium features (playlists, bookmarks, watch history)
- Live streaming support
- Automated moderation via ML/AI
- Social features (comments, likes, shares within app)
- Cloud storage integration (app-private storage only)

**Future Consideration**:
- Volunteer moderator program
- Advanced analytics (watch time, completion rates)
- Content recommendations engine
- Multi-device sync
- Parental controls and profiles

---

## User Stories & Acceptance Criteria

### Story 1: Admin Searches and Adds Channel for Approval
**As an** administrator
**I want to** search YouTube and submit channels for approval
**So that** moderators can review and publish safe Islamic content

**Acceptance Criteria**:
1. Search returns blended results (channels/playlists/videos) within 2s
2. Results cached for 1 hour; subsequent searches hit cache
3. "Add for Approval" opens category assignment modal requiring ≥1 category
4. Submission creates document with status="pending", submittedBy=actor,
   submittedAt=timestamp
5. Item appears in moderator's pending queue immediately after submission
6. Audit log records submission with actor UID, entity ID, timestamp

### Story 2: Moderator Approves Channel with Exclusions
**As a** moderator
**I want to** review pending channels and exclude specific videos
**So that** channels with mostly good content can be approved despite isolated
inappropriate items

**Acceptance Criteria**:
1. Pending queue displays items sorted oldest-first (FIFO) with filters for
   type, category, date
2. Channel detail modal shows recent 50 videos with exclude checkboxes
3. Exclusion requires reason from dropdown (Contains Music, Theological Issue,
   Duplicate, Other)
4. Approve action updates status="approved", approvedBy=moderator UID,
   approvedAt=timestamp
5. Excluded items stored in excludedItems array; hidden from mobile API
6. Audit log records approval decision with categories, exclusions, optional
   note
7. Approved channel visible in mobile app within 60 seconds (cache refresh)

### Story 3: Mobile User Watches Video Safely
**As a** Muslim parent
**I want to** browse and play approved videos without ads or unsafe suggestions
**So that** my children can watch Islamic content unsupervised

**Acceptance Criteria**:
1. Home feed returns 3 latest items per category within 3s on cold start
2. Category filter persists across tabs during session
3. Video tap loads player screen; playback starts within 1.2s (p50)
4. Player has no autoplay to next video; replay button shown on completion
5. Zero advertisements during playback or in browse UI
6. Quality selector displays all available qualities (144p-1080p), sorted low
   to high
7. Audio-only toggle switches stream without restarting playback

### Story 4: Student Downloads Playlist Offline
**As a** student of knowledge
**I want to** download entire playlists for offline study
**So that** I can listen during commute without mobile data

**Acceptance Criteria**:
1. Playlist detail shows "Download" button; tap opens EULA modal
2. EULA acceptance required; rejection blocks download with explanation
3. Storage check warns if quota exceeded; prompts deletion of old downloads
4. WorkManager enqueues download with foreground notification showing progress
5. Notification shows "Downloading 1/30" with cancel action
6. Downloads tab displays aggregated progress (30% across playlist)
7. Completed downloads expire after 30 days; user notified 7 days prior
8. Offline playback works without network; maintains watch position

### Story 5: Admin Views Dashboard Metrics
**As an** administrator
**I want to** see real-time platform metrics
**So that** I can monitor content growth and moderation queue health

**Acceptance Criteria**:
1. Dashboard displays: total categories, approved channels/playlists/videos,
   pending approvals count, active moderators
2. Data refreshes every 60 seconds with cached results (5-minute TTL)
3. Comparison to previous timeframe shows trend arrows (+12% channels, +8%
   videos)
4. Stale data (>15 minutes old) triggers warning toast
5. Only ADMIN and MODERATOR roles can access; 403 error for others
6. Response includes traceId header for debugging
7. Dashboard loads within 2s on desktop Chrome

---

## Key Flows

### Flow 1: First-Time Mobile User
1. User launches app; splash screen displays ≥1.5s during data load
2. Onboarding carousel presents 3 pages: Welcome, Features, Language Selection
3. User selects locale (en/ar/nl); choice persists in app-private storage
4. Main shell loads with Home tab active; categories populated from backend
5. User taps "Quran Recitation" category chip
6. Filtered video grid displays approved content in that category
7. User taps video thumbnail
8. Player screen loads in fullscreen; video starts within 1.2s
9. User watches to completion; replay button appears (no autoplay)
10. Back navigation returns to filtered grid; filter state preserved

### Flow 2: Admin Approves New Channel
1. Admin logs into dashboard with email/password (Firebase Auth)
2. Navigates to Content Search; enters "Islamic lecture" in search box
3. Search returns 20 YouTube results (channels, playlists, videos) within 2s
4. Admin filters by "Channels only"; finds channel with 100K subscribers
5. Clicks "Preview" to open YouTube in new tab; verifies channel authenticity
6. Returns to dashboard; clicks "Add for Approval"
7. Category assignment modal opens; admin selects "Hadith" and "Seerah"
8. Adds submission note: "Recommended by community"
9. Clicks Submit; confirmation toast appears
10. Channel appears in Pending Approvals queue with status="pending"
11. Moderator (different user) reviews channel in pending queue
12. Opens channel detail modal; views recent 50 videos
13. Identifies 2 videos with background music; checks exclude boxes
14. Adds exclusion reason: "Contains Music"
15. Clicks "Approve Channel"
16. Channel status updates to "approved"; excluded videos stored in array
17. Audit log records approval with moderator UID, categories, exclusions
18. Mobile app receives approved channel in next API fetch (<60s)

### Flow 3: Moderator Reviews Pending Queue
1. Moderator logs into dashboard
2. Navigates to Pending Approvals; sees 5 pending items sorted oldest-first
3. Applies filter: "Type=Channel", "Category=Quran Recitation"
4. Queue filters to 2 matching channels
5. Clicks first channel row to open detail modal
6. Reviews Info tab: channel description, subscriber count, total videos
7. Switches to Videos tab; scans thumbnails for inappropriate content
8. Finds 1 video with music; checks "Exclude" checkbox
9. Exclusion dropdown appears; selects "Contains Music", adds note
10. Returns to Info tab; clicks "Approve"
11. Confirmation modal asks "Approve channel with 1 exclusion?"
12. Moderator confirms; success toast appears
13. Queue updates to show 1 pending item remaining
14. Audit log entry created with timestamp, actor, entity, decision

---

## Non-Functional Requirements

### Performance
**Backend API**:
- p95 latency < 200ms for list endpoints at 200 RPS sustained load
- Payload size ≤ 80KB per page (limit=20 items)
- Cache hit ratio ≥ 85% for list queries with 5-minute TTL
- Database queries < 10ms for indexed queries

**Mobile (Android)**:
- Cold start < 2.5s on Pixel 4a (mid-range device, API 31)
- Frame time ≤ 11ms for 90% of frames during endless scroll
- Video playback start p50 < 1.2s from tap to first frame
- Memory usage average < 150MB, peak < 250MB

**Admin Dashboard (Web)**:
- Time to Interactive < 3s on desktop Chrome
- First Contentful Paint < 1.5s
- Bundle size < 500KB gzipped (initial load)

### Security & Privacy
**Authentication**:
- JWT-based authentication with custom claims for role enforcement
- Token expiry: 15 minutes (access), refresh tokens rotate on use
- Rate limiting: 5 failed login attempts per 15 minutes trigger lockout

**Authorization**:
- Role-based access control: ADMIN (full access), MODERATOR (approve/reject
  only, cannot manage users)
- Method-level enforcement via security annotations
- Firestore security rules mirror backend role checks

**Data Protection**:
- TLS 1.2+ for all connections; HSTS enabled
- Downloaded videos stored in app-private directory with OS-level encryption
- No personal data collected from mobile users (public access, no accounts)
- Admin passwords hashed with Argon2id
- Audit logs retained 7 years in immutable storage

**API Security**:
- Rate limiting: 60 req/min (write), 600 req/min (read) per IP
- CSRF protection via double-submit cookie pattern
- Input validation: JSON schema validation for all POST/PUT payloads
- Signed URLs for download manifests (5-minute expiry)

### Accessibility
- WCAG 2.1 AA compliance: color contrast ≥ 4.5:1 (text), ≥ 3:1 (UI)
- All interactive elements have TalkBack labels (Android) or aria-label (web)
- Focus order follows visual hierarchy; keyboard navigation supported (web)
- TalkBack/VoiceOver tested on Android; screen reader announces pagination

### Localization
**Supported Languages**: English (default), Arabic (RTL), Dutch

**Implementation**:
- Backend honors Accept-Language header with fallback to English
- ICU MessageFormat for plurals respecting locale rules (Arabic has 6 forms)
- RTL mirroring: layout direction, directional icons (back/forward), text
  alignment
- Locale-specific numerals (Eastern Arabic for Arabic), date formats
  (Gregorian calendar)
- Mobile locale switcher in Settings; web switcher in header

**Coverage**:
- 100% of UI strings translated for en/ar/nl
- Admin-managed content (titles, descriptions) stored as locale maps in
  database
- Error messages, empty states, accessibility labels localized

### Reliability
**Uptime Target**: 99.5% (43 hours downtime/year max)

**Error Handling**:
- Retry with exponential backoff (max 3 attempts) for network failures
- Circuit breaker for external APIs (YouTube, Firebase) with fallback to cached
  data
- Graceful degradation: mobile app shows deterministic fake data when backend
  unreachable

**Backup & Recovery**:
- Firestore automatic daily backups with 35-day retention
- Audit logs exported daily to immutable object storage
- Recovery Time Objective (RTO): 4 hours
- Recovery Point Objective (RPO): 24 hours

**Monitoring**:
- Prometheus metrics: API latency, error rate, cache hit ratio
- Grafana dashboards with 5-minute granularity
- Alerts: latency > 200ms for 3 intervals, cache hit < 60%, error rate > 1%
- Mobile crash-free rate ≥ 99%, ANR rate ≤ 0.5% (Firebase Crashlytics)

---

## Risks & Open Questions

**High-Priority Risks**:
1. **YouTube Terms of Service Compliance**: Verify that curated playback via
   NewPipe extractor complies with YouTube ToS; consult legal counsel pre-launch
   - Mitigation: Use official YouTube API where possible; fallback to extractor
     only for stream resolution

2. **Content Moderation Scalability**: Manual review doesn't scale beyond 5,000
   videos
   - Mitigation: Implement auto-approval rules for trusted channels in v1.1;
     recruit volunteer moderators

3. **Download Storage Quota Enforcement**: Users may circumvent 500MB quota via
   app reinstall
   - Mitigation: Track downloads server-side by device ID; enforce global quota
     in v1.1

4. **Arabic Translation Quality**: Machine-translated Islamic content may
   contain theological inaccuracies
   - Mitigation: Engage Islamic scholars for Arabic translation review;
     community feedback mechanism

**Medium-Priority Risks**:
5. **NewPipe Extractor Breakage**: YouTube UI changes break extractor library
   - Mitigation: Monitor extractor GitHub repo; maintain fallback to official
     API where possible; display user-friendly errors

6. **Firebase Costs**: Firestore read/write costs may exceed budget at scale
   - Mitigation: Aggressive caching (5-minute TTL); monitor quota usage;
     migrate to self-hosted database if costs exceed $500/month

**Open Questions**:
- Should moderators be able to approve in bulk (e.g., all videos from trusted
  channel)? → Defer to v1.1 based on moderator feedback
- What happens to approved content if the YouTube source is deleted/private? →
  Backend marks as inactive; mobile API filters automatically
- Should mobile users be able to suggest content for review? → Out of scope for
  MVP; consider for v1.2

---

## Success Metrics

**Content Quality** (Measured monthly):
- Inappropriate content incidents < 0.1% of catalog
- Moderator approval time < 48 hours for 95% of pending submissions
- Content freshness: ≥ 20% of catalog refreshed each month

**User Engagement** (Measured weekly):
- Monthly Active Users (MAU): 1,000 by Month 6
- Daily Active Users (DAU): 30% of MAU
- Average session duration ≥ 15 minutes
- Video completion rate > 60%
- 7-day return visitor rate > 50%

**Platform Health** (Measured continuously):
- API p95 latency < 200ms
- Mobile crash rate < 0.5%
- Mobile cold start < 2.5s on Pixel 4a
- Uptime ≥ 99.5%

**Operational Efficiency** (Measured monthly):
- Admin onboarding time < 2 hours
- Moderator productivity ≥ 50 approvals per day
- Support ticket resolution < 24 hours

**Growth Targets** (6-month roadmap):
- Month 1-2: 500+ videos, 10 categories, 100 MAU
- Month 3-4: 2,000+ videos, 15 categories, 500 MAU
- Month 5-6: 5,000+ videos, 20 categories, 1,000 MAU

---

## Release Plan (MVP → v1.1)

### MVP (Months 1-3): Core Curation & Playback
**Must-Have Features**:
- Admin authentication with ADMIN/MODERATOR roles
- YouTube content search via official API
- Approval workflow: submission → pending → approved/rejected
- Category management (hierarchical, max 2 levels)
- Exclusions (videos/playlists within channels)
- Android app: browse by category, video playback, quality selection
- Offline downloads with 500MB quota, 30-day expiry
- Multi-language: English, Arabic (RTL), Dutch
- Audit logging of all moderation actions
- Metrics dashboard: pending queue, content totals, moderator activity

**Launch Criteria**:
- 500+ approved videos across 20 categories
- 3+ trained moderators
- Zero P0 bugs
- Performance budgets met (API < 200ms p95, mobile < 2.5s cold start)
- WCAG AA compliance verified
- Security audit passed

**MVP Backlog** (defer if time-constrained):
- Bulk import/export (JSON format)
- Advanced search filters (video length, publish date)
- Email notifications for moderators

### v1.1 (Months 4-6): Optimization & Scale
**Next Features**:
- Auto-approval rules for trusted channels (skip manual review)
- Bulk approval actions for moderators
- Content suggestions from mobile users (submission form)
- Advanced analytics: watch time, completion rates per category
- Admin-managed settings persistence (locale, notifications)
- Download queue management (pause/resume individual items)
- Video player: Picture-in-Picture mode (Android 8+)
- Background audio playback with MediaSession notifications

**Performance Enhancements**:
- CDN for thumbnails (reduce cold start bandwidth)
- Baseline profile for Android (improve startup time 20%)
- Database indexing optimization (target < 5ms query times)

**Operational Improvements**:
- Volunteer moderator onboarding program
- Community-driven category suggestions
- Automated content freshness checks (detect deleted YouTube videos)

### Future (v1.2+): Platform Expansion
- iOS application (Swift/SwiftUI)
- Web viewer (public content browsing without mobile app)
- Parental controls: profiles, watch time limits
- Advanced recommendations engine (ML-based)
- Multi-device sync (watch history, bookmarks)
- Premium features: custom playlists, advanced search
