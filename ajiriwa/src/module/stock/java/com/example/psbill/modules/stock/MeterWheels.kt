package com.example.psbill.modules.stock

import java.math.BigDecimal

/**
 * The wheels of a dial water meter as one number, like the real meter (pure, no Android: MeterWheelsTest).
 *
 * Digits run from the biggest wheel (first black drum) to the smallest (last red dial). The whole meter is one
 * integer in units of the smallest wheel, so:
 *  - [step] turns a wheel by whole numbers and CARRIES: the 0.1 L wheel moved 10 steps moves the 1 L wheel on by one,
 *    9 -> 0 moves the next bigger wheel up, 0 -> 9 backwards moves it down. Past the top it rolls over to 0 like the
 *    meter; it never goes below 0.
 *  - [set] puts one wheel on a number (tapping the number the needle points at): no carry, the other wheels stay.
 *  - [value] is always exactly the wheels (drums . dials).
 * [Spin] turns a finger moving around a dial into whole steps (36 degrees each, clockwise = up).
 */
internal object MeterWheels {
    fun scale(n: Int): Long { var p = 1L; repeat(n) { p *= 10 }; return p }

    fun toLong(digits: List<Int>): Long = digits.fold(0L) { acc, d -> acc * 10 + d }

    fun fromLong(v: Long, n: Int): List<Int> {
        var x = v
        val out = IntArray(n)
        for (i in n - 1 downTo 0) { out[i] = (x % 10).toInt(); x /= 10 }
        return out.toList()
    }

    /** Turn wheel [pos] (0 = biggest) by [by] numbers, carrying into the bigger wheels. */
    fun step(digits: List<Int>, pos: Int, by: Int): List<Int> {
        val n = digits.size
        if (pos !in 0 until n || by == 0) return digits
        val top = scale(n)
        val next = toLong(digits) + by.toLong() * scale(n - 1 - pos)
        if (next < 0) return digits                  // a meter never shows less than 0
        return fromLong(Math.floorMod(next, top), n) // past 9999.9999 it rolls over to 0
    }

    /** Put wheel [pos] on [d] (0-9) without touching the others. */
    fun set(digits: List<Int>, pos: Int, d: Int): List<Int> =
        if (pos !in digits.indices) digits else digits.mapIndexed { i, x -> if (i == pos) Math.floorMod(d, 10) else x }

    /** The reading the wheels show: [drums] whole units, the rest decimals. */
    fun value(digits: List<Int>, drums: Int): BigDecimal =
        BigDecimal.valueOf(toLong(digits), (digits.size - drums).coerceAtLeast(0))

    /** Wheels for a reading (more decimals than wheels are cut, like the meter's smallest wheel). */
    fun digitsOf(reading: BigDecimal, drums: Int, dials: Int): List<Int> {
        val scaled = reading.movePointRight(dials).toBigInteger().toLong().coerceAtLeast(0)
        return fromLong(Math.floorMod(scaled, scale(drums + dials)), drums + dials)
    }

    /** Smallest angle from [from] to [to] in degrees, in (-180, 180]. */
    fun angleDelta(from: Float, to: Float): Float {
        var d = (to - from) % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /** Degrees clockwise from 12 o'clock for a point (dx, dy) from the dial centre (screen y grows down). */
    fun clockAngle(dx: Float, dy: Float): Float {
        val deg = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
        return if (deg < 0) deg + 360f else deg
    }

    /** Whole steps from a finger going around a dial (or along a drum): keeps the part of a step not used yet. */
    class Spin(private val perStep: Float = 36f) {
        private var rest = 0f
        fun add(amount: Float): Int {
            rest += amount
            val steps = (rest / perStep).toInt()   // toward zero: half a step back does not undo a step
            rest -= steps * perStep
            return steps
        }
        fun reset() { rest = 0f }
    }
}
