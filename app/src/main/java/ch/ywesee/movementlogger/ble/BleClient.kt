package ch.ywesee.movementlogger.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.UUID

/**
 * Single-worker BLE client for the PumpTsueri FileSync protocol.
 *
 * Design mirrors the Rust reference client (`stbox-viz-gui/src/ble.rs`):
 * Android BLE callbacks marshal raw events into one channel; a single
 * coroutine `select!`s between that channel and the command channel,
 * holding the per-op state machine (Listing / Reading / Deleting).
 * This keeps all state mutations on one thread without locks.
 *
 * Notifications are subscribed once per connection. Per-op subscription
 * risks losing the first packet if the box notifies before the await
 * is parked — same reasoning as the Rust client.
 */
@SuppressLint("MissingPermission")  // Caller is responsible for ensuring permissions.
class BleClient(private val context: Context) {

    private val tag = "BleClient"

    private val _events = MutableSharedFlow<BleEvent>(extraBufferCapacity = 256)
    val events = _events.asSharedFlow()

    private val cmdChannel = Channel<BleCmd>(Channel.UNLIMITED)
    private val rawChannel = Channel<RawEvent>(Channel.UNLIMITED)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workerJob: Job

    private val btManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val btAdapter: BluetoothAdapter? = btManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var dataChar: BluetoothGattCharacteristic? = null
    private var streamChar: BluetoothGattCharacteristic? = null
    private var op: CurrentOp = CurrentOp.Idle
    private var scanning: Boolean = false
    /** ATT MTU negotiated on connect (updated in [onMtuChanged]). Defaults
     *  to the BLE minimum until the box answers. Used to size FW_DATA
     *  chunks: payload = min(244, mtu-3) - 5 (offset header). */
    private var negotiatedMtu: Int = DEFAULT_ATT_MTU
    /** MAC of the box we last successfully subscribed to — the
     *  reconnect target after an unexpected mid-transfer drop. */
    private var lastConnectedAddress: String? = null
    /** Bounded auto-reconnect state machine (desktop v0.0.11–13).
     *  Driven by the 200 ms watchdog tick so it composes with the
     *  single-worker model without new concurrency. null = inactive. */
    private var reconnect: ReconnectState? = null
    private data class ReconnectState(
        val address: String,
        var attempt: Int,
        var phase: Phase,
        var nextAtMs: Long,
    ) { enum class Phase { WAITING, CONNECTING } }
    private var scanStopJob: Job? = null

    /** 3-chunk reassembly state for the SensorStream MTU-fallback path.
     *  When the negotiated MTU is too small for a 46-byte single notify,
     *  the firmware splits the snapshot across three sequential notifies
     *  with first-byte sequence indices 0x00 / 0x01 / 0x02. */
    private val streamAsm = ArrayList<Byte>(LiveSample.WIRE_SIZE)
    private var streamAsmNext: Int = 0

    init {
        workerJob = scope.launch { workerLoop() }
        // Watchdog ticks are posted into the raw-event channel so they're
        // serialised with everything else — keeps op-state mutation
        // single-threaded without locks.
        scope.launch {
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                rawChannel.trySend(RawEvent.Tick)
            }
        }
    }

    /** Submit a command. Returns immediately; results arrive on `events`. */
    fun send(cmd: BleCmd) {
        cmdChannel.trySend(cmd)
    }

    /** Tear everything down. After calling, no more events will be emitted. */
    fun close() {
        scope.cancel()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) {
            // Caller revoked permissions on the way out — ignore.
        }
        gatt = null
    }

    // -------------------------------------------------------------------------
    //  Worker loop
    // -------------------------------------------------------------------------

    private suspend fun workerLoop() {
        while (true) {
            select<Unit> {
                cmdChannel.onReceive { handleCommand(it) }
                rawChannel.onReceive { handleRaw(it) }
            }
        }
    }

    private fun emit(e: BleEvent) {
        _events.tryEmit(e)
    }

    private fun emitErr(msg: String) {
        emit(BleEvent.Error(msg))
    }

    /** Subscribe confirmed = we're (re)connected. Remember the box for
     *  future reconnects; if a reconnect was running, clear it (success)
     *  — the Connected event drives FileSyncCore's mirror-resume. */
    private fun emitConnected() {
        lastConnectedAddress = gatt?.device?.address
        if (reconnect != null) {
            reconnect = null
            emit(BleEvent.Status("auto-reconnected — resuming transfer"))
        }
        emit(BleEvent.Connected)
    }

    // -------------------------------------------------------------------------
    //  Command dispatch
    // -------------------------------------------------------------------------

    private suspend fun handleCommand(cmd: BleCmd) {
        when (cmd) {
            BleCmd.Scan -> startScan()
            is BleCmd.Connect -> connect(cmd.address)
            BleCmd.Disconnect -> {
                // User-initiated: cancel any auto-reconnect and forget
                // the box so a deliberate disconnect doesn't bounce back.
                reconnect = null
                lastConnectedAddress = null
                disconnectInner(emitEvent = true)
            }
            BleCmd.List -> sendList()
            is BleCmd.Read -> sendRead(cmd.name, cmd.size, cmd.offset)
            BleCmd.StopLog -> sendStopLog()
            is BleCmd.StartLog -> sendStartLog(cmd.durationSeconds)
            is BleCmd.Delete -> sendDelete(cmd.name)
            is BleCmd.SetLogMode -> sendSetMode(cmd.manual)
            BleCmd.GetLogMode -> sendGetMode()
            is BleCmd.SetTime -> sendSetTime(cmd.epochMs)
            is BleCmd.UploadFirmware -> startFirmwareUpload(cmd.image, cmd.sha256)
            BleCmd.AbortFirmware -> abortFirmwareUpload()
        }
    }

    private fun startScan() {
        val adapter = btAdapter ?: run {
            emitErr("no BLE adapter found")
            return
        }
        if (!adapter.isEnabled) {
            emitErr("Bluetooth is off — enable it in system settings")
            return
        }
        if (scanning) {
            emit(BleEvent.Status("scan already running"))
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            emitErr("no BLE scanner available")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            emitErr("scan permission denied: ${e.message}")
            return
        }
        scanning = true
        emit(BleEvent.Status("scanning…"))
        scanStopJob = scope.launch {
            delay(SCAN_DURATION_MS)
            stopScan()
        }
    }

    private fun stopScan() {
        if (!scanning) return
        val scanner = btAdapter?.bluetoothLeScanner
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // Ignore — we're winding down anyway.
        }
        scanning = false
        scanStopJob?.cancel()
        scanStopJob = null
        emit(BleEvent.ScanStopped)
    }

    private fun connect(address: String) {
        if (gatt != null) {
            emitErr("already connected — disconnect first")
            return
        }
        val adapter = btAdapter ?: run {
            emitErr("no BLE adapter")
            return
        }
        if (scanning) stopScan()
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            emitErr("bad device address: $address")
            return
        }
        emit(BleEvent.Status("connecting…"))
        gatt = try {
            device.connectGatt(context, /* autoConnect = */ false, gattCallback)
        } catch (e: SecurityException) {
            emitErr("connect permission denied: ${e.message}")
            null
        }
    }

    private suspend fun disconnectInner(emitEvent: Boolean) {
        when (val o = op) {
            is CurrentOp.Reading -> {
                // Hand back the partial so the resume continues from the
                // true break point (appended to the mirror), then
                // surface the abort (desktop v0.0.9 disconnect_inner).
                emit(BleEvent.ReadAborted(o.name, o.content.toByteArray(), o.base))
                emitErr(
                    "READ ${o.name} aborted by disconnect at ${o.base + o.content.size}/${o.expected} B"
                )
            }
            is CurrentOp.Listing -> emitErr("LIST aborted by disconnect")
            is CurrentOp.Deleting -> emitErr("DELETE ${o.name} aborted by disconnect")
            is CurrentOp.ModeReq -> emitErr(
                "${if (o.isSet) "SET_MODE" else "GET_MODE"} aborted by disconnect"
            )
            is CurrentOp.Uploading -> {
                // A disconnect right after COMMIT is the SUCCESS path: the
                // box swapped banks and reset. Otherwise it's a real failure.
                if (o.phase == CurrentOp.Uploading.Phase.COMMITTING) {
                    emit(BleEvent.FwUploadDone(
                        success = true,
                        message = "sent — box rebooting into new firmware, reconnect in a few seconds",
                    ))
                } else {
                    emit(BleEvent.FwUploadDone(
                        success = false,
                        message = "firmware upload aborted by disconnect at ${o.offset}/${o.image.size} B",
                    ))
                }
            }
            CurrentOp.Idle -> Unit
        }
        op = CurrentOp.Idle
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) {
            // ignore
        }
        gatt = null
        cmdChar = null
        dataChar = null
        streamChar = null
        streamAsm.clear()
        streamAsmNext = 0
        negotiatedMtu = DEFAULT_ATT_MTU
        if (emitEvent) emit(BleEvent.Disconnected)
    }

    private fun writeCmdBytes(payload: ByteArray): Boolean {
        val g = gatt ?: run { emitErr("not connected"); return false }
        val c = cmdChar ?: run { emitErr("FileCmd characteristic missing"); return false }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val rc = g.writeCharacteristic(
                    c, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
                rc == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                c.value = payload
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        } catch (e: SecurityException) {
            emitErr("write permission denied: ${e.message}")
            false
        }
    }

    private fun sendList() {
        if (op !is CurrentOp.Idle) {
            emitErr("another op is in flight — wait or Disconnect"); return
        }
        if (!writeCmdBytes(byteArrayOf(FileSyncProtocol.OP_LIST))) return
        op = CurrentOp.Listing(StringBuilder(), now(), 0)
        emit(BleEvent.Status("LIST sent"))
    }

    private fun sendRead(name: String, size: Long, offset: Long) {
        if (op !is CurrentOp.Idle) {
            emitErr("another op is in flight — wait or Disconnect"); return
        }
        // Opcode payload: 0x02 + name + NUL + 4-byte LE start offset.
        // The firmware seeks to `offset` before streaming, so a resumed
        // or grown file continues mid-file. offset is u32 on the wire —
        // SD files are well under 4 GiB.
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + nameBytes.size + 1 + 4)
        payload[0] = FileSyncProtocol.OP_READ
        System.arraycopy(nameBytes, 0, payload, 1, nameBytes.size)
        payload[1 + nameBytes.size] = 0x00
        val off = offset.toInt()
        val p = 1 + nameBytes.size + 1
        payload[p]     = (off and 0xFF).toByte()
        payload[p + 1] = ((off ushr 8) and 0xFF).toByte()
        payload[p + 2] = ((off ushr 16) and 0xFF).toByte()
        payload[p + 3] = ((off ushr 24) and 0xFF).toByte()
        if (!writeCmdBytes(payload)) return
        val remaining = (size - offset).coerceAtLeast(0)
        op = CurrentOp.Reading(
            name = name,
            expected = size,
            base = offset,
            content = ArrayList(remaining.coerceAtMost(MAX_BUFFER_HINT.toLong()).toInt()),
            lastEmit = offset,
            lastProgress = now(),
            firstPacket = true,
        )
        emit(BleEvent.ReadStarted(name, size))
    }

    private fun sendDelete(name: String) {
        if (op !is CurrentOp.Idle) {
            emitErr("another op is in flight — wait or Disconnect"); return
        }
        val payload = ByteArray(1 + name.length)
        payload[0] = FileSyncProtocol.OP_DELETE
        System.arraycopy(name.toByteArray(Charsets.UTF_8), 0, payload, 1, name.length)
        if (!writeCmdBytes(payload)) return
        op = CurrentOp.Deleting(name, now())
        emit(BleEvent.Status("DELETE $name sent"))
    }

    private fun sendStopLog() {
        if (!writeCmdBytes(byteArrayOf(FileSyncProtocol.OP_STOP_LOG))) return
        emit(BleEvent.Status("STOP_LOG sent"))
    }

    private suspend fun sendStartLog(durationSeconds: Int) {
        val payload = byteArrayOf(
            FileSyncProtocol.OP_START_LOG,
            (durationSeconds         and 0xFF).toByte(),
            (durationSeconds ushr 8  and 0xFF).toByte(),
            (durationSeconds ushr 16 and 0xFF).toByte(),
            (durationSeconds ushr 24 and 0xFF).toByte(),
        )
        if (!writeCmdBytes(payload)) return
        // write-w/o-response returns once bytes are queued, not when
        // transmitted — settle before any follow-up command.
        delay(500)
        // Current firmware does NOT reboot on START_LOG: it just opens a
        // session and auto-stops after the duration. (Legacy PumpTsueri
        // rebooted; we no longer rely on that.)
        emit(BleEvent.Status("START_LOG sent (${durationSeconds}s)"))
    }

    private fun sendSetMode(manual: Boolean) {
        if (op !is CurrentOp.Idle) {
            emitErr("SET_MODE rejected — another op in flight")
            return
        }
        val payload = byteArrayOf(
            FileSyncProtocol.OP_SET_MODE,
            if (manual) 1 else 0,
        )
        if (!writeCmdBytes(payload)) return
        op = CurrentOp.ModeReq(isSet = true, manual = manual, lastProgress = now())
        emit(BleEvent.Status("SET_MODE ${if (manual) "manual" else "auto"} sent"))
    }

    private fun sendGetMode() {
        if (op !is CurrentOp.Idle) {
            emitErr("GET_MODE rejected — another op in flight")
            return
        }
        if (!writeCmdBytes(byteArrayOf(FileSyncProtocol.OP_GET_MODE))) return
        op = CurrentOp.ModeReq(isSet = false, manual = false, lastProgress = now())
    }

    /**
     * SET_TIME `0x08 + epoch_ms(u64-LE)`: hand the box the phone's wall
     * clock so it stamps a `# SYNC` anchor into the open Sens/Gps CSVs.
     * Deliberately *fire-and-forget* — it does NOT occupy a [CurrentOp]
     * slot: the host never needs the reply, and legacy firmware that
     * doesn't implement 0x08 never answers (so tracking it would stall the
     * op for the full 20 s watchdog window). The box's OK byte, if any,
     * lands while `op == Idle` and is harmlessly ignored. Skipped if an op
     * is mid-flight so a stray marker can't interleave with a READ.
     */
    private fun sendSetTime(epochMs: Long) {
        if (op !is CurrentOp.Idle) return
        val payload = ByteArray(1 + 8)
        payload[0] = FileSyncProtocol.OP_SET_TIME
        for (i in 0 until 8) {
            payload[1 + i] = ((epochMs ushr (8 * i)) and 0xFF).toByte()
        }
        if (!writeCmdBytes(payload)) return
        emit(BleEvent.Status("SET_TIME sent — box clock anchored to phone"))
    }

    // -------------------------------------------------------------------------
    //  Raw BLE callback handling
    // -------------------------------------------------------------------------

    private suspend fun handleRaw(raw: RawEvent) {
        when (raw) {
            is RawEvent.Discovered -> emit(
                BleEvent.Discovered(raw.address, raw.name, raw.rssi)
            )
            is RawEvent.ConnectionStateChange -> onConnectionState(raw.status, raw.newState)
            is RawEvent.ServicesDiscovered -> onServicesDiscovered(raw.status)
            is RawEvent.Notification -> onNotification(raw.charUuid, raw.value)
            RawEvent.Tick -> tickWatchdog()
            is RawEvent.DescriptorWritten -> onDescriptorWritten(raw.charUuid, raw.status)
            is RawEvent.MtuChanged -> onMtuChanged(raw.mtu, raw.status)
        }
    }

    /**
     * Two CCCDs to write in sequence (Android only allows one in-flight GATT
     * op at a time): FileData first, then SensorStream (if present). When the
     * FileData CCCD is acked we either chain into SensorStream or emit
     * Connected. SensorStream failures are soft — legacy PumpTsueri firmware
     * doesn't expose it and the user should still get FileSync.
     */
    private fun onDescriptorWritten(charUuid: UUID, status: Int) {
        Log.i(tag, "CCCD ack for $charUuid status=$status")
        when (charUuid) {
            FileSyncProtocol.FILEDATA_UUID -> {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    emitErr("FileData CCCD write failed: $status")
                    scope.launch { disconnectInner(emitEvent = true) }
                    return
                }
                // FileData ready. Chain to SensorStream subscribe if the
                // characteristic exists on this firmware.
                val s = streamChar
                if (s != null) {
                    Log.i(tag, "FileData CCCD done — chaining SensorStream subscribe")
                    if (!subscribeAndWriteCccd(s)) {
                        // Soft-fail: log and proceed with Connected.
                        emit(BleEvent.Status("SensorStream subscribe failed — Live tab will be empty"))
                        op = CurrentOp.Idle
                        emitConnected()
                    }
                } else {
                    Log.i(tag, "no SensorStream char — emitting Connected without it")
                    op = CurrentOp.Idle
                    emitConnected()
                }
            }
            FileSyncProtocol.STREAM_UUID -> {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    emit(BleEvent.Status("SensorStream CCCD write failed ($status) — Live tab will be empty"))
                } else {
                    emit(BleEvent.Status("SensorStream subscribed (live data at 0.5 Hz)"))
                }
                op = CurrentOp.Idle
                emitConnected()
            }
        }
    }

    private suspend fun onConnectionState(status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            emit(BleEvent.Status("connected — discovering services"))
            try {
                gatt?.discoverServices()
            } catch (e: SecurityException) {
                emitErr("discover permission denied: ${e.message}")
                disconnectInner(emitEvent = true)
            }
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitErr("disconnected (status $status)")
            }
            val wasTransfer = op is CurrentOp.Reading
            if (wasTransfer && lastConnectedAddress != null && reconnect == null) {
                // Mid-READ remote drop: keep the partial (disconnectInner
                // emits ReadAborted) and auto-reconnect instead of a hard
                // Disconnected (desktop v0.0.11/12).
                armReconnect()
            } else if (reconnect == null) {
                disconnectInner(emitEvent = true)
            }
            // else: stray callback while already reconnecting — ignore.
        }
    }

    private suspend fun onServicesDiscovered(status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            emitErr("service discovery failed: $status")
            disconnectInner(emitEvent = true); return
        }
        val g = gatt ?: return
        cmdChar = findCharacteristic(g, FileSyncProtocol.FILECMD_UUID)
        dataChar = findCharacteristic(g, FileSyncProtocol.FILEDATA_UUID)
        // SensorStream is optional — only PumpLogger firmware exposes it.
        // Legacy SDDataLogFileX (PumpTsueri name) is FileSync-only.
        streamChar = findCharacteristic(g, FileSyncProtocol.STREAM_UUID)
        // Diagnostic dump — every characteristic on the box, with properties.
        // Helps explain "SensorStream not subscribing" reports from the field.
        for (svc in g.services) {
            for (ch in svc.characteristics) {
                Log.i(tag, "char=${ch.uuid} props=0x${"%02X".format(ch.properties)} svc=${svc.uuid}")
            }
        }
        Log.i(tag, "FileCmd=${cmdChar != null}, FileData=${dataChar != null}, SensorStream=${streamChar != null}")
        if (cmdChar == null || dataChar == null) {
            emitErr("Box firmware doesn't expose FileSync chars — flash a newer build")
            disconnectInner(emitEvent = true); return
        }
        if (streamChar == null) {
            emit(BleEvent.Status(
                "SensorStream characteristic not advertised — legacy firmware, Live tab will be empty"
            ))
        }
        // Request a larger MTU *before* subscribing. SensorStream notifies
        // are 46 bytes; the default ATT MTU 23 truncates them to 20 bytes
        // and the firmware doesn't fall back to the 3-chunk protocol the
        // desktop client expects (verified on PumpLogger PR #18 against a
        // Pixel 8a — every notify came in 20 B with no sequence prefix).
        // CCCD writes are kicked off from onMtuChanged once the negotiated
        // MTU is known. requestMtu→onMtuChanged is the only way to upgrade
        // on Android; the OS won't auto-negotiate.
        val mtuKicked = try {
            g.requestMtu(MTU_REQUEST)
        } catch (e: SecurityException) {
            emitErr("requestMtu permission denied: ${e.message}"); false
        }
        if (!mtuKicked) {
            // Couldn't even queue the request — fall back to subscribing at
            // default MTU. Live tab will be empty but FileSync still works.
            Log.w(tag, "requestMtu($MTU_REQUEST) returned false — subscribing at default MTU")
            if (!subscribeAndWriteCccd(dataChar!!)) {
                disconnectInner(emitEvent = true); return
            }
        }
        // BleEvent.Connected is emitted once both subscriptions are settled,
        // either from onDescriptorWritten(SensorStream) or the no-SensorStream
        // branch in onDescriptorWritten(FileData).
    }

    /** MTU upgrade callback — proceed with CCCD subscription chain. */
    private suspend fun onMtuChanged(mtu: Int, status: Int) {
        Log.i(tag, "onMtuChanged mtu=$mtu status=$status")
        // Remember the negotiated MTU regardless of `status` — some MTU is
        // in effect either way, and FW_DATA chunk sizing keys off it.
        negotiatedMtu = mtu
        // We continue regardless of `status` — even if Android reports
        // failure, *some* MTU is in effect. If it's still 23 we'll get
        // truncated 20-byte notifies (Live tab stays empty), but FileSync
        // is unaffected and disconnecting would be worse UX.
        val d = dataChar ?: run {
            emitErr("internal: dataChar missing after MTU change")
            disconnectInner(emitEvent = true); return
        }
        if (!subscribeAndWriteCccd(d)) {
            disconnectInner(emitEvent = true)
        }
    }

    /**
     * Enable notifications for `ch` and write its CCCD. Returns true if the
     * write was kicked off; the caller must wait for `onDescriptorWritten`
     * for the final result. Returns false on synchronous failure (missing
     * CCCD, permission denied) — caller decides whether to disconnect.
     */
    private fun subscribeAndWriteCccd(ch: BluetoothGattCharacteristic): Boolean {
        val g = gatt ?: return false
        val ok = try {
            g.setCharacteristicNotification(ch, true)
        } catch (e: SecurityException) {
            emitErr("subscribe permission denied: ${e.message}"); false
        }
        if (!ok) {
            emitErr("setCharacteristicNotification failed for ${ch.uuid}")
            return false
        }
        val cccd = ch.getDescriptor(FileSyncProtocol.CCCD_UUID) ?: run {
            emitErr("CCCD descriptor missing on ${ch.uuid}")
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        } catch (e: SecurityException) {
            emitErr("CCCD write permission denied: ${e.message}")
            false
        }
    }

    private fun findCharacteristic(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        for (svc in g.services) {
            svc.getCharacteristic(uuid)?.let { return it }
        }
        return null
    }

    private fun onNotification(charUuid: UUID, value: ByteArray) {
        if (charUuid == FileSyncProtocol.STREAM_UUID) {
            handleStreamNotify(value)
            return
        }
        // FileData path — drive the in-flight op's state machine.
        when (val current = op) {
            CurrentOp.Idle -> Unit  // stray notify between ops — harmless
            is CurrentOp.Listing -> handleListNotify(current, value)
            is CurrentOp.Reading -> handleReadNotify(current, value)
            is CurrentOp.Deleting -> handleDeleteNotify(current, value)
            is CurrentOp.ModeReq -> handleModeNotify(current, value)
            is CurrentOp.Uploading -> handleUploadNotify(current, value)
        }
    }

    /**
     * SensorStream notification handler. Two transport modes per
     * DESIGN.md §3:
     *
     *   - **Single-notify** (negotiated MTU ≥ 50): one 46-byte payload
     *     per snapshot, parsed in one shot.
     *   - **3-chunk fallback** (default MTU ≈ 23): three sequential
     *     ~20-byte notifies, first byte is the sequence index
     *     (0x00 / 0x01 / 0x02). Out-of-order chunks reset the asm
     *     buffer and wait for the next 0x00.
     *
     * Malformed packets drop silently — the stream auto-resyncs on the
     * next 0x00 start, and at 0.5 Hz a lost frame is barely a blip.
     */
    private fun handleStreamNotify(bytes: ByteArray) {
        if (bytes.size == LiveSample.WIRE_SIZE) {
            // Single-notify path. Reset any in-flight chunked frame so a
            // mid-frame MTU upgrade doesn't leave the asm in a bad state.
            streamAsm.clear()
            streamAsmNext = 0
            LiveSample.parse(bytes)?.let { emit(BleEvent.Sample(it)) }
            return
        }
        if (bytes.isEmpty()) return
        val seq = bytes[0].toInt() and 0xFF
        val body = bytes.copyOfRange(1, bytes.size)
        when (seq) {
            0x00 -> {
                streamAsm.clear()
                for (b in body) streamAsm.add(b)
                streamAsmNext = 1
            }
            0x01 -> {
                if (streamAsmNext != 1) {
                    streamAsm.clear(); streamAsmNext = 0
                    return
                }
                for (b in body) streamAsm.add(b)
                streamAsmNext = 2
            }
            0x02 -> {
                if (streamAsmNext != 2) {
                    streamAsm.clear(); streamAsmNext = 0
                    return
                }
                for (b in body) streamAsm.add(b)
                if (streamAsm.size == LiveSample.WIRE_SIZE) {
                    val assembled = ByteArray(LiveSample.WIRE_SIZE) { streamAsm[it] }
                    LiveSample.parse(assembled)?.let { emit(BleEvent.Sample(it)) }
                }
                streamAsm.clear(); streamAsmNext = 0
            }
            else -> {
                streamAsm.clear(); streamAsmNext = 0
            }
        }
    }

    private fun handleListNotify(state: CurrentOp.Listing, value: ByteArray) {
        state.lastProgress = now()
        val sb = state.line
        for (b in value) {
            if (b == '\n'.code.toByte()) {
                if (sb.isEmpty()) {
                    op = CurrentOp.Idle
                    emit(BleEvent.ListDone)
                    return
                }
                parseListRow(sb.toString())?.let { (n, sz) ->
                    emit(BleEvent.ListEntry(n, sz))
                    state.rowsSeen += 1
                }
                sb.clear()
            } else {
                sb.append(Char(b.toInt() and 0xFF))
            }
        }
    }

    private fun handleReadNotify(state: CurrentOp.Reading, value: ByteArray) {
        state.lastProgress = now()

        // Status-byte detection: only the first packet, exactly 1 byte, and that
        // byte must be a recognised error code. Avoids false positives on tiny
        // CSV/log files (which start with ASCII text, well below 0x80).
        if (state.firstPacket && value.size == 1 && FileSyncProtocol.isStatusByte(value[0])) {
            emitErr("READ ${state.name}: ${FileSyncProtocol.statusMessage(value[0])} " +
                "(0x${"%02X".format(value[0].toInt() and 0xFF)})")
            op = CurrentOp.Idle
            return
        }
        state.firstPacket = false

        for (b in value) state.content.add(b)
        // `done` is the absolute byte position in the file: the resume
        // base plus what this segment has received. EOF is the box's
        // full size, not the segment length.
        val done = state.base + state.content.size.toLong()

        // Progress throttling: every ~4 KB or at EOF. BLE FileSync runs
        // ~1-3 KB/s so this updates the bar every 1-4 s.
        if (done - state.lastEmit >= PROGRESS_CHUNK_BYTES || done >= state.expected) {
            state.lastEmit = done
            emit(BleEvent.ReadProgress(state.name, done))
        }

        if (done >= state.expected) {
            val take = (state.expected - state.base).coerceAtLeast(0).toInt()
            val bytes = ByteArray(take)
            for (i in 0 until take) bytes[i] = state.content[i]
            emit(BleEvent.ReadDone(state.name, bytes, state.base))
            op = CurrentOp.Idle
        }
    }

    private fun handleDeleteNotify(state: CurrentOp.Deleting, value: ByteArray) {
        if (value.isEmpty()) return  // shouldn't happen; tolerate
        val s = value[0]
        if (s == FileSyncProtocol.STATUS_OK) {
            emit(BleEvent.DeleteDone(state.name))
        } else {
            emitErr("DELETE ${state.name}: ${FileSyncProtocol.statusMessage(s)} " +
                "(0x${"%02X".format(s.toInt() and 0xFF)})")
        }
        op = CurrentOp.Idle
    }

    private fun handleModeNotify(state: CurrentOp.ModeReq, value: ByteArray) {
        if (value.isEmpty()) return  // shouldn't happen; tolerate
        val b = value[0]
        if (state.isSet) {
            // Reply is a status byte. OK ⇒ the box is now in `manual`.
            if (b == FileSyncProtocol.STATUS_OK) {
                emit(BleEvent.LogMode(state.manual))
                emit(BleEvent.Status(
                    "log mode set to ${if (state.manual) "manual" else "auto"}"
                ))
            } else {
                emitErr("SET_MODE: ${FileSyncProtocol.statusMessage(b)} " +
                    "(0x${"%02X".format(b.toInt() and 0xFF)})")
            }
        } else {
            // GET_MODE reply: 0 = auto, 1 = manual.
            emit(BleEvent.LogMode(manual = b.toInt() != 0))
        }
        op = CurrentOp.Idle
    }

    // -------------------------------------------------------------------------
    //  Firmware OTA upload (FW_BEGIN → FW_DATA… → FW_COMMIT)
    // -------------------------------------------------------------------------

    /**
     * Kick off a firmware upload. Sends FW_BEGIN `[len:u32][sha256:32]` and
     * parks in the BEGINNING phase awaiting the box's 1-byte ready/err. The
     * FW_DATA stream is ACK-gated from [handleUploadNotify]: one chunk in
     * flight at a time, advance on the 4-byte next-offset ACK.
     */
    private fun startFirmwareUpload(image: ByteArray, sha256: ByteArray) {
        if (op !is CurrentOp.Idle) {
            emit(BleEvent.FwUploadDone(false, "another op is in flight — wait or Disconnect"))
            return
        }
        if (image.isEmpty()) {
            emit(BleEvent.FwUploadDone(false, "firmware image is empty"))
            return
        }
        if (sha256.size != 32) {
            emit(BleEvent.FwUploadDone(false, "internal: SHA-256 digest must be 32 bytes"))
            return
        }
        // FW_DATA payload per chunk: min(244, mtu-3) - 5 (the 4-byte offset
        // header + opcode share the ATT write). Clamp to ≥1 so a tiny MTU
        // still makes (slow) progress.
        val maxData = (minOf(244, negotiatedMtu - 3) - 5).coerceAtLeast(1)
        // FW_BEGIN: opcode + image_len(u32-LE) + sha256(32) = 37 bytes.
        val begin = ByteArray(1 + 4 + 32)
        begin[0] = FileSyncProtocol.OP_FW_BEGIN
        val len = image.size
        begin[1] = (len and 0xFF).toByte()
        begin[2] = ((len ushr 8) and 0xFF).toByte()
        begin[3] = ((len ushr 16) and 0xFF).toByte()
        begin[4] = ((len ushr 24) and 0xFF).toByte()
        System.arraycopy(sha256, 0, begin, 5, 32)
        if (!writeCmdBytes(begin)) {
            emit(BleEvent.FwUploadDone(false, "not connected — couldn't send FW_BEGIN"))
            return
        }
        op = CurrentOp.Uploading(
            image = image,
            sha256 = sha256,
            maxData = maxData,
            phase = CurrentOp.Uploading.Phase.BEGINNING,
            offset = 0,
            retries = 0,
            lastEmit = 0,
            lastProgress = now(),
        )
        emit(BleEvent.FwUploadStarted(image.size.toLong()))
        emit(BleEvent.Status("FW_BEGIN sent (${image.size} B, chunk ${maxData} B) — erasing bank…"))
    }

    /**
     * Best-effort FW_ABORT then drop the op locally. Sent regardless of the
     * phase so a user cancel mid-stream tells the box to discard the staged
     * image; we don't wait for its 0x00 reply (the op is gone either way).
     */
    private fun abortFirmwareUpload() {
        if (op !is CurrentOp.Uploading) return
        writeCmdBytes(byteArrayOf(FileSyncProtocol.OP_FW_ABORT))
        op = CurrentOp.Idle
        emit(BleEvent.FwUploadDone(false, "firmware upload cancelled"))
    }

    /**
     * Send the FW_DATA chunk that starts at `state.offset` (does NOT advance
     * the offset — that happens when the ACK lands, so a resend after a
     * timeout re-sends the same chunk; the box is idempotent for offset below
     * its cursor). When the whole image is staged, transitions to COMMIT.
     */
    private fun sendCurrentFwChunk(state: CurrentOp.Uploading) {
        val off = state.offset
        if (off >= state.image.size) {
            // Whole image staged — verify + swap + reset.
            state.phase = CurrentOp.Uploading.Phase.COMMITTING
            state.lastProgress = now()
            if (!writeCmdBytes(byteArrayOf(FileSyncProtocol.OP_FW_COMMIT))) {
                finishUpload(false, "not connected — couldn't send FW_COMMIT")
                return
            }
            emit(BleEvent.Status("FW_COMMIT sent — verifying image…"))
            return
        }
        val n = minOf(state.maxData, state.image.size - off)
        // FW_DATA: opcode + offset(u32-LE) + n image bytes.
        val payload = ByteArray(1 + 4 + n)
        payload[0] = FileSyncProtocol.OP_FW_DATA
        payload[1] = (off and 0xFF).toByte()
        payload[2] = ((off ushr 8) and 0xFF).toByte()
        payload[3] = ((off ushr 16) and 0xFF).toByte()
        payload[4] = ((off ushr 24) and 0xFF).toByte()
        System.arraycopy(state.image, off, payload, 5, n)
        if (!writeCmdBytes(payload)) {
            finishUpload(false, "not connected — couldn't send FW_DATA @$off")
        }
    }

    /**
     * Drive the upload state machine from a FileData notify.
     *  - BEGINNING: 1 byte. 0x00 → start streaming FW_DATA; else error.
     *  - SENDING: 4-byte LE next-offset ACK → advance + send next; a 1-byte
     *    reply is an error (0xE7 bad-seq / 0xE5 flash-fail).
     *  - COMMITTING: 0xA0 → success (box reboots); else map the error byte.
     */
    private fun handleUploadNotify(state: CurrentOp.Uploading, value: ByteArray) {
        state.lastProgress = now()
        state.retries = 0  // any reply clears the resend counter
        when (state.phase) {
            CurrentOp.Uploading.Phase.BEGINNING -> {
                if (value.isEmpty()) return  // tolerate
                if (value[0] == FileSyncProtocol.STATUS_OK) {
                    state.phase = CurrentOp.Uploading.Phase.SENDING
                    state.offset = 0
                    emit(BleEvent.Status("bank erased — streaming image"))
                    sendCurrentFwChunk(state)
                } else {
                    finishUpload(false, "FW_BEGIN: ${FileSyncProtocol.fwErrorMessage(value[0])}")
                }
            }
            CurrentOp.Uploading.Phase.SENDING -> {
                if (value.size == 4) {
                    // 4-byte LE next-expected-offset ACK.
                    val ack = (value[0].toInt() and 0xFF) or
                        ((value[1].toInt() and 0xFF) shl 8) or
                        ((value[2].toInt() and 0xFF) shl 16) or
                        ((value[3].toInt() and 0xFF) shl 24)
                    state.offset = ack
                    val done = ack.toLong().coerceAtMost(state.image.size.toLong())
                    if (done - state.lastEmit >= PROGRESS_CHUNK_BYTES || ack >= state.image.size) {
                        state.lastEmit = done
                        emit(BleEvent.FwUploadProgress(done, state.image.size.toLong()))
                    }
                    sendCurrentFwChunk(state)
                } else if (value.isNotEmpty()) {
                    // 1-byte error (0xE7 bad-seq / 0xE5 flash-fail).
                    finishUpload(false, "FW_DATA @${state.offset}: ${FileSyncProtocol.fwErrorMessage(value[0])}")
                }
            }
            CurrentOp.Uploading.Phase.COMMITTING -> {
                if (value.isEmpty()) return
                if (value[0] == FileSyncProtocol.FW_READY) {
                    finishUpload(true,
                        "sent — box rebooting into new firmware, reconnect in a few seconds")
                } else {
                    finishUpload(false, "FW_COMMIT: ${FileSyncProtocol.fwErrorMessage(value[0])}")
                }
            }
        }
    }

    /** Terminate the upload op and emit the final result. */
    private fun finishUpload(success: Boolean, message: String) {
        op = CurrentOp.Idle
        emit(BleEvent.FwUploadDone(success, message))
    }

    private fun parseListRow(line: String): Pair<String, Long>? {
        val comma = line.lastIndexOf(',')
        if (comma < 0) return null
        val name = line.substring(0, comma)
        val size = line.substring(comma + 1).toLongOrNull() ?: return null
        return name to size
    }

    // -------------------------------------------------------------------------
    //  Watchdog
    // -------------------------------------------------------------------------

    /**
     * Begin a bounded auto-reconnect after an unexpected mid-transfer
     * drop/stall (desktop `auto_reconnect`). Tears the half-dead link
     * down *without* a public Disconnected (we intend to come back),
     * then lets [tickReconnect] drive connect rounds. No-op if we never
     * had a connected box or a reconnect is already running. Android
     * connects by MAC directly — no rescan phase needed (vs iOS).
     */
    private suspend fun armReconnect() {
        val addr = lastConnectedAddress ?: return
        if (reconnect != null) return
        disconnectInner(emitEvent = false)
        reconnect = ReconnectState(addr, 1, ReconnectState.Phase.WAITING,
            now() + RECONNECT_WAIT_MS)
        val msg = if (keepSyncedActive())
            "link lost — auto-reconnecting (attempt 1, keep-synced)…"
        else
            "link lost — auto-reconnecting (attempt 1/$RECONNECT_ATTEMPTS)…"
        emit(BleEvent.Status(msg))
    }

    /** One step of the reconnect state machine, from the 200 ms tick.
     *  Success is detected elsewhere (subscribe-confirmed clears it). */
    private fun tickReconnect() {
        val rc = reconnect ?: return
        val n = now()
        if (n < rc.nextAtMs) return
        when (rc.phase) {
            ReconnectState.Phase.WAITING -> {
                val adapter = btAdapter
                if (adapter == null) { failReconnectAttempt(rc, n); return }
                try {
                    val dev = adapter.getRemoteDevice(rc.address)
                    gatt = dev.connectGatt(context, /* autoConnect = */ false, gattCallback)
                    rc.phase = ReconnectState.Phase.CONNECTING
                    rc.nextAtMs = n + RECONNECT_CONNECT_MS
                } catch (e: Exception) {
                    failReconnectAttempt(rc, n)
                }
            }
            ReconnectState.Phase.CONNECTING -> {
                // Subscribe-confirmed would have cleared `reconnect`; the
                // attempt timed out. Drop and retry.
                try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
                gatt = null
                failReconnectAttempt(rc, n)
            }
        }
    }

    private fun failReconnectAttempt(rc: ReconnectState, n: Long) {
        rc.attempt++
        // Bounded only when Keep-synced is OFF. With Keep-synced on the
        // user has opted into "mirror whenever possible" so we never
        // surrender — same regime as desktop's Auto Mode (v0.0.19). The
        // bounded budget still applies to a one-shot manual sync.
        val keepSynced = keepSyncedActive()
        if (!keepSynced && rc.attempt > RECONNECT_ATTEMPTS) {
            reconnect = null
            emit(BleEvent.Status("auto-reconnect exhausted — reconnect manually"))
            emit(BleEvent.Disconnected)
            return
        }
        rc.phase = ReconnectState.Phase.WAITING
        rc.nextAtMs = n + RECONNECT_WAIT_MS
        if (keepSynced) {
            emit(BleEvent.Status("auto-reconnecting (attempt ${rc.attempt}, keep-synced)…"))
        } else {
            emit(BleEvent.Status("auto-reconnecting (attempt ${rc.attempt}/$RECONNECT_ATTEMPTS)…"))
        }
    }

    /**
     * `true` when the user has opted into Keep-synced **and** the box
     * isn't in MANUAL log mode. Mirrors iOS `keepSyncedActive`. Read
     * from [failReconnectAttempt] so a mid-loop toggle of the switch is
     * honoured on the next failed attempt.
     */
    private fun keepSyncedActive(): Boolean {
        val cfg = ch.ywesee.movementlogger.sync.AgentConfig.load(context)
        return cfg.keepSynced && cfg.logModeManual != true
    }

    private suspend fun tickWatchdog() {
        tickReconnect()
        if (reconnect != null) return
        val now = now()
        // LIST inactivity-done fallback: ≥1 row received and no new bytes for
        // LIST_INACTIVITY_DONE → assume we missed the terminator and finish.
        (op as? CurrentOp.Listing)?.let { st ->
            if (st.rowsSeen > 0 && now - st.lastProgress > LIST_INACTIVITY_DONE_MS) {
                op = CurrentOp.Idle
                emit(BleEvent.ListDone)
                return
            }
        }
        // Firmware upload has its own timeout policy: a missed FW_DATA ACK
        // resends the SAME chunk (the box is idempotent for offset < its
        // cursor), retrying a few times before giving up — not the
        // hand-back-partial-and-reconnect dance a READ does. COMMIT gets a
        // longer grace because the box's verify+swap can take several
        // seconds (and the link may simply drop = success).
        (op as? CurrentOp.Uploading)?.let { st ->
            tickUploadWatchdog(st, now)
            return
        }
        val stale = when (val o = op) {
            is CurrentOp.Listing -> now - o.lastProgress > OP_IDLE_TIMEOUT_MS
            is CurrentOp.Reading -> now - o.lastProgress > OP_IDLE_TIMEOUT_MS
            is CurrentOp.Deleting -> now - o.lastProgress > OP_IDLE_TIMEOUT_MS
            is CurrentOp.ModeReq -> now - o.lastProgress > OP_IDLE_TIMEOUT_MS
            // Uploading is handled by tickUploadWatchdog above (early return),
            // so it never reaches here — branch present only for exhaustiveness.
            is CurrentOp.Uploading -> false
            CurrentOp.Idle -> false
        }
        if (!stale) return
        var stalledRead = false
        when (val o = op) {
            is CurrentOp.Listing -> emitErr("LIST timed out — no notifies for 20 s")
            is CurrentOp.Reading -> {
                // Stalled (GATT still thinks it's connected). Hand back
                // the partial so the resume continues from here, not the
                // last completed segment (desktop v0.0.12 tick_watchdog).
                emit(BleEvent.ReadAborted(o.name, o.content.toByteArray(), o.base))
                emitErr(
                    "READ ${o.name} timed out at ${o.base + o.content.size}/${o.expected} B — no notifies for 20 s"
                )
                stalledRead = true
            }
            is CurrentOp.Deleting -> emitErr("DELETE ${o.name} timed out — no notify for 20 s")
            is CurrentOp.ModeReq -> emitErr(
                "${if (o.isSet) "SET_MODE" else "GET_MODE"} timed out — no reply for 20 s"
            )
            is CurrentOp.Uploading -> Unit  // handled in tickUploadWatchdog; unreachable here
            CurrentOp.Idle -> Unit
        }
        op = CurrentOp.Idle
        // Stalled READ with the box still nominally connected (no formal
        // disconnect — the case Peter hit). Tear the half-dead link down
        // and auto-reconnect so the mirror resume can continue (desktop
        // v0.0.12). Partial already handed back above.
        if (stalledRead && lastConnectedAddress != null && reconnect == null) {
            armReconnect()
        }
    }

    /**
     * Per-tick timeout policy for the firmware-upload op.
     *  - SENDING: a missed ACK for [FW_CHUNK_TIMEOUT_MS] resends the SAME
     *    chunk (idempotent) up to [FW_MAX_RETRIES] times, then fails.
     *  - BEGINNING: bank erase can take ~1 s; allow [FW_BEGIN_TIMEOUT_MS].
     *  - COMMITTING: verify+swap can take several seconds and the link may
     *    just drop (= success, handled in disconnectInner); allow
     *    [FW_COMMIT_TIMEOUT_MS] before declaring it stuck.
     */
    private fun tickUploadWatchdog(st: CurrentOp.Uploading, now: Long) {
        val elapsed = now - st.lastProgress
        when (st.phase) {
            CurrentOp.Uploading.Phase.BEGINNING -> {
                if (elapsed > FW_BEGIN_TIMEOUT_MS) {
                    finishUpload(false, "FW_BEGIN timed out — no reply (bank erase failed?)")
                }
            }
            CurrentOp.Uploading.Phase.SENDING -> {
                if (elapsed <= FW_CHUNK_TIMEOUT_MS) return
                if (st.retries >= FW_MAX_RETRIES) {
                    finishUpload(false,
                        "FW_DATA @${st.offset} timed out — no ACK after ${FW_MAX_RETRIES} retries")
                    return
                }
                st.retries++
                st.lastProgress = now
                emit(BleEvent.Status(
                    "FW_DATA @${st.offset} no ACK — resending (retry ${st.retries}/${FW_MAX_RETRIES})"))
                sendCurrentFwChunk(st)
            }
            CurrentOp.Uploading.Phase.COMMITTING -> {
                if (elapsed > FW_COMMIT_TIMEOUT_MS) {
                    // No 0xA0 and no disconnect within the grace window —
                    // treat as failure (the disconnect-as-success path is
                    // handled in disconnectInner, not here).
                    finishUpload(false, "FW_COMMIT timed out — no FW_READY and link stayed up")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    //  Android BLE callbacks → rawChannel
    // -------------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (FileSyncProtocol.BOX_NAMES.none { it == name }) return
            rawChannel.trySend(
                RawEvent.Discovered(result.device.address, name, result.rssi)
            )
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(tag, "scan failed: $errorCode")
            rawChannel.trySend(RawEvent.Discovered("", "<scan failed: $errorCode>", 0))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            rawChannel.trySend(RawEvent.ConnectionStateChange(status, newState))
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            rawChannel.trySend(RawEvent.ServicesDiscovered(status))
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            rawChannel.trySend(RawEvent.MtuChanged(mtu, status))
        }
        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int,
        ) {
            if (descriptor.uuid == FileSyncProtocol.CCCD_UUID) {
                // Two characteristics share the standard CCCD UUID — pass
                // the parent characteristic UUID so the worker can route
                // the write ack to the right state-machine slot.
                rawChannel.trySend(
                    RawEvent.DescriptorWritten(descriptor.characteristic.uuid, status)
                )
            }
        }
        // Android 13+ delivers the value here directly; older versions fall through
        // to the deprecated overload below.
        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            rawChannel.trySend(
                RawEvent.Notification(characteristic.uuid, value.copyOf())
            )
        }
        @Deprecated("Pre-Tiramisu callback path", level = DeprecationLevel.HIDDEN)
        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            val v = characteristic.value ?: return
            rawChannel.trySend(
                RawEvent.Notification(characteristic.uuid, v.copyOf())
            )
        }
    }

    // -------------------------------------------------------------------------
    //  Internal types
    // -------------------------------------------------------------------------

    private sealed class CurrentOp {
        data object Idle : CurrentOp()
        data class Listing(
            val line: StringBuilder,
            var lastProgress: Long,
            var rowsSeen: Int,
        ) : CurrentOp()
        data class Reading(
            val name: String,
            val expected: Long,
            val base: Long,
            val content: ArrayList<Byte>,
            var lastEmit: Long,
            var lastProgress: Long,
            var firstPacket: Boolean,
        ) : CurrentOp()
        data class Deleting(val name: String, var lastProgress: Long) : CurrentOp()
        /** SET_MODE / GET_MODE: both get one FileData reply byte.
         *  `isSet` true = SET (reply is a status byte; on OK the box is
         *  now in `manual`), false = GET (reply is 0/1 = the mode). */
        data class ModeReq(
            val isSet: Boolean,
            val manual: Boolean,
            var lastProgress: Long,
        ) : CurrentOp()
        /**
         * Firmware OTA upload (FW_BEGIN → FW_DATA… → FW_COMMIT). ACK-gated:
         * exactly one FW_* write is in flight at a time and we advance only
         * when its reply lands.
         *  - BEGINNING: sent FW_BEGIN, awaiting the 1-byte ready/err.
         *  - SENDING: streaming FW_DATA chunks; `offset` = next byte to send;
         *    each chunk awaits the 4-byte ACK before the next.
         *  - COMMITTING: sent FW_COMMIT, awaiting 0xA0 / error / disconnect.
         * `maxData` is the per-chunk payload byte budget, sized from the MTU
         * at BEGIN. `retries` counts watchdog resends of the in-flight chunk
         * (box is idempotent for offset < its cursor). `lastProgress`/`lastEmit`
         * feed the watchdog + progress throttle, mirroring Reading.
         */
        data class Uploading(
            val image: ByteArray,
            val sha256: ByteArray,
            val maxData: Int,
            var phase: Phase,
            var offset: Int,
            var retries: Int,
            var lastEmit: Long,
            var lastProgress: Long,
        ) : CurrentOp() {
            enum class Phase { BEGINNING, SENDING, COMMITTING }
        }
    }

    private sealed class RawEvent {
        data class Discovered(val address: String, val name: String, val rssi: Int) : RawEvent()
        data class ConnectionStateChange(val status: Int, val newState: Int) : RawEvent()
        data class ServicesDiscovered(val status: Int) : RawEvent()
        /** `charUuid` distinguishes FileData (FileSync) from SensorStream (Live tab). */
        data class Notification(val charUuid: UUID, val value: ByteArray) : RawEvent()
        /** `charUuid` is the parent characteristic — descriptors all share the standard CCCD UUID. */
        data class DescriptorWritten(val charUuid: UUID, val status: Int) : RawEvent()
        data class MtuChanged(val mtu: Int, val status: Int) : RawEvent()
        data object Tick : RawEvent()
    }

    private fun now(): Long = System.nanoTime() / 1_000_000

    companion object {
        private const val SCAN_DURATION_MS = 5_000L
        private const val WATCHDOG_TICK_MS = 200L
        private const val OP_IDLE_TIMEOUT_MS = 20_000L
        private const val LIST_INACTIVITY_DONE_MS = 500L
        // Auto-reconnect tunables.
        //
        // Two regimes (mirrors desktop v0.0.19 / iOS): **bounded** when
        // Keep-synced is OFF — manual sync, give up after
        // [RECONNECT_ATTEMPTS] so we don't ratchet forever. **Unbounded**
        // when Keep-synced is ON — the user explicitly opted into
        // "mirror whenever possible" and the box's firmware self-heals
        // across 20+ recovery cycles. Decided in [failReconnectAttempt]
        // via [keepSyncedActive].
        //
        // [RECONNECT_CONNECT_MS] is 60 s on purpose: Android holds the
        // pending `connectGatt(autoConnect=false)` even while the worker
        // coroutine is descheduled (doze, lock screen) and only invokes
        // `onConnectionStateChange` when the peripheral re-advertises.
        // The previous 10 s budget false-timed-out every wake — the
        // worker's watchdog `delay()` freezes while suspended but
        // `SystemClock.uptimeMillis()` keeps ticking, so by resume the
        // "deadline" had elapsed and we cancelled a perfectly good
        // pending connect.
        private const val RECONNECT_ATTEMPTS = 30
        private const val RECONNECT_WAIT_MS = 2_000L
        private const val RECONNECT_CONNECT_MS = 60_000L
        private const val PROGRESS_CHUNK_BYTES = 4L * 1024
        private const val MAX_BUFFER_HINT = 16 * 1024 * 1024
        /**
         * ATT MTU we ask for on connect. Max in the spec is 517; 247 is the
         * BLE 4.2 Data-Length-Extension sweet spot and what most modern
         * peripherals advertise. Box is happy to negotiate down if it
         * can't go that high, so requesting more than the box supports
         * just means we get whatever it advertises.
         */
        private const val MTU_REQUEST = 247
        /** ATT MTU before negotiation completes (BLE spec minimum). */
        private const val DEFAULT_ATT_MTU = 23
        // Firmware-upload timeouts. Chunk ACKs come fast (~ms) over BLE, so a
        // 4 s gap means a lost packet → resend. Bank erase (~1 s) and the
        // verify+swap commit get longer grace windows.
        private const val FW_CHUNK_TIMEOUT_MS = 4_000L
        private const val FW_BEGIN_TIMEOUT_MS = 8_000L
        private const val FW_COMMIT_TIMEOUT_MS = 10_000L
        private const val FW_MAX_RETRIES = 5
    }
}
