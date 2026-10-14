#!/usr/bin/env bash
# Smoke-tests the PoleX APK on a Dockerized Android emulator.
#
# Pipeline:
#   1. build the debug APK (if missing or --build)
#   2. start the backend (skipped if already answering on :8080)
#   3. boot an Android emulator in Docker (needs /dev/kvm)
#   4. install the APK and launch the app
#   5. verify: app window is focused, no fatal crashes, grab a screenshot
#
# Usage:
#   ./scripts/test-emulator.sh               # full smoke test
#   ./scripts/test-emulator.sh --build       # force APK rebuild first
#   ./scripts/test-emulator.sh --keep        # leave emulator+backend running
#   ./scripts/test-emulator.sh --apk path    # use a specific APK
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
APK="$ANDROID_DIR/dist/polex-debug.apk"
COMPOSE="$ANDROID_DIR/docker-compose.emulator.yml"
# Google's official prebuilt image (google/android-emulator-container-scripts).
# us-docker.pkg.dev is anonymous-pull; ghcr.io/gcr.io are blocked on some
# networks (and the old "reviewdog" image name didn't exist publicly).
EMULATOR_IMAGE="${EMULATOR_IMAGE:-us-docker.pkg.dev/android-emulator-268719/images/30-google-x64-no-metrics:30.1.2}"
APK_PACKAGE="com.polex.app"
APK_ACTIVITY="com.polex.app.MainActivity"
BACKEND_URL="http://localhost:8080"

DO_BUILD=0
KEEP=0
for arg in "$@"; do
    case "$arg" in
        --build) DO_BUILD=1 ;;
        --keep)  KEEP=1 ;;
        --apk)   shift; APK="${1:-}" ;;
        -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown flag: $arg" >&2; exit 2 ;;
    esac
done

cleanup() {
    if [ "$KEEP" -eq 1 ]; then return; fi
    echo "==> Tearing down"
    docker compose -f "$COMPOSE" down --remove-orphans >/dev/null 2>&1 || true
    if [ -n "${BACKEND_PID:-}" ]; then
        kill "$BACKEND_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# --- prerequisites -----------------------------------------------------------
command -v docker >/dev/null 2>&1 || { echo "ERROR: docker required" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "ERROR: cannot talk to docker daemon (usermod -aG docker \$USER?)" >&2; exit 1; }
if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
    echo "ERROR: /dev/kvm not accessible — the emulator needs KVM (usermod -aG kvm \$USER, then re-login)" >&2
    exit 1
fi

# --- 1. APK ------------------------------------------------------------------
if [ $DO_BUILD -eq 1 ] || [ ! -f "$APK" ]; then
    echo "==> Building debug APK"
    "$ROOT/scripts/build-apk.sh"
fi
[ -f "$APK" ] || { echo "ERROR: APK not found: $APK" >&2; exit 1; }
echo "==> APK: $APK"

# --- 2. backend ----------------------------------------------------------------
if curl -sf -m 2 "$BACKEND_URL/api/health" >/dev/null 2>&1; then
    echo "==> Backend already running at $BACKEND_URL"
else
    echo "==> Starting backend (local Go build)"
    cd "$ROOT/backend"
    command -v go >/dev/null 2>&1 || { echo "ERROR: need go (or start the backend manually)" >&2; exit 1; }
    go build -o polex-backend .
    CHECK_INTERVAL=5s ./polex-backend &
    BACKEND_PID=$!
    for _ in $(seq 1 10); do
        curl -sf -m 2 "$BACKEND_URL/api/health" >/dev/null 2>&1 && break
        sleep 1
    done
    curl -sf -m 2 "$BACKEND_URL/api/health" >/dev/null 2>&1 \
        || { echo "ERROR: backend did not come up" >&2; exit 1; }
    echo "==> Backend up (pid $BACKEND_PID)"
fi

# The image ships its own adb client+server (emulator runs with
# -skip-adb-auth); use the full path since PATH inside the image is
# not guaranteed to include platform-tools.
adb() { docker compose -f "$COMPOSE" exec -T emulator /android/sdk/platform-tools/adb "$@"; }

# The container mounts ./dist at /apk (read-only), so the APK to install must
# live there. Copy a custom --apk path in if it doesn't already.
APK_IN_CONTAINER="/apk/$(basename "$APK")"
if [ "$(cd "$(dirname "$APK")" && pwd)" != "$ANDROID_DIR/dist" ]; then
    cp "$APK" "$ANDROID_DIR/dist/"
fi

# --- 3. emulator ---------------------------------------------------------------
echo "==> Booting Android emulator in Docker (image: $EMULATOR_IMAGE)"
docker compose -f "$COMPOSE" up -d  # EMULATOR_IMAGE is inherited via env
echo "==> Waiting for boot (up to ~5 min)"
adb wait-for-device
BOOTED=0
for _ in $(seq 1 60); do
    B="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    [ "$B" = "1" ] && { BOOTED=1; break; }
    sleep 5
done
[ $BOOTED -eq 1 ] || { echo "ERROR: emulator did not finish booting" >&2; exit 1; }
echo "==> Emulator booted"

# --- 4. install + launch ---------------------------------------------------------
echo "==> Installing APK"
adb install -r "$APK_IN_CONTAINER"

echo "==> Launching $APK_ACTIVITY"
adb shell am start -n "$APK_ACTIVITY" >/dev/null
sleep 5

# --- 5. verify -------------------------------------------------------------------
FAIL=0

FOCUS="$(adb shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -2 || true)"
echo "--- focused window ---"
echo "$FOCUS"
if ! grep -q "$APK_PACKAGE" <<<"$FOCUS"; then
    echo "FAIL: app window is not focused"; FAIL=1
else
    echo "PASS: app window is focused"
fi

CRASHES="$(adb logcat -d -s AndroidRuntime:E 2>/dev/null | tail -10 || true)"
if [ -n "$CRASHES" ]; then
    echo "FAIL: fatal exception in logcat:"; echo "$CRASHES"; FAIL=1
else
    echo "PASS: no fatal crashes in logcat"
fi

SHOT="$ROOT/android/dist/screenshot-$(date +%Y%m%d-%H%M%S).png"
if adb exec-out screencap -p > "$SHOT" 2>/dev/null && [ -s "$SHOT" ]; then
    echo "PASS: screenshot saved: $SHOT"
else
    echo "WARN: could not capture screenshot"
fi

echo
if [ $FAIL -eq 0 ]; then
    echo "=== SMOKE TEST PASSED ==="
else
    echo "=== SMOKE TEST FAILED ==="
fi
[ $KEEP -eq 1 ] && {
    echo "Emulator left running. Useful commands:"
    echo "  docker compose -f android/docker-compose.emulator.yml exec emulator /android/sdk/platform-tools/adb shell"
    echo "  docker compose -f android/docker-compose.emulator.yml down"
}
exit $FAIL
