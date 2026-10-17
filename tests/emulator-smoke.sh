#!/usr/bin/env bash
set -Eeuo pipefail

API_LEVEL="$1"
DIAG_DIR="dist/diagnostics"
mkdir -p "$DIAG_DIR"

# Trap to capture diagnostics on script exit/error
capture_diagnostics() {
    local exit_code=$?
    echo "--- EXIT TRAP: Capturing diagnostics (Exit code: $exit_code) ---"

    # Try to capture full logcat
    timeout 10s adb logcat -d > "$DIAG_DIR/logcat_full_api${API_LEVEL}.txt" || true

    # Try to capture screenshot
    timeout 10s adb shell screencap -p /sdcard/screen.png || true
    timeout 10s adb pull /sdcard/screen.png "$DIAG_DIR/screen_api${API_LEVEL}.png" || true

    # Try to capture UI hierarchy
    timeout 10s adb shell dumpsys window > "$DIAG_DIR/dumpsys_window_api${API_LEVEL}.txt" || true

    # Try to capture the app process state specifically
    timeout 10s adb shell dumpsys activity top > "$DIAG_DIR/dumpsys_activity_top_api${API_LEVEL}.txt" || true

    echo "Diagnostics capture complete."
    exit "$exit_code"
}
trap capture_diagnostics EXIT ERR

echo "KVM check:"
emulator -accel-check || true

# Dismiss lock screen
timeout 10s adb shell input keyevent 82 || true

# Install
timeout 60s adb install -r dist/Media-Organizer.apk

CRASH_FOUND=false

for run_idx in {1..5}; do
   echo "--- Cold start run $run_idx ---"
   adb logcat -c || true
   adb shell am force-stop tw.mediaorganizer || true

   timeout 30s adb shell am start -W tw.mediaorganizer/.MainActivity

   sleep 10

   # Check if process is alive, trim CR/LF
   PID=$(adb shell pidof tw.mediaorganizer | tr -d '\r\n' || true)

   if [ -z "$PID" ]; then
     echo "Process tw.mediaorganizer is not running on run $run_idx!"
     CRASH_FOUND=true
     break
   fi

   sleep 10

   adb logcat -d --pid="$PID" > "$DIAG_DIR/logcat_run_${run_idx}_api${API_LEVEL}.txt" || true
   CRASH=$(adb logcat -d --pid="$PID" | grep -E "FATAL EXCEPTION" || true)

   if [[ -n "$CRASH" ]]; then
      echo "Crash detected on run $run_idx: $CRASH"
      CRASH_FOUND=true
      break
   fi

   # Navigate back / background return testing
   adb shell input keyevent 4 # BACK
   sleep 2
done

if [[ "$CRASH_FOUND" == "true" ]]; then
   echo "Smoke test failed."
   exit 1
else
   echo "Smoke test passed."
fi
