import Foundation
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct ModelsTests {
    @Test func hasMoreIsTrueWhenNextCursorPresent() {
        let page = CursorPage(items: ["a"], nextCursor: "next")
        #expect(page.hasMore)
    }

    @Test func hasMoreIsFalseWhenNextCursorNil() {
        let page = CursorPage(items: ["a"], nextCursor: nil)
        #expect(!page.hasMore)
    }

    /// Moved off `HomeViewModel` when the four identical copies of this mapping collapsed into
    /// `PlayerArgs(item:)` (gate s1-2). RULINGS #17: `channelName` prefers the video's real
    /// `channelTitle`, falling back to `category` only when nil.
    @Test func playerArgsFromItemPrefersChannelTitleFallingBackToCategory() {
        let withChannelTitle = ContentItem(
            id: "v1", type: .video, title: "Title", category: "Fiqh", description: "Desc",
            thumbnailURL: URL(string: "https://example.com/a.jpg"), durationSeconds: 90,
            uploadedDaysAgo: 2, viewCount: 500, channelTitle: "Al-Huda Institute",
            subscribers: nil, videoCount: nil, itemCount: nil
        )
        let args = PlayerArgs(item: withChannelTitle)
        #expect(args.videoId == "v1")
        #expect(args.title == "Title")
        #expect(args.channelName == "Al-Huda Institute")
        #expect(args.thumbnailURL == withChannelTitle.thumbnailURL)
        #expect(args.description == "Desc")
        #expect(args.durationSeconds == 90)
        #expect(args.viewCount == 500)
        // The 4 fields this mapping deliberately leaves at their defaults.
        #expect(args.playlistId == nil)
        #expect(args.channelId == nil)

        let withoutChannelTitle = ContentItem(
            id: "v2", type: .video, title: "Title 2", category: "Fallback Category", description: nil,
            thumbnailURL: nil, durationSeconds: nil, uploadedDaysAgo: nil, viewCount: nil,
            channelTitle: nil, subscribers: nil, videoCount: nil, itemCount: nil
        )
        #expect(PlayerArgs(item: withoutChannelTitle).channelName == "Fallback Category")
    }
}
