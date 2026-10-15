# AGENTS.md — PoleX

Guidance for AI coding agents (and humans) working in this repository.

## What this is

PoleX is a home-lab server status + Wake-on-LAN system:

- `backend/` — Go + Gin REST API. Holds a configurable list of servers,
  health-checks them on an interval (TCP connect, or ICMP ping, per server),
  and sends WOL magic packets on demand. All `/api` routes (except `/api/health` and
  `/api/login`) require a bearer token from `POST /api/login`; the
  username/password come from `backend/.env`. See `backend/AGENTS.md`.
- `android/` — Android app (Kotlin, Jetpack Compose, Glance widget).
  Dashboard with a per-server power toggle (wake when offline, SSH
  power-off when online), settings for backend URL and server list, and a
  home-screen status widget. See `android/AGENTS.md`.
- `scripts/` — helper scripts (bash): `start-backend.sh` (Docker or local
  Go), `build-apk.sh` (Docker Compose or local gradle), `test-emulator.sh`
  (smoke-test the APK on a Dockerized Android emulator).
- `scripts/windows/` — PowerShell (5.1+) equivalents of the three scripts
  for Windows + Docker Desktop: `start-backend.ps1`, `build-apk.ps1`
  (`-Task debug|release`, `-Method docker|local`), `test-emulator.ps1`
  (`-Build`, `-Keep`, `-ApkPath`).

## Data flow

``` Android app ──POST /api/login──▶ backend (issues bearer token; creds from backend/.env)
     │                              │
     │ GET /api/status              │ TCP/ping-checks servers every CHECK_INTERVAL
     │ (all calls carry `Authorization: Bearer <token>`)
     │ POST/PUT/DELETE /api/servers (configuration)
     │ POST /api/servers/:id/wol    (wake action)
     ▼
backend ──UDP magic packet──▶ target server NIC
```

Everything under `/api` except `GET /api/health` and `POST /api/login`
requires a bearer token. The app stores the token in Prefs after login and
re-routes to the login screen on any 401.

The app auto-refreshes status every 15 s; the widget's cache is refreshed
in the background roughly every 15 minutes by a self-rescheduling inexact
alarm (`WidgetAlarm` → `StatusWorker.enqueueNow`, because the system may
defer the WorkManager periodic job) plus the 15-min WorkManager job, on
every in-app status poll, and on the widget's system update tick
(≥30 min, via `ServerWidgetProvider.onUpdate` → `StatusWorker.enqueueNow`).

## Conventions

- Keep the REST API contract stable; it is the shared boundary between the
  two modules. If you change a field in the status payload, update:
  `backend/main.go` (`StatusView`), `android/.../Model.kt` (`SrvStatus` +
  `parseStatusArray`), and the README tables. Same for auth: `backend/auth.go`
  (`/api/login` → `{ok, token}`, 401 → `{ok:false,error:...}`) ↔
  `android/.../LoginScreen.kt`. Login also takes an optional `remember`
  (remember-me checkbox → 30-day token via `POLEX_REMEMBER_TTL`; regular
  logins use `POLEX_TOKEN_TTL`) — token TTLs are per-token in `auth.go`.
- The app stores the token in Prefs after login and re-routes to the login
  screen on a genuine 401. A connection-level failure (backend unreachable,
  `Api.Result.code == -1`) is "disconnected": the token/session are kept and
  each fetch cycle first silently retries 3× at 2 s. The connection state is
  owned by the supervisor in `android/.../ConnStatus.kt` (`ConnStatus`
  CHECKING/CONNECTED/DISCONNECTED + `ConnSupervisor`: while disconnected the
  checks repeat on a 10 s doubling backoff, capped at 60 s, until the backend
  returns and the dashboard recovers automatically — no user tap or restart):
  the dashboard shows a header indicator (green/red/gray, content description
  "Backend connection: valid / …last check failed / …checking") and keeps the
  server list visible but grayed out and read-only; the server detail page
  shows the full-screen disconnected/retry page only when every silent retry
  fails (`Disconnect.screenVisibleAfterCycle`, the sole show/hide decision,
  `android/.../Disconnect.kt`).
- The backend version has one source of truth: `const Version` in
  `backend/version.go`, served by `GET /api/version` (behind the token
  middleware). The app shows it beside its own `BuildConfig.VERSION_NAME`
  on the settings menu footer; the mismatch compare/highlight logic is
  `android/.../Versions.kt` (tests: `VersionsTest.kt`). When bumping one
  side's version, bump the other's constant/versionName to match.
- Backend config is persisted in `backend/data.json` (plain run) or
  `backend/data/data.json` (Docker run). Both are gitignored. Do not commit.
  Login credentials come from `backend/.env` (gitignored; template in
  `.env.example`) — never hardcode them or commit a real `.env`.
- The backend runs plain HTTP on the LAN; token auth is the only intended
  protection. Do not add TLS scaffolding unless asked.
- Backend: tiny test suite (`version_test.go`); verify changes with
  `go vet ./...`, `go test ./...`, and a live `curl` round-trip (see
  `backend/AGENTS.md` for the smoke test).
- Android: JVM unit tests live in `android/app/src/test/` (no device needed);
  run them with `gradle :app:testDebugUnitTest` alongside `:app:assembleDebug`.
- README files double as the API contract documentation — keep them in sync.

## Convenience scripts (`scripts/`)

```sh
./scripts/start-backend.sh            # backend via Docker Compose; falls back
                                      # to a local Go build if docker is unusable
ADDR=":9000" CHECK_INTERVAL=5s ./scripts/start-backend.sh

./scripts/build-apk.sh                # debug APK via android/docker-compose.yml
                                      # (forces a fresh image: down -v + --no-cache,
                                      #  verifies the in-image source fingerprint
                                      #  before compiling — don't skip it)
./scripts/build-apk.sh release        # release APK (signed with the debug key)
./scripts/build-apk.sh local debug    # local gradle (needs JDK 17)

./scripts/test-emulator.sh            # emulator smoke test (needs /dev/kvm)
./scripts/test-emulator.sh --keep     # ...and leave it running for manual use

# Windows equivalents (PowerShell, Docker Desktop):
#   .\scripts\windows\start-backend.ps1
#   .\scripts\windows\build-apk.ps1 -Task release
#   .\scripts\windows\test-emulator.ps1 -Keep
```

APKs land in `android/dist/`.

On Windows, the `scripts/windows/*.ps1` scripts mirror these; the emulator
needs Docker Desktop's WSL2 backend (KVM), instead of a host `/dev/kvm`.

`test-emulator.sh` uses `android/docker-compose.emulator.yml` (Google's official
prebuilt emulator image, KVM-backed). It verifies: app window focused, no fatal
logcat crashes, and writes a screenshot to `android/dist/`. It does NOT
replace on-device testing of the Glance widget (widgets render differently in
the emulator's launcher).

## Quick orientation

| Task                     | Start here                    |
|--------------------------|-------------------------------|
| Change health logic      | `backend/main.go: checkServer` (→ `checkHost` / `pingHost`) |
| Change WOL behaviour     | `backend/wol.go`              |
| Change API routes        | `backend/main.go: main()`     |
| Change backend version   | `backend/version.go` (constant `Version`) |
| Change auth/login        | `backend/auth.go` + `env.go`  |
| Change dashboard UI      | `android/.../MainScreen.kt`   |
| Change server detail page| `android/.../ServerDetailScreen.kt` |
| Change server reorder    | `backend/main.go: reorderServers` + `android/.../SettingsScreen.kt` (drag) |
| Change settings UI       | `android/.../SettingsScreen.kt` |
| Change login UI          | `android/.../LoginScreen.kt`  |
| Change widget            | `android/.../ServerWidget.kt` |
| Change networking        | `android/.../Api.kt`          |
| Change shared config     | `android/.../Prefs.kt`        |
| Change disconnected/retry| `android/.../ConnStatus.kt` (ConnStatus, ConnSupervisor) + `android/.../Disconnect.kt` (RetryTracker, runFetchCycle, screenVisibleAfterCycle, DisconnectedScreen) |

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
