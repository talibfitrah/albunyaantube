import Foundation
import Testing
@testable import FitrahTube

/// Plan C Task 3: the three share URL builders and the message body. Owner directive
/// 2026-08-27: no share surface may ever produce a youtube.com / youtu.be link.
struct ShareLinksTests {
    @Test func theThreeShareURLsMatchAndroidExactly() {
        // ShareLinks.kt:8-73 -- note video maps to the path segment "watch", and the /api prefix is
        // ALWAYS present on shared links (the non-/api routes exist only for inbound).
        #expect(ShareLinks.video("abc") == URL(string: "https://app.fitrahtube.com/api/watch/abc"))
        #expect(ShareLinks.url(for: .channel("UC1")) == URL(string: "https://app.fitrahtube.com/api/channel/UC1"))
        #expect(ShareLinks.url(for: .playlist("PL1")) == URL(string: "https://app.fitrahtube.com/api/playlist/PL1"))
    }

    @Test func noShareURLEverPointsAtYouTube() {
        for target in [ShareLinks.Target.video("abc"), .channel("UC1"), .playlist("PL1")] {
            let url = ShareLinks.url(for: target)
            #expect(url.host() == "app.fitrahtube.com")
            #expect(url.scheme == "https")
            #expect(url.absoluteString.localizedCaseInsensitiveContains("youtu") == false)
        }
    }

    @Test func everyShareURLRoundTripsThroughOurOwnDeepLinkParser() {
        // RULING 74 + the backend watch page's 50 ms hop (WatchPageController.java:536-542): a link we
        // emit must be a link we accept. This is the test that catches a path-shape drift on either side.
        for url in [ShareLinks.video("abc"), ShareLinks.url(for: .channel("UC1")), ShareLinks.url(for: .playlist("PL1"))] {
            #expect(DeepLinkParser.route(for: url) != nil, "\(url)")
        }
    }

    @Test func theMessageBodyOmitsTheURLBecauseTheURLIsTheItem() {
        // Reconciliation note 5: ShareLink(item:subject:message:) already hands the URL over as its own
        // activity item; repeating it in the body prints it twice in Mail and Messages.
        let body = ShareLinks.message(for: .video("abc"), title: "T", locale: .init(identifier: "en"))
        #expect(body.hasPrefix("T\n\n"))
        #expect(body.contains("Watch in FitrahTube"))
        #expect(body.contains("app.fitrahtube.com") == false)
        #expect(body.contains("youtu") == false)
        #expect(ShareLinks.message(for: .channel("UC1"), title: "C", locale: .init(identifier: "en")).contains("Open this channel in FitrahTube"))
        #expect(ShareLinks.message(for: .playlist("PL1"), title: "P", locale: .init(identifier: "en")).contains("Open this playlist in FitrahTube"))
    }

    @Test func thePromoLineNeverSaysAdFree() {
        // Global Constraints + spec D10/12: Android's share_app_promo is "Get FitrahTube for ad-free
        // Islamic content!" and the embed rung plays YouTube's ads, so the claim is false in-app.
        for loc in ["en", "ar", "nl"] {
            let body = ShareLinks.message(for: .video("a"), title: "T", locale: .init(identifier: loc))
            #expect(body.localizedCaseInsensitiveContains("ad-free") == false, Comment(rawValue: loc))
            #expect(body.localizedCaseInsensitiveContains("advertentievrij") == false, Comment(rawValue: loc))
            #expect(body.contains("بدون إعلانات") == false, Comment(rawValue: loc))
            #expect(body.contains("FitrahTube") || body.contains("فطرة تيوب"), Comment(rawValue: loc))
        }
    }

    @Test func aVideoTitleIsTruncatedAtOneSixtyWithAnEllipsis() {
        // PlayerFragment.kt:3317-3323 -- take(157) + "...". Channel and playlist titles are not truncated.
        let long = String(repeating: "a", count: 200)
        let video = ShareLinks.message(for: .video("v"), title: long, locale: .init(identifier: "en"))
        let firstLine = video.split(separator: "\n", maxSplits: 1).first.map(String.init)
        #expect(firstLine == String(repeating: "a", count: 157) + "...")
        #expect(ShareLinks.message(for: .channel("c"), title: long, locale: .init(identifier: "en")).hasPrefix(long))
        #expect(ShareLinks.message(for: .video("v"), title: String(repeating: "b", count: 160), locale: .init(identifier: "en"))
            .hasPrefix(String(repeating: "b", count: 160) + "\n"))
    }
}
