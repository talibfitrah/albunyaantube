import AVFoundation
import InnerTubeKit
import SwiftUI
import UIKit
import WebKit

/// Rung 3 on screen (plan §6.4 row 3, spec §6.6 rung-3 row): YouTube's own IFrame player inside a
/// navigation-locked `WKWebView`.
///
/// Structured exactly like `PlayerHostView`: a SwiftUI wrapper that owns the chrome, a
/// `UIViewRepresentable` that owns the web view, and one `Coordinator` that owns the delegates, the
/// JS bridge and the lifecycle observer. Every DECISION it makes is `EmbedPolicy.swift`'s
/// (`EmbedPage` / `EmbedNavigationPolicy` / `EmbedMessage` / `EmbedErrorPolicy`) -- this file is the
/// glue, and glue is all it is.
///
/// What is deliberately ABSENT here, versus `PlayerScreen`'s `.ready`/`.rung2Progressive` branch:
/// the whole overlay control column (quality menu, captions menu, audio-language menu, audio-only
/// button, rung-2 pill) and PiP. Spec §6.6 hides every FitrahTube PLAYBACK control on this rung, and
/// there is no `AVPlayer` for any of them to act on. `PlayerToolbar` (favorite / share / report) and
/// `PlayerMetadataView` stay: none of those is a playback control, none is an overlay on the player,
/// and none has another route in from this screen.
struct EmbedRungView: View {
    let resolved: Resolved
    let model: PlayerViewModel
    let args: PlayerArgs

    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dismiss) private var dismiss

    /// Set by the bridge on IFrame state 0 (ENDED) and cleared by any other state, so a Replay puts
    /// the frame back on screen without a remount.
    @State private var ended = EmbedRungView.debugSeedEnded
    /// Bumped by Replay. A token rather than a shared mutable player handle: `updateUIView` is the
    /// one place allowed to talk to the web view, and a monotonic counter is the whole message.
    @State private var replayToken = 0
    /// Task 5 ruling: when the cover appears VoiceOver focus moves to Replay, so the announcement
    /// below is followed by the control it names rather than by wherever focus happened to be.
    @AccessibilityFocusState private var replayFocused: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // ABOVE the frame, never an overlay on it: YouTube's Required Minimum Functionality
                // rules forbid drawing FitrahTube chrome over the embedded player. This is also the
                // visible half of "a native->embed transition is never silent" (plan §6.6
                // Transitions) -- the audible half is `PlayerScreen`'s announcement.
                Text(String(localized: "player_embed_caption"))
                    .font(TypeScale.itemMeta)
                    .foregroundStyle(Color.textSecondary)
                    .padding(.horizontal, Spacing.md(widthClass))
                    .padding(.vertical, Spacing.sm)
                    .accessibilityIdentifier("player.embedCaption")

                ZStack {
                    EmbedWebView(videoId: Self.videoId(resolved), resolved: resolved,
                                 model: model, locale: Self.embedLocale(container.settings.resolvedLocale),
                                 replayToken: replayToken,
                                 // `|| debugSeedEnded` makes the screenshot rig's seeded cover
                                 // STICKY: with a network the IFrame reaches `playing` within a
                                 // second and would clear it out from under the capture. In Release
                                 // the flag is a compile-time `false`, so this is just `$0`.
                                 onEnded: { ended = Self.debugSeedEnded || $0 })
                    if ended { endCover }
                }
                // Plan §6.4 row 3's ≥ 200x200 pt floor is satisfied by CONSTRUCTION, not by a
                // `minHeight`: the frame is the full content column at 16:9, i.e. ≥ 320x180 on the
                // narrowest supported device and ≥ 200 tall from any column ≥ 356 pt. Nothing in
                // this app produces a narrower player column. CF-B3-1: re-check this when B4
                // parameterises the ratio -- 9:16 makes width the tight dimension, not height.
                .aspectRatio(16.0 / 9.0, contentMode: .fit)
                .background(Color.black)
                .animation(reduceMotion ? nil : .easeInOut, value: ended)
                // Task 5 ruling: the cover is a silent screen change for a VoiceOver user -- the
                // video simply stops. Announce it once (on the false->true edge only) with the
                // Replay button's own label, then put focus on that button.
                .onChange(of: ended) { _, isEnded in
                    guard isEnded else { return }
                    AccessibilityNotification.Announcement(String(localized: "player_embed_replay")).post()
                    replayFocused = true
                }

                #if DEBUG
                // `-fitrah-embed-debug-events` (Task 5 live pass): the bridge events and the
                // navigation lock's own verdicts, on screen, so an XCUITest with a REAL IFrame load
                // can read back what the web content process actually did. Compiled out of Release.
                if EmbedDebugLog.isEnabled {
                    Text(EmbedDebugLog.shared.text)
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundStyle(Color.textSecondary)
                        .padding(.horizontal, Spacing.md(widthClass))
                        .accessibilityIdentifier("player.embedDebugLog")
                }
                #endif

                PlayerToolbar(args: args)
                if verticalSizeClass != .compact {
                    PlayerMetadataView(args: args)
                }
            }
            .frame(maxWidth: Size.playerMaxWidth(widthClass))
            .frame(maxWidth: .infinity)
        }
        .background(Color.background.ignoresSafeArea())
    }

    /// Spec §6.6: "end screen covered by a FitrahTube 'Replay / Back' card on ENDED". OPAQUE and
    /// full-frame on purpose -- its whole reason to exist is that YouTube's end-screen
    /// recommendation cards are never visible and never tappable.
    private var endCover: some View {
        VStack(spacing: Spacing.md(widthClass)) {
            // The cover is NOT cleared here: `ended` is owned by the bridge, and the next state
            // message (1 playing) clears it. Clearing on tap uncovers YouTube's end screen for as
            // long as the `seekTo`/`playVideo` round trip takes -- exactly the frames the cover
            // exists to hide -- and leaves it uncovered for good if the round trip never lands.
            Button(String(localized: "player_embed_replay")) { replayToken += 1 }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("player.embedReplay")
            .accessibilityFocused($replayFocused)

            Button(String(localized: "back")) { dismiss() }
                // The cover is opaque black; the stock accent is too dark on it to read.
                .foregroundStyle(.white)
                .accessibilityIdentifier("player.embedBack")
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
        .transition(reduceMotion ? .identity : .opacity)
    }

    // MARK: - Pure decisions (called from the view above, pinned by `PlayerScreenEmbedTests`)

    /// IFrame API player states: -1 unstarted, 0 ENDED, 1 playing, 2 paused, 3 buffering, 5 cued.
    /// Only 0 raises the cover; `.ready`/`.error` never do (an error is a `StreamState` change, not
    /// a cover).
    static func showsEndCover(for event: EmbedMessage.Event) -> Bool {
        event == .state(0)
    }

    /// Ruling 19: device locale for `hl`, en fallback. `EmbedPage.html` defaults an unsupported
    /// tag to en on its own (a3302261 -- `hl` is JSON-encoded, not a security boundary), so this is
    /// belt and braces rather than the only guard; it stays because the CHOICE of catalog language
    /// belongs to the app, not to the page. Region is dropped: YouTube's `hl` wants a language.
    static func embedLocale(_ locale: Locale) -> String {
        let code = locale.language.languageCode?.identifier ?? "en"
        return ["en", "ar", "nl"].contains(code) ? code : "en"
    }

    private static func videoId(_ resolved: Resolved) -> String? {
        guard case .embed(let videoId) = resolved.stream else { return nil }
        return videoId
    }

    /// `-fitrah-fake-embed-ended` (DEBUG only): seeds the cover so the screenshot rig can capture it
    /// without a real IFrame load, a network and a video short enough to reach its end. Same
    /// technique as `PlayerViewModel.debugForceRecoveryExhausted`.
    private static var debugSeedEnded: Bool {
        #if DEBUG
        return ProcessInfo.processInfo.arguments.contains("-fitrah-fake-embed-ended")
        #else
        return false
        #endif
    }
}

/// The `WKWebView` itself. Nothing here decides anything (see `EmbedPolicy.swift`).
///
/// Internal rather than `private` only so `PlayerScreenEmbedTests` can reach `Coordinator` and
/// assert the navigation lock's `@objc` thunk exists (C1, Task 4 review). Nothing else refers to it.
struct EmbedWebView: UIViewRepresentable {
    /// Optional, and never defaulted to `""`: this branch only ever mounts for a `.embed` stream, so
    /// nil is unreachable -- and a placeholder id would be a SECOND unreachable path to reason
    /// about. Nil takes the same "refused substitution" route a bad id already takes (`load`).
    let videoId: String?
    let resolved: Resolved
    let model: PlayerViewModel
    let locale: String
    let replayToken: Int
    let onEnded: (Bool) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(videoId: videoId, resolved: resolved, model: model, locale: locale)
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        // No persistence at all: this is what makes `youtube-nocookie.com` mean what it says ACROSS
        // launches, and it is plan §6.3's cookie discipline by the one mechanism a `WKWebView`
        // offers (it has no `URLSession` to set `httpCookieAcceptPolicy` on).
        config.websiteDataStore = .nonPersistent()
        config.allowsInlineMediaPlayback = true
        // Plan §6.4 row 3: the authorising gesture is the tap on the video in the catalog that
        // opened this screen; this flag only stops WebKit from demanding a SECOND one before the
        // `playVideo()` that tap already asked for.
        config.mediaTypesRequiringUserActionForPlayback = []
        // Task 3 reconciliation 2: spec §6.6 hides PiP on this rung and YouTube API Services policy
        // III.I.9 forbids a background player for embed content -- a live PiP window would also
        // defeat the `didEnterBackground` pause below.
        config.allowsPictureInPictureMediaPlayback = false
        // `add(_:name:)` RETAINS its handler for the life of the configuration, so registering the
        // coordinator directly would mean it never deallocates. The proxy holds it weakly; the
        // registration itself is removed in `dismantleUIView`.
        config.userContentController.add(WeakScriptMessageProxy(context.coordinator),
                                         name: EmbedPage.handlerName)

        let web = WKWebView(frame: .zero, configuration: config)
        // Task 5: the ≥ 200x200 pt floor (plan §6.4 row 3) is asserted, not assumed -- the
        // identifier goes on the UIView, not on the SwiftUI ZStack around it, because only a real
        // UIKit view is guaranteed to surface as an XCUIElement with a readable `frame`.
        web.accessibilityIdentifier = "player.embedFrame"
        web.isOpaque = false
        web.backgroundColor = .black
        web.scrollView.isScrollEnabled = false
        web.allowsBackForwardNavigationGestures = false
        web.navigationDelegate = context.coordinator
        web.uiDelegate = context.coordinator
        // No `customUserAgent`: plan §6.3's fixed per-client UAs govern InnerTube API calls, and the
        // `web` client context in `remote-config-default.json` carries no `userAgent` field at all
        // (`RemoteConfig.swift:4` documents exactly that). `youtube-nocookie.com` also serves a
        // different player to a UA it does not read as a mobile browser, so WKWebView's stock iOS
        // Safari UA -- which carries no app or device token -- is both the correct and the only
        // available answer here.
        context.coordinator.onEnded = onEnded
        context.coordinator.start(web: web)
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {
        // Reassigned every pass, never captured once: the same discipline `PlayerHostView` uses for
        // `onPolicyAction`, so the binding this writes is always the current one.
        context.coordinator.onEnded = onEnded
        context.coordinator.replay(to: replayToken, web: web)
    }

    static func dismantleUIView(_ web: WKWebView, coordinator: Coordinator) {
        coordinator.teardown(web: web)
    }

    /// Owns the delegates, the bridge, the background observer and the reload budget. One lifecycle
    /// owner, like `PlayerHostView.Coordinator` -- and, like it, untested glue: every input is a
    /// live web content process's behaviour, which the unit target cannot produce.
    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
        private let resolved: Resolved
        private let model: PlayerViewModel
        /// Built ONCE, at mount. `EmbedPage.html` reads the bundled page off disk synchronously, and
        /// the `.reloadOnce` path must not repeat that on the main thread -- it re-loads this string.
        private let html: String?
        private var backgroundObserver: NSObjectProtocol?
        /// PER LOAD, and a user Retry gets a fresh one for free: Retry re-walks the ladder through
        /// `.loading`, which swaps `PlayerScreen`'s branch, dismantles this view and builds a new
        /// coordinator. Plan §6.6: retry once, then report -- never a loop.
        ///
        /// ONE budget shared by BOTH recoverable failures -- an IFrame error and a web content
        /// process termination -- deliberately: "retry once" is a property of this mount, not of
        /// each failure kind, so a page that crashes its process and then reports error 5 gets one
        /// reload between them, not two.
        private var alreadyReloaded = false
        private var lastReplayToken = 0
        var onEnded: ((Bool) -> Void)?

        init(videoId: String?, resolved: Resolved, model: PlayerViewModel, locale: String) {
            self.resolved = resolved
            self.model = model
            self.html = videoId.flatMap {
                EmbedPage.html(videoId: $0, locale: locale,
                               captionsPreferred: UIAccessibility.isClosedCaptioningEnabled)
            }
        }

        func start(web: WKWebView) {
            // CF-B2-8, "exactly one owner": while the embed is up the native host is gone, so the
            // embed owns the session. Configured HERE and again on `.ready`, because SwiftUI does
            // not guarantee `PlayerHostView.dismantleUIViewController` (whose `detach()`
            // deactivates the session) runs before this `makeUIView`; `.ready` arrives well after
            // any dismantle. Without `.playback` the hardware ringer switch silences a lecture.
            BackgroundPlaybackController.configureAudioSession()
            // Plan §6.4 row 3: `didEnterBackground` pauses the embed. No background continuation and
            // no Now Playing entry -- YouTube API Services policy III.I.9. `PlayerHostView`'s own
            // hooks (`onWillEnterForeground`, `onPolicyAction`) are deliberately untouched: that
            // host is not mounted on this branch, and CF-B2-7 forbids chaining work onto the
            // awaited foreground hook.
            backgroundObserver = NotificationCenter.default.addObserver(
                forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
            ) { [weak self, weak web] _ in
                MainActor.assumeIsolated { self?.pause(web: web) }
            }
            load(web: web)
        }

        func replay(to token: Int, web: WKWebView) {
            guard token != lastReplayToken else { return }
            lastReplayToken = token
            web.evaluateJavaScript("if (window.player) { player.seekTo(0, true); player.playVideo(); }")
        }

        func teardown(web: WKWebView) {
            if let backgroundObserver {
                NotificationCenter.default.removeObserver(backgroundObserver)
                self.backgroundObserver = nil
            }
            // The other half of the weak proxy: the configuration outlives this view, so an
            // un-removed registration is a leak with a dead target behind it.
            web.configuration.userContentController.removeScriptMessageHandler(forName: EmbedPage.handlerName)
            // Allowed by the navigation lock (`EmbedNavigationPolicy` accepts `about:blank`) --
            // deliberately loaded while the delegate is still installed, so the teardown goes
            // through the same lock everything else does. Same shape as Android's two headless
            // extractor web views: stop the player and drop the remote content before the view goes.
            if let blank = URL(string: "about:blank") { web.load(URLRequest(url: blank)) }
            web.navigationDelegate = nil
            web.uiDelegate = nil
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }

        // MARK: - Bridge

        nonisolated func userContentController(_ controller: WKUserContentController,
                                               didReceive message: WKScriptMessage) {
            // `WKScriptMessage` is not `Sendable`: decode it here, on the main thread WebKit
            // delivers on, and hop with only the resulting value -- the same shape
            // `BackgroundPlaybackController`'s notification observers use.
            MainActor.assumeIsolated {
                guard let event = EmbedMessage.parse(message.body) else { return }
                handle(event, web: message.webView)
            }
        }

        private func handle(_ event: EmbedMessage.Event, web: WKWebView?) {
            #if DEBUG
            EmbedDebugLog.shared.append("\(event)")
            #endif
            switch event {
            case .ready:
                // Second activation, deliberately (CF-B2-8's ordering race -- see `start`). The
                // cover is NOT cleared here: `.ready` says the player exists, not that it is
                // playing, and only a state message moves the cover.
                BackgroundPlaybackController.configureAudioSession()
                #if DEBUG
                // `-fitrah-embed-seek-to-end` (Task 5 live pass): drives a REAL ENDED through the
                // real bridge without needing a video short enough to sit through. Unlike
                // `-fitrah-fake-embed-ended` (which seeds the cover with no IFrame at all) this
                // proves state 0 actually arrives from YouTube's player.
                if ProcessInfo.processInfo.arguments.contains("-fitrah-embed-seek-to-end") {
                    web?.evaluateJavaScript(
                        "if (window.player) { player.seekTo(Math.max(0, player.getDuration() - 2), true); }")
                }
                #endif
            case .state:
                onEnded?(EmbedRungView.showsEndCover(for: event))
            case .error(let code):
                // Called exactly once per error: `EmbedErrorPolicy.decide` logs, so asking it
                // speculatively would put phantom errors in the log.
                apply(EmbedErrorPolicy.decide(code: code, alreadyReloaded: alreadyReloaded), web: web)
            }
        }

        private func apply(_ action: EmbedErrorAction, web: WKWebView?) {
            switch action {
            case .reloadOnce:
                alreadyReloaded = true
                // `web.reload()` on a string-loaded page is unreliable (there is no navigable URL to
                // reload), so the page is re-loaded from the string built at mount.
                if let web { load(web: web) }
            case .fail, .unplayable:
                // DEFERRED, not synchronous: `load` reaches this from `start(web:)`, which runs
                // inside `makeUIView` -- and publishing a `StreamState` there mutates the state
                // SwiftUI is in the middle of reading ("Modifying state during view update"), which
                // swaps `PlayerScreen`'s branch out from under the view being built. Every other
                // caller arrives from a WebKit callback where the hop is a no-op turn.
                Task { @MainActor in model.applyEmbedAction(action) }
            }
        }

        private func load(web: WKWebView) {
            guard let html else {
                // A refused substitution (an id that failed validation, or no id at all) is never
                // followed by an unvalidated fallback -- the rung reports and loads nothing at all.
                // Through `apply`, so this shares the one deferred-mutation sink (I1).
                apply(.fail(messageKey: "player_error_message"), web: web)
                return
            }
            // `loadHTMLString(_:baseURL:)`, NEVER `loadFileURL`: the https base URL is what makes
            // WebKit send a Referer, without which the IFrame API answers error 153.
            web.loadHTMLString(html, baseURL: EmbedPage.baseURL)
        }

        private func pause(web: WKWebView?) {
            web?.evaluateJavaScript("if (window.player) { player.pauseVideo(); }")
        }

        // MARK: - Navigation lock (plan §6.4 row 3, §6.10, §9's "Unrestricted Web Access: No")

        // `@MainActor @Sendable` is NOT decoration: the SDK declares the handler with both, and
        // this is an OPTIONAL protocol requirement -- a signature that only nearly matches compiles
        // with a warning, gets NO `@objc` thunk, and is never called, so WebKit silently allows
        // every navigation. Pinned by `PlayerScreenEmbedTests.theNavigationLockIsInstalledOnTheCoordinator`.
        func webView(_ web: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void) {
            // `targetFrame == nil` is a NEW-WINDOW navigation -- treated as main frame, i.e.
            // cancelled, which is what closes the "Watch on YouTube" / share / end-screen escapes.
            let isMainFrame = action.targetFrame?.isMainFrame ?? true
            let allowed = EmbedNavigationPolicy.allows(url: action.request.url,
                                                       isMainFrame: isMainFrame,
                                                       baseURL: EmbedPage.baseURL)
            #if DEBUG
            EmbedDebugLog.shared.append(
                "\(allowed ? "allow" : "CANCEL") \(isMainFrame ? "main" : "sub") \(action.request.url?.absoluteString ?? "nil")")
            #endif
            decisionHandler(allowed ? .allow : .cancel)
        }

        /// `target="_blank"` opens nothing. Without this the lock above is one `window.open` short
        /// of complete.
        func webView(_ web: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                     for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            #if DEBUG
            EmbedDebugLog.shared.append("CANCEL window.open \(action.request.url?.absoluteString ?? "nil")")
            #endif
            return nil
        }

        func webViewWebContentProcessDidTerminate(_ web: WKWebView) {
            apply(EmbedErrorPolicy.decideProcessTermination(alreadyReloaded: alreadyReloaded), web: web)
        }
    }
}

/// Forwards script messages without retaining the target. `WKUserContentController.add(_:name:)`
/// holds its handler strongly for the life of the configuration, which the web view owns -- so a
/// coordinator registered directly would outlive the view forever.
@MainActor
private final class WeakScriptMessageProxy: NSObject, WKScriptMessageHandler {
    private weak var target: (any WKScriptMessageHandler)?

    init(_ target: any WKScriptMessageHandler) {
        self.target = target
    }

    nonisolated func userContentController(_ controller: WKUserContentController,
                                           didReceive message: WKScriptMessage) {
        // WebKit delivers on the main thread; `WKScriptMessage` is not `Sendable`, so the hop is
        // `assumeIsolated` rather than a `Task` (same shape as `EmbedWebView.Coordinator`'s).
        MainActor.assumeIsolated { target?.userContentController(controller, didReceive: message) }
    }
}

#if DEBUG
/// Task 5's live-pass instrument, and nothing else: a bounded tail of what the web content process
/// did (bridge events in, navigation verdicts out), rendered under the frame only when
/// `-fitrah-embed-debug-events` is passed. It exists because an XCUITest cannot see inside a
/// `WKWebView` -- the IFrame's own chrome exposes no elements -- so without this the live checks
/// could only assert "the app did not crash". Compiled out of Release entirely.
@Observable
@MainActor
final class EmbedDebugLog {
    static let shared = EmbedDebugLog()
    static let isEnabled = ProcessInfo.processInfo.arguments.contains("-fitrah-embed-debug-events")

    private(set) var text = ""
    private var lines: [String] = []

    func append(_ line: String) {
        guard Self.isEnabled else { return }
        lines.append(line)
        // A bounded tail: a live IFrame emits a steady trickle of subframe navigations, and an
        // unbounded log would push the toolbar off screen inside a minute.
        lines = lines.suffix(12)
        text = lines.joined(separator: "\n")
    }
}
#endif
