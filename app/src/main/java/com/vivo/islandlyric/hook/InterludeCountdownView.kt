package com.vivo.islandlyric.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.highcapable.yukihookapi.hook.log.YLog
import com.vivo.islandlyric.Const
import com.vivo.islandlyric.util.EventHistoryStore
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 宽形态右侧独立倒计时视图（v1.2.6 复用于全前奏与间奏）：
 * - 前奏模式（enforceMaxRemainingWindow = false, centerInView = true）：
 *   撤除全部竖条，整个前奏全程显示 16sp 粗体封面色秒数（不再受 15s 限制）；
 * - 间奏模式（enforceMaxRemainingWindow = true, centerInView = false）：
 *   置于原厂 IslandMusicWaveView 左侧（marginEnd = waveW + 6dp），零重叠，最后 15s 显示；
 * - 到点（<=0）自动撤下数字不卡 0/负数；暂停即刻停止刷新省电。
 */
class InterludeCountdownView(
    context: Context,
    private val positionProvider: () -> Long,
    private val enforceMaxRemainingWindow: Boolean = false,
    private val centerInView: Boolean = false,
    private val onBoundaryEvent: ((reason: String, curPos: Long, remaining: Long) -> Unit)? = null,
    // v1.4.5-fix11：前奏流光能力位——仅前奏实例构造时传 true，间奏实例结构性无流光（用户硬线：间奏禁用、怕耗电）
    private val shimmerCapable: Boolean = false,
    // v1.4.5-fix14 追加裁决：合并流光层可用性供给器——可用时本视图彻底不画背景（防两层叠光那一帧），
    // 不可用（注入跳过/绘制停用）时才走框内椭圆遮罩降级自画，已交付功能不许整段消失
    private val shimmerLayerAvailableProvider: (() -> Boolean)? = null,
    // v1.4.5-fix14 实施审 M1：流光意图的唯一出口——setShimmerEnabled 与 stopAndHide 都经此通知合并层，
    // Renderer 侧不再逐调用点镜像（六处 stopAndHide 曾漏同步、倒计时结束整层卡在倒计时配置）
    private val onShimmerIntentChanged: ((Boolean) -> Unit)? = null
) : View(context) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Const.KARAOKE_AMBER_FALLBACK
        textAlign = if (centerInView) Paint.Align.CENTER else Paint.Align.RIGHT
        typeface = Typeface.DEFAULT_BOLD
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            Const.INTRO_RIGHT_COUNTDOWN_TEXT_SIZE_SP,
            context.resources.displayMetrics
        )
    }

    private var preferredWidthPx: Int = 0
    private var accentColor: Int = Const.KARAOKE_AMBER_FALLBACK
    private var targetNextLineMs: Long = -1L
    private var isPlaying: Boolean = false
    private var loggedCrossZero: Boolean = false
    private var loggedGraceExpired: Boolean = false

    private val tickRunnable = Runnable {
        if (visibility == VISIBLE && isPlaying && targetNextLineMs > 0L) {
            invalidate()
        }
    }

    // v1.4.5-fix11 前奏流光（Quick 裁决实现路）：视图内自绘——单个复用 LinearGradient、每帧只改 localMatrix 平移，
    // 不分配对象、不用离屏位图/saveLayer；视图自身 33ms 节拍链驱动，门控任一不满足即不再续约、最多多画一帧；
    // 相位按帧间增量累加，暂停/停链即冻结（不用墙钟，拖进度与暂停都不跳相位）
    private var shimmerEnabled: Boolean = false
    private var shimmerPhaseMs: Long = 0L
    private var shimmerLastTickUptimeMs: Long = 0L
    private var shimmerChainActive: Boolean = false
    // v1.4.5-fix13 C（Quick 裁决：抽共用）：背景层绘制与 shader 缓存全部移交 ShimmerPainter，本视图只管
    // 相位/门控/节拍与文字流光（样式 6）；整岛流光用另一个 Painter 实例画同一套样式，两边永不漂移
    private val shimmerPainter = ShimmerPainter()
    // v1.4.5-fix12：流光峰值 alpha 按设置档位（0=淡 0x30 原强度 / 1=中 0x58 / 2=亮 0x88），七种样式共用此强度口径
    private var shimmerPeakAlpha: Int = 0x30
    // v1.4.5-fix12 流光样式（老大 2026-11-24 圈定）：0 横扫 / 1 呼吸 / 2 极光 / 3 封面色 / 4 星点 / 5 斜扫 / 6 文字
    private var shimmerStyle: Int = 0

    private fun invalidateShaderCache() {
        shimmerPainter.invalidateCache()
    }

    fun setShimmerLevel(level: Int) {
        val peak = ShimmerPainter.peakAlphaForLevel(level)
        if (peak != shimmerPeakAlpha) {
            shimmerPeakAlpha = peak
            invalidateShaderCache()
        }
    }

    fun setShimmerStyle(style: Int) {
        val v = style.coerceIn(0, 6)
        if (v == shimmerStyle) return
        shimmerStyle = v
        invalidateShaderCache()
        textPaint.shader = null
        if (visibility == VISIBLE) invalidate()
    }

    private val shimmerTickRunnable = object : Runnable {
        override fun run() {
            if (!shimmerGate()) {
                stopShimmerChain("gate_closed")
                return
            }
            val now = SystemClock.uptimeMillis()
            if (shimmerLastTickUptimeMs > 0L) {
                shimmerPhaseMs += now - shimmerLastTickUptimeMs
            }
            shimmerLastTickUptimeMs = now
            invalidate()
            postDelayed(this, SHIMMER_TICK_MS)
        }
    }

    private fun shimmerGate(): Boolean =
        // v1.4.5-fix11（Quick M3/M4）：grace 过期后 onDraw 已空转返回，链须同步关停防 30Hz 空转耗电；
        // alpha 不进门控——交叉淡化起点 alpha=0 时若被 tick 撞上会永久熄火，alpha=0 时画 rect 本就不出像素
        shimmerCapable && shimmerEnabled && isPlaying && visibility == VISIBLE &&
            isAttachedToWindow && targetNextLineMs > 0L && !loggedGraceExpired

    private fun kickShimmer() {
        if (!shimmerCapable) return
        // v1.4.5-fix11（Quick S1）：链在跑且门控通过时不打断，免每次 reconcile 的重复 kick 重置相位基准
        if (shimmerChainActive && shimmerGate()) return
        removeCallbacks(shimmerTickRunnable)
        if (!shimmerGate()) {
            // v1.4.5-fix11（Quick S2）：门控不过要正式停链，不留「活跃但无 runnable」的悬空状态
            stopShimmerChain("gate_closed")
            return
        }
        shimmerLastTickUptimeMs = 0L
        if (!shimmerChainActive) {
            shimmerChainActive = true
            val msg = "[SHIMMER] start target=$targetNextLineMs style=$shimmerStyle peakAlpha=$shimmerPeakAlpha view=${width}x${height} parent=${(parent as? View)?.width ?: -1}x${(parent as? View)?.height ?: -1} layer=${shimmerLayerAvailableProvider?.invoke()}"
            YLog.info(msg = msg)
            EventHistoryStore.recordDiagOnly(msg)
        }
        postDelayed(shimmerTickRunnable, SHIMMER_TICK_MS)
    }

    private fun stopShimmerChain(reason: String) {
        removeCallbacks(shimmerTickRunnable)
        shimmerLastTickUptimeMs = 0L
        if (shimmerChainActive) {
            shimmerChainActive = false
            val msg = "[SHIMMER] stop reason=$reason"
            YLog.info(msg = msg)
            EventHistoryStore.recordDiagOnly(msg)
        }
    }

    /** 由 Renderer 在前奏倒计时分支按「前奏总长 >= 28s」设置；其余分支不调用（靠 stopAndHide/门控收口） */
    fun setShimmerEnabled(enabled: Boolean) {
        if (!shimmerCapable) return
        // M1：无条件通知（同值也要报，样式/档位靠这条通道在每次 reconcile 时刷新到合并层）
        onShimmerIntentChanged?.invoke(enabled)
        if (shimmerEnabled == enabled) {
            if (enabled) kickShimmer()
            return
        }
        shimmerEnabled = enabled
        if (enabled) {
            shimmerPhaseMs = 0L
            kickShimmer()
        } else {
            stopShimmerChain("disabled")
        }
        if (visibility == VISIBLE) invalidate()
    }

    init {
        isClickable = false
        isFocusable = false
        isLongClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    fun setPreferredWidthPx(widthPx: Int) {
        val clamped = widthPx.coerceAtLeast(0)
        if (preferredWidthPx == clamped) return
        preferredWidthPx = clamped
        requestLayout()
        invalidate()
    }

    fun setAccentColor(color: Int) {
        val opaque = color or 0xFF000000.toInt()
        if (accentColor == opaque) return
        accentColor = opaque
        textPaint.color = opaque
        // v1.4.5-fix12：封面色流光的色源即 accentColor，换色须失效 shader 缓存（换歌时触发，低频）
        invalidateShaderCache()
        if (visibility == VISIBLE) invalidate()
    }

    fun startCountdown(targetMs: Long, playing: Boolean) {
        val targetChanged = targetNextLineMs != targetMs
        val wasHidden = visibility != VISIBLE
        targetNextLineMs = targetMs
        isPlaying = playing
        if (targetChanged || wasHidden) {
            loggedCrossZero = false
            loggedGraceExpired = false
        }
        if (targetMs > 0L) {
            if (visibility != VISIBLE) visibility = VISIBLE
            invalidate()
            kickShimmer()
        } else {
            stopAndHide()
        }
    }

    fun setPlaying(playing: Boolean) {
        if (isPlaying == playing) return
        isPlaying = playing
        if (!playing) {
            removeCallbacks(tickRunnable)
            stopShimmerChain("paused")
        }
        if (visibility == VISIBLE) invalidate()
        if (playing) kickShimmer()
    }

    fun stopAndHide() {
        targetNextLineMs = -1L
        loggedCrossZero = false
        loggedGraceExpired = false
        // v1.4.5-fix11（Quick M2）：流光意图位随停用一并复位，免上一首的 true 漏进下一首 startCountdown 的 kick
        shimmerEnabled = false
        // v1.4.5-fix14 实施审 M1：停用即倒计时段结束，合并层同步收意图（仅流光实例有回调）
        if (shimmerCapable) onShimmerIntentChanged?.invoke(false)
        removeCallbacks(tickRunnable)
        stopShimmerChain("stop_and_hide")
        if (visibility != GONE) {
            visibility = GONE
        }
    }

    override fun onDetachedFromWindow() {
        stopShimmerChain("detached")
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) {
            stopShimmerChain("aggregated_hidden")
        } else {
            kickShimmer()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = textPaint.fontMetrics
        val h = ceil((fm.descent - fm.ascent).toDouble()).toInt() + paddingTop + paddingBottom
        val sample = if (enforceMaxRemainingWindow) "15s" else "180s"
        val naturalW = ceil(textPaint.measureText(sample).toDouble()).toInt() + paddingLeft + paddingRight
        val desiredW = if (preferredWidthPx > 0) preferredWidthPx else naturalW
        setMeasuredDimension(resolveSize(desiredW, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (visibility != VISIBLE || targetNextLineMs <= 0L) return
        com.vivo.islandlyric.util.PerfStats.countdownFrames.incrementAndGet()
        val curPos = positionProvider()
        removeCallbacks(tickRunnable)
        if (curPos < 0L) {
            // v1.3.13：首帧进度尚未就绪时保持 150ms 自刷新链不断，待进度有效即刻出字
            if (isPlaying && visibility == VISIBLE && isAttachedToWindow) {
                postDelayed(tickRunnable, 150L)
            }
            return
        }
        val remaining = targetNextLineMs - curPos

        if (remaining <= 0L) {
            if (!centerInView) {
                return
            }
            if (!loggedCrossZero) {
                loggedCrossZero = true
                onBoundaryEvent?.invoke("countdown_zero_hold", curPos, remaining)
            }
            // v1.4.5-fix7 v3：前奏到点后保留最长 1500ms 的 "1s" 过渡（覆盖在线 +350ms 与无时间轴学习值 +1200ms），超时撤字并上报 grace_expired
            if (remaining <= -1_500L) {
                if (!loggedGraceExpired) {
                    loggedGraceExpired = true
                    onBoundaryEvent?.invoke("grace_expired", curPos, remaining)
                }
                return
            }
        }

        // v1.4.5-fix11：流光垫在数字之下先画（仅前奏实例且门控全开时）；零点保持期 target 仍 >0，照口径持续到 stopAndHide
        // v1.4.5-fix12 样式 6（文字流光）不画背景层，光只在数字身上流动，见下方 drawText 前的 textPaint shader
        val fxActive = shimmerGate()
        // v1.4.5-fix14：合并层接管背景时本视图不画（样式 6 文字流光不受此限、仍在数字上画）
        val layerHandlesBg = shimmerLayerAvailableProvider?.invoke() == true
        if (fxActive && shimmerStyle != 6 && !layerHandlesBg) {
            drawShimmer(canvas)
        }

        val shouldDraw = !enforceMaxRemainingWindow || remaining <= Const.INTRO_COUNTDOWN_MAX_REMAINING_MS
        if (shouldDraw) {
            val sec = if (remaining <= 0L) 1 else ceil(remaining / 1000.0).toInt().coerceAtLeast(1)
            val label = "${sec}s"
            val availH = (height - paddingTop - paddingBottom).toFloat()
            val fm = textPaint.fontMetrics
            val baseline = paddingTop + (availH - (fm.descent - fm.ascent)) * 0.5f - fm.ascent
            val drawX = if (centerInView) {
                paddingLeft + (width - paddingLeft - paddingRight) * 0.5f
            } else {
                (width - paddingRight).toFloat()
            }
            if (fxActive && shimmerStyle == 6) {
                applyTextShimmer(label, drawX)
            } else if (textPaint.shader != null) {
                textPaint.shader = null
            }
            canvas.drawText(label, drawX, baseline, textPaint)
            if (textPaint.shader != null) {
                textPaint.shader = null
            }
        }

        if (isPlaying && visibility == VISIBLE && isAttachedToWindow) {
            postDelayed(tickRunnable, 150L)
        }
    }

    /**
     * v1.4.5-fix12 流光六样式（老大圈定）：横扫/呼吸/极光/封面色/星点/斜扫，强度档位统一调峰值 alpha。
     * 全部视图内自绘：shader 按（样式,带宽,峰值,色源）缓存、每帧只改 localMatrix 或 paint alpha，不分配对象、不用离屏位图。
     */
    private fun drawShimmer(canvas: Canvas) {
        // v1.4.5-fix13 C：绘制移交 ShimmerPainter（与整岛流光共用实现）
        shimmerPainter.draw(
            canvas = canvas,
            w = width.toFloat(),
            h = height.toFloat(),
            phaseMs = shimmerPhaseMs,
            style = shimmerStyle,
            peakAlpha = shimmerPeakAlpha,
            accentColor = accentColor
        )
    }

    private var textShimmerShader: LinearGradient? = null
    // 同 M1 口径：文字流光的 shader 缓存也用基本类型字段比较，不拼字符串键
    private var textCacheSpan = -1f
    private var textCacheAlpha = -1
    private var textCacheRgb = 0
    private val textShimmerMatrix = Matrix()

    /**
     * 文字流光（样式 6）：不画背景层，给倒计时数字的 textPaint 挂流动渐变——封面色打底、
     * 一道向白色靠拢的高光扫过字面；高光靠拢程度随强度档位（峰值 alpha 0x30→0.36 / 0x58→0.65 / 0x88→纯白）。
     * drawText 后由 onDraw 立即摘除 shader，不污染其它绘制。
     */
    private fun applyTextShimmer(label: String, drawX: Float) {
        val textW = textPaint.measureText(label)
        if (textW <= 0f) return
        val textLeft = if (centerInView) drawX - textW * 0.5f else drawX - textW
        val span = textW * 1.6f
        if (textShimmerShader == null || textCacheSpan != span ||
            textCacheAlpha != shimmerPeakAlpha || textCacheRgb != accentColor
        ) {
            textShimmerShader = LinearGradient(
                0f, 0f, span, 0f,
                // v1.4.5-fix12（Quick 收尾审 S1）：提亮系数下限 0.5，免「淡」档在亮封面色上高光看不出来
                // 注：分母须写十进制 136f——0x88f 会被 Kotlin 当作十六进制整数 2191（构建 #378 实证）
                intArrayOf(accentColor, ShimmerPainter.blendToWhite(accentColor, (shimmerPeakAlpha / 136f).coerceAtLeast(0.5f)), accentColor),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            textCacheSpan = span
            textCacheAlpha = shimmerPeakAlpha
            textCacheRgb = accentColor
        }
        val cycle = textW + span
        val off = (shimmerPhaseMs % SHIMMER_PERIOD_MS).toFloat() / SHIMMER_PERIOD_MS.toFloat() * cycle - span
        textShimmerMatrix.reset()
        textShimmerMatrix.setTranslate(textLeft + off, 0f)
        textShimmerShader?.setLocalMatrix(textShimmerMatrix)
        textPaint.shader = textShimmerShader
    }

    private companion object {
        const val SHIMMER_TICK_MS = 33L
        const val SHIMMER_PERIOD_MS = 2_600L
    }
}
