package com.vivo.islandlyric.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.highcapable.yukihookapi.hook.log.YLog
import com.vivo.islandlyric.Const
import com.vivo.islandlyric.util.EventHistoryStore
import com.vivo.islandlyric.util.IslandFrameGrid
import com.vivo.islandlyric.util.LrcTimelineParser
import com.vivo.islandlyric.util.PerfStats
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 宽形态右侧自绘歌词视图（v1.4.6-b1 / vc51）：
 * - 样式 0（默认款）100% 保持 vc49 原样路径（余量必须为 0）；
 * - F1–F7：③按字宽切分逐字弹入、②双极光+ComposeShader渐隐、④相交词扫光微浮、
 *   ⑤只描字形聚光灯、⑥1.6×词宽波浪弹簧、⑦+30°色相水墨晕染、按样式动态余量与位移钳。
 */
class LyricScrollView(context: Context) : View(context) {

    private val fadeEdgePx: Float = Const.RIGHT_LYRIC_FADING_EDGE_PX
    private val density: Float = resources.displayMetrics.density

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Const.RIGHT_LYRIC_COLOR
        textAlign = Paint.Align.LEFT
    }

    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Const.RIGHT_LYRIC_COLOR
        textAlign = Paint.Align.LEFT
    }

    private val dissolvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Const.RIGHT_LYRIC_COLOR
        textAlign = Paint.Align.LEFT
    }

    private val fxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Const.RIGHT_LYRIC_COLOR
        textAlign = Paint.Align.LEFT
    }

    private val inkStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        textAlign = Paint.Align.LEFT
    }

    private val inkBlotchPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val inkBlotchDst = RectF()

    private val interludeDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var requestedTextSizeSp: Float = Const.RIGHT_LYRIC_TEXT_SIZE_SP
    private var requestedSubTextSizeSp: Float = Const.DUAL_LINE_SUB_TEXT_SIZE_DEFAULT_SP
    private var currentTextSizeSp: Float = Const.RIGHT_LYRIC_TEXT_SIZE_SP
    private var preferredWidthPx: Int = Const.WIDE_CONTENT_EXPLICIT_WIDTH_PX
    private var lyricText: String = ""
    private var nextLyricText: String = ""
    private var ellipsizedNextText: String = ""
    private var ellipsizedNextWidth: Float = 0f
    private var currentLineSeq: Long = -1L
    private var measuredTextWidth: Float = 0f

    // v1.4.0：双行预览 & 换行溶解过渡独立开关
    private var dualLineEnabled: Boolean = Const.DUAL_LINE_LYRIC_ENABLED_DEFAULT
    private var lineDissolveEnabled: Boolean = Const.LINE_DISSOLVE_ENABLED_DEFAULT

    // v1.4.0：300ms 换行溶解过渡快照（零 onDraw 堆分配）
    private var transitionStartUptimeMs: Long = 0L
    private var outgoingText: String = ""
    private var outgoingScrollX: Float = 0f
    private var outgoingTextWidth: Float = 0f
    private var incomingPromotedFromSub: Boolean = false

    // vc55 散聚（模式 2）逐簇数据：换句时一次建好（切分/前缀测量/错峰序），onDraw 只读不分配
    private class ScatterLine {
        var text: String = ""
        var count: Int = 0
        var starts: IntArray = IntArray(0)
        var ends: IntArray = IntArray(0)
        var prefixX: FloatArray = FloatArray(0)
        var widths: FloatArray = FloatArray(0)
        var centerDist: FloatArray = FloatArray(0)
        var staggerRank: IntArray = IntArray(0)
        var totalW: Float = 0f
        var staggerMs: Float = 0f
        var charDurMs: Float = 1f
        var spreadHalf: Float = 0f
        // vc56（Quick 门检）：本行自己的时间跨度——旧行用满过渡时长、新行用 min(时长,500) 绝对锚
        var durMs: Float = 1f
    }

    private val scatterOut = ScatterLine()
    private val scatterIn = ScatterLine()
    private val scatterPaint = android.text.TextPaint()
    private var scatterOutActive: Boolean = false
    /** vc55（门检 D3）：散聚旧行的染色前锋——换句瞬间捕获的旧行已唱宽度（px），与 scatterOut 同像素空间 */
    private var scatterOutFrontPx: Float = 0f
    private var scatterGatherActive: Boolean = false
    private var scatterDualAnim: Boolean = false
    // vc55（门检 M2）：过渡开始时锁定的交替布局判定，窗内改设置不许改画法（防 N+2 画上主行）
    private var transitionAltLayout: Boolean = false
    // vc55（门检 M3）：本曲是否出现过双行（无 next 的歌整首走单行散聚；出现过则末句也保持双槽高）
    private var dualSeenThisTrack: Boolean = false
    private var slotIdleWidth: Float = 0f
    // 双行交替槽位：activeSlot 指当前在唱槽（0=上槽、1=下槽）；slotTexts 为两槽当前内容
    private var activeSlot: Int = 0
    private val slotTexts: Array<String> = arrayOf("", "")
    private var slotIdleEllipsized: String = ""
    private var scatterPrevSeq: Long = 0L
    private var scatterPrevNext: String = ""
    private var lastDrawnScrollX: Float = 0f

    // KRC 逐词预计算数组 + F1 逐字（拉丁词/代理对整体）预计算数组（零 onDraw 分配）
    private var rawWordSpans: List<LrcTimelineParser.WordSpan> = emptyList()
    private var wordStartMs: LongArray = LongArray(0)
    private var wordEndMs: LongArray = LongArray(0)
    private var wordStartPx: FloatArray = FloatArray(0)
    private var wordEndPx: FloatArray = FloatArray(0)
    private var wordCharStart: IntArray = IntArray(0)
    private var wordCharEnd: IntArray = IntArray(0)
    private var wordTotalEndMs: Long = 0L

    private var charUnitStartMs: LongArray = LongArray(0)
    private var popStartActiveMs: LongArray = LongArray(0)
    private var lastPopFrontPx: Float = -1f
    private var lastPopActiveMs: Long = -1L
    private var charUnitEndMs: LongArray = LongArray(0)
    private var charUnitStartPx: FloatArray = FloatArray(0)
    private var charUnitEndPx: FloatArray = FloatArray(0)
    private var charUnitStrStart: IntArray = IntArray(0)
    private var charUnitStrEnd: IntArray = IntArray(0)
    private var charUnitWhitespace: BooleanArray = BooleanArray(0)

    private var isPlaying: Boolean = false

    /** 帧率上限（0=不限）；style!=0 时自动锁 30fps，切回 0 恢复原值 */
    var maxFps: Int = 0
    private var savedMaxFpsBeforeStyle: Int = 0
    private var hasSavedMaxFps: Boolean = false
    private var lastDrawUptimeMs: Long = 0L
    private val fpsThrottleRunnable = Runnable {
        if (visibility == VISIBLE && isPlaying) postInvalidateOnAnimation()
    }
    private var karaokeEnabled: Boolean = Const.KARAOKE_ENABLED_DEFAULT
    private var karaokeSungColor: Int = Const.KARAOKE_AMBER_FALLBACK
    private var unplayedAlpha: Int = Const.KARAOKE_UNPLAYED_ALPHA
    private var shadowEnabled: Boolean = false
    private var shadowPadV: Int = 0
    private var shadowPadH: Int = 0
    private var centerShortText: Boolean = false

    // 7 套歌词动态样式 + F7 按样式余量 + 间奏呼吸点状态 + 色板缓存
    private var lyricStyle: Int = Const.LYRIC_STYLE_DEFAULT
    private var lineTransitionMode: Int = Const.LINE_TRANSITION_MODE_DEFAULT
    // vc55 过渡控制器：换句瞬间锁定本次模式与时长（中途改设置不影响正在进行的过渡）
    private var transitionMode: Int = Const.LINE_TRANSITION_DISSOLVE
    private var transitionDurationMs: Long = Const.LINE_DISSOLVE_DURATION_MS.toLong()
    private var currentStyleHeadroomPx: Int = 0
    private var pendingHeadroomLog: Boolean = false
    private var lastLoggedHeadroomStyle: Int = -1
    private var lastLoggedHeadroomPx: Int = -1
    private var lastLoggedHeadroomViewH: Int = -1

    private var interludeDotsEnabled: Boolean = Const.INTERLUDE_DOTS_ENABLED_DEFAULT
    private var interludeCountdownBlocking: Boolean = false
    private var interludeStartUptimeMs: Long = 0L
    private var interludePausedAccumMs: Long = 0L
    private var interludePauseStartMs: Long = 0L

    private var hasPaletteColors: Boolean = false
    private val paletteColors = IntArray(3) { Const.KARAOKE_AMBER_FALLBACK }
    private val auroraResolvedColors = IntArray(3) { Const.KARAOKE_AMBER_FALLBACK }
    private val hslBuf = FloatArray(3)

    // 预建动态样式 Shader / ComposeShader / Matrix / Bitmap（零 onDraw 分配）
    private var auroraSungShader: LinearGradient? = null
    private var auroraUnplayedShader: LinearGradient? = null
    private var auroraComposeSung: ComposeShader? = null
    private var auroraComposeUnplayed: ComposeShader? = null
    private var auroraComposeMaskWidth: Int = -1
    private var auroraComposeFadeLeft: Boolean = false
    private var auroraComposeFadeRight: Boolean = false
    private var auroraGradWidth: Float = 0f
    private var auroraShaderDirty: Boolean = true
    private val auroraMatrix = Matrix()

    private var sweepShader: LinearGradient? = null
    private var sweepShaderWidth: Float = 0f
    private var sweepShaderColor: Int = 0
    private val sweepMatrix = Matrix()

    private var spotShader: RadialGradient? = null
    private var spotShaderRadius: Float = 0f
    private val spotMatrix = Matrix()
    private var spotFollowX: Float = -1f
    private var spotLastUpdateUptimeMs: Long = 0L

    private var lastActiveWordIdx: Int = -1
    private var lastWordSwitchUptimeMs: Long = 0L
    private var lastSpotKaraokePx: Float = -1f

    private var inkBlotchBitmap: Bitmap? = null

    private var lastLoggedStyle: Int = -1
    private var lastLoggedMaxFps: Int = -1

    private var scrollCycleDurationMs: Long = Const.SCROLL_DEFAULT_WINDOW_MS
    private var karaokeDurationMs: Long = Const.SCROLL_DEFAULT_WINDOW_MS

    // 绝对单调时钟锚点（无 250ms 截断漂移）
    private var lineStartUptimeMs: Long = 0L
    private var pausedAccumulatedMs: Long = 0L
    private var pauseStartedUptimeMs: Long = 0L

    private var cachedWhiteShader: LinearGradient? = null
    private var cachedSungShader: LinearGradient? = null
    private var cachedFadeMaskShader: LinearGradient? = null
    private var cachedShaderWidth: Int = -1
    private var cachedFadeLeft: Boolean = false
    private var cachedFadeRight: Boolean = false
    private var cachedSungShaderColor: Int = 0

    init {
        isClickable = false
        isFocusable = false
        isLongClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        applyEffectiveTextSizes()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    fun currentText(): String = lyricText

    fun currentTextSizePx(): Int = textPaint.textSize.roundToInt()

    fun hasDisplayContent(): Boolean = lyricText.isNotEmpty()

    private fun isDualLineActive(): Boolean = dualLineEnabled && nextLyricText.isNotEmpty()

    /** F7：按样式计算单边余量（style=0 为 0；双行一律按 DUAL_LINE_SHIFT_CAP_PX = 3.5f） */
    private fun computeStyleHeadroomPx(style: Int, dualActive: Boolean): Int {
        if (style == STYLE_DEFAULT) return 0
        val shiftCap = currentStyleShiftCapPx(style, dualActive)
        val maxScale = when (style) {
            STYLE_POP_IN -> if (dualActive) 1.08f else 1.24f
            STYLE_SPRING_WAVE -> if (dualActive) 1.07f else 1.08f
            STYLE_SWEEP, STYLE_SPOTLIGHT -> 1.05f
            else -> 1.0f
        }
        if (shiftCap <= 0f && maxScale <= 1.001f) return 0
        val fm = textPaint.fontMetrics
        val mainH = (fm.descent - fm.ascent).coerceAtLeast(1f)
        return ceil((shiftCap + mainH * (maxScale - 1f) * 0.5f).toDouble()).toInt() + 1
    }

    private fun currentStyleShiftCapPx(style: Int, dualActive: Boolean): Float {
        if (style == STYLE_DEFAULT) return 0f
        val idx = style.coerceIn(0, Const.LYRIC_STYLE_SHIFT_CAP_PX.lastIndex)
        val singleCap = Const.LYRIC_STYLE_SHIFT_CAP_PX[idx].toFloat()
        if (singleCap <= 0f) return 0f
        return if (dualActive) min(singleCap, if (style == STYLE_SPRING_WAVE) Const.DUAL_LINE_WAVE_SHIFT_CAP_PX else Const.DUAL_LINE_SHIFT_CAP_PX) else singleCap
    }

    private fun emitHeadroomLogIfNeeded(viewH: Int) {
        if (!pendingHeadroomLog) return
        pendingHeadroomLog = false
        if (lyricStyle == lastLoggedHeadroomStyle &&
            currentStyleHeadroomPx == lastLoggedHeadroomPx &&
            viewH == lastLoggedHeadroomViewH
        ) {
            return
        }
        lastLoggedHeadroomStyle = lyricStyle
        lastLoggedHeadroomPx = currentStyleHeadroomPx
        lastLoggedHeadroomViewH = viewH
        val msg = "[LYRIC_HEADROOM] style=$lyricStyle headroomPx=$currentStyleHeadroomPx viewH=$viewH"
        YLog.info(msg = msg)
        EventHistoryStore.recordDiagOnly(
            msg,
            dedupKey = "lyric_headroom_${lyricStyle}_${currentStyleHeadroomPx}_$viewH"
        )
    }

    /** vc54：换行过渡模式（0=溶解，1=柔焦），与溶解开关组合：开关关则无过渡 */
    fun setLineTransitionMode(mode: Int) {
        val clamped = mode.coerceIn(Const.LINE_TRANSITION_MODE_MIN, Const.LINE_TRANSITION_MODE_MAX)
        if (lineTransitionMode == clamped) return
        lineTransitionMode = clamped
        // vc55（门检 M1）：模式切换即时生效——清进行中过渡、重算余量、交替槽位对齐当前句/下一句、重新布局
        clearTransitionSnapshot()
        applyEffectiveTextSizes()
        currentStyleHeadroomPx = effectiveHeadroomPx(lyricStyle, isDualLineActive())
        if (isAlternateLayout()) {
            // vc55（门检 M5）：槽位归零重对齐，不留旧轮换相位
            activeSlot = 0
            slotTexts[activeSlot] = lyricText
            slotTexts[1 - activeSlot] = nextLyricText
            refreshSlotIdleText()
        }
        requestLayout()
        if (visibility == VISIBLE) invalidate()
    }

    // vc54 柔焦：预建 6 档模糊滤镜（半径按密度缩放），绘制时只查表、不逐帧分配
    private var softBlurFilters: Array<BlurMaskFilter>? = null
    private fun softBlurFilter(progress: Float): BlurMaskFilter {
        var arr = softBlurFilters
        if (arr == null) {
            val d = resources.displayMetrics.density
            arr = Array(6) { i -> BlurMaskFilter((0.8f + i * 1.1f) * d, BlurMaskFilter.Blur.NORMAL) }
            softBlurFilters = arr
        }
        val idx = (progress.coerceIn(0f, 1f) * (arr.size - 1)).roundToInt().coerceIn(0, arr.size - 1)
        return arr[idx]
    }

    // vc55 雾凝：独立预建 9 档模糊滤镜（半径几何级数 0.8→6.3dp），不碰柔焦已验收的 6 档数组；
    // tier 0（清晰）返回 null、不用极小半径代替，避免新行交接给逐字路径时闪一下
    private var fogBlurFilters: Array<BlurMaskFilter>? = null
    private fun fogBlurFilter(tierT: Float): BlurMaskFilter? {
        if (tierT <= 0.01f) return null
        var arr = fogBlurFilters
        if (arr == null) {
            val d = resources.displayMetrics.density
            arr = Array(9) { i -> BlurMaskFilter((0.8f * Math.pow(1.294, i.toDouble()).toFloat()) * d, BlurMaskFilter.Blur.NORMAL) }
            fogBlurFilters = arr
        }
        val idx = (tierT.coerceIn(0f, 1f) * arr.size).roundToInt().coerceIn(1, arr.size) - 1
        return arr[idx]
    }

    /** vc56：各模式基准时长（溶解 300 不动；柔焦/散聚 600、雾凝 800、呼吸 850——双审定版） */
    private fun transitionBaseFor(mode: Int): Long {
        return when (mode) {
            Const.LINE_TRANSITION_SOFT_FOCUS -> 600L
            Const.LINE_TRANSITION_SCATTER -> 600L
            Const.LINE_TRANSITION_FOG -> 800L
            Const.LINE_TRANSITION_BREATHE -> 850L
            else -> Const.LINE_DISSOLVE_DURATION_MS.toLong()
        }
    }

    /** vc55：本次过渡时长。模式 0/1 沿 vc54 口径（溶解 300ms、柔焦 500ms）不压缩；
     *  新模式 2/3/4 按入句时长压缩：不超过入句 45%，下限 350ms，低于下限返回 0＝硬切不取快照 */
    private fun transitionDurationFor(mode: Int, incomingLineMs: Long): Long {
        val base = transitionBaseFor(mode)
        if (mode == Const.LINE_TRANSITION_DISSOLVE || mode == Const.LINE_TRANSITION_SOFT_FOCUS) return base
        // vc55（门检 M2）：入句时长未知（<=0，普通 LRC 无时间轴）不压缩、直接用 base，否则模式 2–4 永远硬切
        if (incomingLineMs <= 0L) return base
        val cap = incomingLineMs * 45L / 100L
        if (cap >= base) return base
        if (cap < 350L) return 0L
        return cap
    }

    /** vc56：换句过渡观测（双审 E1）——只在 setLyric 换句时打一行，非帧路径、无逐帧分配 */
    private fun logTransition(mode: Int, dur: Long, hardcut: Boolean, interrupted: Boolean, incomingMs: Long, alt: Boolean) {
        val base = transitionBaseFor(mode)
        val tMsg = "[TRANSITION] mode=$mode dur=$dur base=$base compressed=${if (!hardcut && dur < base) 1 else 0} hardcut=${if (hardcut) 1 else 0} alt=${if (alt) 1 else 0} incomingMs=$incomingMs interrupted=${if (interrupted) 1 else 0}"
        YLog.info(msg = tMsg)
        EventHistoryStore.recordDiagOnly(tMsg)
    }

    /** v1.4.6-b1：设置 7 套歌词动态样式（0=默认款，1..6=极光/弹入/扫光/聚光/波浪/水墨） */
    fun setLyricStyle(style: Int) {
        val clamped = style.coerceIn(Const.LYRIC_STYLE_MIN, Const.LYRIC_STYLE_MAX)
        if (lyricStyle == clamped) return
        removeCallbacks(fpsThrottleRunnable)
        val prevStyle = lyricStyle
        lyricStyle = clamped
        popStartActiveMs = LongArray(popStartActiveMs.size) { POP_UNTRIGGERED }
        lastPopFrontPx = -1f
        lastPopActiveMs = -1L
        if (prevStyle == 0 && clamped != 0) {
            savedMaxFpsBeforeStyle = maxFps
            hasSavedMaxFps = true
            maxFps = Const.LYRIC_DYNAMIC_MAX_FPS
        } else if (clamped == 0 && hasSavedMaxFps) {
            maxFps = savedMaxFpsBeforeStyle
            hasSavedMaxFps = false
        }
        val nextHeadroom = effectiveHeadroomPx(clamped, isDualLineActive())
        pendingHeadroomLog = true
        if (nextHeadroom != currentStyleHeadroomPx) {
            currentStyleHeadroomPx = nextHeadroom
            requestLayout()
        } else if (height > 0) {
            emitHeadroomLogIfNeeded(height)
        }
        // 切样式立即清动画瞬态，不回放已唱部分
        spotFollowX = -1f
        spotLastUpdateUptimeMs = 0L
        lastActiveWordIdx = -1
        lastWordSwitchUptimeMs = 0L
        lastSpotKaraokePx = -1f
        logStyleChangeIfNeeded(clamped, maxFps)
        if (visibility == VISIBLE) {
            invalidate()
        }
    }

    private fun logStyleChangeIfNeeded(style: Int, effectiveFps: Int) {
        if (style == lastLoggedStyle && effectiveFps == lastLoggedMaxFps) return
        lastLoggedStyle = style
        lastLoggedMaxFps = effectiveFps
        val msg = "[LYRIC_STYLE] style=$style maxFps=$effectiveFps"
        YLog.info(msg = msg)
        EventHistoryStore.recordDiagOnly(msg, dedupKey = "lyric_style_${style}_$effectiveFps")
    }

    /** F2/F6：平行接收封面色板前 3 色（歌词专用 HSL L >= 0.55 钳制），无变化不失效缓存 */
    fun setPaletteColors(colors: IntArray?) {
        if (colors == null || colors.isEmpty() || isSilverFallbackPalette(colors)) {
            if (!hasPaletteColors) return
            hasPaletteColors = false
            paletteColors[0] = karaokeSungColor
            paletteColors[1] = karaokeSungColor
            paletteColors[2] = karaokeSungColor
            auroraShaderDirty = true
            if (visibility == VISIBLE && (lyricStyle == STYLE_AURORA || lyricStyle == STYLE_INK_WASH || lyricText.isEmpty())) {
                invalidate()
            }
            return
        }
        val c0 = clampLyricPaletteColorL(colors[0])
        val c1 = clampLyricPaletteColorL(colors[min(1, colors.size - 1)])
        val c2 = clampLyricPaletteColorL(colors[min(2, colors.size - 1)])
        if (hasPaletteColors && paletteColors[0] == c0 && paletteColors[1] == c1 && paletteColors[2] == c2) {
            return
        }
        hasPaletteColors = true
        paletteColors[0] = c0
        paletteColors[1] = c1
        paletteColors[2] = c2
        auroraShaderDirty = true
        if (visibility == VISIBLE && (lyricStyle == STYLE_AURORA || lyricStyle == STYLE_INK_WASH || lyricText.isEmpty())) {
            invalidate()
        }
    }

    private fun isSilverFallbackPalette(colors: IntArray): Boolean {
        if (colors.size < Const.GLOW_SILVER_PALETTE.size) return false
        for (i in Const.GLOW_SILVER_PALETTE.indices) {
            if (colors[i] != Const.GLOW_SILVER_PALETTE[i]) return false
        }
        return true
    }

    private fun argbToHsl(argb: Int, out: FloatArray) {
        val r = Color.red(argb) / 255f
        val g = Color.green(argb) / 255f
        val b = Color.blue(argb) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val d = maxC - minC
        val l = (maxC + minC) * 0.5f
        val s = if (d < 1e-5f) 0f else (d / (1f - abs(2f * l - 1f))).coerceIn(0f, 1f)
        val h = when {
            d < 1e-5f -> 0f
            maxC == r -> ((g - b) / d + (if (g < b) 6f else 0f)) * 60f
            maxC == g -> ((b - r) / d + 2f) * 60f
            else -> ((r - g) / d + 4f) * 60f
        }
        out[0] = (h % 360f + 360f) % 360f
        out[1] = s
        out[2] = l
    }

    private fun hslToArgb(hDeg: Float, s: Float, l: Float): Int {
        val h = ((hDeg % 360f) + 360f) % 360f
        val sc = s.coerceIn(0f, 1f)
        val lc = l.coerceIn(0f, 1f)
        val c = (1f - abs(2f * lc - 1f)) * sc
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = lc - c * 0.5f
        val rf: Float
        val gf: Float
        val bf: Float
        when {
            h < 60f -> { rf = c; gf = x; bf = 0f }
            h < 120f -> { rf = x; gf = c; bf = 0f }
            h < 180f -> { rf = 0f; gf = c; bf = x }
            h < 240f -> { rf = 0f; gf = x; bf = c }
            h < 300f -> { rf = x; gf = 0f; bf = c }
            else -> { rf = c; gf = 0f; bf = x }
        }
        val ri = ((rf + m) * 255f).roundToInt().coerceIn(0, 255)
        val gi = ((gf + m) * 255f).roundToInt().coerceIn(0, 255)
        val bi = ((bf + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    private fun clampLyricPaletteColorL(argb: Int): Int {
        argbToHsl(argb, hslBuf)
        val l = max(hslBuf[2], Const.LYRIC_PALETTE_MIN_L)
        return hslToArgb(hslBuf[0], hslBuf[1], l)
    }

    private fun rotateHueAndClampL(argb: Int, deltaDeg: Float): Int {
        argbToHsl(argb, hslBuf)
        val l = max(hslBuf[2], Const.LYRIC_PALETTE_MIN_L)
        return hslToArgb(hslBuf[0] + deltaDeg, hslBuf[1], l)
    }

    /** 间奏呼吸点开关（默认开），禁止通过切 View.visibility 隐藏三点 */
    fun setInterludeDotsEnabled(enabled: Boolean) {
        if (interludeDotsEnabled == enabled) return
        interludeDotsEnabled = enabled
        if (!enabled && lyricText.isEmpty()) {
            removeCallbacks(fpsThrottleRunnable)
        } else if (enabled && lyricText.isEmpty() && interludeStartUptimeMs == 0L) {
            val now = SystemClock.uptimeMillis()
            interludeStartUptimeMs = now
            interludePausedAccumMs = 0L
            interludePauseStartMs = if (isPlaying) 0L else now
        }
        if (visibility == VISIBLE && lyricText.isEmpty()) {
            invalidate()
        }
    }

    /** 间奏/前奏倒计时是否正在占位（与呼吸点互斥） */
    fun setInterludeCountdownBlocking(blocking: Boolean) {
        if (interludeCountdownBlocking == blocking) return
        interludeCountdownBlocking = blocking
        if (blocking && lyricText.isEmpty()) {
            removeCallbacks(fpsThrottleRunnable)
        } else if (!blocking && lyricText.isEmpty() && interludeDotsEnabled && interludeStartUptimeMs == 0L) {
            val now = SystemClock.uptimeMillis()
            interludeStartUptimeMs = now
            interludePausedAccumMs = 0L
            interludePauseStartMs = if (isPlaying) 0L else now
        }
        if (visibility == VISIBLE && lyricText.isEmpty()) {
            invalidate()
        }
    }

    fun setSubTextSizeSp(sp: Float) {
        val clamped = sp.coerceIn(Const.DUAL_LINE_SUB_MIN_SP, Const.DUAL_LINE_SUB_MAX_SP)
        if (abs(clamped - requestedSubTextSizeSp) < 0.05f) return
        requestedSubTextSizeSp = clamped
        applyEffectiveTextSizes()
        rebuildEllipsizedNextText()
        invalidateShaderCache()
        requestLayout()
        if (visibility == VISIBLE) {
            invalidate()
        }
    }

    fun setDualLineEnabled(enabled: Boolean) {
        if (dualLineEnabled == enabled) return
        dualLineEnabled = enabled
        // vc55（门检 M1 同类）：双行开关切换同样清过渡并对齐交替槽位，防半截过渡跨布局续画
        clearTransitionSnapshot()
        if (isAlternateLayout()) {
            activeSlot = 0
            slotTexts[activeSlot] = lyricText
            slotTexts[1 - activeSlot] = nextLyricText
            refreshSlotIdleText()
        }
        applyEffectiveTextSizes()
        currentStyleHeadroomPx = effectiveHeadroomPx(lyricStyle, isDualLineActive())
        rebuildEllipsizedNextText()
        invalidateShaderCache()
        requestLayout()
        if (visibility == VISIBLE) {
            invalidate()
        }
    }

    fun setLineDissolveEnabled(enabled: Boolean) {
        if (lineDissolveEnabled == enabled) return
        lineDissolveEnabled = enabled
        if (!enabled) {
            clearTransitionSnapshot()
        }
        // vc55（确认轮 R2）：isAlternateLayout 依赖本开关——切换时同 setDualLineEnabled 口径即时生效（余量重算/槽位对齐/重新布局），不等下一次布局
        applyEffectiveTextSizes()
        currentStyleHeadroomPx = effectiveHeadroomPx(lyricStyle, isDualLineActive())
        if (isAlternateLayout()) {
            activeSlot = 0
            slotTexts[activeSlot] = lyricText
            slotTexts[1 - activeSlot] = nextLyricText
            refreshSlotIdleText()
        }
        requestLayout()
        if (visibility == VISIBLE) {
            invalidate()
        }
    }

    fun setPreferredWidthPx(widthPx: Int) {
        val clamped = widthPx.coerceAtLeast(60)
        if (preferredWidthPx == clamped) return
        preferredWidthPx = clamped
        rebuildEllipsizedNextText()
        invalidateShaderCache()
        requestLayout()
        invalidate()
    }

    fun setTextSizeSp(sp: Float) {
        val clamped = sp.coerceIn(Const.LANDSCAPE_LYRIC_TEXT_SIZE_MIN_SP, Const.LANDSCAPE_LYRIC_TEXT_SIZE_MAX_SP)
        if (abs(clamped - requestedTextSizeSp) < 0.05f) return
        requestedTextSizeSp = clamped
        applyEffectiveTextSizes()
        currentStyleHeadroomPx = effectiveHeadroomPx(lyricStyle, isDualLineActive())
        rebuildEllipsizedNextText()
        invalidateShaderCache()
        requestLayout()
        invalidate()
    }

    fun setKaraokeEnabled(enabled: Boolean) {
        if (karaokeEnabled == enabled) return
        karaokeEnabled = enabled
        if (visibility == VISIBLE) {
            postInvalidateOnAnimation()
        }
    }

    fun setKaraokeSungColor(color: Int) {
        val opaque = color or 0xFF000000.toInt()
        if (karaokeSungColor == opaque) return
        karaokeSungColor = opaque
        cachedSungShader = null
        auroraShaderDirty = true
        sweepShaderColor = 0
        if (visibility == VISIBLE && karaokeEnabled) {
            invalidate()
        }
    }

    fun setUnplayedAlpha(alpha: Int) {
        val clamped = alpha.coerceIn(80, 255)
        if (unplayedAlpha == clamped) return
        unplayedAlpha = clamped
        if (visibility == VISIBLE) {
            invalidate()
        }
    }

    fun setCenterShortText(enabled: Boolean) {
        if (centerShortText == enabled) return
        centerShortText = enabled
        if (visibility == VISIBLE) {
            invalidate()
        }
    }

    fun setShadowEnabled(enabled: Boolean) {
        if (shadowEnabled == enabled) return
        shadowEnabled = enabled
        if (enabled) {
            val radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2.0f, resources.displayMetrics)
            val dy = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 0.6f, resources.displayMetrics)
            val extraV = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2.2f, resources.displayMetrics)
            val extraH = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.5f, resources.displayMetrics)
            shadowPadV = ceil((radius + dy + extraV).toDouble()).toInt()
            shadowPadH = ceil((radius + extraH).toDouble()).toInt()
            textPaint.setShadowLayer(radius, 0f, dy, 0x99000000.toInt())
        } else {
            shadowPadV = 0
            shadowPadH = 0
            textPaint.clearShadowLayer()
        }
        requestLayout()
        if (visibility == VISIBLE) {
            invalidate()
        }
    }

    fun setLyric(
        text: String,
        scrollDurationMs: Long,
        karaokeDurationMs: Long,
        playing: Boolean,
        initialElapsedMs: Long = 0L,
        wordSpans: List<LrcTimelineParser.WordSpan> = emptyList(),
        lineSeq: Long = 0L,
        nextLyric: String = ""
    ) {
        val seqChanged = lineSeq > 0L && lineSeq != currentLineSeq
        val isNewLine = text != lyricText || seqChanged
        val cleanNext = nextLyric.trim()
        val nextChanged = cleanNext != nextLyricText
        val wordsChanged = wordSpans !== rawWordSpans && (wordSpans.size != rawWordSpans.size || wordSpans != rawWordSpans)
        val now = SystemClock.uptimeMillis()

        val prevText = lyricText
        val prevNext = nextLyricText
        val prevWidth = measuredTextWidth
        val prevScrollX = lastDrawnScrollX
        // vc55（门检 D3）：旧行前锋在状态切换前捕获（切换后算的是新行的），散聚旧行按它盖章染色，与正常路径最后一帧一致
        val prevFrontPx = computeKaraokeSungPx(currentElapsedActiveMs())
        val wasDual = isDualLineActive()

        lyricText = text
        nextLyricText = cleanNext
        if (lineSeq > 0L) {
            currentLineSeq = lineSeq
        }
        scrollCycleDurationMs = scrollDurationMs.coerceAtLeast(600L)
        this.karaokeDurationMs = karaokeDurationMs.coerceAtLeast(1000L)

        val nowDual = isDualLineActive()
        // vc55（门检 M3/M4）：本曲双行出现标记，按歌锁定、换歌由渲染器 onTrackSwitched 明确清零（间奏空行不清，防曲中高度跳变）
        if (nowDual) dualSeenThisTrack = true
        if (wasDual != nowDual) {
            applyEffectiveTextSizes()
            currentStyleHeadroomPx = effectiveHeadroomPx(lyricStyle, nowDual)
        }

        if (text.isEmpty() && interludeStartUptimeMs == 0L) {
            interludeStartUptimeMs = now
            interludePausedAccumMs = 0L
            interludePauseStartMs = if (playing) 0L else now
        } else if (text.isNotEmpty()) {
            interludeStartUptimeMs = 0L
            interludePausedAccumMs = 0L
            interludePauseStartMs = 0L
        }

        if (isNewLine) {
            // vc55（门检建议 1）：连续性记账走所有换句路径（硬切/溶解关/暂停换句也记），否则下一句必判接不上
            val prevSeqSeen = scatterPrevSeq
            val prevNextSeen = scatterPrevNext
            scatterPrevSeq = lineSeq
            scatterPrevNext = cleanNext
            if (lineDissolveEnabled && playing && prevText.isNotEmpty() && text.isNotEmpty() && visibility == VISIBLE) {
                // vc55（确认轮建议 2）：传原始参数——0＝时长未知→base 不压缩；真实短句才走 45% 压缩（字段 coerce 到 >=1000 会把未知误判成 1000）
                val dur = transitionDurationFor(lineTransitionMode, karaokeDurationMs)
                // vc56：上一过渡未完即被本句打断（观测用，须在覆盖/清快照前算）
                val wasInterrupted = transitionStartUptimeMs > 0L && (now - transitionStartUptimeMs) < transitionDurationMs
                if (dur <= 0L) {
                    // vc55：入句太短，新模式硬切（不取快照）
                    clearTransitionSnapshot()
                    logTransition(lineTransitionMode, 0L, true, wasInterrupted, karaokeDurationMs, isAlternateLayout())
                } else {
                    outgoingText = prevText
                    outgoingTextWidth = prevWidth
                    outgoingScrollX = prevScrollX
                    incomingPromotedFromSub = wasDual && prevNext.isNotEmpty() && prevNext == text
                    transitionMode = lineTransitionMode
                    transitionDurationMs = dur
                    transitionStartUptimeMs = now
                    // vc55（门检 S3）：雾凝 9 档滤镜在锁定时预建，不留到 onDraw 首帧
                    if (lineTransitionMode == Const.LINE_TRANSITION_FOG) fogBlurFilter(0.5f)
                    // vc55（门检 M2）：交替判定随过渡一并锁定
                    transitionAltLayout = isAlternateLayout()
                    if (lineTransitionMode == Const.LINE_TRANSITION_SCATTER) {
                        scatterOutFrontPx = prevFrontPx
                        setupScatterSnapshot(prevText, text, cleanNext, lineSeq, wordSpans.isNotEmpty(), prevSeqSeen, prevNextSeen)
                    }
                    // vc56：快照建完才打（读回落后的最终 mode/dur）；建快照途中被清＝按硬切记
                    if (transitionStartUptimeMs > 0L) {
                        logTransition(transitionMode, transitionDurationMs, false, wasInterrupted, karaokeDurationMs, transitionAltLayout)
                    } else {
                        logTransition(lineTransitionMode, 0L, true, wasInterrupted, karaokeDurationMs, isAlternateLayout())
                    }
                }
            } else {
                clearTransitionSnapshot()
            }
            if (isAlternateLayout() && !scatterDualAnim && transitionStartUptimeMs == 0L) {
                // vc55：无过渡快照时（溶解关/未播放）槽位内容直接对齐当前行与下一行
                slotTexts[activeSlot] = text
                slotTexts[1 - activeSlot] = cleanNext
                refreshSlotIdleText()
            }
            measuredTextWidth = if (text.isNotEmpty()) textPaint.measureText(text) else 0f
            lastDrawnScrollX = 0f
            spotFollowX = -1f
            spotLastUpdateUptimeMs = 0L
            lastActiveWordIdx = -1
            lastWordSwitchUptimeMs = 0L
            lastSpotKaraokePx = -1f
            rebuildWordPixelSpans(wordSpans)
            rebuildEllipsizedNextText()
            isPlaying = playing
            lineStartUptimeMs = now - initialElapsedMs.coerceAtLeast(0L)
            pausedAccumulatedMs = 0L
            pauseStartedUptimeMs = if (playing) 0L else now
            invalidateShaderCache()
            requestLayout()
            if (visibility == VISIBLE) {
                postInvalidateOnAnimation()
            }
        } else {
            if (wasDual != nowDual) {
                measuredTextWidth = if (text.isNotEmpty()) textPaint.measureText(text) else 0f
                invalidateShaderCache()
            }
            if (wordsChanged || wasDual != nowDual) {
                rebuildWordPixelSpans(wordSpans)
            }
            if (nextChanged || wasDual != nowDual) {
                rebuildEllipsizedNextText()
                if (isAlternateLayout()) {
                    // vc55：同行内 next 变化时空闲槽预告同步更新
                    slotTexts[1 - activeSlot] = cleanNext
                    refreshSlotIdleText()
                }
                requestLayout()
            }
            setPlaying(playing)
            if ((playing || wordsChanged || nextChanged || wasDual != nowDual) && visibility == VISIBLE) {
                postInvalidateOnAnimation()
            }
        }
    }

    /** vc55（确认轮 G1 定版）：换歌信号——lineSeq 是会话内单调递增的（日志实证单曲循环 286→287），视图侧猜不到换歌，
     * 由渲染器 clearForTrackSwitch 明确调用：清本曲双行标记与进行中过渡，高度随之回落单行口径 */
    fun onTrackSwitched() {
        dualSeenThisTrack = false
        clearTransitionSnapshot()
        // vc55（确认轮连带）：字号口径含交替布局——换歌清标记后立即重算字号，不等下一次单双行切换才回落
        applyEffectiveTextSizes()
        refreshSlotIdleText()
        requestLayout()
    }

    private fun clearTransitionSnapshot() {
        transitionStartUptimeMs = 0L
        outgoingText = ""
        outgoingScrollX = 0f
        outgoingTextWidth = 0f
        incomingPromotedFromSub = false
        scatterOutActive = false
        scatterGatherActive = false
        scatterDualAnim = false
    }

    /** vc55：交替布局＝散聚模式＋双行开＋溶解开＋本曲出现过双行（末句保持双槽高；整首无 next 的歌走单行，门检 M3×2） */
    private fun isAlternateLayout(): Boolean {
        return lineTransitionMode == Const.LINE_TRANSITION_SCATTER && dualLineEnabled && lineDissolveEnabled &&
            (isDualLineActive() || dualSeenThisTrack)
    }

    /** vc55（门检 M2）：过渡窗内读锁定值、窗外读实时值 */
    private fun activeAltLayout(): Boolean {
        return if (transitionStartUptimeMs > 0L) transitionAltLayout else isAlternateLayout()
    }

    /** vc55：交替模式余量减半（对表定案：全额约 115px 超胶囊 112px；样式位移/缩放上限同步减半） */
    private fun effectiveHeadroomPx(style: Int, dual: Boolean): Int {
        return headroomFor(style, dual, isAlternateLayout())
    }

    private fun headroomFor(style: Int, dual: Boolean, alt: Boolean): Int {
        val base = computeStyleHeadroomPx(style, dual)
        return if (alt) base / 2 else base
    }

    /**
     * vc55 散聚建簇（setLyric 时调用，非逐帧）：切分与 rebuildWordPixelSpans 同口径——
     * ASCII 字母数字串合并、代理对不拆；非 ASCII 段走 BreakIterator 字符边界，防 ZWJ/组合符拆碎（Hark）。
     * 簇起点用前缀 measureText（防交接 kerning 跳，Hark）；错峰序按到行中心距离（中心先动，Quick）。
     */
    private fun buildScatterLine(line: ScatterLine, text: String, durMs: Long) {
        line.durMs = durMs.toFloat().coerceAtLeast(1f)
        line.text = text
        if (text.isEmpty()) {
            line.count = 0
            line.totalW = 0f
            return
        }
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        val bi = java.text.BreakIterator.getCharacterInstance()
        bi.setText(text)
        var segStart = bi.first()
        var boundary = bi.next()
        // vc55（门检 M1）：簇初始为空——旧写法把首段预装进簇、循环又处理一遍，非 ASCII/非空白首字会被 flush 两次（首字画两遍）
        var clusterStart = 0
        var clusterEnd = 0
        fun flush() {
            if (clusterEnd > clusterStart) {
                starts.add(clusterStart)
                ends.add(clusterEnd)
            }
        }
        while (boundary != java.text.BreakIterator.DONE) {
            val segLen = boundary - segStart
            val segFirst = text[segStart]
            val merge = when {
                clusterEnd <= clusterStart -> false
                // 空白并入前一簇，不单独占错峰位
                segLen == 1 && segFirst.isWhitespace() -> true
                // ASCII 字母数字串：与前一簇同为 ASCII 字母数字时合并
                segLen == 1 && isAsciiAlphaNum(segFirst) &&
                    clusterEnd - clusterStart >= 1 && isAsciiAlphaNum(text[clusterEnd - 1]) -> true
                else -> false
            }
            if (merge) {
                clusterEnd = boundary
            } else {
                flush()
                clusterStart = segStart
                clusterEnd = boundary
            }
            segStart = boundary
            boundary = bi.next()
        }
        flush()
        val n = starts.size
        line.count = n
        line.starts = starts.toIntArray()
        line.ends = ends.toIntArray()
        line.prefixX = FloatArray(n)
        line.widths = FloatArray(n)
        line.centerDist = FloatArray(n)
        line.staggerRank = IntArray(n)
        line.totalW = textPaint.measureText(text)
        for (i in 0 until n) {
            line.prefixX[i] = textPaint.measureText(text, 0, line.starts[i])
            line.widths[i] = textPaint.measureText(text, line.starts[i], line.ends[i])
            line.centerDist[i] = (line.prefixX[i] + line.widths[i] * 0.5f) - line.totalW * 0.5f
        }
        // 错峰序：|中心距| 升序（中心先动、两头后动）；同距按下标
        val order = (0 until n).sortedWith(compareBy({ kotlin.math.abs(line.centerDist[it]) }, { it }))
        for (rank in 0 until n) {
            line.staggerRank[order[rank]] = rank
        }
        // 时间拆法（双审定案）：按段算，seg=0.55D、charMin=0.6seg，保证 charDur ≥ charMin 不为负
        // vc56：D 取本行自己的 durMs（旧行满时长、新行 min(时长,500)），不再共用过渡总时长
        val seg = 0.55f * line.durMs
        val charMin = 0.6f * seg
        line.staggerMs = if (n > 1) minOf(15f, (seg - charMin) / (n - 1).toFloat()).coerceAtLeast(0f) else 0f
        line.charDurMs = (seg - (n - 1) * line.staggerMs).coerceAtLeast(1f)
        val availW = (width - paddingLeft - paddingRight).toFloat()
        line.spreadHalf = minOf(textPaint.textSize, ((availW - line.totalW) * 0.5f).coerceAtLeast(0f))
    }

    /**
     * vc55 散聚快照（setLyric 换句、过渡字段锁定后调用）：回落与槽位都在此刻定死，不在 onDraw 逐帧判。
     * @param newWordData 新行是否带逐字时间数据（样式③冲突判定用）
     */
    private fun setupScatterSnapshot(
        prevText: String,
        newText: String,
        newNext: String,
        seq: Long,
        newWordData: Boolean,
        prevSeqSeen: Long,
        prevNextSeen: String
    ) {
        scatterOutActive = false
        scatterGatherActive = false
        scatterDualAnim = false
        val stylePop = lyricStyle == STYLE_POP_IN && newWordData
        if (isAlternateLayout()) {
            // 双行交替：接上才轮换（seq 连续且新行==上次预告的 next）；否则硬切重分配（Quick＋Hark 定案）
            val continuous = seq > 0L && seq == prevSeqSeen + 1L &&
                prevNextSeen.isNotEmpty() && newText == prevNextSeen
            if (continuous) {
                activeSlot = 1 - activeSlot
                slotTexts[activeSlot] = newText
                slotTexts[1 - activeSlot] = newNext
                // 空闲槽：刚唱完的 N 散开 → N+2 聚拢（数据来源 Hark 必改：入用 newNext 不是 newText）
                // ③逐字出现的冲突只在正在唱的那一行，空闲槽照常散聚（门检建议 3）
                buildScatterLine(scatterOut, prevText, transitionDurationMs)
                // vc55（门检 D1＋确认轮建议 3）：逐槽回落——N 超宽（原为滚动行）在空闲槽不做动画（其布局从未在该槽出现过，
                // 散开/淡出都会先跳位），该槽直接换内容；聚拢侧照常
                val availW = (width - paddingLeft - paddingRight).toFloat()
                val outWide = availW > 1f && scatterOut.totalW > availW + 1f
                if (outWide) scatterOut.spreadHalf = 0f
                refreshSlotIdleText()
                // vc55（门检 D1）：聚拢用主行尺寸省略版，不用未省略全文（防右侧被裁、结束跳成省略版）
                buildScatterLine(scatterIn, slotIdleEllipsized.ifEmpty { newNext }, minOf(transitionDurationMs, 500L))
                scatterOutActive = scatterOut.count > 0 && !outWide
                scatterGatherActive = scatterIn.count > 0
                scatterDualAnim = scatterOutActive || scatterGatherActive
                if (!scatterDualAnim) clearTransitionSnapshot()
            } else {
                slotTexts[activeSlot] = newText
                slotTexts[1 - activeSlot] = newNext
                // vc55（门检 S1）：硬切重分配不清快照会空跑 500ms 的 30fps 帧
                clearTransitionSnapshot()
            }
        } else {
            // 单行：旧行或新行任一超宽（需滚动）→ 锁定时回落溶解（含其 300ms 时长）
            val availW = (width - paddingLeft - paddingRight).toFloat()
            val outW = textPaint.measureText(prevText)
            val inW = textPaint.measureText(newText)
            if (availW > 1f && (outW > availW + 1f || inW > availW + 1f)) {
                transitionMode = Const.LINE_TRANSITION_DISSOLVE
                transitionDurationMs = transitionDurationFor(Const.LINE_TRANSITION_DISSOLVE, karaokeDurationMs)
                return
            }
            buildScatterLine(scatterOut, prevText, transitionDurationMs)
            buildScatterLine(scatterIn, newText, minOf(transitionDurationMs, 500L))
            scatterOutActive = scatterOut.count > 0
            // ③逐字出现有数据时只散不聚：新行从第一帧走③自己的路径（Quick 必改）
            scatterGatherActive = scatterIn.count > 0 && !stylePop
        }
        refreshSlotIdleText()
    }

    /** vc55：空闲槽静态文本（主行字号省略版，交替模式用）；宽度在此缓存，onDraw 不再逐帧测量（门检建议 5） */
    private fun refreshSlotIdleText() {
        if (!isAlternateLayout()) {
            slotIdleEllipsized = ""
            slotIdleWidth = 0f
            return
        }
        val raw = slotTexts[1 - activeSlot]
        val availW = (width - paddingLeft - paddingRight).toFloat()
        if (raw.isEmpty() || availW <= 1f) {
            slotIdleEllipsized = raw
            slotIdleWidth = if (raw.isEmpty()) 0f else textPaint.measureText(raw)
            return
        }
        if (textPaint.measureText(raw) <= availW) {
            slotIdleEllipsized = raw
            slotIdleWidth = textPaint.measureText(raw)
            return
        }
        val ellipsis = "…"
        var lo = 0
        var hi = raw.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (textPaint.measureText(raw.substring(0, mid) + ellipsis) <= availW) lo = mid else hi = mid - 1
        }
        slotIdleEllipsized = if (lo > 0) raw.substring(0, lo) + ellipsis else ellipsis
        slotIdleWidth = textPaint.measureText(slotIdleEllipsized)
    }

    /**
     * vc55 散聚逐簇绘制（onDraw 内调用，零分配）：旧行 ease-in 散开淡出、新行 ease-out 聚拢淡入；
     * 逐簇按前锋位置盖章染色（整簇亮/暗、无簇内渐变）；展开逐侧封顶（左对齐锚点，Quick）。
     */
    private fun drawScatterLine(
        canvas: Canvas,
        line: ScatterLine,
        baseline: Float,
        transElapsedMs: Long,
        isOut: Boolean,
        frontPx: Float,
        sungColor: Int,
        unsungColor: Int,
        baseAlpha: Float,
        availW: Float,
        // vc55（门检 D3）：未唱簇的 alpha 因子——与正常路径 unplayedAlpha 对齐，防交接瞬间未唱部分跳亮/跳暗（双行聚拢预告传 1f）
        unsungAlphaFactor: Float = 1f
    ) {
        if (line.count == 0 || line.totalW <= 0f) return
        // vc56：偏置与窗口都按本行 durMs（新行 0.45×min(时长,500) 起聚、旧行 0.55×满时长散完）
        val durF = line.durMs.coerceAtLeast(1f)
        val localMs = if (isOut) {
            transElapsedMs.toFloat()
        } else {
            transElapsedMs.toFloat() - 0.45f * durF
        }
        if (!isOut && localMs <= 0f) return
        if (isOut && localMs >= 0.55f * durF) return
        val startX = if (centerShortText) {
            paddingLeft + (availW - line.totalW) * 0.5f
        } else {
            paddingLeft.toFloat()
        }
        val leftRoom = (startX - paddingLeft).coerceAtLeast(0f)
        val rightRoom = (availW - (startX - paddingLeft + line.totalW)).coerceAtLeast(0f)
        val halfW = (line.totalW * 0.5f).coerceAtLeast(1f)
        scatterPaint.set(textPaint)
        scatterPaint.shader = null
        scatterPaint.maskFilter = null
        for (i in 0 until line.count) {
            val q = ((localMs - line.staggerRank[i] * line.staggerMs) / line.charDurMs).coerceIn(0f, 1f)
            val eased: Float
            val alphaF: Float
            val spreadF: Float
            val dy: Float
            if (isOut) {
                eased = q * q // ease-in（加速离开，Hark 规格）
                alphaF = 1f - eased
                spreadF = eased
                dy = -3f * eased // 原始像素（规格口径 −3px；交替半余量约 4px 内）
            } else {
                val u = 1f - q
                eased = 1f - u * u // ease-out
                alphaF = eased
                spreadF = 1f - eased
                dy = 3f * (1f - eased)
            }
            if (alphaF <= 0.01f) continue
            val dist = line.centerDist[i]
            val sideCap = if (dist < 0f) leftRoom else rightRoom
            val mag = minOf(line.spreadHalf, sideCap) * (kotlin.math.abs(dist) / halfW) * spreadF
            val offX = if (dist < 0f) -mag else mag
            val sung = line.prefixX[i] + line.widths[i] <= frontPx + 0.5f
            scatterPaint.color = if (sung) sungColor else unsungColor
            val clusterAlphaF = if (sung) alphaF else alphaF * unsungAlphaFactor
            scatterPaint.alpha = (255f * baseAlpha * clusterAlphaF).roundToInt().coerceIn(0, 255)
            canvas.drawText(line.text, line.starts[i], line.ends[i], startX + line.prefixX[i] + offX, baseline + dy, scatterPaint)
        }
    }

    private fun isAsciiAlphaNum(c: Char): Boolean {
        return (c in '0'..'9') || (c in 'A'..'Z') || (c in 'a'..'z')
    }

    private fun rebuildWordPixelSpans(spans: List<LrcTimelineParser.WordSpan>) {
        rawWordSpans = spans
        if (spans.isEmpty() || measuredTextWidth <= 0f) {
            wordStartMs = LongArray(0)
            wordEndMs = LongArray(0)
            wordStartPx = FloatArray(0)
            wordEndPx = FloatArray(0)
            wordCharStart = IntArray(0)
            wordCharEnd = IntArray(0)
            wordTotalEndMs = 0L
            charUnitStartMs = LongArray(0)
            popStartActiveMs = LongArray(0)
            lastPopFrontPx = -1f
            lastPopActiveMs = -1L
            charUnitEndMs = LongArray(0)
            charUnitStartPx = FloatArray(0)
            charUnitEndPx = FloatArray(0)
            charUnitStrStart = IntArray(0)
            charUnitStrEnd = IntArray(0)
            charUnitWhitespace = BooleanArray(0)
            return
        }
        val n = spans.size
        val sMs = LongArray(n)
        val eMs = LongArray(n)
        val sPx = FloatArray(n)
        val ePx = FloatArray(n)
        val cStart = IntArray(n)
        val cEnd = IntArray(n)
        var accPx = 0f
        var maxEnd = 0L
        var charCursor = 0
        for (i in 0 until n) {
            val sp = spans[i]
            val start = sp.startOffsetMs.coerceAtLeast(0L)
            val end = start + sp.durationMs.coerceAtLeast(1L)
            sMs[i] = start
            eMs[i] = end
            if (end > maxEnd) maxEnd = end
            val w = textPaint.measureText(sp.text)
            sPx[i] = accPx
            accPx += w
            ePx[i] = accPx

            val wt = sp.text
            val idx = if (wt.isNotEmpty()) lyricText.indexOf(wt, charCursor) else charCursor
            if (idx >= 0 && idx + wt.length <= lyricText.length) {
                cStart[i] = idx
                cEnd[i] = idx + wt.length
                charCursor = cEnd[i]
            } else {
                cStart[i] = charCursor.coerceAtMost(lyricText.length)
                cEnd[i] = (charCursor + wt.length).coerceAtMost(lyricText.length)
                charCursor = cEnd[i]
            }
        }
        if (accPx > 0.5f) {
            val scale = measuredTextWidth / accPx
            for (i in 0 until n) {
                sPx[i] *= scale
                ePx[i] *= scale
            }
        }
        wordStartMs = sMs
        wordEndMs = eMs
        wordStartPx = sPx
        wordEndPx = ePx
        wordCharStart = cStart
        wordCharEnd = cEnd
        wordTotalEndMs = maxEnd

        // F1 ③：预建逐字（连续拉丁字母/数字串作一单位、代理对作一单位）时间与像素数组
        val cap = lyricText.length.coerceAtLeast(n)
        val tmpStartMs = LongArray(cap)
        val tmpEndMs = LongArray(cap)
        val tmpStartPx = FloatArray(cap)
        val tmpEndPx = FloatArray(cap)
        val tmpStrStart = IntArray(cap)
        val tmpStrEnd = IntArray(cap)
        val tmpWs = BooleanArray(cap)
        var unitCount = 0

        for (i in 0 until n) {
            val cs = cStart[i]
            val ce = cEnd[i]
            if (ce <= cs || unitCount >= cap) continue
            val spanX0 = sPx[i]
            val spanX1 = ePx[i]
            val spanW = spanX1 - spanX0
            val spanStart = sMs[i]
            val spanEnd = eMs[i]
            val spanDur = (spanEnd - spanStart).coerceAtLeast(1L)
            val rawSpanW = textPaint.measureText(lyricText, cs, ce)
            val widthScale = if (rawSpanW > 0.01f && spanW > 0.01f) (spanW / rawSpanW) else 1f

            val spanUnitFirstIdx = unitCount
            var pos = cs
            var localAccPx = 0f
            while (pos < ce && unitCount < cap) {
                val uStart = pos
                val ch = lyricText[pos]
                pos = when {
                    isAsciiAlphaNum(ch) -> {
                        var p = pos + 1
                        while (p < ce && isAsciiAlphaNum(lyricText[p])) p++
                        p
                    }
                    Character.isHighSurrogate(ch) && pos + 1 < ce && Character.isLowSurrogate(lyricText[pos + 1]) -> pos + 2
                    else -> pos + 1
                }
                val uEnd = pos
                val uRawW = textPaint.measureText(lyricText, uStart, uEnd)
                val uPx0 = spanX0 + localAccPx * widthScale
                localAccPx += uRawW
                val uPx1 = spanX0 + localAccPx * widthScale

                var allWs = true
                for (k in uStart until uEnd) {
                    if (!lyricText[k].isWhitespace()) {
                        allWs = false
                        break
                    }
                }
                val uStartMs = if (spanW > 0.01f) {
                    spanStart + ((spanDur.toFloat() * ((uPx0 - spanX0) / spanW)).roundToInt().toLong().coerceIn(0L, spanDur))
                } else {
                    spanStart
                }
                tmpStrStart[unitCount] = uStart
                tmpStrEnd[unitCount] = uEnd
                tmpStartPx[unitCount] = uPx0
                tmpEndPx[unitCount] = uPx1
                tmpStartMs[unitCount] = uStartMs
                tmpWs[unitCount] = allWs
                unitCount++
            }
            for (u in spanUnitFirstIdx until unitCount) {
                tmpEndMs[u] = if (u + 1 < unitCount) {
                    tmpStartMs[u + 1].coerceAtLeast(tmpStartMs[u] + 1L)
                } else {
                    spanEnd.coerceAtLeast(tmpStartMs[u] + 1L)
                }
            }
        }

        charUnitStartMs = tmpStartMs.copyOf(unitCount)
        charUnitEndMs = tmpEndMs.copyOf(unitCount)
        charUnitStartPx = tmpStartPx.copyOf(unitCount)
        charUnitEndPx = tmpEndPx.copyOf(unitCount)
        charUnitStrStart = tmpStrStart.copyOf(unitCount)
        charUnitStrEnd = tmpStrEnd.copyOf(unitCount)
        charUnitWhitespace = tmpWs.copyOf(unitCount)
        popStartActiveMs = LongArray(unitCount) { POP_UNTRIGGERED }
        lastPopFrontPx = -1f
        lastPopActiveMs = -1L
    }

    private fun rebuildEllipsizedNextText() {
        if (!isDualLineActive()) {
            ellipsizedNextText = ""
            ellipsizedNextWidth = 0f
            return
        }
        val maxW = ((if (width > 0) width else preferredWidthPx) - paddingLeft - paddingRight)
            .toFloat()
            .coerceAtLeast(40f)
        val raw = nextLyricText
        val fullW = subTextPaint.measureText(raw)
        if (fullW <= maxW) {
            ellipsizedNextText = raw
            ellipsizedNextWidth = fullW
            return
        }
        val ellipsis = "…"
        val ellW = subTextPaint.measureText(ellipsis)
        val budget = (maxW - ellW).coerceAtLeast(8f)
        var lo = 0
        var hi = raw.length
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (subTextPaint.measureText(raw, 0, mid) <= budget) {
                lo = mid
            } else {
                hi = mid - 1
            }
        }
        ellipsizedNextText = if (lo > 0) raw.substring(0, lo) + ellipsis else ellipsis
        ellipsizedNextWidth = subTextPaint.measureText(ellipsizedNextText)
    }

    fun setPlaying(playing: Boolean) {
        if (isPlaying == playing) return
        val now = SystemClock.uptimeMillis()
        if (!playing) {
            removeCallbacks(fpsThrottleRunnable)
            if (pauseStartedUptimeMs == 0L) {
                pauseStartedUptimeMs = now
            }
            if (interludePauseStartMs == 0L && interludeStartUptimeMs > 0L) {
                interludePauseStartMs = now
            }
            // 暂停态立刻收口过渡，保证暂停态零重绘
            clearTransitionSnapshot()
        } else {
            if (pauseStartedUptimeMs > 0L) {
                pausedAccumulatedMs += (now - pauseStartedUptimeMs).coerceAtLeast(0L)
                pauseStartedUptimeMs = 0L
            }
            if (interludePauseStartMs > 0L) {
                interludePausedAccumMs += (now - interludePauseStartMs).coerceAtLeast(0L)
                interludePauseStartMs = 0L
            }
        }
        isPlaying = playing
        if (visibility == VISIBLE) {
            postInvalidateOnAnimation()
        }
    }

    fun clearAndHide() {
        removeCallbacks(fpsThrottleRunnable)
        dualSeenThisTrack = false
        lyricText = ""
        nextLyricText = ""
        ellipsizedNextText = ""
        ellipsizedNextWidth = 0f
        currentLineSeq = -1L
        measuredTextWidth = 0f
        lastDrawnScrollX = 0f
        spotFollowX = -1f
        spotLastUpdateUptimeMs = 0L
        lastActiveWordIdx = -1
        lastWordSwitchUptimeMs = 0L
        lastSpotKaraokePx = -1f
        interludeStartUptimeMs = 0L
        interludePausedAccumMs = 0L
        interludePauseStartMs = 0L
        clearTransitionSnapshot()
        rebuildWordPixelSpans(emptyList())
        lineStartUptimeMs = 0L
        pausedAccumulatedMs = 0L
        pauseStartedUptimeMs = 0L
        invalidateShaderCache()
        if (visibility != GONE) {
            visibility = GONE
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(fpsThrottleRunnable)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = textPaint.fontMetrics
        val mainHeight = ceil((fm.descent - fm.ascent).toDouble()).toInt()
        val dualActive = isDualLineActive()
        // vc55（门检 M2）：过渡窗内高度与余量也读锁定值，窗内改设置不改尺寸
        val altNow = activeAltLayout()
        val headroom = headroomFor(lyricStyle, dualActive, altNow)
        currentStyleHeadroomPx = headroom
        val padTotal = headroom * 2
        val desiredHeight = if (altNow) {
            // vc55 交替布局：两槽同主行高（余量已在 effectiveHeadroomPx 减半）；next 为空也保持此高度（Hark 末句口径）
            val gapPx = (1.5f * density).roundToInt().coerceAtLeast(1)
            mainHeight + gapPx + mainHeight + paddingTop + paddingBottom + shadowPadV * 2 + padTotal
        } else if (dualActive) {
            val subFm = subTextPaint.fontMetrics
            val subHeight = ceil((subFm.descent - subFm.ascent).toDouble()).toInt()
            val gapPx = (1.5f * density).roundToInt().coerceAtLeast(1)
            mainHeight + gapPx + subHeight + paddingTop + paddingBottom + shadowPadV * 2 + padTotal
        } else {
            mainHeight + paddingTop + paddingBottom + shadowPadV * 2 + padTotal
        }

        val w = resolveSize(preferredWidthPx, widthMeasureSpec)
        val h = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) {
            rebuildEllipsizedNextText()
            refreshSlotIdleText()
            invalidateShaderCache()
            auroraShaderDirty = true
        }
        if (pendingHeadroomLog && h > 0) {
            emitHeadroomLogIfNeeded(h)
        }
    }

    override fun onDraw(canvas: Canvas) {
        // vc54 保险：每帧先清四支画笔的柔焦滤镜，防任何路径把滤镜带过夜
        textPaint.maskFilter = null
        fxPaint.maskFilter = null
        inkStrokePaint.maskFilter = null
        dissolvePaint.maskFilter = null
        if (visibility != VISIBLE) return
        val availW = (width - paddingLeft - paddingRight).toFloat()
        val availH = (height - paddingTop - paddingBottom).toFloat()
        if (availW <= 1f || availH <= 1f) return

        // 间奏呼吸点原子分支：仅当无可唱文本、开关开启且倒计时未占位时绘制
        if (lyricText.isEmpty()) {
            if (interludeDotsEnabled && !interludeCountdownBlocking) {
                val now = SystemClock.uptimeMillis()
                lastDrawUptimeMs = now
                PerfStats.lyricFrames.incrementAndGet()
                drawInterludeBreathingDots(canvas, availW, availH, now)
                if (isPlaying) {
                    val delay = IslandFrameGrid.delayToNextSlot(now, slotStep = 3)
                    removeCallbacks(fpsThrottleRunnable)
                    postDelayed(fpsThrottleRunnable, delay)
                }
            }
            return
        }

        val now = SystemClock.uptimeMillis()
        lastDrawUptimeMs = now
        PerfStats.lyricFrames.incrementAndGet()

        val dualActive = isDualLineActive()
        val fm = textPaint.fontMetrics
        val mainHeight = fm.descent - fm.ascent
        val mainBaseline: Float
        val subBaseline: Float
        var idleBaseline = 0f
        val altLayout = activeAltLayout()
        if (altLayout) {
            // vc55 交替布局：两槽同主行高，在唱槽走正常主行路径、空闲槽在另一槽（基线按 activeSlot 交换）
            val gapPx = 1.5f * density
            val blockH = mainHeight + gapPx + mainHeight
            val blockTop = paddingTop + ((availH - blockH) * 0.5f).coerceAtLeast(0f)
            val topBaseline = blockTop - fm.ascent
            val bottomBaseline = blockTop + mainHeight + gapPx - fm.ascent
            mainBaseline = if (activeSlot == 0) topBaseline else bottomBaseline
            idleBaseline = if (activeSlot == 0) bottomBaseline else topBaseline
            subBaseline = idleBaseline
        } else if (dualActive) {
            val subFm = subTextPaint.fontMetrics
            val subHeight = subFm.descent - subFm.ascent
            val gapPx = 1.5f * density
            val blockH = mainHeight + gapPx + subHeight
            val blockTop = paddingTop + ((availH - blockH) * 0.5f).coerceAtLeast(0f)
            mainBaseline = blockTop - fm.ascent
            subBaseline = blockTop + mainHeight + gapPx - subFm.ascent
        } else {
            mainBaseline = paddingTop + (availH - mainHeight) * 0.5f - fm.ascent
            subBaseline = mainBaseline
        }

        // 计算换行过渡进度（时长由换句时锁定的 transitionDurationMs 决定；暂停态不推进、结束即清理快照）
        var inDissolve = false
        var dissolveEased = 1f
        var transRawT = 1f
        var transElapsedMs = 0L
        if (lineDissolveEnabled && transitionStartUptimeMs > 0L && isPlaying) {
            val elapsedTrans = (now - transitionStartUptimeMs).coerceAtLeast(0L)
            transElapsedMs = elapsedTrans
            val rawT = (elapsedTrans.toFloat() / transitionDurationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
            if (rawT >= 1f) {
                clearTransitionSnapshot()
            } else {
                inDissolve = true
                transRawT = rawT
                val inv = 1f - rawT
                dissolveEased = 1f - inv * inv * inv
            }
        } else if (transitionStartUptimeMs > 0L) {
            clearTransitionSnapshot()
        }

        // vc54 柔焦换句：本次过渡锁定模式=1 且正处过渡期时走柔焦路径（旧行糊化淡出、新行由糊变清）
        val softFocus = transitionMode == Const.LINE_TRANSITION_SOFT_FOCUS && inDissolve
        // vc55 雾凝/呼吸交融（散聚在下方单独分支处理，不进本段的整行参数）
        val fogMode = transitionMode == Const.LINE_TRANSITION_FOG && inDissolve
        val breatheMode = transitionMode == Const.LINE_TRANSITION_BREATHE && inDissolve
        // vc55 散聚：单行聚拢接管主行绘制、旧行逐簇散开；双行交替时在唱槽走正常路径、空闲槽在第 3 段动画
        val scatterMode = transitionMode == Const.LINE_TRANSITION_SCATTER && inDissolve
        val scatterGatherNow = scatterMode && scatterGatherActive && !altLayout
        // vc55（门检 M4）：逐字变色关闭时散聚不用已唱琥珀色（旧行前锋恒满宽会整行闪琥珀），新旧同用常色
        val scatterSungColor = if (karaokeEnabled) karaokeSungColor else Const.RIGHT_LYRIC_COLOR
        // 雾凝时间轴：旧行仍按相对进度退场（0–60% 糊化 ease-in，alpha 相对段淡出）；
        // vc56（双审定版）：新行入场改绝对时间锚（transElapsedMs）——120ms 起，alpha 在 min(350,dur) 满、
        // 模糊在 min(500,dur) 清完，压缩窗结束时恰好 alpha=1/tier=0、交接零跳变，不许拖慢歌词出现
        val fogOutTier = if (fogMode) {
            val p = (transRawT / 0.6f).coerceIn(0f, 1f)
            p * p
        } else {
            0f
        }
        val fogOutAlpha = if (fogMode) {
            (1f - ((transRawT - 0.214f) / 0.5f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        } else {
            0f
        }
        val fogInTier = if (fogMode) {
            val tierSpan = (minOf(500L, transitionDurationMs) - 120L).coerceAtLeast(1L).toFloat()
            val p = ((transElapsedMs - 120L).toFloat() / tierSpan).coerceIn(0f, 1f)
            val e = 1f - (1f - p) * (1f - p)
            1f - e
        } else {
            0f
        }
        val fogInAlpha = if (fogMode) {
            val alphaSpan = (minOf(350L, transitionDurationMs) - 120L).coerceAtLeast(1L).toFloat()
            val p = ((transElapsedMs - 120L).toFloat() / alphaSpan).coerceIn(0f, 1f)
            1f - (1f - p) * (1f - p)
        } else {
            0f
        }

        val clipLeft = paddingLeft.toFloat()
        val clipTop = if (shadowEnabled) 0f else paddingTop.toFloat()
        val clipRight = (width - paddingRight).toFloat()
        val clipBottom = if (shadowEnabled) height.toFloat() else (height - paddingBottom).toFloat()

        // 1. 溶解中的旧主行不套任何歌词动态样式，保持 vc49 原样（vc55：散聚模式走逐字分支、不进本段）
        if (inDissolve && outgoingText.isNotEmpty() && transitionMode != Const.LINE_TRANSITION_SCATTER) {
            val outAlphaFactor = ((1f - dissolveEased) * (1f - dissolveEased)).coerceIn(0f, 1f)
            if ((if (fogMode) fogOutAlpha else outAlphaFactor) > 0.01f) {
                val outDy = -5.5f * density * dissolveEased
                val outSpread = 1.8f * density * dissolveEased
                val outOverflow = outgoingTextWidth - availW
                val outDrawX = if (outOverflow <= 1f) {
                    if (centerShortText) {
                        paddingLeft + (availW - outgoingTextWidth) * 0.5f
                    } else {
                        paddingLeft.toFloat()
                    }
                } else {
                    paddingLeft - outgoingScrollX
                }
                dissolvePaint.textSize = textPaint.textSize
                dissolvePaint.color = Const.RIGHT_LYRIC_COLOR
                dissolvePaint.shader = null
                val scOut = canvas.save()
                canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)
                if (softFocus) {
                    dissolvePaint.maskFilter = softBlurFilter(dissolveEased)
                    dissolvePaint.alpha = (255f * outAlphaFactor).roundToInt().coerceIn(0, 255)
                    canvas.drawText(outgoingText, outDrawX, mainBaseline, dissolvePaint)
                    dissolvePaint.maskFilter = null
                } else if (fogMode) {
                    // vc55 雾凝旧行：独立 9 档滤镜糊化＋alpha 窗淡出＋向中心微收（整行绘制、不逐字）
                    dissolvePaint.maskFilter = fogBlurFilter(fogOutTier)
                    dissolvePaint.alpha = (255f * fogOutAlpha).roundToInt().coerceIn(0, 255)
                    val fogScale = 1f - 0.01f * (transRawT / 0.6f).coerceIn(0f, 1f)
                    val scFog = canvas.save()
                    canvas.scale(fogScale, fogScale, outDrawX + outgoingTextWidth * 0.5f, mainBaseline - mainHeight * 0.35f)
                    canvas.drawText(outgoingText, outDrawX, mainBaseline, dissolvePaint)
                    canvas.restoreToCount(scFog)
                    dissolvePaint.maskFilter = null
                } else if (breatheMode) {
                    // vc55 呼吸交融旧行：原位淡出＋微缩，无位移无模糊无重影
                    dissolvePaint.alpha = (255f * outAlphaFactor).roundToInt().coerceIn(0, 255)
                    val brScale = 1f - 0.02f * dissolveEased
                    val scBr = canvas.save()
                    canvas.scale(brScale, brScale, outDrawX + outgoingTextWidth * 0.5f, mainBaseline - mainHeight * 0.35f)
                    canvas.drawText(outgoingText, outDrawX, mainBaseline, dissolvePaint)
                    canvas.restoreToCount(scBr)
                } else {
                    val ghostAlpha = (255f * outAlphaFactor * 0.34f).roundToInt().coerceIn(0, 255)
                    val coreAlpha = (255f * outAlphaFactor * 0.66f).roundToInt().coerceIn(0, 255)
                    if (ghostAlpha > 0) {
                        dissolvePaint.alpha = ghostAlpha
                        canvas.drawText(outgoingText, outDrawX - outSpread, mainBaseline + outDy, dissolvePaint)
                        canvas.drawText(outgoingText, outDrawX + outSpread, mainBaseline + outDy, dissolvePaint)
                    }
                    if (coreAlpha > 0) {
                        dissolvePaint.alpha = coreAlpha
                        canvas.drawText(outgoingText, outDrawX, mainBaseline + outDy, dissolvePaint)
                    }
                }
                canvas.restoreToCount(scOut)
            }
        }

        // vc55 散聚单行旧行：逐簇 ease-in 散开淡出（双行交替的空闲槽动画在第 3 段）
        if (scatterMode && scatterOutActive && !altLayout) {
            val scScatter = canvas.save()
            canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)
            drawScatterLine(
                canvas, scatterOut, mainBaseline, transElapsedMs, isOut = true,
                frontPx = scatterOutFrontPx, sungColor = scatterSungColor, unsungColor = Const.RIGHT_LYRIC_COLOR,
                baseAlpha = 1f, availW = availW,
                unsungAlphaFactor = if (karaokeEnabled) unplayedAlpha / 255f else 1f
            )
            canvas.restoreToCount(scScatter)
        }

        // 2. 计算当前主行的滚动与染色参数
        val overflow = measuredTextWidth - availW
        val elapsedActiveMs = currentElapsedActiveMs()
        val inAlphaScale = if (scatterMode) 1f else if (softFocus) (0.3f + 0.7f * dissolveEased) else if (fogMode) fogInAlpha.coerceIn(0f, 1f) else if (inDissolve) dissolveEased.coerceIn(0.05f, 1f) else 1f
        val inDy = if (softFocus || fogMode || breatheMode || scatterMode) 0f else if (inDissolve) {
            if (incomingPromotedFromSub && dualActive) {
                (subBaseline - mainBaseline) * (1f - dissolveEased)
            } else {
                5.5f * density * (1f - dissolveEased)
            }
        } else {
            0f
        }
        val inScale = if (softFocus || fogMode || scatterMode) 1f else if (breatheMode) {
            // vc55 呼吸交融新行：1.02→1，以行中心为轴；满宽行按可用宽度钳制增量、结束必须恰好回 1
            val clampFactor = if (measuredTextWidth > 1f) {
                (((availW / measuredTextWidth) - 1f) / 0.02f).coerceIn(0f, 1f)
            } else {
                1f
            }
            1f + 0.02f * (1f - dissolveEased) * clampFactor
        } else if (inDissolve) (0.92f + 0.08f * dissolveEased) else 1f
        val inSpread = if (fogMode || breatheMode || scatterMode) 0f else if (inDissolve) (1.2f * density * (1f - dissolveEased)) else 0f

        var needMoreFrames = inDissolve
        if (softFocus) {
            val inBlur = softBlurFilter(1f - dissolveEased)
            textPaint.maskFilter = inBlur
            fxPaint.maskFilter = inBlur
            inkStrokePaint.maskFilter = inBlur
        }
        if (fogMode) {
            // vc55 雾凝新行：独立档位由糊变清（tier 0 时 fogBlurFilter 返回 null＝清晰）
            val fIn = fogBlurFilter(fogInTier)
            textPaint.maskFilter = fIn
            fxPaint.maskFilter = fIn
            inkStrokePaint.maskFilter = fIn
        }

        val mainSc = canvas.save()
        if (inDissolve) {
            val pivotX = paddingLeft + availW * 0.5f
            canvas.translate(0f, inDy)
            canvas.scale(inScale, inScale, pivotX, mainBaseline - mainHeight * 0.35f)
        }

        val drawX: Float
        val combinedAlpha: Float
        val fadeLeft: Boolean
        val fadeRight: Boolean
        val cycleFinished: Boolean

        if (overflow <= 1f) {
            lastDrawnScrollX = 0f
            drawX = if (centerShortText) {
                paddingLeft + (availW - measuredTextWidth) * 0.5f
            } else {
                paddingLeft.toFloat()
            }
            combinedAlpha = inAlphaScale
            fadeLeft = false
            fadeRight = false
            cycleFinished = true
        } else {
            val scrollX: Float
            val textAlpha: Float
            val useWordDrivenScroll = wordStartMs.isNotEmpty()
            if (useWordDrivenScroll) {
                val sungPx = computeKaraokeSungPx(elapsedActiveMs)
                scrollX = (sungPx - availW * 0.42f).coerceIn(0f, overflow)
                textAlpha = 1f
                cycleFinished = isKaraokeFinished(elapsedActiveMs)
            } else {
                val budget = scrollCycleDurationMs.coerceAtLeast(600L)
                val startHoldMs: Long
                val endHoldMs: Long
                val fadeBackMs: Long
                val scrollMs: Long
                if (budget >= 3200L) {
                    startHoldMs = Const.SCROLL_START_HOLD_MS
                    endHoldMs = Const.SCROLL_END_HOLD_MS
                    fadeBackMs = Const.SCROLL_FADE_BACK_MS
                    scrollMs = (budget - startHoldMs - endHoldMs - fadeBackMs).coerceAtLeast(450L)
                } else if (budget >= 1600L) {
                    startHoldMs = (budget * 0.10f).toLong().coerceIn(100L, 260L)
                    endHoldMs = (budget * 0.14f).toLong().coerceIn(150L, 420L)
                    fadeBackMs = (budget * 0.08f).toLong().coerceIn(100L, 220L)
                    scrollMs = (budget - startHoldMs - endHoldMs - fadeBackMs).coerceAtLeast(450L)
                } else {
                    startHoldMs = (budget * 0.15f).toLong().coerceAtLeast(80L)
                    endHoldMs = (budget * 0.20f).toLong().coerceAtLeast(100L)
                    fadeBackMs = (budget * 0.10f).toLong().coerceAtLeast(80L)
                    scrollMs = (budget - startHoldMs - endHoldMs - fadeBackMs).coerceAtLeast(300L)
                }

                val t1 = startHoldMs
                val t2 = t1 + scrollMs
                val t3 = t2 + endHoldMs
                val t4 = t3 + fadeBackMs

                when {
                    elapsedActiveMs < t1 -> {
                        scrollX = 0f
                        textAlpha = 1f
                        cycleFinished = false
                    }
                    elapsedActiveMs < t2 -> {
                        val rawT = ((elapsedActiveMs - t1).toFloat() / scrollMs.toFloat()).coerceIn(0f, 1f)
                        val eased = easeInOutCompressed(rawT, scrollMs)
                        scrollX = overflow * eased
                        textAlpha = 1f
                        cycleFinished = false
                    }
                    elapsedActiveMs < t3 -> {
                        scrollX = overflow
                        textAlpha = 1f
                        cycleFinished = false
                    }
                    elapsedActiveMs < t4 -> {
                        val ft = ((elapsedActiveMs - t3).toFloat() / fadeBackMs.toFloat()).coerceIn(0f, 1f)
                        if (ft < 0.5f) {
                            scrollX = overflow
                            textAlpha = (1f - ft * 2f).coerceIn(0f, 1f)
                        } else {
                            scrollX = 0f
                            textAlpha = ((ft - 0.5f) * 2f).coerceIn(0f, 1f)
                        }
                        cycleFinished = false
                    }
                    else -> {
                        scrollX = 0f
                        textAlpha = 1f
                        cycleFinished = true
                    }
                }
            }

            lastDrawnScrollX = scrollX
            combinedAlpha = (textAlpha * inAlphaScale).coerceIn(0f, 1f)
            fadeLeft = scrollX > 0.5f
            fadeRight = scrollX < overflow - 0.5f
            drawX = paddingLeft - scrollX
        }

        if (overflow <= 1f && inSpread > 0.25f && !softFocus && !(lyricStyle == STYLE_POP_IN && charUnitStartMs.isNotEmpty())) {
            dissolvePaint.textSize = textPaint.textSize
            dissolvePaint.color = if (karaokeEnabled) karaokeSungColor else Const.RIGHT_LYRIC_COLOR
            dissolvePaint.shader = null
            dissolvePaint.alpha = (255f * inAlphaScale * 0.28f).roundToInt().coerceIn(0, 255)
            canvas.drawText(lyricText, drawX - inSpread, mainBaseline, dissolvePaint)
            canvas.drawText(lyricText, drawX + inSpread, mainBaseline, dissolvePaint)
        }

        val activeStyle = lyricStyle
        if (scatterGatherNow) {
            // vc55 散聚新行（单行）：逐簇 ease-out 聚拢、前锋按簇盖章染色，不走整行样式路径
            val scIn = canvas.save()
            canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)
            drawScatterLine(
                canvas, scatterIn, mainBaseline, transElapsedMs, isOut = false,
                frontPx = computeKaraokeSungPx(elapsedActiveMs), sungColor = scatterSungColor, unsungColor = Const.RIGHT_LYRIC_COLOR,
                baseAlpha = 1f, availW = availW,
                unsungAlphaFactor = if (karaokeEnabled) unplayedAlpha / 255f else 1f
            )
            canvas.restoreToCount(scIn)
        } else if (activeStyle == STYLE_DEFAULT) {
            // ① 默认款：100% 保持 vc49 原样
            drawDefaultMainLine(
                canvas = canvas,
                overflow = overflow,
                drawX = drawX,
                mainBaseline = mainBaseline,
                mainHeight = mainHeight,
                clipLeft = clipLeft,
                clipTop = clipTop,
                clipRight = clipRight,
                clipBottom = clipBottom,
                combinedAlpha = combinedAlpha,
                fadeLeft = fadeLeft,
                fadeRight = fadeRight,
                elapsedActiveMs = elapsedActiveMs,
                cycleFinished = cycleFinished
            ).let { if (it) needMoreFrames = true }
        } else {
            val styleNeed = drawStyledMainLine(
                canvas = canvas,
                style = activeStyle,
                dualActive = dualActive,
                overflow = overflow,
                drawX = drawX,
                mainBaseline = mainBaseline,
                mainHeight = mainHeight,
                availW = availW,
                clipLeft = clipLeft,
                clipTop = clipTop,
                clipRight = clipRight,
                clipBottom = clipBottom,
                combinedAlpha = combinedAlpha,
                fadeLeft = fadeLeft,
                fadeRight = fadeRight,
                elapsedActiveMs = elapsedActiveMs,
                cycleFinished = cycleFinished,
                nowUptimeMs = now
            )
            if (styleNeed) needMoreFrames = true
        }

        canvas.restoreToCount(mainSc)
        if (softFocus || fogMode) {
            textPaint.maskFilter = null
            fxPaint.maskFilter = null
            inkStrokePaint.maskFilter = null
        }

        // 3. 双行模式次行（下一句灰字预览，不套动态样式、切句轻微上浮淡入）
        if (altLayout) {
            // vc55 交替布局空闲槽：过渡窗内逐簇散开（N）＋聚拢（N+2）；平时静态预告（主行字号、副行配色）
            if (scatterMode && scatterDualAnim) {
                val scAlt = canvas.save()
                canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)
                // vc55（确认轮建议 3）：出/入两侧各自按 active 标志画——超宽 N 的出侧已停用（不动画、内容直接换）
                if (scatterOutActive) {
                    drawScatterLine(
                        canvas, scatterOut, idleBaseline, transElapsedMs, isOut = true,
                        frontPx = scatterOutFrontPx, sungColor = scatterSungColor, unsungColor = Const.RIGHT_LYRIC_COLOR,
                        baseAlpha = 1f, availW = availW,
                        unsungAlphaFactor = if (karaokeEnabled) unplayedAlpha / 255f else 1f
                    )
                }
                if (scatterGatherActive) {
                    drawScatterLine(
                        canvas, scatterIn, idleBaseline, transElapsedMs, isOut = false,
                        frontPx = 0f, sungColor = scatterSungColor, unsungColor = Const.RIGHT_LYRIC_COLOR,
                        baseAlpha = Const.DUAL_LINE_SUB_ALPHA / 255f, availW = availW
                    )
                }
                canvas.restoreToCount(scAlt)
            } else if (slotIdleEllipsized.isNotEmpty()) {
                scatterPaint.set(textPaint)
                scatterPaint.shader = null
                scatterPaint.maskFilter = null
                scatterPaint.color = Const.RIGHT_LYRIC_COLOR
                scatterPaint.alpha = Const.DUAL_LINE_SUB_ALPHA.coerceIn(0, 255)
                val idleW = slotIdleWidth
                val idleX = if (centerShortText) {
                    paddingLeft + (availW - idleW) * 0.5f
                } else {
                    paddingLeft.toFloat()
                }
                val scIdle = canvas.save()
                canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)
                canvas.drawText(slotIdleEllipsized, idleX, idleBaseline, scatterPaint)
                canvas.restoreToCount(scIdle)
            }
        } else if (dualActive && ellipsizedNextText.isNotEmpty()) {
            val subDrawX = if (centerShortText) {
                paddingLeft + (availW - ellipsizedNextWidth) * 0.5f
            } else {
                paddingLeft.toFloat()
            }
            val subDy = if (inDissolve) 4.0f * density * (1f - dissolveEased) else 0f
            val subAlpha = (Const.DUAL_LINE_SUB_ALPHA * inAlphaScale).roundToInt().coerceIn(0, 255)
            subTextPaint.color = Const.RIGHT_LYRIC_COLOR
            subTextPaint.alpha = subAlpha
            val scSub = canvas.save()
            canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)
            canvas.drawText(ellipsizedNextText, subDrawX, subBaseline + subDy, subTextPaint)
            canvas.restoreToCount(scSub)
        }

        if (isPlaying && needMoreFrames && visibility == VISIBLE) {
            if (maxFps > 0) {
                val delay = if (lyricStyle != STYLE_DEFAULT && maxFps == Const.LYRIC_DYNAMIC_MAX_FPS) {
                    IslandFrameGrid.delayToNextSlot(now)
                } else {
                    val minFrameMs = 1000L / maxFps
                    val since = now - lastDrawUptimeMs
                    if (since >= minFrameMs) 0L else minFrameMs - since
                }
                if (delay <= 0L) {
                    postInvalidateOnAnimation()
                } else {
                    removeCallbacks(fpsThrottleRunnable)
                    postDelayed(fpsThrottleRunnable, delay)
                }
            } else if (transitionStartUptimeMs > 0L && transitionMode != Const.LINE_TRANSITION_DISSOLVE) {
                // vc55（Quick 必改 1）：样式 0 不限帧时，过渡模式 1–4 在过渡窗内也要锁 30fps，
                // 否则 500/700ms 的糊化会按屏幕刷新率（最高 120 次/s）跑；模式 0 溶解除外、保持默认款零改动
                val sinceTrans = now - lastDrawUptimeMs
                if (sinceTrans >= 33L) {
                    postInvalidateOnAnimation()
                } else {
                    removeCallbacks(fpsThrottleRunnable)
                    postDelayed(fpsThrottleRunnable, 33L - sinceTrans)
                }
            } else {
                postInvalidateOnAnimation()
            }
        }
    }

    private fun drawInterludeBreathingDots(
        canvas: Canvas,
        availW: Float,
        availH: Float,
        nowUptimeMs: Long
    ) {
        if (interludeStartUptimeMs == 0L) {
            interludeStartUptimeMs = nowUptimeMs
            interludePausedAccumMs = 0L
            interludePauseStartMs = if (isPlaying) 0L else nowUptimeMs
        }
        val ongoingPause = if (!isPlaying && interludePauseStartMs > 0L) {
            (nowUptimeMs - interludePauseStartMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val elapsedMs = (nowUptimeMs - interludeStartUptimeMs - interludePausedAccumMs - ongoingPause).coerceAtLeast(0L)

        val fm = textPaint.fontMetrics
        val textH = (fm.descent - fm.ascent).coerceAtLeast(12f)
        val dotR = 0.25f * textH
        val spacing = 0.76f * textPaint.textSize
        val centerX = paddingLeft + availW * 0.5f
        val centerY = paddingTop + availH * 0.5f

        interludeDotPaint.color = if (hasPaletteColors) paletteColors[0] else Color.WHITE
        for (i in 0 until 3) {
            val phase = elapsedMs.toFloat() / Const.INTERLUDE_DOT_PHASE_DIVISOR_MS - Const.INTERLUDE_DOT_PHASE_STEP_RAD * i
            val wave = 0.5f + 0.5f * sin(phase)
            val r = dotR * (0.72f + 0.42f * wave)
            val alpha = ((0.28f + 0.72f * wave) * 255f).roundToInt().coerceIn(0, 255)
            interludeDotPaint.alpha = alpha
            val cx = centerX + (i - 1) * spacing
            canvas.drawCircle(cx, centerY, r, interludeDotPaint)
        }
    }

    private fun drawDefaultMainLine(
        canvas: Canvas,
        overflow: Float,
        drawX: Float,
        mainBaseline: Float,
        mainHeight: Float,
        clipLeft: Float,
        clipTop: Float,
        clipRight: Float,
        clipBottom: Float,
        combinedAlpha: Float,
        fadeLeft: Boolean,
        fadeRight: Boolean,
        elapsedActiveMs: Long,
        cycleFinished: Boolean
    ): Boolean {
        var needMore = false
        if (overflow <= 1f) {
            textPaint.shader = null
            if (!karaokeEnabled) {
                textPaint.color = Const.RIGHT_LYRIC_COLOR
                textPaint.alpha = (255f * combinedAlpha).roundToInt().coerceIn(0, 255)
                canvas.drawText(lyricText, drawX, mainBaseline, textPaint)
            } else {
                val sungPx = computeKaraokeSungPx(elapsedActiveMs)
                val splitX = drawX + sungPx
                val karaokeFinished = isKaraokeFinished(elapsedActiveMs)
                val padH = shadowPadH.toFloat()

                if (splitX > drawX + 0.5f) {
                    val sc = canvas.save()
                    canvas.clipRect(drawX - padH, clipTop - mainHeight, splitX, clipBottom + mainHeight)
                    textPaint.color = karaokeSungColor
                    textPaint.alpha = (255f * combinedAlpha).roundToInt().coerceIn(0, 255)
                    canvas.drawText(lyricText, drawX, mainBaseline, textPaint)
                    canvas.restoreToCount(sc)
                }
                if (splitX < drawX + measuredTextWidth - 0.5f) {
                    val sc = canvas.save()
                    canvas.clipRect(splitX, clipTop - mainHeight, drawX + measuredTextWidth + padH, clipBottom + mainHeight)
                    textPaint.color = Const.RIGHT_LYRIC_COLOR
                    textPaint.alpha = (unplayedAlpha * combinedAlpha).roundToInt().coerceIn(0, 255)
                    canvas.drawText(lyricText, drawX, mainBaseline, textPaint)
                    canvas.restoreToCount(sc)
                }
                if (!karaokeFinished) {
                    needMore = true
                }
            }
        } else {
            if (!karaokeEnabled) {
                ensureFadeShaders(fadeLeft, fadeRight)
                textPaint.shader = if (fadeLeft || fadeRight) cachedWhiteShader else null
                textPaint.color = Const.RIGHT_LYRIC_COLOR
                textPaint.alpha = (255f * combinedAlpha).roundToInt().coerceIn(0, 255)

                val sc = canvas.save()
                canvas.clipRect(clipLeft, clipTop - mainHeight, clipRight, clipBottom + mainHeight)
                canvas.drawText(lyricText, drawX, mainBaseline, textPaint)
                canvas.restoreToCount(sc)

                if (!cycleFinished) {
                    needMore = true
                }
            } else {
                val sungPx = computeKaraokeSungPx(elapsedActiveMs)
                val splitX = (drawX + sungPx).coerceIn(clipLeft, clipRight)
                ensureFadeShaders(fadeLeft, fadeRight)

                if (splitX > clipLeft + 0.5f) {
                    val sc = canvas.save()
                    canvas.clipRect(clipLeft, clipTop - mainHeight, splitX, clipBottom + mainHeight)
                    textPaint.shader = if (fadeLeft || fadeRight) cachedSungShader else null
                    textPaint.color = karaokeSungColor
                    textPaint.alpha = (255f * combinedAlpha).roundToInt().coerceIn(0, 255)
                    canvas.drawText(lyricText, drawX, mainBaseline, textPaint)
                    canvas.restoreToCount(sc)
                }

                if (splitX < clipRight - 0.5f) {
                    val sc = canvas.save()
                    canvas.clipRect(splitX, clipTop - mainHeight, clipRight, clipBottom + mainHeight)
                    textPaint.shader = if (fadeLeft || fadeRight) cachedWhiteShader else null
                    textPaint.color = Const.RIGHT_LYRIC_COLOR
                    textPaint.alpha = (unplayedAlpha * combinedAlpha).roundToInt().coerceIn(0, 255)
                    canvas.drawText(lyricText, drawX, mainBaseline, textPaint)
                    canvas.restoreToCount(sc)
                }

                if (!cycleFinished || !isKaraokeFinished(elapsedActiveMs)) {
                    needMore = true
                }
            }
        }
        return needMore
    }

    private fun drawStyledMainLine(
        canvas: Canvas,
        style: Int,
        dualActive: Boolean,
        overflow: Float,
        drawX: Float,
        mainBaseline: Float,
        mainHeight: Float,
        availW: Float,
        clipLeft: Float,
        clipTop: Float,
        clipRight: Float,
        clipBottom: Float,
        combinedAlpha: Float,
        fadeLeft: Boolean,
        fadeRight: Boolean,
        elapsedActiveMs: Long,
        cycleFinished: Boolean,
        nowUptimeMs: Long
    ): Boolean {
        val sungPx = if (karaokeEnabled) computeKaraokeSungPx(elapsedActiveMs) else measuredTextWidth
        val vanguardX = drawX + sungPx
        val curWordIdx = findActiveWordIndex(elapsedActiveMs)
        if (curWordIdx != lastActiveWordIdx) {
            lastActiveWordIdx = curWordIdx
            lastWordSwitchUptimeMs = nowUptimeMs
        }
        val karaokeDone = !karaokeEnabled || isKaraokeFinished(elapsedActiveMs)
        val hasWords = wordStartMs.isNotEmpty() && wordCharStart.size == wordStartMs.size
        // vc55：交替模式余量减半，样式位移上限同步减半（对表定案，防上下沿切字）
        val shiftCap = currentStyleShiftCapPx(style, dualActive) * (if (activeAltLayout()) 0.5f else 1f)
        val scalePivotY = mainBaseline - mainHeight * 0.4f

        when (style) {
            STYLE_AURORA -> {
                val useCompose = overflow > 1f && (fadeLeft || fadeRight)
                if (useCompose) {
                    ensureFadeShaders(fadeLeft, fadeRight)
                }
                ensureAuroraShaders(availW, useCompose, fadeLeft, fadeRight)
                val gradW = auroraGradWidth.coerceAtLeast(1f)
                val shift = ((elapsedActiveMs % AURORA_FLOW_PERIOD_MS).toFloat() / AURORA_FLOW_PERIOD_MS.toFloat()) * gradW
                // 仅对内部子极光 Shader 设平移矩阵，严禁对 ComposeShader 整体设矩阵
                auroraMatrix.setTranslate(drawX + shift, 0f)
                auroraSungShader?.setLocalMatrix(auroraMatrix)
                auroraUnplayedShader?.setLocalMatrix(auroraMatrix)

                val sungShaderToUse: Shader? = if (useCompose) auroraComposeSung else auroraSungShader
                val unplayedShaderToUse: Shader? = if (useCompose) auroraComposeUnplayed else auroraUnplayedShader

                val scAll = canvas.save()
                canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)
                if (!karaokeEnabled) {
                    fxPaint.textSize = textPaint.textSize
                    fxPaint.shader = sungShaderToUse
                    fxPaint.color = Color.WHITE
                    fxPaint.alpha = (255f * combinedAlpha).roundToInt().coerceIn(0, 255)
                    canvas.drawText(lyricText, drawX, mainBaseline, fxPaint)
                } else {
                    val splitX = (drawX + sungPx).coerceIn(clipLeft, clipRight)
                    if (splitX > clipLeft + 0.5f) {
                        val sc = canvas.save()
                        canvas.clipRect(clipLeft, clipTop, splitX, clipBottom)
                        fxPaint.textSize = textPaint.textSize
                        fxPaint.shader = sungShaderToUse
                        fxPaint.color = Color.WHITE
                        fxPaint.alpha = (255f * combinedAlpha).roundToInt().coerceIn(0, 255)
                        canvas.drawText(lyricText, drawX, mainBaseline, fxPaint)
                        canvas.restoreToCount(sc)
                    }
                    if (splitX < clipRight - 0.5f) {
                        val sc = canvas.save()
                        canvas.clipRect(splitX, clipTop, clipRight, clipBottom)
                        fxPaint.textSize = textPaint.textSize
                        fxPaint.shader = unplayedShaderToUse
                        fxPaint.color = Color.WHITE
                        fxPaint.alpha = (AURORA_UNPLAYED_ALPHA * combinedAlpha).roundToInt().coerceIn(0, 255)
                        canvas.drawText(lyricText, drawX, mainBaseline, fxPaint)
                        canvas.restoreToCount(sc)
                    }
                }
                canvas.restoreToCount(scAll)
                return isPlaying
            }

            STYLE_POP_IN -> {
                val scAll = canvas.save()
                canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)
                textPaint.shader = null
                var anyAnimating = false
                val hasUnits = charUnitStartMs.isNotEmpty()
                if (!hasUnits) {
                    val dt = elapsedActiveMs.coerceAtLeast(0L)
                    val t = (dt.toFloat() / POP_IN_DURATION_MS.toFloat()).coerceIn(0f, 1f)
                    if (t < 1f) anyAnimating = true
                    val rawScaleFb = popInEaseOutBack(t)
                    val scale = if (dualActive) 1f + (rawScaleFb - 1f) * 0.65f * (if (activeAltLayout()) 0.5f else 1f) else rawScaleFb
                    val dy = (3f * (1f - easeOutCubic(t))).coerceIn(0f, shiftCap)
                    val alphaFrac = (t / 0.4f).coerceIn(0f, 1f)
                    val alpha = ((unplayedAlpha + (255 - unplayedAlpha) * alphaFrac) * combinedAlpha).roundToInt().coerceIn(0, 255)
                    val sc = canvas.save()
                    canvas.translate(0f, dy)
                    canvas.scale(scale, scale, drawX + measuredTextWidth * 0.5f, scalePivotY)
                    textPaint.color = if (karaokeEnabled) karaokeSungColor else Const.RIGHT_LYRIC_COLOR
                    textPaint.alpha = alpha
                    canvas.drawText(lyricText, drawX, mainBaseline, textPaint)
                    canvas.restoreToCount(sc)
                } else {
                    val frontPx = if (karaokeEnabled) sungPx else {
                        val durMs = wordTotalEndMs.coerceAtLeast(1L)
                        (elapsedActiveMs.coerceIn(0L, durMs).toFloat() / durMs.toFloat()) * measuredTextWidth
                    }
                    updatePopInFrontState(frontPx, elapsedActiveMs)
                    for (i in charUnitStartMs.indices) {
                        val cs = charUnitStrStart[i]
                        val ce = charUnitStrEnd[i]
                        if (ce <= cs) continue
                        val ux0 = drawX + charUnitStartPx[i]
                        val ux1 = drawX + charUnitEndPx[i]
                        if (ux1 < clipLeft - 4f || ux0 > clipRight + 4f) continue
                        val uStart = charUnitStartMs[i]
                        val uEnd = charUnitEndMs[i]
                        val popState = if (i < popStartActiveMs.size) popStartActiveMs[i] else POP_UNTRIGGERED
                        if (charUnitWhitespace[i]) {
                            textPaint.color = Const.RIGHT_LYRIC_COLOR
                            textPaint.alpha = (unplayedAlpha * combinedAlpha).roundToInt().coerceIn(0, 255)
                            canvas.drawText(lyricText, cs, ce, ux0, mainBaseline, textPaint)
                        } else if (popState == POP_UNTRIGGERED) {
                            // vc54 逐字出现：未轮到该字，不绘制（轮到时由前锋盖章起弹冒出）
                        } else {
                            val t = if (popState == POP_UNTRIGGERED || popState == POP_DONE) 1f else ((elapsedActiveMs - popState).toFloat() / POP_IN_DURATION_MS.toFloat()).coerceIn(0f, 1f)
                            if (t < 1f) anyAnimating = true
                            val rawScale = popInEaseOutBack(t)
                            val scale = if (dualActive) 1f + (rawScale - 1f) * 0.65f * (if (activeAltLayout()) 0.5f else 1f) else rawScale
                            val dy = (3f * (1f - easeOutCubic(t))).coerceIn(0f, shiftCap)
                            val alphaFrac = (t / 0.4f).coerceIn(0f, 1f)
                            val activeA = ((unplayedAlpha + (255 - unplayedAlpha) * alphaFrac) * combinedAlpha).roundToInt().coerceIn(0, 255)

                            val sc = canvas.save()
                            canvas.translate(0f, dy)
                            canvas.scale(scale, scale, (ux0 + ux1) * 0.5f, scalePivotY)
                            if (!karaokeEnabled || elapsedActiveMs >= uEnd) {
                                textPaint.color = if (karaokeEnabled) karaokeSungColor else Const.RIGHT_LYRIC_COLOR
                                textPaint.alpha = activeA
                                canvas.drawText(lyricText, cs, ce, ux0, mainBaseline, textPaint)
                            } else {
                                val frac = ((elapsedActiveMs - uStart).toFloat() / (uEnd - uStart).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                                val uSplit = ux0 + (ux1 - ux0) * frac
                                if (uSplit > ux0 + 0.3f) {
                                    val scS = canvas.save()
                                    canvas.clipRect(ux0 - 2f, clipTop, uSplit, clipBottom)
                                    textPaint.color = karaokeSungColor
                                    textPaint.alpha = activeA
                                    canvas.drawText(lyricText, cs, ce, ux0, mainBaseline, textPaint)
                                    canvas.restoreToCount(scS)
                                }
                                if (uSplit < ux1 - 0.3f) {
                                    val scU = canvas.save()
                                    canvas.clipRect(uSplit, clipTop, ux1 + 2f, clipBottom)
                                    textPaint.color = Const.RIGHT_LYRIC_COLOR
                                    textPaint.alpha = (unplayedAlpha * combinedAlpha).roundToInt().coerceIn(0, 255)
                                    canvas.drawText(lyricText, cs, ce, ux0, mainBaseline, textPaint)
                                    canvas.restoreToCount(scU)
                                }
                            }
                            canvas.restoreToCount(sc)
                        }
                    }
                }
                canvas.restoreToCount(scAll)
                return !karaokeDone || !cycleFinished || anyAnimating
            }

            STYLE_SWEEP -> {
                val scAll = canvas.save()
                canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)
                val lineSine = sin((elapsedActiveMs % SWEEP_FLOAT_PERIOD_MS).toDouble() / SWEEP_FLOAT_PERIOD_MS * TWO_PI).toFloat()
                val lineDy = 2.5f * lineSine
                ensureSweepShader(mainHeight)
                val bandHalf = sweepShaderWidth * 0.5f
                val bandLeft = vanguardX - bandHalf
                val bandRight = vanguardX + bandHalf

                if (!hasWords) {
                    val clampedDy = lineDy.coerceIn(-shiftCap, shiftCap)
                    drawDefaultMainLine(
                        canvas, overflow, drawX, mainBaseline + clampedDy, mainHeight,
                        clipLeft, clipTop, clipRight, clipBottom, combinedAlpha, fadeLeft, fadeRight,
                        elapsedActiveMs, cycleFinished
                    )
                    sweepMatrix.setTranslate(bandLeft, 0f)
                    sweepShader?.setLocalMatrix(sweepMatrix)
                    fxPaint.textSize = textPaint.textSize
                    fxPaint.shader = sweepShader
                    fxPaint.color = Color.WHITE
                    fxPaint.alpha = (SWEEP_PEAK_ALPHA * combinedAlpha).roundToInt().coerceIn(0, 255)
                    canvas.drawText(lyricText, drawX, mainBaseline + clampedDy, fxPaint)
                } else {
                    textPaint.shader = null
                    for (i in wordStartMs.indices) {
                        val cs = wordCharStart[i]
                        val ce = wordCharEnd[i]
                        if (ce <= cs) continue
                        val wx0 = drawX + wordStartPx[i]
                        val wx1 = drawX + wordEndPx[i]
                        if (wx1 < clipLeft - 4f || wx0 > clipRight + 4f) continue
                        val isCur = i == curWordIdx && elapsedActiveMs >= wordStartMs[i] && elapsedActiveMs <= wordEndMs[i]
                        val wordArch = if (isCur) {
                            val p = ((elapsedActiveMs - wordStartMs[i]).toFloat() / (wordEndMs[i] - wordStartMs[i]).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                            sin(p * PI).toFloat()
                        } else 0f
                        val totalDy = (lineDy - 2.5f * wordArch).coerceIn(-shiftCap, shiftCap)
                        val wScale = if (isCur) 1f + 0.05f * wordArch else 1f
                        val sc = canvas.save()
                        canvas.translate(0f, totalDy)
                        if (wScale != 1f) {
                            canvas.scale(wScale, wScale, (wx0 + wx1) * 0.5f, scalePivotY)
                        }
                        drawWordKaraoke(canvas, cs, ce, wx0, wx1, wordStartMs[i], wordEndMs[i], mainBaseline, mainHeight, clipTop, clipBottom, elapsedActiveMs, combinedAlpha, 1f, unplayedAlpha)
                        // F3：高光带 [vanguardX - bandHalf, vanguardX + bandHalf] 相交的全部词都重描
                        if (wx1 >= bandLeft && wx0 <= bandRight) {
                            sweepMatrix.setTranslate(bandLeft, 0f)
                            sweepShader?.setLocalMatrix(sweepMatrix)
                            fxPaint.textSize = textPaint.textSize
                            fxPaint.shader = sweepShader
                            fxPaint.color = Color.WHITE
                            fxPaint.alpha = (SWEEP_PEAK_ALPHA * combinedAlpha).roundToInt().coerceIn(0, 255)
                            canvas.drawText(lyricText, cs, ce, wx0, mainBaseline, fxPaint)
                        }
                        canvas.restoreToCount(sc)
                    }
                }
                canvas.restoreToCount(scAll)
                return isPlaying
            }

            STYLE_SPOTLIGHT -> {
                val scAll = canvas.save()
                canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)
                val dtSpot = if (spotLastUpdateUptimeMs > 0L) (nowUptimeMs - spotLastUpdateUptimeMs).coerceIn(0L, 100L) else 0L
                spotLastUpdateUptimeMs = nowUptimeMs
                if (spotFollowX < 0f || !isPlaying) {
                    spotFollowX = vanguardX
                } else {
                    val followAlpha = 1f - exp(-dtSpot.toFloat() / SPOTLIGHT_FOLLOW_MS)
                    spotFollowX += (vanguardX - spotFollowX) * followAlpha
                }
                val spotSettled = abs(spotFollowX - vanguardX) <= 0.5f
                val progressMoving = abs(sungPx - lastSpotKaraokePx) > 0.25f
                lastSpotKaraokePx = sungPx

                val spotRadius = mainHeight * Const.LYRIC_SPOTLIGHT_RADIUS_FACTOR
                ensureSpotShader(spotRadius)
                val spotCy = mainBaseline - mainHeight * 0.35f
                val leftEdgeFade = ((spotFollowX - clipLeft) / 12f).coerceIn(0f, 1f)
                val spotAlpha = (255f * leftEdgeFade * combinedAlpha).roundToInt().coerceIn(0, 255)
                if (spotAlpha > 1) {
                    spotMatrix.setTranslate(spotFollowX, spotCy)
                    spotShader?.setLocalMatrix(spotMatrix)
                }

                val spotUnplayedA = Const.LYRIC_SPOTLIGHT_UNPLAYED_ALPHA
                textPaint.shader = null
                if (!hasWords) {
                    val splitX = (drawX + sungPx).coerceIn(clipLeft, clipRight)
                    if (splitX > clipLeft + 0.5f) {
                        val sc = canvas.save()
                        canvas.clipRect(clipLeft, clipTop, splitX, clipBottom)
                        textPaint.color = if (karaokeEnabled) karaokeSungColor else Const.RIGHT_LYRIC_COLOR
                        textPaint.alpha = (255f * Const.LYRIC_SPOTLIGHT_DIM_FACTOR * combinedAlpha).roundToInt().coerceIn(0, 255)
                        canvas.drawText(lyricText, drawX, mainBaseline, textPaint)
                        canvas.restoreToCount(sc)
                    }
                    if (splitX < clipRight - 0.5f) {
                        val sc = canvas.save()
                        canvas.clipRect(splitX, clipTop, clipRight, clipBottom)
                        textPaint.color = Const.RIGHT_LYRIC_COLOR
                        textPaint.alpha = (spotUnplayedA * combinedAlpha).roundToInt().coerceIn(0, 255)
                        canvas.drawText(lyricText, drawX, mainBaseline, textPaint)
                        canvas.restoreToCount(sc)
                    }
                    if (spotAlpha > 1 && spotShader != null) {
                        val scSpot = canvas.save()
                        canvas.clipRect(
                            max(clipLeft, spotFollowX - spotRadius),
                            clipTop,
                            min(clipRight, spotFollowX + spotRadius),
                            clipBottom
                        )
                        fxPaint.textSize = textPaint.textSize
                        fxPaint.shader = spotShader
                        fxPaint.color = Color.WHITE
                        fxPaint.alpha = spotAlpha
                        canvas.drawText(lyricText, drawX, mainBaseline, fxPaint)
                        canvas.restoreToCount(scSpot)
                    }
                } else {
                    for (i in wordStartMs.indices) {
                        val cs = wordCharStart[i]
                        val ce = wordCharEnd[i]
                        if (ce <= cs) continue
                        val wx0 = drawX + wordStartPx[i]
                        val wx1 = drawX + wordEndPx[i]
                        if (wx1 < clipLeft - 4f || wx0 > clipRight + 4f) continue
                        val isCur = i == curWordIdx && elapsedActiveMs >= wordStartMs[i] && elapsedActiveMs <= wordEndMs[i]
                        val sc = canvas.save()
                        if (isCur) {
                            canvas.translate(0f, (-1.0f).coerceIn(-shiftCap, shiftCap))
                            canvas.scale(1.05f, 1.05f, (wx0 + wx1) * 0.5f, scalePivotY)
                        }
                        val sungMul = if (isCur) 1f else Const.LYRIC_SPOTLIGHT_DIM_FACTOR
                        drawWordKaraoke(canvas, cs, ce, wx0, wx1, wordStartMs[i], wordEndMs[i], mainBaseline, mainHeight, clipTop, clipBottom, elapsedActiveMs, combinedAlpha, sungMul, spotUnplayedA)
                        if (spotAlpha > 1 && spotShader != null && wx1 >= spotFollowX - spotRadius && wx0 <= spotFollowX + spotRadius) {
                            fxPaint.textSize = textPaint.textSize
                            fxPaint.shader = spotShader
                            fxPaint.color = Color.WHITE
                            fxPaint.alpha = spotAlpha
                            canvas.drawText(lyricText, cs, ce, wx0, mainBaseline, fxPaint)
                        }
                        canvas.restoreToCount(sc)
                    }
                }
                canvas.restoreToCount(scAll)
                return !karaokeDone || !cycleFinished || !spotSettled || progressMoving
            }

            STYLE_SPRING_WAVE -> {
                if (!hasWords) {
                    return drawDefaultMainLine(
                        canvas, overflow, drawX, mainBaseline, mainHeight,
                        clipLeft, clipTop, clipRight, clipBottom, combinedAlpha, fadeLeft, fadeRight,
                        elapsedActiveMs, cycleFinished
                    )
                }
                val scAll = canvas.save()
                canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)
                textPaint.shader = null
                val curSpread = if (curWordIdx in wordStartPx.indices) {
                    (1.6f * (wordEndPx[curWordIdx] - wordStartPx[curWordIdx])).coerceAtLeast(mainHeight * 0.6f)
                } else {
                    mainHeight * 1.6f
                }
                for (i in wordStartMs.indices) {
                    val cs = wordCharStart[i]
                    val ce = wordCharEnd[i]
                    if (ce <= cs) continue
                    val wx0 = drawX + wordStartPx[i]
                    val wx1 = drawX + wordEndPx[i]
                    if (wx1 < clipLeft - 4f || wx0 > clipRight + 4f) continue
                    val wCx = (wx0 + wx1) * 0.5f
                    val dist = wCx - vanguardX
                    val bell = exp(-((dist / curSpread) * (dist / curSpread)).toDouble()).toFloat()
                    val dy = (-shiftCap * bell).coerceIn(-shiftCap, 0f)
                    val wScale = 1f + (if (dualActive) 0.07f else 0.08f) * (if (activeAltLayout()) 0.5f else 1f) * bell
                    val sc = canvas.save()
                    canvas.translate(0f, dy)
                    canvas.scale(wScale, wScale, wCx, scalePivotY)
                    drawWordKaraoke(canvas, cs, ce, wx0, wx1, wordStartMs[i], wordEndMs[i], mainBaseline, mainHeight, clipTop, clipBottom, elapsedActiveMs, combinedAlpha, 1f, unplayedAlpha)
                    canvas.restoreToCount(sc)
                }
                canvas.restoreToCount(scAll)
                val withinSwitchTail = lastWordSwitchUptimeMs > 0L && (nowUptimeMs - lastWordSwitchUptimeMs) < 300L
                return !karaokeDone || !cycleFinished || withinSwitchTail
            }

            STYLE_INK_WASH -> {
                val scAll = canvas.save()
                canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)
                // 1) F6：墨晕层（64×32 ALPHA_8，色相对已唱色旋转 +30° 并走 LYRIC_PALETTE_MIN_L 钳，alpha 85）
                val blotchColor = rotateHueAndClampL(karaokeSungColor, 30f)
                val strokeColor = if (hasPaletteColors) paletteColors[1] else clampLyricPaletteColorL(karaokeSungColor)
                val blotchBmp = ensureInkBlotchBitmap()
                if (blotchBmp != null) {
                    inkBlotchPaint.color = blotchColor
                    inkBlotchPaint.alpha = (Const.LYRIC_INK_BLOTCH_ALPHA * combinedAlpha).roundToInt().coerceIn(0, 255)
                    val bw = mainHeight * 2f
                    val bh = mainHeight
                    val bCy = mainBaseline - mainHeight * 0.35f
                    inkBlotchDst.set(vanguardX - bw * 0.5f, bCy - bh * 0.5f, vanguardX + bw * 0.5f, bCy + bh * 0.5f)
                    canvas.drawBitmap(blotchBmp, null, inkBlotchDst, inkBlotchPaint)
                }

                if (!hasWords) {
                    drawDefaultMainLine(
                        canvas, overflow, drawX, mainBaseline, mainHeight,
                        clipLeft, clipTop, clipRight, clipBottom, combinedAlpha, fadeLeft, fadeRight,
                        elapsedActiveMs, cycleFinished
                    )
                    canvas.restoreToCount(scAll)
                    return !karaokeDone || !cycleFinished
                }

                // 2) 描边层（仅当前词及前后各 1 词，宽维持 clamp(0.06h, 1.5, 3.0)px，alpha <= 90，唱完 600ms 淡出）
                val strokeW = (0.06f * mainHeight).coerceIn(Const.LYRIC_INK_STROKE_MIN_PX, Const.LYRIC_INK_STROKE_MAX_PX)
                inkStrokePaint.textSize = textPaint.textSize
                inkStrokePaint.strokeWidth = strokeW
                inkStrokePaint.color = strokeColor
                for (i in wordStartMs.indices) {
                    val cs = wordCharStart[i]
                    val ce = wordCharEnd[i]
                    if (ce <= cs) continue
                    val wx0 = drawX + wordStartPx[i]
                    val wx1 = drawX + wordEndPx[i]
                    if (wx1 < clipLeft - 4f || wx0 > clipRight + 4f) continue
                    val nearActive = curWordIdx >= 0 && abs(i - curWordIdx) <= 1
                    val postEndMs = elapsedActiveMs - wordEndMs[i]
                    val inFadeTail = elapsedActiveMs >= wordStartMs[i] && postEndMs in 0L..INK_STROKE_FADE_MS
                    if (!nearActive && !inFadeTail) continue
                    val fadeMul = if (postEndMs > 0L) (1f - postEndMs.toFloat() / INK_STROKE_FADE_MS).coerceIn(0f, 1f) else 1f
                    val sAlpha = (Const.LYRIC_INK_STROKE_ALPHA * fadeMul * combinedAlpha).roundToInt().coerceIn(0, Const.LYRIC_INK_STROKE_ALPHA)
                    if (sAlpha > 1) {
                        inkStrokePaint.alpha = sAlpha
                        canvas.drawText(lyricText, cs, ce, wx0, mainBaseline, inkStrokePaint)
                    }
                }

                // 3) 正文层（F6：仅当前词做由虚变实重影，ghost alpha <= 90，删下一词 p=0 预挂）
                textPaint.shader = null
                for (i in wordStartMs.indices) {
                    val cs = wordCharStart[i]
                    val ce = wordCharEnd[i]
                    if (ce <= cs) continue
                    val wx0 = drawX + wordStartPx[i]
                    val wx1 = drawX + wordEndPx[i]
                    if (wx1 < clipLeft - 4f || wx0 > clipRight + 4f) continue
                    if (curWordIdx >= 0 && i == curWordIdx && elapsedActiveMs >= wordStartMs[i]) {
                        val wDur = (wordEndMs[i] - wordStartMs[i]).coerceAtLeast(1L)
                        val condenseDur = (wDur + 120L).coerceIn(240L, 480L)
                        val p = ((elapsedActiveMs - wordStartMs[i]).toFloat() / condenseDur.toFloat()).coerceIn(0f, 1f)
                        val ghostOffset = 1.5f * (1f - p)
                        if (ghostOffset > 0.05f) {
                            val gAlpha = (Const.LYRIC_INK_GHOST_MAX_ALPHA * (1f - p) * combinedAlpha).roundToInt()
                                .coerceIn(0, Const.LYRIC_INK_GHOST_MAX_ALPHA)
                            if (gAlpha > 1) {
                                dissolvePaint.textSize = textPaint.textSize
                                dissolvePaint.shader = null
                                dissolvePaint.color = if (karaokeEnabled) karaokeSungColor else Const.RIGHT_LYRIC_COLOR
                                dissolvePaint.alpha = gAlpha
                                canvas.drawText(lyricText, cs, ce, wx0 - ghostOffset, mainBaseline, dissolvePaint)
                                canvas.drawText(lyricText, cs, ce, wx0 + ghostOffset, mainBaseline, dissolvePaint)
                            }
                        }
                    }
                    drawWordKaraoke(canvas, cs, ce, wx0, wx1, wordStartMs[i], wordEndMs[i], mainBaseline, mainHeight, clipTop, clipBottom, elapsedActiveMs, combinedAlpha, 1f, unplayedAlpha)
                }
                canvas.restoreToCount(scAll)
                val inInkTail = wordTotalEndMs > 0L && elapsedActiveMs < wordTotalEndMs + INK_STROKE_FADE_MS
                return !karaokeDone || !cycleFinished || inInkTail
            }

            else -> {
                return drawDefaultMainLine(
                    canvas, overflow, drawX, mainBaseline, mainHeight,
                    clipLeft, clipTop, clipRight, clipBottom, combinedAlpha, fadeLeft, fadeRight,
                    elapsedActiveMs, cycleFinished
                )
            }
        }
    }

    private fun drawWordKaraoke(
        canvas: Canvas,
        cs: Int,
        ce: Int,
        wx0: Float,
        wx1: Float,
        wStart: Long,
        wEnd: Long,
        mainBaseline: Float,
        mainHeight: Float,
        clipTop: Float,
        clipBottom: Float,
        elapsedActiveMs: Long,
        combinedAlpha: Float,
        sungAlphaMul: Float,
        unplayedBaseAlpha: Int
    ) {
        if (!karaokeEnabled) {
            textPaint.color = Const.RIGHT_LYRIC_COLOR
            textPaint.alpha = (255f * combinedAlpha).roundToInt().coerceIn(0, 255)
            canvas.drawText(lyricText, cs, ce, wx0, mainBaseline, textPaint)
            return
        }
        if (elapsedActiveMs >= wEnd) {
            textPaint.color = karaokeSungColor
            textPaint.alpha = (255f * sungAlphaMul * combinedAlpha).roundToInt().coerceIn(0, 255)
            canvas.drawText(lyricText, cs, ce, wx0, mainBaseline, textPaint)
        } else if (elapsedActiveMs <= wStart) {
            textPaint.color = Const.RIGHT_LYRIC_COLOR
            textPaint.alpha = (unplayedBaseAlpha * combinedAlpha).roundToInt().coerceIn(0, 255)
            canvas.drawText(lyricText, cs, ce, wx0, mainBaseline, textPaint)
        } else {
            val frac = ((elapsedActiveMs - wStart).toFloat() / (wEnd - wStart).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
            val wSplit = wx0 + (wx1 - wx0) * frac
            if (wSplit > wx0 + 0.3f) {
                val scS = canvas.save()
                canvas.clipRect(wx0 - 2f, clipTop, wSplit, clipBottom)
                textPaint.color = karaokeSungColor
                textPaint.alpha = (255f * sungAlphaMul * combinedAlpha).roundToInt().coerceIn(0, 255)
                canvas.drawText(lyricText, cs, ce, wx0, mainBaseline, textPaint)
                canvas.restoreToCount(scS)
            }
            if (wSplit < wx1 - 0.3f) {
                val scU = canvas.save()
                canvas.clipRect(wSplit, clipTop, wx1 + 2f, clipBottom)
                textPaint.color = Const.RIGHT_LYRIC_COLOR
                textPaint.alpha = (unplayedBaseAlpha * combinedAlpha).roundToInt().coerceIn(0, 255)
                canvas.drawText(lyricText, cs, ce, wx0, mainBaseline, textPaint)
                canvas.restoreToCount(scU)
            }
        }
    }

    private fun easeOutCubic(t: Float): Float {
        val inv = 1f - t.coerceIn(0f, 1f)
        return 1f - inv * inv * inv
    }

    /** F1 ③：easeOutBack 0.78 -> 峰值约 1.12 -> 1.0 */
    /** vc53 F2: front-driven pop-in state. Continuous frames stamp crossed units; discontinuities resync. */
    private fun updatePopInFrontState(frontPx: Float, elapsedActiveMs: Long) {
        val n = charUnitStartMs.size
        if (n == 0) return
        if (popStartActiveMs.size != n) {
            popStartActiveMs = LongArray(n) { POP_UNTRIGGERED }
            lastPopFrontPx = -1f
            lastPopActiveMs = -1L
        }
        val discontinuous = lastPopActiveMs < 0L ||
            elapsedActiveMs < lastPopActiveMs ||
            (elapsedActiveMs - lastPopActiveMs) > 300L ||
            frontPx < lastPopFrontPx - 2f
        if (discontinuous) {
            for (i in 0 until n) {
                when {
                    charUnitEndPx[i] <= frontPx -> popStartActiveMs[i] = POP_DONE
                    charUnitStartPx[i] >= frontPx -> popStartActiveMs[i] = POP_UNTRIGGERED
                }
            }
        } else {
            for (i in 0 until n) {
                if (popStartActiveMs[i] == POP_UNTRIGGERED && frontPx >= charUnitStartPx[i]) {
                    popStartActiveMs[i] = elapsedActiveMs
                }
            }
        }
        lastPopFrontPx = frontPx
        lastPopActiveMs = elapsedActiveMs
    }

    private fun popInEaseOutBack(t: Float): Float {
        val u = t.coerceIn(0f, 1f) - 1f
        val s = 5.0f
        val back = 1f + (s + 1f) * u * u * u + s * u * u
        return 0.85f + (1.0f - 0.85f) * back
    }

    private fun findActiveWordIndex(elapsedMs: Long): Int {
        val n = wordStartMs.size
        if (n == 0) return -1
        for (i in 0 until n) {
            if (elapsedMs < wordEndMs[i]) return i
        }
        return n - 1
    }

    private fun resolveAuroraColors() {
        if (hasPaletteColors) {
            auroraResolvedColors[0] = paletteColors[0]
            auroraResolvedColors[1] = paletteColors[1]
            auroraResolvedColors[2] = paletteColors[2]
            return
        }
        argbToHsl(karaokeSungColor, hslBuf)
        if (hslBuf[1] < 0.15f) {
            auroraResolvedColors[0] = Const.LYRIC_AURORA_FALLBACK_PALETTE[0]
            auroraResolvedColors[1] = Const.LYRIC_AURORA_FALLBACK_PALETTE[1]
            auroraResolvedColors[2] = Const.LYRIC_AURORA_FALLBACK_PALETTE[2]
        } else {
            val l = max(hslBuf[2], Const.LYRIC_PALETTE_MIN_L)
            auroraResolvedColors[0] = hslToArgb(hslBuf[0], hslBuf[1], l)
            auroraResolvedColors[1] = hslToArgb(hslBuf[0] + 35f, hslBuf[1], l)
            auroraResolvedColors[2] = hslToArgb(hslBuf[0] - 35f, hslBuf[1], l)
        }
    }

    private fun ensureAuroraShaders(availW: Float, needCompose: Boolean, fadeLeft: Boolean, fadeRight: Boolean) {
        val targetW = (availW * 1.5f).coerceAtLeast(120f)
        val baseRebuild = auroraShaderDirty || auroraSungShader == null || auroraUnplayedShader == null || abs(auroraGradWidth - targetW) >= 1f
        if (baseRebuild) {
            auroraGradWidth = targetW
            auroraShaderDirty = false
            resolveAuroraColors()
            val c0 = auroraResolvedColors[0]
            val c1 = auroraResolvedColors[1]
            val c2 = auroraResolvedColors[2]
            auroraSungShader = LinearGradient(
                0f, 0f, targetW, 0f,
                intArrayOf(c0, c1, c2),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.MIRROR
            )
            val u0 = lerpArgb(c0, Color.WHITE, 0.5f) or 0xFF000000.toInt()
            val u1 = lerpArgb(c1, Color.WHITE, 0.5f) or 0xFF000000.toInt()
            val u2 = lerpArgb(c2, Color.WHITE, 0.5f) or 0xFF000000.toInt()
            auroraUnplayedShader = LinearGradient(
                0f, 0f, targetW, 0f,
                intArrayOf(u0, u1, u2),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.MIRROR
            )
            auroraComposeMaskWidth = -1
        }
        if (needCompose) {
            val mask = cachedFadeMaskShader
            val w = width
            val composeDirty = baseRebuild ||
                auroraComposeSung == null ||
                auroraComposeUnplayed == null ||
                auroraComposeMaskWidth != w ||
                auroraComposeFadeLeft != fadeLeft ||
                auroraComposeFadeRight != fadeRight
            if (composeDirty && mask != null) {
                val sung = auroraSungShader
                val unplayed = auroraUnplayedShader
                if (sung != null && unplayed != null) {
                    auroraComposeSung = ComposeShader(sung, mask, PorterDuff.Mode.DST_IN)
                    auroraComposeUnplayed = ComposeShader(unplayed, mask, PorterDuff.Mode.DST_IN)
                    auroraComposeMaskWidth = w
                    auroraComposeFadeLeft = fadeLeft
                    auroraComposeFadeRight = fadeRight
                }
            }
        }
    }

    private fun ensureSweepShader(mainHeight: Float) {
        val targetW = (mainHeight * 1.5f).coerceAtLeast(24f)
        val midColor = lerpArgb(karaokeSungColor, Color.WHITE, 0.5f) or 0xFF000000.toInt()
        if (sweepShader != null && abs(sweepShaderWidth - targetW) < 0.5f && sweepShaderColor == midColor) return
        sweepShaderWidth = targetW
        sweepShaderColor = midColor
        val trans = midColor and 0x00FFFFFF
        sweepShader = LinearGradient(
            0f, 0f, targetW, 0f,
            intArrayOf(trans, midColor, trans),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun ensureSpotShader(radiusPx: Float) {
        val r = radiusPx.coerceAtLeast(16f)
        if (spotShader != null && abs(spotShaderRadius - r) < 0.5f) return
        spotShaderRadius = r
        val core = (Const.LYRIC_SPOTLIGHT_CORE_ALPHA.coerceIn(0, 255) shl 24) or 0x00FFFFFF
        val mid = (Const.LYRIC_SPOTLIGHT_MID_ALPHA.coerceIn(0, 255) shl 24) or 0x00FFFFFF
        spotShader = RadialGradient(
            0f, 0f, r,
            intArrayOf(core, mid, 0x00FFFFFF),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun ensureInkBlotchBitmap(): Bitmap? {
        val existing = inkBlotchBitmap
        if (existing != null && !existing.isRecycled) return existing
        val w = 64
        val h = 32
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val pixels = ByteArray(w * h)
        // 4 个柔边高斯墨斑叠加（创建时生成一次）
        val cx = floatArrayOf(18f, 30f, 40f, 48f)
        val cy = floatArrayOf(17f, 14f, 18f, 15f)
        val rx = floatArrayOf(12f, 14f, 11f, 9f)
        val ry = floatArrayOf(8f, 10f, 8f, 7f)
        val amp = floatArrayOf(0.85f, 1.0f, 0.80f, 0.65f)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                for (k in 0 until 4) {
                    val dx = (x - cx[k]) / rx[k]
                    val dy = (y - cy[k]) / ry[k]
                    sum += amp[k] * exp((-(dx * dx + dy * dy) * 1.4f).toDouble()).toFloat()
                }
                val a = (sum.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
                pixels[y * w + x] = a.toByte()
            }
        }
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pixels))
        inkBlotchBitmap = bmp
        return bmp
    }

    private fun lerpArgb(c0: Int, c1: Int, t: Float): Int {
        val inv = 1f - t
        val a = (Color.alpha(c0) * inv + Color.alpha(c1) * t).roundToInt().coerceIn(0, 255)
        val r = (Color.red(c0) * inv + Color.red(c1) * t).roundToInt().coerceIn(0, 255)
        val g = (Color.green(c0) * inv + Color.green(c1) * t).roundToInt().coerceIn(0, 255)
        val b = (Color.blue(c0) * inv + Color.blue(c1) * t).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun currentElapsedActiveMs(): Long {
        if (lineStartUptimeMs <= 0L) return 0L
        val now = SystemClock.uptimeMillis()
        val ongoingPause = if (!isPlaying && pauseStartedUptimeMs > 0L) {
            (now - pauseStartedUptimeMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return (now - lineStartUptimeMs - pausedAccumulatedMs - ongoingPause).coerceAtLeast(0L)
    }

    private fun computeKaraokeProgress(elapsedMs: Long): Float {
        val denom = karaokeDurationMs.coerceAtLeast(1000L).toFloat()
        return (elapsedMs.toFloat() / denom).coerceIn(0f, 1f)
    }

    /** 有 KRC 逐字数组时按字精确插值（字间停顿自然驻留），无逐字回退整句匀速 */
    private fun computeKaraokeSungPx(elapsedMs: Long): Float {
        val n = wordStartMs.size
        if (n == 0) {
            return measuredTextWidth * computeKaraokeProgress(elapsedMs)
        }
        if (elapsedMs <= wordStartMs[0]) return 0f
        if (elapsedMs >= wordTotalEndMs) return measuredTextWidth
        for (i in 0 until n) {
            val start = wordStartMs[i]
            val end = wordEndMs[i]
            if (elapsedMs < start) {
                return if (i == 0) 0f else wordEndPx[i - 1]
            }
            if (elapsedMs <= end) {
                val dur = (end - start).coerceAtLeast(1L).toFloat()
                val frac = ((elapsedMs - start).toFloat() / dur).coerceIn(0f, 1f)
                return wordStartPx[i] + (wordEndPx[i] - wordStartPx[i]) * frac
            }
        }
        return measuredTextWidth
    }

    private fun isKaraokeFinished(elapsedMs: Long): Boolean {
        return if (wordStartMs.isNotEmpty()) {
            elapsedMs >= wordTotalEndMs
        } else {
            computeKaraokeProgress(elapsedMs) >= 1f
        }
    }

    private fun easeInOutCompressed(t: Float, scrollMs: Long): Float {
        val ramp = if (scrollMs < Const.SCROLL_EASE_COMPRESS_THRESHOLD_MS) {
            0.12f * (scrollMs.toFloat() / Const.SCROLL_EASE_COMPRESS_THRESHOLD_MS.toFloat())
        } else {
            0.12f
        }.coerceIn(0.02f, 0.25f)

        val v = 1f / (1f - ramp)
        return when {
            t <= ramp -> 0.5f * v * (t * t) / ramp
            t < 1f - ramp -> 0.5f * v * ramp + v * (t - ramp)
            else -> {
                val u = 1f - t
                (1f - 0.5f * v * (u * u) / ramp).coerceIn(0f, 1f)
            }
        }
    }

    private fun ensureFadeShaders(fadeLeft: Boolean, fadeRight: Boolean) {
        if (!fadeLeft && !fadeRight) return
        val w = width
        if (w <= 0) return

        val geomChanged = cachedShaderWidth != w ||
            cachedFadeLeft != fadeLeft ||
            cachedFadeRight != fadeRight

        if (geomChanged || cachedWhiteShader == null) {
            cachedWhiteShader = buildFadeShader(w, Const.RIGHT_LYRIC_COLOR, fadeLeft, fadeRight)
        }
        if (geomChanged || cachedFadeMaskShader == null) {
            cachedFadeMaskShader = buildFadeShader(w, Color.WHITE, fadeLeft, fadeRight)
        }
        if (geomChanged || cachedSungShader == null || cachedSungShaderColor != karaokeSungColor) {
            cachedSungShader = buildFadeShader(w, karaokeSungColor, fadeLeft, fadeRight)
            cachedSungShaderColor = karaokeSungColor
        }

        cachedShaderWidth = w
        cachedFadeLeft = fadeLeft
        cachedFadeRight = fadeRight
    }

    private fun buildFadeShader(
        widthPx: Int,
        solidColor: Int,
        fadeLeft: Boolean,
        fadeRight: Boolean
    ): LinearGradient {
        val edgeRatio = (fadeEdgePx / widthPx.toFloat()).coerceIn(0.01f, 0.20f)
        val opaque = solidColor or 0xFF000000.toInt()
        val transparent = opaque and 0x00FFFFFF
        val colors = intArrayOf(
            if (fadeLeft) transparent else opaque,
            opaque,
            opaque,
            if (fadeRight) transparent else opaque
        )
        val positions = floatArrayOf(0f, edgeRatio, 1f - edgeRatio, 1f)
        return LinearGradient(
            0f,
            0f,
            widthPx.toFloat(),
            0f,
            colors,
            positions,
            Shader.TileMode.CLAMP
        )
    }

    private fun invalidateShaderCache() {
        cachedWhiteShader = null
        cachedSungShader = null
        cachedFadeMaskShader = null
        cachedShaderWidth = -1
        auroraShaderDirty = true
    }

    private fun applyEffectiveTextSizes() {
        // vc55（确认轮待核为真）：交替布局下本曲已锁定双行口径——间奏/末句 nowDual=false 时字号不许翻回单行（否则与 onMeasure 的双槽高度脱节、曲中跳变）
        val dualActive = isDualLineActive() || isAlternateLayout()
        val mainSp = if (dualActive) {
            requestedTextSizeSp.coerceAtMost(Const.DUAL_LINE_MAIN_MAX_SP)
        } else {
            requestedTextSizeSp
        }
        val subSp = requestedSubTextSizeSp
            .coerceIn(Const.DUAL_LINE_SUB_MIN_SP, Const.DUAL_LINE_SUB_MAX_SP)

        currentTextSizeSp = mainSp
        textPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            mainSp,
            resources.displayMetrics
        )
        subTextPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            subSp,
            resources.displayMetrics
        )
        measuredTextWidth = if (lyricText.isNotEmpty()) textPaint.measureText(lyricText) else 0f
        if (rawWordSpans.isNotEmpty()) {
            rebuildWordPixelSpans(rawWordSpans)
        }
    }

    companion object {
        private const val STYLE_DEFAULT = 0
        private const val STYLE_AURORA = 1
        private const val STYLE_POP_IN = 2
        private const val STYLE_SWEEP = 3
        private const val STYLE_SPOTLIGHT = 4
        private const val STYLE_SPRING_WAVE = 5
        private const val STYLE_INK_WASH = 6

        private const val AURORA_FLOW_PERIOD_MS = 2500L
        private const val AURORA_UNPLAYED_ALPHA = 120f
        private const val POP_IN_DURATION_MS = 280L
        private const val POP_UNTRIGGERED: Long = Long.MIN_VALUE
        private const val POP_DONE: Long = -1L
        private const val SWEEP_FLOAT_PERIOD_MS = 3000L
        private const val SWEEP_PEAK_ALPHA = 220f
        private const val SPOTLIGHT_FOLLOW_MS = 120f
        private const val INK_STROKE_FADE_MS = 600L
        private const val TWO_PI = PI * 2.0
    }
}
