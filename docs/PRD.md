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
- Admin search of YouTube via NewPipeExtractor (https://github.com/TeamNewPipe/NewPipeExtractor)
  - **Fallback (Out of Scope for MVP)**: FreeTube (https://github.com/FreeTubeApp/FreeTube) or Invidious (https://github.com/iv-org/invidious) when NewPipe breaks
  - **Note**: Never fallback to official YouTube API
- Three-stage approval workflow: submission → pending → approved/rejected
- Category assignment (hierarchical: parent/child structure, max 2 levels)
- Granular exclusions (specific videos/playlists within approved channels and specific videos within approved playlists)
- Live stream support: channels/playlists include active/upcoming/archived streams; individual live videos can be added for approval; status automatically updates when stream ends (detected via NewpipeExtractor)
- Audit trail of all moderation decisions with actor, timestamp, reason

**Mobile Experience (Android)**:
- Browse approved content by category, channel, playlist, video
- Video player with advanced features:
  - Quality selection (144p-1080p) with resolution info display
  - Audio-only mode toggle (playback and downloads)
  - Subtitle/caption support with multi-language tracks, auto-generated detection, "Off" option
  - Picture-in-Picture mode (Android 8+) with auto aspect ratio calculation
  - Fullscreen mode with immersive UI (hides status/navigation bars)
  - Gesture controls: swipe for brightness/volume, double-tap to seek ±10s
  - Chromecast support: Cast button, device detection, metadata passthrough
  - UpNext queue with backend recommendations API integration
  - Share functionality (external apps via intent chooser)
- Live stream playback: "LIVE" badge on active streams, disabled seek bar, auto-transition to VOD when ended
- Offline downloads with quality selector (144p-1080p), separate audio/video stream merging for HD (>480p), 30-day expiry, audio-only download option, device storage management
- Search across approved content with persistent search history (max 10 items, delete individual entries)
- Safe mode toggle for family-friendly content filtering
- Multi-language support: English, Arabic (RTL), Dutch
- Basic analytics: event tracking for playback, quality changes, subtitle changes, queue interactions

**Admin Dashboard (Web)**:
- YouTube content search and preview
- Pending approvals queue with inline category assignment
- Content library management with advanced features:
  - Dual layout (desktop sidebar filters + mobile bottom sheet)
  - Multi-select with bulk actions (approve, delete, assign categories)
  - Advanced filtering (type, status, category, date added, search)
  - Sort options (newest, oldest, alphabetical)
  - Inline editing and exclusion management
- Exclusions workspace:
  - Dedicated management interface for all exclusions
  - Search and type filtering
  - Bulk removal operations
  - Parent context display (shows which channel/playlist)
  - Reason tracking and audit trail
- Bulk import/export system:
  - Full format: Complete JSON backup with merge strategies (SKIP_EXISTING, OVERWRITE, MERGE)
  - Simple format: JSON-like format with downloadable templates
  - Selective export (categories, channels, playlists, videos)
  - Option to exclude unavailable videos
- Video validation system:
  - Manual trigger with configurable video limits
  - Validation history tracking with statistics
  - Status tracking (NEVER_RUN, RUNNING, COMPLETED, FAILED, ERROR)
  - Detects unavailable/deleted YouTube videos
  - Dashboard panel showing validation status and unavailable video counts
- User management (create/assign admin/moderator roles)
- Metrics dashboard:
  - Pending queue depth, content totals, moderator activity
  - Comparison to previous timeframe with trend indicators (UP/DOWN/FLAT)
  - Timeframe selector (Last 24h / Last 7 Days / Last 30 Days)
  - Validation status panel with error counts
  - Warning banners for threshold breaches
- Audit log with filtering by actor, action, date range

**Technical Requirements**:
- Role-based access control (ADMIN: full permissions including direct approval and user management; MODERATOR: can submit content for admin review, view dashboard metrics)
- NoSQL document database with composite indexes
- Response caching (5-minute TTL) with cache stampede protection
- Cursor-based pagination for infinite scroll
- Internationalization with ICU MessageFormat plurals

**Testing Scope (TestSprite)**:
- **Frontend Testing**: Test only the `/frontend` directory (Vue 3 Admin Dashboard)
  - **IMPORTANT**: Exclude `/android` directory from frontend test scope
  - Android app has separate native testing (Kotlin/Gradle)
- **Backend Testing**: Test only the `/backend` directory (Spring Boot API)
  - **IMPORTANT**: Exclude `/frontend` and `/android` directories from backend test scope

---

## Out of Scope

**For MVP**:
- iOS application (Android-only for launch)
- User accounts for mobile viewers (public access, no login)
- Community content suggestions from end-users
- Video uploads (YouTube-only sourcing)
- Premium features (playlists, bookmarks, watch history sync across devices)
  - Note: Basic search history (local, max 10 items) IS in scope
- Automated moderation via ML/AI
- In-app social features (comments, likes, in-app sharing)
  - Note: External sharing via intent chooser (WhatsApp, email, etc.) IS in scope
- Cloud storage integration (app-private storage only)

**Future Consideration**:
- Volunteer moderator program
- Advanced analytics (watch time heatmaps, completion rates, retention metrics)
  - Note: Basic event tracking (playback events, quality changes) IS in scope
- Content recommendations engine (ML-based personalization)
  - Note: UpNext queue with backend recommendations IS in scope
- Multi-device sync
- Parental controls and profiles (kid mode, time limits)
  - Note: Safe mode toggle IS in scope

---

## User Stories & Acceptance Criteria

### Story 1: Admin Searches and Directly Approves Content
**As an** administrator
**I want to** search YouTube and directly approve content
**So that** it immediately appears in the mobile app for users

**Acceptance Criteria**:
1. Search returns blended results (channels/playlists/videos) within 2s
2. Results cached for 1 hour; subsequent searches hit cache
3. "Add & Approve" button opens category assignment modal requiring ≥1 category
4. Approval creates document with status="approved", approvedBy=admin UID,
   approvedAt=timestamp
5. Content visible in mobile app within 60 seconds (cache refresh)
6. Audit log records approval with actor UID, entity ID, timestamp, assigned categories

### Story 1B: Moderator Submits Content for Admin Review
**As a** moderator
**I want to** submit content for admin approval
**So that** admins can make final decisions on content selection

**Acceptance Criteria**:
1. Search returns blended results (channels/playlists/videos) within 2s
2. "Submit for Review" button opens category assignment modal requiring ≥1 category
3. Submission creates document with status="pending", submittedBy=moderator UID,
   submittedAt=timestamp
4. Item appears in admin's pending queue immediately after submission
5. Audit log records submission with actor UID, entity ID, timestamp

### Story 2: Admin Approves Channel with Exclusions
**As an** administrator
**I want to** review pending channels and exclude specific videos
**So that** channels with mostly good content can be approved despite isolated inappropriate items

**Acceptance Criteria**:
1. Pending queue displays items sorted oldest-first (FIFO) with filters for
   type, category, date
2. Channel detail modal shows recent 50 videos with exclude checkboxes
3. Exclusion requires reason from dropdown (Contains Music, Theological Issue,
   Duplicate, Other)
4. Approve action updates status="approved", approvedBy=admin UID,
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
1. Home feed returns 20 latest items per type within 3s on cold start
2. Category filter persists across tabs during session
3. Video tap loads player screen; playback starts within 1.2s (p50)
4. Player has no autoplay to next video; replay button shown on completion
5. Zero advertisements during playback or in browse UI
6. Quality selector displays all available qualities (144p-4k), sorted low
   to high
7. Audio-only toggle switches stream without restarting playback

### Story 4: Student Downloads Single Video Offline
**As a** student of knowledge
**I want to** download individual videos for offline viewing
**So that** I can watch specific lectures without internet connection

**Acceptance Criteria**:
1. Video detail screen shows "Download" icon/button
2. Tap opens quality selector modal showing available qualities (144p, 360p, 720p, 1080p, 4k) with estimated file sizes
3. User selects quality; app shows estimated file size and available device storage
4. For qualities >360p: downloads video and audio streams separately, then merges using FFmpeg or similar (following NewPipe implementation: https://github.com/TeamNewPipe/NewPipe/blob/f836f5e75dcf80e4ca8c7e525114c57f425952e9/app/src/main/java/us/shandian/giga/service/DownloadManagerService.java#L59)
5. WorkManager queues download with foreground notification showing progress (percentage, speed, ETA)
6. Download can be paused/resumed/cancelled from notification or Downloads tab
7. Downloaded indicator appears on video thumbnail after completion
8. Tap downloaded video opens player in offline mode; no network required
9. Downloads expire after 30 days; user notified 7 days before expiry
10. If device storage is critically low during download, Android OS will handle warning/cancellation (system behavior)

### Story 5: Student Downloads Playlist Offline
**As a** student of knowledge
**I want to** download entire playlists for offline study
**So that** I can listen during commute without mobile data

**Acceptance Criteria**:
1. Playlist detail shows "Download" button with available quality selector (144p, 360p, 720p, 1080p, 4k)
2. User selects preferred download quality; app shows estimated total storage requirement and available device storage
3. For qualities >360p: downloads video and audio streams separately, then merges (following NewPipe implementation: https://github.com/TeamNewPipe/NewPipe/blob/f836f5e75dcf80e4ca8c7e525114c57f425952e9/app/src/main/java/us/shandian/giga/service/DownloadManagerService.java#L59)
4. WorkManager enqueues download with foreground notification showing progress
5. Notification shows "Downloading 1/30" with cancel action
6. Downloads tab displays aggregated progress (30% across playlist)
7. Completed downloads expire after 30 days; user notified 7 days prior
8. Offline playback works without network; maintains watch position
9. If device storage becomes critically low during download, Android OS handles warnings (system behavior)

### Story 6: Admin Views Dashboard Metrics
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
2. Onboarding carousel presents 3 pages: Browse, Listen in background, Download for offline
3. Main shell loads with Home tab active showing:
   - Category filter chip at top
   - Three horizontal scrollable sections: Channels (20 latest), Playlists (20 latest), Videos (20 latest by published date)
   - Each section has "See All" link to navigate to dedicated tab
4. User taps category filter chip
5. Categories screen opens showing all top-level categories; categories with subcategories display next arrow indicator
6. User taps "Quran Recitation" category (or subcategory if applicable)
7. Navigation returns to Home tab, now filtered to show only "Quran Recitation" content
8. User taps video thumbnail from Videos horizontal list
9. Player screen loads; video starts within 1.2s
10. User watches to completion; replay button appears (no autoplay)
11. Back navigation returns to filtered Home tab; filter state preserved

### Flow 2: Admin Approves New Channel Directly
1. Admin logs into dashboard with email/password (Firebase Auth)
2. Navigates to Content Search; enters "Islamic lecture" in search box
3. Search returns 20 YouTube results (channels, playlists, videos) within 2s
4. Admin filters by "Channels only"; finds channel with 100K subscribers
5. Clicks "Preview" to open YouTube in new tab; verifies channel authenticity
6. Returns to dashboard; clicks "Add & Approve"
7. Category assignment modal opens; admin selects "Hadith" and "Seerah"
8. Opens channel detail modal; views recent 50 videos
9. Identifies 2 videos with background music; checks exclude boxes
10. Adds exclusion reason: "Contains Music"
11. Clicks "Approve Channel"
12. Channel status set to "approved"; excluded videos stored in array
13. Audit log records approval with admin UID, categories, exclusions
14. Mobile app receives approved channel in next API fetch (<60s)

### Flow 3: Moderator Submits Content for Admin Review
1. Moderator logs into dashboard
2. Navigates to Content Search; enters "Sheikh Ahmad Tafsir"
3. Search returns channels/playlists/videos within 2s
4. Moderator finds relevant channel; clicks "Preview" to verify
5. Returns to dashboard; clicks "Submit for Review"
6. Category assignment modal opens; selects "Tafsir" category
7. Adds submission note: "Popular channel, needs admin verification"
8. Clicks Submit; confirmation toast appears
9. Channel appears in Admin's pending queue with status="pending"
10. Admin reviews and approves/rejects using Flow 2 process
11. Audit log records both submission (moderator) and approval (admin)

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

**MVP (Minimal Required)**:
- Firebase Authentication for admin dashboard login (email/password)
- Role-based access control: ADMIN and MODERATOR roles via Firebase custom claims
- HTTPS/TLS for all connections (production only, localhost HTTP acceptable for dev)
- Admin passwords managed by Firebase Auth (automatic bcrypt hashing)
- Mobile app: public access only, no user accounts or authentication required
- Downloaded videos stored in app-private directory (Android OS-level encryption)
- Basic input validation: reject empty/null required fields

**Deferred to Later Phases**:
- Advanced rate limiting (abuse protection)
- Token refresh rotation
- Account lockout after failed login attempts
- CSRF protection (double-submit cookies)
- Comprehensive JSON schema validation
- Firestore security rules hardening
- Audit log immutable storage
- Signed download URLs
- HSTS headers
- Argon2id password hashing (Firebase default is sufficient for MVP)

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
**Uptime Target**: 99.9% (43 hours downtime/year max)

**Error Handling (MVP - Minimal)**:
- Basic retry logic: 2 retries with 1-second delay for network failures
- Try-catch blocks with user-friendly error messages
- Mobile app: show "Unable to connect" message when backend unreachable

**Error Handling (Deferred to Later)**:
- Exponential backoff retry strategies
- Circuit breaker patterns for external APIs
- Graceful degradation with cached/fallback data
- Deterministic fake data for offline development

**Backup & Recovery (Out of Scope for MVP)**:
- Firestore has built-in replication (no manual backups needed for MVP)
- Manual data export via Firebase Console if needed
- **Deferred to v1.1+**:
  - Automated daily backups with retention policies
  - Audit log exports to immutable storage
  - Formal RTO/RPO targets
  - Disaster recovery procedures

**Monitoring (Out of Scope for MVP)**:
- MVP: Manual testing and basic Firebase Console monitoring only
- **Deferred to v1.1+**:
  - Prometheus metrics collection
  - Grafana dashboards
  - Automated alerting (latency, error rates)
  - APM tools (Application Performance Monitoring)
  - Firebase Crashlytics integration for mobile crash tracking

---

## Risks & Open Questions

**High-Priority Risks**:


1. **Content Moderation Scalability**: Manual review doesn't scale beyond 5,000
   videos
   - Mitigation: Implement auto-approval rules for trusted channels in v1.1;
     recruit volunteer moderators

2. **Download Storage Abuse**: Users may download excessive content, impacting backend bandwidth
   - Mitigation: Monitor download patterns; implement reasonable rate limits (e.g., max 10 concurrent downloads) in v1.1 if abuse detected
   - Note: No artificial storage quota - user's device storage is the natural limit

3. **Arabic Translation Quality**: Machine-translated Islamic content may
   contain theological inaccuracies
   - Mitigation: Engage Islamic scholars for Arabic translation review;
     community feedback mechanism

**Medium-Priority Risks**:
5. **NewPipe Extractor Breakage**: YouTube UI changes break extractor library
   - Mitigation (Out of Scope for MVP): Monitor NewPipe GitHub repo; implement
     fallback to FreeTube (https://github.com/FreeTubeApp/FreeTube) or Invidious
     (https://github.com/iv-org/invidious) extractors when NewPipe breaks
   - MVP approach: Display user-friendly errors; manually update NewPipe library
     when breakage occurs
   - **Never fallback to official YouTube API** (preserves privacy, avoids vendor lock-in)

6. **Firebase Costs**: Firestore read/write costs may exceed budget at scale
   - Mitigation: Aggressive caching (5-minute TTL); monitor quota usage via
     Firebase Console
   - **Migration Path (Out of Scope for MVP)**: Migrate to Supabase
     (https://supabase.com) if Firebase costs exceed $500/month
   - Supabase offers: PostgreSQL (vs NoSQL), better pricing at scale, row-level
     security, self-hosting option

**Open Questions**:
- Should admins be able to approve in bulk (e.g., all videos from trusted
  channel)? → Defer to v1.1 based on admin feedback
- What happens to approved content if the YouTube source is deleted/private? →
  Backend marks as inactive; mobile API filters automatically
- Should mobile users be able to suggest content for review? → Out of scope for
  MVP; consider for v1.2

---

## Success Metrics

**Content Quality** (Measured monthly):
- Inappropriate content incidents < 0.1% of catalog
- Admin approval time < 48 hours for 95% of pending submissions (from moderators)
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
- Admin productivity ≥ 50 approvals per day
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
- YouTube content search via NewPipeExtractor (primary extractor)
  - **Note**: FreeTube/Invidious fallback system deferred to v1.1
- Approval workflow: ADMIN direct approval, MODERATOR submission for review
- Category management (hierarchical, max 2 levels)
- Exclusions (videos/playlists within channels) with dedicated workspace
- Bulk import/export (formats: full JSON)
- Video validation system (detect unavailable videos)
- Content library with advanced filtering and bulk actions
- Android app: browse by category, video playback with advanced features
  - Quality selection, audio-only mode, subtitles
  - Picture-in-Picture, fullscreen, gesture controls
  - Chromecast support, UpNext recommendations
  - External sharing, search history
  - Live stream support
- Offline downloads with 30-day expiry, audio-only option, device storage management
- Multi-language: English, Arabic (RTL), Dutch
- Audit logging of all approval actions
- Metrics dashboard with trend analysis: pending queue, content totals, admin activity, validation status

**Launch Criteria**:
- 500+ approved videos across 20 categories
- 2+ trained admins
- Zero P0 bugs
- Performance budgets met (API < 200ms p95, mobile < 2.5s cold start)
- WCAG AA compliance verified
- Security audit passed

**MVP Backlog** (defer if time-constrained):
- Advanced search filters (video length, publish date)
- Email notifications for admins (pending queue alerts)

### v1.1 (Months 4-6): Optimization & Scale
**Next Features**:
- Auto-approval rules for trusted channels (skip manual review)
- Content suggestions from mobile users (submission form)
- Advanced analytics: watch time, completion rates per category
- Admin-managed settings persistence (locale, notifications)
- Background audio playback with MediaSession notifications

**Performance Enhancements**:
- CDN for thumbnails (reduce cold start bandwidth)
- Baseline profile for Android (improve startup time 20%)
- Database indexing optimization (target < 5ms query times)

**Operational Improvements**:
- Volunteer moderator onboarding program
- Community-driven category suggestions
- Automated content freshness checks (detect deleted YouTube videos)
- **Extractor Resilience**: Implement fallback system for FreeTube/Invidious when NewPipe breaks

### Future (v1.2+): Platform Expansion
- iOS application (Swift/SwiftUI)
- Desktop application (public content browsing without mobile app)
- Smart TV platforms:
  - Samsung TV (Tizen OS)
  - LG TV (webOS)
  - Google TV / Android TV
- Parental controls: profiles, watch time limits
- Advanced recommendations engine (ML-based)
- Multi-device sync (watch history, bookmarks)
- Premium features: custom playlists, advanced search
