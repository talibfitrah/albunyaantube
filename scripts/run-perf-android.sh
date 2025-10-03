#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT_DIR/android"

MODE="device"
COLLECT_BASELINE=true

usage() {
  cat <<USAGE
Usage: $(basename "$0") [--mode device|emulator] [--skip-baseline]

Runs the macrobenchmark suite against a connected device/emulator.
When --mode emulator is provided the script suppresses the emulator warning
emitted by androidx.benchmark.

Examples:
  $(basename "$0") --mode device
  $(basename "$0") --mode emulator --skip-baseline
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode)
      MODE="$2"
      shift 2
      ;;
    --skip-baseline)
      COLLECT_BASELINE=false
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

case "$MODE" in
  device)
    unset BENCHMARK_SUPPRESS_ERRORS
    ;;
  emulator)
    export BENCHMARK_SUPPRESS_ERRORS="EMULATOR"
    ;;
  *)
    echo "Unsupported mode: $MODE" >&2
    exit 1
    ;;
endcase

cd "$ANDROID_DIR"

echo "[perf-android] Installing release build on target ($MODE)"
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64} \
  ./gradlew :app:installRelease

if [[ "$COLLECT_BASELINE" == true ]]; then
  echo "[perf-android] Collecting baseline profile"
  JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64} \
    ./gradlew :macrobenchmarks:collectBaselineProfile
fi

echo "[perf-android] Running macrobenchmarks"
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64} \
  ./gradlew :macrobenchmarks:connectedBenchmarkBenchmarkAndroidTest

echo "[perf-android] Outputs available under android/macrobenchmarks/build/outputs/connected_android_test_additional_output"
