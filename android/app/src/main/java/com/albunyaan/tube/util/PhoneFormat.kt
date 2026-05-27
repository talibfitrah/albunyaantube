package com.albunyaan.tube.util

import android.content.Context
import io.michaelrocks.libphonenumber.android.NumberParseException
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil
import java.util.Locale

/**
 * Wraps libphonenumber-android. The PhoneNumberUtil is per-context (loads
 * metadata via the AAR's asset bundle) — callers pass any Context (typically
 * ApplicationContext). All methods are pure and side-effect free.
 */
object PhoneFormat {

    @Volatile private var cachedUtil: PhoneNumberUtil? = null

    private fun util(ctx: Context): PhoneNumberUtil {
        cachedUtil?.let { return it }
        synchronized(this) {
            cachedUtil?.let { return it }
            return PhoneNumberUtil.createInstance(ctx.applicationContext).also { cachedUtil = it }
        }
    }

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

    /**
     * Country pickers for both Bootstrap and Edit Phone use the same shape: a list
     * of (ISO region, display name) pairs sorted alphabetically by display name in
     * the device locale. Display includes the dial code so users immediately see
     * which country code they're selecting.
     */
    fun countryRows(ctx: Context): List<Pair<String, String>> {
        val u = util(ctx)
        val locale = ctx.resources.configuration.locales[0]
        return supportedRegions(ctx)
            .map { iso ->
                val name = Locale("", iso).getDisplayCountry(locale).ifBlank { iso }
                val code = u.getCountryCodeForRegion(iso)
                iso to "$name (+$code)"
            }
            .sortedBy { it.second }
    }

    /**
     * Format an E.164 string for human-readable display, e.g. "+31 6 12345678".
     * Returns the raw [e164] if parsing fails.
     */
    fun formatInternational(ctx: Context, e164: String): String = try {
        val u = util(ctx)
        val parsed = u.parse(e164, null)
        u.format(parsed, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
    } catch (_: NumberParseException) {
        e164
    }
}
