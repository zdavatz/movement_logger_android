# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Android port of the Movement Logger desktop app at `~/software/fp-sns-stbox1/Utilities/rust`. Two screens:

- **Sync** — connects to the PumpTsueri SensorTile.box over BLE, downloads its CSV recordings, saves them to app-private external storage.
- **Replay** — picks a saved sensor / GPS CSV pair and a video, plays them time-synced with overlaid panels (speed, pitch / Nasenwinkel, height above water, GPS track).

Pure Kotlin + Jetpack Compose, no NDK. Phase-2 numerics (Madgwick fusion, baro height, butterworth, etc.) are ported from `stbox-viz/*.rs` to Kotlin under `data/`; the desktop's plotly-HTML / GIF / board-3D output side is replaced by direct Compose Canvas rendering. ffmpeg-overlay GIF export remains desktop-only.

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
│   ├── FileSyncProtocol.kt    UUIDs, opcodes, status bytes
│   └── BleClient.kt           single-worker GATT state machine
├── data/
│   ├── CsvParsers.kt          Sens / Gps / Bat CSV → typed rows
│   ├── GpsTime.kt             hhmmss.ss → absolute UTC ms
│   ├── VideoMetadata.kt       MediaMetadataRetriever creation_time
│   ├── GpsMath.kt             haversine, speed, fast rolling-median
│   ├── Butterworth.kt         4th-order LP design + filtfilt
│   ├── EulerAngles.kt         quat → roll/pitch/yaw + gimbal-lock regions
│   ├── Madgwick.kt            6DOF IMU AHRS + nose-angle series
│   ├── Baro.kt                GPS-anchored TC'd-pressure height
│   └── FusionHeight.kt        α-β baro + body-frame acc complementary
└── ui/
    ├── MainNav.kt             bottom-nav scaffold (Sync / Replay)
    ├── FileSyncScreen.kt      Sync tab UI
    ├── FileSyncViewModel.kt   Sync state machine
    ├── ReplayScreen.kt        Replay tab UI + Compose Canvas panels
    └── ReplayViewModel.kt     CSV + fusion pipeline orchestration
```

### Sync tab — BLE FileSync

- `ble/BleClient.kt` is the canonical Kotlin port of the desktop client's `stbox-viz-gui/src/ble.rs`. All Android BLE callbacks marshal raw events into one `Channel<RawEvent>`; one coroutine `select`s between that and the command channel, holding `CurrentOp` (Idle / Listing / Reading / Deleting). Watchdog ticks every 200 ms are posted as `RawEvent.Tick` through the same channel so op-state mutation stays single-threaded without locks. Read the Rust comments before changing behaviour here.
- Compose UI binds to `StateFlow<FileSyncUiState>`. Runtime BLE permissions via `rememberLauncherForActivityResult`; Android 12+ uses `BLUETOOTH_SCAN` (with `neverForLocation`) + `BLUETOOTH_CONNECT`, pre-12 falls back to legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` / `ACCESS_FINE_LOCATION` (manifest caps the legacy ones at `maxSdkVersion=30`).
- Downloaded files land in `Android/data/ch.ywesee.movementlogger/files/` under the original filename from LIST.

### Replay tab — data on top of video

`ReplayViewModel.maybeComputeFusion()` runs the full pipeline on `Dispatchers.Default` after both `Sens*.csv` and `Gps*.csv` are picked:

1. `Fusion.detectDtSeconds` → sample rate
2. `Fusion.computeQuaternions` (β = 0.1, matches `animate_cmd.rs:78`)
3. `Fusion.noseAngleSeriesDeg` — 1 s + 60 s rolling-median drift correction (uses the TreeMap-based fast median in `GpsMath.rollingMedianFast`, ~100× faster than the simple O(n·w) impl for the 6000-sample baseline)
4. `Baro.heightAboveWaterM` — GPS-anchored water reference, falls back to session-max pressure when no stationary anchors exist
5. `FusionHeight.fusedHeightM` — α-β complementary baro + body→world-rotated acc
6. Per-sensor-row absolute UTC by tick-offset from the GPS anchor

Video alignment: `MediaMetadataRetriever`'s `METADATA_KEY_DATE` against four common ISO-8601 / RFC-3339 spellings → UTC ms. GPS anchor = first parseable `hhmmss.ss` against today's UTC date (the desktop's `--date YYYY-MM-DD` override hasn't landed yet; revisit when needed).

Panels (all Compose Canvas, all bound to a 33 ms playhead poll):

- **Speed** — `GpsMath.smoothSpeedKmh` (clip > 60 km/h, linear-interp gaps, 5-sample rolling median).
- **Pitch / Nasenwinkel** — `noseAngleSeriesDeg`, symmetric ±max scaling around zero.
- **Height** — overlay of raw baro (thin grey) and fused (thick primary).
- **GPS track** — lat/lon with `cos(meanLat)` longitude correction; moving red dot at the playhead.

Each panel takes its own absolute-time array (`gpsAbsTimesMs` or `sensorAbsTimesMs`) and binary-searches the cursor index from `videoMeta.creationTimeMillis + playheadMs`. When the video has no `creation_time`, cursors hide (manual offset slider is a future polish slice).

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

- Keystore copied from generika_android (same ywesee GmbH cert) into `keystore/release.keystore` (gitignored). Credentials in `signing.properties` at repo root (also gitignored).
- `app/build.gradle.kts` loads `signing.properties` at configuration time; falls back to unsigned release when absent (e.g. on CI without secrets).
- Bump `versionCode` and `versionName` in `app/build.gradle.kts` for each release.
- `./gradlew bundleRelease assembleRelease` → AAB + APK.
- Commit + tag `vX.Y.Z` + `git push && git push origin vX.Y.Z`.
- `gh release create vX.Y.Z app/build/outputs/apk/release/app-release.apk app/build/outputs/bundle/release/app-release.aab --title … --notes …` for the GitHub release with sideload binaries attached.

## Memory and references

The full BLE wire spec, source-Rust-project map, and architectural notes live in the project memory under `~/.claude/projects/-home-zdavatz-software-movement-logger-android/memory/`. Check `MEMORY.md` there for the index before re-deriving any of it. The Phase-2 architecture deferral is resolved (Kotlin rewrite landed in v0.0.3); update that memory entry if the decision needs to be revisited for future numerics work.
