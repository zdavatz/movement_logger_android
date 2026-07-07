package ch.ywesee.movementlogger.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Box-persisted board-orientation calibration blob (firmware v0.0.37+).
 *
 * Wire format + semantics: firmware `DESIGN.md` →
 * *Box-persisted calibration (`CAL_GET` / `CAL_SET`)*. Ported 1:1 from the
 * desktop reference (`stbox-viz-gui/src/calibration.rs`) so a "Zero here" /
 * nosePlusY / heading-bias set on ANY host survives on the next connect
 * from a different one.
 *
 * Two responsibilities:
 * - [decode] — parse a 32-byte CAL_GET reply into per-field nullable values.
 *   A `null` field means "the box has NOT set this yet (valid_mask bit
 *   clear)" — the caller should fall back to its local `AgentConfig` /
 *   defaults for that one field.
 * - [encode] — build a 32-byte payload for CAL_SET. Only fields passed as
 *   non-null in the [EncodeInput] have their `valid_mask` bit set; the
 *   box's merge leaves unset fields alone so a host can push a single new
 *   value without knowing the box's current others.
 */
object Calibration {
    const val BLOB_SIZE: Int = 32
    const val LAYOUT_VERSION: Byte = 0x01

    const val MASK_NOSE_PLUS_Y: Int = 0x01
    const val MASK_MAG_OFFSET: Int = 0x02
    const val MASK_ANGLE_ZERO: Int = 0x04
    const val MASK_HEADING_BIAS: Int = 0x08

    /**
     * The decoded calibration as the app models it. A `null` per field means
     * "box didn't have this yet" — the local `AgentConfig` value stands.
     */
    class DecodedCal(
        val nosePlusY: Boolean? = null,
        /** Hard-iron offset in mg per axis (X, Y, Z). */
        val magOffsetMg: DoubleArray? = null,
        /** [pitch, roll, yaw] in degrees. */
        val angleZeroRef: DoubleArray? = null,
        /**
         * Unix epoch ms when "Zero here" was captured; `null` if never
         * zeroed. Distinct from [angleZeroRef] being `null` (which means
         * the whole zero-tare bit is clear) — this one is `null` when the
         * bit IS set but the box has no wall-clock stamp yet (the 0
         * sentinel).
         */
        val angleZeroAtEpochMs: Long? = null,
        val headingBiasDeg: Double? = null,
    )

    /**
     * Fields to include in a `CAL_SET` write. Any non-null value sets its
     * valid_mask bit; the box's merge overwrites just those fields.
     */
    class EncodeInput(
        val nosePlusY: Boolean? = null,
        val magOffsetMg: DoubleArray? = null,
        /**
         * Pair the ref with the epoch — a non-null `angleZeroRef` implies
         * "the zero-tare bit is being set" so the epoch (defaults to 0 if
         * not passed) travels alongside in the same 8-byte slot.
         */
        val angleZeroRef: DoubleArray? = null,
        val angleZeroAtEpochMs: Long? = null,
        val headingBiasDeg: Double? = null,
    )

    /**
     * Encode a partial-update CAL_SET payload. Returns exactly [BLOB_SIZE]
     * bytes (any field passed as `null` is zero-filled AND its valid_mask
     * bit is cleared, so the box's merge leaves that field untouched).
     */
    fun encode(input: EncodeInput): ByteArray {
        val b = ByteArray(BLOB_SIZE)
        b[0] = LAYOUT_VERSION
        var mask = 0

        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

        input.nosePlusY?.let { pos ->
            mask = mask or MASK_NOSE_PLUS_Y
            b[2] = if (pos) 1 else 0
        }
        input.magOffsetMg?.let { mo ->
            require(mo.size == 3) { "magOffsetMg must have 3 elements" }
            mask = mask or MASK_MAG_OFFSET
            for (i in 0 until 3) {
                buf.putShort(4 + i * 2, clampI16(mo[i]))
            }
        }
        if (input.angleZeroRef != null) {
            require(input.angleZeroRef.size == 3) { "angleZeroRef must have 3 elements" }
            mask = mask or MASK_ANGLE_ZERO
            for (i in 0 until 3) {
                // Tenths of a degree: ±3276.7° range, plenty.
                buf.putShort(10 + i * 2, clampI16(input.angleZeroRef[i] * 10.0))
            }
            // Epoch travels in the same "zero-tare" bit. Missing epoch → 0
            // (the layout's own "never zeroed" sentinel), which is
            // deliberately DIFFERENT from "there IS an epoch = 0".
            val epoch = input.angleZeroAtEpochMs ?: 0L
            buf.putLong(16, epoch)
        }
        input.headingBiasDeg?.let { bd ->
            mask = mask or MASK_HEADING_BIAS
            buf.putShort(24, clampI16(bd * 10.0))
        }

        b[1] = (mask and 0xFF).toByte()
        return b
    }

    /**
     * Decode a 32-byte `CAL_GET` reply. Fields whose valid_mask bit is
     * clear come back as `null` — caller falls back to its own local
     * `AgentConfig`. Returns `null` on the two malformed cases (short blob
     * or unknown layout version) — legacy firmware doesn't reply at all,
     * so we don't see stale layouts here.
     */
    fun decode(blob: ByteArray): DecodedCal? {
        if (blob.size != BLOB_SIZE) return null
        if (blob[0] != LAYOUT_VERSION) return null
        val mask = blob[1].toInt() and 0xFF
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)

        val nose = if (mask and MASK_NOSE_PLUS_Y != 0) blob[2].toInt() != 0 else null
        val magOff = if (mask and MASK_MAG_OFFSET != 0) {
            DoubleArray(3) { i -> buf.getShort(4 + i * 2).toDouble() }
        } else null
        val angleRef: DoubleArray?
        val angleAt: Long?
        if (mask and MASK_ANGLE_ZERO != 0) {
            angleRef = DoubleArray(3) { i -> buf.getShort(10 + i * 2).toDouble() / 10.0 }
            val e = buf.getLong(16)
            // 0 = "the zero-tare bit is set but the box has no wall-clock
            // stamp yet" — treat as `null` so the UI can decide whether to
            // display a "zeroed just now" note or omit it.
            angleAt = if (e == 0L) null else e
        } else {
            angleRef = null
            angleAt = null
        }
        val headingBias = if (mask and MASK_HEADING_BIAS != 0) {
            buf.getShort(24).toDouble() / 10.0
        } else null

        return DecodedCal(
            nosePlusY = nose,
            magOffsetMg = magOff,
            angleZeroRef = angleRef,
            angleZeroAtEpochMs = angleAt,
            headingBiasDeg = headingBias,
        )
    }

    private fun clampI16(x: Double): Short {
        val r = kotlin.math.round(x)
        return when {
            r >= Short.MAX_VALUE.toDouble() -> Short.MAX_VALUE
            r <= Short.MIN_VALUE.toDouble() -> Short.MIN_VALUE
            else -> r.toInt().toShort()
        }
    }
}
