package ch.ywesee.movementlogger.usb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import ch.ywesee.movementlogger.MainActivity

/**
 * Location foreground service for [PhoneLoggerCore]: owns the
 * `LocationManager` GPS updates (~1 Hz) and the [GnssStatus]
 * satellites-used count, forwarding each fix to
 * [PhoneLoggerCore.onPhoneFix] — and keeps the process unfrozen so the
 * 100 Hz sensor listeners survive screen-off for the length of a ride.
 * Twin of [RaceGpsService] (which serves the race uplink instead); both
 * can run at once. Started/stopped by [PhoneLoggerCore.start]/[stop].
 */
class PhoneLoggerService : Service(), LocationListener {

    companion object {
        private const val TAG = "PhoneLoggerService"
        private const val CHANNEL_ID = "movement_logger_phone_logger"
        private const val NOTIFICATION_ID = 4

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, PhoneLoggerService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PhoneLoggerService::class.java))
        }
    }

    private var locationManager: LocationManager? = null
    private var satsUsed = -1
    private var gnssCallback: GnssStatus.Callback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A sticky restart after process death has no open CSV writers —
        // nothing to log into, so don't hold a location wakelock for it.
        if (!PhoneLoggerCore.isRunning) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "no location permission — stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (locationManager == null) {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            locationManager = lm
            try {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 500L, 0f, this, Looper.getMainLooper(),
                )
                val cb = object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        var used = 0
                        for (i in 0 until status.satelliteCount) {
                            if (status.usedInFix(i)) used++
                        }
                        satsUsed = used
                    }
                }
                lm.registerGnssStatusCallback(cb, null)
                gnssCallback = cb
            } catch (e: SecurityException) {
                Log.w(TAG, "location updates refused: $e")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        gnssCallback?.let { locationManager?.unregisterGnssStatusCallback(it) }
        locationManager?.removeUpdates(this)
        locationManager = null
        super.onDestroy()
    }

    // --- LocationListener ---

    override fun onLocationChanged(location: Location) {
        PhoneLoggerCore.onPhoneFix(location, satsUsed)
    }

    @Deprecated("Deprecated in API 29, still invoked on some devices")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // --- notification ---

    private fun createChannel() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Phone logger", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shown while the phone is recording GPS + motion CSVs." },
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Phone logger")
            .setContentText("Recording GPS + motion sensors to CSV")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
