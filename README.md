# Movement Logger for Android

Android port of the [Movement Logger desktop app](https://github.com/zdavatz/fp-sns-stbox1) for the **PumpTsueri** SensorTile.box. Downloads CSV recordings from the box over BLE and plays them back time-synced with a video of the session — speed, pitch, height above water, and GPS track all moving together as the video plays.

[![Latest release](https://img.shields.io/github/v/release/zdavatz/movement_logger_android)](https://github.com/zdavatz/movement_logger_android/releases/latest)

## Install

Sideload the latest release APK:

```sh
adb install -r app-release.apk
```

The APK and AAB are attached to every tagged release: <https://github.com/zdavatz/movement_logger_android/releases/latest>

The always-current direct-download URL for the APK is

```
https://github.com/zdavatz/movement_logger_android/releases/latest/download/app-release.apk
```

Requires Android 8.0 (API 26) or newer. Targets API 35.

## What it does

Five tabs via the bottom navigation bar: Live, GPS, Sync, Replay, GPS Debug.

### Live

When connected to a PumpLogger-firmware box (advertises as `STBoxFs`), shows the 0.5 Hz SensorStream snapshot live: accel / gyro / mag / baro / GPS readouts plus two sparklines (acceleration magnitude, barometric pressure). Subscription is automatic on Connect — no extra button. On connect, the app requests an ATT MTU of 247 so each 46-byte snapshot lands in a single notify (the firmware doesn't chunk on small MTUs, so the upgrade is required to see data). Legacy PumpTsueri firmware doesn't expose the SensorStream characteristic, so the tab stays empty with a one-shot log line in the Sync log explaining why.

A **Board angles** card sits above the readouts: live **Pitch** (up/down hill), **Roll** (lean left/right) and **Yaw** (heading), computed about the box's *physical* axes from the gyro + accel orientation filter (not the raw accel formulas). The box's nose is its Y axis, so these come out right where the old accel-only roll/pitch — which assumed a phone's long X axis — were swapped. Two readouts: **Absolute** (yaw = compass heading) and **Calibrated** — tap **Zero here** to tare the current pose to 0°, **Clear** to reset; a "zeroed M:SS ago" line shows how long the tare has stood. The tare is remembered across launches **and — with box firmware ≥ v0.0.37 — is stored on the box itself** (`CAL.CFG` on the SD), so a calibration set on Android is visible to the Desktop / iPhone on their next connect without a re-tap.

### GPS

Drives a u-blox GNSS receiver plugged into the phone's USB-C port (CDC-ACM serial) — an independent fix to cross-check the box GPS. Reference hardware: [**GNSS Receiver u-blox M8, 3.3 V – 5 V, RoHS, IPX6** (AliExpress)](https://de.aliexpress.com/item/1005008108943368.html) — USB-C, u-blox USB ID `1546:01a8`; any receiver that enumerates as a u-blox CDC-ACM serial device should work. Note the M8 delivers ~5 Hz with multiple GNSS constellations enabled (10 Hz is GPS-only), and IPX6 means spray-proof, not submersible — GPS can't measure through water anyway. Mount it as high and dry as possible (helmet or top of the impact vest) — GPS cannot measure through water, and a submerged antenna makes the receiver fabricate plausible-looking data rather than fail cleanly. On connect the app bumps the receiver from its factory 1 Hz to **10 Hz** (`UBX-CFG-RATE`), shows a live fix readout + per-sentence rate counters, and **Record** writes `UbloxGps_*.csv` files in the box's `Gps*.csv` schema (mirrored to `Download/MovementLogger/`). A recordings list shows every file with max speed / distance / duration, swipe-left-to-delete, and a per-row menu with **Map / View / Share**.

Recording is built to survive a session on the water: a foreground notification ("Recording GPS — N rows · 10.0 Hz") keeps the reader alive with the screen off or the app in the background, and after a USB glitch or a silently stalled port the reader **auto-reconnects** as soon as the receiver re-enumerates — no more dead sessions until you notice and tap Connect by hand.

**Race mode** — a card in the GPS tab streams this phone's live position (2 Hz, small UDP datagrams) to the MovementLogger desktop app's **Race** tab, which plots every rider on a shared map. Enter your rider name plus the `ip:port` the desktop shows when it starts listening (same WiFi, or any relay that forwards the datagrams). The iOS app has the same card, sourced from the iPhone GPS or the Apple Watch.

**Map** draws the recorded track on an interactive OpenStreetMap view — pinch-zoom/pan, green start and red end markers. Its **Share** button exports a shareable ride image, the Android twin of the iOS Rides-tab PNG: real map tiles under an **activity-coloured track** — blue **in the water**, green **on the board**, orange **on land** (same colours as iOS) — with a legend, start/end markers, and a branded footer with the app logo, top speed, distance, duration and the project link. The activity is inferred without any extra sensor: the map itself knows where the water is (each track point is checked against the OpenStreetMap water pixels), so land is decided geographically; on the water, sustained speed ≥ 3.5 km/h means on the board, and slow stretches or stretches next to signal blackouts (the antenna rides at/under the surface whenever you're off the board) count as in the water. Offline, a weaker speed + blackout heuristic takes over. The top-speed stat is outlier-hardened: when the antenna dips under water the receiver fabricates plausible-looking speeds (27 km/h on a 7 km/h session), so speeds near signal blackouts or inconsistent with the actual positions are discarded. The drawn track gets the same treatment: fabricated positions are dropped and the line breaks across signal holes instead of bridging them. The exported PNG also lands in `Download/MovementLogger/`, so it shows up in Google Files without going through the share sheet. Downloaded `Gps*.csv` files in the Sync tab get the same **Map** button.

### Sync

Scans for the box over BLE (advertise name `PumpTsueri` *or* `STBoxFs`), connects via GATT, and drives the FileSync protocol:

- **Scan / Connect** — runtime BLE permission prompts on first launch.
- **List files** — splits results into a **Sensor** group (Sens\*.csv, Gps\*.csv, Bat\*.csv, Mic\*.wav) and a **Debug** group, matching the desktop GUI.
- **Download** — saves each file under its original name and **also mirrors it into `Download/MovementLogger/`**, the public Downloads folder that Google Files and any file manager can browse directly (CSV/WAV keep the right extension + MIME). Downloads run **strictly one-at-a-time**: tapping a second file while a big one is still streaming queues it (the row shows **Queued**) and it starts automatically when the first finishes — no more stuck rows. Long downloads keep running when you switch apps or lock the phone — a foreground notification shows the active file + percentage so the OS doesn't kill the worker mid-READ.
- **View** — once a file is fully downloaded the row's button switches from Download to **View**. Opens an inline preview (CSV / log files show their head, capped at 48 KB so multi-MB recordings open instantly) plus a **Share** action that exports the full untruncated file to Gmail / Drive / Files / Bluetooth via the system share sheet.
- **Sync now** — distinct from per-file Download: pulls every session file (Sens\*/Gps\*/Bat\*.csv + Mic\*.wav) on the box not already mirrored locally, tracked in a local SQLite DB (`filesDir/sqlite/sync.db`) keyed per box. Manual downloads register too, so a later Sync skips them. Purely additive — never deletes anything on the box. While a pass runs, a progress card under the Keep-synced toggle shows "N of M files · X / Y MB · Z %" with a per-file bar for the in-flight READ. Port of the desktop's SQLite-tracked sync.
- **Background sync** — when the box is in **Auto** mode and **Keep synced** is on, the app keeps mirroring even after you close it. A WorkManager job wakes every ~15 minutes, connects to the remembered box, runs one sync pass, and disconnects — only when the foreground UI isn't already using BLE ("GUI wins, agent yields"). Survives device reboots via a boot receiver that re-arms the schedule. Android pendant of the desktop's `--agent` background process.
- **Delete** — removes a file from the SD card.
- **Log mode (Auto / Manual)** — persisted *on the box* (requires firmware v0.0.7+). **Auto** (default): the box opens a recording session automatically on every power-on — the data-safe always-on behaviour, nothing can leave it silently not recording. **Manual**: the box boots idle and records nothing until you tap **Start session**; it then logs for the chosen duration and goes idle again. Manual is opt-in and carries a deliberate tradeoff — a forgotten Start session silently loses the run. The app sends `SET_MODE` to change it and `GET_MODE` on connect to reflect the box's current mode (legacy PumpTsueri firmware can't report it, so the toggle stays "unknown").
- **Start session** (Manual mode only) — sends `START_LOG` with a user-set duration (default 30 min). The box opens a session and auto-closes after the duration; the BLE link stays up (current firmware does **not** reboot). An on-screen countdown banner ticks while the session runs.
- **GPS power (On / Off)** — a selector next to the log-mode toggle that turns the box's u-blox GPS off to save battery when it's faulty or you don't need a track (**requires box firmware v0.0.35+**). The box persists the setting; the app just reflects and sends it (`GET`/`SET` mirror the log-mode plumbing). With GPS off the IMU + barometer keep logging, so Replay still time-aligns from the phone-clock `# SYNC` anchor — you lose speed + GPS track but keep pitch / roll and height.

### Replay

Pick any video from your phone plus a `Sens*.csv` / `Gps*.csv` pair that the Sync tab downloaded, and the app:

1. Pulls the video's `creation_time` from its metadata to anchor wall-clock alignment. The box has no RTC, so on every connect the app stamps the phone's wall clock into the box's open logs (`SET_TIME` `0x08`, firmware v0.0.10+); when those `# SYNC` anchors are present the panels align to absolute time straight from them — **drift-free and with no GPS fix required**, in the same clock domain as the video — instead of leaning on the GPS `hhmmss.ss` strings (which stay as the fallback for older recordings, combined with the video's UTC date so a clip from last week still aligns).
2. Parses the CSVs.
3. Runs the full numerics pipeline (Madgwick 6DOF fusion, GPS-anchored baro height, complementary baro + IMU fused height, drift-corrected nose angle, GPS-derived speed with outlier rejection + rolling median).
4. Renders four data panels stacked below the video player:
   - **Speed** (km/h) — position-derived, smoothed.
   - **Pitch / Nasenwinkel** (°) — board nose elevation.
   - **Height above water** (m) — raw baro overlaid by complementary-fused.
   - **GPS track** — lat/lon path with longitude aspect correction.
5. Each panel shows a red cursor that tracks the video playhead — every panel updates live while the video plays.

The downloaded CSVs from the Sync tab are picked up directly, so the typical loop is: take the box out, run a session, plug into BLE on the way home, download files, open the video in Replay.

#### Export combined video

Once the video and both CSVs are picked, **Export combined** renders a single MP4 with the rider on top and the four data panels stacked below — exactly what the live Replay shows, baked into a video the phone's Photos app understands. The export is automatically trimmed to the video's time window, so panels only show sensor + GPS data that overlaps the ride, not the whole session. HDR clips (HEVC HLG, iPhone-style) are tone-mapped to SDR before encoding so the panel overlays composite correctly. When the encode finishes, an **Open video** button on the banner launches the system video viewer on the freshly saved file under `Movies/MovementLogger/`.

The Alignment card shows the row counts that fall inside the video window, so you can see at a glance how much of the session will be in the export (`trim → gps: 365 / 26914` etc.) before kicking it off.

### GPS Debug

Live u-blox UBX diagnostics tunnelled over the box's BLE link (no cable), for antenna selection + mounting. Connect the box on the Sync tab, then Start: the app bridges the receiver over BLE (firmware opcodes `0x0D`/`0x0E`), polls it once a second (NAV-PVT / NAV-DOP / NAV-SAT / NAV-SIG / MON-RF) and writes two CSVs — `<label>_gnss_epoch.csv` (fix quality: fixType, numSV, hAcc, DOP) and `<label>_gnss_signals.csv` (per-signal C/N0, elevation, azimuth, prRes) — under `Android/data/…/files/gps-debug/` and mirrored to `Download/MovementLogger/`, same schema as the desktop `gps-debug` tool, plus antenna/RF health (antStatus, AGC, jamming). Polling is non-destructive; the receiver is never persistently reconfigured. **Needs box firmware ≥ v0.0.18** (the GPS-bridge opcode + the MAX-M10S UBX-output fix); on older firmware it just shows "no NAV-PVT reply". (This is BLE-only — separate from the USB GNSS tab.)

## Build from source

Requirements: JDK 17+, Android SDK with build-tools 34.0.0 and platforms 35.

```sh
./gradlew assembleDebug              # debug APK
./gradlew installDebug               # install on an attached device
./gradlew :app:testDebugUnitTest     # run JVM unit tests
```

Outputs land in `app/build/outputs/`.

For signed release builds, drop a `signing.properties` at the repo root with `STORE_FILE`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (all gitignored), then `./gradlew bundleRelease assembleRelease`.

## Releasing

Pushing a `vX.Y.Z` git tag fires `.github/workflows/release.yml`, which derives `versionName` + `versionCode` from the tag, builds a signed AAB + APK, uploads to the Play Store **internal** track (bundle + listing + screenshots + 512×512 icon + Ayano promo video URL + release notes via `scripts/publish_to_play.py`), and creates a GitHub release with both binaries attached:

```sh
git tag v0.0.13 && git push origin v0.0.13
```

Manual production push (when you're ready to promote — Play Console forms must be complete first):

```sh
./gradlew bundleRelease -PappVersionName=0.0.13 -PappVersionCode=13
python3 scripts/publish_to_play.py \
  --aab app/build/outputs/bundle/release/app-release.aab \
  --version-name 0.0.13 \
  --track production \
  --release-status draft   # required while the app is still in Play "draft" state
```

See [CLAUDE.md](CLAUDE.md) for the full release-pipeline docs (required GitHub secrets, listing source-of-truth under `app/src/main/play/`, why we run a direct-API Python script instead of Gradle Play Publisher).

## Architecture

Two layers; see [CLAUDE.md](CLAUDE.md) for the full map.

- **BLE** — Kotlin port of the Rust desktop client's single-worker GATT state machine. All Android BLE callbacks funnel into one `Channel<RawEvent>`; a coroutine `select`s between that and the command channel so op-state mutation stays single-threaded.
- **Numerics** (under `data/`) — Kotlin ports of the desktop's `stbox-viz/*.rs` math: haversine + speed, Madgwick AHRS, Butterworth, quat ↔ Euler, GPS-anchored barometric height, complementary baro + IMU fusion. Replaces the desktop's plotly-HTML + GIF + ffmpeg-overlay output side with direct Compose Canvas rendering, so there's no NDK and the APK stays small (~10 MB signed).

## License

GPL-3.0. See [LICENSE](LICENSE).
