#!/bin/bash
# Phase 0: Collect playback metrics from Android device
# Usage: ./scripts/collect-playback-metrics.sh [output_file] [duration_seconds]
#
# This script captures PlaybackMetrics logs from the connected Android device.
# Run this while testing playback on different videos to establish baseline metrics.
#
# Prerequisites:
# - Android device connected via adb
# - App installed with debug build (metrics logging enabled)
#
# Output format: PlaybackMetrics logs with timestamps
#
# Example:
#   ./scripts/collect-playback-metrics.sh baseline_metrics.txt 300
#   # Captures 5 minutes of playback metrics

OUTPUT_FILE="${1:-playback_metrics_$(date +%Y%m%d_%H%M%S).txt}"
DURATION="${2:-60}"

# Validate duration is a positive integer
if ! [[ "$DURATION" =~ ^[0-9]+$ ]] || [ "$DURATION" -eq 0 ]; then
    echo "ERROR: Duration must be a positive integer (got: $DURATION)"
    exit 1
fi

echo "=== Playback Metrics Collection ==="
echo "Output file: $OUTPUT_FILE"
echo "Duration: ${DURATION}s"
echo ""

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo "ERROR: No Android device connected. Please connect a device and try again."
    exit 1
fi

echo "Connected device:"
adb devices | grep -v "List of devices"
echo ""

# Clear existing logs
echo "Clearing existing logcat..."
adb logcat -c

# Check for timeout command (GNU coreutils) or gtimeout (macOS via brew)
TIMEOUT_CMD=""
if command -v timeout &> /dev/null; then
    TIMEOUT_CMD="timeout"
elif command -v gtimeout &> /dev/null; then
    TIMEOUT_CMD="gtimeout"
fi

echo "Starting metrics collection. Press Ctrl+C to stop early."
echo "Perform playback tests on the device now..."
echo ""

# Collect logs for the specified duration
# Filter for PlaybackMetrics tag
if [ -n "$TIMEOUT_CMD" ]; then
    "$TIMEOUT_CMD" "$DURATION" adb logcat -v time PlaybackMetrics:* *:S | tee "$OUTPUT_FILE"
else
    echo "(timeout command not available - using manual timing for ${DURATION}s)"
    echo "Tip: For better experience on macOS, install with: brew install coreutils"
    # Fallback: run adb logcat in background, sleep for duration, then kill it
    adb logcat -v time PlaybackMetrics:* *:S | tee "$OUTPUT_FILE" &
    LOGCAT_PID=$!
    # Ensure cleanup on script exit or interrupt
    trap "kill $LOGCAT_PID 2>/dev/null; wait $LOGCAT_PID 2>/dev/null" EXIT INT TERM
    sleep "$DURATION"
    kill $LOGCAT_PID 2>/dev/null
    wait $LOGCAT_PID 2>/dev/null
    trap - EXIT INT TERM
fi

echo ""
echo "=== Collection Complete ==="
echo "Logs saved to: $OUTPUT_FILE"
echo ""

# Parse and summarize the collected metrics
echo "=== Quick Summary ==="
if [ -f "$OUTPUT_FILE" ]; then
    echo "Total playback_requested events: $(grep -c 'playback_requested' "$OUTPUT_FILE")"
    echo "Total playback_started events: $(grep -c 'playback_started' "$OUTPUT_FILE")"
    echo "Total playback_failed events: $(grep -c 'playback_failed' "$OUTPUT_FILE")"
    echo "Total first_frame_rendered events: $(grep -c 'first_frame_rendered' "$OUTPUT_FILE")"
    echo "Total rebuffer_started events: $(grep -c 'rebuffer_started' "$OUTPUT_FILE")"
    echo "Total media_source_rebuilt events: $(grep -c 'media_source_rebuilt' "$OUTPUT_FILE")"
    echo "Total error_403 events: $(grep -c 'error_403' "$OUTPUT_FILE")"
    echo ""

    # Extract and show TTFF values
    echo "TTFF values (ms):"
    grep 'ttff_ms=' "$OUTPUT_FILE" | sed -E -n 's/.*ttff_ms=([0-9]+).*/\1/p' | sort -n | uniq -c
    echo ""

    # Extract source types used
    echo "Source types used:"
    grep 'media_source_created' "$OUTPUT_FILE" | sed -E -n 's/.*sourceType=([A-Z_]+).*/\1/p' | sort | uniq -c
    echo ""

    # Show session summaries
    echo "Session summaries (look for 'Playback Session Metrics'):"
    grep -A15 'Playback Session Metrics' "$OUTPUT_FILE" | head -60
fi

echo ""
echo "For full details, view: $OUTPUT_FILE"
echo "Or filter with: adb logcat -s PlaybackMetrics"
