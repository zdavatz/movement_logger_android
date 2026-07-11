package ch.ywesee.movementlogger.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import ch.ywesee.movementlogger.data.CsvParsers
import ch.ywesee.movementlogger.data.GpsRow
import ch.ywesee.movementlogger.data.RideMapRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * Detail screen for one GPS CSV: draws the recorded track on an interactive
 * OSM map and exports a shareable PNG (map tiles under a speed-coloured
 * track with logo + stats + source link — see [RideMapRenderer]). Port of
 * the iOS `RideMapView` (`RideMap.swift`); reached from the GPS tab's
 * recordings list and from downloaded `Gps*.csv` rows in the Sync tab.
 */
@Composable
fun RideMapView(name: String, path: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember(path) { mutableStateOf<List<GpsRow>?>(null) }
    var loadError by remember(path) { mutableStateOf<String?>(null) }
    var rendering by remember(path) { mutableStateOf(false) }
    var shareError by remember(path) { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        val result = withContext(Dispatchers.IO) {
            runCatching { CsvParsers.parseGpsFile(File(path)) }
        }
        result.fold(
            onSuccess = { rows = it; loadError = if (it.isEmpty()) "no rows parsed" else null },
            onFailure = { loadError = it.message ?: "couldn't read file" },
        )
    }

    // Blackout-cleaned segments: fabricated antenna-under-water positions
    // are dropped and the polyline breaks across fix holes instead of
    // bridging them with straight connector lines.
    val segments = rows?.let { RideMapRenderer.cleanTrackSegments(it) } ?: emptyList()
    val pts = segments.flatten()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        name,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (rendering) {
                        CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(
                            enabled = pts.size >= 2,
                            onClick = {
                                rendering = true
                                shareError = null
                                scope.launch {
                                    val png = RideMapRenderer.render(
                                        ctx, rows.orEmpty(), name.substringBeforeLast('.'),
                                    )
                                    rendering = false
                                    if (png != null) sharePng(ctx, png)
                                    else shareError = "Export failed — check the internet connection (map tiles)."
                                }
                            },
                        ) { Text("Share") }
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                shareError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                HorizontalDivider()
                Box(Modifier.fillMaxSize()) {
                    when {
                        loadError != null -> CenteredNote("Couldn't read file: $loadError")
                        rows == null -> Row(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                        pts.size < 2 -> CenteredNote(
                            "No GPS fixes — this recording has fewer than two valid points to plot.",
                        )
                        else -> TrackMap(segments)
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The interactive osmdroid map with the downsampled track + start/end dots.
 *  One polyline per blackout-cleaned segment — never a line across a hole. */
@Composable
private fun TrackMap(segments: List<List<GpsRow>>) {
    val pts = segments.flatten()
    AndroidView(
        // clipToBounds: osmdroid paints outside its layout bounds while
        // panning/zooming and would otherwise draw over the dialog's top bar.
        modifier = Modifier.fillMaxSize().clipToBounds(),
        factory = { ctx ->
            // osmdroid setup: context-scoped cache paths (scoped-storage
            // safe) + an identifying user agent per the OSM tile policy.
            Configuration.getInstance().load(
                ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE),
            )
            Configuration.getInstance().userAgentValue =
                "MovementLogger-Android (${RideMapRenderer.SOURCE_URL})"

            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

                for (seg in segments) {
                    val idx = RideMapRenderer.downsampleIndices(
                        seg.size, (2000 * seg.size / pts.size).coerceAtLeast(2),
                    )
                    overlays.add(Polyline(this).apply {
                        outlinePaint.color = AColor.rgb(0x30, 0xB0, 0xC7) // iOS .teal
                        outlinePaint.strokeWidth = 9f
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        setPoints(idx.map { GeoPoint(seg[it].lat, seg[it].lon) })
                        infoWindow = null
                    })
                }
                overlays.add(
                    dotMarker(this, GeoPoint(pts.first().lat, pts.first().lon), AColor.rgb(52, 199, 89)),
                )
                overlays.add(
                    dotMarker(this, GeoPoint(pts.last().lat, pts.last().lon), AColor.rgb(255, 59, 48)),
                )

                // Deterministic camera fit: reuse the PNG renderer's margin +
                // min-span + zoom math once the view has real dimensions.
                // (zoomToBoundingBox proved unreliable pre-layout — it left
                // the camera at z21 over null island, where the tile source
                // max of 19 silently disables all downloads.)
                val bbox = RideMapRenderer.boundingBox(
                    DoubleArray(pts.size) { pts[it].lat },
                    DoubleArray(pts.size) { pts[it].lon },
                )!!
                addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: android.view.View, l: Int, t: Int, r: Int, b: Int,
                        ol: Int, ot: Int, or2: Int, ob: Int,
                    ) {
                        if (r - l <= 0 || b - t <= 0) return
                        removeOnLayoutChangeListener(this)
                        val z = RideMapRenderer.fitZoom(bbox, r - l, b - t)
                        controller.setZoom(z.toDouble())
                        controller.setCenter(
                            GeoPoint((bbox[0] + bbox[1]) / 2, (bbox[2] + bbox[3]) / 2),
                        )
                    }
                })
            }
        },
        onRelease = { it.onDetach() },
    )
}

/** A round start/end dot (green/red, white ring) as a centre-anchored marker. */
private fun dotMarker(map: MapView, at: GeoPoint, fill: Int): Marker {
    val d = (map.context.resources.displayMetrics.density * 18).toInt()
    val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = d / 2f
    c.drawCircle(r, r, r - 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
    c.drawCircle(r, r, r - 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AColor.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f
    })
    return Marker(map).apply {
        position = at
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = BitmapDrawable(map.context.resources, bmp)
        setInfoWindow(null)
    }
}

/** Fire the system share sheet for a rendered ride-map PNG. */
private fun sharePng(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, "Share ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
