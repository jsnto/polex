#!/bin/sh
# PoleX emulator smoke pipeline - runs INSIDE the emulator container.
#
# android/docker-compose.emulator.yml mounts this file read-only at
# /pipeline/emulator-pipeline.sh; scripts/test-emulator.sh and
# scripts/windows/test-emulator.ps1 both exec it, so the boot/install/
# verify logic has one implementation (no bash/PowerShell drift on the
# part that actually drives the emulator).
#
# Usage: sh /pipeline/emulator-pipeline.sh <apk-basename>
#   <apk-basename>: file name of the APK; the container sees the host's
#   android/dist at /apk (read-only), so the APK must live there.
#
# The ADB binary defaults to the image's own platform-tools adb (the
# emulator runs with -skip-adb-auth); ADB env var overrides it (used by
# the host's throwaway tests with a stub adb).
#
# Prints PASS/FAIL lines and "=== SMOKE TEST PASSED/FAILED ==="; exits 0
# when every check passes, 1 otherwise. Writes the screenshot to
# /tmp/screenshot.png (the host script copies it out to android/dist/).

set -u

ADB="${ADB:-/android/sdk/platform-tools/adb}"
[ $# -ge 1 ] || { echo "ERROR: usage: $0 <apk-basename>" >&2; exit 2; }
APK_BASE="$1"
PKG="com.polex.app"
ACTIVITY="com.polex.app.MainActivity"
FAIL=0

echo "==> Waiting for boot (up to ~5 min)"
if ! $ADB wait-for-device; then
    echo "ERROR: 'adb wait-for-device' failed - emulator did not come up" >&2
    exit 1
fi
BOOTED=0
i=0
while [ "$i" -lt 60 ]; do
    B=$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
    if [ "$B" = "1" ]; then BOOTED=1; break; fi
    i=$((i + 1))
    sleep 5
done
if [ "$BOOTED" -ne 1 ]; then
    echo "ERROR: emulator did not finish booting" >&2
    exit 1
fi
echo "==> Emulator booted"

echo "==> Installing APK (/apk/$APK_BASE)"
if ! $ADB install -r "/apk/$APK_BASE"; then
    echo "FAIL: adb install failed"
    exit 1
fi

echo "==> Launching $ACTIVITY"
$ADB shell am start -n "$ACTIVITY" >/dev/null
sleep 5

echo "--- focused window ---"
FOCUS=$($ADB shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -2 || true)
echo "$FOCUS"
if printf '%s\n' "$FOCUS" | grep -q "$PKG"; then
    echo "PASS: app window is focused"
else
    echo "FAIL: app window is not focused"
    FAIL=1
fi

CRASHES=$($ADB logcat -d -s AndroidRuntime:E 2>/dev/null | tail -10 || true)
if [ -n "$CRASHES" ]; then
    echo "FAIL: fatal exception in logcat:"
    echo "$CRASHES"
    FAIL=1
else
    echo "PASS: no fatal crashes in logcat"
fi

if $ADB exec-out screencap -p > /tmp/screenshot.png 2>/dev/null && [ -s /tmp/screenshot.png ]; then
    echo "PASS: screenshot captured (/tmp/screenshot.png)"
else
    echo "WARN: could not capture screenshot"
    rm -f /tmp/screenshot.png
fi

if [ "$FAIL" -eq 0 ]; then
    echo "=== SMOKE TEST PASSED ==="
else
    echo "=== SMOKE TEST FAILED ==="
fi
exit "$FAIL"
