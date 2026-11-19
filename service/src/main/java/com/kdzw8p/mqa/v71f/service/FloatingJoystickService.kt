package com.kdzw8p.mqa.v71f.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.ElectricBike
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kdzw8p.mqa.v71f.data.motion.MotionController
import com.kdzw8p.mqa.v71f.data.repository.LocationRepository
import com.kdzw8p.mqa.v71f.utils.ConfigManager
import com.kdzw8p.mqa.v71f.utils.NetworkBlockManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.android.inject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 宿主跑步页每秒广播的实时跑步状态 */
private const val ACTION_LEPAO_RUN_STATUS = "com.kdzw8p.mqa.v71f.RUN_STATUS"

/**
 * 悬浮摇杆：浮在其他 App 之上操作模拟位置，所有操作都转给进程级 [MotionController]，
 * 和 App 里的界面共用同一份状态。可以收起成小圆点，减少对下层 App 的遮挡。
 * 只在"摇杆手动控制"的路线模拟中出现，没有关闭按钮；路线停止（且录制的路线已保存或丢弃）后自动关闭。
 */
class FloatingJoystickService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val motionController: MotionController by inject()
    private val locationRepository: LocationRepository by inject()
    private val configManager: ConfigManager by inject()
    private val overlaySettings by lazy { com.kdzw8p.mqa.v71f.utils.SettingsManager(this) }
    private val networkBlockManager: NetworkBlockManager by inject()

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var composeView: ComposeView? = null

    /** 悬浮窗里「断网」按钮的实时状态；初值从持久化状态恢复，重启后不会显示成已关 */
    private var networkBlocked by mutableStateOf(false)

    /** 宿主跑步页每秒广播的实时状态；超过 6 秒没更新视为不在跑，避免悬浮窗显示陈旧数据 */
    private var lepaoRunStatus by mutableStateOf<LepaoRunStatus?>(null)
    private var runStatusAt = 0L
    private var runStatusRegistered = false

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        isShowing = true
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        networkBlocked = networkBlockManager.isEnabled
        showFloatingWindow()
        registerRunStatusReceiver()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        lifecycleScope.launch {
            motionController.state.collect {
                if (!it.hasRoute && !it.hasPendingRecording) stopSelf()
            }
        }
    }

    private fun registerRunStatusReceiver() {
        if (runStatusRegistered) return
        runStatusRegistered = true
        val filter = android.content.IntentFilter(ACTION_LEPAO_RUN_STATUS)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action != ACTION_LEPAO_RUN_STATUS) return
                lepaoRunStatus = LepaoRunStatus(
                    km = intent.getDoubleExtra("km", 0.0),
                    speed = intent.getDoubleExtra("speed", 0.0),
                    elapsed = intent.getStringExtra("elapsed").orEmpty(),
                    paused = intent.getBooleanExtra("paused", false)
                )
                runStatusAt = System.currentTimeMillis()
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                val at = runStatusAt
                if (at != 0L && System.currentTimeMillis() - at > 6000) {
                    lepaoRunStatus = null
                    runStatusAt = 0L
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingWindow() {
        val density = resources.displayMetrics.density
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不用 FLAG_SECURE：那样截图里会留一块黑块反而显眼。
            // 隐形模式（CompactJoystickOverlay）在静止后把窗口 alpha 降到 0——不绘制、不参与合成，
            // 截图/录屏里完全看不到；触摸后立即显形。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
            y = (160 * density).toInt()
        }

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingJoystickService)
            setViewTreeViewModelStoreOwner(this@FloatingJoystickService)
            setViewTreeSavedStateRegistryOwner(this@FloatingJoystickService)
            setContent {
                MaterialTheme {
                    val state by motionController.state.collectAsState()
                    val initialCadence = remember { configManager.stepCadenceSpm }
                    CompactJoystickOverlay(
                        state = state,
                        initialCadence = initialCadence,
                        settings = overlaySettings,
                        networkBlocked = networkBlocked,
                        runStatus = lepaoRunStatus,
                        onToggleNetworkBlock = {
                            lifecycleScope.launch {
                                networkBlocked = networkBlockManager.toggle(NETWORK_BLOCK_TARGET)
                            }
                        },
                        onMoveWindow = { dx, dy -> moveWindow(dx, dy) },
                        onJoystick = { bearing, intensity -> motionController.joystick(bearing, intensity) },
                        onPause = { motionController.pause() },
                        onResume = { motionController.resume() },
                        onPreset = { motionController.setSpeedPreset(it) },
                        onStartRecording = { motionController.startRecording() },
                        onStopRecording = { motionController.stopRecording() },
                        onSaveRecording = { name -> saveRecording(name) },
                        onDiscardRecording = { motionController.discardRecording() },
                        onNeedKeyboard = { setFocusable(it) },
                        onCadenceStep = { spm -> lifecycleScope.launch { locationRepository.updateStepCadence(spm) } },
                        onAutoStopKm = { value ->
                            overlaySettings.lepaoAutoStopKm = value
                            // 只触发一次配置刷新：putPersistentSettings 会从 settings 重新写入
                            // lepao_auto_stop_km 并派生 target_distance_m，无需在此直接 put（会被覆盖，
                            // 且若调整覆盖顺序就成了新的双源失配点）。
                            lifecycleScope.launch {
                                configManager.patchConfig { }
                            }
                        }
                    )
                }
            }
        }
        windowManager.addView(composeView, params)
    }

    private fun moveWindow(dx: Float, dy: Float) {
        val screen = resources.displayMetrics
        val visibleEdge = (24 * screen.density).roundToInt()
        params.x = (params.x + dx.roundToInt()).coerceIn(0, (screen.widthPixels - visibleEdge).coerceAtLeast(0))
        params.y = (params.y + dy.roundToInt()).coerceIn(0, (screen.heightPixels - visibleEdge).coerceAtLeast(0))
        composeView?.let { windowManager.updateViewLayout(it, params) }
    }

    /** 默认不抢焦点，以免挡住下层 App 的输入；只有给录制的路线命名时才需要弹出键盘 */
    private fun setFocusable(focusable: Boolean) {
        val flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (flags == params.flags) return
        params.flags = flags
        composeView?.let { windowManager.updateViewLayout(it, params) }
    }

    private fun saveRecording(name: String) {
        lifecycleScope.launch {
            val saved = motionController.saveRecording(name)
            if (saved) Toast.makeText(this@FloatingJoystickService, R.string.fj_saved_toast, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        isShowing = false
        // 悬浮窗没了就等于断网按钮没了，不能留一个关不掉的规则在内核里。
        // 用独立进程级 IO 作用域，不能阻塞主线程，也不能跟随 service 的 lifecycleScope 一起被取消
        CoroutineScope(Dispatchers.IO).launch { networkBlockManager.clearAll() }
        motionController.joystick(0.0, 0f)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        composeView?.let { windowManager.removeView(it) }
        composeView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** 断网按钮针对的目标 App：乐跑 */
        private const val NETWORK_BLOCK_TARGET = "com.lptiyu.tanke"

        @Volatile
        var isShowing = false
            private set
    }
}

/** 与 App 主题（AppColors）一致的配色，深浅色跟随系统 */
internal data class Palette(
    val card: Color,
    val border: Color,
    val variant: Color,
    val segment: Color,
    val text: Color,
    val textSecondary: Color,
    val red: Color
)

internal val LightPalette = Palette(
    card = Color(0xFFFFFFFF),
    border = Color(0xFFD0D7DE),
    variant = Color(0xFFEEF2F6),
    segment = Color(0xFFFFFFFF),
    text = Color(0xFF24292F),
    textSecondary = Color(0xFF57606A),
    red = Color(0xFFCF222E)
)

internal val DarkPalette = Palette(
    card = Color(0xFF1C2333),
    border = Color(0xFF30363D),
    variant = Color(0xFF21262D),
    segment = Color(0xFF30363D),
    text = Color(0xFFE6EDF3),
    textSecondary = Color(0xFF8B949E),
    red = Color(0xFFF85149)
)

internal val AccentBlue = Color(0xFF388BFD)
internal val AccentGreen = Color(0xFF2EA043)
internal val AccentOrange = Color(0xFFD29922)

@Composable
internal fun Joystick(enabled: Boolean, palette: Palette, onJoystick: (Double, Float) -> Unit) {
    val baseSize = 56.dp
    val thumbSize = 22.dp
    val maxRadiusPx = with(LocalDensity.current) { ((baseSize - thumbSize) / 2).toPx() }
    var thumb by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(enabled) {
        if (!enabled) thumb = Offset.Zero
    }

    Box(
        modifier = Modifier
            .size(baseSize)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(palette.variant)
            .border(1.dp, palette.border.copy(alpha = 0.5f), CircleShape)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragEnd = {
                        thumb = Offset.Zero
                        onJoystick(0.0, 0f)
                    },
                    onDragCancel = {
                        thumb = Offset.Zero
                        onJoystick(0.0, 0f)
                    }
                ) { change, drag ->
                    change.consume()
                    val raw = thumb + drag
                    val dist = sqrt(raw.x * raw.x + raw.y * raw.y)
                    thumb = if (dist <= maxRadiusPx) raw else raw * (maxRadiusPx / dist)
                    val angle = atan2(thumb.y.toDouble(), thumb.x.toDouble())
                    val intensity = (sqrt(thumb.x * thumb.x + thumb.y * thumb.y) / maxRadiusPx).coerceIn(0f, 1f)
                    onJoystick((Math.toDegrees(angle) + 90 + 360) % 360, intensity)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        listOf(Alignment.TopCenter, Alignment.BottomCenter, Alignment.CenterStart, Alignment.CenterEnd).forEach { align ->
            Box(
                modifier = Modifier
                    .align(align)
                    .padding(6.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(palette.textSecondary.copy(alpha = 0.45f))
            )
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(thumb.x.roundToInt(), thumb.y.roundToInt()) }
                .size(thumbSize)
                .shadow(if (enabled) 6.dp else 0.dp, CircleShape)
                .clip(CircleShape)
                .background(AccentBlue),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.85f))
            )
        }
    }
}

/** 操作按钮并排在摇杆侧面，减少面板高度。 */
@Composable
internal fun ActionButtons(
    state: MotionController.State,
    palette: Palette,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.hasRoute) {
            when {
                state.paused -> RoundButton(
                    Icons.Rounded.PlayArrow, stringResource(R.string.fj_resume),
                    container = AccentGreen, content = Color.White,
                    enabled = state.active && !state.returning, onClick = onResume
                )
                else -> RoundButton(
                    Icons.Rounded.Pause, stringResource(R.string.fj_pause),
                    container = AccentOrange, content = Color.White,
                    enabled = state.active && !state.returning, onClick = onPause
                )
            }
        }
        if (state.recording) {
            RoundButton(
                Icons.Rounded.Stop, stringResource(R.string.fj_stop_record),
                container = palette.red, content = Color.White, enabled = true, onClick = onStopRecording
            )
        } else {
            RoundButton(
                Icons.Rounded.FiberManualRecord, stringResource(R.string.fj_record),
                container = palette.red.copy(alpha = 0.12f), content = palette.red,
                enabled = state.active, onClick = onStartRecording
            )
        }
    }
}

@Composable
internal fun presetLabel(name: String): String = stringResource(
    when (name) {
        "WALKING" -> R.string.fj_preset_walking
        "RUNNING" -> R.string.fj_preset_running
        "CYCLING" -> R.string.fj_preset_cycling
        "EBIKE" -> R.string.fj_preset_ebike
        "DRIVING" -> R.string.fj_preset_driving
        else -> R.string.fj_preset_custom
    }
)

@Composable
private fun RoundButton(
    icon: ImageVector,
    description: String,
    container: Color,
    content: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = content, modifier = Modifier.size(18.dp))
    }
}

/** 保存录制：字段宽度固定，避免弹键盘时窗口宽度跳动 */
@Composable
internal fun SaveRecordingForm(palette: Palette, onSave: (String) -> Unit, onDiscard: () -> Unit, onNeedKeyboard: (Boolean) -> Unit) {
    val defaultName = stringResource(
        R.string.fj_default_route_name,
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
    )
    var name by remember { mutableStateOf(defaultName) }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        placeholder = { Text(stringResource(R.string.fj_route_name_hint)) },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = palette.text,
            unfocusedTextColor = palette.text,
            focusedBorderColor = AccentBlue,
            unfocusedBorderColor = palette.border,
            cursorColor = AccentBlue
        ),
        modifier = Modifier.fillMaxWidth().onFocusChanged { onNeedKeyboard(it.hasFocus) }
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(
            Icons.Rounded.Close, stringResource(R.string.fj_discard),
            container = palette.variant, content = palette.textSecondary, enabled = true, onClick = onDiscard
        )
        RoundButton(
            Icons.Rounded.Check, stringResource(R.string.fj_save),
            container = AccentBlue, content = Color.White, enabled = name.isNotBlank(), onClick = { onSave(name) }
        )
    }
}
