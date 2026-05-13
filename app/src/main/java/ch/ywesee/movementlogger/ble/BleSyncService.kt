package ch.ywesee.movementlogger.ble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import ch.ywesee.movementlogger.MainActivity
import ch.ywesee.movementlogger.R
import ch.ywesee.movementlogger.ui.Connection
import ch.ywesee.movementlogger.ui.FileSyncUiState

/**
 * Foreground service that keeps the BLE worker alive while the app is in
 * the background. Holds a sticky notification ("Connected to PumpTsueri",
 * "Downloading Sens001.csv 42 %", etc.) so Android won't kill our process
 * mid-READ when the user switches apps.
 *
 * Lifecycle:
 *   - [start] is called from the ViewModel when the user initiates BLE work
 *     (Scan / Connect / etc.).
 *   - The service binds itself to [FileSyncCore] as a [FileSyncCore.Listener]
 *     and refreshes the notification on every state change.
 *   - When [FileSyncCore.isBusy] returns false (disconnected, no scan/list/
 *     download running), the service self-terminates after a short grace.
 *
 * Mirror of the iOS approach in `MovementLogger/BLE/BleClient.swift`, where
 * `UIApplication.beginBackgroundTask` plays the equivalent role.
 */
class BleSyncService : Service(), FileSyncCore.Listener {

    companion object {
        private const val TAG = "BleSyncService"
        private const val CHANNEL_ID = "movement_logger_sync"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "ch.ywesee.movementlogger.action.START_SYNC"
        private const val ACTION_STOP  = "ch.ywesee.movementlogger.action.STOP_SYNC"
        private const val IDLE_GRACE_MS = 5_000L

        fun start(context: Context) {
            val intent = Intent(context, BleSyncService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BleSyncService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val idleCheck = Runnable { stopIfIdle() }

    override fun onCreate() {
        super.onCreate()
        FileSyncCore.ensureInit(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Promote to foreground immediately — Android gives services
                // a hard ~5 s deadline to call startForeground after
                // startForegroundService, or it kills us with an ANR-style
                // crash.
                promoteForeground(buildNotification(FileSyncCore.state.value))
                FileSyncCore.setListener(this)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        FileSyncCore.setListener(null)
        mainHandler.removeCallbacks(idleCheck)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    //  FileSyncCore.Listener
    // -------------------------------------------------------------------------

    override fun onStateChanged(state: FileSyncUiState) {
        // Refresh notification on every meaningful state change.
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, buildNotification(state))

        // Self-terminate when the user fully disconnects and no work is
        // queued. Grace delay handles the short window between an op
        // finishing and the next one starting.
        mainHandler.removeCallbacks(idleCheck)
        if (!FileSyncCore.isBusy()) {
            mainHandler.postDelayed(idleCheck, IDLE_GRACE_MS)
        }
    }

    private fun stopIfIdle() {
        if (FileSyncCore.isBusy()) return
        Log.d(TAG, "BLE idle — stopping foreground service")
        stopForegroundCompat()
        stopSelf()
    }

    // -------------------------------------------------------------------------
    //  Notification
    // -------------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "PumpTsueri sync",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while the app is syncing with the SensorTile.box."
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(state: FileSyncUiState): android.app.Notification {
        val (title, body, progress) = renderText(state)
        val tap = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, 0, tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
        progress?.let { (cur, max) ->
            builder.setProgress(max, cur, false)
        }
        return builder.build()
    }

    /** Returns (title, body, optional progress = current/max in 0..100). */
    private fun renderText(state: FileSyncUiState): Triple<String, String, Pair<Int, Int>?> {
        // Active download wins — show the file + percentage.
        state.downloads.entries.firstOrNull()?.let { (name, p) ->
            val pct = (p.fraction * 100).toInt()
            return Triple(
                "Downloading $name",
                "$pct % · keep app installed; you can switch away",
                pct to 100,
            )
        }
        if (state.listing) {
            return Triple("Listing files", "Reading SD-card index from the box", null)
        }
        if (state.sessionRunning != null) {
            val remaining = state.sessionRunning.remainingMillis(System.currentTimeMillis())
            val mins = (remaining / 60_000L).toInt()
            return Triple(
                "LOG session running",
                if (mins > 0) "$mins min remaining" else "finishing up…",
                null,
            )
        }
        return when (state.connection) {
            Connection.Connecting -> Triple("Connecting", "Opening BLE link to PumpTsueri", null)
            Connection.Connected  -> Triple("Connected", "PumpTsueri ready · idle", null)
            Connection.Disconnected ->
                if (state.scanning) Triple("Scanning", "Looking for PumpTsueri", null)
                else Triple("Movement Logger", "Idle", null)
        }
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
