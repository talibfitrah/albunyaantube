import AVFoundation
import SwiftUI

/// The pure half of Task 5 (player.md §2.2/§8.6 "sticky per session"): given the current sticky
/// pick and the audible options the live asset actually carries, which option should be selected.
/// `AVMediaSelectionGroup`/`AVMediaSelectionOption` aren't constructible in tests, so this takes
/// plain tuples -- `AudioLanguageMenu` below is the untested glue that maps real AVFoundation types
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
}

/// One audible option as the menu renders it -- a plain value type so `AudioLanguageMenu`'s SwiftUI
/// body doesn't hold `AVMediaSelectionOption` directly (that class isn't `Sendable`-friendly for
/// `@State`, and isn't needed for display).
struct AudioLanguageOption: Identifiable, Equatable {
    /// `extendedLanguageTag`, or (rare: a track authored with no BCP-47 tag) the option's own
    /// display name as a fallback identifier -- what `PlayerViewModel.stickyAudioLanguage` stores.
    let tag: String
    let displayName: String
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

    @State private var group: AVMediaSelectionGroup?
    @State private var options: [AudioLanguageOption] = []

    var body: some View {
        Group {
            // Hidden when the group has <=1 option (brief's contract) -- also naturally covers "no
            // audible group at all" (progressive/local fixtures) and "still loading" (`options`
            // starts empty).
            if options.count > 1 {
                Menu {
                    Section(String(localized: "shorts_audio_track_title")) {
                        ForEach(options) { option in
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
                    Image(systemName: "waveform")
                        .foregroundStyle(.white)
                        .padding(10)
                        .background(.black.opacity(0.55), in: Circle())
                }
                .accessibilityIdentifier("player.audioLanguageMenu.button")
                .accessibilityLabel(String(localized: "shorts_audio_track_title"))
            }
        }
        .task(id: model.currentItem) { await load() }
    }

    /// What the checkmark tracks: the sticky pick if one's been made, else whichever option
    /// `pickIndex` resolves the (still-`nil`) sticky value to -- same fallback the actual
    /// selection uses, so the checkmark never disagrees with what's really playing.
    private var selectedTag: String? {
        if let sticky = model.stickyAudioLanguage { return sticky }
        let pure = options.map { (tag: $0.tag, isDefault: $0.isDefault) }
        guard let index = AudioLanguageSelection.pickIndex(sticky: nil, options: pure) else { return nil }
        return options[index].tag
    }

    @MainActor
    private func load() async {
        guard let item = model.currentItem,
              let mediaGroup = try? await item.asset.loadMediaSelectionGroup(for: .audible) else {
            group = nil
            options = []
            return
        }
        group = mediaGroup
        options = mediaGroup.options.map { Self.option(mediaGroup, $0) }
        applySticky(item: item, group: mediaGroup)
    }

    private static func option(_ group: AVMediaSelectionGroup, _ option: AVMediaSelectionOption) -> AudioLanguageOption {
        let isDefault = option == group.defaultOption
        let name = isDefault
            ? String(format: String(localized: "shorts_audio_track_original_prefix"), option.displayName)
            : option.displayName
        return AudioLanguageOption(tag: option.extendedLanguageTag ?? option.displayName, displayName: name, isDefault: isDefault)
    }

    /// Re-apply hook: runs once per `load()`, i.e. once per new/replaced item -- the sticky pick
    /// (or the group default, if none) is selected before the user ever opens the menu.
    private func applySticky(item: AVPlayerItem, group: AVMediaSelectionGroup) {
        let pure = options.map { (tag: $0.tag, isDefault: $0.isDefault) }
        guard let index = AudioLanguageSelection.pickIndex(sticky: model.stickyAudioLanguage, options: pure) else { return }
        item.select(group.options[index], in: group)
    }

    private func select(_ option: AudioLanguageOption) {
        guard let item = model.currentItem, let group,
              let avOption = group.options.first(where: { ($0.extendedLanguageTag ?? $0.displayName) == option.tag }) else { return }
        item.select(avOption, in: group)
        model.stickyAudioLanguage = option.tag
    }
}
