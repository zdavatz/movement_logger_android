package ch.ywesee.movementlogger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.ywesee.movementlogger.ble.BatterySample
import ch.ywesee.movementlogger.ble.BoardAngles
import ch.ywesee.movementlogger.ble.Calibration
import ch.ywesee.movementlogger.ble.LiveSample
import ch.ywesee.movementlogger.ble.OriRows
import ch.ywesee.movementlogger.ble.Triad
import ch.ywesee.movementlogger.ble.normDeltaDeg
import ch.ywesee.movementlogger.sync.AgentConfig
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Live tab — renders the most recent SensorStream snapshot at 0.5 Hz.
 *
 * Mirrors the desktop `ui_live_tab` in `stbox-viz-gui/src/main.rs`:
 * status strip, six-row readout grid (Accel / Gyro / Mag / Baro / GPS /
 * GPS aux / Flags), two sparklines (acc magnitude, pressure). Gated on
 * already being connected via the Sync tab — Connect involves scan +
 * device picking that doesn't fit nicely above a 6-row readout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(onGoToSync: () -> Unit) {
    val vm: FileSyncViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        // Outer MainNav Scaffold already insets for the status bar — zero
        // here so the title isn't pushed down by a doubled inset.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("Live") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
    ) { padding ->
        LiveContent(
            state = state,
            onGoToSync = onGoToSync,
            onResetFilter = { vm.resetOrientationFilter() },
            onPushCalibration = { vm.pushCalibration(it) },
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun LiveContent(
    state: FileSyncUiState,
    onGoToSync: () -> Unit,
    onResetFilter: () -> Unit,
    onPushCalibration: (Calibration.EncodeInput) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            "SensorStream — 0.5 Hz packed all-sensor snapshot (IMU + mag + baro + GPS).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.connection != Connection.Connected) {
            Spacer(Modifier.height(24.dp))
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Not connected.", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Open the Sync tab, run Scan, and Connect to a box (PIN 123456). " +
                        "The live stream starts automatically — no extra button needed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onGoToSync) { Text("Go to Sync") }
            }
            return@Column
        }

        Spacer(Modifier.height(12.dp))
        FreshnessStrip(state.live)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        val sample = state.live.latestSample
        if (sample == null) {
            Text(
                "Waiting for first SensorStream notify…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        val ctx = LocalContext.current
        // Refresh calibration values on every new sample — the background
        // auto-calibration (FileSyncCore) updates prefs while we render.
        var magOffset by remember { mutableStateOf(AgentConfig.magOffset(ctx)) }
        var headingBias by remember { mutableStateOf(AgentConfig.headingBiasDeg(ctx)) }
        var nosePlusY by remember { mutableStateOf(AgentConfig.nosePlusY(ctx)) }
        var angleZeroRef by remember { mutableStateOf(AgentConfig.angleZeroRef(ctx)) }
        var angleZeroAtMs by remember { mutableStateOf(AgentConfig.angleZeroAtMs(ctx)) }
        LaunchedEffect(sample) {
            // Also re-read the three CAL_GET-driven fields so a connect-time
            // merge from the box's CAL.CFG shows up in the UI on the next
            // 0.5 Hz sample (FileSyncCore.mergeCalibration writes prefs; this
            // is where LiveScreen picks them up).
            magOffset = AgentConfig.magOffset(ctx)
            headingBias = AgentConfig.headingBiasDeg(ctx)
            nosePlusY = AgentConfig.nosePlusY(ctx)
            angleZeroRef = AgentConfig.angleZeroRef(ctx)
            angleZeroAtMs = AgentConfig.angleZeroAtMs(ctx)
        }

        // Board angles (pitch/roll/yaw about the box's PHYSICAL axes) — the
        // prominent, correct readout. Replaces the old swapped "Angles (°)" row.
        BoardAnglesCard(
            oriRows = state.live.oriRows,
            nosePlusY = nosePlusY,
            headingBias = headingBias,
            angleZeroRef = angleZeroRef,
            angleZeroAtMs = angleZeroAtMs,
            onZero = {
                state.live.oriRows?.let { rows ->
                    val a = BoardAngles.from(rows, nosePlusY ?: false, 0.0)
                    val ref = doubleArrayOf(a.pitchDeg, a.rollDeg, a.yawDeg)
                    val now = System.currentTimeMillis()
                    AgentConfig.setAngleZeroRef(ctx, ref)
                    AgentConfig.setAngleZeroAtMs(ctx, now)
                    angleZeroRef = ref
                    angleZeroAtMs = now
                    // Also push to the box's CAL.CFG so a "Zero here" done on
                    // Android is visible from iPhone/desktop on their next
                    // connect (box is source of truth per DESIGN.md). Only
                    // the angle-zero bit is set, so the box's merge leaves
                    // the other calibration fields alone.
                    onPushCalibration(Calibration.EncodeInput(
                        angleZeroRef = ref,
                        angleZeroAtEpochMs = now,
                    ))
                }
            },
            onClear = {
                AgentConfig.setAngleZeroRef(ctx, null)
                AgentConfig.setAngleZeroAtMs(ctx, null)
                angleZeroRef = null
                angleZeroAtMs = null
                // Push a zeros-with-mask-set to the box — the merge stores
                // all-zero for the tare fields (angleZeroAtEpoch=0 is the
                // "never zeroed" sentinel; the ref itself reads back as
                // [0,0,0]° via decode). Any host reading afterwards sees no
                // tare, which matches the local Clear semantics.
                onPushCalibration(Calibration.EncodeInput(
                    angleZeroRef = doubleArrayOf(0.0, 0.0, 0.0),
                    angleZeroAtEpochMs = 0L,
                ))
            },
        )
        Spacer(Modifier.height(12.dp))

        ReadoutGrid(sample)

        BatterySection(state.live.latestBattery, state.live.latestBatteryAtMs)

        Spacer(Modifier.height(16.dp))
        OrientationSection(
            s = sample,
            oriRows = state.live.oriRows,
            magOffset = magOffset,
            headingBias = headingBias,
            nosePlusY = nosePlusY,
            onSetDirection = { bias ->
                AgentConfig.setHeadingBias(ctx, bias)
                headingBias = bias
                // Push the heading bias so a "USB-C south" set here is
                // visible from iPhone/desktop on their next connect.
                onPushCalibration(Calibration.EncodeInput(
                    headingBiasDeg = bias.toDouble(),
                ))
            },
            onNoseConfirm = { v, newBias ->
                AgentConfig.setNosePlusY(ctx, v)
                nosePlusY = v
                if (newBias != null) {
                    AgentConfig.setHeadingBias(ctx, newBias)
                    headingBias = newBias
                }
                // Combined into a single CAL_SET write — the BLE worker is
                // single-op, so pushing nose + bias in one blob avoids the
                // second write racing the first and being rejected. Bit is
                // only set when the value actually changed (newBias != null
                // ⇔ nose flipped).
                onPushCalibration(Calibration.EncodeInput(
                    nosePlusY = v,
                    headingBiasDeg = newBias?.toDouble(),
                ))
            },
            onReset = {
                AgentConfig.resetMagCalibration(ctx)
                // A tare captured under the old nose end / frame is now
                // meaningless — drop it too, matching desktop semantics.
                AgentConfig.setAngleZeroRef(ctx, null)
                AgentConfig.setAngleZeroAtMs(ctx, null)
                onResetFilter()
                magOffset = null
                headingBias = 0f
                nosePlusY = null
                angleZeroRef = null
                angleZeroAtMs = null
                // Wipe the box's copy too so a fresh calibration on this or
                // any other host starts from a clean slate. All four mask
                // bits set with all-zero payloads — the merge overwrites
                // each field to zero, which decode()s back as the "not
                // calibrated" sentinel. Legacy firmware ignores silently.
                onPushCalibration(Calibration.EncodeInput(
                    nosePlusY = false,
                    magOffsetMg = doubleArrayOf(0.0, 0.0, 0.0),
                    angleZeroRef = doubleArrayOf(0.0, 0.0, 0.0),
                    angleZeroAtEpochMs = 0L,
                    headingBiasDeg = 0.0,
                ))
            },
        )

        Spacer(Modifier.height(16.dp))
        Text("Acc magnitude (g)", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Sparkline(
            points = state.live.accHistory,
            color = Color(0xFF50B4FA),
            yRange = 0.5 to 1.5,
        )

        Spacer(Modifier.height(12.dp))
        Text("Pressure (hPa)", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Sparkline(
            points = state.live.pressureHistory,
            color = Color(0xFFFAB450),
            yRange = 980.0 to 1030.0,
        )
    }
}

/**
 * Top status strip — "N samples received" on the left, freshness label on
 * the right. Recomposes every 250 ms via a wall-clock tick so the
 * "X ms ago" label keeps ticking between samples (which arrive at 0.5 Hz).
 */
@Composable
private fun FreshnessStrip(live: LiveState) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(250)
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${live.sampleCount} samples received", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        val t = live.latestSampleAtMs
        when {
            t == null -> Text(
                "waiting for first SensorStream notify…",
                color = Color(0xFFB58B00),
                style = MaterialTheme.typography.bodySmall,
            )
            nowMs - t < 5_000 -> Text(
                "last sample ${nowMs - t} ms ago",
                color = Color(0xFF2E7D32),
                style = MaterialTheme.typography.bodySmall,
            )
            else -> Text(
                "no sample for ${(nowMs - t) / 1000} s — check connection",
                color = Color(0xFFB58B00),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ReadoutGrid(s: LiveSample) {
    val accG = { i: Int -> s.accMg[i] / 1000f }
    // Firmware v0.0.27+ streams DECI-dps — divide by 10 for °/s (was ÷100).
    val gyroDps = { i: Int -> s.gyroCdps[i] / 10f }
    val presHpa = s.pressurePa / 100.0
    val tempC = s.temperatureCc / 100f

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ReadoutRow("Accel (g)",
                "X %+.3f".format(accG(0)),
                "Y %+.3f".format(accG(1)),
                "Z %+.3f".format(accG(2)),
            )
            ReadoutRow("Gyro (°/s)",
                "X %+.1f".format(gyroDps(0)),
                "Y %+.1f".format(gyroDps(1)),
                "Z %+.1f".format(gyroDps(2)),
            )
            ReadoutRow("Mag (mG)",
                "X %+d".format(s.magMg[0].toInt()),
                "Y %+d".format(s.magMg[1].toInt()),
                "Z %+d".format(s.magMg[2].toInt()),
            )
            // (Pitch/Roll/Yaw now live in the dedicated BoardAnglesCard above —
            // computed about the box's physical axes, not the phone-style accel
            // frame that swapped pitch and roll on this Y-nose box.)
            ReadoutRow("Baro",
                "%.2f hPa".format(presHpa),
                "%+.2f °C".format(tempC),
                "",
            )
            val ll = s.latLonDeg()
            if (ll != null) {
                ReadoutRow("GPS",
                    "%.6f°".format(ll.first),
                    "%.6f°".format(ll.second),
                    "${s.gpsAltM} m",
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabelCell("GPS")
                    Text("no fix", color = Color(0xFFB58B00), modifier = Modifier.weight(3f))
                }
            }
            ReadoutRow("GPS aux",
                "${s.gpsNsat} sats",
                "fix-q ${s.gpsFixQ}",
                "%.2f km/h  (%.1f°)".format(
                    s.gpsSpeedCmh.toDouble() / 100.0,
                    s.gpsCourseCdeg.toDouble() / 100.0,
                ),
            )
            ReadoutRow("GPS C/N0",
                if (s.gpsCn0Max > 0) "${s.gpsCn0Max} dB-Hz max" else "—",
                when {
                    s.gpsCn0Max == 0  -> "no GSV / no data"
                    s.gpsCn0Max >= 40 -> "good antenna"
                    s.gpsCn0Max >= 30 -> "ok"
                    else              -> "weak signal"
                },
                "",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabelCell("Flags")
                Row(Modifier.weight(3f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlagChip("gps_valid", s.gpsValid)
                    FlagChip("logging", s.loggingActive)
                    FlagChip("low_batt", s.lowBattery)
                }
            }
        }
    }
}

@Composable
private fun ReadoutRow(label: String, a: String, b: String, c: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LabelCell(label)
        Text(a, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Text(b, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Text(c, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

@Composable
private fun LabelCell(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
}

@Composable
private fun FlagChip(label: String, on: Boolean) {
    Text(
        label,
        color = if (on) Color(0xFF2E7D32) else Color(0xFF888888),
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = 12.sp,
    )
}

/**
 * Battery meter driven by the box's dedicated …0200… BatteryStatus
 * characteristic (STC3115 fuel gauge — real voltage / SoC% / current), a
 * superset of the low_batt flag in the readout grid above. Hidden on legacy
 * firmware that doesn't expose the characteristic (latestBattery == null).
 * Mirrors the desktop meter in stbox-viz-gui/src/main.rs.
 */
@Composable
private fun BatterySection(b: BatterySample?, atMs: Long?) {
    if (b == null) return
    val pct = b.socPct()
    val fill = when {                       // same ramp as the desktop meter
        pct < 20 -> Color(0xFFC84646)
        pct < 40 -> Color(0xFFD2AA3C)
        else     -> Color(0xFF2E7D32)       // reuse the FlagChip green
    }
    val stale = atMs?.let { System.currentTimeMillis() - it > 90_000 } ?: true  // ~once/min
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Battery", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
        Text(
            "%d%%  ·  %.2f V  ·  %+.2f A%s".format(
                pct, b.volts(), b.amps(), if (stale) "  · stale" else "",
            ),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            fontSize = 13.sp,
        )
    }
    Spacer(Modifier.height(6.dp))
    LinearProgressIndicator(
        progress = { b.socFrac() },
        modifier = Modifier.fillMaxWidth(),
        color = fill,
    )
    if (b.lowBatt) {
        Spacer(Modifier.height(6.dp))
        Text(
            "⚠ low battery (< 10 %) — charge the box; GPS may lose fix",
            color = Color(0xFFD32F2F),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Wrap a degree value into [0, 360). */
private fun normDeg(d: Double): Double {
    var v = d % 360.0
    if (v < 0) v += 360.0
    return v
}

/** "0:SS" / "M:SS" elapsed-time formatting for the "zeroed N ago" note. */
private fun agoText(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    return if (t < 60) "0:%02d".format(t) else "%d:%02d".format(t / 60, t % 60)
}

/**
 * Prominent board-attitude readout for the Live tab — the correct
 * pitch/roll/yaw computed about the box's PHYSICAL axes by [BoardAngles] from
 * the drift-free gyro+accel filter (NOT the old accel-only formulas, which
 * assume a phone-style frame where the long axis is X and so swap pitch and
 * roll on this Y-nose box). Two sets:
 *
 *   • Absolute — vs level & north (yaw is a compass heading, render bias applied)
 *   • Calibrated — deviation from a "Zero here" reference pose
 *
 * "Zero here" tares all three to the current pose (yaw sampled at bias 0 so the
 * tared heading measures turn-since-zero independent of the direction cal); the
 * reference persists across reconnect / app restart via [AgentConfig]. Ported
 * 1:1 from iOS `BoardAnglesCard` (LiveScreen.swift).
 */
@Composable
private fun BoardAnglesCard(
    oriRows: OriRows?,
    nosePlusY: Boolean?,
    headingBias: Float,
    angleZeroRef: DoubleArray?,
    angleZeroAtMs: Long?,
    onZero: () -> Unit,
    onClear: () -> Unit,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { nowMs = System.currentTimeMillis(); delay(1000) }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Board angles", fontWeight = FontWeight.SemiBold)

            if (oriRows == null) {
                Text(
                    "Move the box a little to seed the orientation filter…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val abs = BoardAngles.from(oriRows, nosePlusY ?: false, headingBias.toDouble())
                val rel = BoardAngles.from(oriRows, nosePlusY ?: false, 0.0)

                // --- Absolute ---
                Text(
                    "Absolute — vs level & north",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AngleRow(
                    pitch = "%+5.1f°".format(abs.pitchDeg),
                    roll = "%+5.1f°".format(abs.rollDeg),
                    yaw = "%5.1f°".format(abs.yawDeg),
                )

                HorizontalDivider(Modifier.padding(vertical = 2.dp))

                // --- Calibrated (tared) ---
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Calibrated — vs zero pose",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onZero) { Text("Zero here") }
                }
                if (angleZeroRef != null) {
                    AngleRow(
                        pitch = "%+5.1f°".format(rel.pitchDeg - angleZeroRef[0]),
                        roll = "%+5.1f°".format(rel.rollDeg - angleZeroRef[1]),
                        yaw = "%+5.1f°".format(normDeltaDeg(rel.yawDeg - angleZeroRef[2])),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (angleZeroAtMs != null) {
                            Text(
                                "zeroed ${agoText(nowMs - angleZeroAtMs)} ago",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        OutlinedButton(onClick = onClear) { Text("Clear") }
                    }
                } else {
                    Text(
                        "Tap “Zero here” with the board in its reference pose " +
                            "(e.g. sitting level) to read deviation from it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** One pitch / roll / yaw line with plain-language axis hints underneath. */
@Composable
private fun AngleRow(pitch: String, roll: String, yaw: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AngleCell("Pitch", pitch, "up / down hill", Modifier.weight(1f))
        AngleCell("Roll", roll, "lean L / R", Modifier.weight(1f))
        AngleCell("Yaw", yaw, "heading", Modifier.weight(1f))
    }
}

@Composable
private fun AngleCell(name: String, value: String, hint: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            maxLines = 1,
        )
        Text(hint, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * "Box orientation" section — gyro+accel design, ported 1:1 from iOS
 * (LiveScreen.swift `OrientationSection`). Just TWO calibration taps:
 *  1. "USB-C points SOUTH — set direction" (box flat) — defines the gyro
 *     filter's current yaw as south (a render bias).
 *  2. "USB-C end is UP — confirm" (box upright) — pins which body end
 *     carries the nose arrow; flipping it nudges the bias 180°.
 * plus Reset. The hard-iron offset is learned silently in the background
 * (FileSyncCore.autoCalibrate) and only SEEDS the filter — it no longer
 * touches the render. The preview is pure gyro+accel: no magnetometer.
 */
@Composable
private fun OrientationSection(
    s: LiveSample,
    oriRows: OriRows?,
    magOffset: FloatArray?,
    headingBias: Float,
    nosePlusY: Boolean?,
    onSetDirection: (Float) -> Unit,
    /**
     * `v` = the new nosePlusY. `newBias` = the flipped-by-180° heading bias
     * when the nose end actually changed (nudged so the set direction stays
     * valid); `null` when this tap just confirmed the existing end (no flip
     * → no bias change). The caller writes a SINGLE combined CAL_SET so the
     * BLE worker's single-op guard doesn't reject a second consecutive
     * push.
     */
    onNoseConfirm: (v: Boolean, newBias: Float?) -> Unit,
    onReset: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Box orientation", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text("· gyro-27", color = Color(0xFF2E7D32), fontSize = 12.sp)
    }
    if (magOffset != null) {
        Text(
            "offset [%+.0f %+.0f %+.0f] mG".format(magOffset[0], magOffset[1], magOffset[2]),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(4.dp))

    // THE calibration: hard-iron is learned automatically; the only thing
    // the user must supply is one known direction. Box flat, USB-C south.
    val flat = kotlin.math.abs(s.accMg[2].toInt()) >= 900
    Text(
        "Front of the box = USB-C connector end. Lay the box flat, USB-C " +
            "end pointing SOUTH, then tap:",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = {
            // Define the gyro filter's current yaw as south: read the nose
            // azimuth with no bias, then set the bias so the nose reads 180°.
            val r = oriRows ?: return@Button
            val az = Triad.noseAzimuth(r, nosePlusY ?: false, 0.0)
            onSetDirection(normDeg(az - 180.0).toFloat())
        },
        enabled = flat && oriRows != null,
    ) { Text("USB-C points SOUTH — set direction") }

    val upright = kotlin.math.abs(s.accMg[1].toInt()) >= 900
    Text(
        "Once, for tilt: stand the box upright, USB-C end UP, and tap:",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = {
            // Nose end = the long-axis end currently pointing up.
            val was = nosePlusY ?: false
            val now = s.accMg[1].toInt() > 0
            // Flipping the nose end flips its azimuth by exactly 180° in any
            // pose — nudge the bias to keep the set direction valid. Pass
            // the new bias to the callback so it can compose one atomic
            // CAL_SET (nose + bias) instead of two racing writes.
            val newBias = if (now != was)
                normDeg(headingBias.toDouble() + 180.0).toFloat()
            else null
            onNoseConfirm(now, newBias)
        },
        enabled = upright,
    ) { Text("USB-C end is UP — confirm") }

    // No left/right flip button: the handedness is a fixed hardware fact
    // (mag-Y chirality in Triad.rows), not a user choice. A scene mirror
    // reverses the yaw sense and broke the 360° heading — removed (iOS).
    OutlinedButton(onClick = onReset) { Text("Reset calibration") }

    Spacer(Modifier.height(4.dp))
    Text(
        "Orientation comes from the box's gyroscope + accelerometer — tilt " +
            "is exact and rotation is tracked live (every pose, upside-down " +
            "included). Set which way is south once; it holds and drifts only " +
            "slowly — re-tap if it wanders. Preview is a fixed map, top = SOUTH.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    OrientationBoxCanvas(
        rows = oriRows,
        biasDeg = headingBias.toDouble(),
        nosePlusY = nosePlusY ?: false,
    )
}

/**
 * Wireframe cuboid rendered from the gyro+accel attitude filter — the box's
 * true 3D orientation from gravity (tilt) + gyroscope (rotation), independent
 * of the magnetometer. Ported 1:1 from iOS `OrientationBoxCanvas`. All poses
 * are consistent (upside-down and compound rotations included). Fixed map
 * view, screen-up = SOUTH; N/E/S/W labels (N red); semi-transparent lid;
 * green nose arrow on the user-confirmed front end.
 */
@Composable
private fun OrientationBoxCanvas(
    rows: OriRows?,
    biasDeg: Double,
    nosePlusY: Boolean,
) {
    val textMeasurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.bodyMedium.copy(color = labelColor)
    val northStyle = MaterialTheme.typography.bodyMedium.copy(
        color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold,
    )
    val sideColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lidColor = Color(0xFF50B4FA)
    val noseColor = Color(0xFF43A047)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(200.dp),
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val scale = size.height / 3.2f
        val el = Math.toRadians(28.0)
        val sel = sin(el); val cel = cos(el)

        // Fixed south-up orthographic screen mapping for a world
        // (north, east, down) point.
        fun screen(n: Double, e: Double, d: Double): Offset {
            val sx = cx + (-e) * scale
            val sy = cy - ((-n) * sel - d * cel) * scale
            return Offset(sx.toFloat(), sy.toFloat())
        }

        // Ground compass cross (world frame, fixed).
        drawLine(axisColor, screen(1.35, 0.0, 0.0), screen(-1.35, 0.0, 0.0), 2f)
        drawLine(axisColor, screen(0.0, 1.35, 0.0), screen(0.0, -1.35, 0.0), 2f)
        val nP = screen(1.5, 0.0, 0.0)
        val eP = screen(0.0, 1.5, 0.0)
        val sP = screen(-1.5, 0.0, 0.0)
        val wP = screen(0.0, -1.5, 0.0)
        drawText(textMeasurer, "N", topLeft = Offset(nP.x - 6f, nP.y - 10f), style = northStyle)
        drawText(textMeasurer, "E", topLeft = Offset(eP.x - 6f, eP.y - 10f), style = labelStyle)
        drawText(textMeasurer, "S", topLeft = Offset(sP.x - 6f, sP.y - 10f), style = labelStyle)
        drawText(textMeasurer, "W", topLeft = Offset(wP.x - 6f, wP.y - 10f), style = labelStyle)

        // Attitude from the gyro+accel filter; flat until it seeds.
        val r = rows ?: OriRows(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, -1.0),
        )
        fun p3(p: DoubleArray): Offset {
            val w = Triad.world(p, r, biasDeg)
            return screen(w[0], w[1], w[2])
        }

        // Cuboid in the SENSOR frame: z points UP out of the lid (accel reads
        // +1g on z when flat lid-up), long side = y.
        val hx = 0.62; val hy = 1.0; val hz = 0.28
        val v = arrayOf(
            doubleArrayOf(hx, hy, hz), doubleArrayOf(hx, -hy, hz),
            doubleArrayOf(-hx, -hy, hz), doubleArrayOf(-hx, hy, hz),     // lid
            doubleArrayOf(hx, hy, -hz), doubleArrayOf(hx, -hy, -hz),
            doubleArrayOf(-hx, -hy, -hz), doubleArrayOf(-hx, hy, -hz),   // bottom
        )
        val pts = v.map { p3(it) }
        for ((a, b) in listOf(4 to 5, 5 to 6, 6 to 7, 7 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)) {
            drawLine(sideColor, pts[a], pts[b], 2.5f)
        }
        // Semi-transparent lid fill — a bare wireframe is a Necker cube.
        val lidPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(pts[0].x, pts[0].y)
            lineTo(pts[1].x, pts[1].y)
            lineTo(pts[2].x, pts[2].y)
            lineTo(pts[3].x, pts[3].y)
            close()
        }
        drawPath(lidPath, lidColor.copy(alpha = 0.25f))
        for ((a, b) in listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0)) {
            drawLine(lidColor, pts[a], pts[b], 4f)
        }
        // Nose arrow on the user-confirmed front end, on the lid.
        val ny = if (nosePlusY) hy else -hy
        val lidC = p3(doubleArrayOf(0.0, 0.0, hz))
        val tip = p3(doubleArrayOf(0.0, ny * 1.25, hz))
        drawLine(noseColor, lidC, tip, 4f)
        val ang = atan2(tip.y - lidC.y, tip.x - lidC.x)
        val ah = 14f
        for (side in floatArrayOf(1f, -1f)) {
            val a2 = ang + side * 2.6f
            drawLine(
                noseColor, tip,
                Offset(tip.x + ah * cos(a2), tip.y + ah * sin(a2)), 4f,
            )
        }
    }
}

/**
 * Bare polyline sparkline. Y is clamped to `yRange`; X auto-scales to the
 * full point set so the line drifts left as new samples arrive (matches the
 * desktop `draw_sparkline`).
 */
@Composable
private fun Sparkline(
    points: List<LivePoint>,
    color: Color,
    yRange: Pair<Double, Double>,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        // Subtle baseline so an empty panel doesn't look broken.
        drawRect(
            color = gridColor.copy(alpha = 0.15f),
            size = size,
        )
        if (points.size < 2) return@Canvas
        val tMin = points.first().tSec
        val tMax = points.last().tSec
        val dt = (tMax - tMin).coerceAtLeast(1e-9)
        val (yMin, yMax) = yRange
        val dy = (yMax - yMin).coerceAtLeast(1e-9)
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path()
        points.forEachIndexed { i, p ->
            val x = (((p.tSec - tMin) / dt) * w).toFloat()
            val ny = ((p.value - yMin) / dy).coerceIn(0.0, 1.0)
            val y = (h - ny * h).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
        )
        // Faint axis bounds — top and bottom of the Y range.
        drawLine(
            color = gridColor,
            start = Offset(0f, 0f),
            end = Offset(w, 0f),
            strokeWidth = 1f,
        )
        drawLine(
            color = gridColor,
            start = Offset(0f, h - 1f),
            end = Offset(w, h - 1f),
            strokeWidth = 1f,
        )
    }
}
