package com.albunyaan.tube.data.extractor

import org.json.JSONObject

/** One dub language exposed by the MWEB innertube player response. */
data class DubLanguage(val languageCode: String, val displayName: String, val isOriginal: Boolean)

/**
 * Lists which dub (audio-language) tracks a video exposes, via a single MWEB
 * innertube `/player` call. Listing needs no poToken and no nsig — only
 * streaming the tracks does (see [DubAudioResolver]). Verified in the spike:
 * memory/player-dubs-phase2-spike.md.
 */
class DubAudioEnumerator {
    companion object {
        /**
         * Parse distinct dub languages from a player response. Returns empty when
         * the video has fewer than two audio languages (no picker needed) — the
         * `availableAudioLanguages()` gate then keeps the globe hidden.
         */
        fun parseDubLanguages(json: JSONObject): List<DubLanguage> {
            val formats = json.optJSONObject("streamingData")
                ?.optJSONArray("adaptiveFormats") ?: return emptyList()
            val byCode = LinkedHashMap<String, DubLanguage>()
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val at = f.optJSONObject("audioTrack") ?: continue
                // id is like "en.4" / "ar.3"; the language is the part before the dot.
                val code = at.optString("id", "").substringBefore('.')
                    .takeIf { it.isNotBlank() } ?: continue
                val display = at.optString("displayName", "").ifBlank { code }
                val isOriginal = at.optBoolean("audioIsDefault", false) ||
                    display.trim().endsWith("original", ignoreCase = true)
                if (!byCode.containsKey(code)) {
                    byCode[code] = DubLanguage(code, display, isOriginal)
                }
            }
            return if (byCode.size <= 1) emptyList() else byCode.values.toList()
        }
    }
}
