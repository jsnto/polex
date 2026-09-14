#!/usr/bin/env bash
# Starts the PoleX backend.
#
# Prefers Docker Compose (correct for WOL: host networking).
# Falls back to a local Go build if docker isn't available.
#
# Usage:
#   ./scripts/start-backend.sh            # :8080, 10s check interval
#   ADDR=":9000" CHECK_INTERVAL=5s ./scripts/start-backend.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$ROOT/backend"
ADDR="${ADDR:-:8080}"
CHECK_INTERVAL="${CHECK_INTERVAL:-10s}"

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    echo "==> Starting backend via docker compose (host networking, WOL-safe)"
    cd "$BACKEND_DIR"
    # Container runs as uid 10001 and must be able to CREATE data.json.tmp
    # inside data/ (needs write on the DIR, then it renames over data.json).
    # Prefer proper ownership; fall back to a world-writable dir when
    # sudo isn't available (chown needs root).
    if [ ! -d data ] || [ ! -w data ]; then
        echo "==> Preparing data dir"
        mkdir -p data
        sudo chown 10001 data 2>/dev/null || chmod 777 data
    fi
    # Warn if the config file is missing: the backend will seed the default
    # example servers (config only persists once data/data.json exists here —
    # see AGENTS.md "Data persistence" for why it can go missing).
    if [ ! -f data/data.json ]; then
        echo "WARN: data/data.json not found — the backend will seed the"
        echo "      default example servers. Add your servers in the app;"
        echo "      they will persist in data/data.json from then on."
    fi
    # down first: a plain `up --build` can keep a running container built
    # from an OLD image (same stale-container failure mode as the APK
    # build); dropping it forces a fresh container from the rebuilt image.
    # (data/ is a bind mount, so config survives.)
    docker compose down >/dev/null 2>&1 || true
    exec docker compose up --build
else
    echo "==> docker unavailable, starting backend with local Go build"
    command -v go >/dev/null 2>&1 || { echo "ERROR: need docker or go" >&2; exit 1; }
    cd "$BACKEND_DIR"
    go build -o polex-backend .
    echo "==> Running ./polex-backend (ADDR=$ADDR CHECK_INTERVAL=$CHECK_INTERVAL)"
    exec env ADDR="$ADDR" CHECK_INTERVAL="$CHECK_INTERVAL" ./polex-backend
fi
