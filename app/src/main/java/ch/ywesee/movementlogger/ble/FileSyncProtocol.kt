package ch.ywesee.movementlogger.ble

import java.util.UUID

/**
 * Wire protocol for the PumpTsueri SensorTile.box's BLE FileSync service.
 * Authoritative spec lives in the firmware source (`Core/Src/ble_filesync.c`)
 * and the desktop Rust client (`stbox-viz-gui/src/ble.rs`).
 */
object FileSyncProtocol {
    /**
     * Accepted advertise names. `PumpTsueri` is the legacy SDDataLogFileX
     * firmware; `STBoxFs` is the bare-metal PumpLogger firmware (Peter's
     * PR #18) which adds the SensorStream characteristic. Match either so
     * one APK handles both during the firmware transition.
     */
    val BOX_NAMES: Array<String> = arrayOf("PumpTsueri", "STBoxFs")

    val FILECMD_UUID: UUID = UUID.fromString("00000080-0010-11e1-ac36-0002a5d5c51b")
    val FILEDATA_UUID: UUID = UUID.fromString("00000040-0010-11e1-ac36-0002a5d5c51b")
    /**
     * SensorStream — 0.5 Hz packed 46-byte all-sensor snapshot. Optional;
     * only PumpLogger firmware exposes it. Subscribing is enough to start
     * the stream; the box has no STREAM_START opcode.
     */
    val STREAM_UUID: UUID = UUID.fromString("00000100-0010-11e1-ac36-0002a5d5c51b")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Opcodes (first byte of FileCmd write).
    const val OP_LIST: Byte = 0x01
    const val OP_READ: Byte = 0x02
    const val OP_DELETE: Byte = 0x03
    const val OP_STOP_LOG: Byte = 0x04
    const val OP_START_LOG: Byte = 0x05
    /** SET_MODE `<u8>`: 0 = auto, 1 = manual. Persisted on the box. */
    const val OP_SET_MODE: Byte = 0x06
    /** GET_MODE: box replies one byte 0 = auto, 1 = manual. */
    const val OP_GET_MODE: Byte = 0x07
    /**
     * SET_TIME `<epoch_ms:u64-LE>`: push the phone's wall-clock millis so the
     * box (which has no RTC) stamps a `# SYNC epoch_ms=… tick_ms=…` anchor
     * into the open Sens/Gps CSVs, pairing the phone epoch with its
     * free-running ms counter. Sent on every connect; lets replay resolve
     * absolute wall-clock without a GPS fix. Box replies one status byte we
     * don't track (legacy firmware without 0x08 just ignores the write).
     */
    const val OP_SET_TIME: Byte = 0x08

    // Firmware-update (OTA) opcodes (firmware v0.0.x+). The box stages a new
    // image into the inactive flash bank, then verifies + swaps + resets on
    // COMMIT. Single-in-flight through the same worker state machine as READ
    // (concurrent ops get rejected with BUSY 0xB0).
    /** FW_BEGIN `[image_len:u32-LE][sha256:32]` — erase the inactive bank and
     *  arm staging. Box replies one status byte (0x00 ready, else error). */
    const val OP_FW_BEGIN: Byte = 0x09
    /** FW_DATA `[offset:u32-LE][bytes…]` — write one chunk at `offset`. Box
     *  replies a 4-byte LE next-expected-offset ACK, or a 1-byte error. */
    const val OP_FW_DATA: Byte = 0x0A
    /** FW_COMMIT (no payload) — verify the staged SHA, swap banks, reset. Box
     *  replies 0xA0 (FW_READY) then drops the link, or a 1-byte error. */
    const val OP_FW_COMMIT: Byte = 0x0B
    /** FW_ABORT (no payload) — discard the staged image. Box replies 0x00. */
    const val OP_FW_ABORT: Byte = 0x0C

    // Status bytes returned in single-byte FileData notifies.
    const val STATUS_OK: Byte = 0x00
    const val STATUS_BUSY: Byte = 0xB0.toByte()
    const val STATUS_NOT_FOUND: Byte = 0xE1.toByte()
    const val STATUS_IO_ERROR: Byte = 0xE2.toByte()
    const val STATUS_BAD_REQUEST: Byte = 0xE3.toByte()

    // Firmware-update status bytes (in single-byte FW_* notifies).
    /** FW_COMMIT success: image verified, box is swapping banks + resetting. */
    const val FW_READY: Byte = 0xA0.toByte()
    /** FW_COMMIT: staged SHA-256 didn't match — image rejected, box stayed
     *  on the old firmware. */
    const val FW_HASH_MISMATCH: Byte = 0xE4.toByte()
    /** FW_BEGIN bank-erase failed / FW_DATA / FW_COMMIT flash write failed. */
    const val FW_FLASH_FAIL: Byte = 0xE5.toByte()
    /** FW_BEGIN: image is larger than the inactive bank can hold. */
    const val FW_TOO_BIG: Byte = 0xE6.toByte()
    /** FW_DATA: offset didn't match the box's cursor (bad sequence), or
     *  FW_COMMIT: fewer bytes staged than `image_len` (short image). */
    const val FW_BAD_SEQ: Byte = 0xE7.toByte()

    fun isStatusByte(b: Byte): Boolean = b == STATUS_BUSY || b == STATUS_NOT_FOUND ||
        b == STATUS_IO_ERROR || b == STATUS_BAD_REQUEST

    fun statusMessage(b: Byte): String = when (b) {
        STATUS_BUSY -> "BUSY (logging in progress, send STOP_LOG first)"
        STATUS_NOT_FOUND -> "NOT_FOUND"
        STATUS_IO_ERROR -> "IO_ERROR"
        STATUS_BAD_REQUEST -> "BAD_REQUEST"
        else -> "unknown error"
    }

    /** Human-readable reason for a firmware-update error byte. */
    fun fwErrorMessage(b: Byte): String = when (b) {
        STATUS_BUSY -> "box busy (logging or another op in progress)"
        FW_HASH_MISMATCH -> "image rejected (hash mismatch)"
        FW_FLASH_FAIL -> "flash write failed"
        FW_TOO_BIG -> "image too big for the box's firmware bank"
        FW_BAD_SEQ -> "bad sequence / short image"
        STATUS_BAD_REQUEST -> "bad request (malformed FW command)"
        else -> "unknown firmware error (0x${"%02X".format(b.toInt() and 0xFF)})"
    }
}

sealed class BleCmd {
    data object Scan : BleCmd()
    data class Connect(val address: String) : BleCmd()
    data object Disconnect : BleCmd()
    data object List : BleCmd()
    /**
     * `size` is the full file length from the prior LIST (EOF marker).
     * `offset` is the byte position to resume/grow from (0 = whole
     * file) — the firmware seeks there before streaming, so an
     * interrupted or grown file continues instead of restarting.
     * Wire: `0x02 + name + 0x00 + offset(u32-LE)` (port of desktop
     * v0.0.11/#8).
     */
    data class Read(val name: String, val size: Long, val offset: Long) : BleCmd()
    data object StopLog : BleCmd()
    data class StartLog(val durationSeconds: Int) : BleCmd()
    data class Delete(val name: String) : BleCmd()
    /** Persist the box log-mode (false = auto, true = manual). */
    data class SetLogMode(val manual: Boolean) : BleCmd()
    /** Query the box's current log-mode; reply arrives as [BleEvent.LogMode]. */
    data object GetLogMode : BleCmd()
    /**
     * Push the phone's current wall-clock millis to the box so it stamps a
     * time-sync anchor into the open Sens/Gps CSVs. Fire-and-forget — no
     * tracked reply (so legacy firmware that ignores 0x08 never stalls us).
     */
    data class SetTime(val epochMs: Long) : BleCmd()
    /**
     * Upload a firmware image to the box's inactive flash bank, then verify +
     * swap + reset (OTA). `image` is the exact `.bin` bytes; `sha256` is its
     * SHA-256 digest (32 bytes). Drives the FW_BEGIN → FW_DATA… → FW_COMMIT
     * handshake through the single-op state machine; progress + result arrive
     * as [BleEvent.FwUploadProgress] / [BleEvent.FwUploadDone].
     */
    data class UploadFirmware(val image: ByteArray, val sha256: ByteArray) : BleCmd() {
        override fun equals(other: Any?): Boolean =
            other is UploadFirmware && image.contentEquals(other.image) &&
                sha256.contentEquals(other.sha256)
        override fun hashCode(): Int = 31 * image.contentHashCode() + sha256.contentHashCode()
    }
    /**
     * Abort an in-flight firmware upload (best-effort FW_ABORT). Cancels the
     * `Uploading` op locally regardless of the box's reply.
     */
    data object AbortFirmware : BleCmd()
}

sealed class BleEvent {
    data class Status(val msg: String) : BleEvent()
    data class Discovered(val address: String, val name: String, val rssi: Int) : BleEvent()
    data object ScanStopped : BleEvent()
    data object Connected : BleEvent()
    data object Disconnected : BleEvent()
    data class ListEntry(val name: String, val size: Long) : BleEvent()
    data object ListDone : BleEvent()
    data class ReadStarted(val name: String, val size: Long) : BleEvent()
    data class ReadProgress(val name: String, val bytesDone: Long) : BleEvent()
    /**
     * `base` is the offset the streamed segment started at (= the
     * resume offset requested). The consumer appends `content` to the
     * local mirror at `base` (desktop v0.0.14 live-mirror model).
     */
    data class ReadDone(val name: String, val content: ByteArray, val base: Long) : BleEvent() {
        override fun equals(other: Any?): Boolean =
            other is ReadDone && name == other.name && base == other.base &&
                content.contentEquals(other.content)
        override fun hashCode(): Int =
            (31 * name.hashCode() + content.contentHashCode()) * 31 + base.hashCode()
    }
    /**
     * A READ cut short by a link drop / 20 s stall. Carries the partial
     * segment so the consumer appends it to the mirror — the resume
     * then continues from the *true* break point, not the last
     * completed segment (desktop v0.0.9/#6). Followed by an `Error`.
     */
    data class ReadAborted(val name: String, val content: ByteArray, val base: Long) : BleEvent() {
        override fun equals(other: Any?): Boolean =
            other is ReadAborted && name == other.name && base == other.base &&
                content.contentEquals(other.content)
        override fun hashCode(): Int =
            (31 * name.hashCode() + content.contentHashCode()) * 31 + base.hashCode()
    }
    data class DeleteDone(val name: String) : BleEvent()
    /**
     * The box's current log-mode, from a GET_MODE reply or a confirmed
     * SET_MODE. `manual = false` → auto (logs on boot), `true` → manual
     * (idle until START_LOG).
     */
    data class LogMode(val manual: Boolean) : BleEvent()
    data class Error(val msg: String) : BleEvent()
    /**
     * One decoded SensorStream snapshot (0.5 Hz). Only emitted while
     * connected to PumpLogger firmware that exposes the SensorStream
     * characteristic; legacy PumpTsueri builds never produce this event.
     */
    data class Sample(val sample: LiveSample) : BleEvent()
    /** A firmware upload started; `total` is the image byte length. */
    data class FwUploadStarted(val total: Long) : BleEvent()
    /** Firmware-upload progress — `bytesDone` of `total` staged so far. */
    data class FwUploadProgress(val bytesDone: Long, val total: Long) : BleEvent()
    /**
     * Firmware upload finished. `success == true` means the box accepted the
     * image and is rebooting into it (reconnect in a few seconds); `false`
     * carries a human-readable failure reason in `message`.
     */
    data class FwUploadDone(val success: Boolean, val message: String) : BleEvent()
}
