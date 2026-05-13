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

Two screens via the bottom navigation bar.

### Sync

Scans for the `PumpTsueri` box over BLE, connects via GATT, and drives the FileSync protocol:

- **Scan / Connect** — runtime BLE permission prompts on first launch.
- **List files** — splits results into a **Sensor** group (Sens\*.csv, Gps\*.csv, Bat\*.csv, Mic\*.wav) and a **Debug** group, matching the desktop GUI.
- **Download** — saves each file to `Android/data/ch.ywesee.movementlogger/files/` under its original name. Visible in the system Files app. Long downloads keep running when you switch apps or lock the phone — a foreground notification shows the active file + percentage so the OS doesn't kill the worker mid-READ.
- **Delete** — removes a file from the SD card.
- **Start session** — sends START_LOG with a user-set duration (default 30 min). The box reboots into LOG mode and is invisible to Scan until the duration elapses. An on-screen countdown banner ticks while the session runs; the physical button on the box aborts early.
- **STOP_LOG** — gracefully closes any active logging session so subsequent LIST/READ stop returning BUSY. Does **not** reboot the box.

### Replay

Pick any video from your phone plus a `Sens*.csv` / `Gps*.csv` pair that the Sync tab downloaded, and the app:

1. Pulls the video's `creation_time` from its metadata to anchor wall-clock alignment. The GPS CSV's `hhmmss.ss` timestamps carry no date, so the video's UTC date is also used as the session date — replaying a clip from last week stays correctly aligned.
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
