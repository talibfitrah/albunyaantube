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
1. Enable profileable builds in `app/build.gradle.kts` for `benchmark` build type.
2. Create benchmark cases:
   - `ColdStartBenchmark`: launches `MainActivity` using `MacrobenchmarkRule`.
   - `HomeScrollBenchmark`: scrolls RecyclerView using `UiDevice` gestures.
   - `DownloadFlowBenchmark`: triggers download CTA on seeded content via `UiAutomator`.
3. Generate Baseline Profiles after each run:
   ```bash
   ./gradlew :macrobenchmarks:collectBaselineProfile
   ```

## Execution
```bash
./gradlew :macrobenchmarks:connectedCheck \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile
```
Collect artefacts from `macrobenchmarks/build/outputs/benchmark`.

## Reporting
- Upload JSON summaries to `perf/android-scroll.csv` (append timestamp, device, metric, pass/fail).
- Update Grafana `Perf:Android` dashboard via Cloud Storage ingestion job.
- Raise ticket if frame time > budget or cold start exceeds 2.5 s.

## Troubleshooting
- Disable animations on device via `adb shell settings put global window_animation_scale 0` etc.
- If `adb` disconnects, rerun with `--no-daemon` to avoid gradle caching.
- Use `adb shell dumpsys gfxinfo com.albunyaan.tube` for additional frame stats.

## Ownership
- Android Lead (primary)
- QA Performance cohort for validation runs
