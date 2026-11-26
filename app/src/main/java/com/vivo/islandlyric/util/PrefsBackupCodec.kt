package com.vivo.islandlyric.util

import android.content.SharedPreferences
import com.vivo.islandlyric.BuildConfig
import com.vivo.islandlyric.Const
import org.json.JSONObject

/**
 * 16 项热生效配置 JSON 编解码与默认值校验工具（供设置页 SAF 导出/导入与宿主镜像共用）。
 */
object PrefsBackupCodec {

    private val ALL_16_KEYS = arrayOf(
        Const.PREF_MASTER_SWITCH,
        Const.PREF_GLOW_ENABLED,
        Const.PREF_GLOW_LEVEL,
        Const.PREF_HALO_ENABLED,
        Const.PREF_GLOW_STYLE,
        Const.PREF_GLOW_INTENSITY,
        Const.PREF_GLOW_PERIOD_MS,
        Const.PREF_GLOW_OUTER_ENABLED,
        Const.PREF_HALO_ORBIT_ENABLED,
        Const.PREF_HALO_OUTER_LEVEL,
        Const.PREF_LYRIC_TEXT_SIZE_SP,
        Const.PREF_SCROLL_AUTO,
        Const.PREF_SCROLL_SPEED_FACTOR,
        Const.PREF_KARAOKE_ENABLED,
        Const.PREF_LINE_DISSOLVE_ENABLED,
        Const.PREF_LYRIC_STYLE,
        Const.PREF_LINE_TRANSITION_MODE,
        Const.PREF_LANDSCAPE_MODE,
        Const.PREF_INTERLUDE_DOTS_ENABLED,
        Const.PREF_DUAL_LINE_LYRIC_ENABLED,
        Const.PREF_PERSISTENT_WAVE_ENABLED,
        Const.PREF_DUAL_LINE_SUB_TEXT_SIZE_SP,
        Const.PREF_LYRIC_CENTER_ALIGN,
        Const.PREF_WIDE_LYRIC_WIDTH_DP,
        Const.PREF_LEFT_CENTER_ALIGN,
        Const.PREF_ONLINE_TIMELINE_ENABLED,
        Const.PREF_LYRIC_LEFT_OFFSET_DP,
        Const.PREF_ALBUM_LEFT_PADDING_PX,
        Const.PREF_LEFT_INFO_OFFSET_DP,
        Const.PREF_LEFT_INFO_MARGIN_START_DP,
        Const.PREF_LEFT_INFO_RIGHT_INSET_DP,
        Const.PREF_RIGHT_LYRIC_RIGHT_INSET_DP,
        Const.PREF_LANDSCAPE_LYRIC_ENABLED,
        Const.PREF_LANDSCAPE_LYRIC_TOP_MARGIN_DP,
        Const.PREF_LANDSCAPE_LYRIC_OFFSET_X_DP,
        Const.PREF_LANDSCAPE_LYRIC_TEXT_SIZE_SP
    )

    /** 仅当从未初始化且 16 项业务键均未写入时，判定为重装/首启未配置默认态 */
    fun isUnconfiguredDefaultState(sp: SharedPreferences): Boolean {
        if (sp.getBoolean(Const.PREF_CONFIG_INITIALIZED, false)) return false
        for (k in ALL_16_KEYS) {
            if (sp.contains(k)) return false
        }
        return true
    }

    fun serializeFromPrefs(sp: SharedPreferences): String {
        val prefs = RuntimePrefs(
            masterEnabled = sp.getBoolean(Const.PREF_MASTER_SWITCH, true),
            glowEnabled = sp.getBoolean(Const.PREF_GLOW_ENABLED, Const.GLOW_ENABLED_DEFAULT),
            glowLevel = readIntSafe(sp, Const.PREF_GLOW_LEVEL, Const.GLOW_LEVEL_DEFAULT)
                .coerceIn(Const.GLOW_LEVEL_MIN, Const.GLOW_LEVEL_MAX),
            haloEnabled = sp.getBoolean(Const.PREF_HALO_ENABLED, Const.HALO_ENABLED_DEFAULT),
            glowStyle = readIntSafe(sp, Const.PREF_GLOW_STYLE, Const.GLOW_STYLE_DEFAULT)
                .coerceIn(Const.GLOW_STYLE_MIN, Const.GLOW_STYLE_MAX),
            glowIntensity = readIntSafe(sp, Const.PREF_GLOW_INTENSITY, Const.GLOW_INTENSITY_DEFAULT)
                .coerceIn(Const.GLOW_INTENSITY_MIN, Const.GLOW_INTENSITY_MAX),
            glowPeriodMs = when (readIntSafe(sp, Const.PREF_GLOW_PERIOD_MS, Const.GLOW_PERIOD_MS_DEFAULT)) {
                Const.GLOW_PERIOD_MS_FAST -> Const.GLOW_PERIOD_MS_FAST
                Const.GLOW_PERIOD_MS_SLOW -> Const.GLOW_PERIOD_MS_SLOW
                else -> Const.GLOW_PERIOD_MS_DEFAULT
            },
            glowOuterEnabled = sp.getBoolean(Const.PREF_GLOW_OUTER_ENABLED, Const.GLOW_OUTER_ENABLED_DEFAULT),
            glowOrbitEnabled = sp.getBoolean(Const.PREF_HALO_ORBIT_ENABLED, Const.HALO_ORBIT_ENABLED_DEFAULT),
            haloOuterLevel = readIntSafe(sp, Const.PREF_HALO_OUTER_LEVEL, Const.HALO_OUTER_LEVEL_DEFAULT)
                .coerceIn(Const.HALO_OUTER_LEVEL_MIN, Const.HALO_OUTER_LEVEL_MAX),
            lyricTextSizeSp = readFloatSafe(sp, Const.PREF_LYRIC_TEXT_SIZE_SP, Const.RIGHT_LYRIC_TEXT_SIZE_SP)
                .coerceIn(Const.RIGHT_LYRIC_TEXT_SIZE_MIN_SP, Const.RIGHT_LYRIC_TEXT_SIZE_MAX_SP),
            scrollAuto = sp.getBoolean(Const.PREF_SCROLL_AUTO, Const.SCROLL_AUTO_DEFAULT),
            scrollSpeedFactor = readFloatSafe(sp, Const.PREF_SCROLL_SPEED_FACTOR, Const.SCROLL_SPEED_FACTOR_DEFAULT)
                .coerceIn(Const.SCROLL_SPEED_FACTOR_MIN, Const.SCROLL_SPEED_FACTOR_MAX),
            karaokeEnabled = sp.getBoolean(Const.PREF_KARAOKE_ENABLED, Const.KARAOKE_ENABLED_DEFAULT),
            lineDissolveEnabled = sp.getBoolean(Const.PREF_LINE_DISSOLVE_ENABLED, Const.LINE_DISSOLVE_ENABLED_DEFAULT),
            lyricStyle = readIntSafe(sp, Const.PREF_LYRIC_STYLE, Const.LYRIC_STYLE_DEFAULT)
                .coerceIn(Const.LYRIC_STYLE_MIN, Const.LYRIC_STYLE_MAX),
            lineTransitionMode = readIntSafe(sp, Const.PREF_LINE_TRANSITION_MODE, Const.LINE_TRANSITION_MODE_DEFAULT)
                .coerceIn(Const.LINE_TRANSITION_MODE_MIN, Const.LINE_TRANSITION_MODE_MAX),
            landscapeMode = readIntSafe(sp, Const.PREF_LANDSCAPE_MODE, Const.LANDSCAPE_MODE_DEFAULT)
                .coerceIn(Const.LANDSCAPE_MODE_MIN, Const.LANDSCAPE_MODE_MAX),
            interludeDotsEnabled = sp.getBoolean(Const.PREF_INTERLUDE_DOTS_ENABLED, Const.INTERLUDE_DOTS_ENABLED_DEFAULT),
            dualLineLyricEnabled = sp.getBoolean(Const.PREF_DUAL_LINE_LYRIC_ENABLED, Const.DUAL_LINE_LYRIC_ENABLED_DEFAULT),
            persistentWaveEnabled = sp.getBoolean(Const.PREF_PERSISTENT_WAVE_ENABLED, Const.PERSISTENT_WAVE_ENABLED_DEFAULT),
            shimmerLevel = readIntSafe(sp, Const.PREF_SHIMMER_LEVEL, Const.SHIMMER_LEVEL_DEFAULT)
                .coerceIn(Const.SHIMMER_LEVEL_MIN, Const.SHIMMER_LEVEL_MAX),
            shimmerStyle = readIntSafe(sp, Const.PREF_SHIMMER_STYLE, Const.SHIMMER_STYLE_DEFAULT)
                .coerceIn(Const.SHIMMER_STYLE_MIN, Const.SHIMMER_STYLE_MAX),
            // v1.4.5-fix13 C（Quick 裁决）：旧备份缺字段时默认 true，与现行行为一致
            shimmerCountdownEnabled = sp.getBoolean(Const.PREF_SHIMMER_COUNTDOWN_ENABLED, Const.SHIMMER_COUNTDOWN_ENABLED_DEFAULT),
            islandShimmerEnabled = sp.getBoolean(Const.PREF_ISLAND_SHIMMER_ENABLED, Const.ISLAND_SHIMMER_ENABLED_DEFAULT),
            dualLineSubTextSizeSp = readFloatSafe(sp, Const.PREF_DUAL_LINE_SUB_TEXT_SIZE_SP, Const.DUAL_LINE_SUB_TEXT_SIZE_DEFAULT_SP)
                .coerceIn(Const.DUAL_LINE_SUB_MIN_SP, Const.DUAL_LINE_SUB_MAX_SP),
            lyricCenterAlign = sp.getBoolean(Const.PREF_LYRIC_CENTER_ALIGN, Const.LYRIC_CENTER_ALIGN_DEFAULT),
            wideLyricWidthDp = readIntSafe(sp, Const.PREF_WIDE_LYRIC_WIDTH_DP, Const.WIDE_LYRIC_WIDTH_DP_DEFAULT)
                .coerceIn(Const.WIDE_LYRIC_WIDTH_DP_MIN, Const.WIDE_LYRIC_WIDTH_DP_MAX),
            leftCenterAlign = sp.getBoolean(Const.PREF_LEFT_CENTER_ALIGN, Const.LEFT_CENTER_ALIGN_DEFAULT),
            onlineTimelineEnabled = sp.getBoolean(Const.PREF_ONLINE_TIMELINE_ENABLED, Const.ONLINE_TIMELINE_ENABLED_DEFAULT),
            lyricLeftOffsetDp = readFloatSafe(sp, Const.PREF_LYRIC_LEFT_OFFSET_DP, Const.LYRIC_LEFT_OFFSET_DEFAULT_DP)
                .coerceIn(Const.LYRIC_LEFT_OFFSET_MIN_DP, Const.LYRIC_LEFT_OFFSET_MAX_DP),
            albumLeftPaddingPx = readIntSafe(sp, Const.PREF_ALBUM_LEFT_PADDING_PX, Const.ALBUM_LEFT_PADDING_DEFAULT_PX)
                .coerceIn(Const.ALBUM_LEFT_PADDING_MIN_PX, Const.ALBUM_LEFT_PADDING_MAX_PX),
            leftInfoOffsetDp = readFloatSafe(sp, Const.PREF_LEFT_INFO_OFFSET_DP, Const.LEFT_INFO_OFFSET_DEFAULT_DP)
                .coerceIn(Const.LEFT_INFO_OFFSET_MIN_DP, Const.LEFT_INFO_OFFSET_MAX_DP),
            leftInfoMarginStartDp = readFloatSafe(sp, Const.PREF_LEFT_INFO_MARGIN_START_DP, Const.LEFT_INFO_MARGIN_START_DEFAULT_DP)
                .coerceIn(Const.LEFT_INFO_MARGIN_START_MIN_DP, Const.LEFT_INFO_MARGIN_START_MAX_DP),
            leftInfoRightInsetDp = readFloatSafe(sp, Const.PREF_LEFT_INFO_RIGHT_INSET_DP, Const.LEFT_INFO_RIGHT_INSET_DEFAULT_DP)
                .coerceIn(Const.LEFT_INFO_RIGHT_INSET_MIN_DP, Const.LEFT_INFO_RIGHT_INSET_MAX_DP),
            rightLyricRightInsetDp = readFloatSafe(sp, Const.PREF_RIGHT_LYRIC_RIGHT_INSET_DP, Const.RIGHT_LYRIC_RIGHT_INSET_DEFAULT_DP)
                .coerceIn(Const.RIGHT_LYRIC_RIGHT_INSET_MIN_DP, Const.RIGHT_LYRIC_RIGHT_INSET_MAX_DP),
            landscapeLyricEnabled = sp.getBoolean(Const.PREF_LANDSCAPE_LYRIC_ENABLED, Const.LANDSCAPE_LYRIC_ENABLED_DEFAULT),
            landscapeLyricTopMarginDp = readFloatSafe(sp, Const.PREF_LANDSCAPE_LYRIC_TOP_MARGIN_DP, Const.LANDSCAPE_LYRIC_TOP_MARGIN_DEFAULT_DP)
                .coerceIn(Const.LANDSCAPE_LYRIC_TOP_MARGIN_MIN_DP, Const.LANDSCAPE_LYRIC_TOP_MARGIN_MAX_DP),
            landscapeLyricOffsetXDp = readFloatSafe(sp, Const.PREF_LANDSCAPE_LYRIC_OFFSET_X_DP, Const.LANDSCAPE_LYRIC_OFFSET_X_DEFAULT_DP)
                .coerceIn(Const.LANDSCAPE_LYRIC_OFFSET_X_MIN_DP, Const.LANDSCAPE_LYRIC_OFFSET_X_MAX_DP),
            landscapeLyricTextSizeSp = readFloatSafe(sp, Const.PREF_LANDSCAPE_LYRIC_TEXT_SIZE_SP, Const.LANDSCAPE_LYRIC_TEXT_SIZE_DEFAULT_SP)
                .coerceIn(Const.LANDSCAPE_LYRIC_TEXT_SIZE_MIN_SP, Const.LANDSCAPE_LYRIC_TEXT_SIZE_MAX_SP)
        )
        return serializeRuntimePrefs(prefs)
    }

    fun serializeRuntimePrefs(prefs: RuntimePrefs): String {
        val root = JSONObject()
        root.put("schema", 1)
        root.put("appVersion", BuildConfig.VERSION_NAME)
        val data = JSONObject().apply {
            put(Const.PREF_MASTER_SWITCH, prefs.masterEnabled)
            put(Const.PREF_GLOW_ENABLED, prefs.glowEnabled)
            put(Const.PREF_GLOW_LEVEL, prefs.glowLevel)
            put(Const.PREF_HALO_ENABLED, prefs.haloEnabled)
            put(Const.PREF_GLOW_STYLE, prefs.glowStyle)
            put(Const.PREF_GLOW_INTENSITY, prefs.glowIntensity)
            put(Const.PREF_GLOW_PERIOD_MS, prefs.glowPeriodMs)
            put(Const.PREF_GLOW_OUTER_ENABLED, prefs.glowOuterEnabled)
            put(Const.PREF_HALO_ORBIT_ENABLED, prefs.glowOrbitEnabled)
            put(Const.PREF_HALO_OUTER_LEVEL, prefs.haloOuterLevel)
            put(Const.PREF_LYRIC_TEXT_SIZE_SP, prefs.lyricTextSizeSp.toDouble())
            put(Const.PREF_SCROLL_AUTO, prefs.scrollAuto)
            put(Const.PREF_SCROLL_SPEED_FACTOR, prefs.scrollSpeedFactor.toDouble())
            put(Const.PREF_KARAOKE_ENABLED, prefs.karaokeEnabled)
            put(Const.PREF_LINE_DISSOLVE_ENABLED, prefs.lineDissolveEnabled)
            put(Const.PREF_LYRIC_STYLE, prefs.lyricStyle)
            put(Const.PREF_LINE_TRANSITION_MODE, prefs.lineTransitionMode)
            put(Const.PREF_LANDSCAPE_MODE, prefs.landscapeMode)
            put(Const.PREF_INTERLUDE_DOTS_ENABLED, prefs.interludeDotsEnabled)
            put(Const.PREF_DUAL_LINE_LYRIC_ENABLED, prefs.dualLineLyricEnabled)
            put(Const.PREF_PERSISTENT_WAVE_ENABLED, prefs.persistentWaveEnabled)
            put(Const.PREF_SHIMMER_LEVEL, prefs.shimmerLevel)
            put(Const.PREF_SHIMMER_STYLE, prefs.shimmerStyle)
            put(Const.PREF_SHIMMER_COUNTDOWN_ENABLED, prefs.shimmerCountdownEnabled)
            put(Const.PREF_ISLAND_SHIMMER_ENABLED, prefs.islandShimmerEnabled)
            put(Const.PREF_DUAL_LINE_SUB_TEXT_SIZE_SP, prefs.dualLineSubTextSizeSp.toDouble())
            put(Const.PREF_LYRIC_CENTER_ALIGN, prefs.lyricCenterAlign)
            put(Const.PREF_WIDE_LYRIC_WIDTH_DP, prefs.wideLyricWidthDp)
            put(Const.PREF_LEFT_CENTER_ALIGN, prefs.leftCenterAlign)
            put(Const.PREF_ONLINE_TIMELINE_ENABLED, prefs.onlineTimelineEnabled)
            put(Const.PREF_LYRIC_LEFT_OFFSET_DP, prefs.lyricLeftOffsetDp.toDouble())
            put(Const.PREF_ALBUM_LEFT_PADDING_PX, prefs.albumLeftPaddingPx)
            put(Const.PREF_LEFT_INFO_OFFSET_DP, prefs.leftInfoOffsetDp.toDouble())
            put(Const.PREF_LEFT_INFO_MARGIN_START_DP, prefs.leftInfoMarginStartDp.toDouble())
            put(Const.PREF_LEFT_INFO_RIGHT_INSET_DP, prefs.leftInfoRightInsetDp.toDouble())
            put(Const.PREF_RIGHT_LYRIC_RIGHT_INSET_DP, prefs.rightLyricRightInsetDp.toDouble())
            put(Const.PREF_LANDSCAPE_LYRIC_ENABLED, prefs.landscapeLyricEnabled)
            put(Const.PREF_LANDSCAPE_LYRIC_TOP_MARGIN_DP, prefs.landscapeLyricTopMarginDp.toDouble())
            put(Const.PREF_LANDSCAPE_LYRIC_OFFSET_X_DP, prefs.landscapeLyricOffsetXDp.toDouble())
            put(Const.PREF_LANDSCAPE_LYRIC_TEXT_SIZE_SP, prefs.landscapeLyricTextSizeSp.toDouble())
        }
        root.put("prefs", data)
        return root.toString(2)
    }

    /**
     * 解析 JSON 并校验限幅写入 SharedPreferences；返回成功写入的参数项数，格式非法返回 -1。
     * 同时兼容顶层 `prefs` 包装格式与扁平键值格式。
     */
    fun parseAndApplyJson(jsonText: String, sp: SharedPreferences): Int {
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return -1
        val obj = root.optJSONObject("prefs") ?: root
        var applied = 0
        val ed = sp.edit()

        fun applyBool(key: String) {
            if (obj.has(key) && !obj.isNull(key)) {
                ed.putBoolean(key, obj.optBoolean(key))
                applied++
            }
        }

        fun applyInt(key: String, minVal: Int, maxVal: Int) {
            if (obj.has(key) && !obj.isNull(key)) {
                val raw = obj.optDouble(key, Double.NaN)
                if (!raw.isNaN()) {
                    ed.putInt(key, raw.toInt().coerceIn(minVal, maxVal))
                    applied++
                }
            }
        }

        fun applyFloat(key: String, minVal: Float, maxVal: Float) {
            if (obj.has(key) && !obj.isNull(key)) {
                val raw = obj.optDouble(key, Double.NaN)
                if (!raw.isNaN()) {
                    ed.putFloat(key, raw.toFloat().coerceIn(minVal, maxVal))
                    applied++
                }
            }
        }

        applyBool(Const.PREF_MASTER_SWITCH)
        applyBool(Const.PREF_GLOW_ENABLED)
        applyInt(Const.PREF_GLOW_LEVEL, Const.GLOW_LEVEL_MIN, Const.GLOW_LEVEL_MAX)
        applyBool(Const.PREF_HALO_ENABLED)
        applyInt(Const.PREF_GLOW_STYLE, Const.GLOW_STYLE_MIN, Const.GLOW_STYLE_MAX)
        applyInt(Const.PREF_GLOW_INTENSITY, Const.GLOW_INTENSITY_MIN, Const.GLOW_INTENSITY_MAX)
        if (obj.has(Const.PREF_GLOW_PERIOD_MS) && !obj.isNull(Const.PREF_GLOW_PERIOD_MS)) {
            val rawPeriod = obj.optDouble(Const.PREF_GLOW_PERIOD_MS, Double.NaN)
            if (!rawPeriod.isNaN()) {
                val snapped = when (rawPeriod.toInt()) {
                    Const.GLOW_PERIOD_MS_FAST -> Const.GLOW_PERIOD_MS_FAST
                    Const.GLOW_PERIOD_MS_SLOW -> Const.GLOW_PERIOD_MS_SLOW
                    else -> Const.GLOW_PERIOD_MS_DEFAULT
                }
                ed.putInt(Const.PREF_GLOW_PERIOD_MS, snapped)
                applied++
            }
        }
        applyBool(Const.PREF_GLOW_OUTER_ENABLED)
        applyBool(Const.PREF_HALO_ORBIT_ENABLED)
        applyInt(Const.PREF_HALO_OUTER_LEVEL, Const.HALO_OUTER_LEVEL_MIN, Const.HALO_OUTER_LEVEL_MAX)
        applyFloat(Const.PREF_LYRIC_TEXT_SIZE_SP, Const.RIGHT_LYRIC_TEXT_SIZE_MIN_SP, Const.RIGHT_LYRIC_TEXT_SIZE_MAX_SP)
        applyBool(Const.PREF_SCROLL_AUTO)
        applyFloat(Const.PREF_SCROLL_SPEED_FACTOR, Const.SCROLL_SPEED_FACTOR_MIN, Const.SCROLL_SPEED_FACTOR_MAX)
        applyBool(Const.PREF_KARAOKE_ENABLED)
        applyBool(Const.PREF_LINE_DISSOLVE_ENABLED)
        applyInt(Const.PREF_LYRIC_STYLE, Const.LYRIC_STYLE_MIN, Const.LYRIC_STYLE_MAX)
        applyInt(Const.PREF_LINE_TRANSITION_MODE, Const.LINE_TRANSITION_MODE_MIN, Const.LINE_TRANSITION_MODE_MAX)
        applyInt(Const.PREF_LANDSCAPE_MODE, Const.LANDSCAPE_MODE_MIN, Const.LANDSCAPE_MODE_MAX)
        applyBool(Const.PREF_INTERLUDE_DOTS_ENABLED)
        applyBool(Const.PREF_DUAL_LINE_LYRIC_ENABLED)
        applyBool(Const.PREF_PERSISTENT_WAVE_ENABLED)
        applyFloat(Const.PREF_DUAL_LINE_SUB_TEXT_SIZE_SP, Const.DUAL_LINE_SUB_MIN_SP, Const.DUAL_LINE_SUB_MAX_SP)
        applyBool(Const.PREF_LYRIC_CENTER_ALIGN)
        applyInt(Const.PREF_WIDE_LYRIC_WIDTH_DP, Const.WIDE_LYRIC_WIDTH_DP_MIN, Const.WIDE_LYRIC_WIDTH_DP_MAX)
        applyBool(Const.PREF_LEFT_CENTER_ALIGN)
        applyBool(Const.PREF_ONLINE_TIMELINE_ENABLED)
        applyFloat(Const.PREF_LYRIC_LEFT_OFFSET_DP, Const.LYRIC_LEFT_OFFSET_MIN_DP, Const.LYRIC_LEFT_OFFSET_MAX_DP)
        applyInt(Const.PREF_ALBUM_LEFT_PADDING_PX, Const.ALBUM_LEFT_PADDING_MIN_PX, Const.ALBUM_LEFT_PADDING_MAX_PX)
        applyFloat(Const.PREF_LEFT_INFO_OFFSET_DP, Const.LEFT_INFO_OFFSET_MIN_DP, Const.LEFT_INFO_OFFSET_MAX_DP)
        applyFloat(Const.PREF_LEFT_INFO_MARGIN_START_DP, Const.LEFT_INFO_MARGIN_START_MIN_DP, Const.LEFT_INFO_MARGIN_START_MAX_DP)
        applyFloat(Const.PREF_LEFT_INFO_RIGHT_INSET_DP, Const.LEFT_INFO_RIGHT_INSET_MIN_DP, Const.LEFT_INFO_RIGHT_INSET_MAX_DP)
        applyFloat(Const.PREF_RIGHT_LYRIC_RIGHT_INSET_DP, Const.RIGHT_LYRIC_RIGHT_INSET_MIN_DP, Const.RIGHT_LYRIC_RIGHT_INSET_MAX_DP)
        applyBool(Const.PREF_LANDSCAPE_LYRIC_ENABLED)
        applyFloat(Const.PREF_LANDSCAPE_LYRIC_TOP_MARGIN_DP, Const.LANDSCAPE_LYRIC_TOP_MARGIN_MIN_DP, Const.LANDSCAPE_LYRIC_TOP_MARGIN_MAX_DP)
        applyFloat(Const.PREF_LANDSCAPE_LYRIC_OFFSET_X_DP, Const.LANDSCAPE_LYRIC_OFFSET_X_MIN_DP, Const.LANDSCAPE_LYRIC_OFFSET_X_MAX_DP)
        applyFloat(Const.PREF_LANDSCAPE_LYRIC_TEXT_SIZE_SP, Const.LANDSCAPE_LYRIC_TEXT_SIZE_MIN_SP, Const.LANDSCAPE_LYRIC_TEXT_SIZE_MAX_SP)

        if (applied <= 0) return -1
        ed.putBoolean(Const.PREF_CONFIG_INITIALIZED, true)
        ed.apply()
        return applied
    }

    private fun readIntSafe(sp: SharedPreferences, key: String, defaultVal: Int): Int {
        return runCatching { sp.getInt(key, defaultVal) }.getOrElse {
            runCatching { sp.getFloat(key, defaultVal.toFloat()).toInt() }.getOrDefault(defaultVal)
        }
    }

    private fun readFloatSafe(sp: SharedPreferences, key: String, defaultVal: Float): Float {
        return runCatching { sp.getFloat(key, defaultVal) }.getOrElse {
            runCatching { sp.getInt(key, defaultVal.toInt()).toFloat() }.getOrDefault(defaultVal)
        }
    }
}
