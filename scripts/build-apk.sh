#!/usr/bin/env bash
# Builds the PoleX Android APK.
#
# Usage:
#   ./scripts/build-apk.sh                  # debug APK (default)
#   ./scripts/build-apk.sh release          # release APK (signed with debug key)
#   ./scripts/build-apk.sh local debug      # build with local gradle (no docker)
#
# Output: android/dist/polex-debug.apk or android/dist/polex-release.apk
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
APK_DIR="$ANDROID_DIR/dist"

TASK="${1:-debug}"
METHOD="${2:-docker}"   # docker | local

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
    echo "==> Building APK in container (gradle $tasks)"
    cd "$ANDROID_DIR"
    # 1) Fingerprint the host source (relative paths, so it's comparable to
    #    the image copy regardless of the in-container WORKDIR).
    local host_src
    host_src=$(cd app/src && find . -type f | sort | xargs md5sum | md5sum | awk '{print $1}')

    # 2) Remove containers AND the persistent gradle-cache volume. A poisoned
    #    Kotlin incremental-compile cache replays old `e:` errors even on fresh
    #    source, so a plain `down` (no -v) isn't enough.
    docker compose down -v >/dev/null 2>&1 || true

    # 3) Rebuild the image. --no-cache forces COPY . . to re-copy the current
    #    source instead of reusing a cached (stale) layer. (This re-runs the
    #    SDK/gradle install steps; it's the price of a guaranteed-fresh copy.)
    echo "==> Building image (no cache — guarantees fresh source copy)..."
    docker compose build --no-cache apk

    # 4) Hard-gate: verify the image actually contains the current source
    #    BEFORE spending a full compile. This is the failure mode that kept
    #    biting — a silent stale copy making the old errors reappear.
    local image_src
    image_src=$(docker compose run --rm apk sh -c 'cd /workspace/app/src && find . -type f | sort | xargs md5sum | md5sum | awk "{print \$1}"')
    if [ "$host_src" != "$image_src" ]; then
        echo "ERROR: image source != host source — the build is using stale source." >&2
        echo "  host : $host_src" >&2
        echo "  image: $image_src" >&2
        echo "  The Dockerfile's COPY . . did not pick up your working tree." >&2
        echo "  Check you're not shadowing the context; the compose file at" >&2
        echo "  $ANDROID_DIR uses '.' as the build context (android/ dir)." >&2
        exit 1
    fi
    echo "==> Source verified in image (md5 ${host_src:0:12}...)"

    # 5) Build from the verified image. --exit-code-from propagates a
    #    failed gradle run to this script (plain `up` exits 0 even when
    #    the container build fails, which would ship an empty dist/ as
    #    "success").
    TASKS="$tasks" docker compose up --exit-code-from apk apk
}

build_local() {
    cd "$ANDROID_DIR"
    command -v java >/dev/null 2>&1 || { echo "ERROR: need java 17 for local build" >&2; exit 1; }
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
