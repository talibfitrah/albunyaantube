package com.albunyaan.tube.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Public, anonymously reachable legal pages served from {@code app.fitrahtube.com}.
 *
 * <p>Two obligations converge here:
 * <ul>
 *   <li>Google Play policy 13327111 requires any app that allows in-app account
 *       creation to publish a web URL where deletion can be requested WITHOUT
 *       reinstalling the app → {@code GET /delete-account}.</li>
 *   <li>The Android About screen links to {@code /privacy}, {@code /terms} and
 *       {@code /licenses}. A dead privacy-policy URL is an automatic Play
 *       rejection, so all three must render.</li>
 * </ul>
 *
 * <p>Hand-built HTML strings, following {@link WatchPageController}: there is no
 * template engine on the classpath ({@code spring-boot-starter-web} only) and no
 * {@code resources/templates} or {@code static} directory. Every page is
 * self-contained — inline CSS, no images, no scripts, no external assets — so it
 * renders on a reviewer's device with no network beyond the initial request.
 *
 * <p>No user input reaches these pages: every string below is author-controlled
 * static content, so there is nothing to escape.
 */
@Controller
public class LegalPagesController {

    /** Public contact address for privacy, deletion and licence enquiries. */
    private static final String CONTACT = "info@albunyaan.tv";

    /** Bump whenever the substance of any page below changes. */
    private static final String LAST_UPDATED = "27 September 2026";

    // ── /delete-account ────────────────────────────────────────────────────
    //
    // The in-app control shipped in 249c7d75, so the wording below is no longer
    // a forward reference. It is NOT under Settings — every occurrence of the
    // path on this and the other pages must stay pinned to what the app really
    // does:
    //   res/layout/fragment_me.xml:11,16 ....... Me tab toolbar, title nav_me
    //   res/menu/menu_me_kebab.xml ............. overflow item action_profile,
    //                                            title me_kebab_profile
    //   ui/me/MeFragment.kt:279 ................ navigates action_me_to_profile
    //   res/navigation/main_tabs_nav.xml:60,71 . → profileFragment
    //   res/layout/fragment_profile.xml:257,268  deleteAccountRow, label
    //                                            profile_delete_account
    //   ui/me/profile/ProfileFragment.kt:81 .... row → confirmDeleteAccount()
    // Settings has an Account section, but fragment_settings.xml:34-65 shows it
    // holds settings_item_signout ONLY — no delete control and no profile
    // fields. LegalPagesControllerTest pins the exact published string so the
    // three copies cannot drift apart again.

    @GetMapping(value = "/delete-account", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> deleteAccount() {
        return html("Delete your FitrahTube account", """
                <h1>Delete your FitrahTube account</h1>
                <p class="lede">FitrahTube (the Android app on Google Play, package
                <code>com.albunyaan.tube.play</code>, and other FitrahTube app builds, package
                <code>com.albunyaan.tube</code>) lets you
                delete your account and its data permanently. There is no waiting period and
                no grace period &mdash; once the deletion runs, it cannot be undone.
                FitrahTube is published by Stichting Tarbiyah Consultancy, Almere, the Netherlands (the data controller).</p>

                <div class="callout">
                  <h2>Option 1 &mdash; delete it in the app</h2>
                  <p>Open FitrahTube and go to:</p>
                  <p class="path"><strong>Me &rarr; &#8942; &rarr; Profile &rarr; Delete account</strong></p>
                  <p>&#8942; is the three-dot menu at the top of the <strong>Me</strong>
                  tab. Confirm when prompted. Your account is deleted immediately and you
                  are signed out.</p>
                </div>

                <div class="callout">
                  <h2>Option 2 &mdash; ask us to delete it</h2>
                  <p>If you have already uninstalled the app, or you cannot sign in, email us
                  and we will delete the account for you:</p>
                  <p class="path"><!--email_off--><a href="mailto:%1$s?subject=Account%%20deletion%%20request">%1$s</a><!--email_on--></p>
                  <p>Send the request <strong>from the email address the account was
                  registered with</strong>, so we can confirm the account is yours. We do not
                  ask for a password. We aim to complete the deletion within 30 days of
                  verifying the request.</p>
                </div>

                <h2>What gets deleted</h2>
                <ul>
                  <li>Your sign-in record, including your email address and password
                      (held by Firebase Authentication).</li>
                  <li>Your profile: display name, date of birth and phone number.</li>
                  <li>Your entire library: subscribed channels, saved playlists and
                      favourite videos.</li>
                  <li>Your download activity records.</li>
                  <li>Any personal access grants that named your account.</li>
                </ul>

                <h2>What is kept, and why</h2>
                <ul>
                  <li><strong>Security and audit logs.</strong> We keep records of
                      security-relevant actions (sign-in state changes, moderation actions,
                      and the deletion itself), including the account identifier and the
                      email address that performed the action. We keep these to investigate
                      abuse and to prove that a deletion happened.</li>
                  <li><strong>An anonymous placeholder record.</strong> Your account
                      identifier is kept as an empty marker with no personal data attached
                      &mdash; no email, no name, no date of birth, no phone number. It exists
                      only so that content you submitted for review does not point at a
                      missing record. The identifier carries no personal data itself;
                      the security and audit logs described above still connect it to your email address,
                      and so do the records of moderation work described below.</li>
                  <li><strong>Records of moderation work (moderator and administrator accounts).</strong>
                      If your account reviewed content, the approvals and rejections you made, the
                      content reports you resolved and the validation runs you started keep the email
                      address you acted from, as the record of who took each decision.</li>
                  <li><strong>Content you submitted for review.</strong> Channels, playlists
                      and videos you submitted for the catalogue &mdash; from a YouTube import
                      or, for moderator accounts, as a suggestion together with any note
                      written with it &mdash; are kept as moderation records, linked only to
                      the anonymous placeholder. They have no fixed deletion date and remain
                      until a moderator removes them.</li>
                  <li><strong>Content that was approved into the public catalogue.</strong>
                      Channels, playlists and videos are public YouTube content and are not
                      personal data about you.</li>
                </ul>

                <h2>If you only want to stop using the app</h2>
                <p>You do not need to delete your account to stop using FitrahTube &mdash;
                you can simply sign out or uninstall it. Deletion is permanent; uninstalling
                is not.</p>

                <p>Questions? <!--email_off--><a href="mailto:%1$s">%1$s</a><!--email_on--> &middot;
                <a href="/privacy">Privacy Policy</a></p>
                """.formatted(CONTACT));
    }

    // ── /privacy ───────────────────────────────────────────────────────────
    //
    // Content below is derived from what the code ACTUALLY collects, not from a
    // template. Anchors, so the claims stay checkable:
    //   model/User.java:16-61 .......... email, displayName, role, status,
    //                                    dateOfBirth, phoneNumber, lastLoginAt
    //   repository/SyncRepository.java:17-19,37 .. users/{uid}/{subscriptions,
    //                                    playlists,favorites}
    //   model/DownloadEvent.java:9-16 .. download analytics (userId, videoId,
    //                                    quality, fileSize, deviceType)
    //   model/ContentReport.java:17 .... deviceId (X-Device-Id), NOT uid-keyed
    //   model/AuditLog.java:40,45,60 ... actorUid, actorDisplayName (= email);
    //                                    ipAddress declared but NEVER written —
    //                                    no setIpAddress caller exists anywhere
    //   security/FirebaseAuthFilter.java:112-114,223 .. uid/email/emailVerified
    //   controller/ContentReportController.java:34,48 .. X-Device-Id required
    //   controller/WatchPageController.java:140 ........ X-Device-Id rate limit
    //   controller/IndexController.java:57,74 .......... X-Device-Id dedupe
    //   service/MailService.java:176 ... recipient address leaves to MS Graph
    //   config/NewPipeConfiguration.java:62-63 ......... hardcoded Locale.US,
    //                                    so no user locale is sent to YouTube
    //   scheduler/TombstoneGcScheduler.java:29 ......... 90-day tombstone GC
    //
    // Open questions for the owner. These MUST stay in `//` comments: an HTML
    // comment written inside the text block below is served verbatim to every
    // reader of the published policy.
    //
    // Controller named in section 1 (owner, 2026-09-27): Stichting Tarbiyah
    //   Consultancy, Almere NL. TODO(owner): whether an EU/UK representative
    //   or a DPO must be named.
    // TODO(owner): audit-log retention is currently unbounded (there is no GC
    //   job for the audit_logs collection). If a fixed maximum retention period
    //   is required for GDPR proportionality, set one and state it in
    //   section 6.
    // TODO(owner): /opt/albunyaan/logs/app.log has no rotation (live check
    //   2026-09-27: 91 MB since Nov 2025, no logrotate entry). Add rotation,
    //   then replace "no fixed deletion date" for application logs in section 6.
    // Section 8 names the Autoriteit Persoonsgegevens (controller is in NL).
    // TODO(owner): whether a self-service data export is
    //   required.

    @GetMapping(value = "/privacy", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> privacy() {
        return html("FitrahTube Privacy Policy", """
                <h1>FitrahTube Privacy Policy</h1>
                <p class="meta">Last updated: %2$s</p>

                <p class="lede">FitrahTube is a curated Islamic video app. This policy
                describes what the FitrahTube app and its backend collect, why, who it is
                shared with, how long it is kept, and how to have it deleted. It covers the FitrahTube Android app on Google Play (<code>com.albunyaan.tube.play</code>), other FitrahTube app builds, and the service at <code>app.fitrahtube.com</code>.</p>

                <h2>1. Who is responsible</h2>
                <p>FitrahTube is published by Stichting Tarbiyah Consultancy, Almere, the Netherlands (the data controller). For any privacy question,
                or to exercise any right described below, contact
                <!--email_off--><a href="mailto:%1$s">%1$s</a><!--email_on-->.</p>

                <h2>2. What we collect</h2>

                <h3>2.1 Account information</h3>
                <p>FitrahTube requires an account, so we collect the following when you
                create one:</p>
                <ul>
                  <li><strong>Email address</strong> and, for email/password sign-in, a
                      password. Passwords are handled entirely by Google Firebase
                      Authentication and are never stored on our servers.</li>
                  <li><strong>Display name.</strong></li>
                  <li><strong>Date of birth.</strong> Used to check that you are at least 13,
                      and kept with your profile until you delete your account. Our administrators
                      can see it when they manage accounts. Accounts that
                      report an age under 13 are refused and immediately deactivated.</li>
                  <li><strong>Phone number.</strong> Required when you set up your profile and
                      stored with it. It is shown back to you in the app and to our administrators
                      when they manage accounts; we do not verify it,
                      send it SMS messages, show it to moderators or use it for anything
                      else.</li>
                  <li><strong>Account state:</strong> role, status, creation and last sign-in
                      timestamps.</li>
                  <li>If you sign in with Google, Google shares your name, email address and
                      profile photo address with Firebase Authentication, which keeps them in
                      your sign-in record, and Firebase gives us your email address, a user
                      identifier and whether that address is verified. The app suggests your
                      Google name as your display name, which you can change. We do not copy
                      your profile photo into our own database, and we do not receive your
                      Google password or your Google contacts.</li>
                  <li>If you use Sign in with Apple, Firebase gives us a user identifier and
                      the email address you choose to share &mdash; either your own or Apple's
                      private relay address. We do not ask for your name and do not receive
                      your Apple ID password.</li>
                </ul>

                <h3>2.2 Your library</h3>
                <p>The channels you subscribe to, the playlists you save and
                the videos you mark as favourites are stored against your account so they
                sync between your devices. Each entry stores the YouTube identifier, title,
                thumbnail and the time you added it.</p>

                <h3>2.3 Downloads</h3>
                <p>When you download a video for offline viewing, we record the video
                identifier, the chosen quality, an estimated file size, a coarse device
                category and a timestamp. Because the app requires an account, this record
                is linked to yours.</p>

                <h3>2.4 Device identifier</h3>
                <p>The app generates a random identifier for each installation and sends it
                as an <code>X-Device-Id</code> header. It is not your advertising ID, not
                your hardware serial number, and it is not linked to your account. We use it
                only to rate-limit abuse and to de-duplicate repeated requests. It is stored
                permanently only when you submit a content report (see below); everywhere
                else it lives in a short-lived in-memory counter (60&nbsp;seconds to
                24&nbsp;hours) and is never written to disk.</p>

                <h3>2.5 Content reports</h3>
                <p>If you report content, we store the reported item, the reasons you
                selected, any free-text description you write, and the reporting
                installation's <code>X-Device-Id</code>. Reports are keyed to the
                installation, not to your account. Because a report is not linked to an account, deleting
                your account does not delete reports you submitted.</p>

                <h3>2.6 Security and audit logs</h3>
                <p>We record security-relevant events &mdash; account status changes,
                moderation decisions, profile edits and account deletions &mdash; together
                with the account identifier and email address that performed them.</p>

                <h3>2.7 Optional: importing from your YouTube account</h3>
                <p>If you choose to import from YouTube, Google asks you to grant FitrahTube
                read-only access to your YouTube account (Google OAuth scope
                <code>youtube.readonly</code>). The app then uses the YouTube Data API, directly
                from your device, to read your subscriptions, your playlists and your liked
                videos. The access token stays on your device and is not sent to or stored by
                us. The identifiers, titles and thumbnails of the items you select are sent to
                our server to check them against the catalogue; items that are not yet in the
                catalogue are stored as submissions linked to your account and
                sent to our moderators for catalogue review, together with your account's display name and email address.
                If you delete your account, these submissions stay in our moderation records but
                point only to an anonymous placeholder (see section&nbsp;6). You can revoke FitrahTube's access at any time on your Google
                Account permissions page,
                <a href="https://myaccount.google.com/permissions">myaccount.google.com/permissions</a>.
                By using the import you agree to the
                <a href="https://www.youtube.com/t/terms">YouTube Terms of Service</a>; Google
                handles your data under the
                <a href="https://policies.google.com/privacy">Google Privacy Policy</a>.</p>
                <p>FitrahTube's use and transfer of information received from Google APIs adheres
                to the <a href="https://developers.google.com/terms/api-services-user-data-policy">Google
                API Services User Data Policy</a>, including the Limited Use requirements. We use
                it only for the import and catalogue-review feature you start; we never use it for
                advertising, and we do not sell it or transfer it to anyone except as needed to
                provide that feature.</p>

                <h3>2.8 Content suggestions (moderator accounts)</h3>
                <p>Accounts with the moderator role can also
                suggest channels, playlists and videos for the catalogue, with an optional free-text note.
                The suggestion, the note and the account that made it are visible to our
                moderators and administrators.</p>

                <h3>2.9 Searches</h3>
                <p>Search terms you type are sent to our server to search the catalogue. They
                are not stored with your account, but they
                can appear in our application logs, without your account identifier (see
                section&nbsp;6).</p>

                <h2>3. What we do NOT collect</h2>
                <p>These are absences we have verified in our own code and server configuration, not merely
                intentions:</p>
                <ul>
                  <li><strong>No IP address storage by our application.</strong> Our backend
                      does not record your IP address. Requests reach it through Cloudflare, so
                      our web server logs
                      record Cloudflare's network address, not yours; those logs are
                      deleted after 14 days. Cloudflare itself processes your IP address
                      (section&nbsp;5).</li>
                  <li><strong>No location data</strong> of any kind &mdash; no GPS, no
                      geolocation lookup, no country inference.</li>
                  <li><strong>No advertising identifiers</strong> (no GAID, no IDFA). We do not
                      fingerprint your device ourselves; the Google script described in
                      section&nbsp;5 does collect device signals, which go to Google.</li>
                  <li><strong>No third-party analytics, crash-reporting or advertising
                      SDKs</strong> &mdash; no Google Analytics, no Firebase Analytics, no
                      Crashlytics, no Sentry, no Facebook SDK. FitrahTube shows no ads.
                      (The app does include Google's Cast SDK for casting; see section&nbsp;5.)</li>
                  <li><strong>No watch history.</strong> We do not record which videos you
                      play or how far you watch. Only what you explicitly save, and what you
                      explicitly download, is recorded.</li>
                  <li><strong>No search-query logging</strong> against your account.</li>
                  <li><strong>No cookies or server-side sessions.</strong> The service is
                      stateless and authenticates each request with a token.</li>
                  <li><strong>We do not sell your data</strong>, and we do not share it for
                      advertising or any other commercial purpose.</li>
                </ul>

                <h2>4. Why we use it</h2>
                <ul>
                  <li><strong>Account information</strong> &mdash; to authenticate you, to
                      enforce the minimum age of 13, and to contact you about your
                      account.</li>
                  <li><strong>Library</strong> &mdash; to give you your subscriptions,
                      playlists and favourites on every device you sign in on.</li>
                  <li><strong>Download records</strong> &mdash; to understand aggregate load
                      and to diagnose failed downloads.</li>
                  <li><strong>Device identifier</strong> &mdash; to rate-limit abusive
                      traffic and de-duplicate requests.</li>
                  <li><strong>Content reports</strong> &mdash; to moderate the catalogue.</li>
                  <li><strong>Audit logs</strong> &mdash; to detect and investigate abuse and
                      to keep an accountable record of administrative actions.</li>
                </ul>
                <p>Our legal bases under the GDPR are: <strong>contract</strong>
                (Article 6(1)(b)) for your account, library and download records;
                <strong>legitimate interests</strong> (Article 6(1)(f)) &mdash; preventing abuse
                and moderating the catalogue &mdash; for the device identifier, content reports,
                content suggestions and security and audit logs; and <strong>consent</strong>
                (Article 6(1)(a)) for the YouTube import, which you give by starting it and
                accepting Google's consent screen, and can withdraw at any time.</p>

                <h2>5. Who it is shared with</h2>
                <ul>
                  <li><strong>Google (Firebase Authentication and Cloud Firestore).</strong>
                      Our authentication and database provider. The data described in
                      section&nbsp;2 is stored on Google's infrastructure on our behalf.
                      Password-reset emails you request from the app are sent by Google Firebase.</li>
                  <li><strong>Our hosting provider.</strong> Our backend runs on a
                      virtual private server operated for us by a hosting provider. Requests to
                      our service, and the server logs described in section&nbsp;6, are processed
                      there.</li>
                  <li><strong>Microsoft (Microsoft Graph mail),
                      only when our own mail service is switched on.</strong> Email-verification
                      messages, and password-reset messages sent when an administrator resets your
                      password, are then sent through Microsoft's mail service, which receives
                      your email address and that message; otherwise Google Firebase sends the
                      email-verification messages.
                      Nothing else is sent.</li>
                  <li><strong>YouTube (Google) &mdash; your device connects directly.</strong>
                      To show and play videos,
                      the app connects directly from your device to YouTube/Google servers (<code>youtube.com</code>,
                      <code>googleapis.com</code>, <code>googlevideo.com</code>,
                      <code>ytimg.com</code>). Those servers receive your device's IP address
                      and technical data (such as device and network information and the
                      videos requested) and handle them under the
                      <a href="https://policies.google.com/privacy">Google Privacy Policy</a>;
                      we do not receive that data.
                      To play videos the app runs a Google-provided script in a web view on your device; it collects device and browser signals and sends them to Google. The app
                      does not attach your FitrahTube account, email address or device
                      identifier to these requests.</li>
                  <li><strong>YouTube (Google) &mdash; our server.</strong> Our server also
                      fetches public video, channel and playlist metadata from YouTube to build
                      the catalogue. These requests carry only the content identifier or search
                      term. <strong>No account identifier, email address or device identifier
                      is attached</strong>, and the language and country sent are fixed values,
                      not yours.</li>
                  <li><strong>Cloudflare.</strong> Our network and security provider (CDN) in
                      front of <code>app.fitrahtube.com</code>. Every request to our service
                      passes through Cloudflare, which processes your IP address to deliver and
                      protect the service.</li>
                  <li><strong>Google Cast.</strong> The video player includes Google's Cast SDK
                      so you can play videos on a TV or speaker.
                      When you cast, Google's Cast SDK sends session and usage data to Google,
                      under the Google Privacy Policy.</li>
                </ul>
                <p>We may also disclose data where we are legally required to do so.</p>
                <p><strong>International transfers.</strong> Google, Cloudflare and Microsoft
                may process data in the United States. Where they do, the transfer relies on
                the EU-US Data Privacy Framework or the European Commission's Standard
                Contractual Clauses.</p>

                <h2>6. Retention</h2>
                <ul>
                  <li><strong>Account and library data</strong> &mdash; kept until you delete
                      your account.</li>
                  <li><strong>Deleted library entries</strong> &mdash; a "removed" marker is
                      kept for up to 90 days so the deletion propagates to your other
                      devices, then it is purged automatically.</li>
                  <li><strong>Download records</strong> &mdash; kept until you delete your
                      account, at which point the records linked to your account are
                      erased.</li>
                  <li><strong>Content reports</strong> &mdash; kept indefinitely as
                      moderation records. They are keyed to an installation identifier, not
                      to your account.</li>
                  <li><strong>Content you submitted for review</strong> (YouTube imports and
                      suggestions, with any note) &mdash; kept as moderation records with no
                      fixed deletion date, until a moderator removes them. After you delete
                      your account they point only to an anonymous placeholder.</li>
                  <li><strong>Web server logs</strong> &mdash; deleted after 14 days.</li>
                  <li><strong>Application logs</strong> &mdash; our backend's own log. It can
                      contain search terms, account identifiers and, for some errors, an email
                      address. It currently has no fixed deletion date.</li>
                  <li><strong>Security and audit logs</strong> &mdash; retained indefinitely
                      for security and audit purposes, <strong>including after your account
                      is deleted</strong>. This is a deliberate exception to erasure, and it
                      is the one category of data that survives deletion in identifiable
                      form.</li>
                </ul>

                <h2>7. Deleting your account and your data</h2>
                <p>You can delete your account and its data at any time, permanently and
                without a waiting period:</p>
                <ul>
                  <li><strong>In the app:</strong>
                      Me &rarr; &#8942; &rarr; Profile &rarr; Delete account
                      (&#8942; is the three-dot menu at the top of the Me tab).</li>
                  <li><strong>On the web, without reinstalling the app:</strong>
                      <a href="/delete-account">%3$s/delete-account</a>.</li>
                  <li><strong>By email:</strong> <!--email_off--><a href="mailto:%1$s">%1$s</a><!--email_on-->, from the
                      address the account is registered with.</li>
                </ul>
                <p>Deletion destroys your sign-in record, your profile fields, your whole
                library, your download records and any personal access grants naming your
                account. It keeps the security and audit logs described in section&nbsp;6,
                content you submitted for review, records of moderation work done by moderator
                and administrator accounts, which keep the email address that acted, and an
                anonymous placeholder record carrying
                no personal data. See the
                <a href="/delete-account">deletion page</a> for the full breakdown.</p>

                <h2>8. Your rights</h2>
                <p>You have the right to access, correct,
                export, restrict or object to our use of your data, and to withdraw consent.
                You can view and correct your display name, date of birth, phone number and
                email address in the app under Me &rarr; &#8942; &rarr; Profile. For anything
                else, write to <!--email_off--><a href="mailto:%1$s">%1$s</a><!--email_on-->.
                You also have the right to complain to the Dutch Data Protection Authority, the
                <a href="https://autoriteitpersoonsgegevens.nl">Autoriteit Persoonsgegevens</a>,
                or to the supervisory authority where you live.</p>

                <h2>9. Children</h2>
                <p>FitrahTube is not for children under 13. We ask for a date of birth during
                sign-up and refuse any account that reports an age under 13; that account is
                deactivated immediately. If you believe a child under 13 has created an
                account, write to <!--email_off--><a href="mailto:%1$s">%1$s</a><!--email_on--> and we will delete it.</p>

                <h2>10. Security</h2>
                <p>All traffic is encrypted in transit. Authentication is delegated to Google
                Firebase; we never see or store your password. Sessions are stateless and
                access is revoked immediately when an account is blocked or deleted.</p>

                <h2>11. Changes</h2>
                <p>If we change this policy we will update the date at the top of this page.
                Material changes will also be announced in the app.</p>

                <h2>12. Contact</h2>
                <p><!--email_off--><a href="mailto:%1$s">%1$s</a><!--email_on--></p>
                """.formatted(CONTACT, LAST_UPDATED, "app.fitrahtube.com"));
    }

    // ── /terms ─────────────────────────────────────────────────────────────
    //
    // Open question for the owner. Same rule as /privacy: `//` only, never an
    // HTML comment inside the text block — that would be published.
    //
    // TODO(owner): these terms are a factual baseline derived from how the
    //   product actually behaves. Before relying on them commercially, have
    //   them reviewed and settle: the contracting legal entity, the governing
    //   law and forum, the limitation-of-liability and warranty-disclaimer
    //   wording, and the project's own software licence (see /licenses).

    @GetMapping(value = "/terms", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> terms() {
        return html("FitrahTube Terms of Service", """
                <h1>FitrahTube Terms of Service</h1>
                <p class="meta">Last updated: %2$s</p>

                <h2>1. What FitrahTube is</h2>
                <p>FitrahTube is an app for viewing a curated selection of publicly
                available YouTube content. Every channel, playlist and video in the
                catalogue is reviewed and approved by a moderator before it appears.
                FitrahTube is not affiliated with, endorsed by, or sponsored by YouTube or
                Google. Stichting Tarbiyah Consultancy, Almere, the Netherlands, is the operator of FitrahTube.</p>

                <h2>2. Using FitrahTube</h2>
                <p>You may use FitrahTube for personal, non-commercial viewing. You agree not
                to:</p>
                <ul>
                  <li>use the service to break the law, or to infringe anyone's rights;</li>
                  <li>attempt to gain unauthorised access to the service, other accounts, or
                      the systems behind them;</li>
                  <li>interfere with or overload the service, including by automated or
                      bulk requests;</li>
                  <li>redistribute or re-publish content obtained through the service in
                      breach of the rights of the content's owner.</li>
                </ul>

                <h2>3. Accounts</h2>
                <p>You must sign in to use FitrahTube. Your account also lets your library sync
                across devices. You must be at least <strong>13 years old</strong> to hold an
                account. You are responsible for keeping your sign-in credentials secure and
                for activity under your account. Provide accurate information; do not
                impersonate anyone.</p>

                <h2>4. Deleting your account</h2>
                <p>You may delete your account at any time, permanently, from
                Me &rarr; &#8942; &rarr; Profile &rarr; Delete account in the app, or from
                <a href="/delete-account">the account deletion page</a>. Deletion cannot be
                undone. See the <a href="/privacy">Privacy Policy</a> for exactly what is
                erased and what is retained.</p>

                <h2>5. Suspension and termination</h2>
                <p>We may block or remove an account that breaches these terms, that is used
                for abuse, or where we are legally required to do so.</p>

                <h2>6. Content and ownership</h2>
                <p>Videos, channels and playlists shown in FitrahTube belong to their
                respective owners and are subject to their own terms. FitrahTube provides
                access to publicly available content; it does not claim ownership of it.
                Content is offered as-is, and availability can change or disappear at any
                time without notice, because the underlying platform controls it. By using
                YouTube content in FitrahTube, including through the YouTube import,
                you agree to be bound by the <a href="https://www.youtube.com/t/terms">YouTube Terms of Service</a>.</p>

                <h2>7. Downloads and offline viewing</h2>
                <p>Downloading is provided for your personal offline viewing only. You are
                responsible for ensuring your use complies with applicable law and with the
                rights of the content's owner.</p>

                <h2>8. Third-party software</h2>
                <p>FitrahTube includes open-source components. See
                <a href="/licenses">Open-Source Licences</a>.</p>

                <h2>9. Availability</h2>
                <p>The service is provided on an "as is" and "as available" basis. We do not
                guarantee that it will be uninterrupted, error-free, or that any particular
                content will remain available.</p>

                <h2>10. Changes to these terms</h2>
                <p>We may update these terms. The date at the top of this page shows when
                they last changed. Continuing to use FitrahTube after a change means you
                accept the updated terms.</p>

                <h2>11. Contact</h2>
                <p><!--email_off--><a href="mailto:%1$s">%1$s</a><!--email_on--></p>
                """.formatted(CONTACT, LAST_UPDATED));
    }

    // ── /licenses ──────────────────────────────────────────────────────────
    //
    // Enumerated from android/app/build.gradle.kts (read-only).
    //
    // Licensing settled by the owner 2026-08-25: FitrahTube is GPLv3, which is what
    // linking NewPipeExtractor (GPL-3.0, and the app's playback engine) requires. The
    // LICENSE file at the repository root carries the full text, and the source is
    // public at github.com/talibfitrah/albunyaantube, which is how the GPLv3 s6
    // Corresponding Source offer is met. The statement below must stay consistent with
    // both -- if the repository ever goes private, this offer breaks and the licence
    // obligation is no longer satisfied.

    @GetMapping(value = "/licenses", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> licenses() {
        return html("FitrahTube Open-Source Licences", """
                <h1>Open-Source Licences</h1>
                <p class="meta">Last updated: %2$s</p>

                <p class="lede">FitrahTube is built on open-source software. The components
                below are included in the Android app, with the licence each is distributed
                under. Full licence texts are available from each project.</p>

                <div class="callout">
                  <h2>FitrahTube is free software</h2>
                  <p>FitrahTube is licensed under the
                  <strong>GNU General Public License, version 3</strong>. You may use, study,
                  share and modify it under those terms.</p>
                  <p>The complete corresponding source code is published at
                  <a href="https://github.com/talibfitrah/albunyaantube">github.com/talibfitrah/albunyaantube</a>,
                  where the full licence text is in the <code>LICENSE</code> file. If you
                  would rather receive the source another way, write to
                  <!--email_off--><a href="mailto:%1$s">%1$s</a><!--email_on--> and we will send it to you.</p>
                  <p>The app is GPLv3 because it builds on two copyleft components:</p>
                  <ul>
                    <li><strong>NewPipeExtractor</strong> (v0.26.5) &mdash;
                        GNU GPL v3.0. Used to read publicly available YouTube content.
                        <a href="https://github.com/TeamNewPipe/NewPipeExtractor">Project</a></li>
                    <li><strong>ffmpeg-kit-min-gpl</strong> (7.1.5) &mdash; a GPL-licensed
                        build of FFmpeg. Used to merge downloaded audio and video
                        tracks.</li>
                  </ul>
                </div>

                <h2>Apache License 2.0</h2>
                <ul>
                  <li>AndroidX &mdash; core-ktx 1.18.0, appcompat 1.8.0,
                      constraintlayout 2.2.2, recyclerview 1.4.0,
                      swiperefreshlayout 1.2.0, navigation 2.9.8, lifecycle 2.11.0,
                      fragment-ktx 1.9.0, paging 3.5.1, viewpager2 1.1.0,
                      datastore-preferences 1.2.1, work-runtime-ktx 2.11.2,
                      profileinstaller 1.4.1, mediarouter 1.8.1, hilt-work 1.4.0,
                      room 2.8.4 &mdash; The Android Open Source Project</li>
                  <li>AndroidX Media3 1.11.0 (ExoPlayer, HLS, DASH, UI, Session, Cronet
                      data source) &mdash; The Android Open Source Project</li>
                  <li>Material Components for Android &mdash; Google</li>
                  <li>Kotlin standard library, kotlinx-coroutines 1.11.0,
                      kotlinx-serialization &mdash; JetBrains</li>
                  <li>Firebase Android SDK (BoM 34.17.0, Authentication) &mdash; Google</li>
                  <li>Google Play services &mdash; Auth 21.6.0,
                      Cast Framework 22.3.1 &mdash; Google</li>
                  <li>Dagger and Hilt 2.58 &mdash; Google</li>
                  <li>Retrofit 2.11.0, Moshi 1.15.2, OkHttp 4.12.0 (logging-interceptor)
                      &mdash; Square, Inc.</li>
                  <li>Coil 2.7.0 &mdash; Coil Contributors</li>
                  <li>libphonenumber-android 8.13.55 &mdash; Michael Rozumyanskiy,
                      based on libphonenumber by Google</li>
                  <li>smart-exception-java 0.2.1 &mdash; Taner Sener</li>
                  <li>desugar_jdk_libs_nio 2.1.5 &mdash; Google</li>
                </ul>

                <h2>Backend components</h2>
                <p>The FitrahTube service additionally uses, all under the Apache License
                2.0: Spring Boot and Spring Framework (Pivotal/VMware), Firebase Admin SDK
                and Google Cloud Firestore (Google), Caffeine (Ben Manes), Jackson
                (FasterXML), Lettuce (Mark Paluch), Microsoft Graph SDK and Azure Identity
                (Microsoft) &mdash; together with the same GPLv3 NewPipeExtractor named
                above.</p>

                <p><a href="/terms">Terms of Service</a> &middot;
                <a href="/privacy">Privacy Policy</a> &middot;
                <a href="/delete-account">Delete your account</a></p>
                """.formatted(CONTACT, LAST_UPDATED));
    }

    // ── Shared shell ───────────────────────────────────────────────────────

    /**
     * Wrap a page body in a self-contained document. Inline CSS only, so the
     * page renders with no follow-up request; {@code prefers-color-scheme}
     * keeps it readable in either theme.
     */
    private static ResponseEntity<String> html(String title, String body) {
        String doc = """
                <!DOCTYPE html>
                <html lang="en"><head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%1$s — FitrahTube</title>
                <meta name="description" content="%1$s">
                <meta name="robots" content="index,follow">
                <style>
                :root{--bg:#ffffff;--fg:#1f2933;--muted:#5b6672;--rule:#e3e8ee;--link:#1d4ed8;--card:#f6f8fa}
                @media(prefers-color-scheme:dark){
                  :root{--bg:#0b0d12;--fg:#e4e7eb;--muted:#9ca3af;--rule:#242a35;--link:#7aa2f7;--card:#13161d}
                }
                *{box-sizing:border-box}
                body{margin:0;background:var(--bg);color:var(--fg);
                  font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;
                  font-size:16px;line-height:1.65}
                main{max-width:44rem;margin:0 auto;padding:2.5rem 1.25rem 4rem}
                h1{font-size:1.75rem;line-height:1.25;margin:0 0 .75rem}
                h2{font-size:1.2rem;margin:2rem 0 .5rem;padding-top:1rem;border-top:1px solid var(--rule)}
                h3{font-size:1rem;margin:1.25rem 0 .35rem}
                .callout h2,.callout h3{border:0;padding-top:0;margin-top:0}
                p,ul{margin:0 0 .9rem}
                ul{padding-left:1.25rem}
                li{margin-bottom:.4rem}
                a{color:var(--link)}
                code{background:var(--card);padding:.1em .35em;border-radius:4px;font-size:.9em}
                .lede{font-size:1.05rem;color:var(--fg)}
                .meta{color:var(--muted);font-size:.875rem;margin-bottom:1.5rem}
                .path{font-size:1.05rem;font-weight:600}
                .callout{background:var(--card);border:1px solid var(--rule);border-radius:10px;
                  padding:1.1rem 1.25rem;margin:1.25rem 0}
                footer{margin-top:3rem;padding-top:1rem;border-top:1px solid var(--rule);
                  color:var(--muted);font-size:.875rem}
                footer a{color:var(--muted)}
                </style>
                </head><body><main>
                %2$s
                <footer>FitrahTube &middot; <a href="/delete-account">Delete account</a>
                &middot; <a href="/privacy">Privacy</a>
                &middot; <a href="/terms">Terms</a>
                &middot; <a href="/licenses">Licences</a></footer>
                </main></body></html>
                """.formatted(title, body);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(doc);
    }
}
