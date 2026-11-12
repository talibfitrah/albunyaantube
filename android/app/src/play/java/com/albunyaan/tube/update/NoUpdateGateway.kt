package com.albunyaan.tube.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.LifecycleOwner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ANDROID-FLAVOR-01 — the Google Play flavor ships no self-updater.
 *
 * Play's Device and Network Abuse policy forbids an app that downloads and installs an
 * APK on its own. In this flavor the classes that could do that (ApkInstaller,
 * UpdateChecker, ReleaseSummaryFetcher, InstallStatusActivity, UpdatePromptFlow) are not
 * on the compile path at all, and REQUEST_INSTALL_PACKAGES /
 * UPDATE_PACKAGES_WITHOUT_USER_ACTION are absent from the merged manifest. This class
 * exists only so shared code in `src/main` keeps compiling.
 *
 * Its one action is [openRequiredUpdate], which opens this app's Play Store page. This
 * gateway itself makes no network call and never downloads or installs an update. Do not
 * give it a "just check for a release but don't install" path — Settings hides every update
 * affordance when [hasUpdater] is false, so a probe here would be a network call nothing can
 * act on. Updates for this flavor come from Play. The minimum-version floor is separate: the
 * shared [MinVersionGate] (src/main) reads the minimum-version file on cold start in both
 * flavors.
 */
@Singleton
class NoUpdateGateway @Inject constructor() : UpdateGateway {

    override val hasUpdater: Boolean = false

    override suspend fun checkForUpdate(): UpdateInfo? = null

    override suspend fun showUpdateDialogAndAwait(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        info: UpdateInfo,
    ) = Unit

    override fun runCheck(activity: Activity, lifecycleOwner: LifecycleOwner) = Unit

    /** Opens the Play Store listing; the web listing when no store app handles `market://`. */
    override fun openRequiredUpdate(activity: Activity, lifecycleOwner: LifecycleOwner) {
        val id = activity.packageName
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")))
        } catch (e: ActivityNotFoundException) {
            try {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id"))
                )
            } catch (e: ActivityNotFoundException) {
                // No store and no browser: nothing can open; the button stays for a retry.
            }
        }
    }
}

/**
 * Play binding. The `sideload` source set has a file of the same name binding
 * [UpdatePromptFlow] instead; exactly one of the two is ever compiled.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateGatewayModule {

    @Binds
    @Singleton
    abstract fun bindUpdateGateway(impl: NoUpdateGateway): UpdateGateway
}
