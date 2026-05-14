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
│   ├── LiveSample.kt          46-byte SensorStream wire layout + decoder
│   ├── BleClient.kt           single-worker GATT state machine; dual CCCD (FileData + SensorStream)
│   ├── FileSyncCore.kt        process-singleton: BleClient + UiState owner (FileSync + Live)
│   └── BleSyncService.kt      foreground service (connectedDevice) keeping BLE alive in background
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
│   ├── ReplayTrim.kt          slice parallel arrays to a UTC-ms window
│   ├── ExportPanelRenderer.kt android.graphics.Canvas port of the four panels
│   └── VideoExporter.kt       Media3 Transformer combined-video pipeline
└── ui/
    ├── MainNav.kt             bottom-nav scaffold (Live / Sync / Replay)
    ├── LiveScreen.kt          Live tab UI: readout grid + two Compose Canvas sparklines
    ├── FileSyncScreen.kt      Sync tab UI
    ├── FileSyncViewModel.kt   thin façade over FileSyncCore (Activity-scoped)
    ├── ReplayScreen.kt        Replay tab UI + Compose Canvas panels
    └── ReplayViewModel.kt     CSV + fusion pipeline orchestration
```

### Live tab — SensorStream readouts

`ble/LiveSample.kt` mirrors the desktop `LiveSample` in `stbox-viz-gui/src/ble.rs`: a 46-byte little-endian packed snapshot decoded into typed fields (acc mg, gyro centi-dps, mag mG, pressure Pa, GPS lat/lon ×1e7, fix-q + sat count + flags). Two transport modes — single 46-byte notify when the negotiated MTU is large enough, or a 3-chunk sequence (0x00 / 0x01 / 0x02 prefix bytes) on the default-MTU fallback path. Out-of-order chunks reset the asm buffer; malformed frames drop silently and auto-resync on the next 0x00 start.

`BleClient` now subscribes to **two** characteristics on connect (FileData *and* SensorStream). Android serialises GATT ops one-at-a-time, so the second CCCD write is chained from the first's `onDescriptorWrite` callback — both descriptors share the standard CCCD UUID, so `RawEvent.DescriptorWritten` carries the parent characteristic UUID to route the ack. SensorStream subscription is soft-fail: legacy PumpTsueri firmware doesn't expose it, the user still gets FileSync, and the Live tab stays empty with a log line saying why.

**MTU upgrade is mandatory for Live data on Android.** The PumpLogger firmware sends 46-byte single-notify packets even when the negotiated MTU can't hold them — it does *not* fall back to the 3-chunk protocol the desktop client expects. At the default ATT MTU of 23 (20-byte payload), Android truncates every notify to 20 bytes and the LiveSample decoder rejects them. So `onServicesDiscovered` calls `gatt.requestMtu(247)` *before* the CCCD chain; subscribe-and-write-CCCD runs from `onMtuChanged` instead. With the bump in place the box delivers full 46-byte payloads in a single notify; the 3-chunk reassembly path remains in the code as a fallback for hypothetical future firmware that needs it.

`FileSyncCore.onSample` updates a `LiveState` inside `FileSyncUiState`: most-recent decoded sample, `latestSampleAtMs` for the freshness label, sample count, plus two 120-point rolling buffers (acc magnitude in g, pressure in hPa). Cleared on disconnect so a stale sparkline doesn't masquerade as a live stream.

The Compose UI gates the readouts on `Connection.Connected` — when disconnected it shows a "Go to Sync" button. A 250 ms tick recomposes the freshness strip so the "X ms ago" label keeps moving between the 2-second sample arrivals.

### Sync tab — BLE FileSync

- `ble/BleClient.kt` is the canonical Kotlin port of the desktop client's `stbox-viz-gui/src/ble.rs`. All Android BLE callbacks marshal raw events into one `Channel<RawEvent>`; one coroutine `select`s between that and the command channel, holding `CurrentOp` (Idle / Listing / Reading / Deleting). Watchdog ticks every 200 ms are posted as `RawEvent.Tick` through the same channel so op-state mutation stays single-threaded without locks. Read the Rust comments before changing behaviour here.
- `ble/FileSyncCore.kt` is a process-wide singleton that owns `BleClient` + `StateFlow<FileSyncUiState>`. Moved out of the ViewModel so the BLE worker and in-flight READ buffers survive Activity recreation. `FileSyncViewModel` is now a thin façade that observes the core and forwards commands.
- `ble/BleSyncService.kt` is a foreground service (`foregroundServiceType=connectedDevice`) the ViewModel `start()`s on scan/connect. It registers itself as a `FileSyncCore.Listener`, refreshes a sticky notification on every state change ("Downloading Sens001.csv 42 %" etc.), and self-stops 5 s after the core reports `isBusy()=false`. Mirrors the iOS approach (`UIBackgroundModes=bluetooth-central` + `beginBackgroundTask`).
- Compose UI binds to `StateFlow<FileSyncUiState>`. Runtime BLE permissions via `rememberLauncherForActivityResult`; Android 12+ uses `BLUETOOTH_SCAN` (with `neverForLocation`) + `BLUETOOTH_CONNECT`, pre-12 falls back to legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` / `ACCESS_FINE_LOCATION` (manifest caps the legacy ones at `maxSdkVersion=30`). `POST_NOTIFICATIONS` is requested on API 33+ but kept *separate* from the BLE-perm gate so denial only loses the foreground notification, not the BLE UI itself.
- Downloaded files land in `Android/data/ch.ywesee.movementlogger/files/` under the original filename from LIST.

### Replay tab — data on top of video

`ReplayViewModel.maybeComputeFusion()` runs the full pipeline on `Dispatchers.Default` after both `Sens*.csv` and `Gps*.csv` are picked:

1. `Fusion.detectDtSeconds` → sample rate
2. `Fusion.computeQuaternions` (β = 0.1, matches `animate_cmd.rs:78`)
3. `Fusion.noseAngleSeriesDeg` — 1 s + 60 s rolling-median drift correction (uses the TreeMap-based fast median in `GpsMath.rollingMedianFast`, ~100× faster than the simple O(n·w) impl for the 6000-sample baseline)
4. `Baro.heightAboveWaterM` — GPS-anchored water reference, falls back to session-max pressure when no stationary anchors exist
5. `FusionHeight.fusedHeightM` — α-β complementary baro + body→world-rotated acc
6. Per-sensor-row absolute UTC by tick-offset from the GPS anchor

Video alignment: `MediaMetadataRetriever`'s `METADATA_KEY_DATE` against four common ISO-8601 / RFC-3339 spellings → UTC ms. GPS anchor = first parseable `hhmmss.ss` against the video's session date (extracted via `GpsTime.utcYmdFromMillis` from the video's `creation_time`), falling back to today's UTC date when no video is picked yet. Picking the video after the GPS triggers `ReplayViewModel.reanchorGpsTimes()` so the sensor + GPS abs-time series stay consistent with the playhead.

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

## BLE protocol gotchas (carried over from the Rust client)

- Subscribe to FileData notifications **once per connection**, not per op. Subscribing per op risks losing the first packet if the box notifies before the await is parked.
- READ's first packet may be a 1-byte status error OR file content. Disambiguate: first packet, exactly 1 byte, AND byte ∈ {0xB0, 0xE1, 0xE2, 0xE3} → treat as error. Otherwise treat as content. CSV/log files start with ASCII text (well below 0x80) so the test is unambiguous in practice.
- LIST may not deliver its terminator `\n` on flaky links. Inactivity fallback: ≥1 row seen and 500 ms with no new bytes → treat as ListDone. Without this fallback the next op trips the "another op is in flight" guard for 20 s.
- After START_LOG, sleep ~500 ms before any subsequent Disconnect — write-without-response returns when bytes are queued, not when transmitted; a fast Disconnect can tear the link down before the opcode hits the air. Send Disconnect right after START_LOG (firmware NVIC_SystemReset's ~50 ms later, BLE link dies abruptly without LL_TERMINATE_IND).
- STOP_LOG does **not** reboot the box — it just posts `COMMAND_STOP_LOG` to the FileX thread, which gracefully closes any open Sens/Gps files. Connection stays up; LIST/READ afterward stop returning `0xB0 BUSY`.

## Numerics gotchas

- `GpsMath.rollingMedianSimple` allocates a buffer of `w + 1` (not `w`) because a centred window at the array's middle covers `2·half + 1` elements — odd windows fit `w`, even windows need one more slot. Discovered the hard way; tests cover both parities now.
- `Fusion.noseAngleSeriesDeg` uses a 60 s rolling median for drift baseline. At 100 Hz that's a 6000-sample window — the simple O(n·w·log w) impl is unusable on long sessions (multi-second on phone). `GpsMath.rollingMedian` auto-dispatches to the TreeMap fast path for windows ≥ 32 and inputs ≥ 64.
- Madgwick output is sensitive to mount orientation. The desktop GUI has a `--mount mast|deck` flag in `animate_cmd.rs`; the Android Replay screen currently assumes the same mount as `animate_cmd.rs`'s default (Y axis along the board nose). If a future user reports inverted pitch, surface this as a UI toggle.

## Release pipeline

Releases are driven from a single source: push a `vX.Y.Z` git tag and the GitHub Actions workflow at `.github/workflows/release.yml` builds the signed AAB + APK, runs `scripts/publish_to_play.py` to upload everything (bundle, listing texts, screenshots, 512×512 icon, video URL, contact email, release notes) to the Play Store **internal** track via the Android Publisher v3 API directly, and creates a GitHub release with the binaries attached. No local commit-and-bump dance needed.

```sh
git tag v0.0.6
git push origin v0.0.6
# Watch the workflow run; binaries land on GitHub + Play within ~5 min.
```

The workflow derives both `versionName` and `versionCode` from the tag:

- `versionName` = the tag minus the `v` prefix (`v0.0.6` → `"0.0.6"`).
- `versionCode` = `major * 10000 + minor * 100 + patch` (`0.0.6` → `6`, `0.1.0` → `100`, `1.0.0` → `10000`). Monotonic for any sane semver bump.

The `appVersionName` / `appVersionCode` Gradle properties in `app/build.gradle.kts` honor these, with sensible fallback defaults so local `assembleRelease` builds still work without CLI args.

### Required GitHub secrets

Add via Settings → Secrets and variables → Actions:

| Secret name                    | Value                                                                                          |
| ------------------------------ | ---------------------------------------------------------------------------------------------- |
| `RELEASE_KEYSTORE_BASE64`      | `base64 -w0 keystore/movement_logger_upload.keystore` output (single line, no newlines)                       |
| `STORE_PASSWORD`               | the `STORE_PASSWORD` value from local `signing.properties`                                      |
| `KEY_ALIAS`                    | the `KEY_ALIAS` value                                                                          |
| `KEY_PASSWORD`                 | the `KEY_PASSWORD` value                                                                       |
| `PLAY_SERVICE_ACCOUNT_JSON`    | full JSON contents of the Play Console service-account key (paste the file verbatim)            |

Service-account JSON comes from Google Cloud Console (Service Accounts → Create → download JSON key) plus a one-time grant in Play Console → Nutzer und Berechtigungen → invite the SA email and give Admin / all-permissions on Movement Logger. The Android Publisher API must be enabled in the GCP project (one-time, click-through prompt the first time you use it).

### Local development

`signing.properties` + `keystore/movement_logger_upload.keystore` at the repo root work the same as before for local signed builds (`./gradlew bundleRelease assembleRelease`). The Play Publisher plugin's tasks are disabled via `play { enabled.set(false) }`, so day-to-day builds are unaffected by the missing service-account JSON. Drop `play-service-account.json` at the repo root if you want to run `scripts/publish_to_play.py` locally — it's gitignored.

### Promoting to production

The workflow defaults to the **internal** track with status `completed`. To push to production:

```sh
./gradlew bundleRelease -PappVersionName=0.0.X -PappVersionCode=X --rerun-tasks
python3 scripts/publish_to_play.py \
  --aab app/build/outputs/bundle/release/app-release.aab \
  --version-name 0.0.X \
  --track production \
  --release-status draft
```

`--release-status draft` is required while the app itself is still in Play Console "draft" state (i.e. no production release has ever been approved). The release lands as a draft in Play Console; complete the data-safety / content-rating / target-audience / app-category / privacy-policy forms in the web UI, then click **Send for review** to actually publish.

### Play listing source of truth

`app/src/main/play/` holds the listing metadata that `scripts/publish_to_play.py` uploads on each release:

- `default-language.txt` — `de-DE` (matches Play Console's default locale, which was set when the app was created with a German UI)
- `listings/de-DE/title.txt`, `short-description.txt`, `full-description.txt`
- `listings/de-DE/video-url.txt` — YouTube watch URL (one per locale; Play rejects direct uploads)
- `listings/de-DE/graphics/phone-screenshots/*.png` — synced from `/screenshots/`; min 2, max 8, 16:9 or 9:16 (or close to it, each side max 2.3× the other)
- `listings/de-DE/graphics/icon/icon.png` — 512×512 RGB (no alpha; Play rejects alpha channels on icons)
- `release-notes/de-DE/default.txt` — "what's new" string for the upcoming release

Edit these files in the same commit that bumps the version, and they ride along on the next tag push.

### Why we replaced Gradle Play Publisher with a direct-API script

Three independent reasons:

1. **Deprecated IAP endpoint.** GPP's umbrella `publishReleaseApps` chains in `publishReleaseProducts` + `publishReleaseSubscriptions`, which hit the legacy `/applications/.../inappproducts` v3 endpoint. Google flagged it with `PERMISSION_DENIED: "Please migrate to the new publishing API."` for apps that have never had in-app products configured. Same for the `bootstrapListing` task — both query IAPs even when none exist.
2. **No `changesNotSentForReview` in 3.x.** GPP 3.13's `play { }` extension lacks `changesNotSentForReview` (landed in 4.0, which requires Gradle 9). We initially needed it to bypass the review gate; turned out Google now rejects `changesNotSentForReview=true` with `INVALID_ARGUMENT: "Changes are sent for review automatically"` for this app, so we don't need it after all — but discovering that took several failed runs.
3. **Aggressive Gradle UP-TO-DATE caching.** GPP's `publishReleaseListing` decides what's been uploaded based on local file mtimes, not on actual Play state. If you re-run the workflow after a partial failure, GPP says "UP-TO-DATE" and skips the uploads even though Play has nothing. Forcing `--rerun-tasks` works but defeats incremental builds elsewhere.

`scripts/publish_to_play.py` sidesteps all three: one open edit, explicit uploads of bundle + listing + screenshots + icon + release-notes + details, one commit. ~250 LOC, no plugin dependency. GPP stays applied (`play { enabled.set(false) }`) only so `processReleaseVersionCodes` continues to wire into the bundle task graph cleanly; all its real publish tasks are off.

### First-release caveats

Before the first production push from this pipeline, the developer must have:

1. **Created the app entry** on https://play.google.com/console (one-time). The API can publish updates but not create the app itself.
2. **Created a dedicated upload keystore** (`keystore/movement_logger_upload.keystore`) — Play Console's App Signing rejects keys reused across apps, so the ywesee shared `release.keystore` (still used by generika/sdif/parados) cannot be used here.
3. **Granted the service-account Admin (or Release manager)** on Movement Logger via Play Console → Nutzer und Berechtigungen.
4. **Completed the Play-Console-only forms** before production reviews accept anything: Datenschutzerklärung URL, Datenschutz/Data safety, Altersfreigabe/Content rating questionnaire, Zielgruppe/Target audience, App-Kategorie. The API can stage a draft release without these but cannot send it for review.

Internal track uploads work as soon as 1+2+3 are done; production reviews need 4 on top.

## Memory and references

The full BLE wire spec, source-Rust-project map, and architectural notes live in the project memory under `~/.claude/projects/-home-zdavatz-software-movement-logger-android/memory/`. Check `MEMORY.md` there for the index before re-deriving any of it. The Phase-2 architecture deferral is resolved (Kotlin rewrite landed in v0.0.3); update that memory entry if the decision needs to be revisited for future numerics work.
