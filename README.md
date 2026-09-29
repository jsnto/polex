# PoleX

Home-lab server status + Wake-on-LAN, from your phone.

- **Backend** (Go + Gin): keeps a configurable list of servers, checks each
  one on an interval (a TCP port connect, or an ICMP ping, per server),
  exposes status over REST, sends Wake-on-LAN "magic packets" on demand, and
  can power a server off cleanly over SSH (`systemctl poweroff`, key auth
  with an optional password fallback — per-server opt-in via `sshUser` plus
  `sshKeyPath` and/or `sshPassword`).
- **Android app** (Kotlin, Jetpack Compose):
  - **login screen** — signs in via `POST /api/login` and stores the
    returned bearer token; every API call sends it, and a 401 from the
    backend returns the app to the login screen (token expires after
    `POLEX_TOKEN_TTL`, default 30 days). The **remember me** checkbox
    requests a longer-lived token (30 days, `POLEX_REMEMBER_TTL`);
  - dashboard that auto-refreshes (and refreshes when pulled down) and shows
    each server's status (online dot,
    latency, last wake time) with a single **power** button per server (the
    stroke-style power symbol — open circle with a vertical line — tinted by
    status: green when online, red when offline). The button is a toggle
    driven by the server's current state: **online → power off** (a clean
    `systemctl poweroff` over SSH; dimmed when the server has no SSH power-off
    configured) and **offline → power on** (a Wake-on-LAN magic packet; dimmed
    when no MAC is configured) — each direction asks for confirmation in a
    dialog ("Power off <server>?" / "Send wake to <server>?"); tapping a
    server opens a detail page with its live status and editable settings;
  - settings screen to configure the backend URL and add/remove/reorder
    servers (name, IP, MAC, check port, and the check method — TCP or ping).
    The server list supports **drag-and-drop reordering**; all changes are
    POSTed to the backend. The menu footer shows the **app version**
    (`BuildConfig.VERSION_NAME`) and the **backend version** (`GET
    /api/version`); when they differ, the lower (outdated) side is
    highlighted in amber with a one-line note — an unreachable backend shows
    "unreachable" and is **not** treated as a mismatch (comparison helpers:
    `android/.../Versions.kt`, tests in `VersionsTest.kt`);
  - a **home screen widget** (Glance) — a compact panel listing each server
    (green/red dot + name + latency) with the up count and data-freshness
    time on a single bottom line ("2/3 up · updated HH:mm"); tapping it
    opens the app. A background job (WorkManager + an inexact alarm,
    since the system may defer WorkManager periodic work) refreshes the
    widget roughly every 15 minutes, and the widget also refreshes
    whenever the app polls status.

## Quick start

```sh
./scripts/start-backend.sh               # start the backend (Docker or local Go)
./scripts/build-apk.sh                   # build the Android APK → android/apk/
adb install android/apk/polex-debug.apk    # install on a real device

./scripts/test-emulator.sh               # or smoke-test on a Dockerized
                                         # Android emulator (needs /dev/kvm)
```

`test-emulator.sh` builds the APK (if missing), starts the backend, boots an
emulator via `android/docker-compose.emulator.yml`, installs + launches the
app (which opens on the login screen — sign in with the `.env` credentials
to see the dashboard), checks that its window is focused and logcat has no
fatal crashes, and saves a screenshot to `android/apk/`. Flags: `--build`
(force rebuild), `--keep` (leave emulator/backend running), `--apk <path>`.

`build-apk.sh` forces a fresh Docker image every run (`down -v` +
`--no-cache`) and verifies the in-image source fingerprint matches the
working tree **before** compiling, so a stale cached source can never cause
mystery build errors (a `.dockerignore` keeps host build state out of the
image).

**Windows:** the same three actions have PowerShell versions in
`scripts/windows/` (Docker Desktop required):

```powershell
.\scripts\windows\start-backend.ps1
.\scripts\windows\build-apk.ps1                 # or -Task release
.\scripts\windows\test-emulator.ps1             # or -Build / -Keep / -ApkPath ...
```

## Project layout

```
backend/          Go + Gin REST API  (see backend/README.md, backend/AGENTS.md)
android/          Android app (Kotlin + Compose + Glance widget)
                  (see android/README.md, android/AGENTS.md)
scripts/          helpers: start-backend.sh, build-apk.sh, test-emulator.sh
scripts/windows/  PowerShell equivalents: *.ps1 (Windows + Docker Desktop)
AGENTS.md         guidance for AI coding agents working in this repo
```

## Backend

### Run

```sh
cd backend
go mod tidy
go run .            # listens on :8080, writes data.json
# or from the repo root (preferred; picks Docker or local Go automatically):
#   ./scripts/start-backend.sh
#   ADDR=":9000" CHECK_INTERVAL=5s ./scripts/start-backend.sh
```

Environment variables:

| Var              | Default | Meaning                                        |
|------------------|---------|------------------------------------------------|
| `ADDR`           | `:8080` | listen address                                 |
| `CHECK_INTERVAL` | `10s`   | how often servers are health-checked           |
| `WOL_BROADCAST`  | –       | extra WOL target address(es), comma separated  |
| `POLEX_USERNAME` | `admin` | login username                                 |
| `POLEX_PASSWORD` | `polex` | login password                                 |
| `POLEX_TOKEN_TTL`| `720h`  | how long an issued auth token stays valid      |
| `POLEX_REMEMBER_TTL` | `720h` | how long a "remember me" token stays valid    |

Login credentials come from a `.env` file: `cp backend/.env.example
backend/.env`, then edit `POLEX_USERNAME` / `POLEX_PASSWORD` (the file is
gitignored). If no credentials are set the backend warns loudly and runs
with the `admin`/`polex` defaults.

Server config is persisted in `backend/data.json` (seeded with two example
servers on first run — edit or use the app to change them).

### REST API

All routes except `GET /api/health` and `POST /api/login` require an
`Authorization: Bearer <token>` header (token from `/api/login`);
unauthenticated calls get `401`.

| Method | Path                | Description                                            |
|--------|---------------------|--------------------------------------------------------|
| POST   | `/api/login`        | `{username, password, remember?}` → `{ok, token}` |
| GET    | `/api/health`       | liveness (public)                                      |
| GET    | `/api/servers`      | configured servers                                     |
| POST   | `/api/servers`      | add server `{name, ip, mac?, port?, check?, sshUser?, sshKeyPath?, sshPassword?}` |
| POST   | `/api/servers/reorder` | set display order `{order: [id, ...]}`          |
| PUT    | `/api/servers/:id`  | update server                                          |
| DELETE | `/api/servers/:id`  | remove server                                          |
| GET    | `/api/status`       | live status: `{id,name,ip,mac,port,check,sshUser,sshKeyPath,sshPassword,online,latencyMs,lastChecked,lastWolAt,lastShutdownAt}` |
| POST   | `/api/servers/:id/wol` | send Wake-on-LAN packet to the server's MAC (the power button's **offline** direction) |
| POST   | `/api/servers/:id/shutdown` | clean SSH shutdown (`systemctl poweroff`, key then password auth) (the power button's **online** direction) |
| GET    | `/api/version`        | backend version `{"version":"…"}` (constant `Version` in `backend/version.go`) |

WOL packets are sent via UDP to `255.255.255.255:9` **and** to the /24 subnet
broadcast of the server's IP (e.g. `192.168.1.255:9`), plus any addresses in
`WOL_BROADCAST`.

> **Important:** for WOL to work the backend must be on the same L2 network
> (VLAN/broadcast domain) as the target machine, and the target's NIC/BIOS
> must have Wake-on-LAN enabled.

### Power off over SSH

Servers with `sshUser` set plus at least one credential — `sshKeyPath` and/or
`sshPassword` — can be powered off cleanly from the app (the power button's
**online** direction: `POST /api/servers/:id/shutdown` runs `systemctl
poweroff` over SSH). Credentials are tried in order, key first: the key is
offered, and if sshd rejects it (an authentication failure) the password is
offered next within the same handshake; a key file that fails to load
(missing/unreadable/invalid) falls back to the password on its own when one
is set, with the key error kept visible in the result; with no key path the
password is used directly. A non-auth failure (unreachable, OS not
responsive) never burns the password fallback. The
power button is dimmed for the power-off direction when the server has no SSH
config, exactly like Wake is without a MAC. The private key is a **file on
the backend host** — the config holds only its path (`sshKeyPath`, `~/...`
expanded); under Docker, mount the key dir into the container (see
`backend/docker-compose.yml`). The password is stored in the same per-server
config blob (bearer-token-protected home-lab config — no extra secret store).
Honest limits:

- **In-band only:** the shutdown works only while the target's OS and sshd
  are responsive. If the server is wedged or unreachable the endpoint
  reports it ("server unreachable or OS not responsive") instead of
  pretending — an out-of-band mechanism (BMC, smart plug) is the recovery
  path for that case.
- **Self-shutdown:** if the backend host is itself one of the servers,
  "command accepted; backend unreachable" is *expected* — the backend powers
  off mid-request and the app's disconnected/retry page appears. The
  `lastShutdownAt` record is best-effort here: written after the run
  returns, in-memory only (cleared on restart), so it is often never
  written at all.

### Quick test

```sh
# login first (credentials from backend/.env):
TOKEN=$(curl -s -X POST localhost:8080/api/login \
  -d '{"username":"admin","password":"change-me"}' | jq -r .token)

AUTH="Authorization: Bearer $TOKEN"
curl -s -H "$AUTH" localhost:8080/api/status
curl -s -X POST localhost:8080/api/servers -H "$AUTH" -d '{"name":"nas","ip":"192.168.1.10","mac":"aa:bb:cc:dd:ee:01","port":22}'
# a ping-checked server (ICMP instead of a TCP port):
curl -s -X POST localhost:8080/api/servers -H "$AUTH" -d '{"name":"printer","ip":"192.168.1.40","check":"ping"}'
# reorder the server list (the app's drag-and-drop does this for you):
curl -s -X POST localhost:8080/api/servers/reorder -H "$AUTH" -d '{"order":[2,1]}'
curl -s -X POST localhost:8080/api/servers/1/wol -H "$AUTH"
```

## Android app

### Build

```sh
cd android
docker compose up --build apk        # → android/apk/polex-debug.apk

# ...or use the helper script (docker, or local gradle as fallback/choice):
#   ./scripts/build-apk.sh             # debug APK (docker)
#   ./scripts/build-apk.sh release     # release APK (signed w/ debug key) (docker)
#   ./scripts/build-apk.sh local debug # local gradle, no docker

# ...or manually:
gradle wrapper --gradle-version 8.7  # one-time, if no wrapper present
./gradlew assembleDebug
```

Then install on a phone:

```sh
adb install android/apk/polex-debug.apk
```

### Configure

1. Run the backend on your PC (set credentials in `backend/.env` first — see
   above; the app will ask for the same username/password on first launch).
2. Launch the app and **sign in** on the login screen. On a physical device
   the backend URL is usually wrong (the default is the emulator's host
   loopback `http://10.0.2.2:8080`) — expand **Backend URL…** on the login
   screen and enter your PC's LAN IP, e.g. `http://192.168.1.5:8080`
   (plain HTTP on the LAN is intentional — `usesCleartextTraffic=true`).
   The URL can also be changed later in **Settings → Backend**.
3. Add your servers in **Settings → Servers** (name, IP, MAC, check port);
   each can be edited or deleted from the same screen. The MAC is what the
   power button uses to wake the server (when it is offline); leave it empty
   to list a server without WOL. Toggle
   **Use ping (ICMP)** to check a server with an ICMP echo instead of a TCP
   port connect (the check port is then ignored) — handy for devices that
   don't expose a known open port. The **SSH user** / **SSH key path** /
   **SSH password** fields (all optional) let you power the server off over
   SSH: set the user plus a key path and/or a password, with a key file on
   the machine running the backend (the key is tried first; a rejected or
   broken key falls back to the password when one is set), and the server's
   **power** button is active
   for the power-off direction. Drag the ⋮ handle to **reorder** the list
   (persisted via `POST /api/servers/reorder`).
4. On the dashboard, **tap a server** to open its detail page: live status
   (online/latency/last checked/last wake/last shutdown), the configured
   settings (incl. the SSH user/key), and the same single **power** button
   (stroke power icon, green = online, red = offline; online → power off
   over SSH, offline → wake); system back returns to the dashboard.

### Widget

Long-press the home screen → Widgets → **PoleX** → drag it onto the home
screen. It lists each server (green/red dot + name + latency) and shows
the up count and data-freshness time on a single bottom line
("2/3 up · updated 14:32"). **Tapping it opens the
app**. It refreshes roughly every 15 minutes in the background (a
self-rescheduling inexact alarm + a WorkManager job, because the system
can defer WorkManager's periodic work) and on every app status refresh
(15 s while the app is open).

## Flow

```
 Android app ──POST /api/login──▶  backend (issues bearer token; creds from .env)
     │                              │
     │ all other calls carry        │ polls servers every CHECK_INTERVAL
     │ `Authorization: Bearer` ───▶ │ TCP/ping check per server
     ▼                              ▼
 Android app ◀─GET /api/status────  backend ──UDP magic packet──▶  server NIC
        (config POST/PUT/DELETE /api/servers, reorder POST /api/servers/reorder,
         power toggle: wake POST /api/servers/:id/wol (offline),
         power off POST /api/servers/:id/shutdown (online, SSH))
```
