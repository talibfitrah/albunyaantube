package com.albunyaan.tube.data.extractor

import java.util.Locale

/**
 * One entry in the shorts audio-language picker.
 *
 * Built by [availableAudioLanguages] by grouping a [ResolvedStreams.audioTracks]
 * list by [AudioTrack.language] and picking a representative (highest-bitrate)
 * track per language. The shorts UI uses [displayName] for the label and
 * [representative] when swapping the active audio track on selection.
 */
data class AudioLanguageOption(
    /** Raw language code as returned by NewPipe, or "und" for unknown. */
    val language: String,
    /** Localized display name (e.g. "English", "العربية"). */
    val displayName: String,
    /** Track classification for this language, if NewPipe reported one. */
    val trackKind: AudioTrackKind?,
    /** True when any track in the group was tagged [AudioTrackKind.ORIGINAL]. */
    val isOriginal: Boolean,
    /** Highest-bitrate track selected to represent this language. */
    val representative: AudioTrack
)

/**
 * Group the resolved audio tracks by language and return one
 * [AudioLanguageOption] per language.
 *
 * Returns an empty list when there are fewer than two audio tracks — the
 * caller should not show a picker with a single choice.
 *
 * Sort order: tracks flagged [AudioTrackKind.ORIGINAL] first, then
 * alphabetically by localized [AudioLanguageOption.displayName].
 */
fun ResolvedStreams.availableAudioLanguages(): List<AudioLanguageOption> {
    if (audioTracks.size <= 1) return emptyList()

    val grouped: Map<String, List<AudioTrack>> =
        audioTracks.groupBy { it.language?.takeIf { code -> code.isNotBlank() } ?: "und" }

    val options = grouped.map { (lang, tracks) ->
        val representative = tracks.maxByOrNull { it.bitrate ?: 0 } ?: tracks.first()
        val kind = tracks.firstNotNullOfOrNull { it.trackType }
        val isOriginal = tracks.any { it.trackType == AudioTrackKind.ORIGINAL }
        AudioLanguageOption(
            language = lang,
            displayName = displayNameFor(lang, representative),
            trackKind = kind,
            isOriginal = isOriginal,
            representative = representative
        )
    }

    return options.sortedWith(
        compareByDescending<AudioLanguageOption> { it.isOriginal }
            .thenBy { it.displayName.lowercase(Locale.getDefault()) }
    )
}

/**
 * Append the non-original dub languages discovered by [DubAudioEnumerator] as lazy
 * WEB_DUB [AudioTrack]s (url == "", resolved on selection). The original is dropped —
 * it is already the VR-native track — and any language the VR resolve already exposes
 * is skipped to avoid duplicates. No-op when [dubs] has fewer than two languages.
 * Lights up the globe (via [availableAudioLanguages]) without touching VR playback.
 */
fun ResolvedStreams.withDubLanguages(dubs: List<DubLanguage>): ResolvedStreams {
    if (dubs.size < 2) return this
    val existing = audioTracks.mapNotNull { it.language }.toSet()
    val lazy = dubs
        .filter { !it.isOriginal && it.languageCode !in existing }
        .map {
            AudioTrack(
                url = "", mimeType = null, bitrate = null, codec = null,
                language = it.languageCode, trackName = it.displayName,
                trackType = AudioTrackKind.DUBBED, source = AudioTrackSource.WEB_DUB
            )
        }
    return if (lazy.isEmpty()) this else copy(audioTracks = audioTracks + lazy)
}

/**
 * Pick a label for a language group. Prefer Java's [Locale] display name
 * (which Android localizes to the current UI locale); fall back to the
 * track's human-readable [AudioTrack.trackName]; last resort "Unknown".
 */
private fun displayNameFor(language: String, representative: AudioTrack): String {
    if (language == "und") {
        val tn = representative.trackName
        return if (!tn.isNullOrBlank()) tn else "Unknown"
    }
    return try {
        val locale = Locale.forLanguageTag(language)
        val display = locale.getDisplayLanguage(Locale.getDefault())
        if (display.isNullOrBlank() || display.equals(language, ignoreCase = true)) {
            representative.trackName?.takeIf { it.isNotBlank() } ?: language
        } else {
            display
        }
    } catch (t: Throwable) {
        representative.trackName?.takeIf { it.isNotBlank() } ?: language
    }
}
