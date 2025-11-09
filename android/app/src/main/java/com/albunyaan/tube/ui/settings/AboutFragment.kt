package com.albunyaan.tube.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textview.MaterialTextView

/**
 * About screen showing app version, licenses, and links.
 */
class AboutFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_about, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar(view)
        setupVersionInfo(view)
        setupLinks(view)
    }

    private fun setupToolbar(view: View) {
        view.findViewById<MaterialToolbar>(R.id.toolbar)?.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupVersionInfo(view: View) {
        view.findViewById<MaterialTextView>(R.id.versionText)?.text =
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }

    private fun setupLinks(view: View) {
        view.findViewById<View>(R.id.websiteItem)?.setOnClickListener {
            openUrl("https://albunyaan.tube")
        }

        view.findViewById<View>(R.id.privacyItem)?.setOnClickListener {
            openUrl("https://albunyaan.tube/privacy")
        }

        view.findViewById<View>(R.id.termsItem)?.setOnClickListener {
            openUrl("https://albunyaan.tube/terms")
        }

        view.findViewById<View>(R.id.licensesItem)?.setOnClickListener {
            openUrl("https://albunyaan.tube/licenses")
        }

        view.findViewById<View>(R.id.githubItem)?.setOnClickListener {
            openUrl("https://github.com/albunyaan/albunyaan-tube")
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }
}

