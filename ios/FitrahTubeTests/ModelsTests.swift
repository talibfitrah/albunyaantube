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
}
