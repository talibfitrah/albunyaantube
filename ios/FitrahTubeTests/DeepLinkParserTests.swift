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
        #expect(route == .shorts(id: "sh1"))
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
}
