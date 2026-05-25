package com.albunyaan.tube.util

import android.content.Context
import io.michaelrocks.libphonenumber.android.NumberParseException
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil

/**
 * Wraps libphonenumber-android. The PhoneNumberUtil is per-context (loads
 * metadata via the AAR's asset bundle) — callers pass any Context (typically
 * ApplicationContext). All methods are pure and side-effect free.
 */
object PhoneFormat {

    private fun util(ctx: Context): PhoneNumberUtil =
        PhoneNumberUtil.createInstance(ctx.applicationContext)

    /**
     * Parse [national] (digits, may contain spaces / dashes / parens) using
     * [region] (ISO-3166-1 alpha-2) and return the E.164 string when valid
     * for that region, else null.
     */
    fun formatE164(ctx: Context, region: String, national: String): String? = try {
        val u = util(ctx)
        val parsed = u.parse(national, region)
        if (!u.isValidNumberForRegion(parsed, region)) null
        else u.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    } catch (_: NumberParseException) {
        null
    }

    /**
     * Reverse of [formatE164]: split an E.164 string into (region, national).
     * Returns null when [e164] cannot be parsed.
     */
    fun parseDisplay(ctx: Context, e164: String): Pair<String, String>? = try {
        val u = util(ctx)
        val parsed = u.parse(e164, null)
        val region = u.getRegionCodeForNumber(parsed) ?: return null
        val national = u.getNationalSignificantNumber(parsed)
        region to national
    } catch (_: NumberParseException) {
        null
    }

    /** ISO-3166-1 alpha-2 region codes libphonenumber knows about. */
    fun supportedRegions(ctx: Context): Set<String> =
        util(ctx).supportedRegions
}
