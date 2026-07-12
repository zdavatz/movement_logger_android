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
 * Race mode with the **Phone GPS** source: owns the `LocationManager`
 * GPS updates (~1 Hz) and forwards each fix to
 * [RaceUplink.sendPhoneFix], wrapped in a `location`-type foreground
 * service so streaming survives screen-off / app-switch — the Android
 * pendant of iOS's location background mode. Started/stopped by
 * [RaceUplink.setEnabled] when the source is "phone"; requires the
 * runtime ACCESS_FINE_LOCATION grant (the Race card asks before
 * enabling), otherwise it stops itself immediately.
 *
 * Also tracks the satellites-used count via [GnssStatus] so the phone
 * source reports `sat` like the u-blox source does.
 */
class RaceGpsService : Service(), LocationListener {

    companion object {
        private const val TAG = "RaceGpsService"
        private const val CHANNEL_ID = "movement_logger_race"
        private const val NOTIFICATION_ID = 3

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, RaceGpsService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RaceGpsService::class.java))
        }
    }

    private var locationManager: LocationManager? = null
    private var satsUsed = -1
    private var gnssCallback: GnssStatus.Callback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        return START_STICKY
    }

    override fun onDestroy() {
        gnssCallback?.let { locationManager?.unregisterGnssStatusCallback(it) }
        locationManager?.removeUpdates(this)
        locationManager = null
        super.onDestroy()
    }

    // --- LocationListener ---

    override fun onLocationChanged(location: Location) {
        RaceUplink.sendPhoneFix(location, satsUsed)
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
                CHANNEL_ID, "Race mode", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Live position streaming to the race map" },
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Race mode")
            .setContentText("Streaming phone GPS to the race map")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
