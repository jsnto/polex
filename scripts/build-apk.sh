#!/usr/bin/env bash
# Builds the PoleX Android APK.
#
# Usage:
#   ./scripts/build-apk.sh                  # debug APK (default)
#   ./scripts/build-apk.sh release          # release APK (signed with the
#                                           # stable polex release key - the
#                                           # ONE permanent key kept outside
#                                           # the repo; see the signing dir
#                                           # below / "Release signing" in
#                                           # android/AGENTS.md)
#   ./scripts/build-apk.sh local debug      # build with local gradle (no docker)
#
# Output: android/dist/polex-debug.apk or android/dist/polex-release.apk
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
APK_DIR="$ANDROID_DIR/dist"

# Stable release signing key: ONE permanent keystore kept OUTSIDE the repo
# (0700 dir, password in 0600 files, referenced by pointer only). The docker
# build mounts it at /release (android/docker-compose.yml); the local build
# exports the env vars below at the host paths. Override the location with
# POLEX_SIGNING_DIR. Never commit it; never regenerate it.
SIGNING_DIR="${POLEX_SIGNING_DIR:-/home/jasonto/polex-signing}"

TASK="${1:-debug}"
METHOD="${2:-docker}"   # docker | local

# Source-fingerprint gate, executed INSIDE the freshly built image. It
# compares the host tree (mounted read-only at /hostsrc, i.e. the live
# working tree) against the image's copy (COPY . . at build time) and
# aborts if they differ — the failure mode that kept biting: a silent
# stale copy making old `e:` errors reappear.
#
# The command is computed in-container so the bash and PowerShell
# build-apk scripts run the exact same one-liner (no platform-specific
# md5 code to drift). It is written to contain NO single quotes (it must
# also sit in a PowerShell single-quoted string in
# scripts/windows/build-apk.ps1) and uses dash-compatible syntax.
FINGERPRINT_GATE='h=$(cd /hostsrc && find . -type f | sort | xargs md5sum | md5sum | awk "{print \$1}"); i=$(cd /workspace/app/src && find . -type f | sort | xargs md5sum | md5sum | awk "{print \$1}"); if [ "$h" != "$i" ]; then echo "ERROR: image source != host source - the build is using stale source." >&2; echo "  host : $h" >&2; echo "  image: $i" >&2; echo "  The Dockerfile COPY . . did not pick up your working tree." >&2; echo "  Check you are not shadowing the context; the compose file in" >&2; echo "  android/ builds from the android/ directory (build context)." >&2; exit 1; fi; echo "==> Source verified in image (md5 $i)"'

# True when the stable release signing key (keystore + credential file)
# exists in the signing dir. Shared by the docker and local release paths.
signing_key_present() {
    [ -f "$SIGNING_DIR/polex-release.jks" ] && [ -f "$SIGNING_DIR/keystore.properties" ]
}

build_docker() {
    if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
        echo "ERROR: docker not available (use 'local' method)" >&2
        exit 1
    fi
    local tasks
    case "$TASK" in
        debug)   tasks="assembleDebug" ;;
        release) tasks="assembleRelease" ;;
        *) echo "ERROR: task must be 'debug' or 'release'" >&2; exit 1 ;;
    esac
    if [ "$TASK" = "release" ] && ! signing_key_present; then
        echo "ERROR: release signing key not found in $SIGNING_DIR (need polex-release.jks + keystore.properties)." >&2
        echo "  The stable release key lives OUTSIDE the repo; set POLEX_SIGNING_DIR" >&2
        echo "  to point at it. A release build must never fall back to a fresh key." >&2
        exit 1
    fi
    echo "==> Building APK in container (gradle $tasks)"
    cd "$ANDROID_DIR"
    # 1) Remove containers AND the persistent gradle-cache volume. A
    #    poisoned Kotlin incremental-compile cache replays old `e:` errors
    #    even on fresh source, so a plain `down` (no -v) isn't enough.
    docker compose down -v >/dev/null 2>&1 || true

    # 2) Rebuild the image. --no-cache forces COPY . . to re-copy the
    #    current source instead of reusing a cached (stale) layer. (This
    #    re-runs the SDK/gradle install steps; it's the price of a
    #    guaranteed-fresh copy.)
    echo "==> Building image (no cache - guarantees fresh source copy)..."
    docker compose build --no-cache apk

    # 3) Hard-gate: verify the image actually contains the current source
    #    BEFORE spending a full compile (the in-container one-liner above,
    #    byte-identical to scripts/windows/build-apk.ps1).
    local host_src_abs
    host_src_abs="$(cd app/src && pwd)"
    docker compose run --rm -T -v "$host_src_abs:/hostsrc:ro" apk sh -c "$FINGERPRINT_GATE"

    # 4) Build from the verified image. --exit-code-from propagates a
    #    failed gradle run to this script (plain `up` exits 0 even when
    #    the container build fails, which would ship an empty dist/ as
    #    "success").
    TASKS="$tasks" docker compose up --exit-code-from apk apk
}

build_local() {
    cd "$ANDROID_DIR"
    command -v java >/dev/null 2>&1 || { echo "ERROR: need java 17 for local build" >&2; exit 1; }
    if [ "$TASK" = "release" ]; then
        # Point the release signingConfig at the host signing dir (the docker
        # path gets the same key via the /release bind mount).
        if ! signing_key_present; then
            echo "ERROR: release signing key not found in $SIGNING_DIR (need polex-release.jks + keystore.properties)." >&2
            echo "  Set POLEX_SIGNING_DIR to point at it. A release build must never fall back to a fresh key." >&2
            exit 1
        fi
        export POLEX_RELEASE_KEYSTORE="${POLEX_RELEASE_KEYSTORE:-$SIGNING_DIR/polex-release.jks}"
        export POLEX_RELEASE_KEYSTORE_PROPS="${POLEX_RELEASE_KEYSTORE_PROPS:-$SIGNING_DIR/keystore.properties}"
    fi
    if [ ! -x ./gradlew ]; then
        echo "==> Creating gradle wrapper (one-time)"
        gradle wrapper --gradle-version 8.7
    fi
    local out
    case "$TASK" in
        debug)   out="app/build/outputs/apk/debug/polex-debug.apk" ;;
        release) out="app/build/outputs/apk/release/polex-release.apk" ;;
        *) echo "ERROR: task must be 'debug' or 'release'" >&2; exit 1 ;;
    esac
    echo "==> Building APK locally"
    ./gradlew "$TASK"
    mkdir -p "$APK_DIR"
    cp "$out" "$APK_DIR/"
}

if [ "$METHOD" = "docker" ]; then
    build_docker
elif [ "$METHOD" = "local" ]; then
    build_local
else
    echo "ERROR: method must be 'docker' (default) or 'local'" >&2
    exit 1
fi

echo ""
echo "==> APK(s) in $APK_DIR:"
ls -lh "$APK_DIR"
echo ""
echo "Install on a connected phone:  adb install $APK_DIR/polex-${TASK}.apk"
