package com.albunyaan.tube.ui

import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.albunyaan.tube.R
import com.albunyaan.tube.update.UpdateGateway
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Terminal screen when [com.albunyaan.tube.update.MinVersionGate] says this install is below
 * the floor (iOS `UpdateRequiredView`). The splash pops the whole graph to reach it, so it is
 * the only destination; Back sends the app to the background ([leaveAppOnBack]).
 */
@AndroidEntryPoint
class UpdateRequiredFragment : Fragment(R.layout.fragment_update_required) {

    @Inject lateinit var updateGateway: UpdateGateway

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        leaveAppOnBack()
        view.findViewById<MaterialButton>(R.id.updateButton).setOnClickListener {
            // The activity, not viewLifecycleOwner: the sideload download outlives this view.
            val host = requireActivity()
            updateGateway.openRequiredUpdate(host, host)
        }
    }
}

/**
 * Back on a terminal screen sends the app to the background, as Back on a root screen does since Android 12.
 * MainActivity's fallback would call navigateUp(), which from a lone screen that is not the graph's start relaunches
 * the app at splash (NavController.tryRelaunchUpToGeneratedStack) and lands here again. The activity stays alive, so
 * a sideload download started here keeps going. Opened by a deep link inside another app's task, it finishes instead,
 * so Back returns to that app rather than sending its task away too.
 *
 * Bound to the activity's lifecycle, after MainActivity's own: a callback is re-added on top at each ON_START, in
 * the order its owners start. A view-bound one starts before the activity, so after any stop/start (back from the
 * store) it fell under the fallback again. Removed with the view.
 */
internal fun Fragment.leaveAppOnBack() {
    val activity = requireActivity()
    val callback = activity.onBackPressedDispatcher.addCallback(activity) {
        if (activity.isTaskRoot) activity.moveTaskToBack(true) else activity.finish()
    }
    viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = callback.remove()
    })
}
