# PoleX Backend

Go + Gin REST API. Keeps a configurable list of servers, health-checks them
on an interval (a TCP port connect, or an ICMP ping, per server), and sends
Wake-on-LAN magic packets on demand. Servers with optional SSH config can
also be powered off cleanly (`systemctl poweroff` over SSH, key auth with an
optional password fallback).

> Agent/handler documentation: see [`AGENTS.md`](AGENTS.md).

## Run

```sh
# from the repo root (recommended; picks Docker or local Go automatically):
./scripts/start-backend.sh
ADDR=":9000" CHECK_INTERVAL=5s ./scripts/start-backend.sh

# or manually:
cd backend
go mod tidy
go run .          # listens on :8080, creates data.json on first run
```

| Env var          | Default | Meaning                                      |
|------------------|---------|----------------------------------------------|
| `ADDR`           | `:8080` | listen address                               |
| `CHECK_INTERVAL` | `10s`   | how often servers are health-checked         |
| `WOL_BROADCAST`  | –       | extra WOL UDP targets, comma separated       |
| `POLEX_USERNAME` | `admin` | login username (set a real value in `.env`)  |
| `POLEX_PASSWORD` | `polex` | login password (set a real value in `.env`)  |
| `POLEX_TOKEN_TTL`| `720h`  | lifetime of issued auth tokens               |
| `POLEX_REMEMBER_TTL`| `720h` | "remember me" token lifetime (sign-in checkbox) |

Configuration is persisted in `data.json` (seeded with two example servers
on first run; change entries via the app or this API). Credentials come from
the process environment; a `.env` file next to the binary (or `backend/.env`
for Docker compose interpolation) populates it — see `.env.example`:

```sh
cp .env.example .env   # then edit POLEX_USERNAME / POLEX_PASSWORD
```

`.env` is gitignored; the backend falls back to `admin`/`polex` with a loud
warning if no credentials are configured.

## API

All endpoints except `GET /api/health` and `POST /api/login` require an
`Authorization: Bearer <token>` header (token from `/api/login`). Unauth
requests get `401 {"ok": false, "error": "unauthorized"}`.

| Method | Path                   | Auth | Description                        |
|--------|------------------------|------|------------------------------------|
| GET    | `/api/health`          | no   | liveness                           |
| POST   | `/api/login`           | no   | `{username, password, remember?}` → `{ok, token}` (401 on bad creds); `remember: true` grants a token with the `POLEX_REMEMBER_TTL` lifetime |
| GET    | `/api/servers`         | yes  | configured servers                 |
| POST   | `/api/servers`         | yes  | add `{name, ip, mac?, port?, check?, sshUser?, sshKeyPath?, sshPassword?}` |
| POST   | `/api/servers/reorder` | yes  | set order `{order: [id, ...]}`        |
| PUT    | `/api/servers/:id`     | yes  | update server                      |
| DELETE | `/api/servers/:id`     | yes  | remove server                      |
| GET    | `/api/status`          | yes  | live status of all servers         |
| POST   | `/api/servers/:id/wol` | yes  | send Wake-on-LAN packet            |
| POST   | `/api/servers/:id/shutdown` | yes | clean SSH shutdown (`systemctl poweroff`, key then password auth), see below |
| GET    | `/api/version`         | yes  | backend version `{"version":"…"}` (constant `Version` in `version.go`) |

Tokens are random 64-hex-char session tokens kept in memory; they expire
after `POLEX_TOKEN_TTL` (default 30 days) and die on backend restart.
"Remember me" logins (`remember: true`) instead expire after
`POLEX_REMEMBER_TTL` (default 30 days).

`GET /api/status` returns:

```json
[
  {
    "id": 1,
    "name": "NAS",
    "ip": "192.168.1.10",
    "mac": "AA:BB:CC:DD:EE:01",
    "port": 22,
    "check": "tcp",
    "sshUser": "root",
    "sshKeyPath": "/app/keys/polex",
    "sshPassword": "",
    "online": true,
    "latencyMs": 3,
    "lastChecked": "2026-09-09T01:27:07Z",
    "lastWolAt": "",
    "lastShutdownAt": ""
  }
]
```

`POST .../wol` returns `{"ok": true, "message": "WOL packet sent to ...", "lastWolAt": "..."}`.
WOL is sent via UDP to `255.255.255.255:9`, the server's /24 subnet
broadcast, and any `WOL_BROADCAST` targets.

`POST .../shutdown` powers the server off cleanly over SSH and returns
`{"ok": true, "message": "shutdown command accepted", "lastShutdownAt": "..."}`
(200 with `ok: false` + a distinct `message` on failure — check `ok`).
Both `lastWolAt` and `lastShutdownAt` are recorded when the action is
*attempted*, even if it fails, and persist until the backend restarts.

## Health checks

Each server is checked every `CHECK_INTERVAL`. The method is per-server:

- `"check": "tcp"` (default) — a TCP connect to `ip:port` (port defaults to
  22). Success = online; `latencyMs` is the connect round-trip.
- `"check": "ping"` — one ICMP echo to `ip`. The backend shells out to the
  system `ping` (`ping -c 1 -W 3 ip`), which works for a normal user (the
  binary is usually setuid on Linux) and inside the Docker container
  (Docker grants `CAP_NET_RAW` by default). Success = online; `latencyMs` is
  the echo RTT. The `port` is ignored.

If `ping` isn't on `PATH`, ping-based servers report offline and the backend
logs a warning.

## Power off over SSH

A server with `sshUser` set plus at least one credential (`sshKeyPath`
and/or `sshPassword`) gets a power-off action: `POST /api/servers/:id/shutdown`
connects to `ip:22` with that user and runs `systemctl poweroff`.
Credentials are tried in order, key first: if the key path is set the key is
offered, and if sshd rejects it (an authentication failure) the password is
offered next within the same handshake; with no key path the password is used
directly. All-empty = power-off disabled (exactly like an empty MAC disabling
WOL); any incomplete subset (a user with no credential, or credentials
without a user) is rejected by validation. No extra secret store — the
password lives in the same per-server config blob as the other fields
(bearer-token-protected home-lab config):

- **The private key is a file on the backend host.** The config blob
  (`data.json`) holds only the *path* (`sshKeyPath`), never key material.
  A leading `~/` is expanded to the backend's home dir. Under Docker, mount
  the key (e.g. `./ssh:/app/ssh:ro`) and use the in-container path
  (`/app/ssh/id_ed25519`) — see `docker-compose.yml`.
- **Fallback rules:** the password fallback is for *authentication
  failures only*. A key attempt that fails for a non-auth reason (timeout,
  unreachable, OS not responsive — the endpoint reports
  `ok: false, "server unreachable or OS not responsive: ..."`) does not
  consume the password, and a missing/invalid key *file* is a local config
  error (its own distinct message), also not a fallback trigger. The
  `"ssh authentication failed ..."` message is reported only after every
  configured credential (key, then password when set) was tried.
- **In-band limit (the wedge case):** this works only while the target's OS
  and sshd are responsive. If the server is wedged or unreachable the
  command cannot run, and the endpoint says so. Out-of-band mechanisms
  (BMC, smart plug) are the fallback for that case and are not implemented
  here.
- **Success semantics:** `systemctl poweroff` returns as soon as systemd
  enqueues the job, so the response ("shutdown command accepted") arrives
  before the machine is actually off. A connection that dies right after the
  command was accepted is treated as success (the host is powering off and
  tears down the SSH session). One 4.5 s total deadline bounds the WHOLE SSH
  sequence (dial, handshake with every credential attempt, session start,
  exit-status wait), well inside the app's 5 s read timeout.
- **Self-shutdown:** if the backend host is itself one of the servers, the
  response may never arrive — the backend powers off mid-request. That is
  expected: the command was accepted. `lastShutdownAt` is best-effort here:
  it is written after the run returns and is in-memory only (cleared on
  restart, like `lastWolAt`), so the record is often never written at all.
- **Trust note:** host-key checking is intentionally off
  (`ssh.InsecureIgnoreHostKey`) — the backend sits on a trusted LAN behind
  bearer-token auth, matching polex's plain-HTTP posture. A leaked token can
  now power servers off; keep it as protected as `POLEX_PASSWORD`.
  The SSH user needs to be able to run `systemctl poweroff` (root, or a
  polkit/sudo rule on the target).

> **WOL requirements:** the backend must be on the same L2 network as the
> target machine, and the target's NIC/BIOS must have Wake-on-LAN enabled.

## Run with Docker

```sh
# one-time: the container runs as uid 10001 and must own the data dir
# (skips this automatically if you start via ../scripts/start-backend.sh)
sudo chown 10001 data
docker compose down        # drop a stale container (if any) so the rebuilt
                           # image is actually used
docker compose up -d --build
curl -s localhost:8080/api/health
```

`network_mode: host` is intentional — with Docker's default bridge network,
WOL UDP broadcasts (`255.255.255.255:9`) never reach the LAN and WOL fails
silently. The server list is persisted in `./data/data.json`.

### Development (live source via `go run`)

`docker-compose.dev.yml` overrides the service to run `go run .` against a
bind-mounted copy of the source (the golang build stage of the Dockerfile),
so a restart picks up code changes without a forgotten binary rebuild:

```sh
docker compose down
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build
```

Same host networking and `./data` volume as the standard file; drop the
`-f docker-compose.dev.yml` for a normal run.

### If config appears after a restart ("data didn't persist")

The server list persists in `./data/data.json` via the `./data:/app/data`
bind — **but the bind only takes effect when the container is created from
a compose that has it.** A container started from an older compose writes
its config to the container's own layer, which `docker restart` wipes
(default NAS/Workstation servers reappear, `data/data.json` missing on
disk). Recreate it:

```sh
docker compose down && docker compose up -d --build
```

If the recreated backend serves the default servers, your old config lived
in the dead container's layer — re-add the servers in the app (it will then
be persisted in `./data/data.json`) or restore from a plain-run
`data.json`. See AGENTS.md → "Data persistence".

## Test

```sh
curl -s localhost:8080/api/health
# get a token first (credentials from .env):
TOKEN=$(curl -s -X POST localhost:8080/api/login \
  -d '{"username":"admin","password":"<your-password>"}' | jq -r .token)
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/status
curl -s -X POST localhost:8080/api/servers \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"name":"nas","ip":"192.168.1.10","mac":"aa:bb:cc:dd:ee:01","port":22}'
curl -s -X POST localhost:8080/api/servers/1/wol -H "Authorization: Bearer $TOKEN"
# clean SSH shutdown (server 1 needs sshUser plus a key path and/or password;
# if a key path is set, the key file must be present on the backend host):
curl -s -X POST localhost:8080/api/servers/1/shutdown -H "Authorization: Bearer $TOKEN"
```
