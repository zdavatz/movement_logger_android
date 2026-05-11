# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Android port of the Movement Logger desktop app at `~/software/fp-sns-stbox1/Utilities/rust`. Talks to the PumpTsueri SensorTile.box over BLE and downloads its CSV recordings. Pure Kotlin + Jetpack Compose; no NDK for now.

Phase 1 (current): BLE FileSync only — scan / connect / LIST / READ / DELETE / STOP_LOG / START_LOG, save downloaded files to app-private external storage (`getExternalFilesDir(null)`).

Phase 2+ (not started): the ~7.5 kLOC of Rust numerics from `stbox-viz/` (Madgwick fusion, baro height, GPS, butterworth, board-3D, plotly HTML, GIF animation). Architecture choice between Kotlin-rewrite and Rust-via-JNI is intentionally deferred until Phase 1 is verified on a real device.

## Build & run

```sh
./gradlew assembleDebug          # build debug APK
./gradlew installDebug           # install on attached device (needs adb + USB)
./gradlew :app:compileDebugKotlin   # quick syntax check
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`. SDK path comes from `local.properties` (not committed). The wrapper pins Gradle 8.9; AGP is 8.7.3 in `gradle/libs.versions.toml`.

Targets: `minSdk 26 / compileSdk 35 / targetSdk 35`, `buildToolsVersion 34.0.0` (whatever's installed under `~/Android/Sdk/build-tools/`).

## Architecture

Single screen for now, three layers:

- `ble/FileSyncProtocol.kt` — UUIDs, opcodes, status bytes. Authoritative spec is the firmware's `ble_filesync.c`; the Rust client's `ble.rs` is the reference host implementation.
- `ble/BleClient.kt` — single-worker state machine. All Android BLE callbacks marshal raw events into one `Channel<RawEvent>`; one coroutine `select`s between that and the command channel, holding `CurrentOp` (Idle / Listing / Reading / Deleting). Watchdog ticks every 200 ms are posted as `RawEvent.Tick` through the same channel so op-state mutation stays single-threaded without locks. Design mirrors the Rust client — read its comments before changing behaviour here.
- `ui/FileSyncViewModel.kt` + `ui/FileSyncScreen.kt` — Compose UI binds to `StateFlow<FileSyncUiState>`. Runtime BLE permissions handled via `rememberLauncherForActivityResult`; Android 12+ uses `BLUETOOTH_SCAN` (with `neverForLocation`) + `BLUETOOTH_CONNECT`, pre-12 falls back to legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` / `ACCESS_FINE_LOCATION` (manifest caps the legacy ones at `maxSdkVersion=30`).

Downloaded files are saved to `Android/data/ch.ywesee.movementlogger/files/` under the original filename from LIST. The saved path is surfaced in the UI per row so the user can find them with the system Files app.

## BLE protocol gotchas (carried over from the Rust client)

- Subscribe to FileData notifications **once per connection**, not per op. Subscribing per op risks losing the first packet if the box notifies before the await is parked.
- READ's first packet may be a 1-byte status error OR file content. Disambiguate: first packet, exactly 1 byte, AND byte ∈ {0xB0, 0xE1, 0xE2, 0xE3} → treat as error. Otherwise treat as content. CSV/log files start with ASCII text (well below 0x80) so the test is unambiguous in practice.
- LIST may not deliver its terminator `\n` on flaky links. Inactivity fallback: ≥1 row seen and 500 ms with no new bytes → treat as ListDone. Without this fallback the next op trips the "another op is in flight" guard for 20 s.
- After START_LOG, sleep ~500 ms before any subsequent Disconnect — write-without-response returns when bytes are queued, not when transmitted; a fast Disconnect can tear the link down before the opcode hits the air.

## Memory and references

The full BLE wire spec, the source-Rust-project map, and the Phase-2 architecture deferral live in the project memory under `~/.claude/projects/-home-zdavatz-software-movement-logger-android/memory/`. Check `MEMORY.md` there for the index before re-deriving any of it.
