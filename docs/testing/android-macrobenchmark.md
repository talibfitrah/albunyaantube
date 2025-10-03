# PERF-ANDROID-01 – Android Macrobenchmark Baseline

This guide bootstraps the macrobenchmark workstream for endless scroll and download UX measurements.

## Targets
- Cold start (`StartupMode.COLD`) ≤2.5 s on Pixel 4a, Moto G Power.
- Endless scroll `HomeFragment` frame time ≤11 ms for ≥90% frames.
- Download enqueue → notification visible <3 s.

## Prerequisites
- Android Studio Jellyfish or later with Macrobenchmark plugin.
- Physical devices: Pixel 4a (API 34), Moto G Power 2023 (API 34).
- Benchmark module at `android/macrobenchmarks` (create via AGP template if absent).

## Setup
1. Enable profileable builds in `app/build.gradle.kts` for the `benchmark` build type. The
   app module uses a dedicated `benchmark` build that inherits `release`, signs with the
   debug keystore, and flips a manifest placeholder:

   ```kotlin
   defaultConfig {
       manifestPlaceholders["profileable"] = "false"
   }

   buildTypes {
       getByName("release") {
           signingConfig = signingConfigs.getByName("debug")
       }

       create("benchmark") {
           initWith(getByName("release"))
           signingConfig = signingConfigs.getByName("debug")
           matchingFallbacks += listOf("release")
           manifestPlaceholders["profileable"] = "true"
       }
   }
   ```
   Ensure the application manifest exposes the placeholder via a
   `<profileable android:shell="${profileable}" tools:targetApi="29" />`
   element inside the `<application>` block.
2. Create benchmark cases under `android/macrobenchmarks/src/main/java` (the `com.android.test` plugin treats the `main` source set as the instrumentation payload):
   - `ColdStartBenchmark`: launches `MainActivity` using `MacrobenchmarkRule`.
   - `HomeScrollBenchmark`: scrolls RecyclerView using `UiDevice` gestures.
   - `DownloadFlowBenchmark`: triggers download CTA on seeded content via `UiAutomator`.
   Depend on both `androidx.benchmark:benchmark-macro-junit4` and
   `androidx.benchmark:benchmark-junit4` so the instrumentation runner is packaged with the
   test APK. The target app must include `androidx.profileinstaller:profileinstaller:1.3.1`
   (or newer) so shader cache drops succeed during macrobenchmark runs.
3. Generate Baseline Profiles after each run:
   ```bash
   ./gradlew :macrobenchmarks:collectBaselineProfile
   ```

## Execution
```bash
./gradlew :macrobenchmarks:connectedBenchmarkAndroidTest
```

(`Macrobenchmark` rules are injected automatically; if you need to run a different rule (for
example `BaselineProfile` when regenerating profiles), pass
`-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=<value>`—the
Gradle script will honour the override without duplicating arguments.)

The Gradle task copies `benchmarkData.json` into `android/macrobenchmarks/build/outputs/connected_android_test_additional_output/benchmark/connected/<device>/`. If you need to pull manually from the device, use:

```bash
adb shell run-as com.albunyaan.tube.macrobenchmarks cat files/benchmarkData.json \
  > android/macrobenchmarks/build/outputs/benchmark-results/benchmarkData.json
```

(`files/benchmarkData.json` is created by `androidx.benchmark.junit4.AndroidBenchmarkRunner` when the `androidx.benchmark.output.enable=true` instrumentation argument is present.)

## Reporting
- Upload JSON summaries to `perf/android-scroll.csv` (append timestamp, device, metric, pass/fail). Initial placeholder row committed 2025-10-08; replace `uncollected` once devices run.
- Update Grafana `Perf:Android` dashboard via Cloud Storage ingestion job.
- Raise ticket if frame time > budget or cold start exceeds 2.5 s.

## Troubleshooting
- Disable animations on device via `adb shell settings put global window_animation_scale 0` etc.
- If `adb` disconnects, rerun with `--no-daemon` to avoid gradle caching.
- Use `adb shell dumpsys gfxinfo com.albunyaan.tube` for additional frame stats.

## Ownership
- Android Lead (primary)
- QA Performance cohort for validation runs
