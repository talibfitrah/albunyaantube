package com.albunyaan.tube.data.extractor

import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult

/** Test fake: a [PoTokenProvider] that mints nothing. Lets unit tests construct DubAudioResolver
 *  without a WebView/Context. */
object NoOpPoTokenProvider : PoTokenProvider {
    override fun getWebClientPoToken(videoId: String): PoTokenResult? = null
    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null
    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null
    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null
}
