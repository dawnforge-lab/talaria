package lab.dawnforge.hermes.core.data.phonedata.location

import kotlin.math.max
import kotlin.math.min

/** The phone's power and data savers, which make routine sends wait longer. */
data class DeviceConditions(val powerSaver: Boolean = false, val dataSaver: Boolean = false) {
    /** How many times longer routine sends wait: the power saver wins over the data saver. */
    val stretch: Double get() = if (powerSaver) {
        POWER_STRETCH
    } else if (dataSaver) {
        DATA_STRETCH
    } else {
        1.0
    }

    private companion object {
        const val POWER_STRETCH = 3.0
        const val DATA_STRETCH = 2.0
    }
}

/**
 * The last fix that reached the host with a message or a call. In memory only: what's stored on the
 * phone never holds a location the host already has.
 */
data class TalkingUpload(val fix: RawFix, val precision: LocationPrecision, val host: Host, val sentAtMs: Long) {
    /** The host's copy it went to: a new key or store drops the latest fix. */
    data class Host(val keyAgreements: Int, val generation: String?)
}

/**
 * When a message's fix is taken and sent, as iOS's `ContextUploadPolicy`: a fix the phone already
 * has answers a message while it's fresh, and the same place isn't sent again with every message.
 */
object LocationPolicy {
    /** A fix the phone has answers a message when it's this recent and the phone was no faster than a walk. */
    const val REUSE_AGE_MS = 5 * 60_000L

    /** What the phone's own cache answers anyway. */
    const val FRESH_AGE_MS = 30_000L
    private const val WALKING_SPEED = 2.0

    /** A fix with no speed can't say it was still: reused only younger and sharper than this. */
    private const val UNKNOWN_SPEED_AGE_MS = 2 * 60_000L
    private const val UNKNOWN_SPEED_ACCURACY = 65.0

    /** The same place goes again no more often than this (stretched by the savers, up to the longest). */
    private const val REPEAT_MS = 10 * 60_000L
    private const val REPEAT_LONGEST_MS = 30 * 60_000L

    /** A move smaller than this, or than either fix's accuracy, isn't one. */
    private const val MOVE_M = 100.0

    /** Whether a fix the phone already has can go with a message instead of a new one. */
    fun reusesFix(ageMs: Long, accuracy: Double, speed: Double?, precision: LocationPrecision): Boolean {
        if (ageMs < 0 || accuracy < 0 || accuracy > LocationFixes.acceptableAccuracy(precision)) return false
        if (ageMs <= FRESH_AGE_MS) return true
        if (speed != null) return ageMs <= REUSE_AGE_MS && speed <= WALKING_SPEED
        // Speed unknown: only a sharp, recent fix (Wi-Fi indoors, say).
        return ageMs <= UNKNOWN_SPEED_AGE_MS && accuracy <= UNKNOWN_SPEED_ACCURACY
    }

    /** Whether a message's fix tells the host something the last one sent with a message or call didn't. */
    fun messageFixIsNews(
        fix: RawFix,
        precision: LocationPrecision,
        host: TalkingUpload.Host,
        last: TalkingUpload?,
        nowMs: Long,
        conditions: DeviceConditions,
    ): Boolean {
        if (last == null || last.precision != precision || last.host != host) return true
        val elapsed = nowMs - last.sentAtMs
        val repeatAfter = min(REPEAT_MS * conditions.stretch, REPEAT_LONGEST_MS.toDouble())
        if (elapsed < 0 || elapsed >= repeatAfter) return true
        // Much sharper than the last one (a GPS fix after a cell one).
        if (last.fix.accuracy > MOVE_M && fix.accuracy < last.fix.accuracy / 2) return true
        val moved = LocationGeo.distance(fix.latitude, fix.longitude, last.fix.latitude, last.fix.longitude)
        return moved > max(MOVE_M, max(fix.accuracy, last.fix.accuracy))
    }
}
