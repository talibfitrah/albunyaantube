import Foundation
import Testing
import WebKit
@testable import FitrahTube

/// B3 task 3 (`docs/superpowers/plans/2026-08-27-ios-phase2b3-embed-safemode.md`): every decision
/// the embed rung makes is a pure function of its inputs -- what HTML gets loaded, which
/// navigations are allowed, what a bridge message means, what an IFrame error code does. All four
/// are pinned here, before any `WKWebView` exists, so task 4 is glue with nothing to reason about.
@Suite(.perTest)
struct EmbedPolicyTests {

    // MARK: - EmbedPage

    @Test func htmlSubstitutesOnlyAValidVideoIdAndLocale() {
        #expect(EmbedPage.html(videoId: "dQw4w9WgXcQ", locale: "ar", captionsPreferred: false)?
            .contains("dQw4w9WgXcQ") == true)
        // The one injection point in this app that puts a value inside a <script>. A validated
        // 11-char id is the whole defence -- escaping is not attempted, refusal is.
        #expect(EmbedPage.html(videoId: "\";alert(1);//", locale: "en", captionsPreferred: false) == nil)
        #expect(EmbedPage.html(videoId: "short", locale: "en", captionsPreferred: false) == nil)
    }

    @Test func htmlDefaultsUnsupportedHlToEnglish() {
        // `hl` is JSON-encoded, not a security boundary (unlike `videoId`, which stays strict and
        // nil-returning): any value outside {en, ar, nl} -- including one shaped like an injection
        // attempt -- just defaults to "en" rather than failing the whole embed.
        #expect(EmbedPage.html(videoId: "dQw4w9WgXcQ", locale: "fr", captionsPreferred: false)?
            .contains("\"hl\":\"en\"") == true)
        #expect(EmbedPage.html(videoId: "dQw4w9WgXcQ", locale: "en-US\";x", captionsPreferred: false)?
            .contains("\"hl\":\"en\"") == true)
    }

    @Test func htmlCarriesThePlayerVarsPlan64RequiresAndNothingElse() {
        let html = EmbedPage.html(videoId: "dQw4w9WgXcQ", locale: "nl", captionsPreferred: true)!
        #expect(html.contains("playsinline"))
        #expect(html.contains("enablejsapi"))
        #expect(html.contains("rel"))
        #expect(html.contains("youtube-nocookie.com"))
        #expect(html.contains("hl") && html.contains("nl"))
        #expect(html.contains("cc_load_policy"))          // captionsPreferred: true
        #expect(EmbedPage.html(videoId: "dQw4w9WgXcQ", locale: "nl", captionsPreferred: false)!
            .contains("cc_load_policy") == false)
        // 2.5.2 / plan §6.14: the ONLY remote script is YouTube's own IFrame API.
        let scripts = html.components(separatedBy: "src=\"").dropFirst().map { $0.prefix(while: { $0 != "\"" }) }
        #expect(scripts == ["https://www.youtube.com/iframe_api"])
    }

    @Test func htmlPinsTheOriginPlayerVarToASchemeAndHostOnlyOrigin() {
        // Ruled (review round 1): YouTube's IFrame API docs specify `origin` as scheme+host only --
        // a path never matches. `baseURL` (with `/embed`) stays the `loadHTMLString` base and the
        // navigation lock's one accepted URL; `origin` is the app-owned https origin, not youtube.com
        // and not `baseURL`'s full path.
        let html = EmbedPage.html(videoId: "dQw4w9WgXcQ", locale: "en", captionsPreferred: false)!
        #expect(html.contains("\"origin\":\"https://app.fitrahtube.com\""))
        #expect(html.contains("https://app.fitrahtube.com/embed") == false)
        #expect(EmbedPage.baseURL.absoluteString == "https://app.fitrahtube.com/embed")
    }

    @Test func htmlAutoplaysOnReadyAndReportsTheFourEvents() {
        // Ruled: autoplay is driven from `onReady`, not the `autoplay` player var -- the var races
        // the API's own readiness and is ignored on iOS without a user gesture in some builds.
        let html = EmbedPage.html(videoId: "dQw4w9WgXcQ", locale: "en", captionsPreferred: false)!
        #expect(html.contains("playVideo()"))
        #expect(html.contains("onReady"))
        #expect(html.contains("onStateChange"))
        #expect(html.contains("onError"))
        // The bridge is name-spaced, and the name lives in exactly one place.
        #expect(html.contains("window.webkit.messageHandlers.\(EmbedPage.handlerName)"))
        #expect(html.contains("__VIDEO_ID__") == false)   // every placeholder substituted
        #expect(html.contains("__HANDLER__") == false)
        #expect(html.contains("__PLAYER_VARS__") == false)
    }

    // MARK: - EmbedNavigationPolicy

    @Test func navigationLockCancelsEveryMainFrameNavigationOffTheBundledPage() {
        let base = EmbedPage.baseURL
        // Allowed: the bundled page itself, and every SUBFRAME navigation -- the IFrame is a
        // subframe and YouTube navigates it constantly; cancelling those breaks the player.
        #expect(EmbedNavigationPolicy.allows(url: base, isMainFrame: true, baseURL: base))
        #expect(EmbedNavigationPolicy.allows(url: URL(string: "about:blank"), isMainFrame: true, baseURL: base))
        #expect(EmbedNavigationPolicy.allows(url: URL(string: "https://www.youtube-nocookie.com/embed/x")!,
                                             isMainFrame: false, baseURL: base))
        // Cancelled (plan §6.10): the title, the logo, "Watch on YouTube", share, end-screen cards.
        #expect(EmbedNavigationPolicy.allows(url: URL(string: "https://www.youtube.com/watch?v=x")!,
                                             isMainFrame: true, baseURL: base) == false)
        #expect(EmbedNavigationPolicy.allows(url: URL(string: "https://accounts.google.com/signin")!,
                                             isMainFrame: true, baseURL: base) == false)
        #expect(EmbedNavigationPolicy.allows(url: URL(string: "javascript:alert(1)")!,
                                             isMainFrame: true, baseURL: base) == false)
        // Non-http(s) top-frame schemes a malicious page could pivot to: same denial path as
        // `javascript:` -- scheme mismatch, no allowlist to fall into.
        #expect(EmbedNavigationPolicy.allows(url: URL(string: "data:text/html,<script>alert(1)</script>")!,
                                             isMainFrame: true, baseURL: base) == false)
        #expect(EmbedNavigationPolicy.allows(url: URL(string: "blob:https://app.fitrahtube.com/x")!,
                                             isMainFrame: true, baseURL: base) == false)
        #expect(EmbedNavigationPolicy.allows(url: nil, isMainFrame: true, baseURL: base) == false)
        // Same host, wrong scheme: still cancelled. An http downgrade is not the bundled page.
        #expect(EmbedNavigationPolicy.allows(url: URL(string: "http://app.fitrahtube.com/embed")!,
                                             isMainFrame: true, baseURL: base) == false)
    }

    // MARK: - EmbedMessage

    @Test func bridgeMessagesParse() {
        #expect(EmbedMessage.parse(["event": "ready"]) == .ready)
        #expect(EmbedMessage.parse(["event": "state", "state": 0]) == .state(0))
        #expect(EmbedMessage.parse(["event": "error", "code": 150]) == .error(150))
        #expect(EmbedMessage.parse(["event": "state"]) == nil)          // malformed
        #expect(EmbedMessage.parse("state") == nil)                     // not a dictionary
        #expect(EmbedMessage.parse(["event": "navigate", "url": "x"]) == nil)  // unknown event, dropped
    }

    @Test func bridgeMessagesTolerateWebKitsNSNumberBoxing() {
        // `WKScriptMessage.body` hands JS numbers over as `NSNumber`, not `Int` -- a plain
        // `as? Int` cast on a double-backed NSNumber is what silently drops a real state change.
        #expect(EmbedMessage.parse(["event": "state", "state": NSNumber(value: 1)]) == .state(1))
        #expect(EmbedMessage.parse(["event": "error", "code": NSNumber(value: 101.0)]) == .error(101))
    }

    // MARK: - EmbedErrorPolicy

    @Test func errorCodesMapExactlyAsPlan66Says() {
        // 100 -> removed. Terminal, distinct copy.
        #expect(EmbedErrorPolicy.decide(code: 100, alreadyReloaded: false, safeMode: false)
            == .fail(messageKey: "player_embed_removed"))
        // 101/150 -> creator only allows it on YouTube, + Open in YouTube UNLESS Safe Mode.
        #expect(EmbedErrorPolicy.decide(code: 101, alreadyReloaded: false, safeMode: false)
            == .offerYouTube(messageKey: "player_embed_owner_only"))
        #expect(EmbedErrorPolicy.decide(code: 150, alreadyReloaded: false, safeMode: false)
            == .offerYouTube(messageKey: "player_embed_owner_only"))
        #expect(EmbedErrorPolicy.decide(code: 150, alreadyReloaded: false, safeMode: true)
            == .fail(messageKey: "player_embed_owner_only"))
        // 2/5/153 -> retry once, then give up. 153 is a missing Referer, i.e. OUR bug -- one reload
        // covers a transient load failure and the second occurrence is worth surfacing, not looping.
        for code in [2, 5, 153, 999] {
            #expect(EmbedErrorPolicy.decide(code: code, alreadyReloaded: false, safeMode: false) == .reloadOnce)
            #expect(EmbedErrorPolicy.decide(code: code, alreadyReloaded: true, safeMode: false)
                == .fail(messageKey: "player_error_message"))
        }
        // 100 and 101/150 are terminal on the FIRST occurrence: a reload cannot un-remove a video.
        #expect(EmbedErrorPolicy.decide(code: 100, alreadyReloaded: true, safeMode: false)
            == .fail(messageKey: "player_embed_removed"))
        // Content-process termination: reload once, then rung 4 (plan §6.4 row 3), Safe Mode excepted.
        #expect(EmbedErrorPolicy.decideProcessTermination(alreadyReloaded: false, safeMode: false) == .reloadOnce)
        #expect(EmbedErrorPolicy.decideProcessTermination(alreadyReloaded: true, safeMode: false)
            == .offerYouTube(messageKey: "player_error_generic"))
        #expect(EmbedErrorPolicy.decideProcessTermination(alreadyReloaded: true, safeMode: true)
            == .fail(messageKey: "player_error_generic"))
    }

    // MARK: - The one non-pure test

    @MainActor
    @Test(.timeLimit(.minutes(1))) func theBridgeRoundTripsThroughARealWebView() async {
        // The one non-pure test in this plan. It uses a LOCAL html string, never the bundled page
        // and never the network -- what it proves is that the handler name, the message shape and
        // `EmbedMessage.parse` agree end to end, which is the seam a typo silently breaks.
        //
        // It runs headless: the unit-test bundle is hosted by FitrahTube (`project.yml`'s
        // `FitrahTubeTests -> dependencies: target: FitrahTube` gives it a TEST_HOST), so the test
        // executes inside a live UIApplication with a running main run loop, and the awaited
        // continuation lets that run loop pump while WebKit spins up its content process. The view
        // never enters a window -- WKWebView evaluates page JS regardless.
        let config = WKWebViewConfiguration()
        let recorder = MessageRecorder()
        config.userContentController.add(recorder, name: EmbedPage.handlerName)
        let web = WKWebView(frame: .zero, configuration: config)
        web.loadHTMLString("""
        <script>
        window.webkit.messageHandlers.\(EmbedPage.handlerName).postMessage({event: "error", code: 150});
        </script>
        """, baseURL: EmbedPage.baseURL)
        let event = await recorder.next()
        #expect(event == .error(150))
        config.userContentController.removeScriptMessageHandler(forName: EmbedPage.handlerName)
    }
}

/// Funnels one bridge message through `EmbedMessage.parse` into an awaiting continuation.
@MainActor
private final class MessageRecorder: NSObject, WKScriptMessageHandler {
    private var pending: [EmbedMessage.Event] = []
    private var waiter: CheckedContinuation<EmbedMessage.Event, Never>?

    nonisolated func userContentController(_ controller: WKUserContentController,
                                           didReceive message: WKScriptMessage) {
        // WKScriptMessage is not Sendable: decode it here, on the main thread WebKit delivers on.
        MainActor.assumeIsolated {
            guard let event = EmbedMessage.parse(message.body) else { return }
            if let waiter {
                self.waiter = nil
                waiter.resume(returning: event)
            } else {
                pending.append(event)
            }
        }
    }

    func next() async -> EmbedMessage.Event {
        if !pending.isEmpty { return pending.removeFirst() }
        return await withCheckedContinuation { waiter = $0 }
    }
}
