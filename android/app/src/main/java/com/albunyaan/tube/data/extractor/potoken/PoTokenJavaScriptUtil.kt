package com.albunyaan.tube.data.extractor.potoken

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject

/*
 * BotGuard / poToken JS bridging helpers. Ported from NewPipe (GPLv3):
 * org.schabi.newpipe.util.potoken.JavaScriptUtil. The reference uses nanojson; that ships as a
 * transitive (implementation-scoped) dependency of NewPipeExtractor and is not on the app's compile
 * classpath, so the JSON handling here uses Android's built-in org.json instead. okio ships
 * transitively (api-scoped) via OkHttp and is available.
 */

/**
 * Parses the raw challenge data obtained from the Create endpoint and returns an object that can be
 * embedded in a JavaScript snippet.
 */
fun parseChallengeData(rawChallengeData: String): String {
    val scrambled = JSONArray(rawChallengeData)

    val challengeData = if (scrambled.length() > 1 && scrambled.opt(1) is String) {
        val descrambled = descramble(scrambled.getString(1))
        JSONArray(descrambled)
    } else {
        scrambled.getJSONArray(0)
    }

    val messageId = challengeData.getString(0)
    val interpreterHash = challengeData.getString(3)
    val program = challengeData.getString(4)
    val globalName = challengeData.getString(5)
    val clientExperimentsStateBlob = challengeData.optString(7)

    val safeScript = challengeData.optJSONArray(1)?.firstStringOrNull()
    val trustedUrl = challengeData.optJSONArray(2)?.firstStringOrNull()

    val interpreterJavascript = JSONObject()
        .put("privateDoNotAccessOrElseSafeScriptWrappedValue", safeScript ?: JSONObject.NULL)
        .put(
            "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue",
            trustedUrl ?: JSONObject.NULL
        )

    return JSONObject()
        .put("messageId", messageId)
        .put("interpreterJavascript", interpreterJavascript)
        .put("interpreterHash", interpreterHash)
        .put("program", program)
        .put("globalName", globalName)
        .put("clientExperimentsStateBlob", clientExperimentsStateBlob)
        .toString()
}

/**
 * Parses the raw integrity token data obtained from the GenerateIT endpoint to a JavaScript
 * `Uint8Array` that can be embedded directly in JavaScript code, and a [Long] representing the
 * duration of this token in seconds.
 */
fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val integrityTokenData = JSONArray(rawIntegrityTokenData)
    return base64ToU8(integrityTokenData.getString(0)) to integrityTokenData.getLong(1)
}

/**
 * Converts a string (usually the identifier used as input to `obtainPoToken`) to a JavaScript
 * `Uint8Array` that can be embedded directly in JavaScript code.
 */
fun stringToU8(identifier: String): String {
    return newUint8Array(identifier.toByteArray())
}

/**
 * Takes a poToken encoded as a sequence of bytes represented as integers separated by commas
 * (e.g. "97,98,99" would be "abc"), which is the output of `Uint8Array::toString()` in JavaScript,
 * and converts it to the specific base64 representation for poTokens.
 */
fun u8ToBase64(poToken: String): String {
    return poToken.split(",")
        .map { it.toUByte().toByte() }
        .toByteArray()
        .toByteString()
        .base64()
        .replace("+", "-")
        .replace("/", "_")
}

/** Returns the first [String] element of this array, or null if there is none. */
private fun JSONArray.firstStringOrNull(): String? {
    for (i in 0 until length()) {
        val value = opt(i)
        if (value is String) return value
    }
    return null
}

/**
 * Takes the scrambled challenge, decodes it from base64, adds 97 to each byte.
 */
private fun descramble(scrambledChallenge: String): String {
    return base64ToByteString(scrambledChallenge)
        .map { (it + 97).toByte() }
        .toByteArray()
        .decodeToString()
}

/**
 * Decodes a base64 string encoded in the specific base64 representation used by YouTube, and
 * returns a JavaScript `Uint8Array` that can be embedded directly in JavaScript code.
 */
private fun base64ToU8(base64: String): String {
    return newUint8Array(base64ToByteString(base64))
}

private fun newUint8Array(contents: ByteArray): String {
    return "new Uint8Array([" + contents.joinToString(separator = ",") { it.toUByte().toString() } + "])"
}

/**
 * Decodes a base64 string encoded in the specific base64 representation used by YouTube.
 */
private fun base64ToByteString(base64: String): ByteArray {
    val base64Mod = base64
        .replace('-', '+')
        .replace('_', '/')
        .replace('.', '=')

    return (base64Mod.decodeBase64() ?: throw PoTokenException("Cannot base64 decode"))
        .toByteArray()
}
