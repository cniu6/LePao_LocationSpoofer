package com.kdzw8p.mqa.v71f.xposed.hooks

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.kdzw8p.mqa.v71f.xposed.utils.XposedBridge
import org.json.JSONObject

/**
 * 到点自动结束：跑步页布局里里程是 `tv_distance_value`（公里），结束按钮是
 * `tv_stoprun`（TextProgressBar，按住才走完进度）。到阈值后只对结束按钮合成一次
 * 长按，由乐跑自己走结束与上传，模块不另造跑步记录、不改里程与轨迹。
 *
 * 配置 `lepao_auto_stop_km`：空串或 0 = 关闭；单位公里。取不到控件时每秒重试，
 * 同一 Activity 只触发一次，避免反复长按。
 */
internal object LepaoAutoStop {
    private const val HOST_PKG = "com.lptiyu.tanke"
    private const val DISTANCE_ID = "tv_distance_value"
    private const val STOP_ID = "tv_stoprun"
    private const val ACTION_RUN_START = "com.kdzw8p.mqa.v71f.AUTO_RUN_START"
    private const val ACTION_RUN_DONE = "com.kdzw8p.mqa.v71f.AUTO_RUN_DONE"
    private const val ACTION_RUN_STATUS = "com.kdzw8p.mqa.v71f.RUN_STATUS"
    private const val MODULE_PKG = "com.kdzw8p.mqa.v71f"

    private var installed = false
    private var triggeredFor = 0
    private var stopping = false
    /** 一键跑/批量跑时由广播传入的目标公里；非空时优先于配置项 */
    private var autoKm: Double? = null
    private var automationBusy = false
    private var lastStatusLogAt = 0L
    private var lastLogWaitAt = 0L
    private var startPressAt = 0L
    private var stopRetries = 0
    private var automationStartAt = 0L
    private var lastChronoText = ""
    private var lastChronoChangeAt = 0L

    fun install(config: () -> JSONObject?) {
        if (installed) return
        installed = true
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                runCatching { tick(handler, config) }
                handler.postDelayed(this, 1000)
            }
        }, 4000)
        XposedBridge.log("[R7VqzN] auto-stop: armed, poll 1s")
        registerAutomationReceiver()
    }

    private fun tick(handler: Handler, config: () -> JSONObject?) {
        val top = topActivity()
        val inHost = top != null && top.packageName == HOST_PKG && !top.isFinishing
        // 跑步页的里程/速度/状态每秒发给模块，悬浮窗直接显示，不需要用户盯着宿主界面
        if (inHost) runCatching { broadcastRunStatus(top!!) }

        // 「当前跑区合格标准」是乐跑自己的原生 Dialog（里程/打卡/配速 + 「我知道了」），
        // 开跑前后都会弹，不点掉会一直遮住底部按钮，自动流程会卡在等待。
        if (inHost && !stopping) runCatching {
            val dismiss = allWindowRoots().firstNotNullOfOrNull { root ->
                findClickableByText(root, listOf("我知道了"))
            }
            if (dismiss != null) {
                dismiss.second.performClick()
                XposedBridge.log("[R7VqzN] auto-stop: dismissed '${dismiss.first}'")
            }
        }

        if (stopping) return
        val targetKm = autoKm
            ?: (config()?.optString("lepao_auto_stop_km").orEmpty().toDoubleOrNull() ?: return)
        if (targetKm <= 0.0) return

        val activity = top ?: return
        if (activity.packageName != HOST_PKG || activity.isFinishing) return
        // 跑步页控件不在 topActivity 的 decor 里，findViewById 永远拿不到里程，
        // 自动结束会一直不触发；统一走全窗口根视图。
        val distance = (findByAllRoots(DISTANCE_ID) as? TextView)
            ?.text?.toString()?.trim()?.toDoubleOrNull() ?: return
        // 到点必须真的达到目标：乐跑判的是 ≥ 最低里程，差 10 米也会判不合格。
        // 乐跑自己的累加值可能比模拟距离差一点，所以目标处多跑 30 米（在 ConfigManager 侧加），
        // 这里不再留向下容差。
        if (distance < targetKm || activity.hashCode() == triggeredFor) return

        // 到点不等于该结束：定向打卡跑区还要求打满打卡点。tv_log_count 是已打卡数，
        // tv_tip 会显示「待分配(再跑 N 米后分配打卡点)」。没走完就继续跑，
        // 最多续到目标的1.5 倍；仍不齐再结束（那种是路线压根不经过打卡点）。
        val logCount = (findByAllRoots("tv_log_count") as? TextView)
            ?.text?.toString()?.trim()?.toIntOrNull() ?: -1
        val pendingLog = (findByAllRoots("tv_tip") as? TextView)
            ?.text?.toString()?.contains("待分配") == true
        // logCount = -1 是读不到计数控件（不同版本 id 不同，实测 4.2.3 就读不到），
        // 不能当成「打卡不足」，否则里程到了也会一直被拦；那种情况只信 tv_tip 的「待分配」。
        val logIncomplete = pendingLog || logCount == 0
        if (logIncomplete && distance < targetKm * 1.5) {
            val nowMs = SystemClock.uptimeMillis()
            if (nowMs - lastLogWaitAt > 15_000L) {
                lastLogWaitAt = nowMs
                XposedBridge.log(
                    "[R7VqzN] auto-stop: at ${distance}km but logs=$logCount pending=$pendingLog, keep running"
                )
            }
            return
        }

        // 均速不够就继续跑的做法会把 1.8 公里无限拖下去（乐跑判的是全程均值，跑完就定），
        // 真正该做的是把速度拉到位，而不是到点了还不结束。这里只保留里程判定。

        val stop = findByAllRoots(STOP_ID)
        if (stop != null && stop.visibility == View.VISIBLE && stop.isShown) {
            // 已暂停：直接长按结束
            triggeredFor = activity.hashCode()
            stopping = true
            XposedBridge.log("[R7VqzN] auto-stop: distance=$distance km >= $targetKm, long-press stop")
            longPress(handler, stop) {
                handler.postDelayed({
                    // 没弹确认框且还能看到「结束」按钮，说明长按没生效（跑步仍停着），
                    // 交回 tick 重按一次；重试超过 2 次就认为已直接结束。
                    val confirmed = clickConfirm()
                    if (!confirmed && stopRetries < 2 && findByAllRoots(STOP_ID) != null) {
                        stopRetries++
                        XposedBridge.log("[R7VqzN] auto-stop: stop not applied, retry ${stopRetries}")
                        stopping = false
                        triggeredFor = 0
                        return@postDelayed
                    }
                    stopRetries = 0
                    stopping = false
                    finishAutomation()
                }, 2500)
            }
            return
        }
        // 跑步中是「长按暂停」，暂停后 tv_stoprun 才出现；先暂停再结束
        val pause = findByAllRoots("tv_start_pause_continue_run")
        if (pause == null || !pause.isShown) return
        triggeredFor = activity.hashCode()
        stopping = true
        XposedBridge.log("[R7VqzN] auto-stop: distance=$distance km >= $targetKm, long-press pause")
        longPress(handler, pause) {
            handler.postDelayed({ stopAfterPause(handler, activity) }, 2500)
        }
    }

    /** 暂停后结束按钮才可见：长按结束并确认。按钮可能延迟出现，要重试而不是直接放弃。 */
    private fun stopAfterPause(handler: Handler, activity: Activity, attempt: Int = 0) {
        val stop = findByAllRoots(STOP_ID)
        if (stop == null || !stop.isShown) {
            if (attempt < 6) {
                handler.postDelayed({ stopAfterPause(handler, activity, attempt + 1) }, 1500)
                return
            }
            // 长按暂停可能没生效；清掉 triggeredFor，下一秒的 tick 会重新暂停再试
            XposedBridge.log("[R7VqzN] auto-stop: stop button still hidden, retry next tick")
            triggeredFor = 0
            stopping = false
            return
        }
        XposedBridge.log("[R7VqzN] auto-stop: long-press stop after pause")
        longPress(handler, stop) {
            handler.postDelayed({
                clickConfirm()
                stopping = false
                finishAutomation()
            }, 2500)
        }
    }

    /** 跑步页实时状态：里程(km)、速度(m/s)、计时、是否暂停。 */
    private fun broadcastRunStatus(activity: Activity) {
        // 跑步页的控件不在 topActivity 的 decor 里（外壳 Activity + 独立窗口），用全窗口根视图找
        val kmView = findByAllRoots(DISTANCE_ID) as? TextView ?: return
        val km = kmView.text?.toString()?.trim()?.toDoubleOrNull() ?: return
        val speed = (findByAllRoots("tv_speed_value") as? TextView)
            ?.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
        val pauseText = (findByAllRoots("tv_start_pause_continue_run") as? TextView)
            ?.text?.toString().orEmpty()
        val elapsedText = (findByAllRoots("chronometer") as? TextView)
            ?.text?.toString().orEmpty()
        // 开始/暂停按钮是画布文字的 ProgressBar，读不到文本；计时连续 3 秒不走就当作已暂停
        val nowMs = SystemClock.uptimeMillis()
        if (elapsedText != lastChronoText) {
            lastChronoText = elapsedText
            lastChronoChangeAt = nowMs
        }
        val paused = pauseText.contains("继续") ||
            (lastChronoChangeAt > 0 && nowMs - lastChronoChangeAt > 3000 && km > 0.0)
        var speedValue = speed
        // 乐跑跑步页显示的是平均配速，tv_speed_value 常常是 0；这时用里程/用时算平均速度
        if (speedValue <= 0.0 && km > 0.0) {
            val parts = elapsedText.split(":").mapNotNull { it.trim().toIntOrNull() }
            val seconds = when (parts.size) {
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                2 -> parts[0] * 60 + parts[1]
                else -> 0
            }
            if (seconds > 0) speedValue = km * 1000.0 / seconds
        }
        val app = currentApplication() ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastStatusLogAt > 10_000) {
            lastStatusLogAt = now
            XposedBridge.log("[R7VqzN] run-status: km=$km speed=$speedValue paused=$paused")
        }
        app.sendBroadcast(
            Intent(ACTION_RUN_STATUS).setPackage(MODULE_PKG)
                .putExtra("km", km).putExtra("speed", speedValue)
                .putExtra("elapsed", elapsedText).putExtra("paused", paused)
        )
    }

    /** 结束按钮是长按型控件：合成 DOWN，保持 2.2 秒后 UP。 */
    private fun longPress(handler: Handler, view: View, after: () -> Unit) {
        val downAt = SystemClock.uptimeMillis()
        val x = (view.width / 2).toFloat().coerceAtLeast(1f)
        val y = (view.height / 2).toFloat().coerceAtLeast(1f)
        val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0)
        view.dispatchTouchEvent(down)
        down.recycle()
        handler.postDelayed({
            val up = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            view.dispatchTouchEvent(up)
            up.recycle()
            after()
        }, 2200)
    }

    /** 结束确认框通常在独立窗口，遍历 WindowManagerGlobal.mViews 而不是只看 Activity。 */
    /** 接收模块 App 的「一键跑」指令：自动点进跑步页，结束后回传 AUTO_RUN_DONE。 */
    private fun registerAutomationReceiver(attempt: Int = 0) {
        val app = currentApplication()
        if (app == null) {
            if (attempt < 30) {
                Handler(Looper.getMainLooper()).postDelayed({ registerAutomationReceiver(attempt + 1) }, 1000)
            }
            return
        }
        runCatching {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != ACTION_RUN_START) return
                    val km = intent.getStringExtra("km")?.toDoubleOrNull() ?: return
                    startAutomation(km)
                }
            }, IntentFilter(ACTION_RUN_START), Context.RECEIVER_EXPORTED)
            XposedBridge.log("[R7VqzN] auto-run: receiver registered")
        }
    }

    private fun startAutomation(km: Double) {
        if (km <= 0.0) return
        // 上一次自动跑可能停在跑步页或已被用户手动结束，automationBusy 不清会让后续一键跑全部失效；
        // 超过 90 秒或跑步页已不在，就当作陈旧状态重置。
        if (automationBusy) {
            val stale = SystemClock.uptimeMillis() - automationStartAt > 90_000 ||
                findByAllRoots(DISTANCE_ID) == null
            if (!stale) return
            XposedBridge.log("[R7VqzN] auto-run: reset stale automation state")
            automationBusy = false
            autoKm = null
        }
        automationBusy = true
        automationStartAt = SystemClock.uptimeMillis()
        startPressAt = 0L
        stopRetries = 0
        autoKm = km
        val handler = Handler(Looper.getMainLooper())
        XposedBridge.log("[R7VqzN] auto-run: start km=$km")
        handler.postDelayed({ stepAutomation(handler, 0) }, 1200)
    }

    /** 首页「开始乐跑」→「自由跑」(dialog_confirm) → MapRunActivity 的「开始」。 */
    private fun stepAutomation(handler: Handler, attempt: Int) {
        if (!automationBusy) return
        val activity = topActivity()
        if (activity == null || activity.packageName != HOST_PKG) {
            retryAutomation(handler, attempt)
            return
        }
        // 乐跑重启后会问「检测到您有未完成的跑步记录，是否恢复？」；
        // 一键跑要求每个账号每次从头，所以选不恢复。
        val restoreRoot = allWindowRoots().firstOrNull { containsText(it, "未完成的跑步") }
        if (restoreRoot != null) {
            // 实测弹窗：「检测到您有未完成的跑步记录，是否恢复？」按钮是「放弃 / 恢复」，
            // 一键跑要每次从头，选「放弃」。
            val decline = findClickableByText(restoreRoot, listOf("放弃", "不恢复", "重新开始", "重新跑步", "否", "取消"))
            if (decline != null) {
                decline.second.performClick()
                XposedBridge.log("[R7VqzN] auto-run: declined restore (${decline.first})")
                handler.postDelayed({ stepAutomation(handler, attempt + 1) }, 1800)
                return
            }
        }
        // 「安全提示」等弹窗在独立窗口里，确认按钮文案随模式变（进入乐跑 / 自由跑），
        // 必须先点掉它，否则会一直点到被遮住的首页「开始乐跑」。
        val dialogStart = allWindowRoots().firstNotNullOfOrNull { root ->
            findClickableByText(root, listOf("进入乐跑", "自由跑"))
        }
        if (dialogStart != null) {
            dialogStart.second.performClick()
            XposedBridge.log("[R7VqzN] auto-run: clicked ${dialogStart.first}")
            handler.postDelayed({ stepAutomation(handler, attempt + 1) }, 2200)
            return
        }
        if (findByAllRoots(DISTANCE_ID) != null) {
            // 进跑步页不等于已开跑：定向打卡等模式底部仍是「开始乐跑」，里程 0.00 / 用时 00:00:00。
            // 只有确认在跑（按钮显示「长按暂停」或计时已走）才停手，否则再点会把跑步暂停。
            // 底部开始/暂停/继续是 TextProgressBar（id tv_start_pause_continue_run），文字画在画布上，
            // 没有 TextView 文本可读，也不能 performClick；开跑要合成一次长按。
            val chrono = (findByAllRoots("chronometer") as? TextView)?.text?.toString()?.trim().orEmpty()
            // 乐跑开跑前有 3-2-1 倒计时，倒计时期间 chronometer 一直是 00:00:00，
            // 光看计时会把「已经开始倒计时」当成没开跑，于是再长按一次——那次落在已开跑之后，
            // 直接把跑步暂停了。所以距离>0 也算在跑。
            val distKm = (findByAllRoots(DISTANCE_ID) as? TextView)
                ?.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
            val running = (chrono.isNotBlank() && chrono != "00:00:00") || distKm > 0.0
            if (running) {
                XposedBridge.log("[R7VqzN] auto-run: run page running, waiting distance")
                return
            }
            val confirmRun = findByAllRoots("dialog_confirm")
            if (confirmRun != null && confirmRun.isShown) {
                confirmRun.performClick()
                XposedBridge.log("[R7VqzN] auto-run: clicked run-page confirm")
                handler.postDelayed({ stepAutomation(handler, attempt + 1) }, 2200)
                return
            }
            val startBtn = findByAllRoots("tv_start_pause_continue_run")
            if (startBtn != null && startBtn.isShown) {
                // 长按后 6 秒内不重复（倒计时 3 秒 + 启动开销），否则第二次长按会变成「长按暂停」
                val sincePress = SystemClock.uptimeMillis() - startPressAt
                if (startPressAt > 0L && sincePress < 6_000L) {
                    handler.postDelayed({ stepAutomation(handler, attempt + 1) }, 1500)
                    return
                }
                startPressAt = SystemClock.uptimeMillis()
                XposedBridge.log("[R7VqzN] auto-run: long-press start on run page")
                longPress(handler, startBtn) {
                    handler.postDelayed({ stepAutomation(handler, attempt + 1) }, 2500)
                }
                return
            }
            XposedBridge.log("[R7VqzN] auto-run: run page idle, no start control found")
            retryAutomation(handler, attempt)
            return
        }
        val confirm = findByAllRoots("dialog_confirm")
        if (confirm != null && confirm.isShown) {
            confirm.performClick()
            XposedBridge.log("[R7VqzN] auto-run: clicked 自由跑")
            handler.postDelayed({ stepAutomation(handler, attempt + 1) }, 2200)
            return
        }
        // 时段校验弹窗的按钮 id 在不同版本/皮肤下不一定保留，用文本兜底
        val freeRun = findClickableByText(activity.window.decorView, listOf("自由跑"))
        if (freeRun != null) {
            freeRun.second.performClick()
            XposedBridge.log("[R7VqzN] auto-run: clicked 自由跑(text)")
            handler.postDelayed({ stepAutomation(handler, attempt + 1) }, 2200)
            return
        }
        val startRun = findClickableByText(activity.window.decorView, listOf("开始乐跑"))
        if (startRun != null) {
            startRun.second.performClick()
            XposedBridge.log("[R7VqzN] auto-run: clicked 开始乐跑")
            handler.postDelayed({ stepAutomation(handler, attempt + 1) }, 1800)
            return
        }
        retryAutomation(handler, attempt)
    }

    private fun retryAutomation(handler: Handler, attempt: Int) {
        if (!automationBusy) return
        if (attempt > 100) {
            XposedBridge.log("[R7VqzN] auto-run: give up waiting UI")
            automationBusy = false
            autoKm = null
            return
        }
        handler.postDelayed({ stepAutomation(handler, attempt + 1) }, 1500)
    }

    /** 上报完成：等上传页稳定后回传模块，并关闭宿主，便于下一账号切换。 */
    private fun finishAutomation() {
        if (!automationBusy) return
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                currentApplication()?.sendBroadcast(Intent(ACTION_RUN_DONE).setPackage(MODULE_PKG))
            }
            runCatching { topActivity()?.finishAffinity() }
            XposedBridge.log("[R7VqzN] auto-run: done, host closed")
            automationBusy = false
            autoKm = null
        }, 12_000)
    }

    /** 乐跑计时器文本（mm:ss / h:mm:ss）转秒；读不到返回 0。 */
    private fun parseChronoSeconds(text: String): Long {
        val parts = text.split(':').mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0L
        }
    }

    private fun findByAllRoots(name: String): View? {
        for (root in allWindowRoots()) {
            val id = root.resources.getIdentifier(name, "id", HOST_PKG)
            if (id != 0) root.findViewById<View>(id)?.let { return it }
        }
        return null
    }

    private fun currentApplication(): android.app.Application? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null)
            as? android.app.Application
    }.getOrNull()

    private fun clickConfirm(): Boolean {
        // 不能再用 contains：跑步结束页里「# 原来大学是这样的」也会命中「是」。
        // 这里只接受完全相等，或以目标开头（「结束跑步」→「结束」）。
        val targets = listOf("结束", "确定", "完成", "确认", "是")
        for (root in allWindowRoots()) {
            val hit = findClickableByExactText(root, targets) ?: continue
            XposedBridge.log("[R7VqzN] auto-stop: click confirm '${hit.first}'")
            hit.second.performClick()
            return true
        }
        XposedBridge.log("[R7VqzN] auto-stop: no confirm button, sport may end directly")
        return false
    }

    /** 比 findClickableByText 严格：完全相等或目标开头，避免单词「是」误中长句。 */
    private fun findClickableByExactText(v: View, targets: List<String>): Pair<String, View>? {
        if (v is TextView && v.isShown) {
            val text = v.text?.toString()?.trim().orEmpty()
            val matched = targets.firstOrNull {
                text == it || (it.length >= 2 && text.startsWith(it))
            }
            if (matched != null) {
                val clickable = generateSequence<View>(v) { it.parent as? View }
                    .firstOrNull { it.isClickable }
                if (clickable != null) return matched to clickable
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                v.getChildAt(i)?.let { child ->
                    findClickableByExactText(child, targets)?.let { return it }
                }
            }
        }
        return null
    }

    private fun findClickableByText(v: View, targets: List<String>): Pair<String, View>? {
        if (v is TextView && v.isShown) {
            val text = v.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && targets.any { text == it || text.contains(it) }) {
                val clickable = generateSequence<View>(v) { it.parent as? View }
                    .firstOrNull { it.isClickable }
                if (clickable != null) return text to clickable
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findClickableByText(v.getChildAt(i), targets)?.let { return it }
            }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun containsText(view: View?, needle: String): Boolean {
        when (view) {
            null -> return false
            is TextView -> if (view.text?.toString()?.contains(needle) == true) return true
            is ViewGroup -> for (i in 0 until view.childCount) {
                if (containsText(view.getChildAt(i), needle)) return true
            }
        }
        return false
    }

    private fun allWindowRoots(): List<View> = runCatching {
        val wmg = Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getInstance").invoke(null) ?: return emptyList()
        val f = wmg.javaClass.getDeclaredField("mViews").apply { isAccessible = true }
        (f.get(wmg) as? List<View>)?.toList() ?: emptyList()
    }.getOrDefault(emptyList())

    private fun topActivity(): Activity? {
        return try {
            val at = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null) ?: return null
            val f = at.javaClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val map = f.get(at) as? Map<*, *> ?: return null
            for (record in map.values) {
                if (record == null) continue
                val af = record.javaClass.getDeclaredField("activity").apply { isAccessible = true }
                val activity = af.get(record) as? Activity ?: continue
                if (!activity.isFinishing) return activity
            }
            null
        } catch (_: Throwable) {
            null
        }
    }
}
