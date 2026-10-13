import AVFoundation
import Foundation
import Testing
@testable import FitrahTube

/// player.md §2.2/§8.6 "sticky per session" contract, tested against the pure core --
/// `AVMediaSelectionGroup`/`AVMediaSelectionOption` aren't constructible in unit tests, so this
/// drives `AudioLanguageSelection.pickIndex` directly. The `@MainActor` tests drive the glue, end
/// to end through a real two-track file (`two-audio-tracks.m4a`) where they need an asset.
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

    // YouTube's HLS master marks every audio rendition DEFAULT=NO, the original included (live
    // probe 2026-10-13, I-WkGE4aTdE / cu1tihE_v3c / 3JSriET8zTE); the original is only
    // recognisable by its NAME, which AVFoundation exposes as the option's title metadata.
    @Test func youtubeOriginalNameSuffixMarksTheOriginal() {
        #expect(AudioLanguageSelection.isOriginal(isDefault: false, title: "American English - original"))
        #expect(!AudioLanguageSelection.isOriginal(isDefault: false, title: "Nederlands (Nederland) - dubbed-auto"))
        #expect(!AudioLanguageSelection.isOriginal(isDefault: false, title: nil))
        #expect(AudioLanguageSelection.isOriginal(isDefault: true, title: nil))
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

    /// The Dutch-iPhone bug (no globe, the device-language dub playing): the menu loaded its
    /// options from a `.task` on its own body, which is EMPTY until options exist -- so nothing
    /// ever loaded or selected. A new item must load and apply the pick with no menu on screen.
    /// Fixture: `ffmpeg -f lavfi -i anullsrc -f lavfi -i anullsrc -t 0.5` with two AAC tracks,
    /// `eng` (the group default) and `ara`.
    @MainActor @Test func aNewItemAppliesTheStickyPickWithNoMenuOnScreen() async throws {
        let url = try #require(Bundle(for: RecordingResolver.self).url(forResource: "two-audio-tracks", withExtension: "m4a"))
        let item = AVPlayerItem(url: url)
        let group = try #require(try await item.asset.loadMediaSelectionGroup(for: .audible))
        let selected = { item.currentMediaSelection.selectedMediaOption(in: group)?.extendedLanguageTag }
        let model = Self.model()
        model.stickyAudioLanguage = "ar"
        model.currentItem = item
        await model.audioLanguageLoad?.value
        #expect(selected() == "ar")
    }

    /// A new item clears the previous item's options at once: until its own load lands, the menu
    /// must not list -- or `select` into -- the old asset's group.
    @MainActor @Test func aNewItemClearsThePreviousItemsOptionsAtOnce() async throws {
        let url = try #require(Bundle(for: RecordingResolver.self).url(forResource: "two-audio-tracks", withExtension: "m4a"))
        let model = Self.model()
        model.currentItem = AVPlayerItem(url: url)
        await model.audioLanguageLoad?.value
        try #require(model.audioLanguageOptions.count == 2)
        model.currentItem = AVPlayerItem(url: url)
        #expect(model.audioLanguageOptions.isEmpty, "the previous item's options outlived it")
        #expect(model.audioLanguageGroup == nil, "the previous item's group outlived it")
    }

    /// A sticky pick this video doesn't carry: `applySticky` falls back to the original, and the
    /// checkmark (and the menu's accessibility value) must follow it, not the absent tag.
    @MainActor @Test func aStickyPickTheVideoLacksChecksTheOriginal() {
        let model = Self.model()
        model.stickyAudioLanguage = "fr"
        model.audioLanguageOptions = [AudioLanguageOption(tag: "ar", displayName: "Arabic", isDefault: false),
                                      AudioLanguageOption(tag: "en", displayName: "Original: English", isDefault: true)]
        #expect(AudioLanguageMenu(model: model).selectedTag == "en")
    }

    @MainActor private static func model() -> PlayerViewModel {
        PlayerViewModel(resolver: RecordingResolver(.hls),
                        settings: UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: "AudioLanguageTests.\(UUID().uuidString)")!),
                        args: PlayerArgs(videoId: "audio"))
    }
}
