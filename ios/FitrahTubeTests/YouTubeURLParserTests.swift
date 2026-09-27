import Foundation
import Testing
@testable import FitrahTube

/// `SuggestContentViewModel.kt:76-112`, as a table.
///
/// The parser NEVER opens, links to or renders what it reads: it turns a string the user pasted
/// into a `(type, id)` pair for a BACKEND search (`GET api/admin/youtube/search`). No test here —
/// and no line of the parser — addresses youtube.com.
///
/// Fixture ids are the approved three only (video `xc7keR2piUM`, channel `UCmMcOjsVehVlEOteyrhjI2Q`,
/// playlist `PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc`).
@Suite(.perTest)
struct YouTubeURLParserTests {

    private static let video = "xc7keR2piUM"
    private static let channel = "UCmMcOjsVehVlEOteyrhjI2Q"
    private static let playlist = "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc"

    /// Twelve rows, each an explicit precedence or rejection assertion. The order of the middle
    /// five IS the precedence: `v` -> `list` -> `/channel/<id>` -> `/shorts/<id>` -> `@handle`.
    @Test func theTwelveRowPrecedenceTableHolds() {
        // 1. `v` beats `list` on a watch URL opened from inside a playlist (`:101-102`). This row
        //    is the whole reason the order is a rule and not an accident.
        #expect(YouTubeURLParser.parse("https://www.youtube.com/watch?v=\(Self.video)&list=\(Self.playlist)")
                == .video(Self.video))
        // 2. `list` with no `v` is the playlist itself.
        #expect(YouTubeURLParser.parse("https://www.youtube.com/playlist?list=\(Self.playlist)")
                == .playlist(Self.playlist))
        // 3. The short host carries the id in its PATH, not in a query param (`:83-86`).
        #expect(YouTubeURLParser.parse("https://youtu.be/\(Self.video)") == .video(Self.video))
        // 4. A subdomain of an accepted host is accepted (`:88` `host.endsWith(".youtube.com")`).
        #expect(YouTubeURLParser.parse("https://m.youtube.com/channel/\(Self.channel)")
                == .channel(Self.channel))
        // 5. A Short is a VIDEO, not a fifth kind (`:104`).
        #expect(YouTubeURLParser.parse("https://www.youtube.com/shorts/\(Self.video)") == .video(Self.video))
        // 6. A handle is its own case here, and the ViewModel resolves it to a CHANNEL search
        //    (`:105` maps it to `CHANNEL to handle`) — a handle is not an id.
        #expect(YouTubeURLParser.parse("https://www.youtube.com/@fitrahtube") == .handle("@fitrahtube"))
        // 7. The nocookie host is the same host family (`:87-88`).
        #expect(YouTubeURLParser.parse("https://www.youtube-nocookie.com/watch?v=\(Self.video)")
                == .video(Self.video))
        // 8. Only http(s) (`:78`). Any other scheme is text the user typed, not a link.
        #expect(YouTubeURLParser.parse("ftp://youtube.com/watch?v=\(Self.video)")
                == .query("ftp://youtube.com/watch?v=\(Self.video)"))
        // 9. A host that merely CONTAINS the name is not the host.
        #expect(YouTubeURLParser.parse("https://notyoutube.com/watch?v=\(Self.video)")
                == .query("https://notyoutube.com/watch?v=\(Self.video)"))
        // 10. THE TRAP. The suffix match must be on a dot boundary and against the HOST — a
        //     `contains`, or a match against the whole URL string, hands a hostile site the
        //     ability to have its links parsed as YouTube's.
        #expect(YouTubeURLParser.parse("https://evil.com/youtube.com/watch?v=\(Self.video)")
                == .query("https://evil.com/youtube.com/watch?v=\(Self.video)"))
        // 11. A bare word is a plain search, carried verbatim.
        #expect(YouTubeURLParser.parse("tafsir") == .query("tafsir"))
        // 12. Empty in, empty out — the ViewModel's blank check is what turns this into `.idle`,
        //     not the parser's.
        #expect(YouTubeURLParser.parse("") == .query(""))
    }

    /// An accepted host that names no content is a QUERY, never a half-parsed id: Android's `when`
    /// falls through to `null` (`:106`) and `resolveQuery` then searches the raw text (`:73-74`).
    /// The empty-value rows are the ones a naive `queryItems["v"] != nil` gets wrong — `?v=` is
    /// present and empty, and `.video("")` would ask the backend for a video with no id.
    @Test func aYouTubeHostThatNamesNoContentFallsThroughToTheRawQuery() {
        for raw in ["https://www.youtube.com/",
                    "https://www.youtube.com/feed/subscriptions",
                    "https://www.youtube.com/watch?v=",
                    "https://www.youtube.com/playlist?list=",
                    "https://youtu.be/",
                    "https://www.youtube.com/shorts/",
                    "https://www.youtube.com/channel/"] {
            #expect(YouTubeURLParser.parse(raw) == .query(raw), "\(raw) names nothing")
        }
    }

    /// Surrounding whitespace is trimmed before parsing (`:77`) but the QUERY arm carries the raw
    /// text: what the user typed is what gets searched, and only the ViewModel decides that a
    /// whitespace-only query is blank.
    @Test func whitespaceIsTrimmedForParsingAndKeptForSearching() {
        #expect(YouTubeURLParser.parse("  https://youtu.be/\(Self.video)  ") == .video(Self.video))
        #expect(YouTubeURLParser.parse("  tafsir  ") == .query("  tafsir  "))
        #expect(YouTubeURLParser.parse("   ") == .query("   "))
    }
}
