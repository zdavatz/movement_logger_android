# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Android port of the Movement Logger desktop app at `~/software/movement_logger_desktop`. Three screens:

- **Live** — when connected to a PumpLogger-firmware box, renders the 0.5 Hz SensorStream snapshot (accel / gyro / mag / baro / GPS) as a typed readout plus two acc-magnitude + pressure sparklines. Empty for legacy PumpTsueri firmware (no SensorStream characteristic).
- **Sync** — connects to the PumpTsueri/STBoxFs SensorTile.box over BLE, downloads its CSV recordings, saves them to app-private external storage.
- **Replay** — picks a saved sensor / GPS CSV pair and a video, plays them time-synced with overlaid panels (speed, pitch / Nasenwinkel, height above water, GPS track).

Pure Kotlin + Jetpack Compose, no NDK. Phase-2 numerics (Madgwick fusion, baro height, butterworth, etc.) are ported from `stbox-viz/*.rs` to Kotlin under `data/`; the desktop's plotly-HTML / GIF / board-3D output side is replaced by direct Compose Canvas rendering. The desktop's ffmpeg-overlay GIF export is replaced on Android by an in-app **Export combined** that uses Media3 Transformer to bake the same v-stack (rider on top, four sensor panels below) into a single MP4 saved to `Movies/MovementLogger/`.

## Build & run

```sh
./gradlew assembleDebug              # build debug APK
./gradlew installDebug               # install on attached device (needs adb + USB)
./gradlew :app:compileDebugKotlin    # quick syntax check
./gradlew :app:testDebugUnitTest     # run JVM unit tests (parsers + math)
./gradlew bundleRelease              # signed AAB for Play Store
./gradlew assembleRelease            # signed APK for sideload
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk` (debug) or `…/release/app-release.apk` (release, signed only when `signing.properties` is present at repo root). AAB at `app/build/outputs/bundle/release/app-release.aab`. SDK path comes from `local.properties` (not committed). The wrapper pins Gradle 8.9; AGP is 8.7.3 in `gradle/libs.versions.toml`.

Targets: `minSdk 26 / compileSdk 35 / targetSdk 35`, `buildToolsVersion 34.0.0`.

## Architecture

```
app/src/main/java/ch/ywesee/movementlogger/
├── MainActivity.kt          → MainNav
├── ble/
│   ├── FileSyncProtocol.kt    UUIDs (FileCmd/FileData/SensorStream), opcodes, status bytes
│   ├── LiveSample.kt          46-byte SensorStream wire layout + decoder + BoardAngles (physical pitch/roll/yaw)
│   ├── BleClient.kt           single-worker GATT state machine; dual CCCD (FileData + SensorStream)
│   ├── FileSyncCore.kt        process-singleton: BleClient + UiState owner (FileSync + Live)
│   └── BleSyncService.kt      foreground service (connectedDevice) keeping BLE alive in background
├── sync/
│   ├── AgentConfig.kt         SharedPrefs persistence (boxId / keepSynced / logModeManual / angleZeroRef + angleZeroAtMs tare)
│   ├── BackgroundSync.kt      WorkManager façade: refresh()/cancel() unique periodic work
│   ├── SyncWorker.kt          CoroutineWorker: connect → syncNow → disconnect (port of --agent)
│   └── BootReceiver.kt        BOOT_COMPLETED / MY_PACKAGE_REPLACED → BackgroundSync.refresh
├── data/
│   ├── CsvParsers.kt          Sens / Gps / Bat CSV → typed rows
│   ├── GpsTime.kt             hhmmss.ss → absolute UTC ms
│   ├── VideoMetadata.kt       MediaMetadataRetriever creation_time
│   ├── GpsMath.kt             haversine, speed, fast rolling-median
│   ├── Butterworth.kt         4th-order LP design + filtfilt
│   ├── EulerAngles.kt         quat → roll/pitch/yaw + gimbal-lock regions
│   ├── Madgwick.kt            6DOF IMU AHRS + nose-angle series
│   ├── Baro.kt                GPS-anchored TC'd-pressure height
│   ├── FusionHeight.kt        α-β baro + body-frame acc complementary
│   ├── PublicMirror.kt        dual-write synced files into Download/MovementLogger/
│   ├── ReplayTrim.kt          slice parallel arrays to a UTC-ms window
│   ├── ExportPanelRenderer.kt android.graphics.Canvas port of the four panels
│   ├── VideoExporter.kt       Media3 Transformer combined-video pipeline
│   └── RideMapRenderer.kt     shareable ride-map PNG (OSM tile stitch + track + footer)
└── ui/
    ├── MainNav.kt             bottom-nav scaffold (Live / Sync / Replay)
    ├── LiveScreen.kt          Live tab UI: readout grid + two Compose Canvas sparklines
    ├── FileSyncScreen.kt      Sync tab UI
    ├── FileSyncViewModel.kt   thin façade over FileSyncCore (Activity-scoped)
    ├── ReplayScreen.kt        Replay tab UI + Compose Canvas panels
    ├── ReplayViewModel.kt     CSV + fusion pipeline orchestration
    └── RideMap.kt             GPS track on an interactive osmdroid map + Share-PNG
```

### Live tab — SensorStream readouts

`ble/LiveSample.kt` mirrors the desktop `LiveSample` in `stbox-viz-gui/src/ble.rs`: a 46-byte little-endian packed snapshot decoded into typed fields (acc mg, gyro centi-dps, mag mG, pressure Pa, GPS lat/lon ×1e7, fix-q + sat count + flags). Two transport modes — single 46-byte notify when the negotiated MTU is large enough, or a 3-chunk sequence (0x00 / 0x01 / 0x02 prefix bytes) on the default-MTU fallback path. Out-of-order chunks reset the asm buffer; malformed frames drop silently and auto-resync on the next 0x00 start.

`BleClient` now subscribes to **two** characteristics on connect (FileData *and* SensorStream). Android serialises GATT ops one-at-a-time, so the second CCCD write is chained from the first's `onDescriptorWrite` callback — both descriptors share the standard CCCD UUID, so `RawEvent.DescriptorWritten` carries the parent characteristic UUID to route the ack. SensorStream subscription is soft-fail: legacy PumpTsueri firmware doesn't expose it, the user still gets FileSync, and the Live tab stays empty with a log line saying why.

**MTU upgrade is mandatory for Live data on Android.** The PumpLogger firmware sends 46-byte single-notify packets even when the negotiated MTU can't hold them — it does *not* fall back to the 3-chunk protocol the desktop client expects. At the default ATT MTU of 23 (20-byte payload), Android truncates every notify to 20 bytes and the LiveSample decoder rejects them. So `onServicesDiscovered` calls `gatt.requestMtu(247)` *before* the CCCD chain; subscribe-and-write-CCCD runs from `onMtuChanged` instead. With the bump in place the box delivers full 46-byte payloads in a single notify; the 3-chunk reassembly path remains in the code as a fallback for hypothetical future firmware that needs it.

`FileSyncCore.onSample` updates a `LiveState` inside `FileSyncUiState`: most-recent decoded sample, `latestSampleAtMs` for the freshness label, sample count, plus two 120-point rolling buffers (acc magnitude in g, pressure in hPa). Cleared on disconnect so a stale sparkline doesn't masquerade as a live stream.

The Compose UI gates the readouts on `Connection.Connected` — when disconnected it shows a "Go to Sync" button. A 250 ms tick recomposes the freshness strip so the "X ms ago" label keeps moving between the 2-second sample arrivals.

**Board angles card (`BoardAngles` in `ble/LiveSample.kt`, `BoardAnglesCard` in `LiveScreen.kt`).** A board-attitude readout above the raw readouts computes **pitch / roll / yaw about the box's PHYSICAL axes** from the existing gyro + accel orientation filter (`OriRows` / `Triad.world`) — NOT the raw accel roll/pitch formulas. **Swapped-axis fix:** the box's NOSE is the Y axis, so the old accel-based roll/pitch (which assumed a phone's long axis = X) were swapped; now **Pitch = up/down hill**, **Roll = lean L/R**, **Yaw = heading**, and the old swapped Pitch/Roll row was removed from the readout grid. Two readouts share the same computation: **Absolute** passes the real `headingBias` so yaw is a compass heading; **Calibrated** passes `biasDeg = 0` and subtracts a stored zero-reference so it reads "how far I've moved since I zeroed". A **Zero here** button tares to the current pose (stores `angleZeroRef = [pitch, roll, yaw]°` + `angleZeroAtMs`), **Clear** resets, and a "zeroed M:SS ago" line ticks off `angleZeroAtMs`. The tare persists via `AgentConfig` (Float-backed keys in SharedPreferences, since it has no native Double). Ported 1:1 from iOS `BoardAnglesCard`/`struct BoardAngles`.

### Sync tab — BLE FileSync

- `ble/BleClient.kt` is the canonical Kotlin port of the desktop client's `stbox-viz-gui/src/ble.rs`. All Android BLE callbacks marshal raw events into one `Channel<RawEvent>`; one coroutine `select`s between that and the command channel, holding `CurrentOp` (Idle / Listing / Reading / Deleting). Watchdog ticks every 200 ms are posted as `RawEvent.Tick` through the same channel so op-state mutation stays single-threaded without locks. Read the Rust comments before changing behaviour here.
- `ble/FileSyncCore.kt` is a process-wide singleton that owns `BleClient` + `StateFlow<FileSyncUiState>`. Moved out of the ViewModel so the BLE worker and in-flight READ buffers survive Activity recreation. `FileSyncViewModel` is now a thin façade that observes the core and forwards commands.
- `ble/BleSyncService.kt` is a foreground service (`foregroundServiceType=connectedDevice`) the ViewModel `start()`s on scan/connect. It registers itself as a `FileSyncCore.Listener`, refreshes a sticky notification on every state change ("Downloading Sens001.csv 42 %" etc.), and self-stops 5 s after the core reports `isBusy()=false`. Mirrors the iOS approach (`UIBackgroundModes=bluetooth-central` + `beginBackgroundTask`).
- Compose UI binds to `StateFlow<FileSyncUiState>`. Runtime BLE permissions via `rememberLauncherForActivityResult`; Android 12+ uses `BLUETOOTH_SCAN` (with `neverForLocation`) + `BLUETOOTH_CONNECT`, pre-12 falls back to legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` / `ACCESS_FINE_LOCATION` (manifest caps the legacy ones at `maxSdkVersion=30`). `POST_NOTIFICATIONS` is requested on API 33+ but kept *separate* from the BLE-perm gate so denial only loses the foreground notification, not the BLE UI itself.
- Downloaded files land in `Android/data/ch.ywesee.movementlogger/files/` under the original filename from LIST. Once `localBytes[name] >= size` the row's `Download` button is replaced by a **View** button (iOS `a86fd6d` parity) — opens `DownloadedFileViewer`, a full-screen Dialog that reads up to 48 KB of the file on `Dispatchers.IO`, decodes as UTF-8 when no NUL byte is found (CSV/log preview inline; WAV falls back to a size summary), and exposes a `Share` action that fires `Intent.ACTION_SEND` via `FileProvider` with a MIME picked from the extension (`text/plain` for csv/log/txt, `audio/wav`, `video/mp4`, else `application/octet-stream`). 48 KB cap matches iOS — Compose's `Text` lays out the whole string up front and stutters past ~50 KB of monospaced content; Share always exports the full untruncated file.
- **Public Downloads mirror (`data/PublicMirror.kt`).** The canonical mirror under `getExternalFilesDir(null)` is invisible to Google Files / third-party file managers on modern Android. `PublicMirror` *additionally* publishes every appended chunk into **`Download/MovementLogger/`** so the recordings are browsable + shareable outside the app (Peter's ask). API 29+ uses `MediaStore.Downloads` (`RELATIVE_PATH = "Download/MovementLogger/"`, no permission needed for own files, `Uri` cached per name); API ≤ 28 writes via `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` guarded by `WRITE_EXTERNAL_STORAGE` capped at `maxSdkVersion=28`. **MIME matters** — MediaStore *renames* a file if the declared MIME disagrees with the extension (`text/plain` on a `.csv` → appends `.txt`), so the map is `csv→text/csv`, `log/txt→text/plain`, `wav→audio/x-wav`, `mp4/mov/m4v→video/mp4`, else `application/octet-stream`. Best-effort + same offset/realign/rotate semantics as `appendMirror`: a publish failure never breaks the canonical local write. Hooked from `FileSyncCore.appendMirror` (READ path) and `UbloxGpsCore` (GPS CSV header + each row, tracking `csvPublicOffset`). `mirrorOffset`'s rotate branch also `delete()`s the public copy so a re-pull starts clean.
- **Serial manual download queue.** The BLE worker is single-op, so tapping **Download** on a second file while a big one streams used to hit `sendRead`'s `op !is Idle` guard → rejected as "another op in flight", but the row was already in `downloads` → stuck forever with no READ behind it. Fix: `FileSyncCore.download()` is now a *queue entry* — it dedupes (already downloading/queued/mirrored), pushes onto `manualQueue`, mirrors names into `FileSyncUiState.queuedDownloads` (row shows **Queued**), and calls `pumpManualQueue()`. `pumpManualQueue` issues the next READ only when fully idle (`!listing && downloads.isEmpty() && syncInFlight == null && fwUpload == null && !briefOpInFlight && Connected`), draining one-at-a-time; re-pumped from `ReadDone` / `ListDone` / `DeleteDone` / `LogMode` / `Error`. The actual READ moved to private `startRead()`, shared with `pumpSyncQueue` (sync keeps its own `syncQueue`). `briefOpInFlight` covers the brief single-ops not reflected in UI state (DELETE, GET/SET mode) so a Delete-then-Download double-tap can't collide; set on send, cleared on the completion event or any Error, and on Disconnect (which also clears `manualQueue`). Verified on-device: a 5 MB SENS + 3 queued files drained serially, no stuck rows.
- **Sync UI progress card.** `SyncProgressRow` Compose card renders under the Keep-synced toggle while `state.syncing` is true (iOS `2d001a4` parity). Headline: cumulative byte-progress bar `bytesDone / bytesTotal` + "Syncing — N of M files" + percentage. Second row (only while `syncInFlight` is set): the in-flight file's name + per-file `LinearProgressIndicator`. The bar's accounting: `FileSyncUiState.syncPassTotalBytes = sum(fetch.size)` (set at `runSyncDiff`), `syncPassCompletedBytes += localSize` (folded at `ReadDone`), and `syncCumulativeBytes = syncPassCompletedBytes + downloads[syncInFlight].bytesDone` — same model as iOS, both sides in box-bytes so the bar moves while a READ streams. The button-disable for both `List files` and `Sync now` is unified through `workerBusy = listing || syncing || downloads.isNotEmpty()` so tap-while-busy can't pile a second LIST behind a keep-synced READ.
- **Sync vs. transfer (SQLite-tracked).** Two distinct ops, mirroring `movement_logger_desktop` (issues #3/#4): per-file **Download** (manual, unchanged) and **Sync now** (pull every session file not already mirrored, remember what was pulled). `ble/SyncDb.kt` is the port of the desktop's `sync_db.rs` — same `synced_files` schema, PK `(box_id, name, size)` (`size` in the key so a regrown same-name file re-pulls). `box_id` = BLE MAC, captured in `FileSyncCore.connect()`. DB at `filesDir/sqlite/sync.db` — internal `filesDir`, *not* the external `getExternalFilesDir` download folder (user-clearable; desktop analogue is "anchored to $HOME, not the download folder"), own `sqlite/` subdir per issue #4. Uses `android.database.sqlite.SQLiteDatabase` directly — no Room / no new Gradle dep. **Live-mirror model (desktop v0.0.14):** `<filesDir-or-externalFilesDir>/<name>` *is* the running mirror — downloads append straight in (no `.part`/rename). `Read` carries a u32-LE byte offset (`0x02 + name + 0x00 + offset`); `mirrorOffset` decides per file by **local size vs box size** (local<box → fetch only the tail; local==box → up to date; local>box → rotated, drop & refetch), so a growing log only pulls its delta and no big file starves GPS/BAT. The SQLite DB is an **audit log only** (`isSynced` removed). **"Keep synced"** toggle: while connected + idle, re-runs a sync pass every 30 s (`keepSyncedJob`, `SYNC_POLL_INTERVAL_MS`). Flow: `syncNow()`/keep-synced → `startSyncPass` sets a pending flag → fresh LIST → `ListDone` runs the diff only when set → `isSensorData` files behind their mirror drain serially through the single-op `startRead()` path via `pumpSyncQueue` (advanced from `ReadDone`, which `appendMirror`s at `base` and `markSynced`s for the audit log). Manual taps use a separate `manualQueue`/`pumpManualQueue` over the same `startRead`. **Lossless resume (desktop v0.0.9):** a link drop / 20 s stall mid-READ emits `BleEvent.ReadAborted(name, content, base)`; `FileSyncCore` appends that partial into the mirror (not `markSynced`) and sets `transferInterrupted`, which shows an amber banner on the disconnected screen and, on the next `Connected`, auto-runs a sync pass that skips complete files and re-pulls only the unfinished one from its mirror offset. **Bounded auto-reconnect (desktop v0.0.11–13):** on a mid-READ remote drop *or* a 20 s watchdog stall, `BleClient` arms a tick-driven reconnect state machine (`reconnect`/`tickReconnect`): rounds of `getRemoteDevice(addr).connectGatt`, each bounded (Android connects by MAC, no rescan phase needed); suppresses the public `Disconnected` while retrying; subscribe-confirmed (`emitConnected`) clears it and the re-emitted `Connected` drives the mirror-resume. Exhaustion → `Disconnected` + banner. A user Disconnect clears the target. **Doze-safe tunables (iOS `81a755d` parity):** `RECONNECT_ATTEMPTS = 30`, `RECONNECT_CONNECT_MS = 60_000L` — Android holds the pending `connectGatt(autoConnect=false)` even while the worker coroutine is descheduled (doze, lock screen) and only invokes `onConnectionStateChange` when the peripheral re-advertises. A 10 s budget false-timed-out every wake because the watchdog `delay()` freezes while suspended but `SystemClock.uptimeMillis()` keeps ticking. **Unbounded when Keep-synced is on** (`failReconnectAttempt` reads `keepSyncedActive()` = `AgentConfig.keepSynced && logModeManual != true`) — same regime as desktop's Auto Mode (v0.0.19). The bounded budget still applies to a one-shot manual sync. **Skip keep-synced tick during the reconnect window** (iOS `8c90276` parity): the 30 s `keepSyncedJob` poll guards on `!state.transferInterrupted` so it doesn't fire `startSyncPass` between a mid-READ drop and the auto-reconnect succeeding — during that window `BleClient` has silently torn the link down so the UI still sees `Connected` but the GATT chars are gone; the next `Connected` handler re-runs `startSyncPass(reason = "Resume")` itself so nothing is lost. **Defer post-Connect sync kick by 500 ms** so the in-flight `GetLogMode` reply demuxes through `CurrentOp.ModeReq` first — otherwise the LIST inside `startSyncPass` collides with it and is rejected as "another op is in flight". The collision-rejection path in `BleEvent.Error` is now distinct from the real-BLE-error path: on collision while a sync is just starting (`syncQueue` empty + no in-flight READ) the handler resets `syncing = false` with status "Sync: deferred (box busy) — retrying" so the next keep-synced tick picks it up; a real error still wipes the queue and emits "Sync aborted (BLE error) — try again". **Policy (locked): purely additive — never DELETE.** Only `Sens*/Gps*/Bat*.csv` + `Mic*.wav` auto-sync. iOS (`BLE/SyncDb.swift`, system `SQLite3`, `Application Support/sqlite/sync.db`, box_id = `CBPeripheral.identifier`) is the exact peer.

### Background sync agent

Android pendant of the desktop `--agent` (`movement_logger_desktop/stbox-viz-gui/src/agent.rs`). Zero-click: when the box is in AUTO + "Keep synced" is on, a periodic worker mirrors recordings every 15 min without the app being open.

- **Config persistence.** `sync/AgentConfig.kt` — `SharedPreferences` (`movement_logger_agent.xml`) holding `boxId` / `keepSynced` / `logModeManual`. GUI is the writer (`FileSyncCore.connect()` persists the MAC, `setKeepSynced()` writes the toggle, the `LogMode` event-handler writes the mode), worker is the reader. Port of `~/.movementlogger/config.toml`. Three-state `logModeManual` (true / false / unknown) is encoded via a `*_known` companion bit because SharedPrefs has no native tri-state Boolean.
- **Worker.** `sync/SyncWorker.kt` — `CoroutineWorker` promoted to foreground with `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`. Reuses `FileSyncCore.{connect, syncNow, disconnect}` and the same `BleClient` — there is **one** BLE state machine, the worker just drives it from a different entry point. Flow: check gating (`AgentConfig.active` + runtime `BLUETOOTH_CONNECT` perm) → yield if `FileSyncCore.isBusy()` (foreground UI is using BLE) → `connect(mac)` with 60 s timeout → `syncNow()` with 8 min timeout → `disconnect()` with 5 s timeout. Returns `Result.retry` on yield/timeout so WorkManager re-runs at the next 15 min tick instead of giving up.
- **Schedule.** `sync/BackgroundSync.refresh(ctx)` reads `AgentConfig.active` and either enqueues a unique periodic `PeriodicWorkRequest` (15 min, REPLACE-policy `KEEP` so config touches don't restart an in-flight run) or cancels the unique work. Called from `FileSyncCore.connect()`, `setKeepSynced()`, and the `LogMode` event handler — mirrors desktop's `SyncCore::persist_config()` + `autostart::sync_with_mode(manual)` call sites.
- **Boot.** `sync/BootReceiver.kt` re-arms the schedule on `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` (WorkManager forgets its job DB across reboots; the schedule needs to be replayed from `AgentConfig`). Mirrors desktop's per-OS autostart entry (LaunchAgent / .desktop / Registry Run).
- **Gating** (locked, do not change without re-confirming): `keepSynced && boxId != null && logModeManual != true`. MANUAL disables the schedule; AUTO + Keep-synced + known MAC enables it. Same condition as `cfg.keep_synced && cfg.log_mode_manual != Some(true)` on desktop.
- **Coordination — "GUI wins, agent yields"** (desktop decided architecture, kept verbatim): both UI and worker share the **same** in-process `FileSyncCore` singleton; the worker checks `isBusy()` before grabbing BLE and returns `Result.retry` if the UI is active. There is no IPC lock equivalent to desktop's `coord.rs` filesystem locks — Android's WorkManager guarantees only one instance of a unique periodic work, and the UI + worker are in the same process so they observe the same `StateFlow`.
- **Permissions.** `RECEIVE_BOOT_COMPLETED` for the receiver; `BLUETOOTH_CONNECT` is already there (foreground UI prompts on first launch — the worker can't prompt, so a denied permission just means the background loop skips itself with `Result.success`). `FOREGROUND_SERVICE_CONNECTED_DEVICE` is reused.

### Replay tab — data on top of video

`ReplayViewModel.maybeComputeFusion()` runs the full pipeline on `Dispatchers.Default` after both `Sens*.csv` and `Gps*.csv` are picked:

1. `Fusion.detectDtSeconds` → sample rate
2. `Fusion.computeQuaternions` (β = 0.1, matches `animate_cmd.rs:78`)
3. `Fusion.noseAngleSeriesDeg` — 1 s + 60 s rolling-median drift correction (uses the TreeMap-based fast median in `GpsMath.rollingMedianFast`, ~100× faster than the simple O(n·w) impl for the 6000-sample baseline)
4. `Baro.heightAboveWaterM` — GPS-anchored water reference, falls back to session-max pressure when no stationary anchors exist
5. `FusionHeight.fusedHeightM` — α-β complementary baro + body→world-rotated acc
6. Per-sensor-row absolute UTC by tick-offset from the GPS anchor

Video alignment: `MediaMetadataRetriever`'s `METADATA_KEY_DATE` against four common ISO-8601 / RFC-3339 spellings → UTC ms. GPS anchor = first parseable `hhmmss.ss` against the video's session date (extracted via `GpsTime.utcYmdFromMillis` from the video's `creation_time`), falling back to today's UTC date when no video is picked yet. Picking the video after the GPS triggers `ReplayViewModel.reanchorGpsTimes()` so the sensor + GPS abs-time series stay consistent with the playhead.

**Time alignment prefers firmware `# SYNC` markers (phone clock, drift-free).** When the loaded `Sens*`/`Gps*` CSV carries `# SYNC` anchors (`CsvParsers.parseSyncAnchorsFile` → `List<SyncAnchor>`, stored in `ReplayUiState.sensorSyncAnchors`/`gpsSyncAnchors`), `ReplayViewModel.absTimesFromSyncAnchors` builds per-row absolute UTC by **piecewise-linear interpolation across the anchors** (single anchor → constant 10 ms/tick from it) — drift-free and GPS-independent, sharing the video's `creation_time` clock domain. This is the iOS `absTimesFromSyncAnchors` mirrored one-for-one. Both `sensorAbsTimes(...)` and `gpsAbsTimes(...)` route through it when anchors exist; the legacy single-GPS-anchor + `tick*10` extrapolation (sensor) / per-row `hhmmss.ss` parse (GPS) is the fallback for legacy / never-connected files. `ReplayUiState.alignmentSource` is `"Phone-clock sync"` when anchors drive the mapping, else `"GPS-derived time"`, surfaced in the Replay screen's Alignment block.

Panels (all Compose Canvas, all bound to a 33 ms playhead poll):

- **Speed** — `GpsMath.smoothSpeedKmh` (clip > 60 km/h, linear-interp gaps, 5-sample rolling median).
- **Pitch / Nasenwinkel** — `noseAngleSeriesDeg`, symmetric ±max scaling around zero.
- **Height** — overlay of raw baro (thin grey) and fused (thick primary).
- **GPS track** — lat/lon with `cos(meanLat)` longitude correction; moving red dot at the playhead.

Each panel takes its own absolute-time array (`gpsAbsTimesMs` or `sensorAbsTimesMs`) and binary-searches the cursor index from `videoMeta.creationTimeMillis + playheadMs`. When the video has no `creation_time`, cursors hide (manual offset slider is a future polish slice).

The **Local video** section in the Replay picker lists any `.mov`/`.mp4`/`.m4v` files in the app-private external dir (`Android/data/ch.ywesee.movementlogger/files/`). Tapping Load picks the file directly via `Uri.fromFile()`, bypassing the system Photo Picker — handy for adb-pushed test clips that the picker can't find (Photo Picker sorts by `datetaken`, which embeds the video's `creation_time`, so old footage is buried).

### Combined video export (`Export combined`)

`ReplayViewModel.exportCombinedVideo()` orchestrates the pipeline:

1. `VideoExporter.probeSource` — width/height/rotation/duration via `MediaMetadataRetriever`. Rotation is applied so dimensions are post-rotated.
2. `ReplayTrim.trimToWindow(creationMs, creationMs + durationMs)` — slices the GPS + sensor parallel arrays to the video's UTC time window. Out-of-window rows are dropped entirely; the Alignment card shows `trim → gps: X / Y` so you can see the in-window count before kicking off the encode. Trip-protection: if the trimmed window is empty (e.g. wrong date), the export errors out instead of producing a blank.
3. `ExportPanelRenderer` — `android.graphics.Canvas` mirror of the Compose Canvas panels. Precomputes stable axes (maxSpeed/absMaxPitch/heightRange/gpsBounds) at construction so frame-to-frame Y axes stay still while the red cursor sweeps.
4. `VideoExporter.export` — Media3 Transformer composition with three video effects layered on top of the source:
   1. `Presentation.createForWidthAndHeight(outW, srcH + panelsH, LAYOUT_SCALE_TO_FIT)` letterboxes the source into the taller output canvas.
   2. A `MatrixTransformation` translates the video up by `panelsH/outH` in NDC so the rider sits at the top of the frame instead of vertically centred.
   3. `OverlayEffect(BitmapOverlay)` draws the four-panel stack at the bottom. `setBackgroundFrameAnchor(0f, -1f) + setOverlayFrameAnchor(0f, -1f)` bottom-aligns the overlay; do **not** also call `setScale` — Media3's `OverlayMatrixProvider.aspectRatioMatrix` already maps overlay→background size, so a manual scale double-shrinks the panels.
5. The encoded MP4 lands in app cache, then `saveToMoviesCollection` copies it into `MediaStore.Video` under `Movies/MovementLogger/` via the `IS_PENDING` flag for atomic visibility.

The Done banner exposes an **Open video** button that fires `Intent.ACTION_VIEW` (chooser) on the saved `content://media/...` URI — Photos/Gallery/etc. handle playback. `Intent.FLAG_GRANT_READ_URI_PERMISSION` is set so the picked viewer can read the MediaStore entry.

#### Export gotchas

- **HDR sources trip `OverlayShaderProgram` checkArgument.** HEVC HLG / Ultra-HDR clips (iPhone, Pixel main camera) route through Media3's HDR overlay codepath, which asserts `bitmap.hasGainmap()` on the overlay — our SDR panel bitmap fails. Fix: set `Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL` on the composition. Without it, the Ayano_Pump session crashes with `java.lang.IllegalArgumentException at OverlayShaderProgram.drawFrame:131` on Pixel 8a.
- **Rotation:** the encoded stream keeps the source's rotation flag (1080×3840 raw bytes for a portrait source) but plays back rotated (3840×1080 displayed → 1080×3840 visible). Don't try to compensate in pixel space; trust the rotation metadata.

## Box-sourced board-orientation calibration (v0.0.46+) — `Calibration.kt`

The four calibration fields (`nosePlusY`, `magOffsetMg`, `angleZeroRef`
+ `angleZeroAtEpoch`, `headingBiasDeg`) live on the BOX in `CAL.CFG`
(firmware v0.0.37+) — `AgentConfig` still mirrors them into
`SharedPreferences`, but the box is now the source of truth. A "Zero
here" or nose toggle done on Android is visible to iPhone + Desktop
on their next connect (and vice versa).

- **Wire format** (32-byte blob, per-field `validMask`, tenths-of-degree
  fixed point, LE `ULong` epoch ms): `app/src/main/java/ch/ywesee/movementlogger/ble/Calibration.kt`
  — `encode(input)` / `decode(bytes)`. 1:1 port of desktop
  `stbox-viz-gui/src/calibration.rs`; byte-compatible. Round-trip
  covered by `CalibrationTest`.
- **On connect**: `FileSyncCore.queryCalibration()` chains a
  `CAL_GET (0x13)` after the `BleEvent.GpsPower` reply lands
  (self-guarded, one-shot per connect via `calGetRequested`). Reply →
  `BleEvent.Calibration(bytes?)` → `mergeCalibration(d)` writes each
  non-null field into `AgentConfig`; fields the box hasn't set leave
  the local value alone. Legacy firmware (< v0.0.37) times out
  silently (`.Calibration(null)`) — the app keeps SharedPreferences
  as before. LiveScreen re-reads the merged values on the next 0.5 Hz
  sample so the UI reflects the box's state without a manual refresh.
- **On any user tap**: `FileSyncViewModel.pushCalibration(input)` fires
  `CAL_SET (0x14)` with ONLY the touched field's bit set. Call sites
  in `LiveScreen.kt`: `Zero here` / `Clear`, `USB-C south — set
  direction`, nose-confirm (**single combined blob** — nose +
  nudged bias in one `CAL_SET`; the strict single-op BLE worker
  would reject a second write while the first is in flight, so the
  atomic combined push matters — this is a deliberate deviation
  from the desktop's two-sequential-call pattern), and
  `Reset calibration` (full wipe on all four mask bits).
- **Deliberately not synced**: continuous mag-offset auto-cal — same
  "avoid SD write churn" tradeoff as desktop + iOS.

## BLE protocol gotchas (carried over from the Rust client)

- Subscribe to FileData notifications **once per connection**, not per op. Subscribing per op risks losing the first packet if the box notifies before the await is parked.
- READ's first packet may be a 1-byte status error OR file content. Disambiguate: first packet, exactly 1 byte, AND byte ∈ {0xB0, 0xE1, 0xE2, 0xE3} → treat as error. Otherwise treat as content. CSV/log files start with ASCII text (well below 0x80) so the test is unambiguous in practice.
- LIST may not deliver its terminator `\n` on flaky links. Inactivity fallback: ≥1 row seen and 500 ms with no new bytes → treat as ListDone. Without this fallback the next op trips the "another op is in flight" guard for 20 s.
- **Log mode (firmware v0.0.7+).** `OP_SET_MODE 0x06 <u8>` (0=auto,1=manual) persists the box's log mode in `LOGMODE.CFG` on its SD root; `OP_GET_MODE 0x07` → 1-byte reply (0/1). `FileSyncCore` sends `GetLogMode` on `Connected` so the Auto/Manual chips reflect reality; `null` = unknown (legacy PumpTsueri ignores 0x07, no reply). `BleClient` routes both single-byte replies through a `CurrentOp.ModeReq` op (added to every exhaustive `when(op)`: dispatch, disconnect-abort, both watchdog timeouts) — same single-op discipline as DELETE.
- **GPS power (firmware v0.0.35+).** `OP_GPS_POWER 0x11 <u8>` (1=on, 0=off) turns the box's u-blox receiver on/off to save battery when GPS is faulty/unused; `OP_GPS_GET_POWER 0x12` → 1-byte reply (1/0). Exact twin of the log-mode pair: `BleCmd.SetGpsPower(on)` / `GetGpsPower`, `BleEvent.GpsPower`, a `CurrentOp.GpsPwrReq` op alongside `ModeReq` (same exhaustive-`when(op)` slots), and `handleGpsPwrNotify` demuxing the single-byte reply. `FileSyncCore.setGpsPower` sends + optimistically reflects into `gpsPowerOn: Boolean?` (in `FileSyncUiState`); `queryGpsPower` is an idle-deferred connect-time `GetGpsPower` so the selector reflects the box's persisted state. The box persists the GPS state and re-applies it at boot — the app only reflects + sends. `null` = unknown (legacy firmware < v0.0.35 ignores 0x11/0x12, so the op times out and the toggle stays last-known). GPS off leaves IMU + baro logging, so Replay still time-aligns via the `# SYNC` anchor (lose speed + track, keep pitch/roll/height). The Sync tab renders it as a `GpsPowerSelector` (On/Off) next to the log-mode selector. iOS is the exact peer.
- **Time sync (`OP_SET_TIME 0x08 <epoch_ms:u64-LE>`, identical in firmware + iOS).** The box has no RTC. `FileSyncCore` sends `BleCmd.SetTime(System.currentTimeMillis())` on **every** `Connected` (first connect + every reconnect), sampling the epoch *right before the send* so it matches the box tick the firmware stamps. The firmware pairs the epoch with its free-running `HAL_GetTick()` ms counter (the CSV `ms` column) and appends a `# SYNC epoch_ms=<u64> tick_ms=<u32>` comment line into the open `Sens*`/`Gps*` CSVs. Replay then maps box ticks → absolute wall-clock with zero drift and no GPS dependency. **Fire-and-forget**: `BleClient.sendSetTime` writes the 9-byte payload but does NOT occupy a `CurrentOp` slot and never tracks the OK reply — legacy firmware without 0x08 ignores the write, so tracking it would stall the op for the 20 s watchdog window. It self-skips when an op is mid-flight so a stray marker can't interleave with a READ. **Sequencing**: `GetLogMode → SET_TIME → startSyncPass` are spaced 500 ms apart in the `Connected` handler — the firmware holds only ONE pending command and the BLE worker is single-op, so back-to-back writes clobber each other. **Settle window (BleClient `SETTIME_SETTLE_MS = 2000`).** After `0x08` the firmware is busy appending the `# SYNC` line to SD and **silently drops the next FileCmd that arrives too soon** — confirmed on the wire: a LIST ~0.5 s after SET_TIME timed out (20 s watchdog), the same LIST ≥1.8 s later always succeeded. This bit hard in **Auto mode** where the user connects and immediately taps List. `sendSetTime` sets `setTimeSettleUntil = now() + SETTIME_SETTLE_MS`; `handleCommand` calls `awaitCmdSettle()` (a one-shot `delay` of the remaining window) before dispatching List/Read/Delete/Get-or-Set-mode — connection-control commands and SET_TIME itself never wait. So the first file command after a connect is held up to ~2 s instead of being swallowed. The worker is single-op, so blocking there can't starve a concurrent transfer.
- **START_LOG no longer reboots / disconnects.** Current firmware (v0.0.7+) treats `0x05 START_LOG [<dur:u32-LE>]` as "open a session, auto-stop after dur seconds, stay connected" — only meaningful in **manual** mode (in auto the box already logs from boot). `FileSyncCore.startSession()` no longer queues a Disconnect and the banner text dropped the "rebooting" wording. The ~500 ms post-write settle is kept (write-without-response returns when queued, not transmitted). Legacy PumpTsueri rebooted on START_LOG; we no longer rely on that.
- STOP_LOG button was removed from the UI (always-on firmware makes it a footgun); `vm.stopLog()` / `OP_STOP_LOG 0x04` plumbing is kept. It gracefully closes the active session; connection stays up.

## Ride map — GPS track on a map + shareable PNG (iOS `RideMap.swift` port)

`ui/RideMap.kt` (`RideMapView`) + `data/RideMapRenderer.kt`. Reached from the
GPS tab's per-recording dropdown (**Map** / View / Share) and from a **Map**
button on downloaded `Gps*.csv` rows in the Sync tab. Interactive map is an
osmdroid `MapView` (OSM tiles, no API key) inside `AndroidView`: downsampled
teal polyline (≤2000 pts), green start / red end dot markers. **Share**
renders a 1080×1630 PNG — hand-stitched Web-Mercator OSM tiles (1080×1440),
white-cased speed-coloured track (blue slow → red fast, 95th-pct vMax like
iOS), and the branded 190 px footer (launcher icon, "Movement Logger",
`Top km/h · km · min`, GitHub link) — saved to
`getExternalFilesDir/RideMaps/` and shared via the existing FileProvider.
Pure math (mercator px, `fitZoom`, downsample, 95th-pct, speed hue,
`fillSpeedGaps`) is `internal` + JVM-only → `RideMapRendererTest`.

Hard-won gotchas (each cost an on-device debug round):

- **`ACCESS_NETWORK_STATE` is mandatory** — osmdroid's downloader silently
  skips every tile without it (grey grid, zero log errors).
- **`Modifier.clipToBounds()` on the `AndroidView`** — osmdroid paints
  outside its layout bounds and otherwise draws over the dialog's top bar.
- **Don't use `zoomToBoundingBox` pre-layout** — it left the camera at z21
  over null island; zoom > tile-source max (19) disables all downloads.
  Camera is set deterministically from `RideMapRenderer.boundingBox` +
  `fitZoom` in a one-shot `OnLayoutChangeListener`.
- OSM tile policy: identifying `userAgentValue` set on both the osmdroid
  `Configuration` and the PNG renderer's HTTP fetches.

**GPS parser leniency (required by this feature).** `parseGpsStream` accepts
all three header generations — spaced (`Lat`, `Speed [km/h]`), compact
firmware ≥ 22.4.2026 (`ms`→÷10, `lat`, `speed_kmh`, `fix_q`…) and bracketed
u-blox/watch (`Lat [deg]`, `SpeedKMh`) — mirrors iOS `parseGpsText`. Only
ticks/lat/lon are hard-required per row; blank optional fields → NaN (fix/
nsat → 0) and corrupted rows are skipped, not fatal. This matters because
`UbloxGpsCore` writes **two half-rows per epoch** (u-blox emits GGA before
RMC: a GGA row with fix/alt but blank speed, then an RMC row with speed but
`fix=0`) — the old strict parser dropped 707 of 708 rows of a real session.
`RideMapRenderer.fillSpeedGaps` forward/back-fills the NaN speeds for the
colour scale; footer top-speed reads ALL rows so it matches the
recordings-list stat.

**Debug builds install alongside the Play app**: `applicationIdSuffix
".dev"` + `versionNameSuffix "-dev"` on the debug build type (the phone's
Play-signed install used to block `installDebug` entirely).

## USB GPS tab (`usb/`) — reliability hardening (v0.0.48–50)

Post-mortem of the 11.7.2026 water test (33-min recording died at 15:49:52,
tombstone: `Scudo ERROR: internal map failure (Out of memory)` inside
`UbloxGpsCore.computeStats` after 19.4 h process uptime). Root cause chain +
the rules that now guard it:

- **Never use Kotlin's `toDoubleOrNull` in a hot loop.** It screens every
  call through `ScreenFloatValueRegEx` — one *native* ICU regex match per
  field. The 3 s recordings tick re-parsed the growing 1 MB CSV each pass
  (~25k fields/s sustained) until the native allocator couldn't map more
  memory and SIGABRT'd the whole app. `data/CsvParsers.kt:fastDoubleOrNull`
  is the regex-free replacement (plain `Double.parseDouble` + catch); the
  NMEA and CSV-parsing paths use it.
- **The in-flight recording's stats are folded incrementally** per written
  row (`updateLiveStats` in `UbloxGpsCore`, zero file IO); finished files
  parse once into a (name, size)-keyed `statsCache`, seeded from the live
  counters at `stopLogging`. `refreshRecordings` never re-reads a file whose
  size hasn't changed.
- **`UbloxGpsService`** — foreground service (`connectedDevice`, twin of
  `BleSyncService`, notification id 2) started from `startReader`; polls
  state every 2 s for the sticky notification and self-stops 5 s after
  reading + logging end. Without it the cached-app freezer stalls the read
  loop on screen-off (the water-test recording survived DOZE only because
  the freezer happened to exempt the USB-fd holder).
- **Reader auto-reconnects**: a read error or 5 s of port silence (a healthy
  receiver never pauses at 10 Hz) recycles the connection and polls the USB
  device list at 1 Hz until the same vid/pid re-enumerates with permission —
  unbounded until the user taps Disconnect. Re-attach normally re-grants
  permission via the manifest `USB_DEVICE_ATTACHED` filter.
- **Per-NMEA-line `Log.v` is gated** behind `Log.isLoggable` (enable with
  `adb shell setprop log.tag.UbloxGpsReader V`). Unconditional logging wrote
  ~40 lines/s and rolled the main logcat buffer to ~20 min — exactly the
  window you need for post-ride forensics.
- Shared ride-map PNGs are additionally published to
  `Download/MovementLogger/` via `PublicMirror` (`png → image/png` MIME).
- **Track is activity-coloured (swim / board / land), same colours as
  iOS.** `RideMode` (blue `0xFF338CF2` In water / green `0xFF29C76B` On
  board / orange `0xFFF28C21` On land — the iOS `RideMode` sRGB values
  verbatim) + `RideMapRenderer.rideModes(rows, pts, onWater)`. The
  Android substitute for the watch's submersion sensor is **geography**:
  `waterMask(pts)` samples the OSM z16 tile pixel under each track point
  (3×3 majority of carto water `#AAD3DF` ± 10/channel, verified on the
  Ermioni tiles; only tiles touched by the track are fetched). Rules with
  a mask: land pixel → land; within ±30 s (`WET_NEAR_TICKS`) of an
  interior fix hole → swim (near a hole the speed is fabrication anyway);
  speed ≥ `WATER_BOARD_KMH` (3.5) → board; else swim. Offline (mask
  null) it degrades to: speed ≥ `BOARD_KMH` (6) → board, near-hole →
  swim, else land. Runs shorter than `MODE_MIN_RUN_SEC` (20 s) are
  absorbed into their longer temporal neighbour (`smoothKeys`,
  absorb-shortest-first — iOS `RideActivity.smoothKeys` port).
  **Per-point speed is the median of ALL finite speed rows within
  ±2.5 s (`SPEED_WINDOW_TICKS`) — never read speed off the cleaned
  points: `dedupFixes` drops the RMC half-row twin (same UTC+position as
  its GGA sibling), which is exactly the row carrying the speed, so the
  points' own speed column is ~all NaN/stale.** Thresholds calibrated on
  the 11.7.2026 Ermioni ride, which the classifier reproduces
  minute-by-minute (quay walk → 12:19:05 jump-in → swim → 4.3 km/h
  para-wing riding 12:27–12:31 → floating till the crash). Consumers:
  PNG renderer (per-edge colour + "Activity" legend in the 240 px
  footer) and the interactive osmdroid map (one Polyline per colour run,
  adjacent runs share their boundary point; teal until the async mask
  lands, then recoloured in place via the AndroidView `update` pass;
  legend card bottom-left).
- **Track drawing is blackout-cleaned too (v0.0.50).**
  `RideMapRenderer.cleanTrackSegments` drops positions inside the blackout
  zones and splits the polyline into per-segment runs so two real points
  are never bridged across a hole (the fake straight lines across Ermioni
  town). Consumers: PNG renderer, interactive osmdroid map, and
  `computeStats`' distance (via `segmentsDistanceKm` — the 11.7.2026 ride
  dropped 1.65 → 1.10 km once the fabricated drift was excluded).
  `validPoints` also gates on `hdop ≤ 50` (the watch logged one WiFi-
  fallback fix 70 km away, honestly stamped accuracy 149 000 m), and
  consecutive identical fixes (same UTC+lat+lon) collapse to one —
  the watch logger rewrites the last-known location every second during
  a signal stall, which otherwise masks the hole from the blackout rule.
- **Top-speed stat is outlier-hardened (v0.0.49) — the raw `SpeedKMh` max
  is garbage when the antenna dips under water.** On the 11.7.2026 ride the
  receiver fabricated a smooth 5 → 27 km/h ramp (on a ~7 km/h session)
  while STILL claiming 12 sats / HDOP 0.6, with positions sliding
  consistently (the straight line across town on the map) — so quality
  flags, an accel limit, AND a position cross-check all pass it. Rules in
  `RideMapRenderer.robustTopSpeed` (shared by the recordings list via
  `computeStats` and the PNG footer; iOS `RideMap.robustTopSpeed` is the
  port): (1) hard clip 60 km/h; (2) **blackout adjacency** — no speed row
  within ±10 s of a ≥2 s hole in the valid-fix timeline counts (the
  fabrication episode always brackets the moment the signal actually
  died); (3) position consistency — earliest/latest valid fix within ±1 s
  must span ≥0.5 s and move commensurately (`v ≤ chord×3 + 5`), which
  kills isolated doppler blips. Write-time guard on top: `maybeFlushCsvRow`
  only logs speed/course from RMC sentences flagged valid (status A) —
  void (V) sentences during reacquisition used to land in the CSV.
  `computeStats` now parses via `CsvParsers.parseGpsStream` and delegates
  max-speed to `robustTopSpeed` so list + footer can never disagree.

## Race mode — live position uplink (`usb/RaceUplink.kt`)

Race-day streaming to the desktop app's **Race** tab: a card in the GPS
tab (rider name + desktop `ip:port` + **source picker** — "u-blox USB"
or "Phone GPS" — persisted in `movement_logger_race` prefs) toggles a
UDP uplink that fires one JSON datagram per fix, throttled to 5 Hz —
`{"v":1,"rider":..,"src":"ublox","lat":..,"lon":..,"kmh":..,"deg":..,
"ts":<epoch ms>,"batt":0-100}`, default port 47777 (constants shared
with iOS `RaceUplink.swift` + desktop `race.rs`, which owns the wire
doc). Hooked from `UbloxGpsCore.onRmc/onGga` after the `_state` merge;
early-outs are cheap and the actual send runs on a dedicated
single-thread executor so nothing can stall the NMEA reader thread
(DNS in particular — the resolved `InetAddress` is cached and dropped
on any send failure so a DHCP change self-heals). Fire-and-forget by
design: a lost datagram is stale 500 ms later anyway. Sends only with
`fixQuality > 0`.

**Phone GPS source** (no receiver needed): `RaceGpsService` — a
`location`-type foreground service (notification id 3, Android pendant
of iOS's location background mode) owns `LocationManager` GPS updates
(~1 Hz) + a `GnssStatus` callback for the satellites-used count, and
forwards each fix to `RaceUplink.sendPhoneFix` (src "phone", acc =
`Location.accuracy`). Started/stopped by `RaceUplink.setEnabled` when
the source is "phone". Needs the runtime `ACCESS_FINE_LOCATION` grant —
the Race card requests it on enable; the manifest permission is now
uncapped (it was `maxSdkVersion=30` for legacy BLE) but the BLE flow on
31+ still never asks for location.

## GPS Debug tab — u-blox UBX survey over BLE

Live u-blox diagnostics for antenna selection/mounting, bridged over the box's
BLE link (no cable). BLE-only — separate from the USB GNSS tab (`usb/`), which
is NMEA. Port of the desktop `gps-debug` survey. Files: `ble/Ubx.kt` (UBX
parser + NAV/MON decoders + poll-frame builder), `ble/BleGpsSurvey.kt` (survey
engine, CSV writer, `StateFlow`), `ui/BleGpsDebugScreen.kt` (the tab).

- **Protocol.** Two firmware opcodes on the FileCmd char: `OP_GPS_BRIDGE 0x0D`
  `<u8 on>` and `OP_GPS_UBX 0x0E` `<raw UBX>`. While the bridge is on the box
  relays raw UBX reply frames back as **FileData notifies**. `BleClient` holds a
  `bridgeActive` flag; when set, `onNotification` diverts FileData bytes to a new
  `BleEvent.UbxFrame` **before** the `when(op)` state machine — the survey and a
  FileSync READ can't share the FileData channel, so `FileSyncCore.startGpsDebug`
  refuses while the worker is busy and gates keep-synced / the manual queue on
  `gpsSurveyActive` (mirrors `BleGpsSurvey.running` via `onRunningChanged`).
- **Survey loop.** `BleGpsSurvey` runs a 1 Hz coroutine: each tick flushes the
  epoch collected over the last second (writes CSV rows + a live-summary line)
  then re-sends the five poll frames. `feed()` (from `onEvent`) and the poll loop
  share `parser`/`cur` under a `synchronized(lock)`.
- **Output.** `<label>_gnss_epoch.csv` + `<label>_gnss_signals.csv` under
  `getExternalFilesDir("gps-debug")`, mirrored to `Download/MovementLogger/` via
  `PublicMirror`. Byte-identical schema to the desktop tool.
- **Non-destructive.** Only zero-length polls are sent; the box enables UBX
  output in the receiver's RAM layer only (reverts on power-cycle). Needs box
  firmware ≥ v0.0.18 (bridge opcode + MAX-M10S UBX-output fix); older firmware
  ignores 0x0D and the survey shows "no NAV-PVT reply".

## Numerics gotchas

- `GpsMath.rollingMedianSimple` allocates a buffer of `w + 1` (not `w`) because a centred window at the array's middle covers `2·half + 1` elements — odd windows fit `w`, even windows need one more slot. Discovered the hard way; tests cover both parities now.
- `Fusion.noseAngleSeriesDeg` uses a 60 s rolling median for drift baseline. At 100 Hz that's a 6000-sample window — the simple O(n·w·log w) impl is unusable on long sessions (multi-second on phone). `GpsMath.rollingMedian` auto-dispatches to the TreeMap fast path for windows ≥ 32 and inputs ≥ 64.
- Madgwick output is sensitive to mount orientation. The desktop GUI has a `--mount mast|deck` flag in `animate_cmd.rs`; the Android Replay screen currently assumes the same mount as `animate_cmd.rs`'s default (Y axis along the board nose). If a future user reports inverted pitch, surface this as a UI toggle.

## Release pipeline

Releases are tag-driven: push `vX.Y.Z` and `.github/workflows/release.yml`
builds + signs + uploads to the Play internal track and creates the GitHub
release. Full pipeline docs (secrets, versionCode derivation, production
promotion, Play listing source of truth, first-release caveats, why we
bypass Gradle Play Publisher) live in the `release` skill —
`.claude/skills/release/SKILL.md` — loaded on demand. Bump the fallback
`versionCode`/`versionName` in `app/build.gradle.kts` and update
`app/src/main/play/release-notes/de-DE/default.txt` in the release commit.

## Memory and references

The full BLE wire spec, source-Rust-project map, and architectural notes live in the project memory under `~/.claude/projects/-home-zdavatz-software-movement-logger-android/memory/`. Check `MEMORY.md` there for the index before re-deriving any of it. The Phase-2 architecture deferral is resolved (Kotlin rewrite landed in v0.0.3); update that memory entry if the decision needs to be revisited for future numerics work.
