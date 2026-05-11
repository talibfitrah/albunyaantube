plugins {
    id("com.android.application") version "8.8.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("androidx.baselineprofile") version "1.3.1" apply false
    id("com.google.dagger.hilt.android") version "2.54" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    // Plan B (ANDROID-AUTH-01): Firebase Auth on Android requires the
    // google-services Gradle plugin to parse app/google-services.json and
    // generate the BuildConfig + Firebase init values at compile time.
    id("com.google.gms.google-services") version "4.4.4" apply false
}
