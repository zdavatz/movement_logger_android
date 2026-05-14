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

    // -------------------------------------------------------------------------
    //  Command dispatch
    // -------------------------------------------------------------------------

    private suspend fun handleCommand(cmd: BleCmd) {
        when (cmd) {
            BleCmd.Scan -> startScan()
            is BleCmd.Connect -> connect(cmd.address)
            BleCmd.Disconnect -> disconnectInner(emitEvent = true)
            BleCmd.List -> sendList()
            is BleCmd.Read -> sendRead(cmd.name, cmd.size)
            BleCmd.StopLog -> sendStopLog()
            is BleCmd.StartLog -> sendStartLog(cmd.durationSeconds)
            is BleCmd.Delete -> sendDelete(cmd.name)
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
            is CurrentOp.Reading -> emitErr(
                "READ ${o.name} aborted by disconnect at ${o.content.size}/${o.expected} B"
            )
            is CurrentOp.Listing -> emitErr("LIST aborted by disconnect")
            is CurrentOp.Deleting -> emitErr("DELETE ${o.name} aborted by disconnect")
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

    private fun sendRead(name: String, size: Long) {
        if (op !is CurrentOp.Idle) {
            emitErr("another op is in flight — wait or Disconnect"); return
        }
        val payload = ByteArray(1 + name.length)
        payload[0] = FileSyncProtocol.OP_READ
        System.arraycopy(name.toByteArray(Charsets.UTF_8), 0, payload, 1, name.length)
        if (!writeCmdBytes(payload)) return
        op = CurrentOp.Reading(
            name = name,
            expected = size,
            content = ArrayList(size.coerceAtMost(MAX_BUFFER_HINT.toLong()).toInt()),
            lastEmit = 0,
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
        // Mirror the Rust client: write-w/o-response returns once bytes are
        // queued, not when transmitted. If the caller queues a Disconnect
        // right behind us, we'd tear down before the opcode hits the air.
        delay(500)
        emit(BleEvent.Status("START_LOG sent (${durationSeconds}s) — box rebooting to LOG mode"))
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
                        emit(BleEvent.Connected)
                    }
                } else {
                    Log.i(tag, "no SensorStream char — emitting Connected without it")
                    op = CurrentOp.Idle
                    emit(BleEvent.Connected)
                }
            }
            FileSyncProtocol.STREAM_UUID -> {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    emit(BleEvent.Status("SensorStream CCCD write failed ($status) — Live tab will be empty"))
                } else {
                    emit(BleEvent.Status("SensorStream subscribed (live data at 0.5 Hz)"))
                }
                op = CurrentOp.Idle
                emit(BleEvent.Connected)
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
            disconnectInner(emitEvent = true)
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
        val done = state.content.size.toLong()

        // Progress throttling: every ~4 KB or at EOF. BLE FileSync runs
        // ~1-3 KB/s so this updates the bar every 1-4 s.
        if (done - state.lastEmit >= PROGRESS_CHUNK_BYTES || done >= state.expected) {
            state.lastEmit = done
            emit(BleEvent.ReadProgress(state.name, done))
        }

        if (done >= state.expected) {
            val bytes = ByteArray(state.expected.toInt())
            for (i in 0 until state.expected.toInt()) bytes[i] = state.content[i]
            emit(BleEvent.ReadDone(state.name, bytes))
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

    private fun tickWatchdog() {
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
        val stale = when (val o = op) {
            is CurrentOp.Listing -> now - o.lastProgress > OP_IDLE_TIMEOUT_MS
            is CurrentOp.Reading -> now - o.lastProgress > OP_IDLE_TIMEOUT_MS
            is CurrentOp.Deleting -> now - o.lastProgress > OP_IDLE_TIMEOUT_MS
            CurrentOp.Idle -> false
        }
        if (!stale) return
        when (val o = op) {
            is CurrentOp.Listing -> emitErr("LIST timed out — no notifies for 20 s")
            is CurrentOp.Reading -> emitErr(
                "READ ${o.name} timed out at ${o.content.size}/${o.expected} B — no notifies for 20 s"
            )
            is CurrentOp.Deleting -> emitErr("DELETE ${o.name} timed out — no notify for 20 s")
            CurrentOp.Idle -> Unit
        }
        op = CurrentOp.Idle
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
            val content: ArrayList<Byte>,
            var lastEmit: Long,
            var lastProgress: Long,
            var firstPacket: Boolean,
        ) : CurrentOp()
        data class Deleting(val name: String, var lastProgress: Long) : CurrentOp()
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
    }
}
