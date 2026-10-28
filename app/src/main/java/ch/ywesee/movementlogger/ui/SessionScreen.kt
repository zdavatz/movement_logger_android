package ch.ywesee.movementlogger.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.ywesee.movementlogger.data.RideMapRenderer
import ch.ywesee.movementlogger.data.SessionData
import ch.ywesee.movementlogger.data.SessionLoader
import ch.ywesee.movementlogger.data.hms
import kotlinx.coroutines.isActive
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Session screen — one recorded session from the box's `SENSnnn.CSV` +
 * `GPSnnn.CSV`, no video needed. Port of the desktop Session tab
 * (`session_view.rs`): the GPS track on a map coloured by speed, stacked
 * time-series charts on one shared time axis (GPS speed, |acc|, pitch /
 * roll, baro height, optional rotation rate), a time slider with
 * playback, and a readout of every value at the cursor. Tapping the map
 * near the track or tapping a chart moves the cursor. Pure numerics live
 * in [SessionLoader] / [SessionData].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(vm: SessionViewModel = viewModel()) {
    val d = vm.data
    Scaffold(
        // Outer MainNav Scaffold already insets for the status bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("Session") },
                actions = { if (d != null) TextButton(onClick = vm::close) { Text("Files") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (d == null) SessionPicker(vm) else SessionView(vm, d)
        }
    }
}

// ---- file picker -----------------------------------------------------------

@Composable
private fun SessionPicker(vm: SessionViewModel) {
    val recordings = remember(vm.sensorFile, vm.gpsFile, vm.loading) { vm.listRecordings() }
    val sens = recordings.filter { isSensCsv(it.name) }
    val gps = recordings.filter { isGpsCsv(it.name) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text(
            "Show one recorded session — GPS track on a map plus speed, acceleration, " +
                "pitch / roll and height charts — straight from the box's SENS + GPS files. No video needed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        val p = vm.progress
        if (p != null) {
            Text("Loading ${p.file} — %.1f / %.1f MB".format(p.done / 1e6, p.total / 1e6))
            Spacer(Modifier.height(6.dp))
            if (p.total > 0 && p.done < p.total) {
                LinearProgressIndicator(
                    progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // Bytes are in; the remaining second or two is the math.
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
        } else {
            Button(onClick = vm::load, enabled = vm.sensorFile != null) {
                Text(if (vm.sensorFile == null) "Pick a sensor file below" else "Show session")
            }
            Spacer(Modifier.height(12.dp))
        }
        vm.error?.let { msg ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                    OutlinedButton(onClick = vm::clearError) { Text("Dismiss") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (recordings.isEmpty()) {
            Text(
                "No CSVs in this app's storage yet. Use the Sync tab to download a SENS + GPS pair first.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Text("Sensor CSV", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        FileChooserList(sens, vm.sensorFile, "no SENS*.CSV downloaded", vm::pickSensor, "Select", "Selected")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GPS CSV", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (vm.gpsFile != null) TextButton(onClick = { vm.pickGps(null) }) { Text("No GPS") }
        }
        Text(
            "Picked automatically from the sensor file's number (SENS003 → GPS003).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        FileChooserList(gps, vm.gpsFile, "no GPS*.CSV downloaded", vm::pickGps, "Select", "Selected")
    }
}

// ---- loaded session --------------------------------------------------------

@Composable
private fun SessionView(vm: SessionViewModel, d: SessionData) {
    PlaybackLoop(vm, d)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            // Landscape / tablet: map left, everything else right (desktop layout).
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxSize()) { MapPane(vm, d, Modifier.weight(1f)) }
                Column(Modifier.weight(1f).fillMaxSize()) {
                    Summary(d)
                    Controls(vm, d)
                    Charts(vm, d, Modifier.weight(1f))
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Summary(d)
                MapPane(vm, d, Modifier.weight(0.42f))
                Controls(vm, d)
                Charts(vm, d, Modifier.weight(0.58f))
            }
        }
    }
}

/** Advances the cursor in real time × the play speed while playing. */
@Composable
private fun PlaybackLoop(vm: SessionViewModel, d: SessionData) {
    LaunchedEffect(vm.playing, d) {
        if (!vm.playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive && vm.playing) {
            withFrameNanos { now ->
                vm.cursorS += (now - last) / 1e9 * SessionViewModel.PLAY_SPEEDS[vm.speedIdx]
                last = now
            }
            if (vm.cursorS >= d.durationS) {
                vm.cursorS = d.durationS
                vm.playing = false
            }
            vm.keepCursorVisible()
        }
    }
}

@Composable
private fun Summary(d: SessionData) {
    val dur = d.durationS
    val parts = buildList {
        add(d.sensName + (d.gpsName?.let { " + $it" } ?: ""))
        val s = d.label(0.0, false)
        val e = d.label(dur, false)
        if (s != null && e != null) add("$s – $e")
        add("%d min %02d s".format((dur / 60).toInt(), (dur % 60).toInt()))
        if (d.track.isNotEmpty()) {
            add("%.2f km".format(d.distanceM / 1000))
            add("max %.1f km/h".format(d.maxSpeedKmh))
            if (d.gpsRateHz > 0) add("GPS %.0f Hz".format(d.gpsRateHz))
        } else if (d.gpsName != null) {
            add("no GPS fix in this file")
        }
    }
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Controls(vm: SessionViewModel, d: SessionData) {
    val dur = d.durationS.coerceAtLeast(0.1)
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (!vm.playing && vm.cursorS >= d.durationS) vm.cursorS = 0.0
                vm.playing = !vm.playing
            }) {
                Icon(
                    if (vm.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (vm.playing) "Pause" else "Play",
                )
            }
            // Tap cycles 1× → 5× → 20× → 60× → 300×.
            TextButton(onClick = { vm.speedIdx = (vm.speedIdx + 1) % SessionViewModel.PLAY_SPEEDS.size }) {
                Text("%.0f×".format(SessionViewModel.PLAY_SPEEDS[vm.speedIdx]), fontFamily = FontFamily.Monospace)
            }
            Slider(
                value = vm.cursorS.toFloat().coerceIn(0f, dur.toFloat()),
                onValueChange = { vm.cursorS = it.toDouble(); vm.playing = false },
                valueRange = 0f..dur.toFloat(),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            d.readout(vm.cursorS),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 3,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

// ---- map -------------------------------------------------------------------

/** Esri World Imagery (z/y/x URL order, unlike OSM's z/x/y) — the desktop's
 *  satellite layer. */
private val ESRI_IMAGERY: ITileSource = object : OnlineTileSourceBase(
    "EsriWorldImagery", 0, 19, 256, ".jpg",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "© Esri, Maxar, Earthstar Geographics",
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
}

/** Per-map bookkeeping the AndroidView `update` pass compares against. */
private class SessionMapHolder {
    var cursor: Marker? = null
    var satellite = false
    var fitDone = -1
}

@Composable
private fun MapPane(vm: SessionViewModel, d: SessionData, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(selected = vm.satellite, onClick = { vm.satellite = !vm.satellite }, label = { Text("Satellite") })
            FilterChip(selected = vm.follow, onClick = { vm.follow = !vm.follow }, label = { Text("Follow") })
            TextButton(onClick = { vm.fitRequest++ }) { Text("Fit track") }
        }
        if (d.track.size < 2) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("No GPS track to draw.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        Box(Modifier.fillMaxSize()) {
            SessionMap(vm, d)
            Text(
                if (vm.satellite) "© Esri, Maxar, Earthstar Geographics" else "© OpenStreetMap contributors",
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .background(Color.White.copy(alpha = 0.7f))
                    .padding(horizontal = 4.dp),
                color = Color.Black,
            )
        }
    }
}

@Composable
private fun SessionMap(vm: SessionViewModel, d: SessionData) {
    val holder = remember(d) { SessionMapHolder() }
    AndroidView(
        // clipToBounds: osmdroid paints outside its layout bounds while panning.
        modifier = Modifier.fillMaxSize().clipToBounds(),
        factory = { ctx ->
            Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            Configuration.getInstance().userAgentValue = "MovementLogger-Android (${RideMapRenderer.SOURCE_URL})"
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                overlays.add(SpeedTrackOverlay(d, ctx.resources.displayMetrics.density))
                overlays.add(dotMarker(this, GeoPoint(d.lat[d.track.first()], d.lon[d.track.first()]), AColor.rgb(30, 160, 60)))
                overlays.add(dotMarker(this, GeoPoint(d.lat[d.track.last()], d.lon[d.track.last()]), AColor.rgb(200, 40, 40)))
                holder.cursor = cursorMarker(this).also { overlays.add(it) }
                // On top, so it sees taps before the polylines swallow them:
                // a tap within 24 dp of the track moves the cursor there.
                overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                        val t = nearestTrackTime(this@apply, d, p) ?: return false
                        vm.cursorS = t
                        vm.playing = false
                        return true
                    }
                    override fun longPressHelper(p: GeoPoint): Boolean = false
                }))
                // Camera fit needs real dimensions (zoomToBoundingBox is
                // unreliable pre-layout — see RideMap); refits on "Fit track".
                addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                    if (r - l > 0 && b - t > 0 && holder.fitDone != vm.fitRequest) fit(v as MapView, d, holder, vm.fitRequest)
                }
            }
        },
        update = { map ->
            if (holder.satellite != vm.satellite) {
                holder.satellite = vm.satellite
                map.setTileSource(if (vm.satellite) ESRI_IMAGERY else TileSourceFactory.MAPNIK)
            }
            val k = d.trackIndexAt(vm.cursorS).coerceAtLeast(0)
            val i = d.track[k]
            val at = GeoPoint(d.lat[i], d.lon[i])
            holder.cursor?.position = at
            if (vm.follow) map.controller.setCenter(at)
            if (holder.fitDone != vm.fitRequest && map.width > 0) fit(map, d, holder, vm.fitRequest)
            map.invalidate()
        },
        onRelease = { it.onDetach() },
    )
}

private fun fit(map: MapView, d: SessionData, holder: SessionMapHolder, request: Int) {
    holder.fitDone = request
    val bbox = RideMapRenderer.boundingBox(
        DoubleArray(d.track.size) { d.lat[d.track[it]] },
        DoubleArray(d.track.size) { d.lon[d.track[it]] },
    ) ?: return
    map.controller.setZoom(RideMapRenderer.fitZoom(bbox, map.width, map.height).toDouble())
    map.controller.setCenter(GeoPoint((bbox[0] + bbox[1]) / 2, (bbox[2] + bbox[3]) / 2))
}

/**
 * The track as ONE overlay coloured by speed in 16 bands: ≤ 4000 points,
 * projected per draw and batched into one `drawLines` call per band. (A
 * polyline per colour run was ~1500 osmdroid `Polyline`s on the real 10 Hz
 * test session and took seconds per frame.)
 */
private class SpeedTrackOverlay(d: SessionData, density: Float) : Overlay() {
    private val geo: List<GeoPoint>
    private val band: IntArray
    private val paints = Array(16) { b ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SessionLoader.speedArgb(b / 15.0 * d.speedScale, d.speedScale)
            strokeWidth = 3.5f * density
            strokeCap = Paint.Cap.ROUND
        }
    }
    private val pt = android.graphics.Point()
    private var xy = FloatArray(0)
    private val buf = Array(16) { FloatArray(0) }
    private val fill = IntArray(16)

    init {
        val idx = RideMapRenderer.downsampleIndices(d.track.size, 4000)
        geo = idx.map { k -> GeoPoint(d.lat[d.track[k]], d.lon[d.track[k]]) }
        band = IntArray(idx.size) { j ->
            val v = d.speedKmh[d.track[idx[j]]].toDouble()
            if (v.isFinite()) Math.round((v / d.speedScale).coerceIn(0.0, 1.0) * 15).toInt() else 0
        }
    }

    override fun draw(c: ACanvas, pj: Projection) {
        val n = geo.size
        if (n < 2) return
        if (xy.size < n * 2) xy = FloatArray(n * 2)
        for (j in 0 until n) {
            pj.toPixels(geo[j], pt)
            xy[2 * j] = pt.x.toFloat(); xy[2 * j + 1] = pt.y.toFloat()
        }
        fill.fill(0)
        for (j in 1 until n) {
            val b = band[j - 1]
            if (buf[b].size < fill[b] + 4) buf[b] = buf[b].copyOf(maxOf(64, buf[b].size * 2))
            val o = buf[b]
            var k = fill[b]
            o[k++] = xy[2 * j - 2]; o[k++] = xy[2 * j - 1]; o[k++] = xy[2 * j]; o[k++] = xy[2 * j + 1]
            fill[b] = k
        }
        for (b in 0 until 16) if (fill[b] > 0) c.drawLines(buf[b], 0, fill[b], paints[b])
    }
}

/** Session time of the track point nearest to a tap, if within 24 dp. */
private fun nearestTrackTime(map: MapView, d: SessionData, p: GeoPoint): Double? {
    val proj = map.projection
    val tap = proj.toPixels(p, null)
    val pt = android.graphics.Point()
    var best = -1
    var bestD = Double.MAX_VALUE
    for (i in d.track) {
        proj.toPixels(GeoPoint(d.lat[i], d.lon[i]), pt)
        val dist = hypot((pt.x - tap.x).toDouble(), (pt.y - tap.y).toDouble())
        if (dist < bestD) { bestD = dist; best = i }
    }
    val limit = 24 * map.context.resources.displayMetrics.density
    return if (best >= 0 && bestD <= limit) d.gpsT[best] else null
}

/** Yellow dot with a black ring — the cursor position on the track. */
private fun cursorMarker(map: MapView): Marker {
    val px = (map.context.resources.displayMetrics.density * 18).toInt()
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = ACanvas(bmp)
    val r = px / 2f
    c.drawCircle(r, r, r - 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.rgb(255, 200, 0) })
    c.drawCircle(r, r, r - 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AColor.BLACK; style = Paint.Style.STROKE; strokeWidth = 4f
    })
    return Marker(map).apply {
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = BitmapDrawable(map.context.resources, bmp)
        setInfoWindow(null)
    }
}

// ---- charts ----------------------------------------------------------------

private class Series(val label: String, val t: DoubleArray, val y: FloatArray, val color: Color)

private val SPEED_C = Color(0xFF3B82F6)
private val ACC_C = Color(0xFFF08C3C)
private val PITCH_C = Color(0xFF22A355)
private val ROLL_C = Color(0xFFC04FC0)
private val HEIGHT_C = Color(0xFF14A3A3)
private val GYRO_C = Color(0xFFC9A400)
private val CURSOR_C = Color(0xFFFFB300)

@Composable
private fun Charts(vm: SessionViewModel, d: SessionData, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (d.gpsT.isNotEmpty()) {
                FilterChip(vm.showSpeed, { vm.showSpeed = !vm.showSpeed }, { Text("Speed") })
            }
            FilterChip(vm.showAcc, { vm.showAcc = !vm.showAcc }, { Text("Acceleration") })
            FilterChip(vm.showAngles, { vm.showAngles = !vm.showAngles }, { Text("Pitch / roll") })
            FilterChip(vm.showHeight, { vm.showHeight = !vm.showHeight }, { Text("Height") })
            FilterChip(vm.showGyro, { vm.showGyro = !vm.showGyro }, { Text("Rotation") })
            if (vm.viewHi - vm.viewLo < d.durationS - 0.5) {
                TextButton(onClick = vm::resetView) { Text("Reset zoom") }
            }
        }
        HorizontalDivider()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            if (vm.showSpeed && d.gpsT.isNotEmpty()) {
                Chart(vm, d, "GPS speed", "km/h", listOf(Series("speed", d.gpsT, d.speedKmh, SPEED_C)), zeroBased = true)
            }
            if (vm.showAcc) Chart(vm, d, "Acceleration |acc|", "g", listOf(Series("|acc|", d.sensT, d.accG, ACC_C)))
            if (vm.showAngles) {
                Chart(
                    vm, d, "Pitch (nose up +) / roll", "°",
                    listOf(Series("pitch", d.sensT, d.pitchDeg, PITCH_C), Series("roll", d.sensT, d.rollDeg, ROLL_C)),
                )
            }
            if (vm.showHeight) Chart(vm, d, "Baro height", "m", listOf(Series("height", d.sensT, d.heightM, HEIGHT_C)))
            if (vm.showGyro) Chart(vm, d, "Rotation rate |gyro|", "°/s", listOf(Series("|gyro|", d.sensT, d.gyroDps, GYRO_C)))
            Text(
                "Tap a chart to move the cursor · pinch to zoom · drag sideways to pan",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

/**
 * One chart over the shared view range `[viewLo, viewHi]`. The decimated
 * paths are built in the `drawWithCache` block, which only re-runs when the
 * range or size changes — the cursor line (read in the draw block alone)
 * moves every playback frame without re-decimating a million rows.
 */
@Composable
private fun ColumnScope.Chart(
    vm: SessionViewModel,
    d: SessionData,
    title: String,
    unit: String,
    series: List<Series>,
    zeroBased: Boolean = false,
) {
    val tm = rememberTextMeasurer()
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        if (series.size > 1) {
            series.forEach { s ->
                Box(Modifier.size(width = 12.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(s.color))
                Spacer(Modifier.width(3.dp))
                Text(s.label, fontSize = 10.sp, color = labelColor)
                Spacer(Modifier.width(8.dp))
            }
        }
        Text(unit, fontSize = 11.sp, color = labelColor)
    }
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(124.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(surface)
            .pointerInput(d) {
                detectTapGestures { o ->
                    vm.cursorS = vm.viewLo + (o.x / size.width) * (vm.viewHi - vm.viewLo)
                    vm.playing = false
                }
            }
            .pointerInput(d) {
                detectHorizontalDragGestures { change, dx ->
                    change.consume()
                    vm.panView(-dx.toDouble() / size.width)
                }
            }
            .pointerInput(d) {
                // Two-finger pinch zooms around the fingers' centroid.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        if (ev.changes.count { it.pressed } >= 2) {
                            val zoom = ev.calculateZoom()
                            if (zoom != 1f) {
                                val at = (ev.calculateCentroid().x / size.width).toDouble().coerceIn(0.0, 1.0)
                                vm.zoomView(1.0 / zoom, at)
                                ev.changes.forEach { it.consume() }
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }
            .drawWithCache {
                val lo = vm.viewLo
                val hi = vm.viewHi
                val span = (hi - lo).coerceAtLeast(1e-6)
                val axisH = 14.dp.toPx()
                val plotH = size.height - axisH
                val buckets = minOf(SessionLoader.PLOT_BUCKETS, size.width.toInt().coerceAtLeast(16))
                val pts = series.map { SessionLoader.decimate(it.t, it.y, lo, hi, buckets) }
                var ymin = Float.POSITIVE_INFINITY
                var ymax = Float.NEGATIVE_INFINITY
                for (p in pts) for (k in 1 until p.size step 2) {
                    if (p[k] < ymin) ymin = p[k]
                    if (p[k] > ymax) ymax = p[k]
                }
                if (!ymin.isFinite()) { ymin = 0f; ymax = 1f }
                if (zeroBased) ymin = minOf(ymin, 0f)
                if (ymax - ymin < 0.1f) { val m = (ymin + ymax) / 2; ymin = m - 0.05f; ymax = m + 0.05f }
                val pad = (ymax - ymin) * 0.06f
                // A zero-based axis (speed) keeps 0 on the floor line.
                ymin = if (zeroBased && ymin >= 0f) 0f else ymin - pad
                ymax += pad
                val yr = ymax - ymin
                fun px(t: Float) = ((t - lo) / span * size.width).toFloat()
                fun py(y: Float) = plotH - (y - ymin) / yr * plotH
                // A stretch with no samples (GPS file paused, logger
                // restart) is drawn as a gap, not a straight bridge.
                val gap = maxOf(2.0, 3 * span / buckets)
                val paths = pts.map { p ->
                    Path().apply {
                        for (k in 0 until p.size step 2) {
                            if (k == 0 || p[k] - p[k - 2] > gap) moveTo(px(p[k]), py(p[k + 1]))
                            else lineTo(px(p[k]), py(p[k + 1]))
                        }
                    }
                }

                // Wall-clock gridlines at round local times (elapsed time
                // when the session has no clock).
                val style = TextStyle(fontSize = 9.sp, color = labelColor)
                val base = d.clock.localSecondOfDay(d.t0Ticks + lo * 100.0, java.util.TimeZone.getDefault()) ?: lo
                val step = niceStepSec(span, size.width, 64.dp.toPx())
                val ticks = ArrayList<Pair<Float, String>>()
                var tick = ceil(base / step) * step
                while (tick <= base + span + 1e-9) {
                    ticks.add(((tick - base) / span * size.width).toFloat() to hms(tick, false))
                    tick += step
                }
                val labels = ticks.map { (x, s) -> x to tm.measure(AnnotatedString(s), style) }
                val top = tm.measure(AnnotatedString("${fmtAxis(ymax)} $unit"), style)
                val bottom = tm.measure(AnnotatedString("${fmtAxis(ymin)} $unit"), style)
                val grid = labelColor.copy(alpha = 0.18f)

                onDrawBehind {
                    var lastEnd = -Float.MAX_VALUE
                    for ((x, l) in labels) {
                        drawLine(grid, Offset(x, 0f), Offset(x, plotH), 1f)
                        val lx = (x - l.size.width / 2f).coerceIn(0f, size.width - l.size.width)
                        if (lx > lastEnd + 4f) {
                            drawText(l, topLeft = Offset(lx, plotH + (axisH - l.size.height) / 2f))
                            lastEnd = lx + l.size.width
                        }
                    }
                    if (ymin < 0f && ymax > 0f) drawLine(grid, Offset(0f, py(0f)), Offset(size.width, py(0f)), 1.5f)
                    paths.forEachIndexed { k, p -> drawPath(p, series[k].color, style = Stroke(width = 1.6.dp.toPx())) }
                    drawText(top, topLeft = Offset(4.dp.toPx(), 2.dp.toPx()))
                    drawText(bottom, topLeft = Offset(4.dp.toPx(), plotH - bottom.size.height - 2.dp.toPx()))
                    val cx = ((vm.cursorS - lo) / span * size.width).toFloat()
                    if (cx in 0f..size.width) drawLine(CURSOR_C, Offset(cx, 0f), Offset(cx, plotH), 1.5.dp.toPx())
                }
            },
    )
}

private fun fmtAxis(v: Float): String = when {
    kotlin.math.abs(v) >= 100 -> String.format(Locale.US, "%.0f", v)
    kotlin.math.abs(v) >= 10 -> String.format(Locale.US, "%.1f", v)
    else -> String.format(Locale.US, "%.2f", v)
}
