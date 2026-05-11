package ch.ywesee.movementlogger.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Butterworth low-pass filter + zero-phase `filtfilt` — Kotlin port of
 * `butter.rs`. scipy.signal.butter + filtfilt for the board animation's
 * 2 Hz smoothing step. 4th-order design in the analog s-plane via pole
 * placement, bilinear-transformed to z-plane, then direct-form I IIR.
 *
 * `filtfilt` runs the filter forward then backward (reversed input) to
 * cancel the phase response — amplitude response squares so effective
 * order = 2N, output timing matches input.
 */
object Butterworth {

    data class Coeffs(val b: DoubleArray, val a: DoubleArray)

    /**
     * Design a 4th-order Butterworth low-pass filter normalised to
     * `cutoffHz` at sampling rate `fsHz`. Returns 5-tap b and a.
     */
    fun butter4Lowpass(cutoffHz: Double, fsHz: Double): Coeffs {
        val n = 4
        // Pre-warp cutoff for the bilinear transform
        val wd = 2.0 * PI * cutoffHz
        val wa = 2.0 * tan(wd / (2.0 * fsHz)) * fsHz

        // Butterworth analog prototype poles on the left half of the unit
        // circle: p_k = exp(j·π·(2k + N + 1)/(2N)) for k in 0..N-1, scaled by wa.
        val polesReal = DoubleArray(n)
        val polesImag = DoubleArray(n)
        for (k in 0 until n) {
            val theta = PI * (2 * k + n + 1).toDouble() / (2.0 * n.toDouble())
            polesReal[k] = wa * cos(theta)
            polesImag[k] = wa * sin(theta)
        }

        // Bilinear transform: z = (2·fs + s) / (2·fs − s)
        val zPolesR = DoubleArray(n)
        val zPolesI = DoubleArray(n)
        val k2 = 2.0 * fsHz
        for (k in 0 until n) {
            val pr = polesReal[k]
            val pi = polesImag[k]
            val numR = k2 + pr
            val numI = pi
            val denR = k2 - pr
            val denI = -pi
            val denom = denR * denR + denI * denI
            zPolesR[k] = (numR * denR + numI * denI) / denom
            zPolesI[k] = (numI * denR - numR * denI) / denom
        }

        // Denominator = prod(1 − z_pole·z⁻¹) via complex polynomial multiply.
        var polyR = doubleArrayOf(1.0)
        var polyI = doubleArrayOf(0.0)
        for (k in 0 until n) {
            val nextR = DoubleArray(polyR.size + 1)
            val nextI = DoubleArray(polyR.size + 1)
            for (i in polyR.indices) {
                val r = polyR[i]
                val im = polyI[i]
                // Coefficient 1 at z⁻i
                nextR[i] += r
                nextI[i] += im
                // Coefficient -p at z⁻(i+1): (−p)·(r + im·j)
                val pr = zPolesR[k]
                val pi = zPolesI[k]
                nextR[i + 1] += -pr * r + pi * im
                nextI[i + 1] += -pr * im - pi * r
            }
            polyR = nextR
            polyI = nextI
        }
        // a is purely real (pole pairs are conjugate). Take real part.
        val a = DoubleArray(5)
        for (i in 0 until 5) a[i] = polyR[i]

        // Numerator (1 + z⁻¹)^4 binomial coefficients
        val b = doubleArrayOf(1.0, 4.0, 6.0, 4.0, 1.0)

        // Normalise gain to unity at DC: scale b so sum(b)/sum(a) = 1.
        val sumB = b.sum()
        val sumA = a.sum()
        val gain = sumA / sumB
        for (i in b.indices) b[i] *= gain

        return Coeffs(b, a)
    }

    /** Direct-form I IIR. `y[n] = (Σ b[k]·x[n-k] − Σ a[k]·y[n-k]) / a[0]`. */
    fun lfilter(c: Coeffs, x: DoubleArray): DoubleArray {
        val n = x.size
        val y = DoubleArray(n)
        for (i in 0 until n) {
            var acc = 0.0
            for (k in 0 until 5) if (i >= k) acc += c.b[k] * x[i - k]
            for (k in 1 until 5) if (i >= k) acc -= c.a[k] * y[i - k]
            y[i] = acc / c.a[0]
        }
        return y
    }

    /** Zero-phase: forward-then-backward pass. */
    fun filtfilt(c: Coeffs, x: DoubleArray): DoubleArray {
        val forward = lfilter(c, x)
        val reversed = DoubleArray(forward.size) { forward[forward.size - 1 - it] }
        val backward = lfilter(c, reversed)
        return DoubleArray(backward.size) { backward[backward.size - 1 - it] }
    }
}
