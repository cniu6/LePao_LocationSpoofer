package com.kdzw8p.mqa.v71f.utils

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * 运动真实度引擎：给速度、步频（步幅）、海拔、加速度计波形加上自然起伏。
 *
 * 速度、步频这些"慢变量"都是以模拟开始时间为种子的确定性函数——system_server、每个被 Hook 的
 * App 进程、以及 App 自己的路线进度都在各自独立计算位置和步数，必须对同一时刻算出同样的结果，
 * 否则不同进程之间的距离、步数会对不上。随机强度为 [Level.OFF] 且速度浮动为 0 时退化为匀速匀步频。
 */
object MotionRealism {

    enum class Level(
        val id: Int,
        /** 步频独立于速度的相对起伏幅度 */
        val cadenceVariation: Double,
        /** 海拔随时间漂移的幅度（米），由 [AltitudeModel] 使用 */
        val altitudeAmplitudeM: Double,
        /** 每一步冲击力度的相对差异 */
        val stepAmplitudeVariation: Double,
        /** 加速度计白噪声标准差（m/s²） */
        val sensorNoise: Double
    ) {
        OFF(0, 0.0, 0.0, 0.0, 0.0),
        LOW(1, 0.015, 1.5, 0.05, 0.03),
        MEDIUM(2, 0.03, 3.0, 0.10, 0.06),
        HIGH(3, 0.05, 5.0, 0.18, 0.10);

        companion object {
            fun fromId(id: Int): Level = entries.firstOrNull { it.id == id } ?: MEDIUM
        }
    }

    const val DEFAULT_LEVEL_ID = 2
    const val DEFAULT_SPEED_FLUCTUATION_PCT = 10
    const val MAX_SPEED_FLUCTUATION_PCT = 30

    // 路径形态：横向抖动幅度倍率（100% = 现有默认摆动）与整条路线的横向平移（米）
    const val DEFAULT_SWAY_SCALE_PCT = 100
    const val MAX_SWAY_SCALE_PCT = 300
    const val DEFAULT_ROUTE_OFFSET_M = 0
    const val MAX_ROUTE_OFFSET_ABS_M = 20

    // 片段式降速：真人不会全程匀速，跑一段会自然放慢一段（体力、坡度、路口），再慢慢找回节奏。
    // 参数是平均降幅、单次最短 / 最长持续时间、两次降速之间恢复常态的平均间隔。
    const val DEFAULT_SLOWDOWN_PCT = 12
    const val MAX_SLOWDOWN_PCT = 70
    const val DEFAULT_SLOWDOWN_MIN_SEC = 20
    const val DEFAULT_SLOWDOWN_MAX_SEC = 60
    const val DEFAULT_SLOWDOWN_GAP_SEC = 90
    const val MIN_SLOWDOWN_MIN_SEC = 5
    const val MIN_SLOWDOWN_MAX_SEC = 10
    const val MIN_SLOWDOWN_GAP_SEC = 20
    const val MAX_SLOWDOWN_MIN_SEC = 180
    const val MAX_SLOWDOWN_MAX_SEC = 300
    const val MAX_SLOWDOWN_GAP_SEC = 600

    /** 缓入缓出时长：0 = 按每段时长自动推导（约 28%，3~15 秒）；指定后每段都用固定的余弦缓变时长 */
    const val DEFAULT_SLOWDOWN_RAMP_SEC = 0
    const val MIN_SLOWDOWN_RAMP_SEC = 2
    const val MAX_SLOWDOWN_RAMP_SEC = 60

    /** 降速块内步频跟随下降的百分比：真人放慢时步幅先缩短，步频只降一部分（默认 45%） */
    const val DEFAULT_CADENCE_DIP_PCT = 45
    const val MAX_CADENCE_DIP_PCT = 100

    /** 速度变化中由步频承担的比例，其余由步幅承担（步幅 = 速度 / 步频） */
    private const val CADENCE_SHARE_OF_SPEED = 0.4

    /** 默认起步加速时长（秒）：从 0 缓升到基准速度，模拟起步的那几秒 */
    const val DEFAULT_SPEED_RAMP_SEC = 8.0
    /** 默认到点前减速距离（米） */
    const val DEFAULT_DECEL_DISTANCE_M = 100.0

    private const val RUN_SPEED_THRESHOLD = 2.2
    private const val GRAVITY = 9.80665f

    private const val SPEED_SALT = 0x5EED5EEDL
    private const val CADENCE_SALT = 0x0CADE0CEL
    private const val STEP_SALT = 0x57E9057EL
    private const val SLOW_SALT = 0x510D0FFFL
    private const val CADENCE_WANDER_SALT = 0x3A0D1F5L
    private const val CADENCE_FAST_SALT = 0x9E3779B1L

    /** 步频独立的中频自然漂移幅度：真人固定步频也会每半分钟上下漂几个 SPM */
    private const val CADENCE_WANDER_SHARE = 0.08
    /** 更快的碎抖动：让步频曲线不会看起来是一条规整的低频正弦 */
    private const val CADENCE_FAST_SHARE = 0.035

    /**
     * 若干个周期、相位随机的正弦叠加成的平滑噪声，取值范围 [-1, 1]。
     * 用正弦叠加而不是随机游走，是因为它的积分有解析解：任意时刻的累计距离 / 步数都能直接算出来，
     * 不依赖逐帧累加的中间状态，所以各个进程在任意时刻都能独立得到一致的结果。
     */
    class SmoothNoise(seed: Long, minPeriodSec: Double, maxPeriodSec: Double, components: Int = 4) {
        private val periods: DoubleArray
        private val phases: DoubleArray
        private val weights: DoubleArray

        init {
            val rnd = Random(seed)
            periods = DoubleArray(components) { minPeriodSec * (maxPeriodSec / minPeriodSec).pow(rnd.nextDouble()) }
            phases = DoubleArray(components) { rnd.nextDouble() * 2 * PI }
            val raw = DoubleArray(components) { 0.5 + rnd.nextDouble() }
            val sum = raw.sum()
            weights = DoubleArray(components) { raw[it] / sum }
        }

        fun value(tSec: Double): Double {
            var v = 0.0
            for (i in periods.indices) v += weights[i] * sin(2 * PI * tSec / periods[i] + phases[i])
            // 校准到真实幅值：4 分量叠加的典型 RMS 只有 ~0.55，直接用会让 ±N% 设定实际只波动一半；
            // 除以 0.62 后典型值在 ±0.7~±0.9，偶尔满幅 clamp。integral 同步同一系数保持导数一致。
            return (v / 0.62).coerceIn(-1.0, 1.0)
        }

        /** 单向波动范围 [-1, 0]，使用未截断的加权正弦，保证解析积分与值一致。 */
        fun downwardValue(tSec: Double): Double {
            var v = 0.0
            for (i in periods.indices) v += weights[i] * sin(2 * PI * tSec / periods[i] + phases[i])
            return (v - 1.0) / 2.0
        }

        fun downwardIntegral(tSec: Double): Double = (0.62 * integral(tSec) - tSec) / 2.0

        /** ∫₀ᵗ value(τ) dτ */
        fun integral(tSec: Double): Double {
            var v = 0.0
            for (i in periods.indices) {
                val w = 2 * PI / periods[i]
                v += weights[i] / w * (cos(phases[i]) - cos(w * tSec + phases[i]))
            }
            return v / 0.62
        }
    }

    /**
     * 片段式降速参数。[pct] 是降幅百分比，[minSec] / [maxSec] 是单次降速的最短 / 最长持续时间，
     * [gapSec] 是两次降速之间恢复常态的平均间隔。pct 或时长任一为 0 表示关闭。
     */
    data class SlowdownSettings(
        val pct: Int = 0,
        val minSec: Int = 0,
        val maxSec: Int = 0,
        val gapSec: Int = DEFAULT_SLOWDOWN_GAP_SEC,
        val rampSec: Int = DEFAULT_SLOWDOWN_RAMP_SEC
    ) {
        val enabled: Boolean get() = pct > 0 && minSec > 0 && maxSec > 0

        fun normalized(): SlowdownSettings {
            if (!enabled) return OFF
            val lo = minSec.coerceIn(1, MAX_SLOWDOWN_MIN_SEC)
            val hi = maxSec.coerceIn(lo, MAX_SLOWDOWN_MAX_SEC)
            return SlowdownSettings(
                pct.coerceIn(1, MAX_SLOWDOWN_PCT),
                lo,
                hi,
                gapSec.coerceIn(0, MAX_SLOWDOWN_GAP_SEC),
                rampSec.coerceIn(0, MAX_SLOWDOWN_RAMP_SEC)
            )
        }

        companion object {
            val OFF = SlowdownSettings(0, 0, 0, 0, 0)
        }
    }

    class Session(
        val startTimestamp: Long,
        val level: Level,
        speedFluctuationPct: Int,
        slowdown: SlowdownSettings = SlowdownSettings.OFF,
        val cadenceDipPct: Int = DEFAULT_CADENCE_DIP_PCT,
        /** 起步加速时长（秒），0 = 立即达到基准速度 */
        val rampSec: Double = 0.0,
        /** 本段要跑的总距离（米），<=0 = 不限；到时速度归零、位置停住（定位照常上报） */
        val targetDistanceM: Double = 0.0,
        /** 到点前多少米开始减速 */
        val decelDistanceM: Double = 0.0,
        /** 基准速度，用于把目标距离换算成时间；<=0 时退化为不减速 */
        val baseSpeedMps: Double = 0.0
    ) {
        val speedFluctuationPct = speedFluctuationPct.coerceIn(0, MAX_SPEED_FLUCTUATION_PCT)
        val slowdown = slowdown.normalized()

        /** 降速时步频跟随下降的比例：步幅先缩短，步频只降一部分，比例可调 */
        private val cadenceDipShare = cadenceDipPct.coerceIn(0, MAX_CADENCE_DIP_PCT) / 100.0

        /** 真实度关闭时全部波动归零，保持旧版均匀运动的遗留语义 */
        private val wanderShare = if (level == Level.OFF) 0.0 else CADENCE_WANDER_SHARE
        // 碎抖动 likewise 在真实度关闭时归零，保持 OFF「匀速匀步频」的遗留语义（否则 steps/cadence 仍带噪声）
        private val fastShare = if (level == Level.OFF) 0.0 else CADENCE_FAST_SHARE
        private val fluctuation = this.speedFluctuationPct / 100.0
        private val speedNoise = SmoothNoise(startTimestamp xor SPEED_SALT, 20.0, 600.0)
        private val cadenceNoise = SmoothNoise(startTimestamp xor CADENCE_SALT, 3.0, 60.0)
        private val cadenceWander = SmoothNoise(startTimestamp xor CADENCE_WANDER_SALT, 15.0, 50.0)
        private val cadenceFast = SmoothNoise(startTimestamp xor CADENCE_FAST_SALT, 2.0, 9.0)
        private val dips = if (this.slowdown.enabled) {
            DipSchedule(startTimestamp xor SLOW_SALT, this.slowdown)
        } else null

        private fun variationAt(t: Double): Double = if (dips != null) speedNoise.downwardValue(t) else speedNoise.value(t)
        private fun variationIntegral(t: Double): Double = if (dips != null) speedNoise.downwardIntegral(t) else speedNoise.integral(t)

        // ---------------------------------------------------------------- 起步加速与到点减速
        // 两段都用余弦完成：速度连续、位置有解析式，多个进程按同一时刻算出的位移仍然一致。

        /** 减速段长度（米）：目标比减速距离还短时就只能整段减速 */
        private val decelSpanM: Double =
            if (targetDistanceM <= 0.0) 0.0 else decelDistanceM.coerceIn(0.0, targetDistanceM)

        /** 减速起点（秒）：基准速度走完「目标 - 减速段」的时刻 */
        private val decelStartSec: Double =
            if (decelSpanM <= 0.0 || baseSpeedMps <= 0.0) Double.POSITIVE_INFINITY
            else ((targetDistanceM - decelSpanM) / baseSpeedMps).coerceAtLeast(0.0)

        /** 减速段时长：∫ base·cos(πp/2) dt = base·T·2/π = decelSpanM */
        private val decelSpanSec: Double =
            if (decelSpanM <= 0.0 || baseSpeedMps <= 0.0) 0.0
            else PI * decelSpanM / (2 * baseSpeedMps)

        private val targetSec: Double = decelStartSec + decelSpanSec

        /** 起步加速段少走的等效时间：匀速会走 R，加速只走了 (2/π)R */
        private val rampLoss: Double = if (rampSec > 0.0) rampSec * (1 - 2 / PI) else 0.0

        /**
         * 速度倍率的积分（等效匀速时间）：乘基准速度就是不考虑噪声时的位移。
         * 到点后饱和在目标距离，位置不再前进。
         */
        fun motionIntegral(elapsedSec: Double): Double {
            val t = elapsedSec.coerceAtLeast(0.0)
            if (targetDistanceM > 0.0 && t >= targetSec) {
                return if (baseSpeedMps > 0.0) targetDistanceM / baseSpeedMps else 0.0
            }
            if (decelSpanSec > 0.0 && t >= decelStartSec) {
                val p = ((t - decelStartSec) / decelSpanSec).coerceIn(0.0, 1.0)
                val before = (decelStartSec - rampLoss).coerceAtLeast(0.0)
                return before + decelSpanSec * (2 / PI) * sin(PI * p / 2)
            }
            if (rampSec > 0.0 && t < rampSec) {
                return rampSec * (2 / PI) * (1 - cos(PI * t / (2 * rampSec)))
            }
            return t - rampLoss
        }

        /** 当前速度倍率：起步从 0 升到 1，到点前降到 0，其余恒为 1 */
        fun speedFactor(elapsedSec: Double): Double {
            val t = elapsedSec.coerceAtLeast(0.0)
            if (targetDistanceM > 0.0 && t >= targetSec) return 0.0
            if (decelSpanSec > 0.0 && t >= decelStartSec) {
                val p = ((t - decelStartSec) / decelSpanSec).coerceIn(0.0, 1.0)
                return cos(PI * p / 2)
            }
            if (rampSec > 0.0 && t < rampSec) return sin(PI * t / (2 * rampSec))
            return 1.0
        }

        /** 当前时刻由降速块造成的速度倍率偏移（只减速，恢复到基准后为 0）。 */
        fun slowdownAt(elapsedSec: Double): Double = dips?.envelope(elapsedSec) ?: 0.0

        fun speed(baseSpeed: Double, elapsedSec: Double): Double {
            // 由解析距离的对称差分给出：速度与位置导数天然一致，起步/减速/到点都走同一条曲线，
            // 不会再出现「速度值与累计距离对不上」的破绽。差分窗口远小于最短噪声周期。
            val h = 0.25
            val lo = (elapsedSec - h).coerceAtLeast(0.0)
            val hi = elapsedSec + h
            return (distance(baseSpeed, hi) - distance(baseSpeed, lo)) / (hi - lo)
        }

        /** 从开始到 elapsedSec 的累计行进距离（米）；设了目标距离时到点饱和，不再增长 */
        fun distance(baseSpeed: Double, elapsedSec: Double): Double {
            val motion = motionIntegral(elapsedSec)
            val noise = fluctuation * variationIntegral(elapsedSec) + (dips?.integral(elapsedSec) ?: 0.0)
            val raw = baseSpeed * (motion + noise)
            return if (targetDistanceM > 0.0) raw.coerceIn(0.0, targetDistanceM) else raw
        }

        fun cadence(baseCadenceSpm: Double, elapsedSec: Double): Double = baseCadenceSpm * speedFactor(elapsedSec) * (
            1 + CADENCE_SHARE_OF_SPEED * fluctuation * variationAt(elapsedSec) +
                0.5 * level.cadenceVariation * cadenceNoise.value(elapsedSec) +
                wanderShare * cadenceWander.value(elapsedSec) +
                fastShare * cadenceFast.value(elapsedSec) +
                cadenceDipShare * slowdownAt(elapsedSec)
            )

        /** 从开始到 elapsedSec 的累计步数（连续值，整数部分是步数，小数部分是当前这一步的相位） */
        fun steps(baseCadenceSpm: Double, elapsedSec: Double): Double = baseCadenceSpm / 60.0 * (
            motionIntegral(elapsedSec) +
                CADENCE_SHARE_OF_SPEED * fluctuation * variationIntegral(elapsedSec) +
                0.5 * level.cadenceVariation * cadenceNoise.integral(elapsedSec) +
                wanderShare * cadenceWander.integral(elapsedSec) +
                fastShare * cadenceFast.integral(elapsedSec) +
                cadenceDipShare * (dips?.integral(elapsedSec) ?: 0.0)
            )

        /**
         * 加速度计读数（含重力，m/s²），坐标约定：Z 竖直、Y 前后、X 左右。
         * 有录制的步态模板时按模板回放，否则用内置的步行 / 跑步波形；两者都会叠加逐步的力度差异和传感器噪声。
         */
        fun accelerometer(
            stepsFloat: Double,
            speed: Double,
            template: GaitTemplate?,
            noise: Random,
            /** 当前降速块的倍率偏移，放慢时逐步力度与传感器噪声一起收敛，避免"越慢越抖" */
            slowdown: Double = 0.0
        ): FloatArray {
            val stepIndex = floor(stepsFloat).toLong()
            val stepPhase = stepsFloat - stepIndex
            val damp = (1 + slowdown).coerceIn(0.25, 1.0)
            val strength = 1 + level.stepAmplitudeVariation * damp * hashUnit(startTimestamp xor STEP_SALT, stepIndex)
            val out = if (template != null) {
                template.sample(stepsFloat / 2.0, strength)
            } else {
                proceduralGait(stepPhase, stepsFloat, stepIndex, speed, strength)
            }
            if (level.sensorNoise > 0) {
                for (i in 0..2) out[i] += (noise.nextGaussian() * level.sensorNoise * damp).toFloat()
            }
            return out
        }
    }

    @Volatile
    private var cached: Session? = null

    /** 同一次模拟会话内复用同一个 Session，避免在高频 Hook 里反复初始化噪声参数 */
    fun session(
        startTimestamp: Long,
        levelId: Int,
        speedFluctuationPct: Int,
        slowdown: SlowdownSettings = SlowdownSettings.OFF,
        cadenceDipPct: Int = DEFAULT_CADENCE_DIP_PCT,
        rampSec: Double = 0.0,
        targetDistanceM: Double = 0.0,
        decelDistanceM: Double = 0.0,
        baseSpeedMps: Double = 0.0
    ): Session {
        val level = Level.fromId(levelId)
        val pct = speedFluctuationPct.coerceIn(0, MAX_SPEED_FLUCTUATION_PCT)
        val slow = slowdown.normalized()
        val dip = cadenceDipPct.coerceIn(0, MAX_CADENCE_DIP_PCT)
        val ramp = rampSec.coerceIn(0.0, 60.0)
        val target = targetDistanceM.coerceAtLeast(0.0)
        val decel = decelDistanceM.coerceAtLeast(0.0)
        val base = baseSpeedMps.coerceAtLeast(0.0)
        val c = cached
        if (c != null && c.startTimestamp == startTimestamp && c.level == level &&
            c.speedFluctuationPct == pct && c.slowdown == slow &&
            c.cadenceDipPct == dip && c.rampSec == ramp && c.targetDistanceM == target &&
            c.decelDistanceM == decel && c.baseSpeedMps == base
        ) return c
        return Session(startTimestamp, level, pct, slow, dip, ramp, target, decel, base).also { cached = it }
    }

    // ---------------------------------------------------------------- 内置步态波形

    private const val SHAPE_SAMPLES = 256

    private fun bump(p: Double, center: Double, width: Double): Double {
        var d = abs(p - center)
        if (d > 0.5) d = 1 - d
        return exp(-0.5 * (d / width) * (d / width))
    }

    /** 采样成查表并去掉均值：真实加速度计在一个步态周期内的平均值必须等于重力，否则长时间积分会暴露 */
    private fun zeroMeanTable(shape: (Double) -> Double): DoubleArray {
        val table = DoubleArray(SHAPE_SAMPLES) { shape(it.toDouble() / SHAPE_SAMPLES) }
        val mean = table.average()
        for (i in table.indices) table[i] -= mean
        return table
    }

    // 步行：脚跟着地冲击 → 支撑期回落 → 蹬地第二峰 → 摆动期低谷
    private val WALK_VERTICAL = zeroMeanTable { p ->
        1.0 * bump(p, 0.00, 0.045) - 0.45 * bump(p, 0.13, 0.07) + 0.55 * bump(p, 0.42, 0.09) - 0.65 * bump(p, 0.72, 0.13)
    }
    private val WALK_FORWARD = zeroMeanTable { p ->
        -0.5 * bump(p, 0.03, 0.05) + 0.45 * bump(p, 0.40, 0.09) - 0.15 * bump(p, 0.75, 0.15)
    }

    // 跑步：尖锐的落地冲击 + 支撑期主峰 + 腾空期接近失重
    private val RUN_VERTICAL = zeroMeanTable { p ->
        1.0 * bump(p, 0.00, 0.035) + 0.55 * bump(p, 0.16, 0.09) - 0.85 * bump(p, 0.62, 0.18)
    }
    private val RUN_FORWARD = zeroMeanTable { p ->
        -0.6 * bump(p, 0.02, 0.04) + 0.5 * bump(p, 0.25, 0.08)
    }

    private fun lookup(table: DoubleArray, phase: Double): Double {
        val pos = (phase - floor(phase)) * table.size
        val i = pos.toInt() % table.size
        val frac = pos - floor(pos)
        return table[i] * (1 - frac) + table[(i + 1) % table.size] * frac
    }

    private fun proceduralGait(
        stepPhase: Double,
        stepsFloat: Double,
        stepIndex: Long,
        speed: Double,
        strength: Double
    ): FloatArray {
        val running = speed >= RUN_SPEED_THRESHOLD
        val amplitude = if (running) 6.0 + 1.5 * speed else 2.0 + 1.0 * speed
        val vertical = lookup(if (running) RUN_VERTICAL else WALK_VERTICAL, stepPhase)
        val forward = lookup(if (running) RUN_FORWARD else WALK_FORWARD, stepPhase)
        // 左右方向：以一个跨步（两步）为周期摆动，左右脚着地时的侧向冲击方向相反
        val side = if (stepIndex % 2 == 0L) 1.0 else -1.0
        val lateral = 0.12 * amplitude * sin(PI * stepsFloat) + 0.1 * amplitude * side * bump(stepPhase, 0.02, 0.05)

        val z = (GRAVITY + strength * amplitude * vertical).coerceAtLeast(0.0)
        return floatArrayOf(
            (strength * lateral).toFloat(),
            (strength * 0.35 * amplitude * forward).toFloat(),
            z.toFloat()
        )
    }

}

/**
 * 片段式降速的确定性时间表。第 n 块的时长、降幅、恢复间隔全部由 (seed, n) 的哈希决定，
 * 所以 system_server、各 Hook 进程和 App 对同一时刻会得到同一条曲线，累计距离/步数不会互相漂移。
 *
 * 每块形状：余弦缓入 → 低速保持 → 余弦缓出到基准速度，不补偿加速。
 * 降速倍率的面积（∫倍率 dt）有解析解，和 [MotionRealism.SmoothNoise] 一样不依赖逐帧累加。
 */
private class DipSchedule(
    private val seed: Long,
    settings: MotionRealism.SlowdownSettings
) {
    private val depthBase = settings.pct / 100.0
    private val minDuration = settings.minSec.toDouble()
    private val maxDuration = settings.maxSec.coerceAtLeast(settings.minSec).toDouble()
    private val gapBase = settings.gapSec.toDouble()
    private val rampSec = settings.rampSec

    // 追加式数组：下标是块序号，块按时间顺序生成后不再修改，读线程二分查找即可
    private val starts = ArrayList<Double>()
    private val depths = ArrayList<Double>()
    private val ramps = ArrayList<Double>()
    private val holds = ArrayList<Double>()
    private val ends = ArrayList<Double>()

    /** areas[i] = 第 i 块开始前的累计面积 */
    private val areas = ArrayList<Double>()
    private var nextStart = Double.NaN

    @Synchronized
    private fun ensure(tSec: Double) {
        if (nextStart.isNaN()) nextStart = gap(0)
        var guard = 0
        while (nextStart <= tSec && guard++ < MAX_BLOCKS) appendBlock(nextStart)
    }

    private fun appendBlock(start: Double) {
        val i = starts.size.toLong()
        val duration = minDuration + (maxDuration - minDuration) * unit(seed, i * 6)
        val depthUnit = unit(seed, i * 6 + 1)
        // 配置降幅是实际深度上限，不能把多数块缩成配置的两三成。
        val depthMix = 0.85 + 0.15 * depthUnit
        val depth = depthBase * depthMix
        // 缓入缓出时长：用户指定秒数优先；自动模式下走路块的缓变比微降块更长，姿态切换更自然
        val ramp = if (rampSec > 0) {
            rampSec.toDouble().coerceAtMost(duration / 2.0).coerceAtLeast(1.0)
        } else {
            // 至少半段维持低速，避免整段都在缓变，地图采样看不到明显慢速段。
            (duration * 0.25).coerceIn(1.0, 15.0).coerceAtMost(duration / 2.0)
        }
        val hold = (duration - 2 * ramp).coerceAtLeast(1.0)

        starts += start
        depths += depth
        ramps += ramp
        holds += hold
        // 历史块的总面积必须在追加新块时累计。旧实现每次都写 0，第二块开始会让
        // distance() 丢掉此前所有降速块的积分，表现为换块时位置突然回跳。
        areas += (areas.lastOrNull() ?: 0.0) + if (starts.size == 1) 0.0 else totalArea(starts.size - 2)
        val end = start + 2 * ramp + hold
        ends += end
        // 深块（累到走路）之后需要更长的恢复间隔；浅块间隔略缩短
        nextStart = end + gap(i + 1) * (0.7 + 0.9 * depthMix)
    }

    private fun totalArea(i: Int): Double =
        -depths[i] * (holds[i] + ramps[i])

    /** 第 n 个恢复段的时长：以配置间隔为中值上下浮动，避免降速节奏机械重复 */
    private fun gap(n: Long): Double = gapBase * (0.55 + 0.9 * unit(seed, n * 6 + 5))

    /** 二分查找到 t 所在的块；t 落在该块之后的恢复段时仍返回该块（倍率为 0、面积已满） */
    private fun indexAt(tSec: Double): Int {
        var low = 0
        var high = starts.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= tSec) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    @Synchronized
    fun envelope(tSec: Double): Double {
        ensure(tSec)
        val i = indexAt(tSec)
        if (i < 0) return 0.0
        val local = tSec - starts[i]
        val ramp = ramps[i]
        val hold = holds[i]
        val depth = depths[i]
        if (local < ramp) return -depth * 0.5 * (1 - cos(PI * local / ramp))
        if (local < ramp + hold) return -depth
        if (local < 2 * ramp + hold) return -depth * 0.5 * (1 + cos(PI * (local - ramp - hold) / ramp))
        return 0.0
    }

    @Synchronized
    fun integral(tSec: Double): Double {
        ensure(tSec)
        val i = indexAt(tSec)
        if (i < 0) return 0.0
        return areas[i] + partialArea(i, tSec)
    }

    private fun partialArea(i: Int, tSec: Double): Double {
        val local = tSec - starts[i]
        if (local <= 0.0) return 0.0
        val ramp = ramps[i]
        val hold = holds[i]
        val depth = depths[i]
        val total = totalArea(i)
        if (local >= ends[i] - starts[i]) return total
        if (local < ramp) {
            val u = local / ramp
            return -depth * ramp * (u / 2 - sin(PI * u) / (2 * PI))
        }
        if (local < ramp + hold) return -depth * (ramp / 2 + (local - ramp))
        if (local < 2 * ramp + hold) {
            val u = (local - ramp - hold) / ramp
            return -depth * (ramp / 2 + hold + ramp * (u / 2 + sin(PI * u) / (2 * PI)))
        }
        return total
    }

    private companion object {
        /** 每天最多 4 位数的降速块；上限只用于防御异常大的 t 导致死循环 */
        const val MAX_BLOCKS = 20000
    }
}

/** 把 [hashUnit] 的 [-1, 1] 映射到 [0, 1] */
private fun unit(seed: Long, n: Long): Double = (hashUnit(seed, n) + 1.0) / 2

/** 由 (seed, n) 确定的 [-1, 1] 伪随机数：同一步在不同进程里算出同样的力度 */
private fun hashUnit(seed: Long, n: Long): Double {
    var x = seed xor (n * -0x61c8864680b583ebL)
    x = (x xor (x ushr 33)) * -0xae502812aa7333L
    x = (x xor (x ushr 33)) * -0x3b314601e57a13adL
    x = x xor (x ushr 33)
    return (x ushr 11).toDouble() / (1L shl 53).toDouble() * 2 - 1
}
