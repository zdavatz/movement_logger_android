package ch.ywesee.movementlogger.usb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import ch.ywesee.movementlogger.MainActivity
import ch.ywesee.movementlogger.R
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the USB GPS reader + CSV logger alive while
 * the app is backgrounded — twin of [ch.ywesee.movementlogger.ble.BleSyncService].
 *
 * Without it the process only survives screen-off by luck: Android's
 * cached-app freezer suspends background processes wholesale, which silently
 * stalls the read loop mid-recording. The 11.7.2026 water-test recording ran
 * 33 min in DOZE only because the freezer happened to leave the USB-fd
 * holder alone — a foreground service makes that survival contractual.
 *
 * Lifecycle: started from [UbloxGpsCore.startReader]; polls the core's state
 * every [POLL_MS] to refresh the sticky notification ("Recording GPS — 1234
 * rows · 10.0 Hz"), and self-stops after [IDLE_GRACE_MS] of neither reading
 * nor logging.
 */
class UbloxGpsService : Service() {

    companion object {
        private const val TAG = "UbloxGpsService"
        private const val CHANNEL_ID = "movement_logger_gps"
        // BleSyncService owns notification id 1.
        private const val NOTIFICATION_ID = 2
        private const val POLL_MS = 2_000L
        private const val IDLE_GRACE_MS = 5_000L

        fun start(context: Context) {
            val intent = Intent(context, UbloxGpsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null
    private var idleSinceMs = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground immediately — Android gives services a hard
        // ~5 s deadline to call startForeground after startForegroundService.
        promoteForeground(buildNotification(UbloxGpsCore.state.value))
        idleSinceMs = 0L
        if (pollJob?.isActive != true) {
            pollJob = scope.launch { pollLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun pollLoop() {
        while (scope.isActive) {
            val state = UbloxGpsCore.state.value
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIFICATION_ID, buildNotification(state))
            if (state.isReading || state.isLogging) {
                idleSinceMs = 0L
            } else {
                val now = SystemClock.uptimeMillis()
                if (idleSinceMs == 0L) idleSinceMs = now
                if (now - idleSinceMs >= IDLE_GRACE_MS) {
                    Log.d(TAG, "GPS idle — stopping foreground service")
                    stopForegroundCompat()
                    stopSelf()
                    return
                }
            }
            delay(POLL_MS)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "USB GPS logging",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while the u-blox USB GPS is connected or recording."
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(state: UbloxUiState): android.app.Notification {
        val title: String
        val body: String
        when {
            state.isLogging -> {
                title = "Recording GPS"
                val hz = maxOf(state.ggaHz, state.rmcHz)
                body = String.format(Locale.US, "%d rows · %.1f Hz", state.loggedRows, hz)
            }
            state.isReading -> {
                title = "GPS connected"
                body = state.deviceName ?: "u-blox receiver"
            }
            else -> {
                title = "Movement Logger"
                body = "GPS idle"
            }
        }
        val tap = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, 0, tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun promoteForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }
}
