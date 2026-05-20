package ch.ywesee.movementlogger.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import ch.ywesee.movementlogger.MainActivity
import ch.ywesee.movementlogger.R
import ch.ywesee.movementlogger.ble.FileSyncCore
import ch.ywesee.movementlogger.ui.Connection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Background sync worker — connects to the configured box, runs one sync
 * pass (LIST → diff → download missing tails), disconnects. The Android
 * pendant of the desktop `--agent` loop (`stbox-viz-gui/src/agent.rs`).
 *
 * Coordination ("GUI wins, agent yields"):
 *   - If [FileSyncCore.isBusy] (the foreground UI is scanning / connected /
 *     downloading), the worker returns [Result.retry] without touching BLE.
 *   - If the worker is mid-run when the UI opens, [FileSyncCore.onEvent]
 *     stays single-threaded — the UI's `connect()` would queue behind the
 *     worker's pending commands; in practice the UI grabs the foreground
 *     service first, so the worker's next iteration sees `isBusy` and
 *     backs off.
 *
 * Scheduling: enqueued by [BackgroundSync.refresh] as a unique periodic
 * work. Re-armed at boot by [BootReceiver].
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        // Gating — port of desktop `cfg.keep_synced && cfg.log_mode_manual != Some(true)`.
        val cfg = AgentConfig.load(ctx)
        if (!cfg.active) {
            Log.i(TAG, "skip — config inactive (keepSynced=${cfg.keepSynced}, " +
                "boxId=${cfg.boxId != null}, manual=${cfg.logModeManual})")
            return Result.success()
        }
        val boxId = cfg.boxId ?: return Result.success()

        // BLE_CONNECT is a runtime permission on Android 12+. A worker can't
        // prompt the user — if it's missing, the agent waits for the UI to
        // grant it. Same idea as desktop's TCC precondition.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "skip — BLUETOOTH_CONNECT not granted")
            return Result.success()
        }

        // Yield if the UI is already driving BLE. The agent runs again in
        // 15 min; nothing is lost.
        FileSyncCore.ensureInit(ctx)
        if (FileSyncCore.isBusy()) {
            Log.i(TAG, "yield — FileSyncCore is busy (UI active)")
            return Result.retry()
        }

        ensureChannel(ctx)
        setForeground(initialForegroundInfo(ctx, boxId))

        Log.i(TAG, "connecting to $boxId")
        FileSyncCore.connect(boxId)

        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            FileSyncCore.state.first { it.connection == Connection.Connected }
        }
        if (connected == null) {
            Log.w(TAG, "connect timeout — disconnecting")
            FileSyncCore.disconnect()
            return Result.retry()
        }

        // Kick the sync. The on-connect handler already runs a pass when
        // keepSynced is on or there's a pending resume, but call it again
        // unconditionally so a freshly-started worker (no prior interrupt
        // state) always advances. startSyncPass is idempotent if already
        // syncing.
        Log.i(TAG, "connected — starting sync pass")
        FileSyncCore.syncNow()

        // Drain: wait until syncing finishes AND downloads queue is empty.
        val drained = withTimeoutOrNull(SYNC_TIMEOUT_MS) {
            FileSyncCore.state.first { s ->
                !s.syncing && s.downloads.isEmpty() && !s.listing
            }
        }
        if (drained == null) {
            Log.w(TAG, "sync timeout — disconnecting and retrying")
        } else {
            Log.i(TAG, "sync complete — disconnecting")
        }

        FileSyncCore.disconnect()
        withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
            FileSyncCore.state.first { it.connection == Connection.Disconnected }
        }
        return if (drained != null) Result.success() else Result.retry()
    }

    private fun initialForegroundInfo(ctx: Context, boxId: String): ForegroundInfo {
        val tap = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Background sync")
            .setContentText("Syncing with $boxId")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notif)
        }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background sync",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while the app mirrors a SensorTile.box in the background."
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val CHANNEL_ID = "movement_logger_bg_sync"
        /** Distinct from BleSyncService's NOTIFICATION_ID=1. */
        private const val NOTIFICATION_ID = 2

        private const val CONNECT_TIMEOUT_MS = 60_000L
        /** ~8 min — leaves headroom under WorkManager's 10 min foreground cap. */
        private const val SYNC_TIMEOUT_MS = 8L * 60 * 1000
        private const val DISCONNECT_TIMEOUT_MS = 5_000L
    }
}
