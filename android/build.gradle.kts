plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("androidx.baselineprofile") version "1.4.1" apply false
    id("com.google.dagger.hilt.android") version "2.58" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
    // Plan B (ANDROID-AUTH-01): Firebase Auth on Android requires the
    // google-services Gradle plugin to parse app/google-services.json and
    // generate the BuildConfig + Firebase init values at compile time.
    id("com.google.gms.google-services") version "4.5.0" apply false
}
