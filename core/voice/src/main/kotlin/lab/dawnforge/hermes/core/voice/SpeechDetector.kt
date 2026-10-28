package lab.dawnforge.hermes.core.voice

/**
 * Speech detection while listening, ported from the iOS app's adaptive detector. It learns the
 * room's noise floor from the current capture and accepts speech that rises clearly above it,
 * never becoming more sensitive than the fixed barge-in threshold. Levels are linear peaks, 0–1.
 */
class SpeechDetector {
    enum class Detection { None, Continued, Started }

    var noiseFloor = MIN_NOISE_FLOOR
        private set
    var isSpeechActive = false
        private set
    private var coldStartRemaining = COLD_START_EVENTS
    private val coldStartWindow = ArrayDeque<Float>()
    private var candidates = 0

    /** The floor-relative level that starts speech, between the minimum and the barge-in ceiling. */
    private val startThreshold: Float
        get() = maxOf(MIN_START, minOf(MAX_START, noiseFloor * START_RISE))

    /** The lower level that keeps an utterance alive through dips; never above the start threshold. */
    private val continueThreshold: Float
        get() = minOf(startThreshold, maxOf(MIN_CONTINUE, noiseFloor * CONTINUE_RISE))

    fun observe(level: Float): Detection {
        if (!level.isFinite() || level < 0f) {
            candidates = 0
            return Detection.None
        }
        if (isSpeechActive) return if (level >= continueThreshold) Detection.Continued else Detection.None
        if (coldStartRemaining > 0) return coldStart(level)
        if (level >= MAX_START) return start()
        if (level < startThreshold) {
            // Ambience teaches the floor; a pending candidate was a spike.
            candidates = 0
            adapt(level, if (level < noiseFloor) FALL_ALPHA else RISE_ALPHA, MAX_NOISE_FLOOR)
            return Detection.None
        }
        // A suspected onset: counted, never learned as noise.
        candidates++
        return if (candidates >= START_DEBOUNCE) start() else Detection.None
    }

    private fun coldStart(level: Float): Detection {
        coldStartRemaining--
        // An unambiguous level is speech before the floor is known.
        if (level >= MAX_START) return start()
        if (level < MIN_START) {
            adapt(level, COLD_QUIET_ALPHA, MIN_START)
            coldStartWindow.clear()
            return Detection.None
        }
        // Speech-plausible input at cold start: speech varies, steady ambience doesn't.
        coldStartWindow.addLast(level)
        if (coldStartWindow.size > COLD_CANDIDATES) coldStartWindow.removeFirst()
        if (coldStartWindow.size == COLD_CANDIDATES &&
            coldStartWindow.max() - coldStartWindow.min() >= COLD_DYNAMIC_RANGE
        ) {
            coldStartWindow.clear()
            return start()
        }
        if (coldStartRemaining == 0 && coldStartWindow.isNotEmpty()) {
            // A steady run when cold start ends is the room: its mean is the floor.
            noiseFloor = coldStartWindow.average().toFloat().coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
            coldStartWindow.clear()
        }
        return Detection.None
    }

    private fun start(): Detection {
        isSpeechActive = true
        candidates = 0
        return Detection.Started
    }

    /** The utterance ended: the next one starts from silence, with the floor learned so far. */
    fun endUtterance() {
        isSpeechActive = false
        candidates = 0
    }

    /** Forgets everything, so nothing from one capture shapes the next. */
    fun reset() {
        noiseFloor = MIN_NOISE_FLOOR
        coldStartRemaining = COLD_START_EVENTS
        coldStartWindow.clear()
        candidates = 0
        isSpeechActive = false
    }

    private fun adapt(level: Float, alpha: Float, ceiling: Float) {
        noiseFloor = (noiseFloor + (level - noiseFloor) * alpha).coerceIn(MIN_NOISE_FLOOR, ceiling)
    }

    companion object {
        const val START_RISE = 3f
        const val MIN_START = 0.012f

        /** The fixed barge-in threshold: at or above it is always speech. */
        const val MAX_START = 0.075f
        const val CONTINUE_RISE = 1.6f
        const val MIN_CONTINUE = 0.006f
        const val START_DEBOUNCE = 2
        const val COLD_START_EVENTS = 16
        const val COLD_CANDIDATES = 4
        const val COLD_DYNAMIC_RANGE = 0.012f
        const val COLD_QUIET_ALPHA = 0.4f
        const val FALL_ALPHA = 0.2f
        const val RISE_ALPHA = 0.06f
        const val MIN_NOISE_FLOOR = 0.003f
        const val MAX_NOISE_FLOOR = 0.5f
    }
}
