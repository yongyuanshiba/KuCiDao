package com.vivo.islandlyric.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow as BackdropShadow // fix13 补修：与 compose 的 ui.graphics.Shadow 同名，浮层阴影走别名
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.vivo.islandlyric.BuildConfig
import com.vivo.islandlyric.Const
import com.vivo.islandlyric.util.PrefsBackupCodec
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 原生 Compose 液态玻璃设置页（v1.3.6，基于 Kyant0/AndroidLiquidGlass io.github.kyant0:backdrop）：
 * - 严格作用域隔离：Compose 与 Backdrop 仅存在于本 Activity（模块独立进程），:island 宿主进程零加载；
 * - 严格耗电与流畅护栏：零无限循环动画、静止零重绘、onPause/onStop 立即收束全部弹簧动效；
 * - 完整承接全部 20 项热生效配置、实时横屏字号预览、SAF JSON 导入/导出、宿主镜像恢复与诊断日志导出。
 */
class SettingsActivity : ComponentActivity() {

    private var worldPrefs: SharedPreferences? = null
    private val uiState = SettingsUiState()
    private val pauseCallbacks = mutableListOf<() -> Unit>()

    private val uiHandler = Handler(Looper.getMainLooper())
    private var mirrorPushRunnable: Runnable? = null
    private var mirrorReplyReceiver: BroadcastReceiver? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        configureEdgeToEdgeWindow()

        syncStateFromPrefs()
        bindRemoteXposedService()

        setContent {
            CompositionLocalProvider(LocalPauseRegistry provides pauseCallbacks) {
                SettingsRootScreen(
                    state = uiState,
                    callbacks = SettingsCallbacks(
                        onMasterChanged = { checked ->
                            uiState.master = checked
                            savePrefs { putBoolean(Const.PREF_MASTER_SWITCH, checked) }
                        },
                        onHaloEnabledChanged = { checked ->
                            uiState.haloEnabled = checked
                            savePrefs { putBoolean(Const.PREF_HALO_ENABLED, checked) }
                        },
                        onHaloStyleChanged = { style ->
                            val v = style.coerceIn(Const.GLOW_STYLE_MIN, Const.GLOW_STYLE_MAX)
                            if (v != uiState.haloStyle) {
                                uiState.haloStyle = v
                                savePrefs { putInt(Const.PREF_GLOW_STYLE, v) }
                            }
                        },
                        onHaloIntensityChanged = { intensity ->
                            val v = intensity.coerceIn(Const.GLOW_INTENSITY_MIN, Const.GLOW_INTENSITY_MAX)
                            if (v != uiState.haloIntensity) {
                                uiState.haloIntensity = v
                                savePrefs { putInt(Const.PREF_GLOW_INTENSITY, v) }
                            }
                        },
                        onHaloPeriodMsChanged = { periodMs ->
                            val v = when (periodMs) {
                                Const.GLOW_PERIOD_MS_FAST, Const.GLOW_PERIOD_MS_SLOW -> periodMs
                                else -> Const.GLOW_PERIOD_MS_DEFAULT
                            }
                            if (v != uiState.haloPeriodMs) {
                                uiState.haloPeriodMs = v
                                savePrefs { putInt(Const.PREF_GLOW_PERIOD_MS, v) }
                            }
                        },
                        onHaloOuterEnabledChanged = { checked ->
                            uiState.haloOuterEnabled = checked
                            savePrefs { putBoolean(Const.PREF_GLOW_OUTER_ENABLED, checked) }
                        },
                        onHaloOrbitEnabledChanged = { checked ->
                            uiState.haloOrbitEnabled = checked
                            savePrefs { putBoolean(Const.PREF_HALO_ORBIT_ENABLED, checked) }
                        },
                        onHaloOuterLevelChanged = { level ->
                            val v = level.coerceIn(Const.HALO_OUTER_LEVEL_MIN, Const.HALO_OUTER_LEVEL_MAX)
                            if (v != uiState.haloOuterLevel) {
                                uiState.haloOuterLevel = v
                                savePrefs { putInt(Const.PREF_HALO_OUTER_LEVEL, v) }
                            }
                        },
                        onKaraokeChanged = { checked ->
                            uiState.karaoke = checked
                            savePrefs { putBoolean(Const.PREF_KARAOKE_ENABLED, checked) }
                        },
                        onLineDissolveChanged = { checked ->
                            uiState.lineDissolve = checked
                            savePrefs { putBoolean(Const.PREF_LINE_DISSOLVE_ENABLED, checked) }
                        },
                        onLyricStyleChanged = { style ->
                            val v = style.coerceIn(Const.LYRIC_STYLE_MIN, Const.LYRIC_STYLE_MAX)
                            if (v != uiState.lyricStyle) {
                                uiState.lyricStyle = v
                                savePrefs { putInt(Const.PREF_LYRIC_STYLE, v) }
                            }
                        },
                        onLineTransitionModeChanged = { mode ->
                            val v = mode.coerceIn(Const.LINE_TRANSITION_MODE_MIN, Const.LINE_TRANSITION_MODE_MAX)
                            if (v != uiState.lineTransitionMode) {
                                uiState.lineTransitionMode = v
                                savePrefs { putInt(Const.PREF_LINE_TRANSITION_MODE, v) }
                            }
                        },
                        onLandscapeModeChanged = { mode ->
                            val v = mode.coerceIn(Const.LANDSCAPE_MODE_MIN, Const.LANDSCAPE_MODE_MAX)
                            if (v != uiState.landscapeMode) {
                                uiState.landscapeMode = v
                                savePrefs { putInt(Const.PREF_LANDSCAPE_MODE, v) }
                            }
                        },
                        onInterludeDotsEnabledChanged = { checked ->
                            uiState.interludeDotsEnabled = checked
                            savePrefs { putBoolean(Const.PREF_INTERLUDE_DOTS_ENABLED, checked) }
                        },
                        onDualLineLyricChanged = { checked ->
                            uiState.dualLineLyric = checked
                            savePrefs { putBoolean(Const.PREF_DUAL_LINE_LYRIC_ENABLED, checked) }
                        },
                        onPersistentWaveChanged = { checked ->
                            uiState.persistentWave = checked
                            savePrefs { putBoolean(Const.PREF_PERSISTENT_WAVE_ENABLED, checked) }
                        },
                        onShimmerLevelChanged = { level ->
                            val v = level.coerceIn(Const.SHIMMER_LEVEL_MIN, Const.SHIMMER_LEVEL_MAX)
                            // v1.4.5-fix12（Quick 审查 S3）：滑块拖动逐帧回调，值未变不重复写 prefs
                            if (v != uiState.shimmerLevel) {
                                uiState.shimmerLevel = v
                                savePrefs { putInt(Const.PREF_SHIMMER_LEVEL, v) }
                            }
                        },
                        onShimmerStyleChanged = { style ->
                            val v = style.coerceIn(Const.SHIMMER_STYLE_MIN, Const.SHIMMER_STYLE_MAX)
                            if (v != uiState.shimmerStyle) {
                                uiState.shimmerStyle = v
                                savePrefs { putInt(Const.PREF_SHIMMER_STYLE, v) }
                            }
                        },
                        onShimmerCountdownEnabledChanged = { checked ->
                            uiState.shimmerCountdownEnabled = checked
                            savePrefs { putBoolean(Const.PREF_SHIMMER_COUNTDOWN_ENABLED, checked) }
                        },
                        onIslandShimmerEnabledChanged = { checked ->
                            uiState.islandShimmerEnabled = checked
                            savePrefs { putBoolean(Const.PREF_ISLAND_SHIMMER_ENABLED, checked) }
                        },
                        onDiagLogEnabledChanged = { checked ->
                            uiState.diagLogEnabled = checked
                            savePrefs { putBoolean(Const.PREF_DIAG_LOG_ENABLED, checked) }
                        },
                        onClearDiagLog = { clearDiagLog() },
                        onDiagDetailedChanged = { checked ->
                            uiState.diagDetailed = checked
                            savePrefs { putBoolean(Const.PREF_DIAG_DETAILED, checked) }
                        },
                        onDualLineSubSizeChanged = { spVal ->
                            val clamped = spVal.coerceIn(Const.DUAL_LINE_SUB_MIN_SP, Const.DUAL_LINE_SUB_MAX_SP)
                            uiState.dualLineSubSizeSp = clamped
                            savePrefs { putFloat(Const.PREF_DUAL_LINE_SUB_TEXT_SIZE_SP, clamped) }
                        },
                        onLyricCenterChanged = { checked ->
                            uiState.lyricCenter = checked
                            savePrefs { putBoolean(Const.PREF_LYRIC_CENTER_ALIGN, checked) }
                        },
                        onOnlineTimelineChanged = { checked ->
                            uiState.onlineTimeline = checked
                            savePrefs { putBoolean(Const.PREF_ONLINE_TIMELINE_ENABLED, checked) }
                        },
                        onLeftCenterChanged = { checked ->
                            uiState.leftCenter = checked
                            savePrefs { putBoolean(Const.PREF_LEFT_CENTER_ALIGN, checked) }
                        },
                        onScrollAutoChanged = { checked ->
                            uiState.scrollAuto = checked
                            savePrefs { putBoolean(Const.PREF_SCROLL_AUTO, checked) }
                        },
                        onScrollSpeedChanged = { factor ->
                            val v = snapFloat(factor, Const.SCROLL_SPEED_FACTOR_MIN, Const.SCROLL_SPEED_FACTOR_MAX, SPEED_STEP)
                            uiState.scrollSpeedFactor = v
                            savePrefs { putFloat(Const.PREF_SCROLL_SPEED_FACTOR, v) }
                        },
                        onTextSizeSpChanged = { sp ->
                            val v = snapFloat(sp, Const.RIGHT_LYRIC_TEXT_SIZE_MIN_SP, Const.RIGHT_LYRIC_TEXT_SIZE_MAX_SP, TEXT_SIZE_STEP)
                            uiState.textSizeSp = v
                            savePrefs { putFloat(Const.PREF_LYRIC_TEXT_SIZE_SP, v) }
                        },
                        onLyricWidthDpChanged = { dpVal ->
                            val v = dpVal.coerceIn(Const.WIDE_LYRIC_WIDTH_DP_MIN, Const.WIDE_LYRIC_WIDTH_DP_MAX)
                            uiState.lyricWidthDp = v
                            savePrefs { putInt(Const.PREF_WIDE_LYRIC_WIDTH_DP, v) }
                        },
                        onLyricLeftOffsetChanged = { dpVal ->
                            val v = snapFloat(dpVal, Const.LYRIC_LEFT_OFFSET_MIN_DP, Const.LYRIC_LEFT_OFFSET_MAX_DP, FINE_DP_STEP)
                            uiState.lyricLeftOffsetDp = v
                            savePrefs { putFloat(Const.PREF_LYRIC_LEFT_OFFSET_DP, v) }
                        },
                        onRightInsetChanged = { dpVal ->
                            val v = snapFloat(dpVal, Const.RIGHT_LYRIC_RIGHT_INSET_MIN_DP, Const.RIGHT_LYRIC_RIGHT_INSET_MAX_DP, FINE_DP_STEP)
                            uiState.rightLyricRightInsetDp = v
                            savePrefs { putFloat(Const.PREF_RIGHT_LYRIC_RIGHT_INSET_DP, v) }
                        },
                        onAlbumLeftPadChanged = { pxVal ->
                            val v = pxVal.coerceIn(Const.ALBUM_LEFT_PADDING_MIN_PX, Const.ALBUM_LEFT_PADDING_MAX_PX)
                            uiState.albumLeftPadPx = v
                            savePrefs { putInt(Const.PREF_ALBUM_LEFT_PADDING_PX, v) }
                        },
                        onLeftInfoOffsetChanged = { dpVal ->
                            val v = snapFloat(dpVal, Const.LEFT_INFO_OFFSET_MIN_DP, Const.LEFT_INFO_OFFSET_MAX_DP, FINE_DP_STEP)
                            uiState.leftInfoOffsetDp = v
                            savePrefs { putFloat(Const.PREF_LEFT_INFO_OFFSET_DP, v) }
                        },
                        onLeftInfoMarginStartChanged = { dpVal ->
                            val v = snapFloat(dpVal, Const.LEFT_INFO_MARGIN_START_MIN_DP, Const.LEFT_INFO_MARGIN_START_MAX_DP, FINE_DP_STEP)
                            uiState.leftInfoMarginStartDp = v
                            savePrefs { putFloat(Const.PREF_LEFT_INFO_MARGIN_START_DP, v) }
                        },
                        onLeftInfoRightInsetChanged = { dpVal ->
                            val v = snapFloat(dpVal, Const.LEFT_INFO_RIGHT_INSET_MIN_DP, Const.LEFT_INFO_RIGHT_INSET_MAX_DP, FINE_DP_STEP)
                            uiState.leftInfoRightInsetDp = v
                            savePrefs { putFloat(Const.PREF_LEFT_INFO_RIGHT_INSET_DP, v) }
                        },
                        onLandscapeLyricChanged = { checked ->
                            uiState.landscapeLyric = checked
                            savePrefs(pushDelayMs = 80L) { putBoolean(Const.PREF_LANDSCAPE_LYRIC_ENABLED, checked) }
                        },
                        onLandscapeTextSizeChanged = { sp ->
                            val v = snapFloat(sp, Const.LANDSCAPE_LYRIC_TEXT_SIZE_MIN_SP, Const.LANDSCAPE_LYRIC_TEXT_SIZE_MAX_SP, TEXT_SIZE_STEP)
                            uiState.landscapeTextSizeSp = v
                            savePrefs(pushDelayMs = 80L) { putFloat(Const.PREF_LANDSCAPE_LYRIC_TEXT_SIZE_SP, v) }
                        },
                        onLandscapeTopMarginChanged = { dpVal ->
                            val v = dpVal.roundToInt().toFloat()
                                .coerceIn(Const.LANDSCAPE_LYRIC_TOP_MARGIN_MIN_DP, Const.LANDSCAPE_LYRIC_TOP_MARGIN_MAX_DP)
                            uiState.landscapeTopMarginDp = v
                            savePrefs(pushDelayMs = 80L) { putFloat(Const.PREF_LANDSCAPE_LYRIC_TOP_MARGIN_DP, v) }
                        },
                        onLandscapeOffsetXChanged = { dpVal ->
                            val v = dpVal.roundToInt().toFloat()
                                .coerceIn(Const.LANDSCAPE_LYRIC_OFFSET_X_MIN_DP, Const.LANDSCAPE_LYRIC_OFFSET_X_MAX_DP)
                            uiState.landscapeOffsetXDp = v
                            savePrefs(pushDelayMs = 80L) { putFloat(Const.PREF_LANDSCAPE_LYRIC_OFFSET_X_DP, v) }
                        },
                        onExportConfig = { exportConfigJson() },
                        onImportConfig = { importConfigJson() },
                        onExportDiag = { exportDiagLog() },
                        onResetDefaults = {
                            if (!uiState.moduleActive) {
                                showInactiveToast()
                            } else {
                                resetToDefaults()
                            }
                        }
                    )
                )
            }
        }
    }

    override fun onPause() {
        cancelAllUiAnimations()
        super.onPause()
    }

    override fun onStop() {
        cancelAllUiAnimations()
        super.onStop()
    }

    override fun onDestroy() {
        cancelAllUiAnimations()
        mirrorPushRunnable?.let { uiHandler.removeCallbacks(it) }
        mirrorPushRunnable = null
        unregisterMirrorReplyReceiver()
        super.onDestroy()
    }

    private fun bindRemoteXposedService() {
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    uiHandler.post {
                        if (isFinishing || isDestroyed) return@post
                        val sp = runCatching {
                            val props = service.frameworkProperties
                            if ((props and XposedService.PROP_CAP_REMOTE) == 0L) null
                            else service.getRemotePreferences(Const.PREF_FILE_NAME)
                        }.getOrNull()
                        worldPrefs = sp
                        uiState.moduleActive = sp != null
                        syncStateFromPrefs()
                        if (sp != null) {
                            if (PrefsBackupCodec.isUnconfiguredDefaultState(sp)) {
                                requestHostMirrorAutoRestore()
                            } else {
                                scheduleHostMirrorPush(immediate = true)
                            }
                        }
                    }
                }

                override fun onServiceDied(service: XposedService) {
                    uiHandler.post {
                        if (isFinishing || isDestroyed) return@post
                        worldPrefs = null
                        uiState.moduleActive = false
                    }
                }
            })
        }
    }

    private fun cancelAllUiAnimations() {
        for (cb in pauseCallbacks.toList()) {
            runCatching { cb() }
        }
    }

    @Suppress("DEPRECATION")
    private fun configureEdgeToEdgeWindow() {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.decorView.post {
                val ctrl = window.insetsController ?: return@post
                val lightMask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                ctrl.setSystemBarsAppearance(if (isDark) 0 else lightMask, lightMask)
            }
        }
    }

    private fun syncStateFromPrefs() {
        val sp = worldPrefs
        uiState.master = sp?.getBoolean(Const.PREF_MASTER_SWITCH, true) ?: true
        uiState.haloEnabled = sp?.getBoolean(Const.PREF_HALO_ENABLED, Const.HALO_ENABLED_DEFAULT)
            ?: Const.HALO_ENABLED_DEFAULT
        uiState.haloStyle = (sp?.getInt(Const.PREF_GLOW_STYLE, Const.GLOW_STYLE_DEFAULT)
            ?: Const.GLOW_STYLE_DEFAULT)
            .coerceIn(Const.GLOW_STYLE_MIN, Const.GLOW_STYLE_MAX)
        uiState.haloIntensity = (sp?.getInt(Const.PREF_GLOW_INTENSITY, Const.GLOW_INTENSITY_DEFAULT)
            ?: Const.GLOW_INTENSITY_DEFAULT)
            .coerceIn(Const.GLOW_INTENSITY_MIN, Const.GLOW_INTENSITY_MAX)
        uiState.haloPeriodMs = when (sp?.getInt(Const.PREF_GLOW_PERIOD_MS, Const.GLOW_PERIOD_MS_DEFAULT)) {
            Const.GLOW_PERIOD_MS_FAST -> Const.GLOW_PERIOD_MS_FAST
            Const.GLOW_PERIOD_MS_SLOW -> Const.GLOW_PERIOD_MS_SLOW
            else -> Const.GLOW_PERIOD_MS_DEFAULT
        }
        uiState.haloOuterEnabled = sp?.getBoolean(Const.PREF_GLOW_OUTER_ENABLED, Const.GLOW_OUTER_ENABLED_DEFAULT)
            ?: Const.GLOW_OUTER_ENABLED_DEFAULT
        uiState.haloOrbitEnabled = sp?.getBoolean(Const.PREF_HALO_ORBIT_ENABLED, Const.HALO_ORBIT_ENABLED_DEFAULT)
            ?: Const.HALO_ORBIT_ENABLED_DEFAULT
        uiState.haloOuterLevel = (sp?.getInt(Const.PREF_HALO_OUTER_LEVEL, Const.HALO_OUTER_LEVEL_DEFAULT)
            ?: Const.HALO_OUTER_LEVEL_DEFAULT)
            .coerceIn(Const.HALO_OUTER_LEVEL_MIN, Const.HALO_OUTER_LEVEL_MAX)
        uiState.karaoke = sp?.getBoolean(Const.PREF_KARAOKE_ENABLED, Const.KARAOKE_ENABLED_DEFAULT)
            ?: Const.KARAOKE_ENABLED_DEFAULT
        uiState.lineDissolve = sp?.getBoolean(Const.PREF_LINE_DISSOLVE_ENABLED, Const.LINE_DISSOLVE_ENABLED_DEFAULT)
            ?: Const.LINE_DISSOLVE_ENABLED_DEFAULT
        uiState.lyricStyle = (sp?.getInt(Const.PREF_LYRIC_STYLE, Const.LYRIC_STYLE_DEFAULT)
            ?: Const.LYRIC_STYLE_DEFAULT)
            .coerceIn(Const.LYRIC_STYLE_MIN, Const.LYRIC_STYLE_MAX)
        uiState.lineTransitionMode = (sp?.getInt(Const.PREF_LINE_TRANSITION_MODE, Const.LINE_TRANSITION_MODE_DEFAULT)
            ?: Const.LINE_TRANSITION_MODE_DEFAULT)
            .coerceIn(Const.LINE_TRANSITION_MODE_MIN, Const.LINE_TRANSITION_MODE_MAX)
        uiState.landscapeMode = (sp?.getInt(Const.PREF_LANDSCAPE_MODE, Const.LANDSCAPE_MODE_DEFAULT)
            ?: Const.LANDSCAPE_MODE_DEFAULT)
            .coerceIn(Const.LANDSCAPE_MODE_MIN, Const.LANDSCAPE_MODE_MAX)
        uiState.interludeDotsEnabled = sp?.getBoolean(Const.PREF_INTERLUDE_DOTS_ENABLED, Const.INTERLUDE_DOTS_ENABLED_DEFAULT)
            ?: Const.INTERLUDE_DOTS_ENABLED_DEFAULT
        uiState.dualLineLyric = sp?.getBoolean(Const.PREF_DUAL_LINE_LYRIC_ENABLED, Const.DUAL_LINE_LYRIC_ENABLED_DEFAULT)
            ?: Const.DUAL_LINE_LYRIC_ENABLED_DEFAULT
        uiState.persistentWave = sp?.getBoolean(Const.PREF_PERSISTENT_WAVE_ENABLED, Const.PERSISTENT_WAVE_ENABLED_DEFAULT)
            ?: Const.PERSISTENT_WAVE_ENABLED_DEFAULT
        uiState.shimmerLevel = (sp?.getInt(Const.PREF_SHIMMER_LEVEL, Const.SHIMMER_LEVEL_DEFAULT)
            ?: Const.SHIMMER_LEVEL_DEFAULT)
            .coerceIn(Const.SHIMMER_LEVEL_MIN, Const.SHIMMER_LEVEL_MAX)
        uiState.shimmerStyle = (sp?.getInt(Const.PREF_SHIMMER_STYLE, Const.SHIMMER_STYLE_DEFAULT)
            ?: Const.SHIMMER_STYLE_DEFAULT)
            .coerceIn(Const.SHIMMER_STYLE_MIN, Const.SHIMMER_STYLE_MAX)
        uiState.shimmerCountdownEnabled = sp?.getBoolean(Const.PREF_SHIMMER_COUNTDOWN_ENABLED, Const.SHIMMER_COUNTDOWN_ENABLED_DEFAULT)
            ?: Const.SHIMMER_COUNTDOWN_ENABLED_DEFAULT
        uiState.islandShimmerEnabled = sp?.getBoolean(Const.PREF_ISLAND_SHIMMER_ENABLED, Const.ISLAND_SHIMMER_ENABLED_DEFAULT)
            ?: Const.ISLAND_SHIMMER_ENABLED_DEFAULT
        uiState.diagLogEnabled = sp?.getBoolean(Const.PREF_DIAG_LOG_ENABLED, Const.DIAG_LOG_ENABLED_DEFAULT)
            ?: Const.DIAG_LOG_ENABLED_DEFAULT
        uiState.diagDetailed = sp?.getBoolean(Const.PREF_DIAG_DETAILED, Const.DIAG_DETAILED_DEFAULT)
            ?: Const.DIAG_DETAILED_DEFAULT
        uiState.dualLineSubSizeSp = (sp?.getFloat(Const.PREF_DUAL_LINE_SUB_TEXT_SIZE_SP, Const.DUAL_LINE_SUB_TEXT_SIZE_DEFAULT_SP)
            ?: Const.DUAL_LINE_SUB_TEXT_SIZE_DEFAULT_SP)
            .coerceIn(Const.DUAL_LINE_SUB_MIN_SP, Const.DUAL_LINE_SUB_MAX_SP)
        uiState.lyricCenter = sp?.getBoolean(Const.PREF_LYRIC_CENTER_ALIGN, Const.LYRIC_CENTER_ALIGN_DEFAULT)
            ?: Const.LYRIC_CENTER_ALIGN_DEFAULT
        uiState.onlineTimeline = sp?.getBoolean(
            Const.PREF_ONLINE_TIMELINE_ENABLED,
            Const.ONLINE_TIMELINE_ENABLED_DEFAULT
        ) ?: Const.ONLINE_TIMELINE_ENABLED_DEFAULT
        uiState.leftCenter = sp?.getBoolean(Const.PREF_LEFT_CENTER_ALIGN, Const.LEFT_CENTER_ALIGN_DEFAULT)
            ?: Const.LEFT_CENTER_ALIGN_DEFAULT
        uiState.scrollAuto = sp?.getBoolean(Const.PREF_SCROLL_AUTO, Const.SCROLL_AUTO_DEFAULT)
            ?: Const.SCROLL_AUTO_DEFAULT
        uiState.scrollSpeedFactor = (sp?.getFloat(Const.PREF_SCROLL_SPEED_FACTOR, Const.SCROLL_SPEED_FACTOR_DEFAULT)
            ?: Const.SCROLL_SPEED_FACTOR_DEFAULT)
            .coerceIn(Const.SCROLL_SPEED_FACTOR_MIN, Const.SCROLL_SPEED_FACTOR_MAX)

        uiState.textSizeSp = (sp?.getFloat(Const.PREF_LYRIC_TEXT_SIZE_SP, Const.RIGHT_LYRIC_TEXT_SIZE_SP)
            ?: Const.RIGHT_LYRIC_TEXT_SIZE_SP)
            .coerceIn(Const.RIGHT_LYRIC_TEXT_SIZE_MIN_SP, Const.RIGHT_LYRIC_TEXT_SIZE_MAX_SP)
        uiState.lyricWidthDp = (sp?.getInt(Const.PREF_WIDE_LYRIC_WIDTH_DP, Const.WIDE_LYRIC_WIDTH_DP_DEFAULT)
            ?: Const.WIDE_LYRIC_WIDTH_DP_DEFAULT)
            .coerceIn(Const.WIDE_LYRIC_WIDTH_DP_MIN, Const.WIDE_LYRIC_WIDTH_DP_MAX)
        uiState.lyricLeftOffsetDp = (sp?.getFloat(Const.PREF_LYRIC_LEFT_OFFSET_DP, Const.LYRIC_LEFT_OFFSET_DEFAULT_DP)
            ?: Const.LYRIC_LEFT_OFFSET_DEFAULT_DP)
            .coerceIn(Const.LYRIC_LEFT_OFFSET_MIN_DP, Const.LYRIC_LEFT_OFFSET_MAX_DP)
        uiState.rightLyricRightInsetDp = (sp?.getFloat(Const.PREF_RIGHT_LYRIC_RIGHT_INSET_DP, Const.RIGHT_LYRIC_RIGHT_INSET_DEFAULT_DP)
            ?: Const.RIGHT_LYRIC_RIGHT_INSET_DEFAULT_DP)
            .coerceIn(Const.RIGHT_LYRIC_RIGHT_INSET_MIN_DP, Const.RIGHT_LYRIC_RIGHT_INSET_MAX_DP)
        uiState.albumLeftPadPx = (sp?.getInt(Const.PREF_ALBUM_LEFT_PADDING_PX, Const.ALBUM_LEFT_PADDING_DEFAULT_PX)
            ?: Const.ALBUM_LEFT_PADDING_DEFAULT_PX)
            .coerceIn(Const.ALBUM_LEFT_PADDING_MIN_PX, Const.ALBUM_LEFT_PADDING_MAX_PX)
        uiState.leftInfoOffsetDp = (sp?.getFloat(Const.PREF_LEFT_INFO_OFFSET_DP, Const.LEFT_INFO_OFFSET_DEFAULT_DP)
            ?: Const.LEFT_INFO_OFFSET_DEFAULT_DP)
            .coerceIn(Const.LEFT_INFO_OFFSET_MIN_DP, Const.LEFT_INFO_OFFSET_MAX_DP)
        uiState.leftInfoMarginStartDp = (sp?.getFloat(Const.PREF_LEFT_INFO_MARGIN_START_DP, Const.LEFT_INFO_MARGIN_START_DEFAULT_DP)
            ?: Const.LEFT_INFO_MARGIN_START_DEFAULT_DP)
            .coerceIn(Const.LEFT_INFO_MARGIN_START_MIN_DP, Const.LEFT_INFO_MARGIN_START_MAX_DP)
        uiState.leftInfoRightInsetDp = (sp?.getFloat(Const.PREF_LEFT_INFO_RIGHT_INSET_DP, Const.LEFT_INFO_RIGHT_INSET_DEFAULT_DP)
            ?: Const.LEFT_INFO_RIGHT_INSET_DEFAULT_DP)
            .coerceIn(Const.LEFT_INFO_RIGHT_INSET_MIN_DP, Const.LEFT_INFO_RIGHT_INSET_MAX_DP)

        uiState.landscapeLyric = sp?.getBoolean(
            Const.PREF_LANDSCAPE_LYRIC_ENABLED,
            Const.LANDSCAPE_LYRIC_ENABLED_DEFAULT
        ) ?: Const.LANDSCAPE_LYRIC_ENABLED_DEFAULT
        uiState.landscapeTextSizeSp = (sp?.getFloat(Const.PREF_LANDSCAPE_LYRIC_TEXT_SIZE_SP, Const.LANDSCAPE_LYRIC_TEXT_SIZE_DEFAULT_SP)
            ?: Const.LANDSCAPE_LYRIC_TEXT_SIZE_DEFAULT_SP)
            .coerceIn(Const.LANDSCAPE_LYRIC_TEXT_SIZE_MIN_SP, Const.LANDSCAPE_LYRIC_TEXT_SIZE_MAX_SP)
        uiState.landscapeTopMarginDp = (sp?.getFloat(Const.PREF_LANDSCAPE_LYRIC_TOP_MARGIN_DP, Const.LANDSCAPE_LYRIC_TOP_MARGIN_DEFAULT_DP)
            ?: Const.LANDSCAPE_LYRIC_TOP_MARGIN_DEFAULT_DP)
            .coerceIn(Const.LANDSCAPE_LYRIC_TOP_MARGIN_MIN_DP, Const.LANDSCAPE_LYRIC_TOP_MARGIN_MAX_DP)
        uiState.landscapeOffsetXDp = (sp?.getFloat(Const.PREF_LANDSCAPE_LYRIC_OFFSET_X_DP, Const.LANDSCAPE_LYRIC_OFFSET_X_DEFAULT_DP)
            ?: Const.LANDSCAPE_LYRIC_OFFSET_X_DEFAULT_DP)
            .coerceIn(Const.LANDSCAPE_LYRIC_OFFSET_X_MIN_DP, Const.LANDSCAPE_LYRIC_OFFSET_X_MAX_DP)
    }

    private fun resetToDefaults() {
        savePrefs(pushDelayMs = 80L) {
            putBoolean(Const.PREF_MASTER_SWITCH, true)
            putBoolean(Const.PREF_HALO_ENABLED, Const.HALO_ENABLED_DEFAULT)
            putInt(Const.PREF_GLOW_STYLE, Const.GLOW_STYLE_DEFAULT)
            putInt(Const.PREF_GLOW_INTENSITY, Const.GLOW_INTENSITY_DEFAULT)
            putInt(Const.PREF_GLOW_PERIOD_MS, Const.GLOW_PERIOD_MS_DEFAULT)
            putBoolean(Const.PREF_GLOW_OUTER_ENABLED, Const.GLOW_OUTER_ENABLED_DEFAULT)
            putBoolean(Const.PREF_HALO_ORBIT_ENABLED, Const.HALO_ORBIT_ENABLED_DEFAULT)
            putInt(Const.PREF_HALO_OUTER_LEVEL, Const.HALO_OUTER_LEVEL_DEFAULT)
            putBoolean(Const.PREF_KARAOKE_ENABLED, Const.KARAOKE_ENABLED_DEFAULT)
            putBoolean(Const.PREF_LINE_DISSOLVE_ENABLED, Const.LINE_DISSOLVE_ENABLED_DEFAULT)
            putInt(Const.PREF_LYRIC_STYLE, Const.LYRIC_STYLE_DEFAULT)
            putInt(Const.PREF_LINE_TRANSITION_MODE, Const.LINE_TRANSITION_MODE_DEFAULT)
            putInt(Const.PREF_LANDSCAPE_MODE, Const.LANDSCAPE_MODE_DEFAULT)
            putBoolean(Const.PREF_INTERLUDE_DOTS_ENABLED, Const.INTERLUDE_DOTS_ENABLED_DEFAULT)
            putBoolean(Const.PREF_DUAL_LINE_LYRIC_ENABLED, Const.DUAL_LINE_LYRIC_ENABLED_DEFAULT)
            putBoolean(Const.PREF_PERSISTENT_WAVE_ENABLED, Const.PERSISTENT_WAVE_ENABLED_DEFAULT)
            putInt(Const.PREF_SHIMMER_LEVEL, Const.SHIMMER_LEVEL_DEFAULT)
            putInt(Const.PREF_SHIMMER_STYLE, Const.SHIMMER_STYLE_DEFAULT)
            putFloat(Const.PREF_DUAL_LINE_SUB_TEXT_SIZE_SP, Const.DUAL_LINE_SUB_TEXT_SIZE_DEFAULT_SP)
            putBoolean(Const.PREF_LYRIC_CENTER_ALIGN, Const.LYRIC_CENTER_ALIGN_DEFAULT)
            putBoolean(Const.PREF_LEFT_CENTER_ALIGN, Const.LEFT_CENTER_ALIGN_DEFAULT)
            putBoolean(Const.PREF_ONLINE_TIMELINE_ENABLED, Const.ONLINE_TIMELINE_ENABLED_DEFAULT)
            putBoolean(Const.PREF_LANDSCAPE_LYRIC_ENABLED, Const.LANDSCAPE_LYRIC_ENABLED_DEFAULT)
            putFloat(Const.PREF_LANDSCAPE_LYRIC_TEXT_SIZE_SP, Const.LANDSCAPE_LYRIC_TEXT_SIZE_DEFAULT_SP)
            putFloat(Const.PREF_LANDSCAPE_LYRIC_TOP_MARGIN_DP, Const.LANDSCAPE_LYRIC_TOP_MARGIN_DEFAULT_DP)
            putFloat(Const.PREF_LANDSCAPE_LYRIC_OFFSET_X_DP, Const.LANDSCAPE_LYRIC_OFFSET_X_DEFAULT_DP)
            putFloat(Const.PREF_LYRIC_TEXT_SIZE_SP, Const.RIGHT_LYRIC_TEXT_SIZE_SP)
            putInt(Const.PREF_WIDE_LYRIC_WIDTH_DP, Const.WIDE_LYRIC_WIDTH_DP_DEFAULT)
            putFloat(Const.PREF_LYRIC_LEFT_OFFSET_DP, Const.LYRIC_LEFT_OFFSET_DEFAULT_DP)
            putFloat(Const.PREF_RIGHT_LYRIC_RIGHT_INSET_DP, Const.RIGHT_LYRIC_RIGHT_INSET_DEFAULT_DP)
            putInt(Const.PREF_ALBUM_LEFT_PADDING_PX, Const.ALBUM_LEFT_PADDING_DEFAULT_PX)
            putFloat(Const.PREF_LEFT_INFO_OFFSET_DP, Const.LEFT_INFO_OFFSET_DEFAULT_DP)
            putFloat(Const.PREF_LEFT_INFO_MARGIN_START_DP, Const.LEFT_INFO_MARGIN_START_DEFAULT_DP)
            putFloat(Const.PREF_LEFT_INFO_RIGHT_INSET_DP, Const.LEFT_INFO_RIGHT_INSET_DEFAULT_DP)
            putBoolean(Const.PREF_SCROLL_AUTO, Const.SCROLL_AUTO_DEFAULT)
            putFloat(Const.PREF_SCROLL_SPEED_FACTOR, Const.SCROLL_SPEED_FACTOR_DEFAULT)
        }
        syncStateFromPrefs()
        Toast.makeText(this, "已恢复默认配置", Toast.LENGTH_SHORT).show()
    }

    private fun savePrefs(pushDelayMs: Long = 350L, block: SharedPreferences.Editor.() -> Unit) {
        val sp = worldPrefs
        if (sp == null || !uiState.moduleActive) {
            showInactiveToast()
            return
        }
        runCatching {
            sp.edit().apply(block).putBoolean(Const.PREF_CONFIG_INITIALIZED, true).apply()
            scheduleHostMirrorPush(immediate = false, delayMs = pushDelayMs)
        }.onFailure {
            Toast.makeText(this, "保存失败，请先在 LSPosed 启用模块并重启", Toast.LENGTH_LONG).show()
        }
    }

    private fun showInactiveToast() {
        Toast.makeText(this, "请先在 LSPosed 启用模块并重启", Toast.LENGTH_LONG).show()
    }

    /** v1.4.5-fix17（老大提议）：清空诊断日志——文件同进程直接截断，岛进程侧内存环/队列经 PREF_DIAG_CLEAR_AT 时间戳同步清理 */
    private fun clearDiagLog() {
        val file = File(filesDir, Const.DIAG_LOG_FILE_NAME)
        runCatching { if (file.exists()) file.writeText("") }
        savePrefs { putLong(Const.PREF_DIAG_CLEAR_AT, System.currentTimeMillis()) }
        Toast.makeText(this, "诊断日志已清空", Toast.LENGTH_SHORT).show()
    }

    private fun exportDiagLog() {
        val file = File(filesDir, Const.DIAG_LOG_FILE_NAME)
        if (!file.exists() || file.length() <= 0L) {
            Toast.makeText(this, "暂无诊断日志（播放音乐后自动记录）", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val createIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "island_diag_v${BuildConfig.VERSION_NAME}_$stamp.log")
        }
        val started = runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(createIntent, REQ_EXPORT_DIAG_DOC)
            true
        }.getOrDefault(false)
        if (!started) {
            shareDiagTextFallback(file)
        }
    }

    private fun exportConfigJson() {
        val sp = worldPrefs
        if (sp == null || !uiState.moduleActive) {
            showInactiveToast()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val createIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "island_lyric_config_v${BuildConfig.VERSION_NAME}_$stamp.json")
        }
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(createIntent, REQ_EXPORT_CONFIG_DOC)
        }.onFailure { err ->
            Toast.makeText(this, "无法打开文件选择器: ${err.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importConfigJson() {
        val sp = worldPrefs
        if (sp == null || !uiState.moduleActive) {
            showInactiveToast()
            return
        }
        val openIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(openIntent, REQ_IMPORT_CONFIG_DOC)
        }.onFailure { err ->
            Toast.makeText(this, "无法打开文件选择器: ${err.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun scheduleHostMirrorPush(immediate: Boolean, delayMs: Long = 350L) {
        val sp = worldPrefs ?: return
        if (!uiState.moduleActive) return
        mirrorPushRunnable?.let { uiHandler.removeCallbacks(it) }
        val task = Runnable {
            mirrorPushRunnable = null
            runCatching {
                val json = PrefsBackupCodec.serializeFromPrefs(sp)
                val pushIntent = Intent(Const.ACTION_PUSH_PREFS_MIRROR).apply {
                    setPackage(Const.PKG_SYSTEMUI_PLUGIN)
                    putExtra(Const.EXTRA_CALLER_PKG, Const.MODULE_PACKAGE_NAME)
                    putExtra(Const.EXTRA_MIRROR_JSON, json)
                }
                sendBroadcast(pushIntent)
            }
        }
        mirrorPushRunnable = task
        if (immediate) {
            uiHandler.post(task)
        } else {
            uiHandler.postDelayed(task, delayMs.coerceAtLeast(30L))
        }
    }

    private fun requestHostMirrorAutoRestore() {
        unregisterMirrorReplyReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Const.ACTION_REPLY_PREFS_MIRROR) return
                val json = intent.getStringExtra(Const.EXTRA_MIRROR_JSON).orEmpty()
                if (json.isBlank()) return
                val sp = worldPrefs ?: return
                if (!PrefsBackupCodec.isUnconfiguredDefaultState(sp)) {
                    unregisterMirrorReplyReceiver()
                    return
                }
                val applied = PrefsBackupCodec.parseAndApplyJson(json, sp)
                unregisterMirrorReplyReceiver()
                if (applied > 0) {
                    syncStateFromPrefs()
                    Toast.makeText(
                        this@SettingsActivity,
                        "已从宿主镜像自动恢复上次配置（$applied 项参数）",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        mirrorReplyReceiver = receiver
        val filter = IntentFilter(Const.ACTION_REPLY_PREFS_MIRROR)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            val req = Intent(Const.ACTION_REQUEST_PREFS_MIRROR).apply {
                setPackage(Const.PKG_SYSTEMUI_PLUGIN)
                putExtra(Const.EXTRA_CALLER_PKG, Const.MODULE_PACKAGE_NAME)
            }
            sendBroadcast(req)
        }.onFailure {
            unregisterMirrorReplyReceiver()
        }
    }

    private fun unregisterMirrorReplyReceiver() {
        val r = mirrorReplyReceiver ?: return
        mirrorReplyReceiver = null
        runCatching { unregisterReceiver(r) }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT_DIAG_DOC -> {
                val file = File(filesDir, Const.DIAG_LOG_FILE_NAME)
                if (!file.exists()) return
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { inp -> inp.copyTo(out) }
                    }
                    Toast.makeText(this, "诊断日志已导出（${file.length() / 1024}KB）", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(this, "导出失败: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
            REQ_EXPORT_CONFIG_DOC -> {
                val sp = worldPrefs ?: return
                runCatching {
                    val json = PrefsBackupCodec.serializeFromPrefs(sp)
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(this, "设置已导出为 JSON", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(this, "导出设置失败: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
            REQ_IMPORT_CONFIG_DOC -> {
                val sp = worldPrefs ?: return
                runCatching {
                    val text = contentResolver.openInputStream(uri)?.use { inp ->
                        inp.bufferedReader(Charsets.UTF_8).readText()
                    }.orEmpty()
                    val applied = PrefsBackupCodec.parseAndApplyJson(text, sp)
                    if (applied > 0) {
                        syncStateFromPrefs()
                        scheduleHostMirrorPush(immediate = true)
                        Toast.makeText(this, "已导入设置（$applied 项参数已即时生效）", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "配置文件无效或不包含可识别参数", Toast.LENGTH_LONG).show()
                    }
                }.onFailure { err ->
                    Toast.makeText(this, "导入设置失败: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareDiagTextFallback(file: File) {
        runCatching {
            val text = file.readText(Charsets.UTF_8).takeLast(64 * 1024)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "VivoIslandKugouLyric 诊断日志 v${BuildConfig.VERSION_NAME}")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(sendIntent, "分享诊断日志"))
        }.onFailure { err ->
            Toast.makeText(this, "分享失败: ${err.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQ_EXPORT_DIAG_DOC = 1409
        private const val REQ_EXPORT_CONFIG_DOC = 1410
        private const val REQ_IMPORT_CONFIG_DOC = 1411
        private const val TEXT_SIZE_STEP = 0.5f
        private const val SPEED_STEP = 0.1f
        private const val FINE_DP_STEP = 0.1f
    }
}

internal class SettingsUiState {
    var moduleActive by mutableStateOf(false)
    var selectedTab by mutableIntStateOf(0)

    var master by mutableStateOf(true)
    var haloEnabled by mutableStateOf(Const.HALO_ENABLED_DEFAULT)
    var haloStyle by mutableIntStateOf(Const.GLOW_STYLE_DEFAULT)
    var haloIntensity by mutableIntStateOf(Const.GLOW_INTENSITY_DEFAULT)
    var haloPeriodMs by mutableIntStateOf(Const.GLOW_PERIOD_MS_DEFAULT)
    var haloOuterEnabled by mutableStateOf(Const.GLOW_OUTER_ENABLED_DEFAULT)
    var haloOrbitEnabled by mutableStateOf(Const.HALO_ORBIT_ENABLED_DEFAULT)
    var haloOuterLevel by mutableIntStateOf(Const.HALO_OUTER_LEVEL_DEFAULT)
    var karaoke by mutableStateOf(Const.KARAOKE_ENABLED_DEFAULT)
    var lineDissolve by mutableStateOf(Const.LINE_DISSOLVE_ENABLED_DEFAULT)
    var lyricStyle by mutableIntStateOf(Const.LYRIC_STYLE_DEFAULT)
    var lineTransitionMode by mutableIntStateOf(Const.LINE_TRANSITION_MODE_DEFAULT)
    var landscapeMode by mutableIntStateOf(Const.LANDSCAPE_MODE_DEFAULT)
    var interludeDotsEnabled by mutableStateOf(Const.INTERLUDE_DOTS_ENABLED_DEFAULT)
    var dualLineLyric by mutableStateOf(Const.DUAL_LINE_LYRIC_ENABLED_DEFAULT)
    var persistentWave by mutableStateOf(Const.PERSISTENT_WAVE_ENABLED_DEFAULT)
    var shimmerLevel by mutableIntStateOf(Const.SHIMMER_LEVEL_DEFAULT)
    var shimmerStyle by mutableIntStateOf(Const.SHIMMER_STYLE_DEFAULT)
    var shimmerCountdownEnabled by mutableStateOf(Const.SHIMMER_COUNTDOWN_ENABLED_DEFAULT)
    var islandShimmerEnabled by mutableStateOf(Const.ISLAND_SHIMMER_ENABLED_DEFAULT)
    var diagLogEnabled by mutableStateOf(Const.DIAG_LOG_ENABLED_DEFAULT)
    var diagDetailed by mutableStateOf(Const.DIAG_DETAILED_DEFAULT)
    var dualLineSubSizeSp by mutableFloatStateOf(Const.DUAL_LINE_SUB_TEXT_SIZE_DEFAULT_SP)
    var lyricCenter by mutableStateOf(Const.LYRIC_CENTER_ALIGN_DEFAULT)
    var onlineTimeline by mutableStateOf(Const.ONLINE_TIMELINE_ENABLED_DEFAULT)
    var leftCenter by mutableStateOf(Const.LEFT_CENTER_ALIGN_DEFAULT)
    var scrollAuto by mutableStateOf(Const.SCROLL_AUTO_DEFAULT)
    var scrollSpeedFactor by mutableFloatStateOf(Const.SCROLL_SPEED_FACTOR_DEFAULT)

    var textSizeSp by mutableFloatStateOf(Const.RIGHT_LYRIC_TEXT_SIZE_SP)
    var lyricWidthDp by mutableIntStateOf(Const.WIDE_LYRIC_WIDTH_DP_DEFAULT)
    var lyricLeftOffsetDp by mutableFloatStateOf(Const.LYRIC_LEFT_OFFSET_DEFAULT_DP)
    var rightLyricRightInsetDp by mutableFloatStateOf(Const.RIGHT_LYRIC_RIGHT_INSET_DEFAULT_DP)
    var albumLeftPadPx by mutableIntStateOf(Const.ALBUM_LEFT_PADDING_DEFAULT_PX)
    var leftInfoOffsetDp by mutableFloatStateOf(Const.LEFT_INFO_OFFSET_DEFAULT_DP)
    var leftInfoMarginStartDp by mutableFloatStateOf(Const.LEFT_INFO_MARGIN_START_DEFAULT_DP)
    var leftInfoRightInsetDp by mutableFloatStateOf(Const.LEFT_INFO_RIGHT_INSET_DEFAULT_DP)

    var landscapeLyric by mutableStateOf(Const.LANDSCAPE_LYRIC_ENABLED_DEFAULT)
    var landscapeTextSizeSp by mutableFloatStateOf(Const.LANDSCAPE_LYRIC_TEXT_SIZE_DEFAULT_SP)
    var landscapeTopMarginDp by mutableFloatStateOf(Const.LANDSCAPE_LYRIC_TOP_MARGIN_DEFAULT_DP)
    var landscapeOffsetXDp by mutableFloatStateOf(Const.LANDSCAPE_LYRIC_OFFSET_X_DEFAULT_DP)
}

internal class SettingsCallbacks(
    val onMasterChanged: (Boolean) -> Unit,
    val onHaloEnabledChanged: (Boolean) -> Unit,
    val onHaloStyleChanged: (Int) -> Unit,
    val onHaloIntensityChanged: (Int) -> Unit,
    val onHaloPeriodMsChanged: (Int) -> Unit,
    val onHaloOuterEnabledChanged: (Boolean) -> Unit,
    val onHaloOrbitEnabledChanged: (Boolean) -> Unit,
    val onHaloOuterLevelChanged: (Int) -> Unit,
    val onKaraokeChanged: (Boolean) -> Unit,
    val onLineDissolveChanged: (Boolean) -> Unit,
    val onLyricStyleChanged: (Int) -> Unit,
    val onLineTransitionModeChanged: (Int) -> Unit,
    val onLandscapeModeChanged: (Int) -> Unit,
    val onInterludeDotsEnabledChanged: (Boolean) -> Unit,
    val onDualLineLyricChanged: (Boolean) -> Unit,
    val onPersistentWaveChanged: (Boolean) -> Unit,
    val onShimmerLevelChanged: (Int) -> Unit,
    val onShimmerStyleChanged: (Int) -> Unit,
    val onShimmerCountdownEnabledChanged: (Boolean) -> Unit,
    val onIslandShimmerEnabledChanged: (Boolean) -> Unit,
    val onDiagLogEnabledChanged: (Boolean) -> Unit,
    val onClearDiagLog: () -> Unit,
    val onDiagDetailedChanged: (Boolean) -> Unit,
    val onDualLineSubSizeChanged: (Float) -> Unit,
    val onLyricCenterChanged: (Boolean) -> Unit,
    val onOnlineTimelineChanged: (Boolean) -> Unit,
    val onLeftCenterChanged: (Boolean) -> Unit,
    val onScrollAutoChanged: (Boolean) -> Unit,
    val onScrollSpeedChanged: (Float) -> Unit,
    val onTextSizeSpChanged: (Float) -> Unit,
    val onLyricWidthDpChanged: (Int) -> Unit,
    val onLyricLeftOffsetChanged: (Float) -> Unit,
    val onRightInsetChanged: (Float) -> Unit,
    val onAlbumLeftPadChanged: (Int) -> Unit,
    val onLeftInfoOffsetChanged: (Float) -> Unit,
    val onLeftInfoMarginStartChanged: (Float) -> Unit,
    val onLeftInfoRightInsetChanged: (Float) -> Unit,
    val onLandscapeLyricChanged: (Boolean) -> Unit,
    val onLandscapeTextSizeChanged: (Float) -> Unit,
    val onLandscapeTopMarginChanged: (Float) -> Unit,
    val onLandscapeOffsetXChanged: (Float) -> Unit,
    val onExportConfig: () -> Unit,
    val onImportConfig: () -> Unit,
    val onExportDiag: () -> Unit,
    val onResetDefaults: () -> Unit
)

@Composable
private fun SettingsRootScreen(
    state: SettingsUiState,
    callbacks: SettingsCallbacks
) {
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) Color(0xFF000000) else Color(0xFFF2F2F7)
    val pageBackdrop = rememberLayerBackdrop()
    val scrollStates = listOf(
        rememberScrollState(),
        rememberScrollState(),
        rememberScrollState(),
        rememberScrollState()
    )
    val transitionScope = rememberCoroutineScope()
    val transitionProgress = remember { Animatable(1f) }
    var activeTab by remember { mutableIntStateOf(state.selectedTab.coerceIn(0, 3)) }
    var pickerReq by remember { mutableStateOf<PickerRequest?>(null) }
    // v1.4.5-fix15（Quick 浮层裁决）：退场动画走完才清请求——直接置空会让浮层凭空消失、没有退场
    val pickerTransition = remember { MutableTransitionState(false) }
    // v1.4.5-fix13 实施审建议：opener 须 remember 住——staticCompositionLocalOf 的值一变，整棵 Provider 子树全部失效重组
    val pickerOpener = remember { { req: PickerRequest -> pickerReq = req; pickerTransition.targetState = true } }
    val dismissPicker = remember { { pickerTransition.targetState = false } }
    LaunchedEffect(pickerTransition.isIdle, pickerTransition.currentState) {
        if (pickerTransition.isIdle && !pickerTransition.currentState && pickerReq != null) {
            pickerReq = null
        }
    }
    // 浮层打开时返回键先关浮层（走退场动画），不直接退出整个设置页
    BackHandler(enabled = pickerReq != null) { dismissPicker() }
    var outgoingTab by remember { mutableIntStateOf(-1) }
    var transitionDir by remember { mutableFloatStateOf(1f) }
    // 首帧仅挂当前页保证冷启动秒出，首帧后常驻保活全部 4 页，后续切页零销毁重建
    var allTabsMounted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        allTabsMounted = true
    }

    LaunchedEffect(state.selectedTab) {
        val target = state.selectedTab.coerceIn(0, 3)
        if (target != activeTab) {
            val prev = activeTab
            transitionDir = if (target > prev) 1f else -1f
            outgoingTab = prev
            activeTab = target
            transitionProgress.snapTo(0f)
            transitionProgress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.95f,
                    stiffness = 580f,
                    visibilityThreshold = 0.002f
                )
            )
            outgoingTab = -1
        }
    }

    RegisterPauseSnap {
        outgoingTab = -1
        activeTab = state.selectedTab.coerceIn(0, 3)
        transitionScope.launch {
            transitionProgress.snapTo(1f)
        }
    }

    val slideDistancePx = with(LocalDensity.current) { 22.dp.toPx() }

    CompositionLocalProvider(LocalPickerOpener provides pickerOpener) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Layer 0 + 1：纯净中性背景 + 4 页常驻保活与短程横滑淡入淡出（挂载 pageBackdrop 供底部胶囊采样折射）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .layerBackdrop(pageBackdrop)
        ) {
            for (tabIndex in 0..3) {
                key(tabIndex) {
                    val shouldMount = allTabsMounted || tabIndex == activeTab || tabIndex == outgoingTab
                    if (shouldMount) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    val isCurrent = tabIndex == activeTab
                                    val isOutgoing = tabIndex == outgoingTab && outgoingTab != activeTab
                                    layout(placeable.width, placeable.height) {
                                        if (isCurrent || isOutgoing) {
                                            val p = transitionProgress.value.coerceIn(0f, 1f)
                                            val pageAlpha = if (isCurrent) p else (1f - p)
                                            val pageTx = if (isCurrent) {
                                                transitionDir * slideDistancePx * (1f - p)
                                            } else {
                                                -transitionDir * slideDistancePx * 0.45f * p
                                            }
                                            val z = if (isCurrent) 1f else 0f
                                            placeable.placeWithLayer(0, 0, z) {
                                                alpha = pageAlpha
                                                translationX = pageTx
                                            }
                                        }
                                    }
                                }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollStates[tabIndex])
                                    .statusBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                HeaderCard(
                                    moduleActive = state.moduleActive,
                                    masterEnabled = state.master,
                                    isDark = isDark,
                                    onMasterChanged = callbacks.onMasterChanged
                                )
                                Spacer(Modifier.height(6.dp))

                                when (tabIndex) {
                                    0 -> GeneralTabContent(state, callbacks, isDark)
                                    1 -> PortraitTabContent(state, callbacks, isDark)
                                    2 -> LandscapeTabContent(state, callbacks, isDark)
                                    else -> BackupTabContent(state, callbacks, isDark)
                                }

                                // 底部留白，使最后一张卡片可完整滚到悬浮玻璃底栏上方并从底栏后面通透穿过
                                Spacer(Modifier.navigationBarsPadding().height(98.dp))
                            }
                        }
                    }
                }
            }
        }

        // Layer 2：官方结构底部悬浮 LiquidBottomTabs（与 pageBackdrop 严格为兄弟节点，杜绝循环引用）
        LiquidBottomTabs(
            selectedTabIndex = { state.selectedTab },
            onTabSelected = { state.selectedTab = it },
            backdrop = pageBackdrop,
            tabsCount = 4,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            LiquidBottomTab(
                label = "常规",
                selected = state.selectedTab == 0,
                iconType = 0,
                onClick = { state.selectedTab = 0 }
            )
            LiquidBottomTab(
                label = "竖屏",
                selected = state.selectedTab == 1,
                iconType = 1,
                onClick = { state.selectedTab = 1 }
            )
            LiquidBottomTab(
                label = "横屏",
                selected = state.selectedTab == 2,
                iconType = 2,
                onClick = { state.selectedTab = 2 }
            )
            LiquidBottomTab(
                label = "备份",
                selected = state.selectedTab == 3,
                iconType = 3,
                onClick = { state.selectedTab = 3 }
            )
        }
        // Layer 3：选择浮层（与玻璃底栏同为 pageBackdrop 兄弟节点）；退场期间请求仍在、动画走完由 LaunchedEffect 清理
        if (pickerReq != null || pickerTransition.currentState) {
            PickerGlassOverlay(
                request = pickerReq,
                transitionState = pickerTransition,
                backdrop = pageBackdrop,
                onDismissRequest = dismissPicker
            )
        }
    }
    }}

@Composable
private fun HeaderCard(
    moduleActive: Boolean,
    masterEnabled: Boolean,
    isDark: Boolean,
    onMasterChanged: (Boolean) -> Unit
) {
    val textPrimary = if (isDark) Color(0xFFF5F5F7) else Color(0xFF1C1C1E)
    val textSecondary = if (isDark) Color(0xFF8E8E93) else Color(0xFF6E6E73)
    val accent = if (isDark) Color(0xFF0A84FF) else Color(0xFF007AFF)
    val green = if (isDark) Color(0xFF30D158) else Color(0xFF34C759)
    val danger = if (isDark) Color(0xFFFF453A) else Color(0xFFFF3B30)

    GroupedCard(isDark = isDark) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        text = "酷词岛",
                        style = TextStyle(
                            color = textPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        BasicText(
                            text = "v${BuildConfig.VERSION_NAME}",
                            style = TextStyle(
                                color = accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (moduleActive) green else danger)
                    )
                    Spacer(Modifier.width(6.dp))
                    BasicText(
                        text = if (moduleActive) {
                            "LSPosed 已激活 · 参数实时热生效"
                        } else {
                            "模块未激活 · 请先在 LSPosed 启用并重启"
                        },
                        style = TextStyle(
                            color = if (moduleActive) textSecondary else danger,
                            fontSize = 12.sp
                        )
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            LiquidToggle(
                selected = { masterEnabled },
                onSelect = onMasterChanged,
                enabled = moduleActive
            )
        }
    }
}

@Composable
private fun GeneralTabContent(
    state: SettingsUiState,
    cb: SettingsCallbacks,
    isDark: Boolean
) {
    SectionLabel("胶囊呼吸光圈", isDark)
    GroupedCard(isDark) {
        SwitchSettingRow(
            title = "呼吸光圈",
            subtitle = "独立于流光；仅宽态播放＋亮屏运行，30fps 节流，暂停与息屏零帧回调",
            checked = state.haloEnabled,
            enabled = state.moduleActive,
            isDark = isDark,
            onCheckedChange = cb.onHaloEnabledChanged
        )
        CardHairline(isDark)
        PickerSettingRow(
            title = "光圈风格",
            subtitle = "6 套里外双层光效，按封面主色动态配色（切歌 400ms 平滑过渡）",
            options = listOf("双晕呼吸", "双峰流光", "液态柔融", "光波外扩", "水银折光", "双向星环"),
            selectedIndex = state.haloStyle,
            enabled = state.moduleActive && state.haloEnabled,
            isDark = isDark,
            onSelect = cb.onHaloStyleChanged
        )
        CardHairline(isDark)
        SegmentedSettingRow(
            title = "光圈强度",
            subtitle = "三档分段（默认淡），内漫射严格克制以保障歌词可读性",
            options = listOf("淡", "中", "亮"),
            selectedIndex = state.haloIntensity,
            enabled = state.moduleActive && state.haloEnabled,
            isDark = isDark,
            onSelect = cb.onHaloIntensityChanged
        )
        CardHairline(isDark)
        SegmentedSettingRow(
            title = "光圈周期",
            subtitle = "光环单圈流动或呼吸周期，默认 4s",
            options = listOf("2.5s", "4s", "6s"),
            selectedIndex = when (state.haloPeriodMs) {
                Const.GLOW_PERIOD_MS_FAST -> 0
                Const.GLOW_PERIOD_MS_SLOW -> 2
                else -> 1
            },
            enabled = state.moduleActive && state.haloEnabled,
            isDark = isDark,
            onSelect = { idx ->
                val period = when (idx) {
                    0 -> Const.GLOW_PERIOD_MS_FAST
                    2 -> Const.GLOW_PERIOD_MS_SLOW
                    else -> Const.GLOW_PERIOD_MS_DEFAULT
                }
                cb.onHaloPeriodMsChanged(period)
            }
        )
        CardHairline(isDark)
        SwitchSettingRow(
            title = "外圈光晕",
            subtitle = "单独控制胶囊外缘柔光（外扩 ≤14px）；关闭后仅保留内细光边与上下弧内漫射",
            checked = state.haloOuterEnabled,
            enabled = state.moduleActive && state.haloEnabled,
            isDark = isDark,
            onCheckedChange = cb.onHaloOuterEnabledChanged
        )
        CardHairline(isDark)
        SegmentedSettingRow(
            title = "外圈亮度",
            subtitle = "五档增益同步调节内外光晕亮度（0.85x–1.35x），默认标准",
            options = listOf("弱", "标准", "增强", "较强", "最强"),
            selectedIndex = state.haloOuterLevel,
            enabled = state.moduleActive && state.haloEnabled,
            isDark = isDark,
            onSelect = cb.onHaloOuterLevelChanged
        )
        CardHairline(isDark)
        SwitchSettingRow(
            title = "环绕光效",
            subtitle = "关闭后只保留静态光晕（对①双晕、④光波无影响）",
            checked = state.haloOrbitEnabled,
            enabled = state.moduleActive && state.haloEnabled,
            isDark = isDark,
            onCheckedChange = cb.onHaloOrbitEnabledChanged
        )
    }

    Spacer(Modifier.height(14.dp))
    SectionLabel("核心视觉与时间轴", isDark)
    GroupedCard(isDark) {
        SwitchSettingRow(
            title = "主行染色（卡拉 OK 逐字变色）",
            subtitle = "开启为封面色逐字变色；关闭恢复纯白高亮（单行/双行通用）",
            checked = state.karaoke,
            enabled = state.moduleActive,
            isDark = isDark,
            onCheckedChange = cb.onKaraokeChanged
        )
        CardHairline(isDark)
        SwitchSettingRow(
            title = "换行溶解过渡",
            subtitle = "切句时 300ms 柔焦溶解与上推过渡，单行/双行通用",
            checked = state.lineDissolve,
            enabled = state.moduleActive,
            isDark = isDark,
            onCheckedChange = cb.onLineDissolveChanged
        )
        CardHairline(isDark)
        PickerSettingRow(
            title = "歌词动态样式",
            subtitle = "7 套宽态主行动效，非默认款 30fps 节流",
            options = listOf("默认逐字", "极光织带", "逐字弹入", "扫光微浮", "聚光灯", "波浪弹簧", "水墨晕染"),
            selectedIndex = state.lyricStyle,
            enabled = state.moduleActive,
            isDark = isDark,
            onSelect = cb.onLyricStyleChanged
        )
        CardHairline(isDark)
        PickerSettingRow(
            title = "换行过渡效果",
            subtitle = "切句时新旧两行的交接方式（需开启换行溶解）",
            options = listOf("溶解", "柔焦", "散聚", "雾凝", "呼吸交融"),
            optionDetails = listOf(
                "旧行散焦上浮淡出",
                "旧行糊化淡出，新行由糊变清",
                "旧句逐字散开、新句逐字聚拢；双行时两行交替轮换",
                "新旧两行在雾中交接",
                "两行同位，一呼一吸交叠"
            ),
            selectedIndex = state.lineTransitionMode,
            enabled = state.moduleActive && state.lineDissolve,
            isDark = isDark,
            onSelect = cb.onLineTransitionModeChanged
        )
        CardHairline(isDark)
        PickerSettingRow(
            title = "横屏时",
            subtitle = "横屏时岛的去向",
            options = listOf("摘岛", "默认岛", "歌词悬浮窗"),
            optionDetails = listOf(
                "藏起原厂岛；开了悬浮歌词时以悬浮歌词显示",
                "交还原厂岛显示，不显示悬浮歌词",
                "岛在时照常显示岛内歌词，岛不在时显示悬浮歌词"
            ),
            selectedIndex = state.landscapeMode,
            enabled = state.moduleActive,
            isDark = isDark,
            onSelect = cb.onLandscapeModeChanged
        )
        CardHairline(isDark)
        SwitchSettingRow(
            title = "双行歌词预览",
            subtitle = "命中在线时间轴时主行下方预览下一句；长间奏与末句自动退回单行居中",
            checked = state.dualLineLyric,
            enabled = state.moduleActive,
            isDark = isDark,
            onCheckedChange = cb.onDualLineLyricChanged
        )
        CardHairline(isDark)
        SliderSettingRow(
            title = "次行预览字号",
            subtitle = "范围 6.5sp ~ 11.5sp，默认 7.8sp（仅双行模式生效，支持实时热调节）",
            valueText = String.format(Locale.US, "%.1fsp", state.dualLineSubSizeSp),
            value = state.dualLineSubSizeSp,
            valueRange = Const.DUAL_LINE_SUB_MIN_SP..Const.DUAL_LINE_SUB_MAX_SP,
            step = 0.1f,
            enabled = state.moduleActive && state.dualLineLyric,
            isDark = isDark,
            onValueChange = cb.onDualLineSubSizeChanged
        )
        CardHairline(isDark)
        SwitchSettingRow(
            title = "歌词常驻声波",
            subtitle = "单行/双行均可独立开启，最右侧常驻原厂声波并自动避让歌词宽度",
            checked = state.persistentWave,
            enabled = state.moduleActive,
            isDark = isDark,
            onCheckedChange = cb.onPersistentWaveChanged
        )
        CardHairline(isDark)
        SliderSettingRow(
            title = "前奏流光强度",
            subtitle = "长前奏（≥28 秒）倒计时区域的流光亮度，三档自选；间奏不启用流光",
            valueText = when (state.shimmerLevel) {
                1 -> "中"
                2 -> "亮"
                else -> "淡"
            },
            value = state.shimmerLevel.toFloat(),
            valueRange = Const.SHIMMER_LEVEL_MIN.toFloat()..Const.SHIMMER_LEVEL_MAX.toFloat(),
            step = 1f,
            enabled = state.moduleActive,
            isDark = isDark,
            onValueChange = { cb.onShimmerLevelChanged(it.roundToInt()) }
        )
        CardHairline(isDark)
        // v1.4.5-fix13 改版（老大 19:50 点名）：样式选择改弹小框——行内只留一行，点开才出名单，不占版面
        PickerSettingRow(
            title = "前奏流光样式",
            subtitle = "点选切换，强度档位对每种样式都生效；仅长前奏倒计时段显示",
            options = listOf("横扫", "呼吸", "极光", "封面色", "星点", "斜扫", "文字"),
            selectedIndex = state.shimmerStyle,
            enabled = state.moduleActive,
            isDark = isDark,
            onSelect = { cb.onShimmerStyleChanged(it) }
        )
        CardHairline(isDark)
        // v1.4.5-fix13 C（老大 19:47 定）：流光两枚独立开关——倒计时流光管长前奏倒计时区，整岛流光管整个宽胶囊背景层，可同时开
        SwitchSettingRow(
            title = "倒计时流光",
            subtitle = "长前奏（≥28 秒）倒计时区域的流光，样式与强度用上方设置",
            checked = state.shimmerCountdownEnabled,
            enabled = state.moduleActive,
            isDark = isDark,
            onCheckedChange = cb.onShimmerCountdownEnabledChanged
        )
        CardHairline(isDark)
        SwitchSettingRow(
            title = "整岛流光",
            subtitle = "流光铺满整个宽胶囊、在歌词底下流动，与倒计时流光可同时开；倒计时亮时右半边自动让位；选了「文字」样式时整岛按横扫显示",
            checked = state.islandShimmerEnabled,
            enabled = state.moduleActive,
            isDark = isDark,
            onCheckedChange = cb.onIslandShimmerEnabledChanged
        )
        CardHairline(isDark)
        SwitchSettingRow(
            title = "诊断日志",
            subtitle = "记录岛的运行诊断（供排查问题导出）；关掉后不再记录、更省电",
            checked = state.diagLogEnabled,
            enabled = state.moduleActive,
            isDark = isDark,
            onCheckedChange = cb.onDiagLogEnabledChanged
        )
        CardHairline(isDark)
        SwitchSettingRow(
            title = "详细诊断日志",
            subtitle = "默认精简（省电省存储）；排查问题时打开，几何与滚动明细全量记录",
            checked = state.diagDetailed,
            enabled = state.moduleActive,
            isDark = isDark,
            onCheckedChange = cb.onDiagDetailedChanged
        )
        CardHairline(isDark)
        SwitchSettingRow(
            title = "在线歌词时间轴（KRC / LRC）",
            subtitle = "按需查酷狗主源＋LRCLIB 备源（本地缓存）；关闭后零联网仅本地学习",
            checked = state.onlineTimeline,
            enabled = state.moduleActive,
            isDark = isDark,
            onCheckedChange = cb.onOnlineTimelineChanged
        )
    }

    SectionLabel("滚动与对齐", isDark)
    GroupedCard(isDark) {
        SwitchSettingRow(
            title = "短歌名/歌手居中",
            subtitle = "开启时左侧短文本水平居中，关闭则左对齐；超宽滚动时自动左起",
            checked = state.leftCenter,
            enabled = state.moduleActive,
            isDark = isDark,
            onCheckedChange = cb.onLeftCenterChanged
        )
        CardHairline(isDark)
        SwitchSettingRow(
            title = "右侧歌词居中对齐",
            subtitle = "关闭靠左对齐，开启后单行与双行主/次歌词短文本均水平居中",
            checked = state.lyricCenter,
            enabled = state.moduleActive,
            isDark = isDark,
            onCheckedChange = cb.onLyricCenterChanged
        )
        CardHairline(isDark)
        SwitchSettingRow(
            title = "按歌自适应滚动时长",
            subtitle = "优先使用时间轴真实行时长，无时间轴按同曲近 6 句中位数计算",
            checked = state.scrollAuto,
            enabled = state.moduleActive,
            isDark = isDark,
            onCheckedChange = cb.onScrollAutoChanged
        )
        CardHairline(isDark)
        SliderSettingRow(
            title = "滚动速度倍率",
            subtitle = "范围 0.5x ~ 2.0x，默认 1.0x（仅影响滚动位移，不影响演唱进度）",
            valueText = String.format(Locale.US, "%.1fx", state.scrollSpeedFactor),
            value = state.scrollSpeedFactor,
            valueRange = Const.SCROLL_SPEED_FACTOR_MIN..Const.SCROLL_SPEED_FACTOR_MAX,
            step = 0.1f,
            enabled = state.moduleActive,
            isDark = isDark,
            onValueChange = cb.onScrollSpeedChanged
        )
    }
}

@Composable
private fun PortraitTabContent(
    state: SettingsUiState,
    cb: SettingsCallbacks,
    isDark: Boolean
) {
    SectionLabel("右侧歌词区（字号与边距）", isDark)
    GroupedCard(isDark) {
        SliderSettingRow(
            title = "宽形态歌词字号",
            subtitle = "范围 10.0 ~ 16.0 sp，默认 12.0 sp（窄形态固定 10.5 sp）",
            valueText = String.format(Locale.US, "%.1f sp", state.textSizeSp),
            value = state.textSizeSp,
            valueRange = Const.RIGHT_LYRIC_TEXT_SIZE_MIN_SP..Const.RIGHT_LYRIC_TEXT_SIZE_MAX_SP,
            step = 0.5f,
            enabled = state.moduleActive,
            isDark = isDark,
            onValueChange = cb.onTextSizeSpChanged
        )
        CardHairline(isDark)
        SliderSettingRow(
            title = "歌词区基准宽度",
            subtitle = "范围 52 ~ 95 dp，默认 65 dp（>约 91dp 时继续撑宽整颗胶囊）",
            valueText = "${state.lyricWidthDp} dp",
            value = state.lyricWidthDp.toFloat(),
            valueRange = Const.WIDE_LYRIC_WIDTH_DP_MIN.toFloat()..Const.WIDE_LYRIC_WIDTH_DP_MAX.toFloat(),
            step = 1f,
            enabled = state.moduleActive,
            isDark = isDark,
            onValueChange = { cb.onLyricWidthDpChanged(it.roundToInt()) }
        )
        CardHairline(isDark)
        SliderSettingRow(
            title = "右侧歌词左缘偏移（0.1dp）",
            subtitle = "范围 -35.0 ~ +30.0 dp，默认 +1.2 dp（正值远离挖孔、负值向挖孔贴）",
            valueText = formatSignedDp(state.lyricLeftOffsetDp),
            value = state.lyricLeftOffsetDp,
            valueRange = Const.LYRIC_LEFT_OFFSET_MIN_DP..Const.LYRIC_LEFT_OFFSET_MAX_DP,
            step = 0.1f,
            enabled = state.moduleActive,
            isDark = isDark,
            onValueChange = cb.onLyricLeftOffsetChanged
        )
        CardHairline(isDark)
        SliderSettingRow(
            title = "右侧歌词右缘内缩（0.1dp）",
            subtitle = "范围 0.0 ~ 20.0 dp，默认 2.3 dp（歌词右缘距右胶囊右缘的留白）",
            valueText = String.format(Locale.US, "%.1f dp", state.rightLyricRightInsetDp),
            value = state.rightLyricRightInsetDp,
            valueRange = Const.RIGHT_LYRIC_RIGHT_INSET_MIN_DP..Const.RIGHT_LYRIC_RIGHT_INSET_MAX_DP,
            step = 0.1f,
            enabled = state.moduleActive,
            isDark = isDark,
            onValueChange = cb.onRightInsetChanged
        )
    }

    SectionLabel("左侧专辑图与歌名区", isDark)
    GroupedCard(isDark) {
        SliderSettingRow(
            title = "专辑图左边距",
            subtitle = "范围 0 ~ 40 px，步进 1，默认 14 px（原厂基线 35 px）",
            valueText = "${state.albumLeftPadPx} px",
            value = state.albumLeftPadPx.toFloat(),
            valueRange = Const.ALBUM_LEFT_PADDING_MIN_PX.toFloat()..Const.ALBUM_LEFT_PADDING_MAX_PX.toFloat(),
            step = 1f,
            enabled = state.moduleActive,
            isDark = isDark,
            onValueChange = { cb.onAlbumLeftPadChanged(it.roundToInt()) }
        )
        CardHairline(isDark)
        SliderSettingRow(
            title = "歌名块整块横向偏移（0.1dp）",
            subtitle = "范围 -20.0 ~ +20.0 dp，默认 0.0 dp（整块左右平移不改列宽）",
            valueText = formatSignedDp(state.leftInfoOffsetDp),
            value = state.leftInfoOffsetDp,
            valueRange = Const.LEFT_INFO_OFFSET_MIN_DP..Const.LEFT_INFO_OFFSET_MAX_DP,
            step = 0.1f,
            enabled = state.moduleActive,
            isDark = isDark,
            onValueChange = cb.onLeftInfoOffsetChanged
        )
        CardHairline(isDark)
        SliderSettingRow(
            title = "歌名左缘间距（0.1dp）",
            subtitle = "范围 -6.0 ~ +20.0 dp，默认 4.0 dp（歌名列距专辑图右缘间距）",
            valueText = formatSignedDp(state.leftInfoMarginStartDp),
            value = state.leftInfoMarginStartDp,
            valueRange = Const.LEFT_INFO_MARGIN_START_MIN_DP..Const.LEFT_INFO_MARGIN_START_MAX_DP,
            step = 0.1f,
            enabled = state.moduleActive,
            isDark = isDark,
            onValueChange = cb.onLeftInfoMarginStartChanged
        )
        CardHairline(isDark)
        SliderSettingRow(
            title = "歌名右缘内缩（0.1dp）",
            subtitle = "范围 -20.0 ~ +24.0 dp，默认 4.0 dp（正值收紧避开挖孔、负值右探）",
            valueText = formatSignedDp(state.leftInfoRightInsetDp),
            value = state.leftInfoRightInsetDp,
            valueRange = Const.LEFT_INFO_RIGHT_INSET_MIN_DP..Const.LEFT_INFO_RIGHT_INSET_MAX_DP,
            step = 0.1f,
            enabled = state.moduleActive,
            isDark = isDark,
            onValueChange = cb.onLeftInfoRightInsetChanged
        )
    }
}

@Composable
private fun LandscapeTabContent(
    state: SettingsUiState,
    cb: SettingsCallbacks,
    isDark: Boolean
) {
    SectionLabel("悬浮开关与字号", isDark)
    GroupedCard(isDark) {
        SwitchSettingRow(
            title = "横屏/游戏顶部悬浮歌词",
            subtitle = "横屏全屏且原岛隐藏时出现，纯文字＋柔阴影、完全触摸穿透，切竖屏无缝交回",
            checked = state.landscapeLyric,
            enabled = state.moduleActive,
            isDark = isDark,
            onCheckedChange = cb.onLandscapeLyricChanged
        )
        CardHairline(isDark)
        SliderSettingRow(
            title = "横屏歌词字号（实时预览）",
            subtitle = "范围 9.0 ~ 24.0 sp，步进 0.5 sp，默认 13.0 sp（独立于竖屏字号）",
            valueText = String.format(Locale.US, "%.1f sp", state.landscapeTextSizeSp),
            value = state.landscapeTextSizeSp,
            valueRange = Const.LANDSCAPE_LYRIC_TEXT_SIZE_MIN_SP..Const.LANDSCAPE_LYRIC_TEXT_SIZE_MAX_SP,
            step = 0.5f,
            enabled = state.moduleActive,
            isDark = isDark,
            onValueChange = cb.onLandscapeTextSizeChanged
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (isDark) Color(0xFF0A0A0C) else Color(0xFF1C1C1E))
                .border(0.8.dp, Color(0x28FFFFFF), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = "你摘下这世界上走得最慢的表",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = Color(0xD7FFFFFF),
                    fontSize = state.landscapeTextSizeSp.sp,
                    textAlign = TextAlign.Center,
                    shadow = Shadow(
                        color = Color(0x99000000),
                        offset = Offset(0f, 1.5f),
                        blurRadius = 4f
                    )
                )
            )
        }
        Spacer(Modifier.height(4.dp))
    }

    SectionLabel("横屏位置微调（热生效）", isDark)
    GroupedCard(isDark) {
        SliderSettingRow(
            title = "距顶部距离（上下位置）",
            subtitle = "范围 0 ~ 120 dp，默认 6 dp（向下微调避开游戏顶栏或挖孔）",
            valueText = "${state.landscapeTopMarginDp.roundToInt()} dp",
            value = state.landscapeTopMarginDp,
            valueRange = Const.LANDSCAPE_LYRIC_TOP_MARGIN_MIN_DP..Const.LANDSCAPE_LYRIC_TOP_MARGIN_MAX_DP,
            step = 1f,
            enabled = state.moduleActive,
            isDark = isDark,
            onValueChange = cb.onLandscapeTopMarginChanged
        )
        CardHairline(isDark)
        val intX = state.landscapeOffsetXDp.roundToInt()
        SliderSettingRow(
            title = "水平偏移（左右位置）",
            subtitle = "范围 -160 ~ +160 dp，默认 0 dp（居中；负值左移、正值右移）",
            valueText = if (intX > 0) "+${intX} dp" else "${intX} dp",
            value = state.landscapeOffsetXDp,
            valueRange = Const.LANDSCAPE_LYRIC_OFFSET_X_MIN_DP..Const.LANDSCAPE_LYRIC_OFFSET_X_MAX_DP,
            step = 1f,
            enabled = state.moduleActive,
            isDark = isDark,
            onValueChange = cb.onLandscapeOffsetXChanged
        )
    }
}

@Composable
private fun BackupTabContent(
    state: SettingsUiState,
    cb: SettingsCallbacks,
    isDark: Boolean
) {
    val textSecondary = if (isDark) Color(0xFF8E8E93) else Color(0xFF6E6E73)

    SectionLabel("配置备份与自动镜像", isDark)
    GroupedCard(isDark) {
        BasicText(
            text = "支持导出/导入 JSON 配置文件；每次修改也会自动镜像到宿主进程，覆盖安装或卸载重装后首次打开将自动恢复。",
            style = TextStyle(
                color = textSecondary,
                fontSize = 12.5.sp,
                lineHeight = 18.sp
            )
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GlassActionButton(
                label = "导出设置",
                danger = false,
                enabled = state.moduleActive,
                isDark = isDark,
                onClick = cb.onExportConfig,
                modifier = Modifier.weight(1f)
            )
            GlassActionButton(
                label = "导入设置",
                danger = false,
                enabled = state.moduleActive,
                isDark = isDark,
                onClick = cb.onImportConfig,
                modifier = Modifier.weight(1f)
            )
        }
    }

    SectionLabel("诊断与重置", isDark)
    GroupedCard(isDark) {
        GlassActionButton(
            label = "导出诊断日志（island_diag.log）",
            danger = false,
            enabled = true,
            isDark = isDark,
            onClick = cb.onExportDiag
        )
        Spacer(Modifier.height(10.dp))
        GlassActionButton(
            label = "清空诊断日志",
            danger = false,
            enabled = true,
            isDark = isDark,
            onClick = cb.onClearDiagLog
        )
        Spacer(Modifier.height(10.dp))
        GlassActionButton(
            label = "恢复全部默认配置",
            danger = true,
            enabled = state.moduleActive,
            isDark = isDark,
            onClick = cb.onResetDefaults
        )
    }

    BasicText(
        text = "提示：请确认酷狗音乐设置内已开启「车载蓝牙歌词」；窄形态系统跑马灯仅支持单次滚动，速度由系统决定。",
        style = TextStyle(
            color = textSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp
        ),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
    )
}

@Composable
private fun SectionLabel(text: String, isDark: Boolean) {
    val color = if (isDark) Color(0xFF8E8E93) else Color(0xFF6E6E73)
    BasicText(
        text = text,
        style = TextStyle(
            color = color,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold
        ),
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 6.dp)
    )
}

@Composable
private fun GroupedCard(
    isDark: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    val cardBg = if (isDark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val cardBorder = if (isDark) Color(0x18FFFFFF) else Color(0x0D000000)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(cardBg)
            .border(0.75.dp, cardBorder, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content
    )
}

@Composable
private fun CardHairline(isDark: Boolean) {
    val divider = if (isDark) Color(0x1EFFFFFF) else Color(0x12000000)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(0.6.dp)
            .background(divider)
    )
}

@Composable
private fun SwitchSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    isDark: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val textPrimary = if (isDark) Color(0xFFF5F5F7) else Color(0xFF1C1C1E)
    val textSecondary = if (isDark) Color(0xFF8E8E93) else Color(0xFF6E6E73)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = TextStyle(
                    color = textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Spacer(Modifier.height(2.dp))
            BasicText(
                text = subtitle,
                style = TextStyle(
                    color = textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.5.sp
                )
            )
        }
        Spacer(Modifier.width(12.dp))
        LiquidToggle(
            selected = { checked },
            onSelect = onCheckedChange,
            enabled = enabled
        )
    }
}

/** v1.4.5-fix12 追加：离散选项点选标签排（每行 4 个，名字全可见，点哪个选哪个） */
// v1.4.5-fix13：选择小框走页内玻璃浮层（老大 19:52 点名沿用液态玻璃、别另起样式显突兀）——
// 不用系统 Dialog 窗口（跨窗口采不到本页 backdrop），浮层与底部玻璃栏同为 pageBackdrop 的兄弟节点、同款玻璃配方。
private data class PickerRequest(
    val title: String,
    val options: List<String>,
    val selectedIndex: Int,
    val isDark: Boolean,
    val onSelect: (Int) -> Unit,
    // v1.4.5-fix15：被点行的中心 Y（窗口坐标 px），浮层入场缩放以这一行为原点，像从行里弹出来
    val anchorCenterYPx: Float = -1f,
    // vc57：逐项说明（与 options 同序，可为 null）；有说明的选项在浮层里两行排，行内副标题不再塞逐项解释
    val optionDetails: List<String>? = null
)

private val LocalPickerOpener = staticCompositionLocalOf<((PickerRequest) -> Unit)?> { null }

@Composable
private fun PickerSettingRow(
    title: String,
    subtitle: String,
    options: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    isDark: Boolean,
    onSelect: (Int) -> Unit,
    optionDetails: List<String>? = null
) {
    val textPrimary = if (isDark) Color(0xFFF5F5F7) else Color(0xFF1C1C1E)
    val textSecondary = if (isDark) Color(0xFF8E8E93) else Color(0xFF6E6E73)
    val accent = if (isDark) Color(0xFF0A84FF) else Color(0xFF007AFF)
    val opener = LocalPickerOpener.current
    var rowCenterYPx by remember { mutableFloatStateOf(-1f) }
    // vc57：按压反馈改自绘底板——默认 indication 的状态层盖满整行、直角深灰（老大录屏点名）。
    // interactions 流顺序驱动 Animatable：Press 先把底板推到位、Release/Cancel 再淡出，同协程顺序执行，
    // 快速轻点也至少闪一下；滚动接管手势时父级 verticalScroll 会发 Cancel，底板随即淡出。
    val interaction = remember { MutableInteractionSource() }
    val plateAnim = remember { Animatable(0f) }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { inter ->
            when (inter) {
                is PressInteraction.Press -> plateAnim.animateTo(1f, tween(90))
                is PressInteraction.Release, is PressInteraction.Cancel -> plateAnim.animateTo(0f, tween(160))
            }
        }
    }
    val plateColor = if (isDark) Color.White else Color.Black
    val plateMaxAlpha = if (isDark) 0.07f else 0.055f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val a = plateAnim.value * plateMaxAlpha
                if (a > 0.003f) {
                    val inset = 6.dp.toPx()
                    drawRoundRect(
                        color = plateColor.copy(alpha = a),
                        topLeft = Offset(inset, 0f),
                        size = Size(size.width - inset * 2f, size.height),
                        cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx())
                    )
                }
            }
            .onGloballyPositioned { coords ->
                rowCenterYPx = coords.positionInWindow().y + coords.size.height / 2f
            }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                opener?.invoke(
                    PickerRequest(title, options, selectedIndex, isDark, onSelect, rowCenterYPx, optionDetails)
                )
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = TextStyle(
                    color = textPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Spacer(Modifier.height(2.dp))
            BasicText(
                text = subtitle,
                style = TextStyle(
                    color = textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.5.sp
                )
            )
        }
        Spacer(Modifier.width(12.dp))
        BasicText(
            text = options.getOrElse(selectedIndex) { "" },
            style = TextStyle(
                color = accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        )
        Spacer(Modifier.width(4.dp))
        BasicText(
            text = "›",
            style = TextStyle(
                color = textSecondary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

@Composable
private fun PickerGlassOverlay(
    request: PickerRequest?,
    transitionState: MutableTransitionState<Boolean>,
    backdrop: Backdrop,
    onDismissRequest: () -> Unit
) {
    if (request == null) return
    val isDark = request.isDark
    val textPrimary = if (isDark) Color(0xFFF5F5F7) else Color(0xFF1C1C1E)
    val textSecondary = if (isDark) Color(0xFF8E8E93) else Color(0xFF6E6E73)
    val accent = if (isDark) Color(0xFF0A84FF) else Color(0xFF007AFF)
    // vc57 B1：tint 浅 45%→65%（深 70% 不动）、blur 10→16dp、lens 18/22→10/12——
    // 旧配方压不住糊开的黑字、成灰团，折射位移又让糊影与真字对不上位（老大录屏点名）
    val containerColor = if (isDark) Color(0xFF1C1C1E).copy(0.70f) else Color(0xFFFFFFFF).copy(0.65f)
    val opening = transitionState.targetState
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 先把约束高落成局部值：面板现嵌在 AV 内容里，BoxWithConstraintsScope 的隐式接收者进不去
        val panelMaxHeight = maxHeight * 0.6f
        AnimatedVisibility(
            visibleState = transitionState,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier.fillMaxSize()
        ) {
            // vc57 B2/B3（双审定版）：统一进度 p 同时驱动遮罩、面板 alpha 与缩放——玻璃、阴影、内容、
            // 蒙层同一个数、同帧进退，杜绝打开首帧残影与关闭后原位留灰影；不再用 fade/scale 规格动画，
            // 也不再给遮罩单开一条 animateFloatAsState 曲线。
            // 注意：AV 作用域里的 transition 是 Transition<EnterExitState>，不是 Boolean（Hark diff 审 M1）
            val p by transition.animateFloat(
                transitionSpec = {
                    if (targetState == EnterExitState.Visible) tween(180, easing = LinearOutSlowInEasing)
                    else tween(140, easing = FastOutLinearInEasing)
                },
                label = "pickerProgress"
            ) { s -> if (s == EnterExitState.Visible) 1f else 0f }
            var panelTopYPx by remember { mutableFloatStateOf(-1f) }
            var panelHeightPx by remember { mutableFloatStateOf(0f) }
            // vc58：蒙层点击专用 interactionSource——配合 indication=null 关掉默认按压反馈
            val scrimInteraction = remember { MutableInteractionSource() }
            Box(modifier = Modifier.fillMaxSize()) {
                // vc58（双审定版）：灰的真根因是这层 clickable 的默认按压 indication——无 Material 主题时
                // 按下会在整个可点区铺 30% 黑直角矩形，按住不松就一直灰、松手当帧消失（老大原话
                // 「一直按着不取消那个弹窗，就会一直灰着」；亮度算术 222.6×0.75×0.7＝116.9 与实测
                // 静止 115.5 互证）。故 indication=null 根治；蒙层本身按老大「不要灰」浅色归 0、
                // 深色留 0.08 保底分层，仍 ×p 同帧进退；退场期间不再接点击。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = (if (isDark) 0.08f else 0f) * p))
                        .clickable(
                            interactionSource = scrimInteraction,
                            indication = null,
                            enabled = opening
                        ) { onDismissRequest() }
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 36.dp)
                        .fillMaxWidth()
                        .heightIn(max = panelMaxHeight)
                        .onGloballyPositioned { coords ->
                            panelTopYPx = coords.positionInWindow().y
                            panelHeightPx = coords.size.height.toFloat()
                        }
                        .graphicsLayer {
                            // 面板位置/尺寸未测得前保持不可见，第一帧就落在最终位置；
                            // 缩放原点按面板实测位置与被点行中心现算，不再拿窗口比例套面板高度
                            // vc58：alpha 走 p^1.3，压短退场低段面板自身的灰白雾尾
                            alpha = if (panelHeightPx > 0f) p.pow(1.3f) else 0f
                            val s = 0.94f + 0.06f * p
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(
                                0.5f,
                                if (panelHeightPx > 0f && request.anchorCenterYPx >= 0f && panelTopYPx >= 0f) {
                                    ((request.anchorCenterYPx - panelTopYPx) / panelHeightPx).coerceIn(0f, 1f)
                                } else {
                                    0.5f
                                }
                            )
                            // 合成策略用 ModulateAlpha（Hark diff 审 M2 定版）：Offscreen 的离屏缓冲会把
                            // drawBackdrop 画在面板外的阴影裁成硬边；Auto 又会在 p 到 1 的末帧让阴影弹出来。
                            // ModulateAlpha 让 alpha 逐条作用在绘制指令上，任何阶段都不裁阴影。
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(28.dp) },
                        effects = {
                            vibrancy()
                            blur(16.0f.dp.toPx())
                            lens(10f.dp.toPx(), 12f.dp.toPx())
                        },
                        highlight = { Highlight.Default },
                        shadow = {
                            BackdropShadow(
                                radius = 28f.dp,
                                color = Color.Black.copy(alpha = if (isDark) 0.30f else 0.14f)
                            )
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    // 顶边高光：1dp 内描边，白色自顶部 22%（深色 12%）渐隐到 40% 高度，模拟光从上方落下
                    .drawWithContent {
                        drawContent()
                        val strokePx = 1.dp.toPx()
                        val inset = strokePx / 2f
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = if (isDark) 0.12f else 0.22f),
                                    Color.Transparent
                                ),
                                startY = 0f,
                                endY = size.height * 0.4f
                            ),
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - strokePx, size.height - strokePx),
                            cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx()),
                            style = Stroke(strokePx)
                        )
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* 消费点击，点面板本身不关闭；vc58：同样关掉默认按压黑层，按住面板不铺黑 */ }
                    .padding(8.dp)
            ) {
                BasicText(
                    text = request.title,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
                    style = TextStyle(
                        color = textSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                // fix15：删掉 0.6dp 横线——层次改靠间距与选中态胶囊底板（用户点名横线丑）
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    request.options.forEachIndexed { idx, label ->
                        val selected = idx == request.selectedIndex
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val plateAlpha by animateFloatAsState(
                            targetValue = when {
                                selected -> if (isDark) 0.16f else 0.12f
                                pressed -> 0.06f
                                else -> 0f
                            },
                            animationSpec = tween(100),
                            label = "pickerPlate"
                        )
                        val plate = accent.copy(alpha = plateAlpha)
                        // vc57：有逐项说明时两行排，行高用 min 撑开、说明可折行不裁字；无说明与旧版一致
                        val detail = request.optionDetails?.getOrNull(idx)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = if (detail != null) 60.dp else 48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(plate)
                                .clickable(interactionSource = interaction, indication = null) {
                                    // 点下当帧即生效，同时开始退场，不等动画走完
                                    request.onSelect(idx)
                                    onDismissRequest()
                                }
                                .padding(horizontal = 12.dp, vertical = if (detail != null) 8.dp else 0.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                BasicText(
                                    text = label,
                                    style = TextStyle(
                                        color = if (selected) accent else textPrimary,
                                        fontSize = 14.5.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                )
                                if (detail != null) {
                                    Spacer(Modifier.height(1.dp))
                                    BasicText(
                                        text = detail,
                                        style = TextStyle(
                                            color = textSecondary,
                                            fontSize = 11.5.sp,
                                            lineHeight = 15.sp
                                        )
                                    )
                                }
                            }
                            if (selected) {
                                BasicText(
                                    text = "✓",
                                    style = TextStyle(
                                        color = accent,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun SegmentedSettingRow(
    title: String,
    subtitle: String,
    options: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    isDark: Boolean,
    onSelect: (Int) -> Unit
) {
    val textPrimary = if (isDark) Color(0xFFF5F5F7) else Color(0xFF1C1C1E)
    val textSecondary = if (isDark) Color(0xFF8E8E93) else Color(0xFF6E6E73)
    val trackBg = if (isDark) Color(0xFF2C2C2E) else Color(0xFFE9E9EE)
    val selectedBg = if (isDark) Color(0xFF0A84FF) else Color(0xFF007AFF)
    val unselectedText = if (isDark) Color(0xFFD1D1D6) else Color(0xFF3A3A3C)
    val contentAlpha = if (enabled) 1f else 0.45f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        BasicText(
            text = title,
            style = TextStyle(
                color = textPrimary.copy(alpha = contentAlpha),
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
        Spacer(Modifier.height(2.dp))
        BasicText(
            text = subtitle,
            style = TextStyle(
                color = textSecondary.copy(alpha = contentAlpha),
                fontSize = 12.sp,
                lineHeight = 16.5.sp
            )
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(trackBg.copy(alpha = contentAlpha))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEachIndexed { idx, label ->
                val active = idx == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) selectedBg.copy(alpha = contentAlpha) else Color.Transparent)
                        .clickable(enabled = enabled) { onSelect(idx) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = label,
                        style = TextStyle(
                            color = (if (active) Color.White else unselectedText).copy(alpha = contentAlpha),
                            fontSize = 13.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderSettingRow(
    title: String,
    subtitle: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    enabled: Boolean,
    isDark: Boolean,
    onValueChange: (Float) -> Unit
) {
    val textPrimary = if (isDark) Color(0xFFF5F5F7) else Color(0xFF1C1C1E)
    val textSecondary = if (isDark) Color(0xFF8E8E93) else Color(0xFF6E6E73)
    val accent = if (isDark) Color(0xFF0A84FF) else Color(0xFF007AFF)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = title,
                style = TextStyle(
                    color = textPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                modifier = Modifier.weight(1f)
            )
            BasicText(
                text = valueText,
                style = TextStyle(
                    color = accent,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Spacer(Modifier.height(2.dp))
        BasicText(
            text = subtitle,
            style = TextStyle(
                color = textSecondary,
                fontSize = 12.sp,
                lineHeight = 16.5.sp
            )
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StepPillButton(
                symbol = "−",
                enabled = enabled,
                isDark = isDark,
                onClick = {
                    val next = (value - step).coerceIn(valueRange.start, valueRange.endInclusive)
                    onValueChange(next)
                }
            )
            LiquidSlider(
                value = { value },
                onValueChange = onValueChange,
                valueRange = valueRange,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            StepPillButton(
                symbol = "+",
                enabled = enabled,
                isDark = isDark,
                onClick = {
                    val next = (value + step).coerceIn(valueRange.start, valueRange.endInclusive)
                    onValueChange(next)
                }
            )
        }
    }
}

private fun snapFloat(value: Float, minVal: Float, maxVal: Float, step: Float): Float {
    val steps = ((value - minVal) / step).roundToInt()
    val raw = minVal + steps * step
    return ((raw * 10f).roundToInt() / 10f).coerceIn(minVal, maxVal)
}

private fun formatSignedDp(offsetDp: Float): String {
    val clean = if (abs(offsetDp) < 0.05f) 0f else offsetDp
    return if (clean > 0f) {
        String.format(Locale.US, "+%.1f dp", clean)
    } else {
        String.format(Locale.US, "%.1f dp", clean)
    }
}
