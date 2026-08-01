package ch.ywesee.movementlogger.data

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Disk cache for [ImuAnalysisResult] so re-opening an analyzed recording is
 * instant instead of re-running the ~30 s pipeline. One binary file per
 * recording under `filesDir/analysis-cache/`, keyed by the source files'
 * (size, mtime) so a regrown or re-recorded file re-analyzes. Survives app
 * restarts; any read problem (schema bump, truncation) is a plain cache miss.
 */
object ImuAnalysisCache {
    private const val MAGIC = 0x494D5541 // "IMUA"
    private const val VERSION = 1
    private const val MAX_FILES = 32

    private fun dir(ctx: Context) = File(ctx.filesDir, "analysis-cache")
    private fun cacheFile(ctx: Context, sens: File) = File(dir(ctx), sens.name + ".bin")

    fun load(ctx: Context, sens: File, gps: File?): ImuAnalysisResult? {
        val f = cacheFile(ctx, sens)
        if (!f.isFile) return null
        return try {
            DataInputStream(f.inputStream().buffered()).use { s ->
                if (s.readInt() != MAGIC || s.readInt() != VERSION) return null
                if (s.readLong() != sens.length() || s.readLong() != sens.lastModified()) return null
                if (s.readLong() != (gps?.length() ?: 0L)) return null
                val seThreshold = s.readDouble()
                val concThreshold = s.readDouble()
                val glideSec = s.readInt()
                val paddleSec = s.readInt()
                val waitSec = s.readInt()
                val foilRuns = s.readInt()
                val medianStrokeRate = s.readDouble()
                val fs = s.readDouble()
                val durationMin = s.readDouble()
                val sourceName = s.readUTF()
                val n = s.readInt()
                if (n < 0 || n > 2_000_000) return null
                fun arr() = DoubleArray(n) { s.readDouble() }
                val minutes = arr(); val speedKmh = arr(); val strokeEnergy = arr()
                val rhythmicity = arr(); val strokeRate = arr()
                val pitchDeg = arr(); val rollDeg = arr()
                val modes = ImuMode.entries
                val mode = Array(n) { modes[s.readUnsignedByte().coerceIn(0, modes.size - 1)] }
                ImuAnalysisResult(
                    minutes, speedKmh, strokeEnergy, rhythmicity, strokeRate, pitchDeg, rollDeg,
                    mode, seThreshold, concThreshold, glideSec, paddleSec, waitSec, foilRuns,
                    medianStrokeRate, fs, durationMin, sourceName,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun store(ctx: Context, sens: File, gps: File?, r: ImuAnalysisResult) {
        val n = r.minutes.size
        val sameLen = intArrayOf(
            r.speedKmh.size, r.strokeEnergy.size, r.rhythmicity.size,
            r.strokeRate.size, r.pitchDeg.size, r.rollDeg.size, r.mode.size,
        ).all { it == n }
        if (!sameLen) return
        try {
            val d = dir(ctx).apply { mkdirs() }
            val tmp = File(d, sens.name + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { s ->
                s.writeInt(MAGIC); s.writeInt(VERSION)
                s.writeLong(sens.length()); s.writeLong(sens.lastModified())
                s.writeLong(gps?.length() ?: 0L)
                s.writeDouble(r.seThreshold); s.writeDouble(r.concThreshold)
                s.writeInt(r.glideSec); s.writeInt(r.paddleSec); s.writeInt(r.waitSec); s.writeInt(r.foilRuns)
                s.writeDouble(r.medianStrokeRate); s.writeDouble(r.fs); s.writeDouble(r.durationMin)
                s.writeUTF(r.sourceName)
                s.writeInt(n)
                for (a in arrayOf(r.minutes, r.speedKmh, r.strokeEnergy, r.rhythmicity, r.strokeRate, r.pitchDeg, r.rollDeg)) {
                    for (v in a) s.writeDouble(v)
                }
                for (m in r.mode) s.writeByte(m.ordinal)
            }
            if (!tmp.renameTo(cacheFile(ctx, sens))) tmp.delete()
            prune(d)
        } catch (_: Exception) {
            // cache write is best-effort — analysis result is already on screen
        }
    }

    private fun prune(d: File) {
        val files = d.listFiles { f -> f.name.endsWith(".bin") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }
}
