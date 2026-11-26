package com.vivo.islandlyric.hook

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * v1.4.5-fix13 C（Quick 裁决：抽共用，禁复制）：流光七样式的背景层绘制器，倒计时视图与整岛流光共用同一实现，
 * 改样式只改这一处、两边永远同步。样式 6（文字）是倒计时数字专属、不走背景层，draw 内部按横扫处理。
 *
 * 口径与 fix12 一致：shader 按（样式,尺寸,峰值 alpha,色源）基本类型字段缓存、每帧只改 localMatrix 或 paint alpha，
 * 不分配对象、不用离屏位图。globalX 为绘制面在共享坐标系（整岛并集）中的左缘偏移——带状/极光的相位按全局坐标
 * 计算，左右两壳各画一段、看起来是一道光跨过整岛；alphaScale 为整岛让位/压暗用的整体透明度系数。
 */
class ShimmerPainter {

    /**
     * 边缘遮罩：ELLIPSE＝径向椭圆（框内自画降级用）；NONE＝不加遮罩；
     * INNER_LEFT/INNER_RIGHT＝靠摄像头一侧 24px 线性渐隐（fix16，Quick 二轮裁决：对样式 0–5 全部生效）——
     * INNER_LEFT 指内侧边在本绘制面左缘（右壳），INNER_RIGHT 指内侧边在右缘（左壳）；光到边前淡到 0，
     * 子 View 内侧改直角裁剪后切边不可见
     */
    enum class EdgeMask { ELLIPSE, NONE, INNER_LEFT, INNER_RIGHT }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var shader: Shader? = null
    @Suppress("unused")
    private var shaderBandW: Float = 0f

    private var cacheStyle = -1
    private var cacheW = -1f
    private var cacheH = -1f
    private var cacheAlpha = -1
    private var cacheRgb = 0

    private var flowShaderRef: LinearGradient? = null
    // v1.4.5-fix14（Quick 裁决）：径向椭圆遮罩取代两条线性渐隐——可分离乘积的等值线是十字/菱形、治不了「框」
    private var ellipseMask: RadialGradient? = null
    private var ellipseMaskW = -1f
    private var ellipseMaskH = -1f
    private val ellipseMatrix = Matrix()

    // v1.4.5-fix17：内侧收口遮罩（与摄像头同心的径向圆弧切；几何异常回退 fix16 线性软边），按 (w, 方向, 圆心键) 缓存
    private var innerMask: Shader? = null
    private var innerMaskW = -1f
    private var innerMaskKind: EdgeMask? = null
    private var innerMaskKey: String = ""

    // 本帧绘制参数（draw 入口写入，子函数读取，免逐层传参）
    private var curPhaseMs: Long = 0L
    private var curStyle: Int = 0
    private var curPeakAlpha: Int = 0x30
    private var curAccent: Int = Color.WHITE
    private var curGlobalX: Float = 0f
    // v1.4.5-fix15（Quick 裁决）：并集总宽——带状/极光的行程与跨度按它算，带宽仍按单壳宽（保视觉宽度）
    private var curTotalW: Float = 0f
    private var cacheTotalW: Float = -1f
    private var curAlphaScale: Float = 1f
    private var curEdgeMask: EdgeMask = EdgeMask.ELLIPSE
    // v1.4.5-fix18 实施审 M1：实际落笔横向范围（本地坐标）——裁剪扩到间隙后，drawRect/星点必须同步
    // 扩到同一范围，否则裁剪再大也没有像素落进间隙（Quick 实证 fix18 初版贯通不成立即此因）
    private var curDrawL: Float = 0f
    private var curDrawR: Float = -1f

    fun invalidateCache() {
        cacheStyle = -1
        shader = null
        shaderBandW = 0f
        flowShaderRef = null
    }

    private var cacheEdgeMask: EdgeMask? = null

    private fun shaderStale(rgb: Int, w: Float, h: Float): Boolean =
        shader == null || cacheStyle != curStyle || cacheW != w || cacheH != h ||
            cacheAlpha != curPeakAlpha || cacheRgb != rgb || cacheEdgeMask != curEdgeMask ||
            cacheTotalW != totalWBucket(curTotalW)

    private fun markShaderCached(rgb: Int, w: Float, h: Float) {
        cacheStyle = curStyle
        cacheW = w
        cacheH = h
        cacheAlpha = curPeakAlpha
        cacheRgb = rgb
        cacheEdgeMask = curEdgeMask
        cacheTotalW = totalWBucket(curTotalW)
    }

    private fun totalWBucket(v: Float): Float = (v / 8f).toInt() * 8f

    private fun peakColor(rgb: Int): Int = (curPeakAlpha shl 24) or (rgb and 0x00FFFFFF)

    fun draw(
        canvas: Canvas,
        w: Float,
        h: Float,
        phaseMs: Long,
        style: Int,
        peakAlpha: Int,
        accentColor: Int,
        globalX: Float = 0f,
        alphaScale: Float = 1f,
        edgeMask: EdgeMask = EdgeMask.ELLIPSE,
        // 并集总宽（整岛两壳并集）；框内降级自画不传、默认等于自身宽，行为与旧版一致
        totalW: Float = w,
        // v1.4.5-fix18 实施审 M1：落笔横向范围（本地坐标），默认本 View 全宽；贯通时传本侧半幅
        drawL: Float = 0f,
        drawR: Float = -1f
    ) {
        if (w <= 0f || h <= 0f) return
        curPhaseMs = phaseMs
        curStyle = style
        curPeakAlpha = peakAlpha
        curAccent = accentColor
        curGlobalX = globalX
        curTotalW = if (totalW > 0f) totalW else w
        curAlphaScale = alphaScale.coerceIn(0f, 1f)
        curEdgeMask = edgeMask
        curDrawL = drawL
        curDrawR = if (drawR > drawL) drawR else w
        when (style) {
            1 -> drawBreathe(canvas, w, h)
            2 -> drawAurora(canvas, w, h)
            // 封面色混 30% 白作亮度下限（fix12 增补审 S4）
            3 -> drawBand(canvas, w, h, blendToWhite(accentColor, 0.3f), diagonal = false)
            4 -> drawSparkle(canvas, w, h)
            5 -> drawBand(canvas, w, h, Color.WHITE, diagonal = true)
            // 样式 6（文字）无背景层，整岛侧按横扫呈现
            else -> drawBand(canvas, w, h, Color.WHITE, diagonal = false)
        }
    }

    /**
     * v1.4.5-fix14（Quick 裁决）：径向椭圆遮罩与光带 MULTIPLY 合成——单位圆 RadialGradient 经矩阵压成贴合
     * 区域的椭圆，色标按 smoothstep 取样（中心缓顶、向外平滑落到 0），任何方向都没有直边、到区域边界
     * alpha 恒为 0，「被长方形框住」从根上消失。旧两条线性渐隐（各带大平台、可分离乘积成十字）已弃。
     */
    /**
     * v1.4.5-fix17（老大 2026-11-25 点名「切成摄像头那样」，取代 fix16 的 24px 直线软边）：
     * 内侧收口改与摄像头同心的圆弧切——摄像头位于并集水平正中（curTotalW/2），换算到本壳局部坐标后
     * 以其为圆心作径向渐变：靠内侧中段（距圆心最近）alpha 最低、上下角与深处渐满，光带末端像被
     * 摄像头圆孔切掉一口。几何异常（并集未知/圆心距边离谱）时回退 fix16 的直线软边。
     */
    private fun innerFadeMask(w: Float, h: Float): Shader? {
        val kind = curEdgeMask
        if (kind != EdgeMask.INNER_LEFT && kind != EdgeMask.INNER_RIGHT) return null
        val camCx = curTotalW / 2f - curGlobalX
        val camCy = h / 2f
        val rEdge = if (kind == EdgeMask.INNER_LEFT) kotlin.math.abs(camCx) else kotlin.math.abs(camCx - w)
        val cacheKey = "${camCx.toInt()}:${camCy.toInt()}:${rEdge.toInt()}"
        if (innerMask == null || innerMaskW != w || innerMaskKind != kind || innerMaskKey != cacheKey) {
            innerMask = if (curTotalW > w + 1f && rEdge in 30f..200f) {
                val rStart = (rEdge - ARC_BITE_PX).coerceAtLeast(1f)
                val rEnd = rEdge + ARC_FADE_OUT_PX
                RadialGradient(
                    camCx, camCy, rEnd,
                    intArrayOf(Color.TRANSPARENT, Color.WHITE),
                    floatArrayOf(rStart / rEnd, 1f),
                    Shader.TileMode.CLAMP
                )
            } else if (kind == EdgeMask.INNER_LEFT) {
                LinearGradient(
                    0f, 0f, INNER_FADE_PX, 0f,
                    intArrayOf(Color.TRANSPARENT, Color.WHITE),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            } else {
                LinearGradient(
                    w, 0f, w - INNER_FADE_PX, 0f,
                    intArrayOf(Color.TRANSPARENT, Color.WHITE),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            innerMaskW = w
            innerMaskKind = kind
            innerMaskKey = cacheKey
        }
        return innerMask
    }

    private fun fadedFlow(band: LinearGradient, w: Float, h: Float): Shader {
        if (curEdgeMask == EdgeMask.NONE) return band
        innerFadeMask(w, h)?.let { return ComposeShader(band, it, PorterDuff.Mode.MULTIPLY) }
        if (ellipseMask == null || ellipseMaskW != w || ellipseMaskH != h) {
            val mask = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(
                    0xFFFFFFFF.toInt(),
                    0xF2FFFFFF.toInt(),
                    0xB3FFFFFF.toInt(),
                    0x59FFFFFF.toInt(),
                    0x1AFFFFFF.toInt(),
                    0x00FFFFFF
                ),
                floatArrayOf(0f, 0.3f, 0.55f, 0.75f, 0.9f, 1f),
                Shader.TileMode.CLAMP
            )
            ellipseMatrix.reset()
            ellipseMatrix.setScale(w * 0.5f, h * 0.5f)
            ellipseMatrix.postTranslate(w * 0.5f, h * 0.5f)
            mask.setLocalMatrix(ellipseMatrix)
            ellipseMask = mask
            ellipseMaskW = w
            ellipseMaskH = h
        }
        return ComposeShader(band, ellipseMask!!, PorterDuff.Mode.MULTIPLY)
    }

    /** 横扫（0）/ 封面色（3）/ 斜扫（5）共用带状扫光；相位按全局坐标，整岛两壳连续 */
    private fun drawBand(canvas: Canvas, w: Float, h: Float, rgb: Int, diagonal: Boolean) {
        val bandW = if (diagonal) (w + h) * 0.6f else w * 0.6f
        if (shaderStale(rgb, w, h)) {
            val band = LinearGradient(
                0f, 0f, bandW, 0f,
                intArrayOf(Color.TRANSPARENT, peakColor(rgb), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            flowShaderRef = band
            shader = fadedFlow(band, w, h)
            shaderBandW = bandW
            markShaderCached(rgb, w, h)
            paint.shader = shader
        }
        // 斜扫循环行程按旋转角放大，免接缝带边落回视图内跳变（fix12 增补审 S5）
        // v1.4.5-fix15：行程改按并集总宽——fix14 按单壳宽算又减 root 坐标 globalX，右壳峰值永远在画布外、左壳只到一半
        val cycle = if (diagonal) {
            ((curTotalW + bandW) / cos(0.3491) + h * tan(0.3491)).toFloat()
        } else {
            curTotalW + bandW
        }
        val offset = (curPhaseMs % SHIMMER_PERIOD_MS).toFloat() / SHIMMER_PERIOD_MS.toFloat() * cycle - bandW - curGlobalX
        matrix.reset()
        matrix.setTranslate(offset, 0f)
        if (diagonal) {
            // v1.4.5-fix16（Quick 二轮裁决新核 bug）：旋转中心改用并集中心在本壳坐标的位置——
            // 原先各壳绕自己中心转，同一条斜线在右壳上下错位、摄像头两侧接不上
            matrix.postRotate(20f, curTotalW * 0.5f - curGlobalX, h * 0.5f)
        }
        flowShaderRef?.setLocalMatrix(matrix)
        paint.alpha = (255f * curAlphaScale).toInt().coerceIn(0, 255)
        try {
            canvas.drawRect(curDrawL, 0f, curDrawR, h, paint)
        } finally {
            paint.alpha = 255
        }
    }

    /** 呼吸（1）：中心径向柔光，3.2s 一明一暗（缓存键含 w/h，fix12 增补审 S1） */
    private fun drawBreathe(canvas: Canvas, w: Float, h: Float) {
        val radius = maxOf(w, h) * 0.75f
        if (shaderStale(Color.WHITE, w, h)) {
            val radial = RadialGradient(
                w * 0.5f, h * 0.5f, radius,
                intArrayOf(peakColor(Color.WHITE), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            // v1.4.5-fix16：呼吸是径向光、内侧边缘同样会被硬切，软边一并乘上（fix17 起为摄像头圆弧切）
            val mask = innerFadeMask(w, h)
            shader = if (mask != null) ComposeShader(radial, mask, PorterDuff.Mode.MULTIPLY) else radial
            shaderBandW = radius
            markShaderCached(Color.WHITE, w, h)
            paint.shader = shader
        }
        val t = (curPhaseMs % 3_200L).toFloat() / 3_200f
        val pulse = 0.55f + 0.45f * sin(t * 2.0 * Math.PI).toFloat()
        paint.alpha = (pulse * 255f * curAlphaScale).toInt().coerceIn(0, 255)
        try {
            canvas.drawRect(curDrawL, 0f, curDrawR, h, paint)
        } finally {
            paint.alpha = 255
        }
    }

    /** 极光（2）：青→紫→粉宽幅渐变，5s 往返流动 */
    private fun drawAurora(canvas: Canvas, w: Float, h: Float) {
        // v1.4.5-fix15：跨度按并集总宽×1.6（Quick 裁决：铺满整颗药丸后 2.2 太稀）
        val span = curTotalW * 1.6f
        if (shaderStale(0, w, h)) {
            val flow = LinearGradient(
                0f, 0f, span, 0f,
                intArrayOf(
                    Color.TRANSPARENT,
                    peakColor(0x40E0D0),
                    peakColor(0x9370FF),
                    peakColor(0xFF6EB4),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.3f, 0.55f, 0.8f, 1f),
                Shader.TileMode.CLAMP
            )
            flowShaderRef = flow
            shader = fadedFlow(flow, w, h)
            shaderBandW = span
            markShaderCached(0, w, h)
            paint.shader = shader
        }
        val t = (curPhaseMs % 5_000L).toFloat() / 5_000f
        val tri = if (t < 0.5f) t * 2f else (1f - t) * 2f
        val offset = -(span - curTotalW) * tri - curGlobalX
        matrix.reset()
        matrix.setTranslate(offset, 0f)
        flowShaderRef?.setLocalMatrix(matrix)
        paint.alpha = (255f * curAlphaScale).toInt().coerceIn(0, 255)
        try {
            canvas.drawRect(curDrawL, 0f, curDrawR, h, paint)
        } finally {
            paint.alpha = 255
        }
    }

    /** 星点（4）：12 颗定点星尘各自闪烁（位置/相位由固定种子推导，零随机、零分配；整岛侧按 globalX 错开种子免两壳同图） */
    private fun drawSparkle(canvas: Canvas, w: Float, h: Float) {
        paint.shader = null
        val rBase = h * 0.055f
        // 星点 alpha 单独 ×2 封顶 255（fix12 增补审 S3）
        val sparkPeak = (curPeakAlpha * 2 * curAlphaScale).toInt().coerceAtMost(255)
        val seedShift = (curGlobalX / 10f).toInt()
        for (i in 0 until 12) {
            val fx = ((i * 37 + 13 + seedShift) % 89 + 5) / 100f
            val fy = ((i * 53 + 29) % 71 + 15) / 100f
            val tw = sin((curPhaseMs + i * 397L).toFloat() / 2_200f * 2f * Math.PI.toFloat())
            var a = (sparkPeak * ((tw * 0.5f + 0.5f) * (tw * 0.5f + 0.5f))).toInt()
            // v1.4.5-fix16：星点落在内侧软边带内时按距离衰减，免得被壳边硬切一半
            // v1.4.5-fix18 实施审 M1：星点分布改按落笔范围（含间隙半幅），否则间隙里永远没有星点
            val xPx = curDrawL + (curDrawR - curDrawL) * fx
            val fadeF = when (curEdgeMask) {
                EdgeMask.INNER_LEFT -> (xPx / INNER_FADE_PX).coerceIn(0f, 1f)
                EdgeMask.INNER_RIGHT -> ((w - xPx) / INNER_FADE_PX).coerceIn(0f, 1f)
                else -> 1f
            }
            a = (a * fadeF).toInt()
            if (a <= 4) continue
            paint.color = (a shl 24) or 0x00FFFFFF
            val r = rBase * (0.7f + 0.3f * ((i * 29) % 10) / 10f)
            canvas.drawCircle(xPx, h * fy, r, paint)
        }
        paint.color = Color.WHITE
    }

    companion object {
        const val SHIMMER_PERIOD_MS = 2_600L

        /** 内侧软边宽度（物理 px，Quick 二轮裁决 24px） */
        const val INNER_FADE_PX = 24f

        // v1.4.5-fix17：摄像头圆弧切——渐变起始在内侧边距圆心距离内缩 ARC_BITE_PX、向外 ARC_FADE_OUT_PX 渐满
        const val ARC_BITE_PX = 12f
        const val ARC_FADE_OUT_PX = 36f

        fun blendToWhite(color: Int, frac: Float): Int {
            val f = frac.coerceIn(0f, 1f)
            val r = (Color.red(color) + (255 - Color.red(color)) * f).toInt()
            val g = (Color.green(color) + (255 - Color.green(color)) * f).toInt()
            val b = (Color.blue(color) + (255 - Color.blue(color)) * f).toInt()
            return Color.rgb(r, g, b)
        }

        /** 强度档位 → 峰值 alpha（与 fix12 口径一致：淡 0x30 / 中 0x58 / 亮 0x88） */
        fun peakAlphaForLevel(level: Int): Int = when (level) {
            1 -> 0x58
            2 -> 0x88
            else -> 0x30
        }
    }
}
