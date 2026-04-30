package com.albunyaan.tube.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer

/**
 * [DefaultRenderersFactory] that re-enables legacy subtitle decoding on the
 * built-in [TextRenderer]. Media3 1.10 disables legacy decoders by default
 * — the TextRenderer rejects raw TTML / VTT samples (`application/ttml+xml`,
 * `application/x-subrip`, etc.) and only accepts parser-emitted cues
 * (`application/x-media3-cues`). For our pipeline that means picking a
 * subtitle crashes with:
 *
 *   IllegalStateException: Legacy decoding is disabled, can't handle
 *   application/ttml+xml samples (expected application/x-media3-cues).
 *
 * The official fix is to either upgrade the source factory chain to one
 * that parses subtitles into cues during extraction (DefaultMediaSourceFactory
 * does this; SingleSampleMediaSource does not) or to opt back into legacy
 * decoding on the renderer. We rely on SingleSampleMediaSource to side-load
 * subtitles (auto-generated YouTube captions in particular are not in the
 * DASH manifest), so the renderer-side opt-in is the path we can take
 * without a wider refactor.
 */
@UnstableApi
class LegacySubtitleRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        val before = out.size
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        for (i in before until out.size) {
            (out[i] as? TextRenderer)?.experimentalSetLegacyDecodingEnabled(true)
        }
    }
}
