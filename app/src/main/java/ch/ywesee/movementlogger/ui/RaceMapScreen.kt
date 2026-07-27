package ch.ywesee.movementlogger.ui

import android.content.Context
import android.graphics.Color as AColor
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ch.ywesee.movementlogger.usb.PhoneMotion
import ch.ywesee.movementlogger.usb.RaceRider
import ch.ywesee.movementlogger.usb.RaceUplink
import ch.ywesee.movementlogger.usb.RaceViewer
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.abs

private const val DEFAULT_RELAY_HOST = "ml.ywesee.com"
private const val DEFAULT_RELAY_PORT = 47777

/** Live race map — every rider streaming to the relay, plotted with their track
 *  and live data (speed + board angles + water + height + battery). Android peer
 *  of the iOS `RaceMapScreen`; iOS, Android and desktop riders share it. */
@Composable
fun RaceMapScreen() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { RaceUplink.init(ctx) }

    val ridersMap by RaceViewer.riders.collectAsState()
    val cfg by RaceUplink.state.collectAsState()
    val riders = remember(ridersMap) { ridersMap.values.sortedBy { it.id } }
    var selected by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    val host = cfg.host.ifBlank { DEFAULT_RELAY_HOST }
    androidx.compose.runtime.DisposableEffect(host, cfg.port, cfg.race) {
        RaceViewer.start(host, if (cfg.port > 0) cfg.port else DEFAULT_RELAY_PORT, cfg.race)
        onDispose { RaceViewer.stop() }
    }

    Box(Modifier.fillMaxSize()) {
        RaceMap(riders, now, onSelect = { selected = it })

        FilledIconButton(
            onClick = { showSettings = true },
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        ) {
            Icon(
                if (cfg.enabled && cfg.source == RaceUplink.SOURCE_PHONE) Icons.Filled.LocationOn
                else Icons.Filled.Settings,
                contentDescription = "Race settings",
            )
        }

        val pick = selected ?: riders.singleOrNull()?.id
        val r = pick?.let { ridersMap[it] }
        if (r != null) {
            RiderPanel(
                r, now,
                Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(),
            )
        }
        if (riders.isEmpty()) {
            Text(
                "No riders yet.\nEnable “Track this phone” (settings, top-left), or have riders " +
                    "stream to your relay. Set the relay server in settings.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
    }

    if (showSettings) RaceSettingsDialog(cfg) { showSettings = false }
}

/** osmdroid map with a heading-rotated marker + trail per rider. */
@Composable
private fun RaceMap(riders: List<RaceRider>, now: Long, onSelect: (String) -> Unit) {
    val fitted = remember { booleanArrayOf(false) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            Configuration.getInstance().load(
                ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE),
            )
            Configuration.getInstance().userAgentValue = "MovementLogger-Android"
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                controller.setZoom(15.0)
            }
        },
        update = { map ->
            map.overlays.clear()
            val withFix = riders.filter { it.hasFix }
            for (rider in withFix) {
                val color = riderColor(rider.id)
                val stale = rider.isStale(now)
                if (rider.trail.size > 1) {
                    map.overlays.add(Polyline(map).apply {
                        setPoints(rider.trail.map { GeoPoint(it.first, it.second) })
                        outlinePaint.color = if (stale) AColor.GRAY else color
                        outlinePaint.strokeWidth = 7f
                    })
                }
                map.overlays.add(Marker(map).apply {
                    position = GeoPoint(rider.lat, rider.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = rider.id
                    if (rider.deg.isFinite()) rotation = rider.deg.toFloat()
                    icon = dotDrawable(map.context, if (stale) AColor.GRAY else color)
                    setOnMarkerClickListener { _, _ -> onSelect(rider.id); true }
                })
            }
            map.invalidate()
            if (!fitted[0] && withFix.isNotEmpty()) {
                fitted[0] = true
                val lats = withFix.map { it.lat }; val lons = withFix.map { it.lon }
                map.post {
                    try {
                        map.zoomToBoundingBox(
                            org.osmdroid.util.BoundingBox(
                                lats.max() + 0.002, lons.max() + 0.002,
                                lats.min() - 0.002, lons.min() - 0.002,
                            ), true, 80,
                        )
                    } catch (e: Exception) { /* pre-layout */ }
                }
            }
        },
    )
}

/** Bottom card: everything the selected rider streams. */
@Composable
private fun RiderPanel(r: RaceRider, now: Long, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(10.dp)
                        .background(Color(riderColor(r.id)), CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(r.id, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                val age = ((now - r.lastUpdate) / 1000).toInt()
                Text(
                    if (age <= 1) "live" else "${age}s ago",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (r.isStale(now)) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                metric("SPEED", r.kmh, "km/h", 1)
                if (r.hasBoard) {
                    metric("PITCH", r.pitch, "°", 0)
                    metric("ROLL", r.roll, "°", 0)
                    metric("YAW", r.yaw, "°", 0)
                } else {
                    metric("HDG", r.deg, "°", 0)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                metric("WATER", r.waterTempC ?: Double.NaN, "°C", 1)
                metric("HEIGHT", r.altM, "m", 1)
                metric("ACC", r.accM, "m", 0)
                metric("BATT", r.batt?.toDouble() ?: Double.NaN, "%", 0)
            }
        }
    }
}

@Composable
private fun RowScope.metric(
    label: String, value: Double, unit: String, dp: Int,
) {
    Column(
        Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (value.isFinite()) String.format("%.${dp}f", value) else "—",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(unit, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Race settings — turn this phone into a rider and point at your relay. */
@Composable
private fun RaceSettingsDialog(cfg: RaceUplink.RaceUplinkState, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val motion by PhoneMotion.angles.collectAsState()
    var track by remember { mutableStateOf(cfg.enabled && cfg.source == RaceUplink.SOURCE_PHONE) }
    var board by remember { mutableStateOf(cfg.boardMounted) }
    var name by remember { mutableStateOf(cfg.rider.ifBlank { deviceName(ctx) }) }
    var host by remember { mutableStateOf(cfg.host.ifBlank { DEFAULT_RELAY_HOST }) }
    var port by remember { mutableStateOf((if (cfg.port > 0) cfg.port else DEFAULT_RELAY_PORT).toString()) }
    var token by remember { mutableStateOf(cfg.race) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                RaceUplink.configure(
                    rider = name.trim().ifBlank { deviceName(ctx) },
                    host = host.trim().ifBlank { DEFAULT_RELAY_HOST },
                    port = port.trim().toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_RELAY_PORT,
                    source = RaceUplink.SOURCE_PHONE,
                    race = token.trim(),
                )
                RaceUplink.setBoardMounted(board)
                RaceUplink.setEnabled(track)
                onDismiss()
            }) { Text("Done") }
        },
        title = { Text("Race settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Track this phone", Modifier.weight(1f))
                    Switch(checked = track, onCheckedChange = { track = it })
                }
                Text(
                    "Carry the phone instead of a watch — it streams its GPS to the relay as a rider.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(name, { name = it }, label = { Text("Rider name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Board-mounted (angles + height)", Modifier.weight(1f))
                    Switch(checked = board, onCheckedChange = {
                        board = it
                        RaceUplink.setBoardMounted(it)   // live so the readout below works
                    })
                }
                if (board) {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        angle("PITCH", if (motion.live) motion.pitch else null)
                        angle("ROLL", if (motion.live) motion.roll else null)
                        angle("YAW", if (motion.live) motion.yaw else null)
                    }
                    TextButton(onClick = {
                        if (motion.zeroed) PhoneMotion.clearZero() else PhoneMotion.zero()
                    }) { Text(if (motion.zeroed) "Clear zero" else "Zero here") }
                }
                Text(
                    "Only for a phone strapped to the board. A carried phone leaves this off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("Relay server", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(host, { host = it }, label = { Text("host") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it }, label = { Text("port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                OutlinedTextField(token, { token = it }, label = { Text("race token (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Text(
                    "Run your own race-relay and point everything at it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun RowScope.angle(label: String, deg: Double?) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (deg != null && deg.isFinite()) String.format("%.0f°", deg) else "—",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

private fun deviceName(ctx: Context): String =
    (Settings.Global.getString(ctx.contentResolver, "device_name")
        ?: Settings.Secure.getString(ctx.contentResolver, "bluetooth_name"))
        ?.takeIf { it.isNotBlank() } ?: Build.MODEL

/** Stable per-rider colour from the name hash (ARGB int). */
private fun riderColor(name: String): Int {
    val hues = floatArrayOf(0f, 210f, 120f, 32f, 280f, 180f, 58f, 324f)
    var h = 5381
    for (b in name.toByteArray()) h = (h shl 5) + h + b
    return android.graphics.Color.HSVToColor(floatArrayOf(hues[abs(h) % hues.size], 0.85f, 0.95f))
}

private fun dotDrawable(ctx: Context, color: Int): android.graphics.drawable.Drawable {
    val size = (24 * ctx.resources.displayMetrics.density).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.color = AColor.WHITE
    c.drawCircle(size / 2f, size / 2f, size / 2f - 1, p)
    p.color = color
    // A little arrow so heading is visible; falls back to a dot when deg is unset.
    val path = android.graphics.Path()
    path.moveTo(size / 2f, size * 0.14f)
    path.lineTo(size * 0.78f, size * 0.82f)
    path.lineTo(size / 2f, size * 0.64f)
    path.lineTo(size * 0.22f, size * 0.82f)
    path.close()
    c.drawPath(path, p)
    return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
}
