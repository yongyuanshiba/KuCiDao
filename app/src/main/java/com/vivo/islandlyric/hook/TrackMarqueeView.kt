package com.vivo.islandlyric.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.vivo.islandlyric.Const
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 宽形态左侧歌名 / 歌手自绘单行循环滚动视图（C 项 + v1.2.3 E 项短文本居中）：
 * - 不依赖 SystemUI 悬浮窗窗口焦点，100% 稳定触发；
 * - 文本不超宽（静态情形）：按 centerWhenStatic 独立水平居中或左对齐，零帧循环、零耗电；
 * - 文本超宽：忽略对齐偏好，起步停留 900ms 后按恒速（26dp/s）携带 28dp 尾部留白无缝循环滚动。
 */
class TrackMarqueeView(
    context: Context,
    textSizeSp: Float,
    private val textColor: Int
) : View(context) {

    private val speedPxPerMs: Float
    private val loopGapPx: Float
    private val fadeEdgePx: Float

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.LEFT
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            textSizeSp,
            context.resources.displayMetrics
        )
    }

    private var textValue: String = ""
    private var measuredTextWidth: Float = 0f
    private var centerWhenStatic: Boolean = Const.LEFT_CENTER_ALIGN_DEFAULT
    private var offsetPx: Float = 0f
    private var holdElapsedMs: Long = 0L
    private var lastFrameUptimeMs: Long = 0L

    private var cachedFadeShader: LinearGradient? = null
    private var cachedShaderWidth: Int = -1

    init {
        isClickable = false
        isFocusable = false
        isLongClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)

        val dm = context.resources.displayMetrics
        val speedPxPerSec = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            Const.LEFT_MARQUEE_SPEED_DP_PER_SEC,
            dm
        )
        speedPxPerMs = speedPxPerSec / 1000f
        loopGapPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            Const.LEFT_MARQUEE_GAP_DP,
            dm
        )
        fadeEdgePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            Const.LEFT_MARQUEE_FADE_EDGE_DP,
            dm
        )
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    fun currentText(): String = textValue

    fun currentTextSizePx(): Int = paint.textSize.roundToInt()

    fun setCenterWhenStatic(enabled: Boolean) {
        if (centerWhenStatic == enabled) return
        centerWhenStatic = enabled
        if (visibility == VISIBLE) {
            invalidate()
        }
    }

    fun setText(newText: String) {
        if (newText == textValue) return
        textValue = newText
        measuredTextWidth = if (newText.isNotEmpty()) paint.measureText(newText) else 0f
        offsetPx = 0f
        holdElapsedMs = 0L
        lastFrameUptimeMs = SystemClock.uptimeMillis()
        requestLayout()
        if (visibility == VISIBLE) {
            postInvalidateOnAnimation()
        }
    }

    fun clearText() {
        textValue = ""
        measuredTextWidth = 0f
        offsetPx = 0f
        holdElapsedMs = 0L
        lastFrameUptimeMs = 0L
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameUptimeMs = SystemClock.uptimeMillis()
        if (visibility == VISIBLE && textValue.isNotEmpty()) {
            postInvalidateOnAnimation()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = paint.fontMetrics
        val desiredHeight = ceil((fm.descent - fm.ascent).toDouble()).toInt() + paddingTop + paddingBottom
        val desiredWidth = ceil(measuredTextWidth.toDouble()).toInt() + paddingLeft + paddingRight
        val w = resolveSize(desiredWidth, widthMeasureSpec)
        val h = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) {
            cachedFadeShader = null
            cachedShaderWidth = -1
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (visibility != VISIBLE || textValue.isEmpty()) return
        val availW = (width - paddingLeft - paddingRight).toFloat()
        val availH = (height - paddingTop - paddingBottom).toFloat()
        if (availW <= 1f || availH <= 1f) return

        val fm = paint.fontMetrics
        val baseline = paddingTop + (availH - (fm.descent - fm.ascent)) * 0.5f - fm.ascent

        // E 项：短文本不超宽时按偏好独立居中或左对齐，零动画
        if (measuredTextWidth <= availW + 0.5f) {
            paint.shader = null
            paint.color = textColor
            val drawX = if (centerWhenStatic) {
                paddingLeft + (availW - measuredTextWidth) * 0.5f
            } else {
                paddingLeft.toFloat()
            }
            canvas.drawText(textValue, drawX, baseline, paint)
            return
        }

        // 长文本超宽：恒速循环滚动 + 首尾留白（不受居中开关影响）
        val now = SystemClock.uptimeMillis()
        val deltaMs = if (lastFrameUptimeMs > 0L) (now - lastFrameUptimeMs).coerceIn(0L, 120L) else 0L
        lastFrameUptimeMs = now

        val cycleSpan = measuredTextWidth + loopGapPx
        if (holdElapsedMs < Const.LEFT_MARQUEE_START_HOLD_MS) {
            holdElapsedMs += deltaMs
        } else {
            offsetPx += speedPxPerMs * deltaMs
            if (offsetPx >= cycleSpan) {
                offsetPx -= cycleSpan
            }
        }

        ensureFadeShader()
        paint.shader = cachedFadeShader

        val sc = canvas.save()
        canvas.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        val x1 = paddingLeft - offsetPx
        canvas.drawText(textValue, x1, baseline, paint)
        val x2 = x1 + cycleSpan
        if (x2 < width - paddingRight) {
            canvas.drawText(textValue, x2, baseline, paint)
        }
        canvas.restoreToCount(sc)

        if (visibility == VISIBLE && isAttachedToWindow) {
            postInvalidateOnAnimation()
        }
    }

    private fun ensureFadeShader() {
        val w = width
        if (w <= 0) {
            cachedFadeShader = null
            return
        }
        if (cachedFadeShader != null && cachedShaderWidth == w) return
        val edgeRatio = (fadeEdgePx / w.toFloat()).coerceIn(0.02f, 0.20f)
        val transparent = textColor and 0x00FFFFFF
        cachedFadeShader = LinearGradient(
            0f,
            0f,
            w.toFloat(),
            0f,
            intArrayOf(transparent, textColor, textColor, transparent),
            floatArrayOf(0f, edgeRatio, 1f - edgeRatio, 1f),
            Shader.TileMode.CLAMP
        )
        cachedShaderWidth = w
    }
}
