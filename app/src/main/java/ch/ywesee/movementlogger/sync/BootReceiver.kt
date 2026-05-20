package ch.ywesee.movementlogger.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-arm the background sync schedule after a device reboot or app update.
 *
 * WorkManager persists its job database across process restarts inside the
 * same boot session, but **not** across reboots. Without this receiver the
 * periodic sync would silently die the first time the phone is power-cycled
 * and never re-arm — matching the desktop autostart story (`autostart.rs`
 * installs the login item so the agent comes back after a reboot).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "rearming background sync after ${intent.action}")
                BackgroundSync.refresh(context.applicationContext)
            }
        }
    }
    companion object { private const val TAG = "BootReceiver" }
}
