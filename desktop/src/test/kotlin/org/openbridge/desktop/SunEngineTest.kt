package org.openbridge.desktop

import org.openbridge.ui.SunEngine
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class SunEngineTest {
    /** The tail is drawn along the sun's path: it must never jump by a turn when the angle wraps back to 0. */
    @Test fun tailNeverSweepsTheRing() {
        val engine = SunEngine()
        fun check(what: String) {
            val path = engine.trail + engine.angle
            for (i in 1 until path.size)
                assertTrue(abs(path[i] - path[i - 1]) < 90, "$what: the tail jumps from ${path[i - 1]}° to ${path[i]}°")
        }
        repeat(30) { engine.step(1.0 / 60, active = true, connected = false, reduceMotion = false); check("scanning") }
        repeat(240) { engine.step(1.0 / 60, active = true, connected = true, reduceMotion = false); check("connecting") }
        repeat(120) { engine.step(1.0 / 60, active = true, connected = false, reduceMotion = false); check("disconnected") }
        repeat(240) { engine.step(1.0 / 60, active = false, connected = false, reduceMotion = false); check("stopping") }
    }
}
