package com.albunyaan.tube.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.albunyaan.tube.R
import com.albunyaan.tube.update.UpdateGateway
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Terminal screen when [com.albunyaan.tube.update.MinVersionGate] says this install is below
 * the floor (iOS `UpdateRequiredView`). The splash pops the whole graph to reach it, so it is
 * the only destination and Back exits the app.
 */
@AndroidEntryPoint
class UpdateRequiredFragment : Fragment(R.layout.fragment_update_required) {

    @Inject lateinit var updateGateway: UpdateGateway

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialButton>(R.id.updateButton).setOnClickListener {
            // The activity, not viewLifecycleOwner: the sideload download outlives this view.
            val host = requireActivity()
            updateGateway.openRequiredUpdate(host, host)
        }
    }
}
