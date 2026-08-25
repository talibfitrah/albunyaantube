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
    private static final String LAST_UPDATED = "25 August 2026";

    // ── /delete-account ────────────────────────────────────────────────────
    //
    // TODO(owner): THE IN-APP PATH DESCRIBED BELOW DOES NOT EXIST YET.
    //   Verified 2026-08-25: grepping android/app/src/main/res/values/strings.xml
    //   and android/app/src/main/java/com/albunyaan/tube/ui/settings/ for
    //   "delete account" returns nothing — the Android Settings screen has no
    //   delete-account entry, so "Settings -> Account -> Delete account" is a
    //   forward reference to UI that still has to be built against the new
    //   DELETE /api/account/me endpoint. Either ship that UI at the same exact
    //   wording, or change the wording here to match whatever the app ends up
    //   calling it. Google Play policy 13327111 requires BOTH halves (in-app
    //   AND web), so the app-side control is not optional. The email route
    //   below works today regardless.

    @GetMapping(value = "/delete-account", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> deleteAccount() {
        return html("Delete your FitrahTube account", """
                <h1>Delete your FitrahTube account</h1>
                <p class="lede">FitrahTube (package <code>com.albunyaan.tube</code>) lets you
                delete your account and its data permanently. There is no waiting period and
                no grace period &mdash; once the deletion runs, it cannot be undone.</p>

                <div class="callout">
                  <h2>Option 1 &mdash; delete it in the app</h2>
                  <p>Open FitrahTube and go to:</p>
                  <p class="path"><strong>Settings &rarr; Account &rarr; Delete account</strong></p>
                  <p>Confirm when prompted. Your account is deleted immediately and you are
                  signed out.</p>
                </div>

                <div class="callout">
                  <h2>Option 2 &mdash; ask us to delete it</h2>
                  <p>If you have already uninstalled the app, or you cannot sign in, email us
                  and we will delete the account for you:</p>
                  <p class="path"><a href="mailto:%1$s?subject=Account%%20deletion%%20request">%1$s</a></p>
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
                      email address that performed the action. Google's own policy permits
                      retaining data for security and audit purposes, and we rely on these
                      logs to investigate abuse and to prove that a deletion actually
                      happened.</li>
                  <li><strong>An anonymous placeholder record.</strong> Your account
                      identifier is kept as an empty marker with no personal data attached
                      &mdash; no email, no name, no date of birth, no phone number. It exists
                      only so that content you submitted for review does not point at a
                      missing record. Once your sign-in record is destroyed, that identifier
                      cannot be linked back to you.</li>
                  <li><strong>Content that was approved into the public catalogue.</strong>
                      Channels, playlists and videos are public YouTube content and are not
                      personal data about you.</li>
                </ul>

                <h2>If you only want to stop using the app</h2>
                <p>You do not need to delete your account to stop using FitrahTube &mdash;
                you can simply sign out or uninstall it. Deletion is permanent; uninstalling
                is not.</p>

                <p>Questions? <a href="mailto:%1$s">%1$s</a> &middot;
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

    @GetMapping(value = "/privacy", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> privacy() {
        return html("FitrahTube Privacy Policy", """
                <h1>FitrahTube Privacy Policy</h1>
                <p class="meta">Last updated: %2$s</p>

                <p class="lede">FitrahTube is an ad-free, curated video app. This policy
                describes what the FitrahTube app and its backend collect, why, who it is
                shared with, how long it is kept, and how to have it deleted. It covers the
                Android app (package <code>com.albunyaan.tube</code>) and the service at
                <code>app.fitrahtube.com</code>.</p>

                <!-- TODO(owner): confirm the legal entity name, registered address and
                     data-controller identity, and whether an EU/UK representative or a
                     DPO must be named. Left generic here because the repository does not
                     record it anywhere. -->
                <h2>1. Who is responsible</h2>
                <p>FitrahTube is operated by the FitrahTube team. For any privacy question,
                or to exercise any right described below, contact
                <a href="mailto:%1$s">%1$s</a>.</p>

                <h2>2. What we collect</h2>

                <h3>2.1 Account information</h3>
                <p>You can browse FitrahTube without an account. If you create one, we
                collect:</p>
                <ul>
                  <li><strong>Email address</strong> and, for email/password sign-in, a
                      password. Passwords are handled entirely by Google Firebase
                      Authentication and are never stored on our servers.</li>
                  <li><strong>Display name.</strong></li>
                  <li><strong>Date of birth.</strong> Used once, to check that you are at
                      least 13. Accounts that report an age under 13 are refused and
                      immediately deactivated.</li>
                  <li><strong>Phone number.</strong> Stored as entered; we do not send it an
                      SMS or verify it.</li>
                  <li><strong>Account state:</strong> role, status, creation and last sign-in
                      timestamps.</li>
                  <li>If you sign in with Google, Firebase gives us your email address, a
                      user identifier and whether that address is verified. We do not receive
                      your Google password or your Google contacts.</li>
                </ul>

                <h3>2.2 Your library</h3>
                <p>If you sign in, the channels you subscribe to, the playlists you save and
                the videos you mark as favourites are stored against your account so they
                sync between your devices. Each entry stores the YouTube identifier, title,
                thumbnail and the time you added it.</p>

                <h3>2.3 Downloads</h3>
                <p>When you download a video for offline viewing, we record the video
                identifier, the chosen quality, an estimated file size, a coarse device
                category and a timestamp. If you are signed in, this record is linked to your
                account; if you are not, it is recorded anonymously.</p>

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
                installation, not to your account, so that reporting works whether or not
                you are signed in. Because a report is not linked to an account, deleting
                your account does not delete reports you submitted.</p>

                <h3>2.6 Security and audit logs</h3>
                <p>We record security-relevant events &mdash; account status changes,
                moderation decisions, profile edits and account deletions &mdash; together
                with the account identifier and email address that performed them.</p>

                <h2>3. What we do NOT collect</h2>
                <p>These are absences we have verified in our own code, not merely
                intentions:</p>
                <ul>
                  <li><strong>No IP address logging.</strong> We do not record or store the
                      IP address of app or web requests.</li>
                  <li><strong>No location data</strong> of any kind &mdash; no GPS, no
                      geolocation lookup, no country inference.</li>
                  <li><strong>No advertising identifiers</strong> (no GAID, no IDFA) and no
                      device fingerprinting.</li>
                  <li><strong>No third-party analytics, crash-reporting or advertising
                      SDKs</strong> &mdash; no Google Analytics, no Firebase Analytics, no
                      Crashlytics, no Sentry, no Facebook SDK. FitrahTube shows no ads.</li>
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

                <h2>5. Who it is shared with</h2>
                <ul>
                  <li><strong>Google (Firebase Authentication and Cloud Firestore).</strong>
                      Our authentication and database provider. All of the data described in
                      section&nbsp;2 is stored on Google's infrastructure on our behalf.</li>
                  <li><strong>Microsoft (Microsoft Graph mail).</strong> When we send you a
                      password-reset or email-verification message, your email address and
                      that message pass through Microsoft's mail service. Nothing else is
                      sent.</li>
                  <li><strong>YouTube (Google).</strong> To fetch video, channel and playlist
                      metadata and playable streams. These requests are made <em>by our
                      server</em>, not from your device, and carry only the content
                      identifier or search term. <strong>No account identifier, email
                      address or device identifier is attached</strong>, and the language and
                      country sent are fixed values, not yours.</li>
                </ul>
                <p>We may also disclose data where we are legally required to do so.</p>

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
                  <li><strong>Security and audit logs</strong> &mdash; retained indefinitely
                      for security and audit purposes, <strong>including after your account
                      is deleted</strong>. This is a deliberate exception to erasure, and it
                      is the one category of data that survives deletion in identifiable
                      form.</li>
                </ul>
                <!-- TODO(owner): audit-log retention is currently unbounded (there is no GC
                     job for the audit_logs collection). If a fixed maximum retention period
                     is required for GDPR proportionality, set one and state it here. -->

                <h2>7. Deleting your account and your data</h2>
                <p>You can delete your account and its data at any time, permanently and
                without a waiting period:</p>
                <ul>
                  <li><strong>In the app:</strong> Settings &rarr; Account &rarr; Delete
                      account.</li>
                  <li><strong>On the web, without reinstalling the app:</strong>
                      <a href="/delete-account">%3$s/delete-account</a>.</li>
                  <li><strong>By email:</strong> <a href="mailto:%1$s">%1$s</a>, from the
                      address the account is registered with.</li>
                </ul>
                <p>Deletion destroys your sign-in record, your profile fields, your whole
                library, your download records and any personal access grants naming your
                account. It keeps the security and audit logs described in section&nbsp;6,
                and an anonymous placeholder record carrying no personal data. See the
                <a href="/delete-account">deletion page</a> for the full breakdown.</p>

                <h2>8. Your rights</h2>
                <p>Depending on where you live, you may have the right to access, correct,
                export, restrict or object to our use of your data, and to withdraw consent.
                You can view and correct your profile in the app under Settings. For anything
                else, write to <a href="mailto:%1$s">%1$s</a>.</p>
                <!-- TODO(owner): confirm the lead supervisory authority to name for the
                     right-to-complain disclosure (the app ships in en/ar/nl, which suggests
                     an EU nexus), and whether a self-service data export is required. -->

                <h2>9. Children</h2>
                <p>FitrahTube is not for children under 13. We ask for a date of birth during
                sign-up and refuse any account that reports an age under 13; that account is
                deactivated immediately. If you believe a child under 13 has created an
                account, write to <a href="mailto:%1$s">%1$s</a> and we will delete it.</p>

                <h2>10. Security</h2>
                <p>All traffic is encrypted in transit. Authentication is delegated to Google
                Firebase; we never see or store your password. Sessions are stateless and
                access is revoked immediately when an account is blocked or deleted.</p>

                <h2>11. Changes</h2>
                <p>If we change this policy we will update the date at the top of this page.
                Material changes will also be announced in the app.</p>

                <h2>12. Contact</h2>
                <p><a href="mailto:%1$s">%1$s</a></p>
                """.formatted(CONTACT, LAST_UPDATED, "app.fitrahtube.com"));
    }

    // ── /terms ─────────────────────────────────────────────────────────────

    @GetMapping(value = "/terms", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> terms() {
        return html("FitrahTube Terms of Service", """
                <h1>FitrahTube Terms of Service</h1>
                <p class="meta">Last updated: %2$s</p>

                <!-- TODO(owner): these terms are a factual baseline derived from how the
                     product actually behaves. Before relying on them commercially, have
                     them reviewed and settle: the contracting legal entity, the governing
                     law and forum, the limitation-of-liability and warranty-disclaimer
                     wording, and the project's own software licence (see /licenses). -->

                <h2>1. What FitrahTube is</h2>
                <p>FitrahTube is an ad-free client for viewing a curated selection of
                publicly available YouTube content. Every channel, playlist and video in the
                catalogue is reviewed and approved by a moderator before it appears.
                FitrahTube is not affiliated with, endorsed by, or sponsored by YouTube or
                Google.</p>

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
                <p>You do not need an account to browse. An account lets your library sync
                across devices. You must be at least <strong>13 years old</strong> to hold an
                account. You are responsible for keeping your sign-in credentials secure and
                for activity under your account. Provide accurate information; do not
                impersonate anyone.</p>

                <h2>4. Deleting your account</h2>
                <p>You may delete your account at any time, permanently, from Settings &rarr;
                Account &rarr; Delete account in the app, or from
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
                time without notice, because the underlying platform controls it.</p>

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
                <p><a href="mailto:%1$s">%1$s</a></p>
                """.formatted(CONTACT, LAST_UPDATED));
    }

    // ── /licenses ──────────────────────────────────────────────────────────
    //
    // Enumerated from android/app/build.gradle.kts (read-only).
    //
    // TODO(owner): LICENSING DECISION REQUIRED — DO NOT SHIP WITHOUT REVIEW.
    //   1. The repository has NO LICENSE file. FitrahTube's own licence is
    //      therefore undetermined. Nothing on this page states one, deliberately.
    //   2. NewPipeExtractor is GPLv3 and ffmpeg-kit-min-gpl is a GPL build.
    //      Linking GPLv3 code into a distributed Android application normally
    //      makes the combined work subject to GPLv3, which requires offering the
    //      Corresponding Source to recipients (GPLv3 s6). Neither this page nor
    //      the app currently makes that offer.
    //   Decide, with legal input: (a) FitrahTube's own licence, (b) whether the
    //   Corresponding Source will be published or offered on request, and (c)
    //   whether ffmpeg-kit-min-gpl can be swapped for an LGPL build. Then add
    //   the resulting statement to this page.

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
                  <h2>Copyleft components</h2>
                  <p>Two of the components below are distributed under the
                  <strong>GNU General Public License</strong>, which carries source-code
                  obligations that the permissive licences above do not:</p>
                  <ul>
                    <li><strong>NewPipeExtractor</strong> (v0.26.5) &mdash;
                        GNU GPL v3.0. Used to read publicly available YouTube metadata and
                        stream URLs.
                        <a href="https://github.com/TeamNewPipe/NewPipeExtractor">Project</a></li>
                    <li><strong>ffmpeg-kit-min-gpl</strong> (7.1.5) &mdash; a GPL-licensed
                        build of FFmpeg. Used to merge downloaded audio and video
                        tracks.</li>
                  </ul>
                  <p>For any question about these licences or about source code, write to
                  <a href="mailto:%1$s">%1$s</a>.</p>
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
