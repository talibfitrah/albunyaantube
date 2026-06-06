package com.albunyaan.tube.data.extractor.potoken

/**
 * Thrown when poToken generation fails for a recoverable reason (network error, BotGuard
 * rejection, JS runtime error). The caller may retry by recreating the generator.
 *
 * Ported from NewPipe (GPLv3): org.schabi.newpipe.util.potoken.
 */
class PoTokenException(message: String) : Exception(message)

/**
 * Thrown when the system WebView implementation is broken (e.g. a syntax error surfaced while
 * running modern JS, meaning the WebView is too old). poToken generation is impossible on this
 * device and must be disabled for the session rather than retried.
 */
class BadWebViewException(message: String) : Exception(message)

fun buildExceptionForJsError(error: String): Exception {
    return if (error.contains("SyntaxError")) {
        BadWebViewException(error)
    } else {
        PoTokenException(error)
    }
}
