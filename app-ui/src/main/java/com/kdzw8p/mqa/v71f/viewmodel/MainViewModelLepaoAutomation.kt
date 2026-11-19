package com.kdzw8p.mqa.v71f.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val LEPAO_PKG = "com.lptiyu.tanke"
private const val ACTION_RUN_START = "com.kdzw8p.mqa.v71f.AUTO_RUN_START"
private const val ACTION_RUN_DONE = "com.kdzw8p.mqa.v71f.AUTO_RUN_DONE"
private const val ACTION_RUN_STATUS = "com.kdzw8p.mqa.v71f.RUN_STATUS"

/** 每账号看门狗余量：目标公里 × 15 分钟/公里，再加 10 分钟缓冲，超时判失败跳过，避免整批永久阻塞。 */
private const val WATCHDOG_MS_PER_KM = 15L * 60L * 1000L
private const val WATCHDOG_BUFFER_MS = 10L * 60L * 1000L
/** 宿主状态中断超过它才重发 START；需大于宿主 finishAutomation 的 12s 上传窗口，避免收尾竞态重发。 */
private const val STATUS_STALE_MS = 20_000L
/** 直线兜底里程余量系数：略大于目标，配合宿主到点判定保证模拟里程压过阈值。 */
private const val FALLBACK_DISTANCE_MARGIN = 1.2

private var lepaoAutomationJob: Job? = null

/** 单个账号在一键/批量跑里的阶段，给状态窗展示。 */
data class LepaoAutomationItem(val name: String, val phase: String)

data class LepaoAutomationState(
    val total: Int,
    val index: Int,
    val account: String,
    val phase: String,
    val km: Double,
    val items: List<LepaoAutomationItem>,
    val finished: Boolean = false
)

private val _lepaoAutomationState = MutableStateFlow<LepaoAutomationState?>(null)
val lepaoAutomationState: StateFlow<LepaoAutomationState?> = _lepaoAutomationState.asStateFlow()

fun dismissLepaoAutomation() {
    _lepaoAutomationState.value = null
}

fun cancelLepaoAutomation() {
    lepaoAutomationJob?.cancel()
    lepaoAutomationJob = null
    _lepaoAutomationState.value = _lepaoAutomationState.value?.copy(finished = true, phase = "已取消")
}

/**
 * 一键跑的轨迹来源：当前画布路线 > 路线库最新一条 > 打卡点环线 > 当前位置的直线兜底。
 * 打卡点环线放在路线库后面：用户存了自己跑过的路线时应该先跑那条，环线只当兜底。
 * 返回用了哪条，给状态窗显示，避免“明明有路线库却说没选路线”。
 */
private fun MainViewModel.ensureRunRoute(km: Double, account: String = ""): String {
    if (_uiState.value.routePoints.size >= 2) return "当前路线"
    val saved = _uiState.value.savedRoutes.firstOrNull { it.points.size >= 2 }
    if (saved != null) {
        _uiState.update {
            it.copy(
                routePoints = saved.points,
                routePlanStage = com.kdzw8p.mqa.v71f.data.model.RoutePlanStage.READY
            )
        }
        return saved.name
    }
    val lastRoute = parseRoutePoints(settingsRepository.lepaoLastRouteJson)
    if (lastRoute.size >= 2) {
        _uiState.update {
            it.copy(
                routePoints = lastRoute,
                routePlanStage = com.kdzw8p.mqa.v71f.data.model.RoutePlanStage.READY
            )
        }
        return "上次路线"
    }
    checkpointLoopRoute(account, km)?.let { return it }
    val s = _uiState.value
    val lat = s.latitudeInput.toDoubleOrNull() ?: return "无路线"
    val lng = s.longitudeInput.toDoubleOrNull() ?: return "无路线"
    val cosLat = kotlin.math.cos(Math.toRadians(lat)).let { if (it > 0.01) it else 0.01 }
    val dLng = km * FALLBACK_DISTANCE_MARGIN / (111.32 * cosLat)
    _uiState.update {
        it.copy(routePoints = listOf(
            com.kdzw8p.mqa.v71f.data.model.RoutePoint(lat, lng),
            com.kdzw8p.mqa.v71f.data.model.RoutePoint(lat, lng + dLng)
        ))
    }
    return "自动直线"
}

private fun parseRoutePoints(json: String): List<com.kdzw8p.mqa.v71f.data.model.RoutePoint> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(com.kdzw8p.mqa.v71f.data.model.RoutePoint(o.optDouble("lat"), o.optDouble("lng")))
            }
        }
    }.getOrDefault(emptyList())
}

/**
 * 定向打卡跑区要求按顺序经过打卡点才算打卡（乐跑自述「经过时系统会自动打卡」，判定半径
 * 就是 run_line_info.log_max_distance，实测 30 米）。路线库里的历史轨迹是绕操场跑的，
 * 常常擦着判定圈边缘过去，于是出现「打卡次数过少，未达到合格标准」。
 *
 * 这里直接用 beforeRunV260 下发的 run_line_info.point_list 生成一条闭合环线：
 * 按 index 串起所有打卡点，重复绕圈直到里程覆盖目标，末段按比例截断。
 */
private fun MainViewModel.checkpointLoopRoute(account: String, km: Double): String? {
    if (account.isBlank() || km <= 0.0) return null
    val rule = runCatching {
        settingsRepository.getLepaoProfile(account)?.optString("run_rule_json").orEmpty()
    }.getOrNull().orEmpty()
    if (rule.isBlank()) return null
    val lineInfo = runCatching {
        org.json.JSONObject(rule).optJSONObject("run_line_info")
    }.getOrNull() ?: return null
    val arr = lineInfo.optJSONArray("point_list") ?: return null

    val control = ArrayList<com.kdzw8p.mqa.v71f.utils.CoordinateUtils.LatLng>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val jw = o.optString("jingwei").split(",")
        if (jw.size != 2) continue
        val lat = jw[0].trim().toDoubleOrNull() ?: continue
        val lng = jw[1].trim().toDoubleOrNull() ?: continue
        if (lat == 0.0 && lng == 0.0) continue
        control.add(com.kdzw8p.mqa.v71f.utils.CoordinateUtils.LatLng(lat, lng))
    }
    // 按坐标去重（固定 Locale.US，避免逗号小数 Locale 影响键的一致性）
    val distinct = control.distinctBy { String.format(java.util.Locale.US, "%.6f,%.6f", it.lat, it.lng) }
    if (distinct.size < 2) return null

    // 环几何（排序/体育场形/Catmull-Rom/截断）在 core-geo 的 RingRouteBuilder，纯 JVM 可单测。
    val out = com.kdzw8p.mqa.v71f.utils.RingRouteBuilder.buildLoop(distinct, km * 1000.0) ?: return null
    _uiState.update {
        it.copy(
            routePoints = out.map { p -> com.kdzw8p.mqa.v71f.data.model.RoutePoint(p.lat, p.lng) },
            routePlanStage = com.kdzw8p.mqa.v71f.data.model.RoutePlanStage.READY
        )
    }
    val per = String.format(
        java.util.Locale.US, "%.0f",
        com.kdzw8p.mqa.v71f.utils.RingRouteBuilder.perimeterOf(distinct)
    )
    return "打卡点环线(${distinct.size}点·${per}m/圈)"
}

private fun MainViewModel.bringHostToFront() {
    runCatching {
        val launch = context.packageManager.getLaunchIntentForPackage(LEPAO_PKG) ?: return
        // 只用 NEW_TASK 把现有任务拉到前台；不加 RESET_TASK_IF_NEEDED，避免宿主任务由深链/通知
        // 等其它入口创建时被重置到初始状态、finish 掉正在进行的跑步页。
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
    }
}

/** 把异步切号的 onDone 回执转成挂起结果：成功（onSuccess 触发）返回 true，任何失败/锁命中返回 false。 */
private suspend fun MainViewModel.switchLepaoAccountAwait(name: String): Boolean =
    suspendCancellableCoroutine { cont ->
        var ok = false
        switchLepaoAccount(
            name,
            onSuccess = { ok = true },
            onDone = { if (cont.isActive) cont.resume(ok) }
        )
    }

fun MainViewModel.getLepaoAutoStopKmSetting(): String = settingsRepository.lepaoAutoStopKm

fun MainViewModel.getLepaoRunKmMale(): String = settingsRepository.lepaoRunKmMale

fun MainViewModel.getLepaoRunKmFemale(): String = settingsRepository.lepaoRunKmFemale

fun MainViewModel.setLepaoRunKm(male: String, female: String) {
    settingsRepository.lepaoRunKmMale = male
    settingsRepository.lepaoRunKmFemale = female
}

fun MainViewModel.getLepaoMinPaceMale(): String = settingsRepository.lepaoMinPaceMaleSec

fun MainViewModel.getLepaoMinPaceFemale(): String = settingsRepository.lepaoMinPaceFemaleSec

fun MainViewModel.setLepaoMinPace(male: String, female: String) {
    settingsRepository.lepaoMinPaceMaleSec = male
    settingsRepository.lepaoMinPaceFemaleSec = female
}

/** 当前账号的最低平均配速（秒/公里）：按身份性别取男女各自的设置；0 = 不限制 */
internal fun MainViewModel.minPaceSecFor(name: String): Double {
    val identity = settingsRepository.getLepaoProfile(name)?.optString("lepao_identity_json").orEmpty()
    val sex = runCatching { org.json.JSONObject(identity).optString("sex") }.getOrDefault("")
    val raw = if (sex.contains("女")) settingsRepository.lepaoMinPaceFemaleSec
    else settingsRepository.lepaoMinPaceMaleSec
    return raw.toDoubleOrNull()?.coerceIn(0.0, 1200.0) ?: 0.0
}

/**
 * 按账号身份性别选目标公里：女用女性值、其余用男性值。
 * 参数 >0 时优先（用户在确认框里改过），否则读设置里的男女默认（女 1.5 / 男 1.8）。
 */
internal fun MainViewModel.targetKmFor(name: String, kmMale: Double, kmFemale: Double): Double {
    val male = if (kmMale > 0) kmMale
        else settingsRepository.lepaoRunKmMale.toDoubleOrNull() ?: 1.8
    val female = if (kmFemale > 0) kmFemale
        else settingsRepository.lepaoRunKmFemale.toDoubleOrNull() ?: 1.5
    val identity = settingsRepository.getLepaoProfile(name)?.optString("lepao_identity_json").orEmpty()
    val sex = runCatching { org.json.JSONObject(identity).optString("sex") }.getOrDefault("")
    return if (sex.contains("女")) female else male
}

/**
 * 一键跑/批量跑：切到目标账户 → 路线从起点重开 → 通知宿主自动进入跑步；
 * 宿主到点结束并上报后回传 AUTO_RUN_DONE，再处理下一个账户。
 * 状态通过 [lepaoAutomationState] 暴露给状态窗（进度条 + 每个账号阶段）。
 */
fun MainViewModel.runLepaoAutomation(names: List<String>, kmMale: Double = 0.0, kmFemale: Double = 0.0) {
    if (names.isEmpty()) return
    if (lepaoAutomationJob?.isActive == true) {
        Toast.makeText(context, "自动跑正在进行中", Toast.LENGTH_SHORT).show()
        return
    }
    if (kmMale <= 0.0 && kmFemale <= 0.0) {
        Toast.makeText(context, "目标公里要大于 0", Toast.LENGTH_LONG).show()
        return
    }
    // 按 user_id 去重：两个不同名档案可能含同一 user_id（手工导入/编辑），避免给同一账号跑两次。
    // user_id 为空的档案按名字保留，不误并。
    val accounts = names.distinctBy { n ->
        settingsRepository.getLepaoProfile(n)?.optString("user_id").orEmpty().ifBlank { n }
    }
    if (accounts.isEmpty()) return
    // 路线优先用当前画布上的；没有就用路线库里最新一条；都没有才用打卡点环线/直线兜底。
    // 传首个账号：无画布/库/上次路线时打卡点环线兜底才可达（checkpointLoopRoute 需要 account）。
    ensureRunRoute(maxOf(kmMale, kmFemale, 1.8), accounts.first())
    if (_uiState.value.routePoints.size < 2) {
        Toast.makeText(context, "先在路线页选一条路线，或在定位页放一个点", Toast.LENGTH_LONG).show()
        return
    }
    // 锁定本次批量用的路线：每个号结束会 stopRoutePlanning 清空画布，不锁住第二个号就掉回兜底。
    val lockedRoute = _uiState.value.routePoints
    val phases = accounts.map { LepaoAutomationItem(it, "待跑") }.toMutableList()
    // 每个账号的目标公里不同（按性别），状态窗显示当前账号的那个
    var currentKm = 0.0
    fun publish(index: Int, account: String, phase: String, finished: Boolean = false) {
        _lepaoAutomationState.value = LepaoAutomationState(
            total = accounts.size, index = index, account = account, phase = phase,
            km = currentKm, items = phases.toList(), finished = finished
        )
    }
    publish(0, accounts.first(), "准备中")

    lepaoAutomationJob = viewModelScope.launch {
        try {
            for ((i, name) in accounts.withIndex()) {
                val targetKm = targetKmFor(name, kmMale, kmFemale)
                currentKm = targetKm
                // 每账号一个随机令牌：随 START 下发、宿主回传携带，receiver 校验，防第三方伪造与跨账号串扰。
                val runToken = java.util.UUID.randomUUID().toString()
                var completed = false
                var receiver: BroadcastReceiver? = null
                try {
                    phases[i] = LepaoAutomationItem(name, "切换账号 · 目标 ${String.format(java.util.Locale.US, "%.2f", targetKm)} km")
                    publish(i, name, phases[i].phase)
                    // 等切号真正完成（含离线写回/宿主写回），不再盲等 6s；失败/锁命中/超时跳过该账号。
                    val switched = withTimeoutOrNull(95_000) { switchLepaoAccountAwait(name) } ?: false
                    if (!switched) {
                        phases[i] = LepaoAutomationItem(name, "切换失败，跳过")
                        publish(i, name, phases[i].phase)
                        continue
                    }
                    delay(2500) // 宿主冷启动缓冲

                    phases[i] = LepaoAutomationItem(name, "开始路线")
                    publish(i, name, "开始路线")
                    // 上一个号结束时 stopRoutePlanning 清空了画布，这里先放回锁定的那条路线。
                    if (lockedRoute.size >= 2 && _uiState.value.routePoints.size < 2) {
                        _uiState.update { it.copy(routePoints = lockedRoute) }
                    }
                    val routeName = ensureRunRoute(targetKm, name)
                    phases[i] = LepaoAutomationItem(name, "路线：${routeName}（循环）")
                    publish(i, name, "路线：${routeName}（循环）")
                    // 传入账号目标公里与账号：饱和目标/配速快照按本账号写，避免切号未提交时用旧账号。
                    startRouteFromBeginning(targetKm, name)
                    // 手动模式的路线以暂停状态加载，一键跑替用户按下开始，否则位置不动里程停在 0.0x。
                    delay(1500)
                    motionController.resume()

                    phases[i] = LepaoAutomationItem(name, "等待开跑")
                    publish(i, name, "等待开跑")
                    val done = CompletableDeferred<Boolean>()
                    var lastStatusAt = System.currentTimeMillis()
                    val accountStartAt = System.currentTimeMillis()
                    val maxMs = (targetKm * WATCHDOG_MS_PER_KM).toLong() + WATCHDOG_BUFFER_MS
                    var runKm = 0.0
                    var runSpeed = 0.0
                    var runPaused = false
                    var runElapsed = ""
                    var runPhase = ""
                    val recv = object : BroadcastReceiver() {
                        override fun onReceive(c: Context?, intent: Intent?) {
                            // 令牌不匹配即丢弃：防第三方伪造 DONE/STATUS，也防上一账号迟到的回执串扰。
                            if (intent?.getStringExtra("token") != runToken) return
                            when (intent.action) {
                                ACTION_RUN_DONE -> done.complete(true)
                                ACTION_RUN_STATUS -> {
                                    lastStatusAt = System.currentTimeMillis()
                                    runKm = intent.getDoubleExtra("km", runKm)
                                    runSpeed = intent.getDoubleExtra("speed", runSpeed)
                                    runPaused = intent.getBooleanExtra("paused", runPaused)
                                    runElapsed = intent.getStringExtra("elapsed").orEmpty()
                                    runPhase = intent.getStringExtra("phase").orEmpty()
                                }
                            }
                        }
                    }
                    receiver = recv
                    context.registerReceiver(recv, IntentFilter().apply {
                        addAction(ACTION_RUN_DONE)
                        addAction(ACTION_RUN_STATUS)
                    }, Context.RECEIVER_EXPORTED)

                    fun sendStart() {
                        context.sendBroadcast(
                            Intent(ACTION_RUN_START).setPackage(LEPAO_PKG)
                                .putExtra("km", targetKm.toString()).putExtra("token", runToken)
                        )
                    }
                    // 宿主进程可能还在启动：重发几次，直到它回执或进入超时等待
                    repeat(8) { pulse ->
                        if (done.isCompleted) return@repeat
                        if (pulse < 2) bringHostToFront()
                        sendStart()
                        delay(2500)
                    }
                    phases[i] = LepaoAutomationItem(name, "跑步中")
                    publish(i, name, "跑步中")
                    // 看门狗兜底：宿主卡死/放弃时超时跳过，绝不永久阻塞整批。
                    while (!done.isCompleted) {
                        delay(3000)
                        if (done.isCompleted) break
                        val now = System.currentTimeMillis()
                        if (now - accountStartAt > maxMs) break
                        // 宿主正在结束/上传（finishAutomation 的 12s 窗口）：只展示，不重发 START。
                        if (runPhase == "uploading") {
                            val up = "跑步中 · 上传中"
                            if (phases[i].phase != up) {
                                phases[i] = LepaoAutomationItem(name, up)
                                publish(i, name, up)
                            }
                            continue
                        }
                        if (now - lastStatusAt > STATUS_STALE_MS) {
                            bringHostToFront()
                            sendStart()
                            lastStatusAt = now
                            val lost = "跑步中 · 宿主不在前台，正在拉回"
                            if (phases[i].phase != lost) {
                                phases[i] = LepaoAutomationItem(name, lost)
                                publish(i, name, lost)
                            }
                            continue
                        }
                        val detail = String.format(
                            java.util.Locale.US, "跑步中 %.2f/%.2f km · %.1f m/s%s%s",
                            runKm, targetKm, runSpeed,
                            if (runPaused) " · 已暂停" else "",
                            if (runElapsed.isNotBlank()) " · 用时 $runElapsed" else ""
                        )
                        if (phases[i].phase != detail) {
                            phases[i] = LepaoAutomationItem(name, detail)
                            publish(i, name, detail)
                        }
                    }
                    completed = done.isCompleted
                    if (completed) {
                        phases[i] = LepaoAutomationItem(name, "完成")
                        publish(i, name, "完成")
                        // 定位撑到乐跑上传完成：宿主确认后 12s 才回执，这里再留 2s 缓冲才停模拟。
                        delay(2000)
                        runCatching { stopRoutePlanning() }
                        runCatching { locationRepository.setFloatingJoystickVisible(context, false) }
                        runCatching { refreshLepaoProfileStats(name) }
                    } else {
                        phases[i] = LepaoAutomationItem(name, "超时跳过")
                        publish(i, name, "超时跳过")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 单账号异常不杀死整批：记录并继续下一个。
                    phases[i] = LepaoAutomationItem(name, "异常跳过：${e.message ?: e.javaClass.simpleName}")
                    publish(i, name, phases[i].phase)
                } finally {
                    // 取消/异常时只注销 receiver，不停模拟：停模拟会让乐跑位置回落真实 GPS 造成突然漂移。
                    // 保持原有设计——取消仅中止编排，模拟与宿主跑步维持现状，由用户手动停止。
                    runCatching { receiver?.let { context.unregisterReceiver(it) } }
                }
            }
            publish(accounts.size, "", "全部完成", finished = true)
            // 单账号且确实跑完了才自动关窗
            if (accounts.size == 1 && _lepaoAutomationState.value?.items?.all { it.phase == "完成" } == true) {
                delay(2500)
                dismissLepaoAutomation()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 外层兜底：任何未预期异常也要发布 finished，状态窗不卡死。
            publish(accounts.size, "", "异常终止：${e.message ?: e.javaClass.simpleName}", finished = true)
        }
    }
}
