package ch.ywesee.movementlogger.data

import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device IMU propulsion analysis — the Kotlin port of the iOS
 * `ImuAnalysis.swift` (itself the native port of the validated Python
 * classifier). Reads a board-mounted phone-logger recording
 * (`SensPhone_*` + `GpsPhone_*`) and classifies every second of the ride into
 * **glide (on foil) / paddle-pump / wait**, plus board pitch/roll and cadence.
 *
 * Pipeline (identical to iOS):
 *  1. signed userAccel per axis (raw − moving-average gravity) — NOT the
 *     magnitude, whose rectification doubles a clean single-axis stroke.
 *  2. STFT (radix-2 FFT, Hann, ~10 s / 1 s hop, decimated to ~25 Hz) → sum of
 *     per-axis PSDs → rhythmicity (band concentration), prominence, cadence.
 *     Stroke *energy* comes from a time-domain bandpass RMS so it goes to ~0 on
 *     a still tail.
 *  3. Otsu thresholds → classify; median-filter + short-run absorb.
 *  4. Board pitch/roll from the pseudo-tared gravity vector.
 */
enum class ImuMode { GLIDE, PADDLE, WAIT }

data class ImuAnalysisResult(
    val minutes: DoubleArray,
    val speedKmh: DoubleArray,
    val strokeEnergy: DoubleArray,
    val rhythmicity: DoubleArray,
    val strokeRate: DoubleArray,   // 1/min, NaN where not paddling
    val pitchDeg: DoubleArray,
    val rollDeg: DoubleArray,
    val mode: Array<ImuMode>,
    val seThreshold: Double,
    val concThreshold: Double,
    val glideSec: Int,
    val paddleSec: Int,
    val waitSec: Int,
    val foilRuns: Int,
    val medianStrokeRate: Double,
    val fs: Double,
    val durationMin: Double,
    val sourceName: String,
    /** Wall-clock epoch ms of the grid's t=0 (from the CSV's `# SYNC` anchor); null for legacy files. */
    val startEpochMs: Long?,
)

object ImuAnalysis {
    const val LO = 0.35
    const val HI = 1.30
    const val TOT_LO = 0.10
    const val TOT_HI = 4.0
    const val PROM_LO = 0.20
    const val PROM_HI = 2.0
    const val FOIL_KMH = 12.0
    const val DEC_TARGET_HZ = 25.0

    class AnalysisException(msg: String) : Exception(msg)

    fun fromPhoneLogger(sensFile: File, gpsFile: File): ImuAnalysisResult {
        // Parse straight into primitive DoubleArrays — a 127 MB / 1.6 M-row file
        // as a List<SensorRow> is ~190 MB of objects and OOMs Android's ~268 MB
        // heap. (ticks are reused in place as seconds; accel arrays are reused
        // in place as userAccel.)
        val s = parsePhoneSens(sensFile)
        val n = s.ticks.size
        if (n <= 200) throw AnalysisException("Recording too short to analyze.")
        val sec = s.ticks
        for (i in 0 until n) sec[i] *= 0.01
        val fs = sampleRate(sec)
        if (!fs.isFinite() || fs <= 1) throw AnalysisException("Cannot infer sample rate.")
        val ux = s.ax; val uy = s.ay; val uz = s.az
        for (i in 0 until n) { ux[i] /= 1000.0; uy[i] /= 1000.0; uz[i] /= 1000.0 }
        val gw = maxOf(1, Math.round(2.0 * fs).toInt())
        val gx = movAvg(ux, gw); val gy = movAvg(uy, gw); val gz = movAvg(uz, gw)
        for (i in 0 until n) { ux[i] -= gx[i]; uy[i] -= gy[i]; uz[i] -= gz[i] }  // raw -> userAccel in place

        val gps = runCatching { CsvParsers.parseGpsFile(gpsFile) }.getOrDefault(emptyList())
        val gpsSec = DoubleArray(gps.size) { gps[it].ticks * 0.01 }
        val gpsSpd = DoubleArray(gps.size) { clampSpeed(gps[it].speedKmhModule) }

        // Wall-clock anchor: the `# SYNC epoch_ms=… tick_ms=…` marker maps the
        // tick clock to phone epoch; project it onto the first sample so the
        // graphs can label the x axis with real time-of-day.
        val startEpochMs = CsvParsers.parseSyncAnchorsFile(sensFile).firstOrNull()?.let { a ->
            (a.epochMs + (sec[0] - a.ticks * 0.01) * 1000.0).toLong()
        }

        return core(ux, uy, uz, sec, fs, gx, gy, gz, gpsSec, gpsSpd, sensFile.name, startEpochMs)
    }

    private class SensArrays(val ticks: DoubleArray, val ax: DoubleArray, val ay: DoubleArray, val az: DoubleArray)

    /** Two-pass streaming parser: count data rows, allocate exact-size arrays,
     *  fill. Never holds the file as lines or as row objects. Columns are the
     *  first four (Time, AccX, AccY, AccZ) in both firmware schemas. */
    private fun parsePhoneSens(file: File): SensArrays {
        var tickDiv = 1.0
        file.bufferedReader().use { br ->
            val first = (br.readLine() ?: "").substringBefore(',').trim()
            if (first == "ms") tickDiv = 10.0
        }
        fun isData(line: String) = line.isNotEmpty() && (line[0] == '-' || line[0] in '0'..'9')
        var n = 0
        file.bufferedReader().useLines { seq -> seq.forEach { if (isData(it)) n++ } }
        val ticks = DoubleArray(n); val ax = DoubleArray(n); val ay = DoubleArray(n); val az = DoubleArray(n)
        var i = 0
        file.bufferedReader().useLines { seq ->
            seq.forEach { line ->
                if (i < n && isData(line)) {
                    val f = line.split(",", limit = 5)
                    if (f.size >= 4) {
                        ticks[i] = (f[0].toDoubleOrNull() ?: 0.0) / tickDiv
                        ax[i] = f[1].toDoubleOrNull() ?: 0.0
                        ay[i] = f[2].toDoubleOrNull() ?: 0.0
                        az[i] = f[3].toDoubleOrNull() ?: 0.0
                        i++
                    }
                }
            }
        }
        return if (i == n) SensArrays(ticks, ax, ay, az)
        else SensArrays(ticks.copyOf(i), ax.copyOf(i), ay.copyOf(i), az.copyOf(i))
    }

    private fun core(
        ux: DoubleArray, uy: DoubleArray, uz: DoubleArray, sampleSec: DoubleArray, fs: Double,
        gx: DoubleArray, gy: DoubleArray, gz: DoubleArray,
        gpsSec: DoubleArray, gpsSpeed: DoubleArray, sourceName: String,
        startEpochMs: Long? = null,
    ): ImuAnalysisResult {
        val n = ux.size
        val dur = sampleSec[n - 1] - sampleSec[0]
        if (dur <= 20) throw AnalysisException("Recording too short to analyze.")
        val T = Math.floor(dur).toInt()
        val secs = DoubleArray(T + 1) { it.toDouble() }
        val base = sampleSec[0]

        // per-second GPS speed (both time bases shifted to start = 0)
        val gpsRel = DoubleArray(gpsSec.size) { gpsSec[it] - base }
        val speed = interp(secs, gpsRel, gpsSpeed, 0.0, 0.0)

        // decimate userAccel to ~DEC_TARGET_HZ for the STFT
        val dec = maxOf(1, Math.round(fs / DEC_TARGET_HZ).toInt())
        val fsA = fs / dec
        val dx = blockMean(ux, dec); val dy = blockMean(uy, dec); val dz = blockMean(uz, dec)
        val mA = dx.size

        // STFT features (rhythmicity + prominence + cadence)
        val nfft = nextPow2(Math.round(10.0 * fsA).toInt())
        val win = minOf(nfft, mA)
        val hop = maxOf(1, Math.round(fsA).toInt())
        val hann = hannWindow(win)
        val kLo = Math.floor(LO * nfft / fsA).toInt()
        val kHi = Math.ceil(HI * nfft / fsA).toInt()
        val ktLo = Math.floor(TOT_LO * nfft / fsA).toInt()
        val ktHi = Math.ceil(TOT_HI * nfft / fsA).toInt()
        val kpLo = Math.floor(PROM_LO * nfft / fsA).toInt()
        val kpHi = Math.ceil(PROM_HI * nfft / fsA).toInt()
        val nyq = nfft / 2

        val winCenter = ArrayList<Double>()
        val promV = ArrayList<Double>(); val concV = ArrayList<Double>(); val peakF = ArrayList<Double>()
        val re = DoubleArray(nfft); val im = DoubleArray(nfft)
        val psd = DoubleArray(nyq + 1)
        var start = 0
        while (start + win <= mA) {
            for (k in 0..nyq) psd[k] = 0.0
            for (axis in 0..2) {
                val src = if (axis == 0) dx else if (axis == 1) dy else dz
                var mean = 0.0
                for (i in 0 until win) mean += src[start + i]
                mean /= win
                for (i in 0 until nfft) {
                    re[i] = if (i < win) (src[start + i] - mean) * hann[i] else 0.0
                    im[i] = 0.0
                }
                fft(re, im)
                for (k in 0..nyq) psd[k] += re[k] * re[k] + im[k] * im[k]
            }
            var bp = 0.0; var tp = 0.0; var pk = 0.0; var pkK = kLo
            for (k in maxOf(1, ktLo)..minOf(nyq, ktHi)) tp += psd[k]
            for (k in maxOf(1, kLo)..minOf(nyq, kHi)) { if (psd[k] > pk) { pk = psd[k]; pkK = k }; bp += psd[k] }
            val pband = ArrayList<Double>()
            for (k in maxOf(1, kpLo)..minOf(nyq, kpHi)) pband.add(psd[k])
            val med = median(pband)
            promV.add(if (med > 0) pk / med else 0.0)
            concV.add(if (tp > 0) bp / tp else 0.0)
            peakF.add(pkK * fsA / nfft)
            winCenter.add((start + win / 2.0) / fsA)
            start += hop
        }
        val wc = winCenter.toDoubleArray()
        val conc = interp(secs, wc, concV.toDoubleArray(), 0.0, 0.0)
        val prom = interp(secs, wc, promV.toDoubleArray(), 0.0, 0.0)
        val strokeF = interp(secs, wc, peakF.toDoubleArray(), Double.NaN, Double.NaN)

        // stroke energy: time-domain bandpass RMS (near-zero on a still tail)
        val energy = bandpassEnergyPerSec(ux, uy, uz, sampleSec, secs, fs)

        val (pitch, roll) = boardAngles(gx, gy, gz, sampleSec, secs)

        // thresholds
        val slowLogE = ArrayList<Double>()
        for (i in secs.indices) if (speed[i] < FOIL_KMH) slowLogE.add(log10(energy[i] + 1e-6))
        val seThr = Math.pow(10.0, otsu(slowLogE))
        val concEnergetic = ArrayList<Double>(); val promEnergetic = ArrayList<Double>()
        for (i in secs.indices) if (speed[i] < FOIL_KMH && energy[i] >= seThr) {
            concEnergetic.add(conc[i]); promEnergetic.add(prom[i])
        }
        val concThr = maxOf(0.30, if (concEnergetic.size > 8) otsu(concEnergetic) else 0.30)
        val promThr = maxOf(4.0, if (promEnergetic.size > 8) otsu(promEnergetic) else 4.0)

        // classify
        var mode = IntArray(secs.size) { i ->
            when {
                speed[i] >= FOIL_KMH -> 0
                energy[i] >= seThr && (conc[i] >= concThr || prom[i] >= promThr) -> 1
                else -> 2
            }
        }
        mode = medianFilter(mode, 11)
        mode = absorbShortRuns(mode, 15)

        var glide = 0; var paddle = 0; var wait = 0
        for (m in mode) when (m) { 0 -> glide++; 1 -> paddle++; else -> wait++ }
        var foilRuns = 0
        for (i in mode.indices) if (mode[i] == 0 && (i == 0 || mode[i - 1] != 0)) foilRuns++
        val srSamples = ArrayList<Double>()
        for (i in mode.indices) if (mode[i] == 1 && strokeF[i].isFinite()) srSamples.add(strokeF[i] * 60)
        val medSr = median(srSamples)
        val strokeRate = DoubleArray(mode.size) { if (mode[it] == 1 && strokeF[it].isFinite()) strokeF[it] * 60 else Double.NaN }

        return ImuAnalysisResult(
            minutes = DoubleArray(secs.size) { secs[it] / 60.0 },
            speedKmh = speed, strokeEnergy = energy, rhythmicity = conc, strokeRate = strokeRate,
            pitchDeg = pitch, rollDeg = roll,
            mode = Array(mode.size) { ImuMode.values()[mode[it]] },
            seThreshold = seThr, concThreshold = concThr,
            glideSec = glide, paddleSec = paddle, waitSec = wait,
            foilRuns = foilRuns, medianStrokeRate = medSr,
            fs = fs, durationMin = dur / 60.0, sourceName = sourceName,
            startEpochMs = startEpochMs,
        )
    }

    private fun bandpassEnergyPerSec(
        ux: DoubleArray, uy: DoubleArray, uz: DoubleArray,
        sampleSec: DoubleArray, secs: DoubleArray, fs: Double,
    ): DoubleArray {
        val wHi = maxOf(2, Math.round(0.443 * fs / HI).toInt())
        val wLo = maxOf(3, Math.round(0.443 * fs / LO).toInt())
        fun bp(u: DoubleArray): DoubleArray {
            val lpHi = movAvg(u, wHi); val lpLo = movAvg(u, wLo)
            return DoubleArray(u.size) { lpHi[it] - lpLo[it] }
        }
        val bx = bp(ux); val by = bp(uy); val bz = bp(uz)
        val pw = DoubleArray(ux.size) { bx[it] * bx[it] + by[it] * by[it] + bz[it] * bz[it] }
        val sm = movAvg(pw, maxOf(1, Math.round(4.0 * fs).toInt()))
        val base = sampleSec[0]
        val sum = DoubleArray(secs.size); val cnt = IntArray(secs.size)
        for (i in ux.indices) {
            val idx = Math.floor(sampleSec[i] - base).toInt()
            if (idx in secs.indices) { sum[idx] += sm[i]; cnt[idx]++ }
        }
        val out = DoubleArray(secs.size)
        for (i in secs.indices) out[i] = if (cnt[i] > 0) sqrt(sum[i] / cnt[i]) else if (i > 0) out[i - 1] else 0.0
        return out
    }

    private fun boardAngles(
        gx: DoubleArray, gy: DoubleArray, gz: DoubleArray, sampleSec: DoubleArray, secs: DoubleArray,
    ): Pair<DoubleArray, DoubleArray> {
        val gmx = median(gx.toList()); val gmy = median(gy.toList()); val gmz = median(gz.toList())
        val gn = sqrt(gmx * gmx + gmy * gmy + gmz * gmz)
        val m = if (gn > 0) doubleArrayOf(gmx / gn, gmy / gn, gmz / gn) else doubleArrayOf(0.0, 0.0, -1.0)
        val down = doubleArrayOf(0.0, 0.0, -1.0)
        val axis = cross(m, down)
        val s = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
        val c = dot(m, down)
        val R = Array(3) { i -> DoubleArray(3) { j -> if (i == j) 1.0 else 0.0 } }
        if (s > 1e-9) {
            val a = doubleArrayOf(axis[0] / s, axis[1] / s, axis[2] / s)
            val K = arrayOf(
                doubleArrayOf(0.0, -a[2], a[1]),
                doubleArrayOf(a[2], 0.0, -a[0]),
                doubleArrayOf(-a[1], a[0], 0.0),
            )
            val K2 = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> K[i][k] * K[k][j] } } }
            for (i in 0..2) for (j in 0..2) R[i][j] = (if (i == j) 1.0 else 0.0) + s * K[i][j] + (1 - c) * K2[i][j]
        }
        val pitch = DoubleArray(secs.size); val roll = DoubleArray(secs.size)
        val base = sampleSec[0]
        val sx = DoubleArray(secs.size); val sy = DoubleArray(secs.size); val sz = DoubleArray(secs.size)
        val sn = IntArray(secs.size)
        for (i in gx.indices) {
            val idx = Math.floor(sampleSec[i] - base).toInt()
            if (idx in secs.indices) { sx[idx] += gx[i]; sy[idx] += gy[i]; sz[idx] += gz[i]; sn[idx]++ }
        }
        for (i in secs.indices) {
            if (sn[i] == 0) { pitch[i] = if (i > 0) pitch[i - 1] else 0.0; roll[i] = if (i > 0) roll[i - 1] else 0.0; continue }
            val vx = sx[i] / sn[i]; val vy = sy[i] / sn[i]; val vz = sz[i] / sn[i]
            val rx = R[0][0] * vx + R[0][1] * vy + R[0][2] * vz
            val ry = R[1][0] * vx + R[1][1] * vy + R[1][2] * vz
            val rz = R[2][0] * vx + R[2][1] * vy + R[2][2] * vz
            pitch[i] = atan2(ry, -rz) * 180.0 / Math.PI
            roll[i] = atan2(rx, -rz) * 180.0 / Math.PI
        }
        return Pair(pitch, roll)
    }

    // ---- DSP / math helpers ----

    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        if (n <= 1) return
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = i + k + len / 2
                    val tr = cr * re[b] - ci * im[b]
                    val ti = cr * im[b] + ci * re[b]
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun log10(x: Double) = ln(x) / ln(10.0)
    private fun nextPow2(x: Int): Int { var p = 1; while (p < x) p = p shl 1; return maxOf(2, p) }
    private fun hannWindow(n: Int) = DoubleArray(n) { 0.5 - 0.5 * cos(2 * Math.PI * it / maxOf(1, n - 1)) }
    private fun movAvg(x: DoubleArray, w: Int): DoubleArray {
        val n = x.size
        if (w <= 1 || n == 0) return x
        val half = w / 2
        val out = DoubleArray(n)   // sliding running sum, no prefix-sum array
        var sum = 0.0; var curLo = 0; var curHi = -1
        for (i in 0 until n) {
            val wlo = maxOf(0, i - half); val whi = minOf(n - 1, i + half)
            while (curHi < whi) { curHi++; sum += x[curHi] }
            while (curLo < wlo) { sum -= x[curLo]; curLo++ }
            out[i] = sum / (whi - wlo + 1)
        }
        return out
    }
    private fun blockMean(x: DoubleArray, dec: Int): DoubleArray {
        if (dec <= 1) return x
        val m = x.size / dec
        return DoubleArray(m) { i -> var s = 0.0; for (k in 0 until dec) s += x[i * dec + k]; s / dec }
    }
    private fun sampleRate(sec: DoubleArray): Double {
        if (sec.size <= 10) return Double.NaN
        val d = ArrayList<Double>()
        for (i in 1 until minOf(sec.size, 2000)) { val dt = sec[i] - sec[i - 1]; if (dt > 0) d.add(dt) }
        val m = median(d)
        return if (m > 0) 1.0 / m else Double.NaN
    }
    private fun clampSpeed(v: Double) = if (v.isFinite() && v in 0.0..60.0) v else Double.NaN

    private fun interp(xs: DoubleArray, xp: DoubleArray, fp: DoubleArray, leftFill: Double, rightFill: Double): DoubleArray {
        val ax = ArrayList<Double>(); val af = ArrayList<Double>()
        for (i in xp.indices) if (xp[i].isFinite() && fp[i].isFinite()) { ax.add(xp[i]); af.add(fp[i]) }
        if (ax.isEmpty()) return DoubleArray(xs.size) { leftFill }
        val out = DoubleArray(xs.size)
        var j = 0
        for (i in xs.indices) {
            val x = xs[i]
            when {
                x < ax.first() -> out[i] = leftFill
                x > ax.last() -> out[i] = rightFill
                else -> {
                    while (j + 1 < ax.size && ax[j + 1] < x) j++
                    val x0 = ax[j]; val x1 = ax[j + 1]; val y0 = af[j]; val y1 = af[j + 1]
                    out[i] = if (x1 > x0) y0 + (y1 - y0) * (x - x0) / (x1 - x0) else y0
                }
            }
        }
        return out
    }

    private fun median(a: List<Double>): Double {
        val v = a.filter { it.isFinite() }.sorted()
        if (v.isEmpty()) return Double.NaN
        val n = v.size
        return if (n % 2 == 1) v[n / 2] else (v[n / 2 - 1] + v[n / 2]) / 2
    }
    private fun otsu(vals: List<Double>): Double {
        val v = vals.filter { it.isFinite() }
        if (v.size < 8) return if (v.isEmpty()) 0.0 else median(v)
        val lo = v.min(); val hi = v.max()
        if (hi <= lo) return lo
        val bins = 64
        val hist = DoubleArray(bins)
        for (x in v) { var b = ((x - lo) / (hi - lo) * bins).toInt(); if (b >= bins) b = bins - 1; if (b < 0) b = 0; hist[b]++ }
        val total = v.size.toDouble()
        for (i in 0 until bins) hist[i] /= total
        val centers = DoubleArray(bins) { lo + (it + 0.5) * (hi - lo) / bins }
        var muT = 0.0; for (i in 0 until bins) muT += hist[i] * centers[i]
        var w = 0.0; var mu = 0.0; var best = -1.0; var bestK = 0
        for (i in 0 until bins) {
            w += hist[i]; mu += hist[i] * centers[i]
            val wf = 1 - w
            if (w <= 0 || wf <= 0) continue
            val sb = (muT * w - mu) * (muT * w - mu) / (w * wf)
            if (sb > best) { best = sb; bestK = i }
        }
        return centers[bestK]
    }
    private fun medianFilter(x: IntArray, w: Int): IntArray {
        val n = x.size; val half = w / 2
        val out = x.copyOf()
        for (i in 0 until n) {
            val lo = maxOf(0, i - half); val hi = minOf(n - 1, i + half)
            val counts = intArrayOf(0, 0, 0)
            for (k in lo..hi) if (x[k] in 0..2) counts[x[k]]++
            out[i] = if (counts[0] >= counts[1] && counts[0] >= counts[2]) 0 else if (counts[1] >= counts[2]) 1 else 2
        }
        return out
    }
    private fun absorbShortRuns(x: IntArray, minLen: Int): IntArray {
        val m = x.copyOf(); var i = 0
        while (i < m.size) {
            var j = i
            while (j + 1 < m.size && m[j + 1] == m[i]) j++
            if (j - i + 1 < minLen && i > 0) for (k in i..j) m[k] = m[i - 1]
            i = j + 1
        }
        return m
    }
    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
