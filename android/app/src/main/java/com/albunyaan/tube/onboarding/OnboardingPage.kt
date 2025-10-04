package com.albunyaan.tube.onboarding

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.albunyaan.tube.R

data class OnboardingPage(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int
)

val onboardingPages = listOf(
    OnboardingPage(
        iconRes = R.drawable.ic_compass,
        titleRes = R.string.onboarding_page1_title,
        descriptionRes = R.string.onboarding_page1_desc
    ),
    OnboardingPage(
        iconRes = R.drawable.ic_headphones,
        titleRes = R.string.onboarding_page2_title,
        descriptionRes = R.string.onboarding_page2_desc
    ),
    OnboardingPage(
        iconRes = R.drawable.ic_download_circle,
        titleRes = R.string.onboarding_page3_title,
        descriptionRes = R.string.onboarding_page3_desc
    )
)
