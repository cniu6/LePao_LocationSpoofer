package com.kdzw8p.mqa.v71f.viewmodel

import androidx.lifecycle.viewModelScope
import com.kdzw8p.mqa.v71f.data.db.CollectionRouteRecord
import com.kdzw8p.mqa.v71f.data.db.CompleteLocation
import com.kdzw8p.mqa.v71f.data.model.CollectionRouteMatcher
import com.kdzw8p.mqa.v71f.data.model.RoutePoint
import com.kdzw8p.mqa.v71f.data.model.geometry
import com.kdzw8p.mqa.v71f.utils.CoordinateUtils.LatLng
import com.kdzw8p.mqa.v71f.utils.GeoMath
import com.kdzw8p.mqa.v71f.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun MainViewModel.setRouteCollection(enabled: Boolean) {
    if (_uiState.value.isContinuousScanning || _uiState.value.isStoppingCollection) return
    _uiState.update { it.copy(isRouteCollection = enabled, isDrawingCollectionRoute = enabled && it.collectionRoutePoints.size < 2) }
}

internal fun MainViewModel.addCollectionRoutePoint(lat: Double, lng: Double) {
    if (_uiState.value.isContinuousScanning || _uiState.value.isStoppingCollection) return
    val previous = _uiState.value.collectionRoutePoints.lastOrNull()
    if (previous != null && GeoMath.distance(LatLng(previous.lat, previous.lng), LatLng(lat, lng)) < 2.0) return
    _uiState.update { it.copy(collectionRoutePoints = it.collectionRoutePoints + RoutePoint(lat, lng)) }
}

internal fun MainViewModel.undoCollectionRoutePoint() {
    if (!_uiState.value.isContinuousScanning && !_uiState.value.isStoppingCollection) {
        _uiState.update { it.copy(collectionRoutePoints = it.collectionRoutePoints.dropLast(1)) }
    }
}

internal fun MainViewModel.drawCollectionRoute() {
    if (_uiState.value.isContinuousScanning || _uiState.value.isStoppingCollection) return
    _uiState.update { it.copy(isRouteCollection = true, isDrawingCollectionRoute = true) }
}

internal fun MainViewModel.finishCollectionRouteDrawing() {
    if (_uiState.value.collectionRoutePoints.size >= 2) {
        _uiState.update { it.copy(isDrawingCollectionRoute = false) }
    }
}

internal fun MainViewModel.saveCollectionRouteInfo(id: Long, name: String, remark: String, closePending: Boolean = true, onSaved: () -> Unit = {}) {
    if (_uiState.value.isSavingCollectionInfo || name.isBlank()) return
    _uiState.update { it.copy(isSavingCollectionInfo = true) }
    viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { environmentDao.updateCollectionRouteInfo(id, name.trim(), remark.trim()) }
            _uiState.update {
                it.copy(isSavingCollectionInfo = false, pendingCollectionRoute = if (closePending) null else it.pendingCollectionRoute)
            }
            onSaved()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _uiState.update { it.copy(isSavingCollectionInfo = false) }
            android.widget.Toast.makeText(context, R.string.collection_info_save_failed, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

internal fun MainViewModel.skipCollectionRouteInfo() {
    if (!_uiState.value.isSavingCollectionInfo) _uiState.update { it.copy(pendingCollectionRoute = null) }
}

internal fun MainViewModel.selectCollectedRoute(id: Long) {
    val route = collectionRoutes.value.firstOrNull { it.route.id == id } ?: return
    val point = route.route.geometry().firstOrNull() ?: return
    pinnedLocationRecordId = null
    _uiState.update {
        it.copy(selectedCollectionRouteId = id, pinnedCollectedLocationId = null, pinnedLocationName = null,
            latitudeInput = point.lat.toString(), longitudeInput = point.lng.toString(),
            routePoints = route.route.geometry().map { p -> RoutePoint(p.lat, p.lng) },
            routePlanStage = com.kdzw8p.mqa.v71f.data.model.RoutePlanStage.READY,
            useRealRoute = false)
    }
    evaluateMockCapabilities()
}

internal fun MainViewModel.deleteCollectedRoute(id: Long) {
    viewModelScope.launch(Dispatchers.IO) { environmentDao.deleteCollectionRoute(id) }
    _uiState.update { if (it.selectedCollectionRouteId == id) it.copy(selectedCollectionRouteId = null) else it }
}

/** 界面能力判断与模拟移动使用同一匹配规则，避免路线中部采样稀疏时丢失环境数据。 */
internal suspend fun MainViewModel.environmentRecordsAt(lat: Double, lng: Double): List<CompleteLocation> {
    val position = LatLng(lat, lng)
    val routes = collectionRoutes.value
    val selected = _uiState.value.selectedCollectionRouteId
    val route = CollectionRouteMatcher.routeAt(routes, position, selected)
    if (route != null) return CollectionRouteMatcher.samplesAt(route, position)
    return environmentLocations.value
        .filter { it.location.collectionRouteId == null && GeoMath.distance(position, LatLng(it.location.lat, it.location.lng)) <= 50.0 }
        .sortedBy { GeoMath.distance(position, LatLng(it.location.lat, it.location.lng)) }

}

/** 乐跑配置页读取的 hook 组开关；未配置过视为全开 */
fun MainViewModel.isLepaoHookGroupEnabled(group: String): Boolean =
    settingsRepository.isHookGroupEnabled(LEPAO_PKG, group)

/**
 * 切换乐跑进程的 hook 组。写回设置后立即重发一次配置，
 * 让已注入的 Xposed 端在下次读配置时生效（进程重启后完全生效）。
 */
fun MainViewModel.setLepaoHookGroupEnabled(group: String, enabled: Boolean) {
    settingsRepository.setHookGroupEnabled(LEPAO_PKG, group, enabled)
    viewModelScope.launch {
        locationRepository.patchConfig { json ->
            json.put("target_hook_groups", settingsRepository.getTargetHookGroupsJson())
        }
    }
}

private const val LEPAO_PKG = "com.lptiyu.tanke"

/** 强制重登全局只允许一个在跑，避免 102 重试和手动导入互相踩 */
private var reloginInFlight = false

/** 自动重登熔断：120 秒冷却 + 连续失败 2 次停止，防止 readDevice→重登→readDevice 死循环反复重启乐跑 */
private var lastReloginAtMs = 0L
private var reloginFailStreak = 0

/** 最近一次微信凭证导入的 openid：回读资料时用它把占位档案认领为真实账号 */
private var pendingSocialIdentity: Pair<String, String>? = null

/**
 * 会话被踢（102）或手里只有微信凭据时：自动 refresh + p2.f 强制拿一份新乐跑会话。
 * quickLoginV339 成功就会把其它设备踢下线，本机成为唯一在线设备。
 */
fun MainViewModel.forceReloginIfPossible() {
    if (reloginInFlight) return
    val now = System.currentTimeMillis()
    if (now - lastReloginAtMs < 120_000) {
        com.kdzw8p.mqa.v71f.utils.LepaoBridge.note(context, "[重登] 冷却中（120 秒内已尝试过），跳过")
        return
    }
    if (reloginFailStreak >= 2) {
        com.kdzw8p.mqa.v71f.utils.LepaoBridge.note(context, "[重登] 连续失败 2 次，已停止自动重登：请手动微信凭证导入或检查凭据")
        return
    }
    val root = settingsRepository.getLepaoProfiles()
    val active = settingsRepository.lepaoActiveAccount
    val names = (listOf(active) + root.keys().asSequence().toList()).distinct()
    val profile = names.firstNotNullOfOrNull { n ->
        root.optJSONObject(n)?.takeIf {
            it.optString("wx_openid").isNotBlank() && it.optString("wx_refresh_token").isNotBlank()
        }
    } ?: return
    reloginInFlight = true
    reloginFailStreak++
    lastReloginAtMs = now
    importWxAndLogin(
        openid = profile.optString("wx_openid"),
        accessToken = profile.optString("wx_access_token"),
        refreshToken = profile.optString("wx_refresh_token"),
        nick = profile.optString("nickname"),
        avatar = profile.optString("avatar_url"),
        schoolId = profile.optString("school_id"),
        force = true
    ) { text, done ->
        // 每一步都落日志，失败原因不能只停在界面提示里
        com.kdzw8p.mqa.v71f.utils.LepaoBridge.note(context, "[重登] $text")
        if (done) {
            reloginInFlight = false
            // 乐跑回执 ok 时立刻回读一次，把真实次数/里程/场地写回档案；成功则清零失败计数
            if (text.contains("回执 ok")) {
                reloginFailStreak = 0
                readDeviceLepaoUser { }
            }
        }
    }
}

/**
 * 乐跑多用户配置切换：每个用户一份 {lat, lng, groups}，groups 是该用户专属的
 * target_hook_groups.com.lptiyu.tanke 快照（检测层接管开关逐人不同）。
 * 保存时从当前界面状态取坐标；切换时写回坐标与组开关并立即重发一次配置，
 * 已注入的乐跑进程下次读配置时生效（hook 安装仍需重启乐跑）。
 */
fun MainViewModel.saveLepaoProfile(name: String) {
    val s = _uiState.value
    val profile = JSONObject()
    profile.put("lat", s.latitudeInput)
    profile.put("lng", s.longitudeInput)
    profile.put("remark", "")
    profile.put("createdAt", System.currentTimeMillis())
    profile.put("groups", settingsRepository.getTargetHookGroupsJson().optJSONObject(LEPAO_PKG) ?: JSONObject())
    settingsRepository.saveLepaoProfile(name, profile)
}

/** 保存时附带备注（表格里显示） */
fun MainViewModel.saveLepaoProfile(name: String, remark: String) {
    val s = _uiState.value
    val profile = JSONObject()
    profile.put("lat", s.latitudeInput)
    profile.put("lng", s.longitudeInput)
    profile.put("remark", remark)
    profile.put("createdAt", System.currentTimeMillis())
    profile.put("groups", settingsRepository.getTargetHookGroupsJson().optJSONObject(LEPAO_PKG) ?: JSONObject())
    settingsRepository.saveLepaoProfile(name, profile)
}

/** 表格行数据：本地档案，不代表已认证用户或实时成绩。 */
data class LepaoProfileRow(
    val name: String,
    val remark: String,
    val lat: String,
    val lng: String,
    val createdAt: Long,
    /** 已跑次数 */
    val runCount: Int = 0,
    /** 目标次数，0 = 未设 */
    val runTarget: Int = 0,
    /** 登录方式：id / wechat / qrcode / token */
    val loginMode: String = "id",
    /** 绑定会话 JSON（user_id + token），空 = 未绑定 */
    val session: String = "",
    /** 该用户专属的 hook 组快照 */
    val groupsJson: String = "{}",
    val userId: String = "",
    val nickname: String = "",
    /** 身份认证页读到的真实姓名/院系/性别/入学年份（JSON），服务端资料里没有这些字段 */
    val identityJson: String = "",
    val school: String = "",
    val studentNo: String = "",
    /** 服务端头像 / 场地 / 真实成绩（从乐跑 MMKV 的 run_rule 读取） */
    val avatarUrl: String = "",
    val faceImage: String = "",
    /** 本地按用户保存的拍照/扫脸图（JPEG base64），空 = 该用户不替换 */
    val faceB64: String = "",
    val serverRunCount: Int = 0,
    val serverTotalRunCount: Int = 0,
    val totalDistanceM: Double = 0.0,
    val runScore: String = "",
    val venueId: Long = 0L,
    val venueName: String = "",
    val source: String = "local",
    val capturedAt: Long = 0L,
    val runRuleJson: String = "",
    val statsJson: String = "",
    val statsFetchedAt: Long = 0L,
    val statsError: String = "",
    val ruleCapturedAt: Long = 0L,
    val statsAttemptAt: Long = 0L,
    val ruleError: String = "",
    val ruleAttemptAt: Long = 0L,
    val ruleSource: String = "",
    val todayRunDay: String = "",
    val todayRunCount: Int = -1,
    val todayRunError: String = ""
) {
    // 仅说明本地凭据结构完整，绝不代表服务端已验证登录。
    val hasSession: Boolean get() = runCatching {
        val json = JSONObject(session)
        com.kdzw8p.mqa.v71f.utils.LepaoRunData.isAccountId(userId) &&
            json.optString("user_id") == userId && json.optString("token").isNotBlank()
    }.getOrDefault(false)
    /**
     * 服务端对这张票的明确判定才算会话过期：102，或 info 直接写「登录信息失效」。
     * 101 是请求失败，与网络错误、超时、其它业务码一样只算刷新失败，不能推断成要重新登录。
     */
    val sessionExpired: Boolean get() = listOf(statsError, ruleError).any { error ->
        error.startsWith("102:") ||
            error.contains("登录信息失效") || error.contains("请重新登录") ||
            error.contains("重新登录")
    }

    /** 版本门槛与服务端会话失效是两件事，分开展示避免误导用户反复扫码。 */
    val versionBlocked: Boolean get() = listOf(statsError, ruleError).any {
        it.contains("版本过低") || it.contains("请升级后使用")
    }

    /**
     * 规则读取需要乐跑进程在线（走宿主 p2.f）；超时不代表登录票失效，
     * 只需要把乐跑打开并保持前台后重试，不要引导用户重新扫码。
     */
    val ruleTimeout: Boolean get() = statsError.isBlank() && ruleError.contains("规则请求超时")
    val refreshFailed: Boolean get() = statsError.isNotBlank() || ruleError.isNotBlank()
    val hasCheckedData: Boolean get() = statsFetchedAt > 0L ||
        (ruleCapturedAt > 0L && ruleSource == "account_server_query")
    fun runsOn(day: String): Int? = todayRunCount.takeIf {
        it >= 0 && todayRunDay == day && todayRunError.isBlank() && hasSession && !sessionExpired
    }
    private val rule: JSONObject? get() = com.kdzw8p.mqa.v71f.utils.LepaoRunData.objectOrNull(runRuleJson)
        ?.takeIf { com.kdzw8p.mqa.v71f.utils.LepaoRunData.validRuleOwner(it, userId) }
    private val stats: JSONObject? get() = com.kdzw8p.mqa.v71f.utils.LepaoRunData.objectOrNull(statsJson)
    private val accountInfo: JSONObject? get() = stats?.optJSONObject("account_info")

    private val identity: JSONObject? get() = com.kdzw8p.mqa.v71f.utils.LepaoRunData.objectOrNull(identityJson)

    /**
     * 身份认证页（学校/院系/姓名/学号/性别/入学年份）优先；服务端 User/User 只有昵称
     * user_name，把它当姓名会显示成昵称，所以这里不取 user_name。
     */
    val realName: String get() = com.kdzw8p.mqa.v71f.utils.LepaoRunData.value(identity, "real_name")
        ?: listOf("real_name", "true_name")
            .firstNotNullOfOrNull { accountInfo?.optString(it)?.takeIf { s -> s.isNotBlank() } }.orEmpty()
    val major: String get() = com.kdzw8p.mqa.v71f.utils.LepaoRunData.value(identity, "major")
        ?: com.kdzw8p.mqa.v71f.utils.LepaoRunData.value(identity, "academy")
        ?: listOf(
            "academy", "academy_name", "profession", "major", "major_name", "profession_name",
            "college", "college_name", "department", "grade"
        ).firstNotNullOfOrNull { accountInfo?.optString(it)?.takeIf { s -> s.isNotBlank() } }.orEmpty()
    val sexText: String get() {
        val raw = com.kdzw8p.mqa.v71f.utils.LepaoRunData.value(identity, "sex")
            ?: (accountInfo?.opt("user_sex") ?: accountInfo?.opt("sex") ?: accountInfo?.opt("gender"))
                ?.toString().orEmpty()
        return when (raw) {
            "1" -> "男"
            "2" -> "女"
            "男", "女" -> raw
            else -> ""
        }
    }
    val completedText: String? get() = com.kdzw8p.mqa.v71f.utils.LepaoRunData.value(rule, "total_num")
    val targetText: String? get() = com.kdzw8p.mqa.v71f.utils.LepaoRunData.value(rule, "term_num")
    val cumulativeRunsText: String? get() = com.kdzw8p.mqa.v71f.utils.LepaoRunData.value(stats?.optJSONObject("running_stat")?.optJSONObject("total"), "total_valid_num")
    val progressText: String get() = if (completedText != null || targetText != null)
        "${completedText ?: "—"} / ${targetText ?: "—"}" else "—"
    val distanceText: String get() {
        val raw = com.kdzw8p.mqa.v71f.utils.LepaoRunData.value(stats?.optJSONObject("running_stat")?.optJSONObject("total"), "total_valid_distance")
        return raw?.let { "$it km" } ?: "—"
    }
    val venueText: String get() = venueName.ifBlank { if (venueId > 0L) "场地 #$venueId" else "" }
    val loginModeLabel: String
        get() = when (loginMode) {
            "wechat" -> "微信"
            "qq" -> "QQ"
            "qrcode" -> "扫码"
            "token" -> "Token"
            "local" -> "—"
            else -> "ID"
        }
}

fun MainViewModel.lepaoProfileRows(): List<LepaoProfileRow> {
    val root = settingsRepository.getLepaoProfiles()
    return root.keys().asSequence().map { name ->
        val p = root.optJSONObject(name)
        LepaoProfileRow(
            name = name,
            remark = p?.optString("remark", "") ?: "",
            lat = p?.optString("lat", "") ?: "",
            lng = p?.optString("lng", "") ?: "",
            createdAt = p?.optLong("createdAt", 0L) ?: 0L,
            runCount = p?.optInt("runCount", 0) ?: 0,
            runTarget = p?.optInt("runTarget", 0) ?: 0,
            loginMode = p?.optString("loginMode", "id") ?: "id",
            session = p?.optString("session", "") ?: "",
            groupsJson = p?.optJSONObject("groups")?.toString() ?: "{}",
            userId = p?.optString("user_id", "").orEmpty(),
            nickname = p?.optString("nickname", "").orEmpty(),
            school = p?.optString("school", "").orEmpty(),
            studentNo = p?.optString("student_no", "").orEmpty(),
            avatarUrl = p?.optString("avatar_url", "").orEmpty(),
            faceImage = p?.optString("face_img", "").orEmpty(),
            faceB64 = p?.optString("face_b64", "").orEmpty(),
            serverRunCount = p?.optInt("server_run_count", 0) ?: 0,
            serverTotalRunCount = p?.optInt("server_total_run_count", 0) ?: 0,
            totalDistanceM = p?.optDouble("total_distance_m", 0.0) ?: 0.0,
            runScore = p?.optString("run_score", "").orEmpty(),
            venueId = p?.optLong("venue_id", 0L) ?: 0L,
            venueName = p?.optString("venue_name", "").orEmpty(),
            source = p?.optString("source", "local") ?: "local",
            capturedAt = p?.optLong("captured_at", 0L) ?: 0L,
            runRuleJson = p?.optString("run_rule_json").orEmpty(),
            statsJson = p?.optString("stats_json").orEmpty(),
            identityJson = p?.optString("lepao_identity_json").orEmpty(),
            statsFetchedAt = p?.optLong("stats_fetched_at") ?: 0L,
            statsError = p?.optString("stats_error").orEmpty(),
            ruleCapturedAt = p?.optLong("rule_captured_at") ?: 0L,
            statsAttemptAt = p?.optLong("stats_attempt_at") ?: 0L,
            ruleError = p?.optString("rule_error").orEmpty(),
            ruleAttemptAt = p?.optLong("rule_attempt_at") ?: 0L,
            ruleSource = p?.optString("rule_source").orEmpty(),
            todayRunDay = p?.optString("today_run_day").orEmpty(),
            todayRunCount = p?.optInt("today_run_count", -1) ?: -1,
            todayRunError = p?.optString("today_run_error").orEmpty()
        )
    }.sortedBy { it.name }.toList()
}

/** 更新单个用户的任意字段（不传则保留原值） */
fun MainViewModel.patchLepaoProfile(
    name: String,
    newName: String? = null,
    remark: String? = null,
    runCount: Int? = null,
    runTarget: Int? = null,
    loginMode: String? = null,
    session: String? = null
) {
    val src = settingsRepository.getLepaoProfile(name) ?: return
    if (remark != null) { src.put("remark", remark); src.put("remark_edited", true) }
    if (runCount != null) src.put("runCount", runCount)
    if (runTarget != null) src.put("runTarget", runTarget)
    if (loginMode != null) src.put("loginMode", loginMode)
    if (session != null) src.put("session", session)
    val target = newName?.takeIf { it.isNotBlank() } ?: name
    if (target != name) settingsRepository.deleteLepaoProfile(name)
    settingsRepository.saveLepaoProfile(target, src)
    _uiState.update { it.copy(lepaoProfilesVersion = it.lepaoProfilesVersion + 1) }
}

/** 本地计数，不是服务端已完成成绩；仅用于兼容旧调用，不得作为成绩来源。 */
fun MainViewModel.incrementLepaoRunCount(name: String) {
    val p = settingsRepository.getLepaoProfile(name) ?: return
    p.put("runCount", (p.optInt("runCount", 0)) + 1)
    settingsRepository.saveLepaoProfile(name, p)
}

/** 资料读取放在IO线程，不阻塞界面，也不读取登录凭据。 */
fun MainViewModel.readDeviceLepaoUser(onResult: (com.kdzw8p.mqa.v71f.utils.LepaoUserReader.Result) -> Unit) {
    viewModelScope.launch {
        if (switchInFlight) return@launch
        val generation = accountSwitchGeneration
        val bridge = context.getSharedPreferences("lepao_bridge", android.content.Context.MODE_PRIVATE)
            .getString("json", null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val base = withContext(Dispatchers.IO) { com.kdzw8p.mqa.v71f.utils.LepaoUserReader.readResult() }
        val user = base.user ?: bridge?.let { bridgeUser(it) }
        if (user == null || !com.kdzw8p.mqa.v71f.utils.LepaoRunData.isAccountId(user.userId)) {
            onResult(if (user == null) base else com.kdzw8p.mqa.v71f.utils.LepaoUserReader.Result(
                failure = com.kdzw8p.mqa.v71f.utils.LepaoUserReader.Failure.NO_PROFILE))
            return@launch
        }
        val sessionJson = withContext(Dispatchers.IO) {
            com.kdzw8p.mqa.v71f.utils.LepaoUserReader.readSessionResult().sessionJson
        } ?: bridge?.let { bridgeSession(it) }
            ?: settingsRepository.lepaoSession.takeIf { it.isNotBlank() }
        val sessionObj = sessionJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val sessionToken = sessionObj?.optString("token")?.ifBlank { sessionObj.optString("access_token") }.orEmpty()
        // 微信 sns token（"1_" 开头）不是乐跑会话 token，拿它调 myZone 必返 102
        val usableSession = sessionJson.takeIf {
            sessionObj?.optString("user_id") == user.userId &&
                sessionToken.isNotBlank() && !sessionToken.startsWith("1_")
        }
        val activeUid = settingsRepository.lepaoActiveAccount.takeIf { it.isNotBlank() }
            ?.let { settingsRepository.getLepaoProfile(it)?.optString("user_id") }.orEmpty()
        if (activeUid.isNotBlank() && user.userId != activeUid) return@launch
        val merged = if (usableSession == null) {
            user
        } else withContext(Dispatchers.IO) {
            when (val outcome = com.kdzw8p.mqa.v71f.utils.LepaoUserCenter.fetch(
                sessionJson = usableSession,
                deviceId = lepaoDeviceId(),
                model = android.os.Build.MODEL ?: "unknown",
                osVersion = android.os.Build.VERSION.RELEASE ?: "13",
                schoolId = settingsRepository.lastLepaoSchoolId
            )) {
                is com.kdzw8p.mqa.v71f.utils.LepaoUserCenter.Outcome.Success -> user.copy(
                    avatarUrl = outcome.stats.avatarUrl.ifBlank { user.avatarUrl },
                    nickname = outcome.stats.nickname.ifBlank { user.nickname },
                    runCount = user.runCount,
                    totalRunCount = user.totalRunCount,
                    totalDistanceM = outcome.stats.totalDistanceM,
                    venueId = outcome.stats.venueId.takeIf { it > 0 } ?: user.venueId,
                    venueName = outcome.stats.venueName.ifBlank { user.venueName }
                )
                is com.kdzw8p.mqa.v71f.utils.LepaoUserCenter.Outcome.Failure -> {
                    // 只有 102（被其它设备踢）才自动重登：myZone 对新鲜 token 也报 101，
                    // 拿它触发重登会造成「回读→重登→重启」循环（2026-10-24 真机观察到）
                    // 读取列表不触发重登：查询失败不能改变当前账号或踢掉其它设备。
                    user
                }
            }
        }
        if (switchInFlight || generation != accountSwitchGeneration) return@launch
        autoSaveDeviceProfile(merged, usableSession)
        // 导入登录的 ok 路径没有交互点能手动 reload 表格：落库后 bump 版本，界面观察它重读
        _uiState.update {
            it.copy(deviceUser = merged, lepaoProfilesVersion = it.lepaoProfilesVersion + 1)
        }
        onResult(com.kdzw8p.mqa.v71f.utils.LepaoUserReader.Result(user = merged))
    }
}

/**
 * 设备用户读取成功后自动落一份本地档案：按 user_id 复用旧档案，没有就新建；
 * 写入实时资料、头像和登录会话，功能页不需要再手点「保存档案」。
 */
fun MainViewModel.autoSaveDeviceProfile(
    user: com.kdzw8p.mqa.v71f.utils.LepaoUserReader.LepaoUser,
    sessionJson: String?
) {
    if (user.isEmpty || !com.kdzw8p.mqa.v71f.utils.LepaoRunData.isAccountId(user.userId)) return
    val root = settingsRepository.getLepaoProfiles()
    // 先按 user_id 匹配；匹配不到时按本次导入的 openid 认领占位档案（占位档案只有 wx_openid，没有 user_id）
    val identity = pendingSocialIdentity
    pendingSocialIdentity = null
    val existingName = root.keys().asSequence().firstOrNull { key ->
        root.optJSONObject(key)?.optString("user_id").orEmpty() == user.userId
    } ?: identity?.let { (provider, oid) ->
        root.keys().asSequence().firstOrNull { key ->
            root.optJSONObject(key)?.optString("${provider}_openid") == oid
        }
    }
    val name = existingName ?: user.nickname.ifBlank { user.userId }
    if (name.isBlank()) return
    val profile = existingName?.let { settingsRepository.getLepaoProfile(it) } ?: JSONObject()
    profile.put("user_id", user.userId)
    profile.put("nickname", user.nickname)
    profile.put("school", user.school)
    profile.put("student_no", user.studentNo)
    if (user.schoolId.isNotBlank()) profile.put("school_id", user.schoolId)
    profile.put("phone", user.phone)
    profile.put("role", user.role)
    profile.put("sex", user.sex)
    profile.put("avatar_url", user.avatarUrl)
    profile.put("face_img", user.faceImage)
    profile.put("server_run_count", user.runCount)
    profile.put("server_total_run_count", user.totalRunCount)
    profile.put("total_distance_m", user.totalDistanceM)
    profile.put("run_score", user.runScore)
    profile.put("venue_id", user.venueId)
    profile.put("venue_name", user.venueName)
    profile.put("captured_at", user.capturedAt)
    profile.put("source", user.source)
    // 宿主导出的 run_rule 是缓存，不得覆盖已按该账号会话查询的新规则。
    if (user.runRuleJson.isNotBlank() && profile.optString("rule_source") != "account_server_query") {
        profile.put("run_rule_json", user.runRuleJson)
        profile.put("rule_captured_at", user.capturedAt)
    }
    if (!sessionJson.isNullOrBlank()) {
        val incoming = runCatching { JSONObject(sessionJson) }.getOrNull()
        val incomingToken = incoming?.optString("token")?.ifBlank { incoming.optString("access_token") }.orEmpty()
        val retired = profile.optJSONArray("retired_session_hashes")
        val isRetired = retired != null && (0 until retired.length()).any {
            retired.optString(it) == com.kdzw8p.mqa.v71f.utils.LepaoApiCrypto.md5(incomingToken)
        }
        if (!isRetired) profile.put("session", sessionJson)
        if (profile.optString("loginMode").isBlank()) profile.put("loginMode", "session")
    }
    if (!profile.optBoolean("remark_edited") && profile.optString("remark").isBlank()) profile.put("remark", user.school)
    if (profile.optString("lat").isBlank()) profile.put("lat", _uiState.value.latitudeInput)
    if (profile.optString("lng").isBlank()) profile.put("lng", _uiState.value.longitudeInput)
    if (profile.optLong("createdAt") == 0L) profile.put("createdAt", System.currentTimeMillis())
    if (profile.optJSONObject("groups") == null) {
        profile.put("groups", settingsRepository.getTargetHookGroupsJson().optJSONObject(LEPAO_PKG) ?: JSONObject())
    }
    if (profile.optInt("runTarget", 0) == 0 && user.totalRunCount > 0) {
        profile.put("runTarget", user.totalRunCount)
    }
    var finalName = name
    // 占位名「微信-xxx」被真实账号认领后，改成真实昵称，不保留两条记录
    if (existingName != null && (existingName.startsWith("微信-") || existingName.startsWith("QQ-")) && user.nickname.isNotBlank() && user.nickname != existingName) {
        settingsRepository.deleteLepaoProfile(existingName)
        finalName = user.nickname
        var suffix = 2
        while (settingsRepository.getLepaoProfile(finalName) != null) finalName = "${user.nickname} (${suffix++})"
    }
    settingsRepository.saveLepaoProfile(finalName, profile)
}

/** 宿主导出只更新本地可观察资料，不在导出回调里重复请求网络。 */
fun MainViewModel.acceptLepaoUserExport(raw: String) {
    if (switchInFlight) return
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
    val user = bridgeUser(json)
    if (!com.kdzw8p.mqa.v71f.utils.LepaoRunData.isAccountId(user.userId)) return
    val activeUid = settingsRepository.getLepaoProfile(settingsRepository.lepaoActiveAccount)?.optString("user_id").orEmpty()
    if (activeUid.isNotBlank() && activeUid != user.userId) return
    autoSaveDeviceProfile(user, bridgeSession(json))
    _uiState.update { it.copy(deviceUser = user, lepaoProfilesVersion = it.lepaoProfilesVersion + 1) }
}

private fun bridgeUser(json: JSONObject): com.kdzw8p.mqa.v71f.utils.LepaoUserReader.LepaoUser {
    val rule = json.optString("run_rule")
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { JSONObject(it) }.getOrNull() }
    return com.kdzw8p.mqa.v71f.utils.LepaoUserReader.LepaoUser(
        userId = json.optString("user_id"),
        nickname = json.optString("user_nickname").ifBlank { json.optString("user_name") },
        school = json.optString("school_name"),
        studentNo = json.optString("user_num").ifBlank { json.optString("auth_num") },
        phone = json.optString("user_phone"),
        role = json.optString("role"),
        sex = json.optString("user_sex"),
        avatarUrl = json.optString("user_avatar_url")
            .ifBlank { rule?.optString("user_avatar", "").orEmpty() },
        faceImage = rule?.optString("face_img", "").orEmpty(),
        runCount = rule?.optInt("total_num", 0) ?: 0,
        totalRunCount = rule?.optInt("term_num", 0) ?: 0,
        totalDistanceM = rule?.optDouble("total_distance", 0.0) ?: 0.0,
        runScore = rule?.optString("run_score", "").orEmpty(),
        venueId = rule?.optLong("run_zone_id", 0L) ?: 0L,
        venueName = rule?.optString("run_zone_name", "").orEmpty().trim(),
        schoolId = json.optString("school_id").ifBlank { rule?.optString("school_id", "").orEmpty() },
        runRuleJson = rule?.let { com.kdzw8p.mqa.v71f.utils.LepaoRunData.redacted(it).toString() }.orEmpty(),
        capturedAt = json.optLong("exportedAt", 0L),
        source = "xposed_bridge"
    )
}

private fun bridgeSession(json: JSONObject): String? {
    val userId = json.optString("user_id")
    val token = json.optString("token").ifBlank { json.optString("access_token") }
    if (!com.kdzw8p.mqa.v71f.utils.LepaoRunData.isAccountId(userId) || token.isBlank()) return null
    return JSONObject()
        .put("user_id", userId)
        .put("token", token)
        .put("access_token", token)
        .apply {
            val rt = json.optString("refresh_token")
            if (rt.isNotBlank()) put("refresh_token", rt)
            val re = json.optString("refresh_expire")
            if (re.isNotBlank()) put("refresh_expire", re)
            val role = json.optString("role")
            if (role.isNotBlank()) put("role", role)
        }
        .toString()
}

/** 保存用户刚预览的同一份资料，不再次读取可能已切换的用户，不创建认证会话。 */
fun MainViewModel.importDeviceLepaoUser(
    name: String,
    user: com.kdzw8p.mqa.v71f.utils.LepaoUserReader.LepaoUser
): Boolean {
    if (user.isEmpty) return false
    val profile = JSONObject()
        .put("user_id", user.userId)
        .put("nickname", user.nickname)
        .put("school", user.school)
        .put("student_no", user.studentNo)
        .put("phone", user.phone)
        .put("role", user.role)
        .put("sex", user.sex)
        .put("avatar_url", user.avatarUrl)
        .put("face_img", user.faceImage)
        .put("server_run_count", user.runCount)
        .put("server_total_run_count", user.totalRunCount)
        .put("total_distance_m", user.totalDistanceM)
        .put("run_score", user.runScore)
        .put("venue_id", user.venueId)
        .put("venue_name", user.venueName)
        .put("remark", user.school)
        .put("createdAt", user.capturedAt)
        .put("captured_at", user.capturedAt)
        .put("source", user.source)
        .put("verification", "local_snapshot")
        .put("loginMode", "local")
        .put("groups", JSONObject())
    var finalName = name.trim().ifBlank { user.nickname.ifBlank { user.userId } }
    val baseName = finalName
    var suffix = 2
    while (settingsRepository.getLepaoProfile(finalName) != null) finalName = "$baseName (${suffix++})"
    settingsRepository.saveLepaoProfile(finalName, profile)
    return true
}

/** 改名/改备注：保留坐标与 hook 组快照 */
fun MainViewModel.updateLepaoProfile(name: String, newName: String, newRemark: String) {
    if (name == newName && newRemark == (settingsRepository.getLepaoProfile(name)?.optString("remark", "") ?: "")) return
    val src = settingsRepository.getLepaoProfile(name) ?: return
    src.put("remark", newRemark)
    if (name != newName) {
        settingsRepository.deleteLepaoProfile(name)
    }
    settingsRepository.saveLepaoProfile(newName, src)
}

fun MainViewModel.applyLepaoProfile(name: String) {
    val p = settingsRepository.getLepaoProfile(name) ?: return
    // 切到该账号时下发它自己的拍照/扫脸图；没有图则关闭替换，不沿用上一个账号的图
    applyLepaoFace(p.optString("face_b64", ""))
    pushFacePhotoConfig()
    _uiState.update {
        it.copy(
            latitudeInput = p.optString("lat", it.latitudeInput),
            longitudeInput = p.optString("lng", it.longitudeInput)
        )
    }
    val groups = p.optJSONObject("groups") ?: return
    groups.keys().forEach { k -> settingsRepository.setHookGroupEnabled(LEPAO_PKG, k, groups.optBoolean(k, true)) }
    viewModelScope.launch {
        locationRepository.patchConfig { json ->
            json.put("target_hook_groups", settingsRepository.getTargetHookGroupsJson())
        }
    }
}

/** 按用户保存拍照/扫脸图；空串 = 清除。只有当前激活账号才同步到全局下发配置。 */
fun MainViewModel.setLepaoProfileFace(name: String, base64: String) {
    val p = settingsRepository.getLepaoProfile(name) ?: return
    p.put("face_b64", base64)
    settingsRepository.saveLepaoProfile(name, p)
    if (settingsRepository.lepaoActiveAccount == name) {
        applyLepaoFace(base64)
        pushFacePhotoConfig()
    }
}

private fun MainViewModel.applyLepaoFace(base64: String) {
    settingsRepository.lepaoFacePhotoBase64 = base64
    settingsRepository.lepaoFacePhotoEnabled = base64.isNotBlank()
}

fun MainViewModel.deleteLepaoProfile(name: String) {
    settingsRepository.deleteLepaoProfile(name)
}

fun MainViewModel.lepaoProfileNames(): List<String> =
    settingsRepository.getLepaoProfiles().keys().asSequence().toList().sorted()

/** 乐跑账号会话注入的 JSON（单账号 id + token），空串表示不注入 */
fun MainViewModel.getLepaoSession(): String = settingsRepository.lepaoSession

fun MainViewModel.saveLepaoSession(json: String) {
    settingsRepository.lepaoSession = json
    viewModelScope.launch {
        locationRepository.patchConfig { /* 常驻设置项每次发布自动重读 */ }
    }
}

fun MainViewModel.getLepaoReportVersionCode(): String = settingsRepository.lepaoReportVersionCode

fun MainViewModel.getLepaoReportVersionName(): String = settingsRepository.lepaoReportVersionName

/**
 * 乐跑版本上报覆盖：空串 = 按真实安装版本上报（默认，不产生任何伪造痕迹）。
 * 写入后立即重发框架配置，Xposed 端 onConfig 会同步，已在运行的乐跑无需重启。
 */
fun MainViewModel.saveLepaoReportVersion(code: String, name: String) {
    settingsRepository.lepaoReportVersionCode = code
    settingsRepository.lepaoReportVersionName = name
    viewModelScope.launch {
        locationRepository.patchConfig { /* 常驻设置项每次发布自动重读 */ }
    }
}

/**
 * 优先离线冷切换；设备没有直接文件访问能力时走宿主写回，不把 su 当切号前提。
 */
/** 切换全局锁：连点会重复广播+重复重启乐跑，同一时间只允许一个切换流程 */
private var switchInFlight = false
private var accountSwitchGeneration = 0L

/** 最近一次微信凭证导入成功的 openid 与时间：5 分钟内同凭据重复导入会踢掉当前登录态，直接拦 */
private var lastSocialImportOkIdentity = ""
private var lastSocialImportOkAt = 0L
private val socialImportsInFlight = mutableSetOf<String>()

fun MainViewModel.switchLepaoAccount(
    name: String, resetTask: Boolean = false, onSuccess: () -> Unit = {}, onDone: (String) -> Unit = {}
) {
    if (switchInFlight) {
        onDone("切换正在进行，等回执结束再操作，避免重复广播与重启")
        return
    }
    val p = settingsRepository.getLepaoProfile(name)
    if (p == null) {
        onDone("档案不存在：$name")
        return
    }
    val session = p.optString("session", "")
    val sess = runCatching { JSONObject(session) }.getOrNull()
    val token = sess?.optString("token")?.ifBlank { sess.optString("access_token") }.orEmpty()
    if (token.isBlank() || !com.kdzw8p.mqa.v71f.utils.LepaoRunData.isAccountId(p.optString("user_id")) ||
        sess?.optString("user_id") != p.optString("user_id")) {
        onDone(context.getString(R.string.lepao_data_session_mismatch))
        return
    }
    switchInFlight = true
    accountSwitchGeneration++
    // 切号后旧账号的历史缓存必须作废：否则点历史记录会用新会话去查旧账号的记录，
    // owner 校验会直接拒绝（Record belongs to another account）。
    settingsRepository.lepaoRunRecordsJson = ""
    viewModelScope.launch {
        try {
            val written = withContext(Dispatchers.IO) {
                com.kdzw8p.mqa.v71f.utils.LepaoOfflineAccount.write(context, p)
            }
            if (!written) {
                val bridge = com.kdzw8p.mqa.v71f.utils.LepaoBridge
                bridge.note(context, "[切换] 离线通道不可用，使用宿主写回通道（不要求 su）")
                val requestId = java.util.UUID.randomUUID().toString()
                val payload = JSONObject()
                    .put("user_id", p.optString("user_id")).put("access_token", token)
                    .put("refresh_token", sess?.optString("refresh_token").orEmpty())
                    .put("refresh_expire", sess?.optString("refresh_expire").orEmpty())
                    .put("role", sess?.optInt("role", p.optInt("role", 1)) ?: p.optInt("role", 1))
                    .put("is_admin", p.optInt("is_admin", 0))
                    .put("name", p.optString("nickname")).put("img", p.optString("avatar_url"))
                    .put("phone", p.optString("phone"))
                val launch = context.packageManager.getLaunchIntentForPackage(LEPAO_PKG)
                if (launch == null) {
                    onDone(context.getString(R.string.lepao_switch_unavailable))
                    return@launch
                }
                context.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                val result = kotlinx.coroutines.withTimeoutOrNull(25_000) {
                    var ack: com.kdzw8p.mqa.v71f.utils.LepaoBridge.Result? = null
                    while (ack == null) {
                        context.sendBroadcast(android.content.Intent("com.kdzw8p.mqa.v71f.SWITCH_USER")
                            .setPackage(LEPAO_PKG).putExtra("request_id", requestId).putExtra("json", payload.toString()))
                        kotlinx.coroutines.delay(500)
                        ack = bridge.read(context)?.takeIf { it.requestId == requestId }
                    }
                    ack
                }
                if (result?.status != "ok") {
                    val message = if (result == null) context.getString(R.string.lepao_switch_timeout)
                        else context.getString(R.string.lepao_switch_host_failed)
                    bridge.note(context, "[切换] $message")
                    onDone(message)
                    return@launch
                }
                bridge.note(context, "[切换] 宿主已校验目标会话，立即同步模块表格")
            }
            // 两条通道均以实际写回成功为提交点，不提前发布当前账号。
            settingsRepository.lepaoSession = session
            settingsRepository.lepaoActiveAccount = name
            settingsRepository.lepaoDumpRequest = ""
            context.getSharedPreferences("lepao_bridge", android.content.Context.MODE_PRIVATE).edit().remove("json").apply()
            applyLepaoProfile(name)
            locationRepository.patchConfig { }
            val rule = com.kdzw8p.mqa.v71f.utils.LepaoRunData.objectOrNull(p.optString("run_rule_json"))
            _uiState.update { it.copy(
                deviceUser = com.kdzw8p.mqa.v71f.utils.LepaoUserReader.LepaoUser(
                    userId = p.optString("user_id"), nickname = p.optString("nickname"),
                    school = p.optString("school"), studentNo = p.optString("student_no"),
                    avatarUrl = p.optString("avatar_url"), schoolId = p.optString("school_id"),
                    runCount = rule?.optInt("total_num") ?: 0, totalRunCount = rule?.optInt("term_num") ?: 0,
                    venueName = p.optString("venue_name"), runRuleJson = rule?.toString().orEmpty()
                ), lepaoProfilesVersion = it.lepaoProfilesVersion + 1
            ) }
            val launch = context.packageManager.getLaunchIntentForPackage(LEPAO_PKG)
            if (launch == null) {
                onDone(context.getString(R.string.lepao_switch_launch_failed))
            } else {
                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                if (resetTask) launch.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                context.startActivity(launch)
                onSuccess()
                onDone(context.getString(if (written) R.string.lepao_switch_success else R.string.lepao_switch_host_success, name))
            }
            refreshLepaoProfileStats(name)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            onDone(context.getString(R.string.lepao_switch_launch_failed))
        } finally {
            switchInFlight = false
        }
    }
}

/** 仅用该账号绑定会话刷新，禁止回退到其它账号的全局会话。 */
private val profileStatsInFlight = mutableSetOf<String>()

fun MainViewModel.refreshLepaoProfileStats(name: String, onDone: (String) -> Unit = {}) =
    viewModelScope.launch {
        if (!profileStatsInFlight.add(name)) {
            onDone(context.getString(R.string.lepao_data_loading))
            return@launch
        }
        try {
        val p = settingsRepository.getLepaoProfile(name) ?: return@launch
        var session = p.optString("session")
        val identity = runCatching { JSONObject(session).optString("user_id") }.getOrDefault("")
        if (session.isBlank() || !com.kdzw8p.mqa.v71f.utils.LepaoRunData.isAccountId(identity) || identity != p.optString("user_id")) {
            val error = context.getString(R.string.lepao_data_session_mismatch)
            p.put("stats_error", error)
            p.put("stats_attempt_at", System.currentTimeMillis())
            settingsRepository.saveLepaoProfile(name, p)
            _uiState.update { it.copy(lepaoProfilesVersion = it.lepaoProfilesVersion + 1) }
            onDone(error)
            return@launch
        }
        // 今日已跑是列表里最先要看的字段：先并发发起，跟规则/统计同时跑。
        // 结果不在这里落库，等统计写完再合并，避免两个 read-modify-write 互相覆盖。
        val todayJob = async { fetchLepaoTodayRun(name) }
        // 规则与主页统计互不依赖，先并发发起；等规则完成提交后再写回统计，避免整档案互相覆盖。
        val rulesJob = refreshLepaoRules(name)
        suspend fun fetchStats() = withContext(Dispatchers.IO) {
            com.kdzw8p.mqa.v71f.utils.LepaoUserCenter.fetch(
                session, lepaoDeviceId(), android.os.Build.MODEL ?: "unknown",
                android.os.Build.VERSION.RELEASE ?: "13",
                settingsRepository.getLepaoProfile(name)?.optString("school_id")
                    .orEmpty().ifBlank { settingsRepository.lastLepaoSchoolId })
        }
        suspend fun fetchIdentity(): JSONObject? = withContext(Dispatchers.IO) {
            try {
                com.kdzw8p.mqa.v71f.utils.LepaoReadClient.identity(session)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        suspend fun fetchAccountData(): Pair<
            com.kdzw8p.mqa.v71f.utils.LepaoUserCenter.Outcome, JSONObject?
        > = coroutineScope {
            val stats = async { fetchStats() }
            val identityInfo = async { fetchIdentity() }
            stats.await() to identityInfo.await()
        }
        var fetched = fetchAccountData()
        var outcome = fetched.first
        var identityInfo = fetched.second
        rulesJob.join()
        val sessionAfterRules = settingsRepository.getLepaoProfile(name)
            ?.takeIf { it.optString("user_id") == identity }
            ?.optString("session") ?: return@launch
        if (sessionAfterRules != session) {
            // 规则请求可能同时完成续期；旧会话的主页结果不能写回新会话。
            session = sessionAfterRules
            fetched = fetchAccountData()
            outcome = fetched.first
            identityInfo = fetched.second
        }
        if (outcome is com.kdzw8p.mqa.v71f.utils.LepaoUserCenter.Outcome.Failure && outcome.sessionInvalid) {
            val renewed = renewLepaoSession(name, session)
            if (renewed != null) {
                session = renewed
                fetched = fetchAccountData()
                outcome = fetched.first
                identityInfo = fetched.second
            }
        }
        // 网络等待期间可能发生删档/换会话，不把旧请求写回新身份。
        val current = settingsRepository.getLepaoProfile(name) ?: return@launch
        if (current.optString("session") != session) return@launch
        current.put("stats_attempt_at", System.currentTimeMillis())
        when (outcome) {
            is com.kdzw8p.mqa.v71f.utils.LepaoUserCenter.Outcome.Success -> {
                val s = outcome.stats
                current.put("stats_json", s.rawJson)
                current.put("stats_fetched_at", System.currentTimeMillis())
                current.remove("stats_error")
                val data = JSONObject(s.rawJson)
                val accountInfo = data.optJSONObject("account_info")
                if (accountInfo != null) {
                    if (accountInfo.optString("school_name").isNotBlank()) current.put("school", accountInfo.optString("school_name"))
                    if (accountInfo.optString("student_num").isNotBlank()) current.put("student_no", accountInfo.optString("student_num"))
                    if (accountInfo.optString("school_id").isNotBlank()) current.put("school_id", accountInfo.optString("school_id"))
                }
                val total = data.optJSONObject("running_stat")?.optJSONObject("total")
                val rule = data.optJSONObject("run_rule")
                if (total?.has("total_valid_distance") == true) current.put("total_distance_m", s.totalDistanceM)
                if (rule?.has("total_num") == true) current.put("server_run_count", rule.optInt("total_num"))
                if (rule?.has("term_num") == true) current.put("server_total_run_count", rule.optInt("term_num"))
                // myZone 不拥有跑前规则；不得用不完整的主页字段覆盖 beforeRun 结果。
                if (s.avatarUrl.isNotBlank()) current.put("avatar_url", s.avatarUrl)
                if (s.nickname.isNotBlank()) current.put("nickname", s.nickname)
                if (s.venueName.isNotBlank()) current.put("venue_name", s.venueName)
                // 身份认证资料与主页统计并发读取；失败只影响身份字段，不影响主页统计。
                identityInfo?.let { info ->
                    val id = JSONObject()
                    for ((key, field) in listOf(
                        "real_name" to "name", "academy" to "academy", "major" to "major",
                        "class" to "class", "sex" to "sex", "grade" to "grade",
                        "student_no" to "student_num", "school" to "school"
                    )) {
                        val v = info.optString(field)
                        if (v.isNotBlank()) id.put(key, v)
                    }
                    if (id.length() > 0) current.put("lepao_identity_json", id.toString())
                }
                onDone(context.getString(R.string.lepao_data_updated))
            }
            is com.kdzw8p.mqa.v71f.utils.LepaoUserCenter.Outcome.Failure -> {
                current.put("stats_error", "${outcome.status}: ${outcome.message}")
                onDone(context.getString(R.string.lepao_data_failed, outcome.message))
            }
        }
        settingsRepository.saveLepaoProfile(name, current)
        _uiState.update { it.copy(lepaoProfilesVersion = it.lepaoProfilesVersion + 1) }
        // 统计先落库，再合并已经并行取回的今日记录：今日字段不会被统计写覆盖。
        todayJob.await()?.let { applyLepaoTodayRun(name, it) }
        } finally {
            profileStatsInFlight.remove(name)
        }
    }

fun MainViewModel.getLepaoFacePhotoEnabled(): Boolean = settingsRepository.lepaoFacePhotoEnabled

fun MainViewModel.setLepaoFacePhotoEnabled(enabled: Boolean) {
    settingsRepository.lepaoFacePhotoEnabled = enabled
    pushFacePhotoConfig()
}

fun MainViewModel.getLepaoFacePhoto(): String = settingsRepository.lepaoFacePhotoBase64

fun MainViewModel.saveLepaoFacePhoto(base64: String) {
    settingsRepository.lepaoFacePhotoBase64 = base64
    pushFacePhotoConfig()
}

fun MainViewModel.getLastLepaoSchoolId(): String = settingsRepository.lastLepaoSchoolId

fun MainViewModel.rememberLepaoSchoolId(schoolId: String) {
    if (schoolId.isNotBlank()) settingsRepository.lastLepaoSchoolId = schoolId
}

fun MainViewModel.exportLepaoBackup(): String = settingsRepository.exportLepaoBackup()

fun MainViewModel.importLepaoBackup(json: String): Boolean = settingsRepository.importLepaoBackup(json)

private fun MainViewModel.lepaoDeviceId(): String =
    android.provider.Settings.Secure.getString(
        context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
    ) ?: "unknown"

/** 拉取 School/nearbySchool 的全量学校列表并缓存，供直登选校。 */
fun MainViewModel.fetchLepaoSchools(onResult: (com.kdzw8p.mqa.v71f.utils.LepaoSchoolDirectory.Outcome) -> Unit) {
    viewModelScope.launch {
        val (lat, lng) = withContext(Dispatchers.IO) {
            if (com.kdzw8p.mqa.v71f.data.state.SpoofingState.isActive) {
                com.kdzw8p.mqa.v71f.data.state.SpoofingState.latitude.toString() to
                    com.kdzw8p.mqa.v71f.data.state.SpoofingState.longitude.toString()
            } else {
                // 没有在模拟时用本地档案里的坐标，避免 lat/lng=0 被服务端拒绝
                val profile = settingsRepository.getLepaoProfiles().keys().asSequence()
                    .mapNotNull { settingsRepository.getLepaoProfile(it) }
                    .firstOrNull {
                        it.optString("lat").isNotBlank() && it.optString("lng").isNotBlank()
                    }
                (profile?.optString("lat").orEmpty().ifBlank { "30.5728" }) to
                    (profile?.optString("lng").orEmpty().ifBlank { "104.0668" })
            }
        }
        val outcome = withContext(Dispatchers.IO) {
            com.kdzw8p.mqa.v71f.utils.LepaoSchoolDirectory.fetch(
                lat = lat,
                lng = lng,
                province = "未知",
                city = "未知",
                deviceId = lepaoDeviceId(),
                model = android.os.Build.MODEL ?: "unknown",
                osVersion = android.os.Build.VERSION.RELEASE ?: "13"
            )
        }
        if (outcome is com.kdzw8p.mqa.v71f.utils.LepaoSchoolDirectory.Outcome.Success) {
            val arr = org.json.JSONArray()
            outcome.schools.forEach { school ->
                arr.put(
                    org.json.JSONObject()
                        .put("id", school.id)
                        .put("name", school.name)
                        .put("key", school.key)
                        .put("nearby", school.nearby)
                )
            }
            settingsRepository.lepaoSchoolsJson = org.json.JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("list", arr)
                .toString()
        }
        onResult(outcome)
    }
}

fun MainViewModel.cachedLepaoSchools(): List<com.kdzw8p.mqa.v71f.utils.LepaoSchoolDirectory.School> {
    val raw = settingsRepository.lepaoSchoolsJson
    if (raw.isBlank()) return emptyList()
    return runCatching {
        val list = org.json.JSONObject(raw).optJSONArray("list") ?: return emptyList()
        (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optLong("id", 0L)
            val name = item.optString("name", "")
            if (id <= 0L || name.isBlank()) null
            else com.kdzw8p.mqa.v71f.utils.LepaoSchoolDirectory.School(
                id, name, item.optString("key", ""), item.optBoolean("nearby", false)
            )
        }
    }.getOrDefault(emptyList())
}

/** 用当前设备登录态拉 Run2/allRecordList，缓存最近一次结果。 */
fun MainViewModel.fetchLepaoRunHistory(onResult: (com.kdzw8p.mqa.v71f.utils.LepaoRunHistory.Outcome) -> Unit) {
    viewModelScope.launch {
        val session = withContext(Dispatchers.IO) {
            com.kdzw8p.mqa.v71f.utils.LepaoUserReader.readSessionResult().sessionJson
                ?: settingsRepository.lepaoSession.takeIf { it.isNotBlank() }
        }
        if (session == null) {
            onResult(com.kdzw8p.mqa.v71f.utils.LepaoRunHistory.Outcome.Failure("没有可用登录态，先在乐跑登录或直登"))
            return@launch
        }
        val outcome = withContext(Dispatchers.IO) {
            com.kdzw8p.mqa.v71f.utils.LepaoRunHistory.fetch(
                sessionJson = session,
                deviceId = lepaoDeviceId(),
                model = android.os.Build.MODEL ?: "unknown",
                osVersion = android.os.Build.VERSION.RELEASE ?: "13",
                schoolId = settingsRepository.lastLepaoSchoolId
            )
        }
        if (outcome is com.kdzw8p.mqa.v71f.utils.LepaoRunHistory.Outcome.Success) {
            val arr = org.json.JSONArray()
            outcome.page.records.forEach { record ->
                arr.put(
                    org.json.JSONObject()
                        .put("name", record.name)
                        .put("distance", record.distance)
                        .put("pace", record.pace)
                        .put("start_time", record.startTime)
                        .put("used_time", record.usedTime)
                        .put("record_id", record.recordId)
                        .put("year", record.year)
                        .put("uid", record.ownerId)
                )
            }
            settingsRepository.lepaoRunRecordsJson = org.json.JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("total", outcome.page.totalNum)
                .put("list", arr)
                .toString()
        }
        onResult(outcome)
    }
}

/** 账号历史查询只使用该档案会话，不从设备快照或全局缓存取其他身份。 */
fun MainViewModel.fetchLepaoAccountHistory(
    name: String, year: String, page: Int,
    onResult: (com.kdzw8p.mqa.v71f.utils.LepaoRunHistory.Outcome) -> Unit
) {
    viewModelScope.launch {
        val p = settingsRepository.getLepaoProfile(name)
        val session = p?.optString("session").orEmpty()
        val uid = runCatching { JSONObject(session).optString("user_id") }.getOrDefault("")
        if (uid.isBlank() || uid != p?.optString("user_id")) {
            onResult(com.kdzw8p.mqa.v71f.utils.LepaoRunHistory.Outcome.Failure(context.getString(R.string.lepao_data_session_mismatch)))
            return@launch
        }
        val result = withContext(Dispatchers.IO) {
            com.kdzw8p.mqa.v71f.utils.LepaoRunHistory.fetch(session, year, page, lepaoDeviceId(),
                android.os.Build.MODEL ?: "unknown", android.os.Build.VERSION.RELEASE ?: "13",
                p.optString("school_id").ifBlank { settingsRepository.lastLepaoSchoolId })
        }
        if (settingsRepository.getLepaoProfile(name)?.optString("session") == session) onResult(result)
    }
}

fun MainViewModel.fetchLepaoAccountRecord(
    name: String, record: com.kdzw8p.mqa.v71f.utils.LepaoRunHistory.Record,
    onResult: (com.kdzw8p.mqa.v71f.utils.LepaoRunHistory.DetailOutcome) -> Unit
) {
    viewModelScope.launch {
        val p = settingsRepository.getLepaoProfile(name)
        val session = p?.optString("session").orEmpty()
        val uid = runCatching { JSONObject(session).optString("user_id") }.getOrDefault("")
        if (uid.isBlank() || uid != p?.optString("user_id")) {
            onResult(com.kdzw8p.mqa.v71f.utils.LepaoRunHistory.DetailOutcome.Failure(context.getString(R.string.lepao_data_session_mismatch)))
            return@launch
        }
        val result = withContext(Dispatchers.IO) {
            com.kdzw8p.mqa.v71f.utils.LepaoRunHistory.detail(session, record, lepaoDeviceId(),
                android.os.Build.MODEL ?: "unknown", android.os.Build.VERSION.RELEASE ?: "13",
                p.optString("school_id").ifBlank { settingsRepository.lastLepaoSchoolId })
        }
        if (settingsRepository.getLepaoProfile(name)?.optString("session") == session) onResult(result)
    }
}

/** 用当前设备登录态读单条记录详情（recordDetailV270，只读）。 */
fun MainViewModel.fetchLepaoRunDetail(
    record: com.kdzw8p.mqa.v71f.utils.LepaoRunHistory.Record,
    onResult: (com.kdzw8p.mqa.v71f.utils.LepaoRunHistory.DetailOutcome) -> Unit
) {
    viewModelScope.launch {
        val session = withContext(Dispatchers.IO) {
            com.kdzw8p.mqa.v71f.utils.LepaoUserReader.readSessionResult().sessionJson
                ?: settingsRepository.lepaoSession.takeIf { it.isNotBlank() }
        }
        if (session == null) {
            onResult(com.kdzw8p.mqa.v71f.utils.LepaoRunHistory.DetailOutcome.Failure("没有可用登录态，先在乐跑登录或直登"))
            return@launch
        }
        // 旧缓存没有 year，按当前年份兜底。
        val fixed = if (record.year.isBlank()) record.copy(year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()) else record
        onResult(withContext(Dispatchers.IO) {
            com.kdzw8p.mqa.v71f.utils.LepaoRunHistory.detail(session, fixed, lepaoDeviceId(),
                android.os.Build.MODEL ?: "unknown", android.os.Build.VERSION.RELEASE ?: "13",
                settingsRepository.lastLepaoSchoolId)
        })
    }
}

fun MainViewModel.cachedLepaoRunRecords(): List<com.kdzw8p.mqa.v71f.utils.LepaoRunHistory.Record> {
    val raw = settingsRepository.lepaoRunRecordsJson
    if (raw.isBlank()) return emptyList()
    return runCatching {
        val list = org.json.JSONObject(raw).optJSONArray("list") ?: return emptyList()
        (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            com.kdzw8p.mqa.v71f.utils.LepaoRunHistory.Record(
                name = item.optString("name", ""),
                distance = item.optString("distance", ""),
                pace = item.optString("pace", ""),
                startTime = item.optString("start_time", ""),
                usedTime = item.optString("used_time", ""),
                recordId = item.optLong("record_id", 0L),
                ownerId = item.optString("uid", ""),
                year = item.optString("year", "")
            )
        }
    }.getOrDefault(emptyList())
}

fun MainViewModel.exchangeWeChatCode(code: String, onResult: (com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.WeChatCodeOutcome) -> Unit) {
    viewModelScope.launch {
        val outcome = withContext(Dispatchers.IO) {
            com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.exchangeWeChatCode(code)
        }
        onResult(outcome)
    }
}

/** 乐跑直登：微信 openid+accesstoken / 学校 app_token / 手机验证码 → quickLoginV339 */
fun MainViewModel.directLoginLepao(
    mode: com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.Mode,
    schoolId: String,
    phone: String = "",
    code: String = "",
    appToken: String = "",
    openId: String = "",
    accessToken: String = "",
    refreshToken: String = "",
    onResult: (com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.Outcome) -> Unit
) {
    viewModelScope.launch {
        val outcome = withContext(Dispatchers.IO) {
            val deviceId = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
            var wxOpenId = openId.trim()
            var wxToken = accessToken.trim()
            var refreshed: com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.WeChatTokens? = null
            if (mode == com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.Mode.WECHAT && refreshToken.isNotBlank()) {
                // access_token 只有 2 小时；有 refresh_token 先刷，刷不出来再用现值兑底
                when (val r = com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.refreshWeChatToken(refreshToken.trim())) {
                    is com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.WeChatCodeOutcome.Success -> {
                        wxOpenId = r.tokens.openId
                        wxToken = r.tokens.accessToken
                        refreshed = r.tokens
                    }
                    is com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.WeChatCodeOutcome.Failure -> {
                        if (wxToken.isBlank()) {
                            return@withContext com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.Outcome.Failure(
                                "微信凭证已过期且刷新失败：${r.message}"
                            )
                        }
                    }
                }
            }
            val login = com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.login(
                mode = mode,
                schoolId = schoolId.trim(),
                deviceId = deviceId,
                model = android.os.Build.MODEL ?: "unknown",
                osVersion = android.os.Build.VERSION.RELEASE ?: "13",
                phone = phone.trim(),
                code = code.trim(),
                appToken = appToken.trim(),
                openId = wxOpenId,
                accessToken = wxToken
            )
            if (login is com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.Outcome.Success && refreshed != null) {
                login.copy(refreshedWeChat = refreshed)
            } else {
                login
            }
        }
        if (outcome is com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.Outcome.Success) {
            saveLepaoSession(outcome.sessionJson)
        }
        onResult(outcome)
    }
}

/** 微信 refresh_token 续期（30 天内可反复刷），成功后 UI 回填最新三元组并重新生成授权码 */
fun MainViewModel.refreshWeChatToken(
    refreshToken: String,
    onResult: (com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.WeChatCodeOutcome) -> Unit
) {
    viewModelScope.launch {
        val outcome = withContext(Dispatchers.IO) {
            com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.refreshWeChatToken(refreshToken.trim())
        }
        onResult(outcome)
    }
}

/** 读回 rc.a.V0 落盘的登录态（乐跑 filesDir/ls_token_capture.json，需 root） */
data class LepaoSessionCapture(
    val uid: String,
    val accessToken: String,
    val refreshToken: String,
    val refreshExpire: String
)

fun MainViewModel.readLepaoSessionCapture(onResult: (LepaoSessionCapture?) -> Unit) {
    viewModelScope.launch {
        val capture = withContext(Dispatchers.IO) {
            val text = runCatching {
                val proc = ProcessBuilder(
                    "su", "-c", "cat /data/data/$LEPAO_PKG/files/ls_token_capture.json 2>/dev/null"
                ).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().use { it.readText() }
                if (proc.waitFor() == 0 && out.isNotBlank()) out else null
            }.getOrNull() ?: return@withContext null
            val obj = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return@withContext null
            val uid = obj.optString("uid")
            val token = obj.optString("access_token").ifBlank { obj.optString("token") }
            if (uid.isBlank() || token.isBlank()) null
            else LepaoSessionCapture(uid, token, obj.optString("refresh_token"), obj.optString("refresh_expire"))
        }
        onResult(capture)
    }
}

/** 读回 hook 落盘的微信授权三元组（乐跑 filesDir/ls_wx_auth.json，需 root） */
fun MainViewModel.readLepaoWxAuthCapture(
    onResult: (com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.WeChatCodeOutcome) -> Unit
) {
    viewModelScope.launch {
        val outcome = withContext(Dispatchers.IO) {
            val proc = try {
                ProcessBuilder(
                    "su", "-c", "cat /data/data/$LEPAO_PKG/files/ls_wx_auth.json 2>/dev/null"
                ).redirectErrorStream(true).start()
            } catch (_: Exception) {
                return@withContext com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.WeChatCodeOutcome
                    .Failure("无法执行 su（模块 App 需要 root）")
            }
            val text = proc.inputStream.bufferedReader().use { it.readText() }
            if (proc.waitFor() != 0 || text.isBlank()) {
                return@withContext com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.WeChatCodeOutcome
                    .Failure("没有捕获记录（先在装有微信的乐跑里登录一次）")
            }
            com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.parseWeChatAuthPayload(text)
        }
        onResult(outcome)
    }
}

/**
 * 一键导入并登录（取代原来的“下发内核”黑盒）：
 * 1) 按 openid 查历史账号：命中就替换 token，没有就新建档案；
 * 2) 把凭据下发给乐跑进程（Xposed 用 App 自己的 p2.f 走 quickLoginV339）；
 * 3) 强停并自动打开乐跑；
 * 4) 轮询乐跑回执，每一步都回给界面，并写一份可查日志。
 */
fun MainViewModel.importWxAndLogin(
    openid: String, accessToken: String, refreshToken: String, nick: String, avatar: String,
    schoolId: String, force: Boolean = false, onSuccess: () -> Unit = {}, onStep: (String, Boolean) -> Unit
) = importSocialAndLogin(
    com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.Mode.WECHAT,
    openid, accessToken, refreshToken, nick, avatar, schoolId, force, onSuccess, onStep
)

fun MainViewModel.importQqAndLogin(
    openid: String, accessToken: String, schoolId: String, onSuccess: () -> Unit = {},
    onStep: (String, Boolean) -> Unit
) = importSocialAndLogin(
    com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.Mode.QQ,
    openid, accessToken, "", "", "", schoolId, false, onSuccess, onStep
)

/** QQ 凭据只从 QQ 授权记录读取，不触碰微信凭据文件。 */
fun MainViewModel.readLepaoQqAuthCapture(
    onResult: (com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.QqAuthOutcome) -> Unit
) {
    viewModelScope.launch {
        val outcome = withContext(Dispatchers.IO) {
            try {
                val proc = ProcessBuilder(
                    "su", "-c", "cat /data/data/$LEPAO_PKG/files/ls_qq_auth.json 2>/dev/null"
                ).redirectErrorStream(true).start()
                val raw = proc.inputStream.bufferedReader().use { it.readText() }
                if (proc.waitFor() != 0 || raw.isBlank()) {
                    com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.QqAuthOutcome.Failure(
                        context.getString(R.string.lepao_qq_capture_missing)
                    )
                } else com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.parseQqAuthPayload(raw)
            } catch (_: Exception) {
                com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.QqAuthOutcome.Failure(
                    context.getString(R.string.lepao_qq_capture_failed)
                )
            }
        }
        onResult(outcome)
    }
}

private fun MainViewModel.importSocialAndLogin(
    mode: com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.Mode,
    openid: String,
    accessToken: String,
    refreshToken: String,
    nick: String,
    avatar: String,
    schoolId: String,
    force: Boolean = false,
    onSuccess: () -> Unit,
    onStep: (text: String, finished: Boolean) -> Unit
) {
    val isQq = mode == com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.Mode.QQ
    val provider = if (isQq) "qq" else "wx"
    val label = if (isQq) "QQ" else "微信"
    val bridge = com.kdzw8p.mqa.v71f.utils.LepaoBridge
    viewModelScope.launch {
        val oid = openid.trim()
        var at = accessToken.trim()
        var rt = refreshToken.trim()
        if (socialImportsInFlight.isNotEmpty()) {
            onStep(context.getString(R.string.lepao_social_busy), true)
            return@launch
        }
        socialImportsInFlight.add("$provider:$oid")
        try {
        suspend fun applyImported(name: String) {
            onStep(context.getString(R.string.lepao_social_wait_host), false)
            var applied = false
            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
            switchLepaoAccount(name, resetTask = true, onSuccess = { applied = true }) { message ->
                if (applied) { onSuccess(); onStep(context.getString(R.string.lepao_social_complete), true) }
                else onStep(message, true)
                done.complete(Unit)
            }
            done.await()
        }
        if (oid.isBlank() || at.isBlank()) {
            onStep("openid / accesstoken 不能为空", true)
            return@launch
        }
        // 同凭据去重：5 分钟内同一 openid 已有成功回执就不再发请求——已消费的微信凭据
        // 再请求会被服务端拒，且强停重启会把当前登录态踢掉；自动重登走 force 绕过
        if (!force && "$provider:$oid" == lastSocialImportOkIdentity && System.currentTimeMillis() - lastSocialImportOkAt < 5 * 60_000) {
            val saved = settingsRepository.getLepaoProfiles()
            val existing = saved.keys().asSequence().firstOrNull { key ->
                val p = saved.getJSONObject(key)
                val s = runCatching { JSONObject(p.optString("session")) }.getOrNull()
                p.optString("${provider}_openid") == oid &&
                    com.kdzw8p.mqa.v71f.utils.LepaoRunData.isAccountId(p.optString("user_id")) &&
                    s?.optString("user_id") == p.optString("user_id") && s.optString("token").isNotBlank()
            }
            if (existing != null) { applyImported(existing); return@launch }
        }
        // 0 access_token 只有 2 小时寿命：有 refresh_token 先续期，否则服务端会直接拒
        if (!isQq && rt.isNotBlank()) {
            onStep("0/4 用 refresh_token 续期 access_token…", false)
            val outcome = withContext(Dispatchers.IO) {
                com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.refreshWeChatToken(rt)
            }
            when (outcome) {
                is com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.WeChatCodeOutcome.Success -> {
                    at = outcome.tokens.accessToken.ifBlank { at }
                    rt = outcome.tokens.refreshToken.ifBlank { rt }
                    bridge.note(context, "access_token 已续期")
                    onStep("0/4 access_token 已续期", false)
                }
                is com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.WeChatCodeOutcome.Failure -> {
                    bridge.note(context, "续期失败：${outcome.message}")
                    onStep("0/4 续期失败（${outcome.message}），改用原 access_token", false)
                }
            }
        }
        val requestId = System.currentTimeMillis().toString()

        // 1 历史账号去重：同一个 openid 只留一条，旧的直接换 token
        val root = settingsRepository.getLepaoProfiles()
        var matched: String? = null
        root.keys().asSequence().forEach { key ->
            val p = root.optJSONObject(key) ?: return@forEach
            val sessionOpenid = runCatching { JSONObject(p.optString("session")).optString("openid") }.getOrDefault("")
            if (p.optString("${provider}_openid") == oid ||
                (p.optString("loginMode") == (if (isQq) "qq" else "wechat") && sessionOpenid == oid)) matched = key
        }
        var name = matched ?: nick.ifBlank { "$label-" + oid.takeLast(6) }
        if (matched == null) {
            val baseName = name
            var suffix = 2
            while (settingsRepository.getLepaoProfile(name) != null) name = "$baseName (${suffix++})"
        }
        val profile = matched?.let { settingsRepository.getLepaoProfile(it) } ?: JSONObject()
        profile.put("${provider}_openid", oid)
        profile.put("${provider}_access_token", at)
        if (!isQq && rt.isNotBlank()) profile.put("wx_refresh_token", rt)
        if (nick.isNotBlank()) profile.put("nickname", nick)
        if (avatar.isNotBlank()) profile.put("avatar_url", avatar)
        if (schoolId.isNotBlank()) profile.put("school_id", schoolId)
        profile.put("loginMode", if (isQq) "qq" else "wechat")
        // 第三方票据不是乐跑会话；授权未完成前不覆盖有效session，也不发布新占位档案。
        if (!profile.optBoolean("remark_edited") && profile.optString("remark").isBlank()) profile.put("remark", "$label 导入")
        if (profile.optString("lat").isBlank()) profile.put("lat", _uiState.value.latitudeInput)
        if (profile.optString("lng").isBlank()) profile.put("lng", _uiState.value.longitudeInput)
        if (profile.optLong("createdAt") == 0L) profile.put("createdAt", System.currentTimeMillis())
        if (profile.optJSONObject("groups") == null) {
            profile.put("groups", settingsRepository.getTargetHookGroupsJson().optJSONObject(LEPAO_PKG) ?: JSONObject())
        }
        bridge.note(context, "[导入] 身份已解析，等待服务端确认UID后合并档案")
        onStep(context.getString(R.string.lepao_social_wait_identity), false)

        // 2/4 纯 HTTP 直连 quickLoginV339：不依赖乐跑进程存活（用户要求 2026-10-24）。
        // 成功直接把会话落档案并实时推送给运行中的乐跑；失败才回落原桥接链
        onStep("2/4 纯 HTTP 直连 quickLoginV339（不依赖乐跑进程）…", false)
        val http = withContext(Dispatchers.IO) {
            com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.login(
                mode = mode,
                schoolId = schoolId.ifBlank { settingsRepository.lastLepaoSchoolId },
                deviceId = lepaoDeviceId(),
                model = android.os.Build.MODEL ?: "M2102K1AC",
                osVersion = android.os.Build.VERSION.RELEASE ?: "13",
                openId = oid,
                accessToken = at,
                nickname = nick,
                avatarUrl = avatar
            )
        }
        if (http is com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.Outcome.Success) {
            val sessionJson = http.sessionJson
            profile.put("user_id", http.result.userId).put("session", sessionJson)
            if (http.result.nickname.isNotBlank()) profile.put("nickname", http.result.nickname)
            if (http.result.avatarUrl.isNotBlank()) profile.put("avatar_url", http.result.avatarUrl)
            name = settingsRepository.mergeLepaoSocialProfile(provider, oid, profile)
            _uiState.update { it.copy(lepaoProfilesVersion = it.lepaoProfilesVersion + 1) }
            bridge.note(context, "[导入] 服务端登录成功，已合并真实账号，等待宿主确认")
            lastSocialImportOkIdentity = "$provider:$oid"
            lastSocialImportOkAt = System.currentTimeMillis()
            clearWxLoginConfig()
            applyImported(name)
            return@launch
        }
        val httpFail = (http as? com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin.Outcome.Failure)?.message ?: "未知"
        bridge.note(context, "[导入] 纯 HTTP 登录失败：$httpFail，回落桥接链路")
        onStep("2/4 纯 HTTP 登录失败（$httpFail），回落乐跑进程桥接", false)

        // 2 下发凭据
        bridge.clearResult(context)
        val startedAt = System.currentTimeMillis()
        locationRepository.patchConfig { json ->
            json.put("lepao_wx_login", JSONObject().apply {
                put("type", if (isQq) 2 else 3)
                put("openid", oid)
                put("access_token", at)
                put("nick", nick)
                put("avatar", avatar)
                put("school_id", schoolId)
                put("request_id", requestId)
            })
        }
        bridge.note(context, "已下发凭据 request_id=$requestId")
        onStep("2/4 凭据已下发给乐跑进程", false)

        // 3 强停 + 自动打开乐跑，让它自己走登录
        runCatching { locationRepository.forceStopApps(listOf(LEPAO_PKG)) }
        kotlinx.coroutines.delay(1200)
        val launched = runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(LEPAO_PKG)
                ?: return@runCatching false
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        bridge.note(context, if (launched) "已自动启动乐跑" else "启动乐跑失败")
        onStep(if (launched) "3/4 已自动打开乐跑，等它完成登录…" else "3/4 乐跑启动失败，请手动打开乐跑", false)

        // 4 轮询回执：成功/失败/模块未重载都要给明确提示
        var last = ""
        repeat(45) {
            kotlinx.coroutines.delay(1000)
            val r = bridge.read(context) ?: return@repeat
            if (r.requestId != requestId || r.ts < startedAt - 3000) return@repeat
            if (r.version.isNotBlank() && r.version != bridge.VERSION) {
                val stale = "4/4 乐跑里跑的还是旧模块（${r.version}，当前应为 ${bridge.VERSION}）：" +
                    "在 LSPosed 里关掉再打开本模块作用域，或重启模拟器后重试"
                if (stale != last) {
                    last = stale
                    bridge.note(context, stale)
                    onStep(stale, false)
                }
                return@repeat
            }
            val text = "4/4 乐跑回执 ${r.status}：${r.message}"
            if (r.status == "pending" && text != last) { last = text; onStep(text, false) }
            if (r.status == "ok") {
                val exported = context.getSharedPreferences("lepao_bridge", android.content.Context.MODE_PRIVATE)
                    .getString("authorization_json", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
                val session = exported?.takeIf {
                    com.kdzw8p.mqa.v71f.utils.LepaoSocialProfiles.matchesAuthorizationExport(it, requestId, startedAt)
                }?.let { bridgeSession(it) }
                // 数据广播和回执到达顺序不固定；只等待同一授权结果，不读取碰巧同时到达的别的账号。
                if (session == null) return@repeat
                val user = bridgeUser(exported)
                profile.put("user_id", user.userId).put("session", session)
                profile.put("nickname", user.nickname).put("avatar_url", user.avatarUrl)
                name = settingsRepository.mergeLepaoSocialProfile(provider, oid, profile)
                lastSocialImportOkIdentity = "$provider:$oid"
                lastSocialImportOkAt = System.currentTimeMillis()
                _uiState.update { it.copy(lepaoProfilesVersion = it.lepaoProfilesVersion + 1) }
                clearWxLoginConfig()
                applyImported(name)
                return@launch
            }
            if (r.status == "error") {
                onStep(text, true)
                clearWxLoginConfig()
                return@launch
            }
        }
        clearWxLoginConfig()
        val timeout = "4/4 45 秒内没收到乐跑回执：模块可能没重载（LSPosed 重启作用域/重启模拟器），或乐跑没启动"
        bridge.note(context, timeout)
        onStep(timeout, true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            onStep(context.getString(R.string.lepao_social_failed), true)
        } finally {
            socialImportsInFlight.remove("$provider:$oid")
            clearWxLoginConfig()
        }
    }
}

/** 导入流程收尾后清掉下发凭据，避免乐跑每次启动都被去重分支刷屏 */
private fun MainViewModel.clearWxLoginConfig() {
    viewModelScope.launch { locationRepository.patchConfig { it.remove("lepao_wx_login") } }
}

/** 人脸图不在 buildConfig 的旧文件里也要能立即下发，所以 patch 时显式写两个字段 */
internal fun MainViewModel.pushFacePhotoConfig() {
    viewModelScope.launch {
        locationRepository.patchConfig { json ->
            json.put("face_photo_enabled", settingsRepository.lepaoFacePhotoEnabled)
            json.put(
                "face_photo_b64",
                if (settingsRepository.lepaoFacePhotoEnabled) settingsRepository.lepaoFacePhotoBase64 else ""
            )
        }
    }
}

/**
 * 乐跑多账户切换器：
 * - backupLepaoAccount(name)：请模块在乐跑启动时机把当前账户 SP「setting」+ MMKV 全量
 *   dump 到 /data/data/com.lptiyu.tanke/files/ls_backup_<name>.json，强停乐跑触发重走，
 *   consumeLepaoDump 再 root 读回存档。
 * - switchLepaoAccount(name)：把该账户快照设为激活，重发配置 + 强停乐跑；
 *   下次乐跑启动模块写回 SP+MMKV，直接就是该账号登录态 + 跑步数据。
 */
fun MainViewModel.backupLepaoAccount(name: String) {
    settingsRepository.lepaoDumpRequest = name
    viewModelScope.launch {
        locationRepository.patchConfig { /* 下发 lepao_dump_request */ }
        try {
            locationRepository.forceStopApps(listOf(LEPAO_PKG))
        } catch (_: Exception) {
        }
    }
}

/** 读回 dump 文件并存入账户存档（sp+mmkv 全量快照 + 提取出的登录态 session） */
fun MainViewModel.consumeLepaoDump(name: String): Boolean {
    val proc = try {
        ProcessBuilder("su", "-c",
            "cat /data/data/$LEPAO_PKG/files/ls_backup_$name.json 2>/dev/null; " +
                "rm -f /data/data/$LEPAO_PKG/files/ls_backup_$name.json")
            .redirectErrorStream(true).start()
    } catch (_: Exception) {
        return false
    }
    val text = proc.inputStream.bufferedReader().use { it.readText() }
    if (proc.waitFor() != 0 || text.isBlank()) return false
    val dump = try {
        JSONObject(text)
    } catch (_: Exception) {
        return false
    }
    val sp = dump.optJSONObject("sp") ?: JSONObject()
    val mmkv = dump.optJSONObject("mmkv") ?: JSONObject()
    val session = JSONObject()
    for (k in listOf("user_id", "token", "refresh_token", "refresh_expire", "user_phone", "user_nickname", "role", "admin")) {
        val fromSp = sp.optJSONObject(k)?.optString("v", "") ?: ""
        val fromMmkv = mmkv.optJSONObject(k)?.optString("s", "") ?: ""
        val v = if (fromSp.isNotEmpty()) fromSp else fromMmkv
        if (v.isNotEmpty()) session.put(k, v)
    }
    val existing = settingsRepository.getLepaoAccount(name) ?: JSONObject()
    val account = JSONObject().apply {
        put("name", name)
        put("note", existing.optString("note", ""))
        put("created_at", existing.optLong("created_at", 0L).takeIf { it > 0 } ?: System.currentTimeMillis())
        put("session", session)
        put("snapshot", JSONObject().put("sp", sp).put("mmkv", mmkv))
    }
    settingsRepository.saveLepaoAccount(name, account)
    settingsRepository.lepaoDumpRequest = ""
    settingsRepository.lepaoActiveAccount = name
    viewModelScope.launch {
        locationRepository.patchConfig { /* 清 dump 请求 + 下发该账户快照 */ }
    }
    return true
}

fun MainViewModel.switchLepaoAccount(name: String) {
    switchLepaoAccount(name, onDone = { })
}

fun MainViewModel.deleteLepaoAccount(name: String) {
    settingsRepository.deleteLepaoAccount(name)
    if (settingsRepository.lepaoActiveAccount == name) {
        settingsRepository.lepaoActiveAccount = ""
        viewModelScope.launch {
            locationRepository.patchConfig { /* 清激活快照 */ }
        }
    }
}

fun MainViewModel.lepaoAccountNames(): List<String> =
    settingsRepository.getLepaoAccounts().keys().asSequence().toList().sorted()

fun MainViewModel.lepaoActiveAccountName(): String = settingsRepository.lepaoActiveAccount

/**
 * 读回模块捕获的微信登录凭证（乐跑内扫码/小程序/验证码任一登录成功后模块自动落盘的
 * ls_token_capture.json）：存为该名账户的 session + 当前注入会话，之后不用手机再扫。
 * 返回是否读到有效凭证。
 */
fun MainViewModel.consumeLepaoTokenCapture(name: String): Boolean {
    val proc = try {
        ProcessBuilder("su", "-c",
            "cat /data/data/$LEPAO_PKG/files/ls_token_capture.json 2>/dev/null")
            .redirectErrorStream(true).start()
    } catch (_: Exception) {
        return false
    }
    val text = proc.inputStream.bufferedReader().use { it.readText() }
    if (proc.waitFor() != 0 || text.isBlank()) return false
    val cap = try {
        JSONObject(text)
    } catch (_: Exception) {
        return false
    }
    val session = JSONObject()
    for (k in listOf("uid", "access_token", "refresh_token", "refresh_expire", "phone")) {
        cap.optString(k, "").takeIf { it.isNotEmpty() }
            ?.let { session.put(if (k == "uid") "user_id" else k, it) }
    }
    if (session.length() == 0) return false
    val existing = settingsRepository.getLepaoAccount(name) ?: JSONObject()
    val account = JSONObject().apply {
        put("name", name)
        put("note", existing.optString("note", ""))
        put("created_at", existing.optLong("created_at", 0L).takeIf { it > 0 } ?: System.currentTimeMillis())
        put("session", session)
        put("captured", cap)
    }
    settingsRepository.saveLepaoAccount(name, account)
    // 同步设为当前注入会话，重启乐跑即以此凭证登录
    settingsRepository.lepaoSession = session.toString()
    settingsRepository.lepaoActiveAccount = name
    viewModelScope.launch {
        locationRepository.patchConfig { /* 下发 lepao_session + 激活快照 */ }
    }
    return true
}

/**
 * 一键开跑：切到目标账户（快照写回 + 强停乐跑）→ 若账户存了坐标快照先写回界面 →
 * 直接 startSpoofing（仍模式）。乐跑下一次启动就是该账号登录态 + 数据，跑步定位已开。
 */
fun MainViewModel.oneClickRun(name: String) {
    val account = settingsRepository.getLepaoAccount(name)
    if (account != null) {
        // 坐标/hook组快照在「多用户配置切换器」的同名 profile 里，有则先套用
        settingsRepository.getLepaoProfile(name)?.let { p ->
            val lat = p.optString("lat", "")
            val lng = p.optString("lng", "")
            if (lat.isNotEmpty() && lng.isNotEmpty()) {
                _uiState.update { it.copy(latitudeInput = lat, longitudeInput = lng) }
            }
            p.optJSONObject("groups")?.let { groups ->
                groups.keys().forEach { k ->
                    settingsRepository.setHookGroupEnabled(LEPAO_PKG, k, groups.optBoolean(k, true))
                }
            }
        }
    }
    switchLepaoAccount(name)
    startSpoofing()
}

internal fun MainViewModel.prepareRouteSimulationOptions(onReady: () -> Unit) {
    val point = _uiState.value.routePoints.firstOrNull() ?: return
    viewModelScope.launch {
        _uiState.update { it.copy(latitudeInput = point.lat.toString(), longitudeInput = point.lng.toString()) }
        evaluateMockCapabilitiesSuspend(point.lat, point.lng)
        // 不再用 `mockWifi || canMockWifi` 强行打开：用户关掉 Wi-Fi 伪造是明确意图，
        // 这里若因"本地恰好有数据"而重新打开，钩子端会把真实网络改写成 DISCONNECTED。
        settingsRepository.mockWifi = _uiState.value.mockWifi
        settingsRepository.mockCell = _uiState.value.mockCell
        settingsRepository.mockBluetooth = _uiState.value.mockBluetooth
        onReady()
    }
}
