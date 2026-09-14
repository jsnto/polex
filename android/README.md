# PoleX Android App

Kotlin + Jetpack Compose dashboard for PoleX: server status at a glance,
one-tap Wake-on-LAN, configurable backend and server list, plus a home
screen widget.

> Agent/code-structure documentation: see [`AGENTS.md`](AGENTS.md).

## Features

- **Login** — signs in against `POST /api/login` (credentials configured in
  the backend's `.env`); the returned bearer token is stored and sent with
  every API call. A 401 (expired/invalidated token) or **Log out** in
  settings returns to the login screen.
- **Dashboard** — auto-refreshes `GET /api/status` every 15 s, and also
  refreshes when you **pull the list down** (Material3 `pullToRefresh`);
  each server
  card shows a green/red status dot, IP, check method (TCP port or ping)
  and latency (or "offline"), last wake time, and a **Wake** power button
  (a stroke-style power symbol — open circle with a vertical line, from
  `res/drawable/ic_power.xml` — tinted by status: green when online, red
  when offline, dimmed when WOL can't be sent) that
  asks for confirmation in a dialog before POSTing
  `/api/servers/:id/wol`. **Tapping a card opens that server's detail page**
  (`ServerDetailScreen`) with live status and its settings.
- **Server detail** — the page a card links to: live status (online/latency,
  last checked, last wake), the configured settings (IP, MAC, check method),
  the same power-icon Wake button, and an **Edit settings** button that
  reuses the settings' add/edit form. System back returns to the dashboard
  (or out of the edit form), not out of the app.
- **Settings** — minimal menu (Backend / Servers / Log out):
  - **Backend** — change the backend URL;
  - **Servers** — list with **Add** (new screen), per-server **Edit**
    (same screen, pre-filled) and **Delete** (with confirm dialog), plus
    **drag-and-drop reordering** (drag the ⋮ handle; committed to the
    backend via `POST /api/servers/reorder`). The add/edit screen also has a
    **Use ping (ICMP)** toggle to check a server with ICMP instead of a TCP
    port connect.
- **Home screen widget** (Glance) — at-a-glance list of all servers
  (dot + name + `12ms`/`off`). Refreshed in the background roughly every
  15 minutes (self-rescheduling inexact alarm via `WidgetAlarm` +
  WorkManager, since the system may defer WorkManager periodic work) and
  on every in-app status refresh.

## Build

Helper script (from repo root, Docker-based, no Android SDK/IDE required):

```sh
./scripts/build-apk.sh                # → android/apk/polex-debug.apk
./scripts/build-apk.sh release        # → android/apk/polex-release.apk
./scripts/build-apk.sh local debug    # local gradle instead of Docker
```

### Docker directly

```sh
cd android
# Recommended: use ../scripts/build-apk.sh — it forces a fresh image
# (down -v + --no-cache) and verifies the in-image source matches the
# working tree before compiling, so stale caches can't cause bad builds.
docker compose up --build apk     # builds debug APK (manual equivalent)
# → apk/polex-debug.apk

# optional: also/instead build a release APK (signed with the debug key,
# so it installs; see the signingConfig in app/build.gradle.kts)
TASKS="assembleRelease" docker compose up apk   # → apk/polex-release.apk
```

The compose file (`docker-compose.yml` + `Dockerfile`) runs JDK 17, Android
SDK 34 and Gradle 8.7 in a container, builds the app, and copies the APKs
to `./apk/`. A named volume caches Gradle dependencies, so only the first
build is slow. Install on a phone with:

```sh
adb install apk/polex-debug.apk
```

### Android Studio / local Gradle

```sh
# one-time if no wrapper present:
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/polex-debug.apk
```

Or open this directory in Android Studio and run (**Build → Build APK(s)**
produces the APK under `app/build/outputs/apk/`).

## Configure

1. Run the backend on your PC (see `../backend`), with credentials set in
   `backend/.env`.
2. Launch the app and **sign in** with those credentials (a login screen is
   shown until a token is stored). On a physical device the default backend
   URL (emulator host loopback `http://10.0.2.2:8080`) is wrong — expand
   **Backend URL…** on the login screen and enter your PC's LAN IP.
   Plain HTTP on the LAN is intentional (`usesCleartextTraffic=true`).
3. In **Settings → Servers** you can add, edit or delete servers (the + icon
   opens a dedicated screen; the pencil icon edits a server in the same
   screen); the **MAC** field is what the Wake button uses — leave it empty
   to list a server without WOL. Toggle **Use ping (ICMP)** to check a
   server with an ICMP echo instead of a TCP port connect.

## Add the widget

Long-press the home screen → **Widgets** → **PoleX** → drag to the home
screen. It appears empty until the first background refresh succeeds or you
open the app once (the app refreshes the widget on every status poll).

The widget lists each server (green/red dot + name + latency) and shows
the up count and data-freshness time on a single bottom line
("2/3 up · updated 14:32") and **tapping it opens the app**.

**Windows:** see `../scripts/windows/build-apk.ps1` and
`../scripts/windows/test-emulator.ps1` (Docker Desktop).
