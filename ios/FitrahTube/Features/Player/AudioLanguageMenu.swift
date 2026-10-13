import AVFoundation
import SwiftUI

/// The pure half of Task 5 (player.md §2.2/§8.6 "sticky per session"): given the current sticky
/// pick and the audible options the live asset actually carries, which option should be selected.
/// `AVMediaSelectionGroup`/`AVMediaSelectionOption` aren't constructible in tests, so this takes
/// plain tuples -- `AudioLanguageMenu` below is the glue that maps real AVFoundation types
/// down to this shape and back up to a real `select(_:in:)` call.
enum AudioLanguageSelection {
    /// - `sticky` present in `options` -> its index (a pick made earlier this session wins).
    /// - `sticky` absent (including `nil`, i.e. no pick made yet, or a re-resolve whose stream
    ///   dropped that language) -> the group's own default/original option, if any.
    /// - Neither found -> `nil` (leave whatever AVPlayer already auto-selected alone).
    static func pickIndex(sticky: String?, options: [(tag: String, isDefault: Bool)]) -> Int? {
        if let sticky, let index = options.firstIndex(where: { $0.tag == sticky }) {
            return index
        }
        return options.firstIndex(where: { $0.isDefault })
    }

    /// YouTube's HLS master marks every audio rendition DEFAULT=NO, the original included (live
    /// probe 2026-10-13), so the default flag alone never finds it; its NAME -- exposed by
    /// AVFoundation as the option's title metadata -- ends "- original" ("American English -
    /// original" vs "Nederlands (Nederland) - dubbed-auto"), unlocalized in every `hl` probed.
    static func isOriginal(isDefault: Bool, title: String?) -> Bool {
        isDefault || title?.hasSuffix("- original") == true
    }
}

/// One audible option as the menu renders it -- a plain value type, so what `PlayerViewModel`
/// publishes for the menu is not `AVMediaSelectionOption` itself (not needed for display).
struct AudioLanguageOption: Identifiable, Equatable {
    /// `extendedLanguageTag`, or (rare: a track authored with no BCP-47 tag) the option's own
    /// display name as a fallback identifier -- what `PlayerViewModel.stickyAudioLanguage` stores.
    let tag: String
    let displayName: String
    /// The original track (`AudioLanguageSelection.isOriginal`), not just the HLS DEFAULT flag.
    let isDefault: Bool
    var id: String { tag }
}

/// FitrahTube's own SwiftUI audio-language menu (spec §10) -- same reasoning as
/// `PlayerScreen.qualityMenu`: AVKit's stock transport exposes no separate accessibility element
/// for its own audio-track picker on this SDK, so this is what `player.audioLanguageMenu.button` /
/// `player.audioLanguageOption.*` actually drive. Reads
/// `asset.mediaSelectionGroup(forMediaCharacteristic: .audible)` off the live item
/// `PlayerHostView` hands back through `PlayerViewModel.currentItem`, lists its options, and
/// re-applies the sticky pick (the pure `AudioLanguageSelection.pickIndex` above) on every new
/// item -- same "re-apply on every prepare" contract as Task 4's quality ceiling. Ruling 13 (Phase
/// 3 dub enumeration): this never asks InnerTubeKit for languages -- only what the resolved
/// asset's own media-selection group already carries.
struct AudioLanguageMenu: View {
    let model: PlayerViewModel

    private var options: [AudioLanguageOption] { model.audioLanguageOptions }

    var body: some View {
        // Hidden when the group has <=1 option (brief's contract) -- also naturally covers "no
        // audible group at all" (progressive/local fixtures) and "still loading" (`options` is
        // emptied on every new item until its load lands).
        if options.count > 1 {
            Menu {
                Section(String(localized: "shorts_audio_track_title")) {
                    // "Original: X" first, like Android's `availableAudioLanguages()`. Only
                    // the display is reordered: `applySticky` indexes `group.options` with
                    // `options`, so `options` itself stays in group order.
                    ForEach(options.filter(\.isDefault) + options.filter { !$0.isDefault }) { option in
                        Button {
                            select(option)
                        } label: {
                            if selectedTag == option.tag {
                                Label(option.displayName, systemImage: "checkmark")
                            } else {
                                Text(option.displayName)
                            }
                        }
                        .accessibilityIdentifier("player.audioLanguageOption.\(option.tag)")
                    }
                }
            } label: {
                Image(systemName: "globe").shortsGlyph()
            }
            .accessibilityIdentifier("player.audioLanguageMenu.button")
            .accessibilityLabel(String(localized: "shorts_audio_track_title"))
            // Task 10 (spec §6.11 "label + value on custom controls"): the currently-selected
            // track's display name (already carries "Original: X" for the default option).
            .accessibilityValue(options.first(where: { $0.tag == selectedTag })?.displayName ?? "")
        }
    }

    /// What the checkmark tracks: `pickIndex` over the sticky pick, exactly as `applySticky`
    /// selects -- a sticky tag this video lacks falls back to the original here too, so the
    /// checkmark never disagrees with what's really playing.
    var selectedTag: String? {
        let pure = options.map { (tag: $0.tag, isDefault: $0.isDefault) }
        guard let index = AudioLanguageSelection.pickIndex(sticky: model.stickyAudioLanguage, options: pure) else { return nil }
        return options[index].tag
    }

    /// Run by `PlayerViewModel.currentItem`'s `didSet` on every new item (see there for why not
    /// from this view).
    static func load(_ model: PlayerViewModel) async {
        let item = model.currentItem
        guard let item, let mediaGroup = try? await item.asset.loadMediaSelectionGroup(for: .audible) else {
            // M4: the failure branch needs the same currentness guard as the success one -- a load
            // that fails against the OLD asset must not blank the menu the new item just populated.
            guard item === model.currentItem else { return }
            model.audioLanguageGroup = nil
            model.audioLanguageOptions = []
            return
        }
        var loaded: [AudioLanguageOption] = []
        for option in mediaGroup.options {
            let title = AVMetadataItem.metadataItems(from: option.commonMetadata, filteredByIdentifier: .commonIdentifierTitle).first
            loaded.append(Self.option(mediaGroup, option, title: try? await title?.load(.stringValue)))
        }
        // T5-1 (folded in by Task 7): currentness guard, same shape as `CaptionOverlay.load()`'s.
        // Recovery replaces the item under this view (`replaceCurrentItem` on every re-resolve), so
        // a load still in flight across that swap would otherwise publish the OLD asset's options
        // and `select` into the old item -- silently undoing the fresh item's own sticky pick.
        guard item === model.currentItem else { return }
        model.audioLanguageGroup = mediaGroup
        model.audioLanguageOptions = loaded
        applySticky(model, item: item, group: mediaGroup)
    }

    private static func option(_ group: AVMediaSelectionGroup, _ option: AVMediaSelectionOption, title: String?) -> AudioLanguageOption {
        let isDefault = AudioLanguageSelection.isOriginal(isDefault: option == group.defaultOption, title: title)
        let name = isDefault
            ? String(format: String(localized: "shorts_audio_track_original_prefix"), option.displayName)
            : option.displayName
        return AudioLanguageOption(tag: option.extendedLanguageTag ?? option.displayName, displayName: name, isDefault: isDefault)
    }

    /// Re-apply hook: runs once per `load()`, i.e. once per new/replaced item -- the sticky pick
    /// (or the original, `AudioLanguageSelection.isOriginal`, if none) is selected before the user
    /// ever opens the menu.
    private static func applySticky(_ model: PlayerViewModel, item: AVPlayerItem, group: AVMediaSelectionGroup) {
        let pure = model.audioLanguageOptions.map { (tag: $0.tag, isDefault: $0.isDefault) }
        guard let index = AudioLanguageSelection.pickIndex(sticky: model.stickyAudioLanguage, options: pure) else { return }
        item.select(group.options[index], in: group)
    }

    private func select(_ option: AudioLanguageOption) {
        guard let item = model.currentItem, let group = model.audioLanguageGroup,
              let avOption = group.options.first(where: { ($0.extendedLanguageTag ?? $0.displayName) == option.tag }) else { return }
        item.select(avOption, in: group)
        model.stickyAudioLanguage = option.tag
    }
}
