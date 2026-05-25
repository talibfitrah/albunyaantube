package com.albunyaan.tube.util

/**
 * RFC-5322-shaped email validator. Mirrors Firebase Auth's own minimum
 * shape: a single non-empty local part, one '@', and a domain with at
 * least one dot (no leading or trailing dot). Pre-network gate so we
 * don't burn Firebase throttle quota on obviously-malformed input.
 */
fun isEmailShape(s: String): Boolean {
    val at = s.indexOf('@')
    if (at <= 0 || at != s.lastIndexOf('@')) return false
    if (at == s.length - 1) return false
    val domain = s.substring(at + 1)
    return domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.')
}
