package ch.ywesee.movementlogger.usb

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow

/**
 * Phone board attitude + height for the board-mounted phone rider — the Android
 * twin of the iOS `PhoneMotion`. `TYPE_ROTATION_VECTOR` gives fused,
 * gravity-referenced pitch/roll/yaw (with a "Zero here" tare so the arbitrary
 * mount orientation is the reference); `TYPE_PRESSURE` gives height relative to
 * the pressure at start.
 *
 * The barometer is OPTIONAL on Android (many phones lack one) — height simply
 * stays absent on those, while the angles still stream. No runtime permission is
 * needed for either sensor at these rates.
 */
object PhoneMotion {
    data class Angles(
        val pitch: Double = Double.NaN,
        val roll: Double = Double.NaN,
        val yaw: Double = Double.NaN,
        val live: Boolean = false,
        val zeroed: Boolean = false,
    )

    private val _angles = MutableStateFlow(Angles())
    val angles: StateFlow<Angles> = _angles.asStateFlow()

    private var sm: SensorManager? = null
    private var listener: SensorEventListener? = null
    private var running = false

    // Tare reference (degrees) — reported values are (raw - ref).
    @Volatile private var refPitch = 0.0
    @Volatile private var refRoll = 0.0
    @Volatile private var refYaw = 0.0
    @Volatile private var hasZero = false
    @Volatile private var zeroPending = false

    @Volatile private var pitchDeg = Double.NaN
    @Volatile private var rollDeg = Double.NaN
    @Volatile private var yawDeg = Double.NaN

    @Volatile private var p0 = Double.NaN          // reference pressure at start
    @Volatile private var altM = Double.NaN        // relative altitude (m)
    @Volatile private var pressureHPa = Double.NaN

    private var pubCount = 0

    fun start(ctx: Context) {
        if (running) return
        val mgr = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        sm = mgr
        val rot = mgr.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val pres = mgr.getDefaultSensor(Sensor.TYPE_PRESSURE)
        val l = object : SensorEventListener {
            private val rm = FloatArray(9)
            private val ori = FloatArray(3)
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rm, e.values)
                        SensorManager.getOrientation(rm, ori)   // [azimuth, pitch, roll] rad
                        val d = 180.0 / Math.PI
                        val rawYaw = ori[0] * d       // heading
                        val rawPitch = -ori[1] * d    // nose up positive
                        val rawRoll = ori[2] * d      // bank
                        if (zeroPending) {
                            refPitch = rawPitch; refRoll = rawRoll; refYaw = rawYaw
                            hasZero = true; zeroPending = false
                        }
                        pitchDeg = if (hasZero) rawPitch - refPitch else rawPitch
                        rollDeg = if (hasZero) rawRoll - refRoll else rawRoll
                        yawDeg = norm180(if (hasZero) rawYaw - refYaw else rawYaw)
                        pubCount++
                        if (pubCount % 3 == 0) {
                            _angles.value = Angles(pitchDeg, rollDeg, yawDeg, true, hasZero)
                        }
                    }
                    Sensor.TYPE_PRESSURE -> {
                        val p = e.values[0].toDouble()   // hPa
                        pressureHPa = p
                        if (p0.isNaN()) p0 = p
                        altM = 44330.0 * (1.0 - (p / p0).pow(0.1903))
                    }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        listener = l
        rot?.let { mgr.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
        pres?.let { mgr.registerListener(l, it, SensorManager.SENSOR_DELAY_NORMAL) }
        running = true
    }

    fun stop() {
        listener?.let { sm?.unregisterListener(it) }
        listener = null
        running = false
        p0 = Double.NaN; altM = Double.NaN; pressureHPa = Double.NaN
        _angles.value = Angles()
    }

    fun zero() { zeroPending = true }
    fun clearZero() {
        hasZero = false
        _angles.value = _angles.value.copy(zeroed = false)
    }

    /** Latest board snapshot for a phone rider datagram. */
    fun snapshot(): RaceUplink.BoardSnapshot =
        RaceUplink.BoardSnapshot(pitchDeg, rollDeg, yawDeg, altM, pressureHPa, hasZero)

    private fun norm180(a: Double): Double {
        var x = a % 360.0
        if (x > 180.0) x -= 360.0
        if (x < -180.0) x += 360.0
        return x
    }
}
