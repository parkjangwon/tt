package org.parkjw.apps.tt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureDetectorTest {

    private val cfg = GestureConfig(foldAngle = 120f, openAngle = 165f, windowMillis = 5_000)

    @Test
    fun `quick bend and reopen fires once`() {
        val d = GestureDetector()
        assertFalse(d.onAngle(178f, 0, cfg))
        assertFalse(d.onAngle(100f, 1_000, cfg))
        assertTrue(d.onAngle(178f, 3_000, cfg))
    }

    @Test
    fun `partial bend that never reaches fold threshold does not fire`() {
        val d = GestureDetector()
        assertFalse(d.onAngle(178f, 0, cfg))
        assertFalse(d.onAngle(150f, 1_000, cfg))
        assertFalse(d.onAngle(130f, 1_500, cfg))
        assertFalse(d.onAngle(178f, 2_000, cfg))
    }

    @Test
    fun `reopen after the window does not fire`() {
        val d = GestureDetector()
        assertFalse(d.onAngle(178f, 0, cfg))
        assertFalse(d.onAngle(100f, 1_000, cfg))
        assertFalse(d.onAngle(178f, 7_000, cfg))
        // A fresh quick cycle afterwards still fires.
        assertFalse(d.onAngle(100f, 8_000, cfg))
        assertTrue(d.onAngle(178f, 9_000, cfg))
    }

    @Test
    fun `starting mid-bend then opening does not fire`() {
        val d = GestureDetector()
        assertFalse(d.onAngle(100f, 0, cfg))
        assertFalse(d.onAngle(178f, 1_000, cfg))
    }

    @Test
    fun `opening straight from closed does not fire`() {
        val d = GestureDetector()
        assertFalse(d.onAngle(20f, 0, cfg))
        assertFalse(d.onAngle(90f, 500, cfg))
        assertFalse(d.onAngle(178f, 1_000, cfg))
    }

    @Test
    fun `requires a new fold between triggers`() {
        val d = GestureDetector()
        assertFalse(d.onAngle(178f, 0, cfg))
        assertFalse(d.onAngle(100f, 1_000, cfg))
        assertTrue(d.onAngle(178f, 2_000, cfg))
        // Staying open and even dipping partially does not re-fire.
        assertFalse(d.onAngle(178f, 2_500, cfg))
        assertFalse(d.onAngle(140f, 3_000, cfg))
        assertFalse(d.onAngle(178f, 3_500, cfg))
        // Full re-fold is required.
        assertFalse(d.onAngle(100f, 4_000, cfg))
        assertTrue(d.onAngle(178f, 5_000, cfg))
    }

    @Test
    fun `reset drops an in-progress gesture`() {
        val d = GestureDetector()
        assertFalse(d.onAngle(178f, 0, cfg))
        assertFalse(d.onAngle(100f, 1_000, cfg))
        d.reset()
        assertFalse(d.onAngle(178f, 2_000, cfg))
        // Detector is unarmed; a full cycle is needed again.
        assertFalse(d.onAngle(100f, 3_000, cfg))
        assertTrue(d.onAngle(178f, 4_000, cfg))
    }

    @Test
    fun `jitter around thresholds between bend and reopen keeps the gesture alive`() {
        val d = GestureDetector()
        assertFalse(d.onAngle(178f, 0, cfg))
        assertFalse(d.onAngle(100f, 1_000, cfg))
        assertFalse(d.onAngle(140f, 1_500, cfg))
        assertFalse(d.onAngle(110f, 2_000, cfg))
        assertTrue(d.onAngle(178f, 3_000, cfg))
    }
}
