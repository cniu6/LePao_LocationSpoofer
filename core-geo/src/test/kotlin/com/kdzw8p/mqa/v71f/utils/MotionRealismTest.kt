package com.kdzw8p.mqa.v71f.utils

import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionRealismTest {

    private val start = 1_789_398_037_217L

    @Test
    fun `level off with no speed fluctuation keeps the legacy uniform motion`() {
        val s = MotionRealism.Session(start, MotionRealism.Level.OFF, 0)
        assertEquals(3.0 * 125.0, s.distance(3.0, 125.0), 1e-9)
        assertEquals(3.0, s.speed(3.0, 125.0), 1e-12)
        assertEquals(165.0 / 60.0 * 125.0, s.steps(165.0, 125.0), 1e-9)
    }

    @Test
    fun `sessions with the same start timestamp agree across processes`() {
        val a = MotionRealism.Session(start, MotionRealism.Level.HIGH, 20)
        val b = MotionRealism.Session(start, MotionRealism.Level.HIGH, 20)
        val c = MotionRealism.Session(start + 1, MotionRealism.Level.HIGH, 20)
        assertEquals(a.distance(3.0, 777.0), b.distance(3.0, 777.0), 0.0)
        assertEquals(a.steps(160.0, 777.0), b.steps(160.0, 777.0), 0.0)
        assertNotEquals(a.distance(3.0, 777.0), c.distance(3.0, 777.0), 1e-6)
    }

    @Test
    fun `speed stays within the configured fluctuation and matches the distance derivative`() {
        val s = MotionRealism.Session(start, MotionRealism.Level.MEDIUM, 20)
        var sawVariation = false
        for (t in 0..3600 step 7) {
            val v = s.speed(3.0, t.toDouble())
            // 标称 ±20%；噪声峰值×1.6 校准与降速块(含恢复超速)最坏同相叠加的物理包络约 ±1.85 倍
            assertTrue("speed $v at $t", v in 3.0 * (1 - 1.85 * 0.2)..3.0 * (1 + 1.85 * 0.2))
            if (abs(v - 3.0) > 0.1) sawVariation = true
            val derivative = (s.distance(3.0, t + 1.0) - s.distance(3.0, t.toDouble())) / 1.0
            assertEquals(v, derivative, 0.05)
        }
        assertTrue(sawVariation)
    }

    @Test
    fun `step count keeps increasing and cadence varies around the base`() {
        val s = MotionRealism.Session(start, MotionRealism.Level.HIGH, 15)
        var last = -1.0
        var minCadence = Double.MAX_VALUE
        var maxCadence = 0.0
        for (t in 0..1800) {
            val steps = s.steps(170.0, t.toDouble())
            assertTrue(steps > last)
            last = steps
            val c = s.cadence(170.0, t.toDouble())
            minCadence = minOf(minCadence, c)
            maxCadence = maxOf(maxCadence, c)
        }
        assertTrue(maxCadence - minCadence > 5)
        // 步频自然漂移加大后允许更宽的生理范围（约 ±15%）
        assertTrue(minCadence > 170 * 0.80 && maxCadence < 170 * 1.20)
    }

    @Test
    fun `procedural gait averages to gravity over whole cycles`() {
        for (speed in listOf(1.3, 3.2)) {
            val s = MotionRealism.Session(start, MotionRealism.Level.OFF, 0)
            val rnd = Random(1)
            val n = 20_000
            var sx = 0.0
            var sy = 0.0
            var sz = 0.0
            for (i in 0 until n) {
                val stepsFloat = i * 40.0 / n // 恰好 40 步 = 20 个完整跨步
                val a = s.accelerometer(stepsFloat, speed, null, rnd)
                sx += a[0]; sy += a[1]; sz += a[2]
            }
            assertEquals(0.0, sx / n, 0.05)
            assertEquals(0.0, sy / n, 0.05)
            assertEquals(9.80665, sz / n, 0.05)
        }
    }

    @Test
    fun `step impact strength differs between steps when randomness is on`() {
        val s = MotionRealism.Session(start, MotionRealism.Level.MEDIUM, 0)
        val quiet = Random(0)
        // 关闭噪声的对照：同一相位、不同步序号，冲击峰值应当不同
        val peaks = (0 until 6).map { step -> s.accelerometer(step.toDouble(), 1.4, null, quiet)[2] }
        assertTrue(peaks.toSet().size > 1)
    }

    @Test
    fun `session cache returns the same instance for the same parameters`() {
        val a = MotionRealism.session(start, 2, 10)
        val b = MotionRealism.session(start, 2, 10)
        val c = MotionRealism.session(start, 2, 20)
        assertTrue(a === b)
        assertTrue(a !== c)
    }

    @Test
    fun `session cache distinguishes slowdown parameters`() {
        val cfg = MotionRealism.SlowdownSettings(12, 20, 60, 90)
        val a = MotionRealism.session(start, 2, 10, cfg)
        val b = MotionRealism.session(start, 2, 10, cfg)
        val c = MotionRealism.session(start, 2, 10, MotionRealism.SlowdownSettings.OFF)
        assertTrue(a === b)
        assertTrue(a !== c)
    }

    @Test
    fun `slowdown blocks are deterministic and integrate to the distance`() {
        val cfg = MotionRealism.SlowdownSettings(12, 20, 60, 90)
        val a = MotionRealism.Session(start, MotionRealism.Level.MEDIUM, 10, cfg)
        val b = MotionRealism.Session(start, MotionRealism.Level.MEDIUM, 10, cfg)
        var sawDip = false
        var lastDistance = -1.0
        for (t in 0..1800 step 3) {
            val tSec = t.toDouble()
            // 每个进程独立算出的曲线必须逐位一致，否则多进程距离会漂移
            assertEquals(a.distance(3.0, tSec), b.distance(3.0, tSec), 0.0)
            val v = a.speed(3.0, tSec)
            assertTrue("speed $v at $t", v > 0.0)
            if (a.slowdownAt(tSec) < -0.05) sawDip = true
            val h = 1e-3
            val derivative = (a.distance(3.0, tSec + h) - a.distance(3.0, tSec)) / h
            assertEquals(v, derivative, 1e-3)
            val d = a.distance(3.0, tSec)
            assertTrue("distance went backwards at $t", d > lastDistance)
            lastDistance = d
        }
        assertTrue("expected at least one pace dip", sawDip)
    }

    @Test
    fun `cadence drops inside a slowdown block`() {
        val cfg = MotionRealism.SlowdownSettings(20, 30, 60, 40)
        val s = MotionRealism.Session(start, MotionRealism.Level.HIGH, 0, cfg)
        var dipT = -1.0
        var t = 0.0
        while (t < 1200) {
            if (s.slowdownAt(t) < -0.15) {
                dipT = t
                break
            }
            t += 0.5
        }
        assertTrue("no dip found", dipT >= 0)
        val base = 170.0
        assertTrue(s.cadence(base, dipT) < base)
        assertTrue(s.cadence(base, dipT) > base * 0.85)
    }

    @Test
    fun `dense samples stay continuous across many completed slowdown blocks`() {
        val cfg = MotionRealism.SlowdownSettings(25, 20, 60, 20)
        val s = MotionRealism.Session(start, MotionRealism.Level.HIGH, 28, cfg)
        var previous = 0.0
        var maximumAdvance = 0.0
        for (millis in 0..900_000L step 100L) {
            val distance = s.distance(3.6, millis / 1000.0)
            assertTrue("distance went backwards at $millis ms", distance >= previous)
            maximumAdvance = maxOf(maximumAdvance, distance - previous)
            previous = distance
        }
        assertTrue("distance jumped between samples: $maximumAdvance", maximumAdvance < 0.5)
    }

    @Test
    fun `custom ramp and cadence dip reshape the slowdown`() {
        val custom = MotionRealism.SlowdownSettings(25, 20, 60, 40, 30)
        val auto = MotionRealism.SlowdownSettings(25, 20, 60, 40, 0)
        val deep = MotionRealism.Session(start, MotionRealism.Level.HIGH, 0, custom, 100)
        val mild = MotionRealism.Session(start, MotionRealism.Level.HIGH, 0, custom, 45)
        val autoRamp = MotionRealism.Session(start, MotionRealism.Level.HIGH, 0, auto, 45)
        var previous = 0.0
        for (millis in 0..600_000L step 100L) {
            val d = deep.distance(3.6, millis / 1000.0)
            assertTrue("distance regressed at $millis", d >= previous)
            previous = d
        }
        var rampDiffers = false
        for (t in 0..120) {
            if (abs(deep.slowdownAt(t.toDouble()) - autoRamp.slowdownAt(t.toDouble())) > 0.03) rampDiffers = true
        }
        assertTrue("custom ramp should reshape the dip envelope", rampDiffers)
        var checked = 0
        for (t in 0..600) {
            if (deep.slowdownAt(t.toDouble()) < -0.2) {
                checked++
                assertTrue(
                    "cadence dip 100% should sit below default 45%",
                    deep.cadence(99.0, t.toDouble()) < mild.cadence(99.0, t.toDouble())
                )
            }
        }
        assertTrue("no full-depth dip found", checked > 0)
    }

    @Test
    fun `slowdown never exceeds base speed even with maximum fluctuation`() {
        for (seed in 0L..7L) {
            val s = MotionRealism.Session(start + seed, MotionRealism.Level.HIGH, 30,
                MotionRealism.SlowdownSettings(70, 20, 60, 20))
            var slowSamples = 0
            var previous = 0.0
            for (tick in 0..7200) {
                val t = tick / 4.0
                val speed = s.speed(3.4, t)
                assertTrue("speed=$speed seed=$seed t=$t", speed >= -1e-8 && speed <= 3.4 + 1e-8)
                assertTrue("positive recovery at $t", s.slowdownAt(t) <= 0.0)
                if (speed < 1.7) slowSamples++
                val distance = s.distance(3.4, t)
                assertTrue("distance regressed at $t", distance >= previous - 1e-8)
                previous = distance
                if (t > 0.0) {
                    val derivative = (s.distance(3.4, t + 0.001) - s.distance(3.4, t - 0.001)) / 0.002
                    // speed() 用 h=0.25 的平滑差分窗，降速块拐点处与 h=0.001 瞬时导数有微小偏差，容差放宽到 0.005
                    assertEquals(speed, derivative, 0.005)
                }
            }
            assertTrue("no sustained deep slowdown for seed $seed", slowSamples > 100)
            assertTrue(s.distance(3.4, 1800.0) < 3.4 * 1800.0)
        }
    }

    @Test
    fun `configured depth is sustained instead of reduced to a shallow dip`() {
        val s = MotionRealism.Session(start, MotionRealism.Level.OFF, 0,
            MotionRealism.SlowdownSettings(40, 40, 40, 20))
        var longest = 0
        var current = 0
        for (tick in 0..2400) {
            if (s.slowdownAt(tick / 4.0) <= -0.34) {
                current++
                longest = maxOf(longest, current)
            } else current = 0
        }
        assertTrue("full-depth hold lasted ${longest / 4.0}s", longest >= 80)
    }

    @Test
    fun `disabled slowdown falls back to plain fluctuation`() {
        val cfg = MotionRealism.SlowdownSettings.OFF
        val s = MotionRealism.Session(start, MotionRealism.Level.OFF, 0, cfg)
        assertEquals(0.0, s.slowdownAt(4321.0), 0.0)
        assertEquals(3.0 * 4321.0, s.distance(3.0, 4321.0), 1e-9)
    }
}
