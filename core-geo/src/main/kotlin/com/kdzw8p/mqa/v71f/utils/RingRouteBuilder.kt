package com.kdzw8p.mqa.v71f.utils

import com.kdzw8p.mqa.v71f.utils.CoordinateUtils.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 定向打卡跑区的闭合环线生成（纯几何，App 与测试共用）。
 *
 * 打卡点按 index 直连会自交（四个点常横跨操场），轨迹成了星形。这里先按绕质心的方位角排序，
 * 再用闭合 Catmull-Rom 插值：曲线经过每个打卡点、不自交，看起来就是绕场地跑的正常环线；
 * 点几乎共线时退化成体育场形（两平行边 + 两个半圆）。最后按目标里程重复绕圈并截断末段。
 *
 * 判定半径是 run_line_info.log_max_distance（实测 30 米），生成的环线必须经过每个打卡点附近。
 */
object RingRouteBuilder {

    /** 共线判定阈值：最远两点距离小于它，或其余点到其连线最大垂距小于它，就当共线处理。 */
    private const val COLLINEAR_SPAN_M = 40.0
    private const val COLLINEAR_OFFSET_M = 3.0
    private const val STADIUM_WIDTH_M = 30.0
    private const val MIN_PERIMETER_M = 40.0
    private const val MAX_LAPS = 500

    /**
     * 把打卡点排成一条不自交的闭合顺序：绕质心按方位角排序。
     * 点几乎落在一条直线上时（操场两侧的打卡点很常见），方位角排序会退化成来回跑，
     * 这时改成体育场形：两端点 + 垂直方向各偏半个宽度。
     */
    fun orderRing(points: List<LatLng>): List<LatLng> {
        if (points.size == 2) return stadiumRing(points[0], points[1], STADIUM_WIDTH_M)
        if (points.size < 3) return points
        val cy = points.sumOf { it.lat } / points.size
        val cx = points.sumOf { it.lng } / points.size
        val sorted = points.sortedBy { atan2(it.lat - cy, it.lng - cx) }
        // 共线检测：最远两点连线，其余点到它的最大垂距太小就不当多边形用。
        var fa = sorted[0]
        var fb = sorted[1]
        var best = -1.0
        for (i in sorted.indices) for (j in i + 1 until sorted.size) {
            val d = GeoMath.distance(sorted[i], sorted[j])
            if (d > best) { best = d; fa = sorted[i]; fb = sorted[j] }
        }
        val maxOffset = sorted.maxOf { PolylineCoverage.distanceMeters(it, listOf(fa, fb)) }
        return if (best < COLLINEAR_SPAN_M || maxOffset < COLLINEAR_OFFSET_M) stadiumRing(fa, fb, STADIUM_WIDTH_M) else sorted
    }

    /** 两端点 + 垂直方向各偏移半个宽度，形成一圈体育场形路线。 */
    fun stadiumRing(a: LatLng, b: LatLng, widthMeters: Double): List<LatLng> {
        val midLat = (a.lat + b.lat) / 2
        val cosLat = cos(Math.toRadians(midLat)).coerceAtLeast(0.01)
        val dx = (b.lng - a.lng) * 111320.0 * cosLat
        val dy = (b.lat - a.lat) * 111320.0
        val len = hypot(dx, dy)
        if (len < 1e-6) return listOf(a, b)
        val half = widthMeters / 2
        val ox = -dy / len * half
        val oy = dx / len * half
        val dLat = oy / 111320.0
        val dLng = ox / (111320.0 * cosLat)
        return listOf(
            LatLng(a.lat + dLat, a.lng + dLng),
            LatLng(b.lat + dLat, b.lng + dLng),
            LatLng(b.lat - dLat, b.lng - dLng),
            LatLng(a.lat - dLat, a.lng - dLng)
        )
    }

    /** 闭合 Catmull-Rom：曲线经过每个控制点，转角自然圆滑，不会把打卡点裁掉。 */
    fun smoothClosedRing(points: List<LatLng>, samplesPerSegment: Int = 6): List<LatLng> {
        if (points.size < 3) return points
        val n = points.size
        val out = ArrayList<LatLng>(n * samplesPerSegment)
        for (i in 0 until n) {
            val p0 = points[(i - 1 + n) % n]
            val p1 = points[i]
            val p2 = points[(i + 1) % n]
            val p3 = points[(i + 2) % n]
            for (s in 0 until samplesPerSegment) {
                val t = s.toDouble() / samplesPerSegment
                val t2 = t * t
                val t3 = t2 * t
                fun v(a: Double, b: Double, c: Double, d: Double): Double = 0.5 * (
                    2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3
                    )
                out.add(LatLng(v(p0.lat, p1.lat, p2.lat, p3.lat), v(p0.lng, p1.lng, p2.lng, p3.lng)))
            }
        }
        return out
    }

    /**
     * 由 ordered 打卡点生成覆盖 targetMeters 的闭合环线：平滑 → 闭合 → 重复绕圈 → 末段按比例截断。
     * 控制点不足、周长过小或目标非正时返回 null（调用方据此回退到其它路线）。
     */
    fun buildLoop(controlPoints: List<LatLng>, targetMeters: Double): List<LatLng>? {
        if (controlPoints.size < 2 || targetMeters <= 0.0) return null
        val ordered = orderRing(controlPoints)
        val loop = smoothClosedRing(ordered)
        if (loop.size < 3) return null
        val ring = loop + loop.first()
        val segs = ring.zipWithNext().map { (a, b) -> GeoMath.distance(a, b) }
        val perimeter = segs.sum()
        if (perimeter < MIN_PERIMETER_M) return null

        val out = ArrayList<LatLng>()
        var acc = 0.0
        var laps = 0
        while (acc < targetMeters && laps < MAX_LAPS) {
            laps++
            var cut = false
            for (k in 0 until ring.size - 1) {
                val a = ring[k]
                val b = ring[k + 1]
                val seg = segs[k]
                if (acc + seg >= targetMeters) {
                    val t = ((targetMeters - acc) / seg).coerceIn(0.0, 1.0)
                    out.add(LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t))
                    acc = targetMeters
                    cut = true
                    break
                }
                out.add(LatLng(a.lat, a.lng))
                acc += seg
            }
            if (cut) break
        }
        return if (out.size < 2) null else out
    }

    /** 环线周长（米），供状态窗显示「N m/圈」。 */
    fun perimeterOf(controlPoints: List<LatLng>): Double {
        val ordered = orderRing(controlPoints)
        val loop = smoothClosedRing(ordered)
        if (loop.size < 3) return 0.0
        val ring = loop + loop.first()
        return ring.zipWithNext().sumOf { (a, b) -> GeoMath.distance(a, b) }
    }
}