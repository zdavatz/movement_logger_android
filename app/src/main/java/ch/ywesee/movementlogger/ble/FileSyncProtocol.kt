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

    // Status bytes returned in single-byte FileData notifies.
    const val STATUS_OK: Byte = 0x00
    const val STATUS_BUSY: Byte = 0xB0.toByte()
    const val STATUS_NOT_FOUND: Byte = 0xE1.toByte()
    const val STATUS_IO_ERROR: Byte = 0xE2.toByte()
    const val STATUS_BAD_REQUEST: Byte = 0xE3.toByte()

    fun isStatusByte(b: Byte): Boolean = b == STATUS_BUSY || b == STATUS_NOT_FOUND ||
        b == STATUS_IO_ERROR || b == STATUS_BAD_REQUEST

    fun statusMessage(b: Byte): String = when (b) {
        STATUS_BUSY -> "BUSY (logging in progress, send STOP_LOG first)"
        STATUS_NOT_FOUND -> "NOT_FOUND"
        STATUS_IO_ERROR -> "IO_ERROR"
        STATUS_BAD_REQUEST -> "BAD_REQUEST"
        else -> "unknown error"
    }
}

sealed class BleCmd {
    data object Scan : BleCmd()
    data class Connect(val address: String) : BleCmd()
    data object Disconnect : BleCmd()
    data object List : BleCmd()
    data class Read(val name: String, val size: Long) : BleCmd()
    data object StopLog : BleCmd()
    data class StartLog(val durationSeconds: Int) : BleCmd()
    data class Delete(val name: String) : BleCmd()
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
    data class ReadDone(val name: String, val content: ByteArray) : BleEvent() {
        override fun equals(other: Any?): Boolean =
            other is ReadDone && name == other.name && content.contentEquals(other.content)
        override fun hashCode(): Int = 31 * name.hashCode() + content.contentHashCode()
    }
    data class DeleteDone(val name: String) : BleEvent()
    data class Error(val msg: String) : BleEvent()
    /**
     * One decoded SensorStream snapshot (0.5 Hz). Only emitted while
     * connected to PumpLogger firmware that exposes the SensorStream
     * characteristic; legacy PumpTsueri builds never produce this event.
     */
    data class Sample(val sample: LiveSample) : BleEvent()
}
