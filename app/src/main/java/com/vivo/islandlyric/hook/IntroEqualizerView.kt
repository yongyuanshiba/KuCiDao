package com.vivo.islandlyric.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.vivo.islandlyric.Const
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 宽形态前奏等化器动效 + 学习型倒计时视图（v1.2.4 双侧复用）：
 * - 左侧实例：5 根竖条（宽 3dp、间距 2dp、最大高 min(14dp, 可用高)），不显示小数字；
 * - 右侧实例：9 根宽幅竖条（宽 3dp、间距 3dp、最大高 min(18dp, 可用高)），已武装且 remaining ∈ (0, 15000]ms 时
 *   在竖条右侧显示 16sp 大号 ceil 秒数；当歌词区宽滑杆调至下限 52dp 时自动按 availW 收紧条间距，52~95dp 全档位不裁切；
 * - 暂停冻结低位停绘省电，隐藏/首行到达立即停净。
 */
class IntroEqualizerView(
    context: Context,
    private val positionProvider: () -> Long,
    private val barCount: Int = Const.INTRO_EQ_BAR_COUNT,
    barWidthDp: Float = Const.INTRO_EQ_BAR_WIDTH_DP,
    barGapDp: Float = Const.INTRO_EQ_BAR_GAP_DP,
    maxBarHeightDp: Float = Const.INTRO_EQ_BAR_MAX_HEIGHT_DP,
    countdownTextSizeSp: Float = Const.INTRO_COUNTDOWN_TEXT_SIZE_SP,
    private val showCountdown: Boolean = false
) : View(context) {

    private val baseBarWidthPx: Float
    private val baseBarGapPx: Float
    private val maxBarHeightPx: Float
    private val textGapPx: Float
    private val barRect = RectF()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Const.KARAOKE_AMBER_FALLBACK
    }

    private val countdownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Const.KARAOKE_AMBER_FALLBACK
        textAlign = Paint.Align.LEFT
        typeface = Typeface.DEFAULT_BOLD
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            countdownTextSizeSp,
            context.resources.displayMetrics
        )
    }

    private var preferredWidthPx: Int = 0
    private var accentColor: Int = Const.KARAOKE_AMBER_FALLBACK
    private var isPlaying: Boolean = false
    private var centerAlign: Boolean = true
    private var learnedFirstLineMs: Long = -1L
    private var animStartUptimeMs: Long = SystemClock.uptimeMillis()

    init {
        isClickable = false
        isFocusable = false
        isLongClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)

        val dm = context.resources.displayMetrics
        baseBarWidthPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, barWidthDp, dm)
        baseBarGapPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, barGapDp, dm)
        maxBarHeightPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, maxBarHeightDp, dm)
        textGapPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, dm)
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
        barPaint.color = opaque
        countdownPaint.color = opaque
        if (visibility == VISIBLE) {
            invalidate()
        }
    }

    fun setCenterAlign(enabled: Boolean) {
        if (centerAlign == enabled) return
        centerAlign = enabled
        if (visibility == VISIBLE) {
            invalidate()
        }
    }

    fun startIntro(learnedFirstLineMs: Long, playing: Boolean) {
        this.learnedFirstLineMs = learnedFirstLineMs
        this.isPlaying = playing
        this.animStartUptimeMs = SystemClock.uptimeMillis()
        if (visibility != VISIBLE) {
            visibility = VISIBLE
        }
        postInvalidateOnAnimation()
    }

    fun setPlaying(playing: Boolean) {
        if (isPlaying == playing) return
        isPlaying = playing
        if (playing) {
            animStartUptimeMs = SystemClock.uptimeMillis()
        }
        if (visibility == VISIBLE) {
            postInvalidateOnAnimation()
        }
    }

    fun stopAndHide() {
        learnedFirstLineMs = -1L
        if (visibility != GONE) {
            visibility = GONE
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = countdownPaint.fontMetrics
        val textH = if (showCountdown) ceil((fm.descent - fm.ascent).toDouble()).toInt() else 0
        val desiredH = max(ceil(maxBarHeightPx.toDouble()).toInt(), textH) + paddingTop + paddingBottom
        val barsW = barCount * baseBarWidthPx + max(0, barCount - 1) * baseBarGapPx
        val extraW = if (showCountdown) textGapPx + countdownPaint.measureText("15s") else 0f
        val naturalW = ceil((barsW + extraW).toDouble()).toInt() + paddingLeft + paddingRight
        val desiredW = if (preferredWidthPx > 0) preferredWidthPx else naturalW
        setMeasuredDimension(
            resolveSize(desiredW, widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (visibility != VISIBLE) return
        val availW = (width - paddingLeft - paddingRight).toFloat()
        val availH = (height - paddingTop - paddingBottom).toFloat()
        if (availW <= 1f || availH <= 1f) return

        val effectiveMaxBarH = min(maxBarHeightPx, availH).coerceAtLeast(baseBarWidthPx)
        val countdownLabel = if (showCountdown) resolveCountdownLabel() else null
        val textW = if (countdownLabel != null) countdownPaint.measureText(countdownLabel) else 0f
        val reservedTextW = if (countdownLabel != null) textW + textGapPx else 0f
        val availForBars = (availW - reservedTextW).coerceAtLeast(barCount * 2f)

        // 当右侧歌词区宽滑杆调至下限 52dp 时自动按 availW 收紧条间距（必要时微缩条宽），确保全档位不裁切
        var effBarW = baseBarWidthPx
        var effGap = baseBarGapPx
        val gapsCount = max(0, barCount - 1)
        val idealBarsW = barCount * effBarW + gapsCount * effGap
        if (idealBarsW > availForBars && gapsCount > 0) {
            val maxGap = (availForBars - barCount * effBarW) / gapsCount.toFloat()
            if (maxGap >= 1.5f) {
                effGap = maxGap
            } else {
                effGap = 1.5f
                effBarW = ((availForBars - gapsCount * effGap) / barCount.toFloat()).coerceAtLeast(2f)
            }
        }

        val barsTotalW = barCount * effBarW + gapsCount * effGap
        val contentW = barsTotalW + reservedTextW

        val startX = if (centerAlign && contentW < availW) {
            paddingLeft + (availW - contentW) * 0.5f
        } else {
            paddingLeft.toFloat()
        }
        val bottomY = paddingTop + (availH + effectiveMaxBarH) * 0.5f
        val radius = effBarW * 0.5f

        val now = SystemClock.uptimeMillis()
        val period = Const.INTRO_EQ_PERIOD_MS.toDouble()
        val basePhase = ((now - animStartUptimeMs) % Const.INTRO_EQ_PERIOD_MS).toDouble() / period

        for (i in 0 until barCount) {
            val ratio = if (isPlaying) {
                val phOffset = (i.toDouble() / barCount.toDouble()) + (i % 2) * 0.13
                val ph = (basePhase + phOffset) * 2.0 * PI
                val norm = ((sin(ph) + 1.0) * 0.5).toFloat()
                0.24f + 0.76f * norm
            } else {
                0.24f
            }
            val barH = max(effBarW, effectiveMaxBarH * ratio)
            val l = startX + i * (effBarW + effGap)
            barRect.set(l, bottomY - barH, l + effBarW, bottomY)
            canvas.drawRoundRect(barRect, radius, radius, barPaint)
        }

        if (countdownLabel != null) {
            val fm = countdownPaint.fontMetrics
            val baseline = paddingTop + (availH - (fm.descent - fm.ascent)) * 0.5f - fm.ascent
            canvas.drawText(countdownLabel, startX + barsTotalW + textGapPx, baseline, countdownPaint)
        }

        if (isPlaying && visibility == VISIBLE && isAttachedToWindow) {
            postInvalidateOnAnimation()
        }
    }

    private fun resolveCountdownLabel(): String? {
        val targetMs = learnedFirstLineMs
        if (targetMs <= 0L) return null
        val curPos = positionProvider()
        if (curPos < 0L) return null
        val remaining = targetMs - curPos
        if (remaining <= 0L || remaining > Const.INTRO_COUNTDOWN_MAX_REMAINING_MS) return null
        val sec = ceil(remaining / 1000.0).toInt().coerceAtLeast(1)
        return "${sec}s"
    }
}
