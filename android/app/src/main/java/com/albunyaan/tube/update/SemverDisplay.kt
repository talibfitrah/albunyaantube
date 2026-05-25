package com.albunyaan.tube.update

/**
 * Sanitizes an untrusted SemVer-shaped string for display in the UI or
 * substitution into a URL. SemVer 2.0.0 grammar is ASCII; this allowlist
 * strips:
 *  - bidi-override / zero-width characters (Unicode Format category)
 *  - Unicode digits and homoglyphs (Arabic-Indic ٠-٩, fullwidth ０-９, Cyrillic
 *    а/о/р, Greek ν/ο, Mathematical Alphanumeric letters)
 *
 * Returns ASCII letters + digits + the SemVer punctuation `.-+_`. Used at
 * adapter bind sites and at the GitHub release-tag URL substitution.
 *
 * Closes cso S2-3 (bidi/zero-width) and the stage-6 security follow-up
 * (Unicode-wide letterOrDigit homoglyph residual).
 */
internal fun String.sanitizeSemverDisplay(): String = filter {
    it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in ".-+_"
}
