package com.albunyaan.tube.update

/**
 * One row in the Available Updates screen. Carries the full UpdateInfo so the
 * future downgrade flow (out of scope today) can drive uninstall + reinstall
 * without a re-fetch.
 *
 * Published date is accessed via [info.publishedAt] — no need to duplicate it here.
 */
data class ReleaseRow(
    val info: UpdateInfo,
    val localizedSummary: String?,
    val state: RowState
)

sealed class RowState {
    /** This release matches the running build — show "Installed" chip, no action. */
    object Current : RowState()

    /** This release is strictly newer than the running build — show "Install" button. */
    object Newer : RowState()

    /**
     * This release is older than the running build OR the comparator could not
     * decide. Shown disabled with "Downgrade not available" subtitle. Future
     * backwards-compat work will give this state an active CTA.
     */
    object Older : RowState()
}
