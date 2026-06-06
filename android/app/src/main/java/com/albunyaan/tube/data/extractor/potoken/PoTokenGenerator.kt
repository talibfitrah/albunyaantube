package com.albunyaan.tube.data.extractor.potoken

import android.content.Context
import java.io.Closeable

/**
 * Generates poTokens for a YouTube client session. Adapted from NewPipe (GPLv3), with the RxJava
 * `Single` surface replaced by Kotlin coroutines to match this codebase.
 *
 * A single generator holds the BotGuard `integrityToken` + `webPoSignalOutput` obtained during
 * initialization and can mint multiple poTokens (one streaming token for the visitorData, then one
 * player token per video id) until [isExpired] returns true.
 */
interface PoTokenGenerator : Closeable {
    /**
     * Generates a poToken for [identifier] (a visitorData string for the streaming token, or a
     * video id for a player token), using the `integrityToken` and `webPoSignalOutput` obtained
     * during initialization. May be called multiple times.
     */
    suspend fun generatePoToken(identifier: String): String

    /**
     * @return whether the `integrityToken` is expired, in which case every token produced by
     * [generatePoToken] is invalid and the generator must be recreated.
     */
    fun isExpired(): Boolean

    interface Factory {
        /**
         * Initializes a [PoTokenGenerator] by loading the BotGuard VM in a WebView, running it, and
         * obtaining an `integrityToken`. The returned generator can then mint poTokens repeatedly.
         *
         * @param context used to load the HTML asset and instantiate the WebView
         */
        suspend fun newPoTokenGenerator(context: Context): PoTokenGenerator
    }
}
