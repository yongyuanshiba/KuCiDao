package com.vivo.islandlyric.util

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.vivo.islandlyric.Const
import io.github.libxposed.api.XposedInterface

data class RuntimePrefs(
    val masterEnabled: Boolean = true,
    val glowEnabled: Boolean = Const.GLOW_ENABLED_DEFAULT,
    val glowLevel: Int = Const.GLOW_LEVEL_DEFAULT,
    val haloEnabled: Boolean = Const.HALO_ENABLED_DEFAULT,
    val glowStyle: Int = Const.GLOW_STYLE_DEFAULT,
    val glowIntensity: Int = Const.GLOW_INTENSITY_DEFAULT,
    val glowPeriodMs: Int = Const.GLOW_PERIOD_MS_DEFAULT,
    val glowOuterEnabled: Boolean = Const.GLOW_OUTER_ENABLED_DEFAULT,
    val glowOrbitEnabled: Boolean = Const.HALO_ORBIT_ENABLED_DEFAULT,
    val haloOuterLevel: Int = Const.HALO_OUTER_LEVEL_DEFAULT,
    val lyricTextSizeSp: Float = Const.RIGHT_LYRIC_TEXT_SIZE_SP,
    val scrollAuto: Boolean = Const.SCROLL_AUTO_DEFAULT,
    val scrollSpeedFactor: Float = Const.SCROLL_SPEED_FACTOR_DEFAULT,
    val karaokeEnabled: Boolean = Const.KARAOKE_ENABLED_DEFAULT,
    val lineDissolveEnabled: Boolean = Const.LINE_DISSOLVE_ENABLED_DEFAULT,
    val lyricStyle: Int = Const.LYRIC_STYLE_DEFAULT,
    val lineTransitionMode: Int = Const.LINE_TRANSITION_MODE_DEFAULT,
    val landscapeMode: Int = Const.LANDSCAPE_MODE_DEFAULT,
    val interludeDotsEnabled: Boolean = Const.INTERLUDE_DOTS_ENABLED_DEFAULT,
    val dualLineLyricEnabled: Boolean = Const.DUAL_LINE_LYRIC_ENABLED_DEFAULT,
    val persistentWaveEnabled: Boolean = Const.PERSISTENT_WAVE_ENABLED_DEFAULT,
    val shimmerLevel: Int = Const.SHIMMER_LEVEL_DEFAULT,
    val shimmerStyle: Int = Const.SHIMMER_STYLE_DEFAULT,
    val shimmerCountdownEnabled: Boolean = Const.SHIMMER_COUNTDOWN_ENABLED_DEFAULT,
    val islandShimmerEnabled: Boolean = Const.ISLAND_SHIMMER_ENABLED_DEFAULT,
    val diagDetailed: Boolean = Const.DIAG_DETAILED_DEFAULT,
    val diagLogEnabled: Boolean = Const.DIAG_LOG_ENABLED_DEFAULT,
    val diagClearAt: Long = 0L,
    val dualLineSubTextSizeSp: Float = Const.DUAL_LINE_SUB_TEXT_SIZE_DEFAULT_SP,
    val lyricCenterAlign: Boolean = Const.LYRIC_CENTER_ALIGN_DEFAULT,
    val wideLyricWidthDp: Int = Const.WIDE_LYRIC_WIDTH_DP_DEFAULT,
    val leftCenterAlign: Boolean = Const.LEFT_CENTER_ALIGN_DEFAULT,
    val onlineTimelineEnabled: Boolean = Const.ONLINE_TIMELINE_ENABLED_DEFAULT,
    val lyricLeftOffsetDp: Float = Const.LYRIC_LEFT_OFFSET_DEFAULT_DP,
    val albumLeftPaddingPx: Int = Const.ALBUM_LEFT_PADDING_DEFAULT_PX,
    val leftInfoOffsetDp: Float = Const.LEFT_INFO_OFFSET_DEFAULT_DP,
    val leftInfoMarginStartDp: Float = Const.LEFT_INFO_MARGIN_START_DEFAULT_DP,
    val leftInfoRightInsetDp: Float = Const.LEFT_INFO_RIGHT_INSET_DEFAULT_DP,
    val rightLyricRightInsetDp: Float = Const.RIGHT_LYRIC_RIGHT_INSET_DEFAULT_DP,
    val landscapeLyricEnabled: Boolean = Const.LANDSCAPE_LYRIC_ENABLED_DEFAULT,
    val landscapeLyricTopMarginDp: Float = Const.LANDSCAPE_LYRIC_TOP_MARGIN_DEFAULT_DP,
    val landscapeLyricOffsetXDp: Float = Const.LANDSCAPE_LYRIC_OFFSET_X_DEFAULT_DP,
    val landscapeLyricTextSizeSp: Float = Const.LANDSCAPE_LYRIC_TEXT_SIZE_DEFAULT_SP
)

/**
 * 钩子侧跨进程配置读取单例（v1.3.8 迁移至 libxposed Remote SharedPreferences，共 20 键热生效）：
 * - 通过 XposedInterface.getRemotePreferences(Const.PREF_FILE_NAME) 实时读取模块配置；
 * - 监听远程偏好变更事件立即刷新缓存快照，同时保留宿主侧配置镜像保护。
 */
object HookPrefs {

    // v1.3.18：已有 OnSharedPreferenceChangeListener + 镜像广播实现 0ms 即时热生效，兜底节流放宽至 3000ms 减少动画期主线程跨进程重解析
    private const val RELOAD_THROTTLE_MS = 3000L

    @Volatile
    private var xposed: XposedInterface? = null

    @Volatile
    private var remotePrefs: SharedPreferences? = null

    @Volatile
    private var lastReloadUptimeMs: Long = 0L

    @Volatile
    private var cachedSnapshot: RuntimePrefs = RuntimePrefs()

    @Volatile
    private var hostMirrorContext: Context? = null

    @Volatile
    private var lastMirroredSnapshot: RuntimePrefs? = null

    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        lastReloadUptimeMs = 0L
    }

    /** 绑定 libxposed 接口，初始化宿主侧 Remote SharedPreferences 通道 */
    fun attachXposed(iface: XposedInterface) {
        xposed = iface
        getOrCreatePrefs()
    }

    /** 绑定宿主上下文，模块配置存在真实用户键值时自动镜像备份至宿主私有 SharedPreferences */
    fun attachHostContext(ctx: Context) {
        hostMirrorContext = ctx.applicationContext ?: ctx
    }

    fun read(forceReload: Boolean = false): RuntimePrefs {
        return runCatching {
            val prefs = getOrCreatePrefs() ?: return@runCatching cachedSnapshot
            val now = SystemClock.uptimeMillis()
            if (forceReload || now - lastReloadUptimeMs >= RELOAD_THROTTLE_MS) {
                lastReloadUptimeMs = now
                val parsed = parseSnapshot(prefs)
                cachedSnapshot = parsed
                maybeMirrorToHost(prefs, parsed)
            }
            cachedSnapshot
        }.getOrDefault(cachedSnapshot)
    }

    private fun maybeMirrorToHost(prefs: SharedPreferences, snapshot: RuntimePrefs) {
        val ctx = hostMirrorContext ?: return
        if (snapshot == lastMirroredSnapshot) return
        // 关键保护：重装后尚未配置时 RemotePreferences 为空，绝不用默认空态覆盖宿主里已有的有效备份
        val hasRealKeys = runCatching { prefs.all.isNotEmpty() }.getOrDefault(false)
        if (!hasRealKeys) return
        lastMirroredSnapshot = snapshot
        runCatching {
            val json = PrefsBackupCodec.serializeRuntimePrefs(snapshot)
            ctx.getSharedPreferences(Const.HOST_PREFS_MIRROR_FILE, Context.MODE_PRIVATE)
                .edit()
                .putString(Const.HOST_PREFS_MIRROR_KEY, json)
                .apply()
        }
    }

    private fun getOrCreatePrefs(): SharedPreferences? {
        remotePrefs?.let { return it }
        synchronized(this) {
            remotePrefs?.let { return it }
            val iface = xposed ?: return null
            val created = runCatching {
                val props = iface.frameworkProperties
                if ((props and XposedInterface.PROP_CAP_REMOTE) == 0L) {
                    return@runCatching null
                }
                iface.getRemotePreferences(Const.PREF_FILE_NAME).also { sp ->
                    runCatching { sp.registerOnSharedPreferenceChangeListener(changeListener) }
                }
            }.getOrNull()
            remotePrefs = created
            return created
        }
    }

    private fun parseSnapshot(prefs: SharedPreferences): RuntimePrefs {
        val master = prefs.getBoolean(Const.PREF_MASTER_SWITCH, true)
        val glowEnabled = prefs.getBoolean(Const.PREF_GLOW_ENABLED, Const.GLOW_ENABLED_DEFAULT)
        val glowLevel = readIntSafe(prefs, Const.PREF_GLOW_LEVEL, Const.GLOW_LEVEL_DEFAULT)
            .coerceIn(Const.GLOW_LEVEL_MIN, Const.GLOW_LEVEL_MAX)
        val haloEnabled = prefs.getBoolean(Const.PREF_HALO_ENABLED, Const.HALO_ENABLED_DEFAULT)
        val glowStyle = readIntSafe(prefs, Const.PREF_GLOW_STYLE, Const.GLOW_STYLE_DEFAULT)
            .coerceIn(Const.GLOW_STYLE_MIN, Const.GLOW_STYLE_MAX)
        val glowIntensity = readIntSafe(prefs, Const.PREF_GLOW_INTENSITY, Const.GLOW_INTENSITY_DEFAULT)
            .coerceIn(Const.GLOW_INTENSITY_MIN, Const.GLOW_INTENSITY_MAX)
        val rawGlowPeriod = readIntSafe(prefs, Const.PREF_GLOW_PERIOD_MS, Const.GLOW_PERIOD_MS_DEFAULT)
        val glowPeriodMs = when (rawGlowPeriod) {
            Const.GLOW_PERIOD_MS_FAST, Const.GLOW_PERIOD_MS_DEFAULT, Const.GLOW_PERIOD_MS_SLOW -> rawGlowPeriod
            else -> Const.GLOW_PERIOD_MS_DEFAULT
        }
        val glowOuterEnabled = prefs.getBoolean(Const.PREF_GLOW_OUTER_ENABLED, Const.GLOW_OUTER_ENABLED_DEFAULT)
        val glowOrbitEnabled = prefs.getBoolean(Const.PREF_HALO_ORBIT_ENABLED, Const.HALO_ORBIT_ENABLED_DEFAULT)
        val haloOuterLevel = readIntSafe(prefs, Const.PREF_HALO_OUTER_LEVEL, Const.HALO_OUTER_LEVEL_DEFAULT)
            .coerceIn(Const.HALO_OUTER_LEVEL_MIN, Const.HALO_OUTER_LEVEL_MAX)
        val lyricSizeSp = readFloatSafe(prefs, Const.PREF_LYRIC_TEXT_SIZE_SP, Const.RIGHT_LYRIC_TEXT_SIZE_SP)
            .coerceIn(Const.RIGHT_LYRIC_TEXT_SIZE_MIN_SP, Const.RIGHT_LYRIC_TEXT_SIZE_MAX_SP)
        val scrollAuto = prefs.getBoolean(Const.PREF_SCROLL_AUTO, Const.SCROLL_AUTO_DEFAULT)
        val speedFactor = readFloatSafe(prefs, Const.PREF_SCROLL_SPEED_FACTOR, Const.SCROLL_SPEED_FACTOR_DEFAULT)
            .coerceIn(Const.SCROLL_SPEED_FACTOR_MIN, Const.SCROLL_SPEED_FACTOR_MAX)
        val karaokeEnabled = prefs.getBoolean(Const.PREF_KARAOKE_ENABLED, Const.KARAOKE_ENABLED_DEFAULT)
        val lineDissolveEnabled = prefs.getBoolean(Const.PREF_LINE_DISSOLVE_ENABLED, Const.LINE_DISSOLVE_ENABLED_DEFAULT)
        val lyricStyle = readIntSafe(prefs, Const.PREF_LYRIC_STYLE, Const.LYRIC_STYLE_DEFAULT)
            .coerceIn(Const.LYRIC_STYLE_MIN, Const.LYRIC_STYLE_MAX)
        val lineTransitionMode = readIntSafe(prefs, Const.PREF_LINE_TRANSITION_MODE, Const.LINE_TRANSITION_MODE_DEFAULT)
            .coerceIn(Const.LINE_TRANSITION_MODE_MIN, Const.LINE_TRANSITION_MODE_MAX)
        val landscapeMode = readIntSafe(prefs, Const.PREF_LANDSCAPE_MODE, Const.LANDSCAPE_MODE_DEFAULT)
            .coerceIn(Const.LANDSCAPE_MODE_MIN, Const.LANDSCAPE_MODE_MAX)
        val interludeDotsEnabled = prefs.getBoolean(Const.PREF_INTERLUDE_DOTS_ENABLED, Const.INTERLUDE_DOTS_ENABLED_DEFAULT)
        val dualLineLyricEnabled = prefs.getBoolean(Const.PREF_DUAL_LINE_LYRIC_ENABLED, Const.DUAL_LINE_LYRIC_ENABLED_DEFAULT)
        val persistentWaveEnabled = prefs.getBoolean(Const.PREF_PERSISTENT_WAVE_ENABLED, Const.PERSISTENT_WAVE_ENABLED_DEFAULT)
        val shimmerLevel = readIntSafe(prefs, Const.PREF_SHIMMER_LEVEL, Const.SHIMMER_LEVEL_DEFAULT)
            .coerceIn(Const.SHIMMER_LEVEL_MIN, Const.SHIMMER_LEVEL_MAX)
        val shimmerStyle = readIntSafe(prefs, Const.PREF_SHIMMER_STYLE, Const.SHIMMER_STYLE_DEFAULT)
            .coerceIn(Const.SHIMMER_STYLE_MIN, Const.SHIMMER_STYLE_MAX)
        val shimmerCountdownEnabled = prefs.getBoolean(Const.PREF_SHIMMER_COUNTDOWN_ENABLED, Const.SHIMMER_COUNTDOWN_ENABLED_DEFAULT)
        val islandShimmerEnabled = prefs.getBoolean(Const.PREF_ISLAND_SHIMMER_ENABLED, Const.ISLAND_SHIMMER_ENABLED_DEFAULT)
        val diagDetailed = prefs.getBoolean(Const.PREF_DIAG_DETAILED, Const.DIAG_DETAILED_DEFAULT)
        val diagLogEnabled = prefs.getBoolean(Const.PREF_DIAG_LOG_ENABLED, Const.DIAG_LOG_ENABLED_DEFAULT)
        val diagClearAt = runCatching { prefs.getLong(Const.PREF_DIAG_CLEAR_AT, 0L) }.getOrDefault(0L)
        val dualLineSubTextSizeSp = readFloatSafe(prefs, Const.PREF_DUAL_LINE_SUB_TEXT_SIZE_SP, Const.DUAL_LINE_SUB_TEXT_SIZE_DEFAULT_SP)
            .coerceIn(Const.DUAL_LINE_SUB_MIN_SP, Const.DUAL_LINE_SUB_MAX_SP)
        val lyricCenterAlign = prefs.getBoolean(Const.PREF_LYRIC_CENTER_ALIGN, Const.LYRIC_CENTER_ALIGN_DEFAULT)
        val wideLyricWidthDp = readIntSafe(prefs, Const.PREF_WIDE_LYRIC_WIDTH_DP, Const.WIDE_LYRIC_WIDTH_DP_DEFAULT)
            .coerceIn(Const.WIDE_LYRIC_WIDTH_DP_MIN, Const.WIDE_LYRIC_WIDTH_DP_MAX)
        val leftCenterAlign = prefs.getBoolean(Const.PREF_LEFT_CENTER_ALIGN, Const.LEFT_CENTER_ALIGN_DEFAULT)
        val onlineTimelineEnabled = prefs.getBoolean(
            Const.PREF_ONLINE_TIMELINE_ENABLED,
            Const.ONLINE_TIMELINE_ENABLED_DEFAULT
        )
        val lyricLeftOffsetDp = readFloatSafe(
            prefs,
            Const.PREF_LYRIC_LEFT_OFFSET_DP,
            Const.LYRIC_LEFT_OFFSET_DEFAULT_DP
        ).coerceIn(Const.LYRIC_LEFT_OFFSET_MIN_DP, Const.LYRIC_LEFT_OFFSET_MAX_DP)
        val albumLeftPaddingPx = readIntSafe(prefs, Const.PREF_ALBUM_LEFT_PADDING_PX, Const.ALBUM_LEFT_PADDING_DEFAULT_PX)
            .coerceIn(Const.ALBUM_LEFT_PADDING_MIN_PX, Const.ALBUM_LEFT_PADDING_MAX_PX)
        val leftInfoOffsetDp = readFloatSafe(
            prefs,
            Const.PREF_LEFT_INFO_OFFSET_DP,
            Const.LEFT_INFO_OFFSET_DEFAULT_DP
        ).coerceIn(Const.LEFT_INFO_OFFSET_MIN_DP, Const.LEFT_INFO_OFFSET_MAX_DP)
        val leftInfoMarginStartDp = readFloatSafe(
            prefs,
            Const.PREF_LEFT_INFO_MARGIN_START_DP,
            Const.LEFT_INFO_MARGIN_START_DEFAULT_DP
        ).coerceIn(Const.LEFT_INFO_MARGIN_START_MIN_DP, Const.LEFT_INFO_MARGIN_START_MAX_DP)
        val leftInfoRightInsetDp = readFloatSafe(
            prefs,
            Const.PREF_LEFT_INFO_RIGHT_INSET_DP,
            Const.LEFT_INFO_RIGHT_INSET_DEFAULT_DP
        ).coerceIn(Const.LEFT_INFO_RIGHT_INSET_MIN_DP, Const.LEFT_INFO_RIGHT_INSET_MAX_DP)
        val rightLyricRightInsetDp = readFloatSafe(
            prefs,
            Const.PREF_RIGHT_LYRIC_RIGHT_INSET_DP,
            Const.RIGHT_LYRIC_RIGHT_INSET_DEFAULT_DP
        ).coerceIn(Const.RIGHT_LYRIC_RIGHT_INSET_MIN_DP, Const.RIGHT_LYRIC_RIGHT_INSET_MAX_DP)
        val landscapeLyricEnabled = prefs.getBoolean(
            Const.PREF_LANDSCAPE_LYRIC_ENABLED,
            Const.LANDSCAPE_LYRIC_ENABLED_DEFAULT
        )
        val landscapeLyricTopMarginDp = readFloatSafe(
            prefs,
            Const.PREF_LANDSCAPE_LYRIC_TOP_MARGIN_DP,
            Const.LANDSCAPE_LYRIC_TOP_MARGIN_DEFAULT_DP
        ).coerceIn(Const.LANDSCAPE_LYRIC_TOP_MARGIN_MIN_DP, Const.LANDSCAPE_LYRIC_TOP_MARGIN_MAX_DP)
        val landscapeLyricOffsetXDp = readFloatSafe(
            prefs,
            Const.PREF_LANDSCAPE_LYRIC_OFFSET_X_DP,
            Const.LANDSCAPE_LYRIC_OFFSET_X_DEFAULT_DP
        ).coerceIn(Const.LANDSCAPE_LYRIC_OFFSET_X_MIN_DP, Const.LANDSCAPE_LYRIC_OFFSET_X_MAX_DP)
        val landscapeLyricTextSizeSp = readFloatSafe(
            prefs,
            Const.PREF_LANDSCAPE_LYRIC_TEXT_SIZE_SP,
            Const.LANDSCAPE_LYRIC_TEXT_SIZE_DEFAULT_SP
        ).coerceIn(Const.LANDSCAPE_LYRIC_TEXT_SIZE_MIN_SP, Const.LANDSCAPE_LYRIC_TEXT_SIZE_MAX_SP)

        return RuntimePrefs(
            masterEnabled = master,
            glowEnabled = glowEnabled,
            glowLevel = glowLevel,
            haloEnabled = haloEnabled,
            glowStyle = glowStyle,
            glowIntensity = glowIntensity,
            glowPeriodMs = glowPeriodMs,
            glowOuterEnabled = glowOuterEnabled,
            glowOrbitEnabled = glowOrbitEnabled,
            haloOuterLevel = haloOuterLevel,
            lyricTextSizeSp = lyricSizeSp,
            scrollAuto = scrollAuto,
            scrollSpeedFactor = speedFactor,
            karaokeEnabled = karaokeEnabled,
            lineDissolveEnabled = lineDissolveEnabled,
            lyricStyle = lyricStyle,
            lineTransitionMode = lineTransitionMode,
            landscapeMode = landscapeMode,
            interludeDotsEnabled = interludeDotsEnabled,
            dualLineLyricEnabled = dualLineLyricEnabled,
            persistentWaveEnabled = persistentWaveEnabled,
            shimmerLevel = shimmerLevel,
            shimmerStyle = shimmerStyle,
            shimmerCountdownEnabled = shimmerCountdownEnabled,
            islandShimmerEnabled = islandShimmerEnabled,
            diagDetailed = diagDetailed,
            diagLogEnabled = diagLogEnabled,
            diagClearAt = diagClearAt,
            dualLineSubTextSizeSp = dualLineSubTextSizeSp,
            lyricCenterAlign = lyricCenterAlign,
            wideLyricWidthDp = wideLyricWidthDp,
            leftCenterAlign = leftCenterAlign,
            onlineTimelineEnabled = onlineTimelineEnabled,
            lyricLeftOffsetDp = lyricLeftOffsetDp,
            albumLeftPaddingPx = albumLeftPaddingPx,
            leftInfoOffsetDp = leftInfoOffsetDp,
            leftInfoMarginStartDp = leftInfoMarginStartDp,
            leftInfoRightInsetDp = leftInfoRightInsetDp,
            rightLyricRightInsetDp = rightLyricRightInsetDp,
            landscapeLyricEnabled = landscapeLyricEnabled,
            landscapeLyricTopMarginDp = landscapeLyricTopMarginDp,
            landscapeLyricOffsetXDp = landscapeLyricOffsetXDp,
            landscapeLyricTextSizeSp = landscapeLyricTextSizeSp
        )
    }

    private fun readIntSafe(prefs: SharedPreferences, key: String, defaultVal: Int): Int {
        return runCatching { prefs.getInt(key, defaultVal) }.getOrElse {
            runCatching { prefs.getFloat(key, defaultVal.toFloat()).toInt() }.getOrDefault(defaultVal)
        }
    }

    private fun readFloatSafe(prefs: SharedPreferences, key: String, defaultVal: Float): Float {
        return runCatching { prefs.getFloat(key, defaultVal) }.getOrElse {
            runCatching { prefs.getInt(key, defaultVal.toInt()).toFloat() }.getOrDefault(defaultVal)
        }
    }
}
