import Testing
@testable import FitrahTube

/// player.md §2.2/§8.6 "sticky per session" contract, tested against the pure core only --
/// `AVMediaSelectionGroup`/`AVMediaSelectionOption` aren't constructible in unit tests, so
/// `AudioLanguageMenu`'s AVFoundation glue (loading the real group, calling `select(_:in:)`) is
/// deliberately left untested; this drives `AudioLanguageSelection.pickIndex` directly.
@Suite(.perTest)
struct AudioLanguageTests {
    private static let options: [(tag: String, isDefault: Bool)] = [
        (tag: "en", isDefault: true),
        (tag: "ar", isDefault: false),
        (tag: "nl", isDefault: false),
    ]

    @Test func stickyTagPresentPicksItsIndex() {
        #expect(AudioLanguageSelection.pickIndex(sticky: "ar", options: Self.options) == 1)
    }

    @Test func stickyTagAbsentFallsBackToTheDefaultOption() {
        // A re-resolve whose new stream dropped the previously-picked language (e.g. "fr" was
        // never in this asset) falls back to the group's default -- never silently to index 0
        // of whatever order the options happen to be in.
        #expect(AudioLanguageSelection.pickIndex(sticky: "fr", options: Self.options) == 0)
    }

    @Test func nilStickyFallsBackToTheDefaultOption() {
        // First prepare this session -- no pick made yet.
        #expect(AudioLanguageSelection.pickIndex(sticky: nil, options: Self.options) == 0)
    }

    /// T5-2 (B1 final review): the default option is NOT always index 0 -- pin a group whose
    /// default sits at index 1 so a `return 0` shortcut in `pickIndex` can't pass.
    @Test func nilStickyPicksTheDefaultOptionEvenWhenItIsNotFirst() {
        let options: [(tag: String, isDefault: Bool)] = [
            (tag: "en", isDefault: false),
            (tag: "ar", isDefault: true),
            (tag: "nl", isDefault: false),
        ]
        #expect(AudioLanguageSelection.pickIndex(sticky: nil, options: options) == 1)
    }

    @Test func noMatchAndNoDefaultReturnsNil() {
        let options: [(tag: String, isDefault: Bool)] = [(tag: "en", isDefault: false), (tag: "ar", isDefault: false)]
        #expect(AudioLanguageSelection.pickIndex(sticky: "fr", options: options) == nil)
    }

    @Test func emptyOptionsReturnsNil() {
        #expect(AudioLanguageSelection.pickIndex(sticky: "en", options: []) == nil)
    }

    @Test func stickyPickSurvivesAcrossReorderedOptions() {
        // A prepare re-resolves with the same languages in a different order (a different rendition
        // set) -- the sticky pick still finds its tag by value, not by position.
        let reordered: [(tag: String, isDefault: Bool)] = [
            (tag: "nl", isDefault: false),
            (tag: "ar", isDefault: false),
            (tag: "en", isDefault: true),
        ]
        #expect(AudioLanguageSelection.pickIndex(sticky: "ar", options: reordered) == 1)
    }
}
