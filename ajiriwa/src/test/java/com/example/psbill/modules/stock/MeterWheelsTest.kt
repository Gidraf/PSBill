package com.example.psbill.modules.stock

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/** The meter dial carries like a real meter. ./gradlew :ajiriwa:testDebugUnitTest --tests '*MeterWheelsTest' */
class MeterWheelsTest {
    private val W = MeterWheels
    private fun d(s: String) = s.map { it - '0' }
    private fun v(digits: List<Int>) = W.value(digits, 4).toPlainString()

    @Test fun smallestWheelTenStepsMovesTheNextOne() {
        // 0012.3705 m3; the 0.1 L wheel (last) turned 10 steps -> the 1 L wheel moves on by one
        var x = d("00123705")
        repeat(10) { x = W.step(x, 7, 1) }
        assertEquals("12.3715", v(x))
        assertEquals(d("00123715"), x)
    }

    @Test fun nineToZeroCarriesUpThroughEveryWheel() {
        assertEquals("13.0000", v(W.step(d("00129999"), 7, 1)))
        assertEquals("13.0999", v(W.step(d("00129999"), 4, 1)))   // 0.1 m3 dial 9 -> 0: the drum moves on, smaller dials stay
        assertEquals("100.0000", v(W.step(d("00990000"), 3, 1)))   // last black drum 9 -> 0 carries into the next drum
    }

    @Test fun backwardsBorrows() {
        assertEquals("12.9999", v(W.step(d("00130000"), 7, -1)))
        assertEquals("12.9000", v(W.step(d("00130000"), 4, -1)))
        var x = d("00123700")
        repeat(10) { x = W.step(x, 6, -1) }                          // 1 L wheel back 10 -> 10 L wheel down one
        assertEquals("12.3600", v(x))
    }

    @Test fun neverBelowZeroAndRollsOverAtTheTop() {
        assertEquals("0.0000", v(W.step(d("00000000"), 7, -1)))
        assertEquals("0.0000", v(W.step(d("99999999"), 7, 1)))
    }

    @Test fun tapSetsOneWheelOnly() {
        val x = W.set(d("00123705"), 4, 9)                           // tap 9 on the 0.1 m3 dial
        assertEquals("12.9705", v(x))
        assertEquals("12.0705", v(W.set(x, 4, 0)))                 // tapping never carries
    }

    @Test fun valueAlwaysEqualsTheWheels() {
        var x = W.digitsOf(BigDecimal("12.3705"), 4, 4)
        val moves = listOf(7 to 13, 6 to -4, 5 to 27, 3 to -1, 4 to 11, 7 to -38)
        for ((pos, by) in moves) {
            val before = W.toLong(x)
            x = W.step(x, pos, by)
            val expected = before + by * W.scale(7 - pos)
            assertEquals(expected, W.toLong(x))
            assertEquals(BigDecimal(x.joinToString("").let { it.take(4) + "." + it.drop(4) }), W.value(x, 4))
        }
    }

    @Test fun readingToWheels() {
        assertEquals(d("00123705"), W.digitsOf(BigDecimal("12.3705"), 4, 4))
        assertEquals(d("001237"), W.digitsOf(BigDecimal("12.3799"), 4, 2))   // cut to the wheels it has
        assertEquals("12", W.value(d("0012"), 4).toPlainString())
    }

    @Test fun spinGivesWholeSteps() {
        val s = MeterWheels.Spin()
        assertEquals(0, s.add(30f))
        assertEquals(1, s.add(10f))      // 40 degrees
        assertEquals(9, s.add(9 * 36f))  // a full turn in all = 10 steps
        assertEquals(0, s.add(-20f))     // a little back does not undo a step
        assertEquals(-1, s.add(-20f))
    }

    @Test fun angles() {
        assertEquals(0f, W.clockAngle(0f, -10f), 0.01f)      // 12 o'clock
        assertEquals(90f, W.clockAngle(10f, 0f), 0.01f)      // 3 o'clock
        assertEquals(270f, W.clockAngle(-10f, 0f), 0.01f)
        assertEquals(20f, W.angleDelta(350f, 10f), 0.01f)    // across 12 o'clock clockwise
        assertEquals(-20f, W.angleDelta(10f, 350f), 0.01f)
    }
}
