import Foundation
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct DeepLinkParserTests {
    // Custom scheme: albunyaantube://{video|channel|playlist|shorts}/{id}

    @Test func customSchemeVideo() {
        let route = DeepLinkParser.route(for: URL(string: "albunyaantube://video/abc123")!)
        #expect(route == .player(PlayerArgs(videoId: "abc123")))
    }

    @Test func customSchemeChannel() {
        let route = DeepLinkParser.route(for: URL(string: "albunyaantube://channel/chan1")!)
        #expect(route == .channel(id: "chan1", name: nil, avatarURL: nil))
    }

    @Test func customSchemePlaylist() {
        let route = DeepLinkParser.route(for: URL(string: "albunyaantube://playlist/pl1")!)
        #expect(route == .playlist(id: "pl1", title: nil, category: nil, count: nil))
    }

    @Test func customSchemeShorts() {
        let route = DeepLinkParser.route(for: URL(string: "albunyaantube://shorts/sh1")!)
        #expect(route == .shorts(PlayerArgs(videoId: "sh1")))
    }

    @Test func shortsSchemeCarriesPlayerArgsWithOnlyTheVideoId() {
        // Ruling 51: the deep link is a SINGLE short, not an entry into a feed -- so everything except
        // the id is nil and the screen resolves exactly one video.
        let route = DeepLinkParser.route(for: URL(string: "albunyaantube://shorts/xc7keR2piUM")!)
        #expect(route == .shorts(PlayerArgs(videoId: "xc7keR2piUM")))
    }

    @Test func watchLinksStillOpenTheRegularPlayerEvenForAShort() {
        // Ruling 67, verbatim: "Inbound watch links open the regular player even for Shorts (parity;
        // the receiver cannot know it is a Short before resolution)". This is the test that stops a
        // well-meaning future change from sniffing durations at parse time.
        let route = DeepLinkParser.route(for: URL(string: "https://app.fitrahtube.com/api/watch/xc7keR2piUM")!)
        #expect(route == .player(PlayerArgs(videoId: "xc7keR2piUM")))
    }

    // Universal Link: https://app.fitrahtube.com/{watch|channel|playlist}/{id}

    @Test func universalLinkWatch() {
        let route = DeepLinkParser.route(for: URL(string: "https://app.fitrahtube.com/watch/abc123")!)
        #expect(route == .player(PlayerArgs(videoId: "abc123")))
    }

    @Test func universalLinkChannel() {
        let route = DeepLinkParser.route(for: URL(string: "https://app.fitrahtube.com/channel/chan1")!)
        #expect(route == .channel(id: "chan1", name: nil, avatarURL: nil))
    }

    @Test func universalLinkPlaylist() {
        let route = DeepLinkParser.route(for: URL(string: "https://app.fitrahtube.com/playlist/pl1")!)
        #expect(route == .playlist(id: "pl1", title: nil, category: nil, count: nil))
    }

    // Universal Link, /api prefix: https://app.fitrahtube.com/api/{watch|channel|playlist}/{id}

    @Test func universalLinkApiWatch() {
        let route = DeepLinkParser.route(for: URL(string: "https://app.fitrahtube.com/api/watch/abc123")!)
        #expect(route == .player(PlayerArgs(videoId: "abc123")))
    }

    @Test func universalLinkApiChannel() {
        let route = DeepLinkParser.route(for: URL(string: "https://app.fitrahtube.com/api/channel/chan1")!)
        #expect(route == .channel(id: "chan1", name: nil, avatarURL: nil))
    }

    @Test func universalLinkApiPlaylist() {
        let route = DeepLinkParser.route(for: URL(string: "https://app.fitrahtube.com/api/playlist/pl1")!)
        #expect(route == .playlist(id: "pl1", title: nil, category: nil, count: nil))
    }

    // Invalid shapes

    @Test func unrelatedSchemeIsNil() {
        let route = DeepLinkParser.route(for: URL(string: "mailto:test@example.com")!)
        #expect(route == nil)
    }

    @Test func wrongHostIsNil() {
        let route = DeepLinkParser.route(for: URL(string: "https://example.com/watch/abc123")!)
        #expect(route == nil)
    }

    // `splash-onboarding.md` / DeepLinkParser's own doc comment: shorts has no Universal Link
    // shape -- Android only reaches it via in-app navigation, never a verified https host.
    @Test func universalLinkShortsIsNil() {
        let route = DeepLinkParser.route(for: URL(string: "https://app.fitrahtube.com/shorts/sh1")!)
        #expect(route == nil)
    }

    // MARK: - Hostile ids (gate A-I4 / cso-F1)

    /// Every one of these was accepted verbatim into a `Route` before the id guard: `..` survives
    /// percent-encoding (`.` is unreserved) and URL normalisation collapses it into a traversal;
    /// an embedded NUL truncates any C string it reaches; an unbounded id lands on the
    /// NavigationStack whole.
    @Test(arguments: [
        "albunyaantube://video/..",
        "albunyaantube://video/%2e%2e",
        "albunyaantube://video/a%00b",
        "albunyaantube://video/a.b",
        "albunyaantube://video/a b",
        "albunyaantube://channel/../../secret",
        "albunyaantube://playlist/",
    ])
    func hostileCustomSchemeIDsAreRejected(_ raw: String) {
        #expect(DeepLinkParser.route(for: URL(string: raw)!) == nil, "accepted \(raw)")
    }

    /// Not hostile, recorded so the boundary is explicit: a query string is not a path component,
    /// so it is ignored and the id is still just "a" -- the same way the Universal Link branch
    /// already ignores `?query` and `#fragment`.
    @Test func queryStringIsIgnoredRatherThanTreatedAsPartOfTheID() {
        #expect(DeepLinkParser.route(for: URL(string: "albunyaantube://video/a?b")!)
                == .player(PlayerArgs(videoId: "a")))
    }

    @Test func idLongerThan128CharactersIsRejected() {
        let tooLong = String(repeating: "a", count: 129)
        #expect(DeepLinkParser.route(for: URL(string: "albunyaantube://playlist/\(tooLong)")!) == nil)
        // CF-G-16: the bound is `SavedPlaylistsStore`'s 128 (Android `^[A-Za-z0-9_-]{3,128}$`),
        // not 64 -- a real >64-char playlist id must route, or the deep link dies at the parser
        // while the store would have accepted it.
        let long = String(repeating: "a", count: 100)
        #expect(DeepLinkParser.route(for: URL(string: "albunyaantube://playlist/\(long)")!)
                == .playlist(id: long, title: nil, category: nil, count: nil))
        // ...and exactly 128 is still fine -- the bound is a bound, not an off-by-one.
        let atLimit = String(repeating: "a", count: 128)
        #expect(DeepLinkParser.route(for: URL(string: "albunyaantube://playlist/\(atLimit)")!)
                == .playlist(id: atLimit, title: nil, category: nil, count: nil))
    }

    @Test func universalLinkHostileIDIsRejected() {
        #expect(DeepLinkParser.route(for: URL(string: "https://app.fitrahtube.com/watch/..")!) == nil)
    }

    // MARK: - Case-insensitive scheme/host, exact segment count (gate A-M2 / A-M3)

    @Test func uppercaseSchemeAndHostStillRoute() {
        #expect(DeepLinkParser.route(for: URL(string: "ALBUNYAANTUBE://VIDEO/abc123")!)
                == .player(PlayerArgs(videoId: "abc123")))
        #expect(DeepLinkParser.route(for: URL(string: "HTTPS://APP.FITRAHTUBE.COM/watch/abc123")!)
                == .player(PlayerArgs(videoId: "abc123")))
    }

    @Test func customSchemeExtraSegmentsAreRejectedNotTruncated() {
        // Used to yield `.player(videoId: "abc")` -- trailing junk silently dropped.
        #expect(DeepLinkParser.route(for: URL(string: "albunyaantube://video/abc/extra")!) == nil)
        // `a%2Fb` decodes to two components; used to yield the *wrong* video "a".
        #expect(DeepLinkParser.route(for: URL(string: "albunyaantube://video/a%2Fb")!) == nil)
    }
}
