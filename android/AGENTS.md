# AGENTS.md — android/

PoleX Android app. Kotlin, Jetpack Compose (Material 3), OkHttp,
Glance widget, WorkManager. `minSdk 26`, `targetSdk 34`.

## Build

Prefer Docker — no local JDK/Android SDK needed. Repo helper script is the
entry point (`scripts/build-apk.sh`; from the repo root):

```sh
./scripts/build-apk.sh               # debug APK via docker compose
./scripts/build-apk.sh release       # release APK (signed with the debug key)
./scripts/build-apk.sh local debug   # local gradle (needs JDK 17 + wrapper)
```

It wraps Docker with a hardened flow (see the script for details):
`down -v` (drops the container **and** the `gradle-cache` volume),
`build --no-cache apk` (forces `COPY . .` to re-copy the current source —
`android/.dockerignore` keeps host `app/build/`, `.gradle/`, `apk/` and
`local.properties` out of the image), then a **source-fingerprint gate**
(md5 of `app/src/**` on the host vs. in the image; aborts if they differ)
before `up apk` runs the compile. `TASKS` is still interpolated, so the host
env var overrides the default `assembleDebug`. `Dockerfile` provides
JDK 17, Android SDK 34 and Gradle 8.7 (the repo has no gradle wrapper; the
container supplies Gradle). APKs are copied to `apk/`; the `gradle-cache`
volume (re-created each run) caches dependencies.

Local build instead:

```sh
# one-time if no wrapper is present:
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/polex-debug.apk
```

Or open `android/` in Android Studio. There are no unit/UI tests yet; the
smoke test is `./scripts/test-emulator.sh` (repo root; Windows equivalent:
`scripts\windows\test-emulator.ps1`) — it boots the
emulator via `docker-compose.emulator.yml`, installs the APK, launches
`MainActivity`, and asserts the app window is focused + no fatal logcat
errors. Prefer that over manual runs when verifying app changes.

## Source layout (`app/src/main/java/com/polex/app/`)

| File               | Role                                                                 |
|--------------------|----------------------------------------------------------------------|
| `MainActivity.kt`  | Entry point. Enqueues the periodic `StatusWorker`, restores `Api.authToken` from Prefs, hosts the Compose root (`PoleXApp`) which routes login ↔ main/settings/server-detail (routes 0/1/2/3; `detailId` holds the selected server) |
| `MainScreen.kt`    | Dashboard. Polls `GET /api/status` every 15 s and supports **pull-to-refresh** (a `Box(Modifier.pullToRefresh)` wrapping the list; the spinner flag `isRefreshing` is only set by the pull — `pullRefresh()` — not by the auto-refresh/toolbar `refresh()`). The list is a plain `Column` + `verticalScroll`, **not a `LazyColumn`**: with a short list that fits on screen a `LazyColumn` produces no overscroll, so `pullToRefresh` would never trigger. Switch back to `LazyColumn` only if the server list ever grows large (pull-to-refresh will then need the list to be scrollable). Server cards (show the check method — `tcp :port` vs `ping` — plus latency/offline). **Tapping a card calls `onOpenServer(id)`** (routes to the detail page); the **`WakeButton`** (shared power-button composable — the stroke power symbol tinted green online / red offline, dimmed when disabled) opens a confirmation `AlertDialog` before `Api.sendWol`. On each successful poll also writes the widget cache and calls `ServerWidget.refreshAll` |
| `ServerDetailScreen.kt` | Per-server page reached by tapping a dashboard card. Polls `GET /api/status` and shows the one matching `serverId`: live status (online/latency/last checked/last wake), the configured settings (read-only) with an **Edit settings** button that reuses `EditServerScreen`, and the shared `WakeButton`. Route 3; `onAuthError` on 401. A `BackHandler` makes system back return to the dashboard (or out of the edit form) instead of exiting the app |
| `SettingsScreen.kt`| Minimal settings menu (Backend / Servers / Log out) + sub-screens: `BackendScreen` (URL editor), `ServersScreen` (list + Add/Edit/Delete + **drag-and-drop reorder** via a `DragIndicator` handle → `POST /api/servers/reorder`, `commitOrder()`), `EditServerScreen` (new via POST, edit via `PUT /api/servers/:id`, with a **Use ping (ICMP)** `Switch` that sets `check` = `ping`/`tcp` and disables the port field). Local navigation only (no router); the "new server" sentinel is `SrvEntry(id = 0)`. A `BackHandler` makes system back walk sub-screens (edit → servers → menu → dashboard) instead of exiting the app |
| `LoginScreen.kt`   | Username/password form → `POST /api/login`; stores the token in `Prefs` + `Api.authToken`. Also holds the only pre-login way to set the backend URL (collapsible **Backend URL…** field — needed because the default is the emulator loopback and Settings is unreachable before login) |
| `Api.kt`           | Blocking OkHttp client (`Api.get/post/put/delete` → `Result(code, body)`); sends `Authorization: Bearer <Api.authToken>` when set. Always call via **`Api.safe { … }`** (runs on the IO dispatcher, maps connection failures to `Result(-1, …)` so callers branch on `code` alone); `Api.sendWol(context, id)` is the WOL POST helper. Raw OkHttp must never be touched from UI code |
| `Prefs.kt`         | SharedPreferences: backend base URL, auth token, cached `/api/status` JSON (also read by the widget) |
| `Model.kt`         | `SrvStatus` / `SrvEntry` (+ `check` field, `"tcp"`/`"ping"`) + JSON (org.json) parse/format helpers; `serverToJson(name, ip, mac, port, check)`, `reorderJson(ids)`, `parseOk(body)` (WOL `"ok"` flag), `parseEpoch`/`relativeTime` |
| `ServerWidget.kt`  | Glance `GlanceAppWidget` (`ServerWidget`) + `ServerWidgetProvider`. Server list (one row per server: dot + name + latency, max 7 + "+N"), bottom line = up count + data freshness dot-separated ("2/3 up · updated HH:mm"). Whole widget is `clickable` → `actionStartActivity` opens `MainActivity`. Renders from the Prefs cache, never from the network |
| `StatusWorker.kt`  | WorkManager `CoroutineWorker`; fetches status, writes cache, calls `ServerWidget.refreshAll`. Two triggers: the 15-min periodic job and `enqueueNow` (one-shot) fired by the widget's system tick **and** by the `WidgetAlarm` inexact alarm |
| `WidgetAlarm.kt`   | Self-rescheduling **inexact** alarm (~15 min, `setAndAllowWhileIdle`, no permission) + `WidgetRefreshReceiver` that calls `StatusWorker.enqueueNow` and re-arms itself. Belt-and-braces against the system deferring the WorkManager periodic job (which is what left the widget stale until the app was opened). Scheduled once in `MainActivity.onCreate` |

## Architecture rules

- **Networking**: use `Api.safe { Api.… }` for every network call (runs on
  the IO dispatcher; connection failures become `Result(-1, message)` so
  callers can branch on `Result.code` without try/catch). The UI never
  touches OkHttp directly. `Api.base(context)` reads the configured URL.
- **Auth flow**: token lives in `Prefs` (survives process death) and is
  mirrored into `Api.authToken` (in-memory, used by the HTTP client) —
  `MainActivity.onCreate` restores it, `LoginScreen` sets it after login,
  logout/401 clears both. `MainScreen` treats a 401 from `/api/status` as
  "re-login" (calls `onAuthError`); the backend returns 401 for any
  protected route with a missing/invalid/expired token, and tokens die on
  backend restart (in-memory sessions in `backend/auth.go`). Note
  `Api.request` adds the header whenever `authToken` is non-null — including
  on the login call itself, which is harmless because `/api/login` is public
  and only checks username/password.
- **Widget data path**: the cache is kept fresh by `StatusWorker` (three
  triggers: the 15-min periodic job, the widget's 30-min system tick →
  `enqueueNow`, and the `WidgetAlarm` ~15-min inexact alarm →
  `enqueueNow`) and `MainScreen` (every successful in-app poll). All go
  `Prefs.setCache` → `ServerWidget.refreshAll` (companion helper wrapping
  the `updateAll` suspend extension). The widget itself must never do
  network I/O.
  `ServerWidgetProvider` is the `BroadcastReceiver` declared in
  `AndroidManifest.xml` (`@xml/polex_widget_info`).
- **Config**: the backend URL lives only in `Prefs` (default
  `http://10.0.2.2:8080` = emulator host loopback). Plain HTTP is intentional
  for LAN use (`usesCleartextTraffic=true`); don't "fix" this.
- **Contract**: JSON field names in `Model.kt` must match the backend's
  `StatusView`/`Server` structs in `backend/main.go` (both now carry a
  `check` field: `"tcp"` default / `"ping"`); auth responses in
  `LoginScreen.kt` must match `backend/auth.go` (`{ok, token}` / 401
  `{ok:false,error}`). Check the backend README when changing either.
- **Wake flow**: dashboard and detail page each keep a `wolBusy` flag and a
  confirmation dialog; sending goes `Api.sendWol(context, id)` →
  `POST /api/servers/:id/wol` → success = `code in 200..299 && parseOk(body)`
  (the endpoint returns HTTP 200 even when the WOL send fails — branch on
  `ok`). The control is the shared `WakeButton` composable (the stroke power
  symbol from `ic_power.xml`, tinted green/red by online status) in
  `MainScreen.kt`, reused by the server detail page.
- **Server reorder**: `ServersScreen` reorders a local `servers` list live
  during the drag (a `DragIndicator` handle runs `pointerInput` +
  `detectDragGestures`; per-row height from `onSizeChanged` sets the move
  threshold; each step calls `moveServer(index, ±1)`) and commits on release
  via `commitOrder()` →
  `POST /api/servers/reorder` with `reorderJson(ids)`. Rows are keyed by
  `id` so the in-flight drag survives list reordering; a failed commit
  re-fetches the server order to roll back. Keep the list order as the single
  source of truth (the backend persists it). Import packages (verified against
  the real artifacts): `detectDragGestures` ← `foundation.gestures`,
  `pointerInput` ← `ui.input.pointer`, `onSizeChanged` ← `ui.layout`.
  **Material3 `pullToRefresh` lives in a subpackage**:
  `import androidx.compose.material3.pulltorefresh.pullToRefresh` (not
  `androidx.compose.material3`). In material3 **1.3.0** (BOM 2024.09.02)
  the signature is `Modifier.pullToRefresh(isRefreshing: Boolean,
  state: PullToRefreshState, enabled: Boolean = true, threshold: Dp, …,
  onRefresh: () -> Unit)` — `state` is required (pass
  `remember { PullToRefreshState() }`) and **`onRefresh` is NOT suspend**
  (unlike the old `PullToRefresh` composable), so it must `scope.launch` the
  fetch rather than call a suspend function directly.
  **Do not use `return@onDrag`** inside the `onDrag` lambda — the
  named-argument-as-label doesn't resolve for a suspend lambda passed by named
  argument (compiles to `Unresolved reference: @onDrag`); guard with
  `if (threshold > 0f) { … }` instead.

## Gotchas

- **Stale Docker builds**: `scripts/build-apk.sh` deliberately does
  `down -v` + `build --no-cache` + an md5 source-fingerprint gate (host
  `app/src/**` vs. in-image) before compiling, because a reused container /
  cached `COPY` layer / poisoned `gradle-cache` volume has repeatedly caused
  `e:` errors pointing at *old* source. If a build error names a line that
  doesn't match the on-disk file (e.g. an import on a line that's now
  something else), the container ran stale source — re-run the script (it
  now aborts loudly on mismatch instead of compiling stale code). Don't
  "optimize" the `--no-cache`/`-v`/fingerprint steps away.
- APK output names are **`polex-debug.apk` / `polex-release.apk`** (not the
  AGP default `app-debug.apk`), set by the `androidComponents { onVariants }` block in
  `app/build.gradle.kts` (it must cast to `VariantOutputImpl` — the public
  AGP 8 API has no `outputFileName`). `scripts/build-apk.sh`, the emulator
  test script and the READMEs all reference these names; don't "clean up"
  the rename block.
- The **release build is signed with the debug keystore**
  (`signingConfig = signingConfigs.getByName("debug")` in the release
  `buildType`). This is intentional so `polex-release.apk` installs on
  devices — an unsigned release APK fails with a bare "App not installed".
  Don't remove the signingConfig; for Play Store distribution replace it
  with a real release keystore.
- Android JSON parsing uses `org.json` (built-in); the project does not use
  kotlinx.serialization or Gson — keep it that way unless asked.
- WorkManager periodic work has a **15-minute minimum interval** — periods
  below that throw at enqueue time. The app uses exactly 15 min. The system
  may still *defer* the job beyond that (doze/battery optimization), which
  is why `MainActivity` also schedules `WidgetAlarm` — a self-rescheduling
  **inexact** alarm (`setAndAllowWhileIdle`, ~15 min, no permission needed
  unlike exact alarms on 12+) whose receiver
  (`WidgetRefreshReceiver`, declared `exported=false` in the manifest)
  calls `StatusWorker.enqueueNow` and re-arms itself. Keep both refresh
  paths; removing the alarm makes the widget go stale until the app is
  opened. `MainScreen` additionally calls `ServerWidget.refreshAll` on
  every status poll (15 s while the app is open).
- **Glance widget API (1.1.0) — verified gotchas, do not regress**:
  - `GlanceAppWidget.provideGlance(context, id: GlanceId)` + `provideContent { }`
    (there is no `buildGlance` in the current API).
  - The receiver class extends `GlanceAppWidgetReceiver` and overrides
    `val glanceAppWidget` (`GlanceAppWidgetProvider` does not exist).
  - Use **Glance** composables inside the widget: `androidx.glance.text.Text`,
    `androidx.glance.layout.{Box,Column,Row,Spacer}` and `androidx.glance.layout`'s
    own modifier factories (`fillMaxSize`, `fillMaxWidth`, `padding`, `size`,
    `width`). Compose `foundation` composables/modifiers
    (`androidx.compose.foundation.*`, `androidx.compose.ui.Modifier`) do
    **not** type-check — `GlanceModifier` is a separate interface; start chains
    with `GlanceModifier.fillMaxSize()` etc.
  - `Text` takes color/fontWeight via `TextStyle(color = ColorProvider(...))`
    (`androidx.glance.unit.ColorProvider`) and `androidx.glance.text.FontWeight`
    (Glance has its own FontWeight — the Compose one does not type-check),
    not `color =`/`fontWeight =` parameters.
  - `updateAll` is a *suspend extension* on `GlanceAppWidget` — it needs an
    instance receiver; callers use the `ServerWidget.refreshAll(context)`
    companion helper.
  - There is no `ForEach`; iterate with a plain `items.forEach { }`. Note
    Glance `Column` supports at most 10 children.
  - Glance `Box` requires an explicit `content` lambda (even if empty: `{}`).
  - `background` takes a color only (no `shape` parameter).
  - To make the widget open the app there is **no `launchApp` in 1.1.0**
    (verified absent from the glance + glance-appwidget jars). Use
    `androidx.glance.action.clickable` +
    `androidx.glance.appwidget.action.actionStartActivity(Intent(context,
    MainActivity::class.java))` on the root Column's modifier. The widget is
    intentionally compact (server list + one dot-separated bottom line,
    small fonts/paddings, `targetCellWidth/Height` 2×1 in
    `res/xml/polex_widget_info.xml`) — keep it that way.
  - `Text` in 1.1.0 has **no `overflow` parameter** (only `text`, `modifier`,
    `style`, `maxLines`) — pre-truncate long strings in Kotlin instead.
    `Box`'s alignment param is `contentAlignment`, not `alignment`.
  - There is **no vertical spacing between server rows** (rows are placed
    back-to-back; no per-row padding). The visible row-to-row gap is the
    font line height — Glance `TextStyle` has no `lineHeight`, so the only
    lever to tighten rows is smaller `fontSize` (currently 9sp names /
    8sp latency). Don't add per-row `padding`.
- Res files: `res/xml/polex_widget_info.xml` (widget metadata),
  `res/layout/widget_preview.xml` (widget preview), launcher icon is adaptive
  (`mipmap-anydpi-v26` + `drawable/ic_launcher_foreground.xml`), and
  `res/drawable/ic_power.xml` — the stroke-based power symbol (open circle +
  vertical line, 1.5 stroke) used by `WakeButton` via `painterResource`.
  It's a stroke drawable tinted by Compose `tint`; the `strokeColor` in the
  file is just a placeholder.
