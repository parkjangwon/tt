package org.parkjw.apps.tt

data class GestureConfig(
    val foldAngle: Float,
    val openAngle: Float,
    val windowMillis: Long,
) {
    companion object {
        /** Fixed gesture thresholds: open → bend below 90° → reopen within 2s. */
        val STANDARD = GestureConfig(foldAngle = 90f, openAngle = 180f, windowMillis = 2_000L)
    }
}

/**
 * Detects the "quick bend and reopen" gesture from a stream of hinge angle readings.
 *
 * Full cycle: open (>= [GestureConfig.openAngle]) -> bent (<= [GestureConfig.foldAngle])
 * -> open again within [GestureConfig.windowMillis]. Partial bends that never reach the
 * fold threshold are ignored, the device must be fully open first, and it must be bent
 * again before a new trigger can fire. Reopening later than the window (e.g. after
 * sitting in flex mode for a while) does not trigger either.
 */
class GestureDetector {

    private enum class Phase { UNKNOWN, OPEN, BENT }

    private var phase = Phase.UNKNOWN
    private var armed = false
    private var bentAtMillis = 0L

    /** Feeds one hinge angle reading. Returns true exactly once, when a valid gesture completes. */
    fun onAngle(angle: Float, nowMillis: Long, config: GestureConfig): Boolean {
        val bent = angle <= config.foldAngle
        val opened = angle >= config.openAngle
        when (phase) {
            Phase.UNKNOWN -> when {
                opened -> phase = Phase.OPEN
                bent -> enterBent(nowMillis, armed = false)
            }

            Phase.OPEN -> if (bent) enterBent(nowMillis, armed = true)

            Phase.BENT -> if (opened) {
                val fire = armed && nowMillis - bentAtMillis <= config.windowMillis
                phase = Phase.OPEN
                armed = false
                if (fire) return true
            }
        }
        return false
    }

    /** Forgets in-progress state, e.g. after the screen turned off mid-gesture. */
    fun reset() {
        phase = Phase.UNKNOWN
        armed = false
    }

    private fun enterBent(nowMillis: Long, armed: Boolean) {
        phase = Phase.BENT
        this.armed = armed
        bentAtMillis = nowMillis
    }
}
