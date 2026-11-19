package com.kdzw8p.mqa.v71f.xposed.utils

import com.kdzw8p.mqa.v71f.utils.AltitudeModel
import com.kdzw8p.mqa.v71f.utils.CoordinateUtils.LatLng
import com.kdzw8p.mqa.v71f.utils.GeoMath
import com.kdzw8p.mqa.v71f.utils.MotionRealism
import com.kdzw8p.mqa.v71f.utils.RunnerSway
import com.kdzw8p.mqa.v71f.utils.RoutePath
import org.json.JSONArray
import org.json.JSONObject

data class SpoofedMotion(
    val lat: Double,
    val lng: Double,
    val bearing: Float,
    val speed: Float
)

/**
 * 根据配置和当前时间推导模拟位置：路线模式沿路线插值，摇杆模式按方向和速度推算，其余返回配置里的固定坐标。
 * 路线几何与 App 端共用 [RoutePath]，保证两端对同一时刻算出同一位置。
 */
object RouteEngine {

    /**
     * 摇杆模式：App 约每秒写一次"当前位置 + 方向 + 速度"，两次写入之间在这里按方向和速度往前推算，
     * 让被 Hook 的 App 看到连续移动而不是每秒跳一下。推算时长有上限，防止 App 被杀后位置一直漂走。
     */
    private const val JOYSTICK_MAX_EXTRAPOLATION_MS = 3000L

    /** 抖动倍率与整条路线平移的上限；配置被改坏时按边界裁剪，不让轨迹飞出合理范围 */
    private const val MAX_SWAY_SCALE = 3.0
    private const val MAX_ROUTE_OFFSET_M = 30.0

    // 配置 JSON 只在文件变化时才重新解析，同一个 JSONArray 实例对应同一条路线，按实例缓存即可
    @Volatile private var cachedRouteArray: JSONArray? = null
    @Volatile private var cachedPath: RoutePath? = null

    private fun routePath(routeArray: JSONArray): RoutePath {
        val cached = cachedPath
        if (routeArray === cachedRouteArray && cached != null) return cached
        val points = ArrayList<LatLng>(routeArray.length())
        for (i in 0 until routeArray.length()) {
            val obj = routeArray.optJSONObject(i) ?: continue
            points += LatLng(obj.optDouble("lat", 0.0), obj.optDouble("lng", 0.0))
        }
        return RoutePath(points).also {
            cachedPath = it
            cachedRouteArray = routeArray
        }
    }

    private fun extrapolateJoystick(config: JSONObject, now: Long, base: LatLng, bearing: Float): SpoofedMotion {
        val speed = config.optDouble("speed_m_s", 0.0)
        val startTime = config.optLong("start_timestamp", 0L)
        if (speed <= 0.0 || startTime <= 0L) return SpoofedMotion(base.lat, base.lng, bearing, 0f)

        val elapsedSec = (now - startTime).coerceIn(0L, JOYSTICK_MAX_EXTRAPOLATION_MS) / 1000.0
        val pos = GeoMath.destination(base, bearing.toDouble(), speed * elapsedSec)
        return SpoofedMotion(pos.lat, pos.lng, bearing, speed.toFloat())
    }

    fun calculateCurrentPosition(config: JSONObject, now: Long = System.currentTimeMillis()): SpoofedMotion {
        val base = LatLng(config.optDouble("lat", 0.0), config.optDouble("lng", 0.0))
        val baseBearing = config.optDouble("sim_bearing", 0.0).toFloat()
        val routeArray = config.optJSONArray("route_points")

        if (!config.optBoolean("is_route_mode", false) || routeArray == null || routeArray.length() < 2) {
            if (config.optString("sim_mode") == "JOYSTICK") {
                return extrapolateJoystick(config, now, base, baseBearing)
            }
            return SpoofedMotion(base.lat, base.lng, baseBearing, 0f)
        }

        val path = routePath(routeArray)
        if (!path.isValid) return SpoofedMotion(base.lat, base.lng, baseBearing, 0f)

        val speed = config.optDouble("speed_m_s", 3.0).coerceAtLeast(0.1)
        val rawStartTime = config.optLong("start_timestamp", 0L)
        val startTime = if (rawStartTime > 0L) rawStartTime else now
        val elapsedSec = (now - startTime).coerceAtLeast(0L) / 1000.0
        val realism = realismSession(config, startTime)
        // 暂停后继续时，路线从暂停处的累计距离接着走（start_timestamp 重置为继续的时刻）
        val offset = config.optDouble("route_distance_offset", 0.0).coerceAtLeast(0.0)
        val traveledRaw = offset + realism.distance(speed, elapsedSec)
        // 再钳一道到目标距离：即使 realism 因参数退化没饱和，位置也不会越过目标点
        val targetTotal = config.optDouble("target_distance_m", 0.0)
        var traveled = if (targetTotal > 0.0) traveledRaw.coerceAtMost(targetTotal) else traveledRaw
        // 到点饱和：位置钉在目标点、速度归零、不再叠加摆动。否则 offset≥target 时 realismSession
        // 的 remaining=0 会被当成「无目标」而恢复满速，出现「原地小幅摆动却报 3m/s」的破绽。
        if (targetTotal > 0.0 && traveledRaw >= targetTotal) {
            val p = path.positionAt(targetTotal, config.optBoolean("stop_at_destination", false))
            return SpoofedMotion(p.lat, p.lng, p.bearing, 0f)
        }

        // 最低平均配速（分段语义）：摇杆力度不足、降速块、下行噪声都会把平均速度压低。
        // 低于设定配速时把本段行程抬到「本段已用时间 × 最低速度」这条线上（不够就加速）。
        // floor 与 traveled 同基准（都含 offset）：resume/换速后 offset 承接已跑距离、本段时长重新计，
        // 底线按分段约束而非全程，避免长暂停后把 traveled 一次性顶前一大段造成位置瞬移。
        val minPaceSecPerKm = config.optDouble("min_pace_sec_per_km", 0.0)
        var paceLimited = false
        if (minPaceSecPerKm > 0.0 && elapsedSec > 0.0) {
            // 续跑段（offset>0）已经在跑动中，不再扣起步斜坡，与 realismSession 的 rampSec=0 一致。
            val rampSec = if (offset > 0.0) 0.0 else config.optDouble("speed_ramp_sec", MotionRealism.DEFAULT_SPEED_RAMP_SEC)
            val paceElapsed = (elapsedSec - rampSec).coerceAtLeast(0.0)
            val requiredSeg = paceElapsed / minPaceSecPerKm * 1000.0
            val decelGuard = if (targetTotal > 0.0) {
                (targetTotal - config.optDouble("target_decel_m", MotionRealism.DEFAULT_DECEL_DISTANCE_M)).coerceAtLeast(0.0)
            } else Double.MAX_VALUE
            val floor = (offset + requiredSeg).let { if (targetTotal > 0.0) it.coerceAtMost(targetTotal) else it }
            if (traveled < floor && traveled < decelGuard) {
                traveled = floor
                paceLimited = true
            }
        }

        val pos = path.positionAt(traveled, config.optBoolean("stop_at_destination", false))
        val naturalSpeed = realism.speed(speed, elapsedSec)
        val currentSpeed = if (pos.arrived) 0f else (
            if (paceLimited) kotlin.math.max(naturalSpeed, 1000.0 / minPaceSecPerKm) else naturalSpeed
            ).toFloat()
        if (pos.arrived || currentSpeed <= 0.05f) {
            return SpoofedMotion(pos.lat, pos.lng, pos.bearing, currentSpeed)
        }

        // 路径空间噪声：沿累计里程缓变，直线段也会左右小幅摆动，而不是整条路线固定平移。
        // 幅度 = route_offset_m（米），0 = 完全贴线；周期按米解释，几十米内换一次方向。
        val routeOffsetAmplitude = kotlin.math.abs(config.optDouble("route_offset_m", 0.0))
            .coerceIn(0.0, MAX_ROUTE_OFFSET_M)
        val offsetPos = if (routeOffsetAmplitude == 0.0) {
            pos
        } else {
            val noise = MotionRealism.SmoothNoise(startTime xor 0x6D3F, 25.0, 70.0)
            val lateral = routeOffsetAmplitude * noise.value(traveled)
            val shifted = GeoMath.destination(LatLng(pos.lat, pos.lng), pos.bearing + 90.0, lateral)
            RoutePath.Position(shifted.lat, shifted.lng, pos.bearing, false)
        }

        // 真人跑步不会贴着几何线：沿路线法线叠加与速度相关的横向摆动，方位角由摆动
        // 横向速度推出；由 (start_timestamp, t) 确定性生成，与注入层 GPS 漂移叠加后
        // 就是「跑者轨迹 + 定位误差」两层自然噪声。幅度由 sway_scale 缩放，0 = 关抖动。
        val swayScale = config.optDouble("sway_scale", 1.0).coerceIn(0.0, MAX_SWAY_SCALE)
        val sway = RunnerSway.at(startTime, currentSpeed.toDouble(), elapsedSec, swayScale)
        if (sway.lateralM == 0.0) return SpoofedMotion(offsetPos.lat, offsetPos.lng, offsetPos.bearing, currentSpeed)
        val swayPos = GeoMath.destination(LatLng(offsetPos.lat, offsetPos.lng), offsetPos.bearing + 90.0, sway.lateralM)
        var bearing = (offsetPos.bearing + sway.bearingOffsetDeg) % 360.0
        if (bearing < 0) bearing += 360.0
        return SpoofedMotion(swayPos.lat, swayPos.lng, bearing.toFloat(), currentSpeed)
    }

    /**
     * 当前位置的海拔：基准海拔 ± 变化范围内随地形起伏，叠加随机强度对应的缓慢漂移（见 [AltitudeModel]），
     * 各进程对同一时刻给出一致的值。旧版配置没有变化范围字段时，按随机强度的漂移幅度处理。
     */
    fun realisticAltitude(config: JSONObject, now: Long = System.currentTimeMillis()): Double {
        val base = config.optDouble("altitude", 25.0)
        val drift = MotionRealism.Level.fromId(config.optInt("realism_level", MotionRealism.Level.OFF.id)).altitudeAmplitudeM
        val variation = config.optDouble("altitude_variation_m", drift)
        if (variation <= 0.0) return base
        val pos = calculateCurrentPosition(config, now)
        return AltitudeModel.altitude(base, variation, drift, pos.lat, pos.lng, now)
    }

    /** 当前模拟已行进的距离（米）：含 offset，已按目标距离饱和；供 UI 侧判定「到点」用。
     *  非路线模式（摇杆/定点）返回 0：摇杆每帧重写 start_timestamp 使读数无意义，且 patchConfig
     *  可能残留上一路线会话的 route_distance_offset，若不 guard 会让 bySimulation 在 UI 0.0x 时即真、秒停。 */
    fun traveledMeters(config: JSONObject, now: Long = System.currentTimeMillis()): Double {
        if (!config.optBoolean("is_route_mode", false)) return 0.0
        val speed = config.optDouble("speed_m_s", 3.0).coerceAtLeast(0.1)
        val rawStartTime = config.optLong("start_timestamp", 0L)
        val startTime = if (rawStartTime > 0L) rawStartTime else now
        val elapsedSec = (now - startTime).coerceAtLeast(0L) / 1000.0
        val offset = config.optDouble("route_distance_offset", 0.0).coerceAtLeast(0.0)
        val traveled = offset + realismSession(config, startTime).distance(speed, elapsedSec)
        val targetTotal = config.optDouble("target_distance_m", 0.0)
        return if (targetTotal > 0.0) traveled.coerceAtMost(targetTotal) else traveled
    }

    /** 配置里缺少真实度字段（旧版 App 写的配置）时按"关闭 + 不浮动"处理，行为与旧版一致 */
    fun realismSession(config: JSONObject, startTimestamp: Long = config.optLong("start_timestamp", 0L)): MotionRealism.Session {
        val offset = config.optDouble("route_distance_offset", 0.0).coerceAtLeast(0.0)
        val targetTotal = config.optDouble("target_distance_m", 0.0)
        // 续跑时已跑的距离算在 offset 里，本段只需要跑剩下的部分
        val remaining = if (targetTotal > 0.0) (targetTotal - offset).coerceAtLeast(0.0) else 0.0
        return MotionRealism.session(
            startTimestamp,
            config.optInt("realism_level", MotionRealism.Level.OFF.id),
            config.optInt("speed_fluctuation_pct", 0),
            MotionRealism.SlowdownSettings(
                config.optInt("slowdown_pct", 0),
                config.optInt("slowdown_min_sec", 0),
                config.optInt("slowdown_max_sec", 0),
                config.optInt("slowdown_gap_sec", 0),
                config.optInt("slowdown_ramp_sec", 0)
            ),
            config.optInt("cadence_dip_pct", MotionRealism.DEFAULT_CADENCE_DIP_PCT),
            // 续跑时已经在跑动中，不再重新做起步加速
            rampSec = if (offset > 0.0) 0.0 else config.optDouble("speed_ramp_sec", MotionRealism.DEFAULT_SPEED_RAMP_SEC),
            targetDistanceM = remaining,
            decelDistanceM = config.optDouble("target_decel_m", MotionRealism.DEFAULT_DECEL_DISTANCE_M),
            baseSpeedMps = config.optDouble("speed_m_s", 0.0)
        )
    }
}
