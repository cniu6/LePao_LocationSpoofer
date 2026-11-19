package com.kdzw8p.mqa.v71f.utils

import com.kdzw8p.mqa.v71f.utils.CoordinateUtils.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RingRouteBuilderTest {

    private fun pathLength(points: List<LatLng>): Double =
        points.zipWithNext().sumOf { (a, b) -> GeoMath.distance(a, b) }

    @Test
    fun `rectangle checkpoints are all covered and loop length matches target`() {
        // 操场四角打卡点（约 111m × 85m 矩形），目标 1000m ≈ 2.5 圈，足以覆盖全部打卡点。
        val cps = listOf(
            LatLng(31.0, 120.0),
            LatLng(31.001, 120.0),
            LatLng(31.001, 120.001),
            LatLng(31.0, 120.001)
        )
        val target = 1000.0
        val loop = RingRouteBuilder.buildLoop(cps, target)!!
        // 环线经过每个打卡点：点-折线距离远小于 30m 判定半径。
        for (cp in cps) {
            assertTrue("checkpoint $cp not covered", PolylineCoverage.distanceMeters(cp, loop) <= 5.0)
        }
        // 截断后总长应精确等于目标（末段按比例截断）。
        assertEquals(target, pathLength(loop), 0.5)
    }

    @Test
    fun `near-collinear checkpoints degrade to a stadium still within judgment radius`() {
        // 操场两侧近乎共线的三个打卡点：方位角排序会退化成来回跑，应改成体育场形。
        val cps = listOf(
            LatLng(31.0, 120.0),
            LatLng(31.0, 120.0005),
            LatLng(31.0, 120.001)
        )
        val loop = RingRouteBuilder.buildLoop(cps, 800.0)!!
        // 体育场形半宽 15m，中心线上的打卡点到环线 ≤15m，仍在 30m 判定半径内。
        for (cp in cps) {
            assertTrue("collinear checkpoint $cp out of radius", PolylineCoverage.distanceMeters(cp, loop) <= 30.0)
        }
        assertEquals(800.0, pathLength(loop), 0.5)
    }

    @Test
    fun `two checkpoints form a stadium whose perimeter spans both points`() {
        val a = LatLng(31.0, 120.0)
        val b = LatLng(31.001, 120.0) // 相距约 111m
        val perimeter = RingRouteBuilder.perimeterOf(listOf(a, b))
        val span = GeoMath.distance(a, b)
        // 体育场形周长 ≈ 2×点距 + 两个半圆（π×宽），至少覆盖两倍点距。
        assertTrue(perimeter >= 2 * span)
        val loop = RingRouteBuilder.buildLoop(listOf(a, b), 600.0)!!
        assertTrue(PolylineCoverage.distanceMeters(a, loop) <= 30.0)
        assertTrue(PolylineCoverage.distanceMeters(b, loop) <= 30.0)
    }

    @Test
    fun `insufficient control points or non-positive target returns null`() {
        assertNull(RingRouteBuilder.buildLoop(listOf(LatLng(31.0, 120.0)), 1000.0))
        assertNull(RingRouteBuilder.buildLoop(listOf(LatLng(31.0, 120.0), LatLng(31.001, 120.0)), 0.0))
        assertNull(RingRouteBuilder.buildLoop(emptyList(), 1000.0))
    }
}