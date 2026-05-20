package ch.ywesee.movementlogger.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * WorkManager façade for the background sync loop. Port of the desktop
 * `autostart::sync_with_mode` + `coord::AGENT_LOCK` plumbing: when the box is
 * in AUTO and "Keep synced" is on, a periodic worker mirrors recordings
 * every [INTERVAL_MIN] minutes; otherwise the schedule is cancelled.
 *
 * Called from:
 *   - [ch.ywesee.movementlogger.ble.FileSyncCore.setKeepSynced]
 *   - [ch.ywesee.movementlogger.ble.FileSyncCore] LogMode handler
 *   - [ch.ywesee.movementlogger.ble.FileSyncCore.connect] (boxId capture)
 *   - [BootReceiver] on device reboot / app update
 */
object BackgroundSync {
    private const val TAG = "BackgroundSync"
    const val UNIQUE_WORK_NAME = "movement_logger_background_sync"
    /** WorkManager minimum is 15 min; we sit at that floor for freshness. */
    private const val INTERVAL_MIN = 15L

    /**
     * Reconcile the schedule with the current [AgentConfig]. Idempotent —
     * safe to call repeatedly. ENQUEUE policy is KEEP so a healthy in-flight
     * run isn't restarted just because the config nudged.
     */
    fun refresh(context: Context) {
        val cfg = AgentConfig.load(context)
        val wm = WorkManager.getInstance(context.applicationContext)
        if (!cfg.active) {
            Log.i(TAG, "cancel — keepSynced=${cfg.keepSynced} boxId=${cfg.boxId != null} manual=${cfg.logModeManual}")
            wm.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_MIN, TimeUnit.MINUTES)
            .setConstraints(
                // BLE is local — no network. Don't require charging (boxes
                // are usually meaningful on the go, not at the desk).
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()
        wm.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            // KEEP so config touches don't restart an in-flight run. UPDATE
            // would reset the next-run timer; we want the existing cadence.
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        Log.i(TAG, "scheduled — boxId=${cfg.boxId} every $INTERVAL_MIN min")
    }

    /** Best-effort cancel — used by the in-app "Background sync off" path. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
