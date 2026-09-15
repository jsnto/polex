# AGENTS.md — backend/

Go + Gin REST API for PoleX. Single binary, no database (JSON file).

## Build & run

```sh
# from the repo root (preferred; Docker or local Go fallback):
../scripts/start-backend.sh

# or manually:
go mod tidy
go vet ./...
go build -o polex-backend .
./polex-backend            # listens on :8080, creates data.json on first run
```

## Environment variables

| Var              | Default | Meaning                                       |
|------------------|---------|-----------------------------------------------|
| `ADDR`           | `:8080` | listen address                                |
| `CHECK_INTERVAL` | `10s`   | duration string; how often servers are probed |
| `WOL_BROADCAST`  | –       | extra WOL UDP targets, comma separated (optional `:port`) |
| `POLEX_USERNAME` | `admin` | login username (set real value in `.env`)     |
| `POLEX_PASSWORD` | `polex` | login password (set real value in `.env`)     |
| `POLEX_TOKEN_TTL`| `720h`  | lifetime of issued auth tokens (Go duration)  |

`loadDotEnv(".env")` (see `env.go`) reads `KEY=VALUE` from a `.env` file next
to the working directory before `os.Getenv` is consulted, so a plain run picks
up `backend/.env`. Under Docker the values arrive as real env vars via
`docker-compose.yml` interpolation, which takes precedence. `.env` is
gitignored; copy `backend/.env.example` to get one. If no credentials are set
the backend runs with `admin`/`polex` and logs a loud `WARN` — never ship that.

## Layout

- `main.go` — models, file persistence, health-check loop (TCP connect +
  ICMP ping via `checkServer`/`checkHost`/`pingHost`), HTTP handlers,
  routing (routes are registered in `main()`), CORS middleware.
- `auth.go` — auth config from env, in-memory session/token store,
  `POST /api/login` handler, and the `authMiddleware` bearer-token guard.
- `env.go` — minimal `.env` loader (`loadDotEnv`).
- `wol.go` — `buildWoLPacket`, `validMac`, `subnetBroadcast24`, `sendWoL`.
- `version.go` — backend version constant (`Version`, keep in sync with the
  app's `versionName`) and the `GET /api/version` handler.
- `data.json` — runtime config (gitignored), used by a plain `./polex-backend`
  run. Format: `{"servers": [...], "nextId": N}`.
- `data/` — runtime config dir for the Docker run (`data/data.json`,
  gitignored; container user uid 10001 must own it).
- `Dockerfile` — multi-stage build (golang:1.22-alpine → alpine:3.20,
  non-root uid 10001, WORKDIR `/app/data`). The runtime stage installs
  `iputils-ping` — ping health checks (`check == "ping"`) shell out to the
  `ping` binary and report offline forever without it.
- `docker-compose.yml` — `network_mode: host` (required for WOL broadcasts),
  mounts `./data:/app/data`.
- `docker-compose.dev.yml` — dev override: `go run .` against a bind-mounted
  source copy (uses the Dockerfile's golang `build` stage); combine with the
  base file via `-f docker-compose.yml -f docker-compose.dev.yml`.

### Stale-container rule (learned the hard way)

`docker compose up --build` can keep a **running container from an old
image**, which silently drops newer JSON fields (e.g. `check`) and replays
old behaviour. Always `docker compose down` before `up` — the root
`scripts/start-backend.sh` does this automatically; a bare manual
`docker compose up` in this dir does not.

### Data persistence (bind mounts apply at container CREATION)

Config lives in `data/data.json` (Docker) / `data.json` (plain run). The
`./data:/app/data` bind mount **only takes effect when the container is
created** from the compose file that contains it. A container created from
an older compose (or `docker run`) without the mount writes its config to
the container's **writable layer**: every `docker restart` wipes that layer
and the backend re-seeds the default NAS/Workstation servers — config is
"lost" (it's still in `docker ps` history / `docker run <id> cat
/app/data/data.json` if quick). Symptom: fresh default servers after a
restart + `data/data.json` missing on disk. Fix: recreate the container so
the current compose's mount applies:

```sh
cd backend
docker compose down          # remove the stale container
[ -f data/data.json ] || {   # one-time migration: backfill the bind target
  echo "WARN: data/data.json missing — if your old container had servers,
recreate it once (without removing) and copy /app/data/data.json here,
or re-add the servers in the app."
}
docker compose up -d --build
```

After recreation, verify persistence: `PUT` a server, `docker compose
restart backend`, then `GET /api/servers` — it must still be there, and
`data/data.json` must exist on disk. Also: `save()` writes
`data.json.tmp` **inside the dir** then renames, so the container user
(uid 10001) needs write permission on the *directory* (`chown 10001 data`
or, without root, `chmod 777 data`) — otherwise every save logs
`could not write data.json.tmp: permission denied`.

## REST API

Auth: everything under `/api` except `GET /api/health` and `POST /api/login`
requires `Authorization: Bearer <token>`. A token comes from `POST /api/login`
(`{username,password}` → `{ok, token}`, `401 {ok:false,error:"invalid credentials"}`
on bad creds). Protected routes answer `401 {ok:false,error:"unauthorized"}`
without/with a bad token. Tokens are random 64-hex strings kept in memory
(`auth.go`), expiring after `POLEX_TOKEN_TTL`; a backend restart invalidates
all of them (the app's 401 hook re-routes to login).

| Method | Path                 | Notes                                          |
|--------|----------------------|------------------------------------------------|
| GET    | `/api/health`        | `{ok, time}` — **public** (used by scripts)   |
| POST   | `/api/login`         | `{username,password}` → `{ok, token}` — **public** |
| GET    | `/api/servers`       | configured servers                             |
| POST   | `/api/servers`       | body `{name, ip, mac?, port?, check?}` → validates, runs an immediate check |
| POST   | `/api/servers/reorder` | body `{order: [id, ...]}` → reorders the list (drives display + `/api/status` order) |
| PUT    | `/api/servers/:id`   | full replacement of the entry                  |
| DELETE | `/api/servers/:id`   | removes from list, status cache, and disk      |
| GET    | `/api/status`        | live status per server                         |
| POST   | `/api/servers/:id/wol` | sends WOL; returns `{ok, message, lastWolAt}` (200 even on WOL failure — check `ok`) |

`GET /api/status` item: `{id, name, ip, mac, port, check, online, latencyMs, lastChecked, lastWolAt}`.
`lastChecked` is `"never"` if the first check hasn't completed.

## Behaviour notes (don't break these)

- Concurrency: all mutable state lives on `state` guarded by `mu`
  (RWMutex). Health checks snapshot the server list under RLock, then probe
  in goroutines and write results back via `setStatus`.
- `Server.ID` is assigned by the backend; client-supplied IDs are zeroed in
  `normalize()`.
- Health check method is per-server (`Server.Check`, `"tcp"` default or
  `"ping"`). TCP: connect to `ip:port` (3 s dial timeout; port defaults to
  22). Ping: `pingHost` shells out to `ping -c 1 -W 3 ip` (context-bounded to
  5 s) and parses the RTT from `time=X ms`. The `ping` binary is resolved
  once (`pingBinary`); if it's missing, ping servers report offline and a
  warning is logged. `checkServer` dispatches between the two.
- Display order: the server list order is the display order (also the order
  `GET /api/status` emits). `reorderServers` rewrites `s.servers` to match
  the supplied ID list; IDs omitted from the body keep their current relative
  position at the end (a partial list can't drop servers). `nextId` is
  unaffected (IDs are stable; only the array order changes).
- WOL is sent to `255.255.255.255:9`, the /24 broadcast of the server IP, and
  any `WOL_BROADCAST` entries; at least one send must succeed for `ok: true`.
- Config persistence: every add/update/delete **and reorder** writes the whole list to
  `data.json` (or `data/data.json` under Docker) via `save()`, which is
  **atomic** (write `data.json.tmp` + rename) so a crash mid-write can't
  corrupt the file. `load()` on startup falls back to defaults if the file
  is missing or unparseable — a corrupted file loses the list, so keep a
  backup if the config is precious.
- Errors return JSON `{"error": "..."}` with 400/404 (not Gin's default).

## Smoke test

```sh
./polex-backend &
curl -s localhost:8080/api/health
# get a token (credentials from .env / env):
TOKEN=$(curl -s -X POST localhost:8080/api/login \
  -d '{"username":"<user>","password":"<pass>"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/status
curl -s -X POST localhost:8080/api/servers \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"name":"test","ip":"127.0.0.1","mac":"aa:bb:cc:dd:ee:ff","port":8080}'
curl -s -X POST localhost:8080/api/servers/<id>/wol -H "Authorization: Bearer $TOKEN"
curl -s -X DELETE localhost:8080/api/servers/<id> -H "Authorization: Bearer $TOKEN"
# unauthenticated must be 401:
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/status   # expect 401
```

Validation cases to try: bad IP, bad MAC, empty name, port 0 (→ normalized
to 22), duplicate add (IDs must keep incrementing), login with wrong
password (→ 401), a request without/with a bogus token (→ 401).
