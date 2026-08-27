import Foundation

/// The embed rung's four decisions, all pure and all testable without a browser
/// (`docs/superpowers/plans/2026-08-27-ios-phase2b3-embed-safemode.md` task 3):
/// what HTML gets loaded, which navigations are allowed, what a bridge message means, and what an
/// IFrame error code does. `EmbedRungView` (task 4) is glue over these -- it decides nothing.

/// The bundled page and its one substitution point.
enum EmbedPage {
    /// `loadHTMLString(_:baseURL:)` with a real https base is what makes WebKit send a Referer;
    /// without one the IFrame API answers 153 ("no Referer"), which is why this is an app-owned
    /// https origin and not `about:blank` (plan §6.4 row 3). It is also the `origin` player var and
    /// the only main-frame URL the navigation lock accepts.
    static let baseURL = URL(string: "https://app.fitrahtube.com/embed")!

    /// The `window.webkit.messageHandlers.<name>` namespace. Declared once: `embed.html`'s JS reads
    /// it through the `__HANDLER__` placeholder, task 4 registers it, the round-trip test asserts
    /// on it -- a typo in any one of the three cannot drift from the others.
    static let handlerName = "fitrahEmbed"

    private static let supportedLocales: Set<String> = ["en", "ar", "nl"]

    /// nil when either substitution value fails validation. The caller shows the generic error --
    /// it never falls back to an unvalidated substitution. This is the app's only place where a
    /// runtime value lands inside a `<script>`; refusal is the defence, not escaping.
    ///
    /// No `customUserAgent` is set anywhere in this rung: plan §6.3's fixed per-client UAs govern
    /// InnerTube API calls, and `youtube-nocookie.com` serves a different player to a UA it does
    /// not read as a mobile browser. WKWebView's stock UA carries no app or device identifier.
    static func html(videoId: String, locale: String, captionsPreferred: Bool) -> String? {
        // `^[A-Za-z0-9_-]{11}$`, spelled without a `Regex` so nothing non-Sendable is stored.
        guard videoId.count == 11,
              videoId.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "_" || $0 == "-") }),
              supportedLocales.contains(locale),                  // ruling 19, en fallback upstream
              let template = Bundle.main.url(forResource: "embed", withExtension: "html")
                  .flatMap({ try? String(contentsOf: $0, encoding: .utf8) })
        else { return nil }
        var vars: [String: Any] = ["playsinline": 1, "rel": 0, "enablejsapi": 1]
        vars["origin"] = baseURL.absoluteString
        vars["hl"] = locale
        if captionsPreferred {                                     // plan §6.5's cc_* pair
            vars["cc_load_policy"] = 1
            vars["cc_lang_pref"] = locale
        }
        // `.withoutEscapingSlashes`: the default writer emits `https:\/\/…` for `origin`, which the
        // IFrame API compares byte-for-byte against the page's real origin.
        guard let json = try? JSONSerialization.data(withJSONObject: vars, options: [.withoutEscapingSlashes]),
              let varsJSON = String(data: json, encoding: .utf8) else { return nil }
        return template
            .replacingOccurrences(of: "__VIDEO_ID__", with: videoId)
            .replacingOccurrences(of: "__HANDLER__", with: handlerName)
            .replacingOccurrences(of: "__PLAYER_VARS__", with: varsJSON)
    }
}

/// The navigation lock.
enum EmbedNavigationPolicy {
    /// Plan §6.4 row 3 / §6.10: `decidePolicyFor` cancels every main-frame navigation off the
    /// bundled page. SUBFRAME navigations are always allowed -- the IFrame itself is a subframe and
    /// YouTube's player navigates it continuously; cancelling those breaks playback rather than
    /// locking anything. This is also the answer that justifies "Unrestricted Web Access: No" in
    /// the age-rating questionnaire (plan §9), so it must not grow an allowlist of "safe" hosts.
    static func allows(url: URL?, isMainFrame: Bool, baseURL: URL) -> Bool {
        guard isMainFrame else { return true }
        guard let url else { return false }
        if url.absoluteString == "about:blank" { return true }
        return url.scheme == baseURL.scheme && url.host == baseURL.host
    }
}

/// The JS -> native wire format. `WKScriptMessage.body` is `Any` and comes from a page hosting
/// YouTube's script, so every field is treated as untrusted and unknown events are dropped.
enum EmbedMessage {
    enum Event: Equatable, Sendable {
        case ready
        case state(Int)
        case error(Int)
    }

    static func parse(_ body: Any) -> Event? {
        guard let dict = body as? [String: Any], let event = dict["event"] as? String else { return nil }
        switch event {
        case "ready": return .ready
        case "state": return int(dict["state"]).map(Event.state)
        case "error": return int(dict["code"]).map(Event.error)
        default: return nil          // unknown event: dropped, never forwarded
        }
    }

    /// JS numbers arrive boxed as `NSNumber`; a bare `as? Int` misses a double-backed one.
    private static func int(_ value: Any?) -> Int? {
        if let int = value as? Int { return int }
        if let number = value as? NSNumber { return number.intValue }
        return nil
    }
}

/// What the embed rung does about one IFrame error (plan §6.6 "embed errors" row).
enum EmbedErrorAction: Equatable, Sendable {
    case reloadOnce
    case fail(messageKey: String)
    case offerYouTube(messageKey: String)
}

/// Plan §6.6's embed-errors row, as a truth table:
///   100 -> removed (terminal, distinct copy) | 101/150 -> embedding disabled by the owner, offer
///   rung 4 | everything else (2 malformed id, 5 HTML5 player, 153 missing Referer) -> retry once,
///   log, then give up.
/// Safe Mode never offers rung 4 (spec §10 / plan §6.10), so its branch lands on the same terminal
/// card without the hand-off button.
enum EmbedErrorPolicy {
    static func decide(code: Int, alreadyReloaded: Bool, safeMode: Bool) -> EmbedErrorAction {
        log("iframe error \(code) (alreadyReloaded: \(alreadyReloaded))")
        switch code {
        case 100:
            // A reload cannot un-remove a video: terminal on the first occurrence.
            return .fail(messageKey: "player_embed_removed")
        case 101, 150:
            return offerYouTube(messageKey: "player_embed_owner_only", safeMode: safeMode)
        default:
            return alreadyReloaded ? .fail(messageKey: "player_error_message") : .reloadOnce
        }
    }

    /// `webViewWebContentProcessDidTerminate` (plan §6.4 row 3): a jetsam is transient, so reload
    /// once; a second one is the web content process telling us it cannot host this video.
    static func decideProcessTermination(alreadyReloaded: Bool, safeMode: Bool) -> EmbedErrorAction {
        log("web content process terminated (alreadyReloaded: \(alreadyReloaded))")
        guard alreadyReloaded else { return .reloadOnce }
        return offerYouTube(messageKey: "player_error_generic", safeMode: safeMode)
    }

    private static func offerYouTube(messageKey: String, safeMode: Bool) -> EmbedErrorAction {
        safeMode ? .fail(messageKey: messageKey) : .offerYouTube(messageKey: messageKey)
    }

    private static func log(_ message: String) {
        #if DEBUG
        print("[EmbedErrorPolicy] \(message)")
        #endif
    }
}
