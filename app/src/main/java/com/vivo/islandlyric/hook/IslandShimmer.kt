package com.vivo.islandlyric.hook

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.highcapable.yukihookapi.hook.log.YLog
import com.vivo.islandlyric.util.EventHistoryStore
import java.lang.ref.WeakReference

/**
 * v1.4.5-fix14 流光层（Quick fix14 裁决 C 等层实现＋追加裁决「突破框」合并版）：
 * fix13 的 LayerDrawable 方案真机实证无处可挂（两壳 background 恒为 null），改往两壳各注入一个 index 0
 * 子 View——画在「壳自身背景/onDraw 之上、其余子 View（歌词歌名）之下」，与 dispatchDraw super 之前同层。
 * 倒计时的背景流光（样式 0–5）也搬到这一层画（用户原话「不能突破那个框吗」）：光以整颗药丸为画布、
 * 只受药丸轮廓裁剪；倒计时视图只在子 View 层不可用时才用框内椭圆遮罩降级自画（已交付功能不许消失）。
 *
 * 仲裁（Quick 追加裁决）：sync（整岛）与 syncCountdown（倒计时）两入口只存输入，resolve() 统一裁决——
 * 倒计时优先、其次整岛、都不亮则淡出停；两路各一个 Painter，切换用权重 200ms 交叉淡化（原让位逻辑
 * 就此作废删除）；相位只在帧循环真正冷启时清零，模式切换连续；门控基线（可见/宽态/scale/播放）在
 * sync 的 gateOk 里由 Renderer 给出、resolve 只判一次。
 *
 * 实施审必须改（已落地）：C-M1 只注入 FrameLayout（layoutTransition 为 null）、子 View MATCH_PARENT 且
 * onMeasure 非 EXACTLY 报 0（永不反噬壳测量）；C-M2 换壳先摘旧、按 tag 去重；C-M3 canvas.save 在 try
 * 外、restore 在 finally；C-S1 门控关/全量还原只停画不拆子 View，真正摘除只在两流光开关都关（detachAll）、
 * 换壳、draw_error 时；C-S2 逐帧坐标数组复用。
 */
class IslandShimmerChildView(
    context: android.content.Context,
    internal val manager: IslandShimmerManager,
    private val isLeft: Boolean
) : View(context) {

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** C-M1：非 EXACTLY 一律报 0——第一轮测量对壳尺寸零影响；壳定型后 FrameLayout 以 EXACTLY 复测 MATCH_PARENT 子 View 自然铺满 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mw = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(widthMeasureSpec)
        } else {
            0
        }
        val mh = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            0
        }
        setMeasuredDimension(mw, mh)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        manager.drawChild(canvas, isLeft, width.toFloat(), height.toFloat())
    }
}

class IslandShimmerManager {

    private val painterIsland = ShimmerPainter()
    private val painterCountdown = ShimmerPainter()
    private val clipPath = Path()
    private val lensPath = Path()
    private val halfPath = Path()
    private val clipRect = RectF()
    // C-S2：逐帧复用的坐标缓冲
    private val rootLoc = IntArray(2)
    private val shellLoc = IntArray(2)

    private var rootRef: WeakReference<View>? = null
    private var leftShellRef: WeakReference<View>? = null
    private var rightShellRef: WeakReference<View>? = null
    private var leftChildRef: WeakReference<IslandShimmerChildView>? = null
    private var rightChildRef: WeakReference<IslandShimmerChildView>? = null

    // 两路输入（只存不裁决，resolve 统一处理）
    private var gateOk: Boolean = false
    private var islandWanted: Boolean = false
    private var islandStyle: Int = 0
    private var islandPeak: Int = 0x30
    private var islandAccent: Int = Color.WHITE
    private var countdownWanted: Boolean = false
    private var countdownStyle: Int = 0
    private var countdownPeak: Int = 0x30
    private var countdownAccent: Int = Color.WHITE

    // 交叉淡化权重（0..1）：目标由 resolve 给出，tick 按 200ms 全程推进
    private var islandWeight: Float = 0f
    private var countdownWeight: Float = 0f

    private var loopRunning: Boolean = false
    private var drawDisabled: Boolean = false
    private var phaseMs: Long = 0L
    private var lastFrameUptimeMs: Long = 0L
    private var leftGlobalX: Float = 0f
    private var rightGlobalX: Float = 0f

    // v1.4.5-fix17：最近一次真正注入子 View 的时刻（[TPL_DETACH] 的 msSinceLastInject 用）
    @Volatile
    var lastInjectElapsedMs: Long = 0L
        private set

    // v1.4.5-fix15：并集坐标在 tick 与 globalX 同时算出（Quick 裁决：不许用 sync 时缓存值）
    private var unionLeft: Float = 0f
    private var unionW: Float = 0f
    // v1.4.5-fix18 实施审 M2：两壳累计 scaleX（视觉量→本地量换算）；并集宽改按视觉宽算，不再混布局宽
    private var leftAccScaleX: Float = 1f
    private var rightAccScaleX: Float = 1f
    private var scaleDiagLogged: Boolean = false
    // v1.4.5-fix18 实施审 S2：displayCutout 镜头圆（root 坐标视觉值；cx<0 表示无数据、退并集中心）
    private var cutoutCxInRoot: Float = -1f
    private var cutoutHalfW: Float = 0f
    // v1.4.5-fix18 实施审 S1/M3：贯通裁剪路径按几何键缓存；op 失败的键单独记下、同键直接走回退
    private val throughPathL = Path()
    private val throughPathR = Path()
    private var cachedKeyL: String? = null
    private var cachedKeyR: String? = null
    private var failedPathKeyL: String? = null
    private var failedPathKeyR: String? = null
    private var treeDiagLogged: Boolean = false
    private val injectSkipLoggedClasses = HashSet<String>()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!loopRunning) return
            com.vivo.islandlyric.util.PerfStats.shimmerFrames.incrementAndGet()
            val now = SystemClock.uptimeMillis()
            if (lastFrameUptimeMs > 0L) {
                val dt = now - lastFrameUptimeMs
                phaseMs += dt
                tick(dt)
            }
            lastFrameUptimeMs = now
            if (loopRunning) {
                Choreographer.getInstance().postFrameCallbackDelayed(this, 33L)
            }
        }
    }

    private fun tick(dtMs: Long) {
        val step = dtMs / 200f
        islandWeight = approach(islandWeight, if (targetMode() == MODE_ISLAND) 1f else 0f, step)
        countdownWeight = approach(countdownWeight, if (targetMode() == MODE_COUNTDOWN) 1f else 0f, step)
        // 两路都淡完且目标为空：停循环（段结束切回整岛时权重此消彼长、循环不停——Quick 时序坑 3）
        if (targetMode() == MODE_NONE && islandWeight == 0f && countdownWeight == 0f) {
            stopLoop("faded_out")
            return
        }
        val root = rootRef?.get()
        if (root != null) {
            root.getLocationInWindow(rootLoc)
            val ls = leftShellRef?.get()
            val rs = rightShellRef?.get()
            if (ls != null) leftGlobalX = visualLeftInRoot(ls)
            if (rs != null) rightGlobalX = visualLeftInRoot(rs)
            if (ls != null && rs != null && ls.width > 0 && rs.width > 0) {
                leftAccScaleX = accumulatedScaleX(ls)
                rightAccScaleX = accumulatedScaleX(rs)
                unionLeft = minOf(leftGlobalX, rightGlobalX)
                unionW = maxOf(
                    leftGlobalX + ls.width * leftAccScaleX,
                    rightGlobalX + rs.width * rightAccScaleX
                ) - unionLeft
                // v1.4.5-fix18 实施审 S2：采样系统挖孔（中心须落在本岛并集内才认），供镜头圆定位
                val cut = root.rootWindowInsets?.displayCutout
                var foundCut = false
                if (cut != null) {
                    for (r in cut.boundingRects) {
                        val cxRoot = (r.left + r.right) * 0.5f - rootLoc[0]
                        if (cxRoot >= unionLeft - 8f && cxRoot <= unionLeft + unionW + 8f) {
                            cutoutCxInRoot = cxRoot
                            cutoutHalfW = (r.right - r.left) * 0.5f + 4f
                            foundCut = true
                            break
                        }
                    }
                }
                if (!foundCut) cutoutCxInRoot = -1f
                if (!scaleDiagLogged) {
                    scaleDiagLogged = true
                    val sMsg = "[ISLAND_SHIMMER] scale lSx=$leftAccScaleX rSx=$rightAccScaleX " +
                        "lW=${ls.width} rW=${rs.width} unionVisualW=$unionW cutoutCx=$cutoutCxInRoot cutoutHalfW=$cutoutHalfW"
                    YLog.info(msg = sMsg)
                    EventHistoryStore.recordDiagOnly(sMsg)
                }
            } else {
                unionW = 0f
            }
        }
        // v1.4.5-fix18：贯通绘制会画出子 View 自身 bounds（向间隙延伸各半），invalidate 须带外扩脏区，
        // 否则间隙段不在脏区内、合成器不重画（按并集全宽外扩，两侧一律，量级无碍）
        val lc = leftChildRef?.get()
        val rc = rightChildRef?.get()
        if (unionW > 0f && (lc != null || rc != null)) {
            val m = unionW.toInt() + 16
            if (lc != null) lc.invalidate(-m, 0, lc.width + m, lc.height)
            if (rc != null) rc.invalidate(-m, 0, rc.width + m, rc.height)
        } else {
            lc?.invalidate()
            rc?.invalidate()
        }
    }

    private fun approach(cur: Float, target: Float, step: Float): Float = when {
        cur < target -> (cur + step).coerceAtMost(target)
        cur > target -> (cur - step).coerceAtLeast(target)
        else -> cur
    }

    private fun targetMode(): Int = when {
        drawDisabled || !gateOk -> MODE_NONE
        countdownWanted -> MODE_COUNTDOWN
        islandWanted -> MODE_ISLAND
        else -> MODE_NONE
    }

    /** 整岛入口（Renderer.syncGlowWithCurrentState 驱动）：gateOk＝可见基线（shouldShow && scaleReady）已在 Renderer 判过 */
    fun sync(
        active: Boolean,
        gateOk: Boolean,
        root: View?,
        capLeft: View?,
        capRight: View?,
        style: Int,
        level: Int,
        accentColor: Int
    ) {
        islandWanted = active
        this.gateOk = gateOk
        if (root != null) {
            val prevOwner = hostRegistry[root]
            if (prevOwner != null && prevOwner !== this) {
                prevOwner.destroy("replaced_by_new_instance")
            }
            hostRegistry[root] = this
            rootRef = WeakReference(root)
        }
        if (capLeft != null && capRight != null) {
            leftShellRef = WeakReference(capLeft)
            rightShellRef = WeakReference(capRight)
            if (gateOk && (islandWanted || countdownWanted)) {
                ensureChild(capLeft, isLeft = true)
                ensureChild(capRight, isLeft = false)
                if (!treeDiagLogged) {
                    treeDiagLogged = true
                    logTreeDiag(root, capLeft, capRight)
                }
            }
        }
        val peak = ShimmerPainter.peakAlphaForLevel(level)
        if (islandStyle != style || islandPeak != peak || islandAccent != accentColor) {
            islandStyle = style
            islandPeak = peak
            islandAccent = accentColor
            painterIsland.invalidateCache()
        }
        resolve()
    }

    /** 倒计时入口：调用点与倒计时视图 setShimmerEnabled 的每个调用点一一对应（Quick 追加裁决，不另推算段判定） */
    fun syncCountdown(enabled: Boolean, style: Int, level: Int, accentColor: Int) {
        // S1：只开倒计时一路时也提前把子 View 注入好，免段开头几帧先自画再跳切到合并层
        if (gateOk) {
            val l = leftShellRef?.get()
            val r = rightShellRef?.get()
            if (l != null && r != null) {
                ensureChild(l, isLeft = true)
                ensureChild(r, isLeft = false)
            }
        }
        // 样式 6（文字）没有背景层，这一路视为不想亮；数字上的文字流光仍由倒计时视图自画
        countdownWanted = enabled && style != 6
        val peak = ShimmerPainter.peakAlphaForLevel(level)
        if (countdownStyle != style || countdownPeak != peak || countdownAccent != accentColor) {
            countdownStyle = style
            countdownPeak = peak
            countdownAccent = accentColor
            painterCountdown.invalidateCache()
        }
        resolve()
    }

    private fun resolve() {
        if (targetMode() != MODE_NONE) {
            if (!loopRunning) {
                // 相位只在帧循环冷启时清零，模式切换不走这里（Quick 时序坑 2）
                loopRunning = true
                phaseMs = 0L
                lastFrameUptimeMs = 0L
                Choreographer.getInstance().postFrameCallback(frameCallback)
                val msg = "[ISLAND_SHIMMER] start mode=${targetMode()} cdStyle=$countdownStyle islandStyle=$islandStyle"
                YLog.info(msg = msg)
                EventHistoryStore.recordDiagOnly(msg)
            }
        } else if (loopRunning && islandWeight == 0f && countdownWeight == 0f) {
            stopLoop("gate_closed")
        }
        // 目标为空但权重未淡完：循环继续跑，tick 淡完后自停
    }

    private fun stopLoop(reason: String) {
        if (!loopRunning) return
        loopRunning = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        leftChildRef?.get()?.invalidate()
        rightChildRef?.get()?.invalidate()
        val msg = "[ISLAND_SHIMMER] stop reason=$reason"
        YLog.info(msg = msg)
        EventHistoryStore.recordDiagOnly(msg)
    }

    /** 子 View 层是否可用（已注入且未因绘制异常停用）：倒计时视图据此决定要不要框内降级自画 */
    fun isLayerAvailable(): Boolean {
        if (drawDisabled) return false
        val l = leftChildRef?.get() ?: return false
        val r = rightChildRef?.get() ?: return false
        // M3：挂上还不够——壳 wrap_content 且子 View 是唯一 MATCH_PARENT 时会被测成 0×0，那种情况必须走降级自画
        return l.parent != null && r.parent != null &&
            l.width > 0 && l.height > 0 && r.width > 0 && r.height > 0
    }


    /** 子 View 的 onDraw 入口：按两路权重分别画（交叉淡化期两层叠画），裁剪成胶囊形；异常即永久停用（SystemUI 不能崩） */
    fun drawChild(canvas: Canvas, isLeft: Boolean, w: Float, h: Float) {
        if (drawDisabled || w <= 0f || h <= 0f) return
        if (islandWeight <= 0.01f && countdownWeight <= 0.01f) return
        // C-M3：save 在 try 外、restore 在 finally
        val save = canvas.save()
        try {
            val radius = h * 0.5f
            // v1.4.5-fix15：传并集相对偏移（左壳 0、右壳约 431）与并集总宽；并集未知时退回本壳自洽坐标
            val gx = if (unionW > 0f) {
                (if (isLeft) leftGlobalX else rightGlobalX) - unionLeft
            } else {
                0f
            }
            val tw = if (unionW > 0f) unionW else w
            // v1.4.5-fix18 整岛贯通（老大拍板＋三方对表＋实施审 M1/M2/M3/S1/S2）：
            // 裁剪＝并集药丸轮廓 ∩ 本侧半幅 − 镜头圆，两壳各画半幅向间隙延伸、光只在镜头圆边断开；
            // 并集量是视觉坐标、画布是本地坐标，按本壳累计 scaleX 换算（sx=1 时恒等）；
            // 路径按几何键缓存，Path.op 任一失败即记失败键、同键退回 fix16 裁法（不整层停用）；
            // 落笔范围（drawL/drawR）与相位量同步切到本地半幅，否则裁剪再大间隙也落不上像素
            var throughActive = false
            var drawL = 0f
            var drawR = w
            var pGx = gx
            var pTw = tw
            if (unionW > 0f) {
                val sx = if (isLeft) leftAccScaleX else rightAccScaleX
                val gxL = gx / sx
                val twL = unionW / sx
                val pillLeft = -gxL
                val pillMid = pillLeft + twL * 0.5f
                val childLeftInRoot = if (isLeft) leftGlobalX else rightGlobalX
                val lensCxL = if (cutoutCxInRoot >= 0f) {
                    (cutoutCxInRoot - childLeftInRoot) / sx
                } else {
                    pillMid
                }
                val lensRL = (if (cutoutHalfW > 0f) cutoutHalfW else LENS_RADIUS_PX) / sx
                val key = "${gxL.toInt()}:${twL.toInt()}:${h.toInt()}:${lensCxL.toInt()}:${lensRL.toInt()}:${if (isLeft) 1 else 0}"
                val failed = if (isLeft) failedPathKeyL == key else failedPathKeyR == key
                if (!failed) {
                    val cached = if (isLeft) throughPathL else throughPathR
                    val cachedKey = if (isLeft) cachedKeyL else cachedKeyR
                    if (cachedKey == key) {
                        canvas.clipPath(cached)
                        throughActive = true
                    } else {
                        cached.reset()
                        clipRect.set(pillLeft, 0f, pillLeft + twL, h)
                        cached.addRoundRect(clipRect, radius, radius, Path.Direction.CW)
                        halfPath.reset()
                        if (isLeft) {
                            halfPath.addRect(pillLeft - 2f, -2f, pillMid, h + 2f, Path.Direction.CW)
                        } else {
                            halfPath.addRect(pillMid, -2f, pillLeft + twL + 2f, h + 2f, Path.Direction.CW)
                        }
                        val ok1 = cached.op(halfPath, Path.Op.INTERSECT)
                        lensPath.reset()
                        lensPath.addCircle(lensCxL, h * 0.5f, lensRL, Path.Direction.CW)
                        val ok2 = if (ok1) cached.op(lensPath, Path.Op.DIFFERENCE) else false
                        if (ok1 && ok2) {
                            if (isLeft) cachedKeyL = key else cachedKeyR = key
                            canvas.clipPath(cached)
                            throughActive = true
                        } else {
                            if (isLeft) failedPathKeyL = key else failedPathKeyR = key
                            val oMsg = "[ISLAND_SHIMMER] op_fail key=$key ok1=$ok1 ok2=$ok2"
                            YLog.info(msg = oMsg)
                            EventHistoryStore.recordDiagOnly(oMsg)
                        }
                    }
                }
                if (throughActive) {
                    if (isLeft) {
                        drawL = pillLeft
                        drawR = pillMid
                    } else {
                        drawL = pillMid
                        drawR = pillLeft + twL
                    }
                    pGx = gxL
                    pTw = twL
                }
            }
            if (!throughActive) {
                clipRect.set(0f, 0f, w, h)
                clipPath.reset()
                // v1.4.5-fix16（Quick 二轮裁决修法 1）：只裁外侧两角——内侧改直角，配合 Painter 内侧 24px 软边，
                // 光在边前淡到 0、直角不可见；原四角全圆角会在两壳内侧各切出一道半圆＝「内层药丸」轮廓
                val radii = if (isLeft) {
                    floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
                } else {
                    floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
                }
                clipPath.addRoundRect(clipRect, radii, Path.Direction.CW)
                canvas.clipPath(clipPath)
            }
            // v1.4.5-fix16：内侧软边——左壳内侧边在其右缘、右壳在其左缘（样式 0–5 全部生效）；贯通时关，光不在壳边提前淡出
            val innerMask = if (throughActive) {
                ShimmerPainter.EdgeMask.NONE
            } else if (isLeft) {
                ShimmerPainter.EdgeMask.INNER_RIGHT
            } else {
                ShimmerPainter.EdgeMask.INNER_LEFT
            }
            if (countdownWeight > 0.01f) {
                painterCountdown.draw(
                    canvas = canvas, w = w, h = h, phaseMs = phaseMs,
                    style = countdownStyle, peakAlpha = countdownPeak,
                    accentColor = countdownAccent, globalX = pGx,
                    alphaScale = LAYER_ALPHA_SCALE * countdownWeight,
                    edgeMask = innerMask,
                    totalW = pTw,
                    drawL = drawL,
                    drawR = drawR
                )
            }
            if (islandWeight > 0.01f) {
                painterIsland.draw(
                    canvas = canvas, w = w, h = h, phaseMs = phaseMs,
                    style = islandStyle, peakAlpha = islandPeak,
                    accentColor = islandAccent, globalX = pGx,
                    alphaScale = LAYER_ALPHA_SCALE * islandWeight,
                    edgeMask = innerMask,
                    totalW = pTw,
                    drawL = drawL,
                    drawR = drawR
                )
            }
        } catch (t: Throwable) {
            drawDisabled = true
            loopRunning = false
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            val msg = "[ISLAND_SHIMMER] draw_error disabled"
            YLog.info(msg = msg)
            EventHistoryStore.recordDiagOnly(msg)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun ensureChild(shell: View, isLeft: Boolean) {
        val group = shell as? ViewGroup ?: return
        // C-M2：本侧旧子 View 若挂在别的壳上（壳实例已换），先从旧壳摘掉，防复用残留
        val tracked = (if (isLeft) leftChildRef else rightChildRef)?.get()
        if (tracked != null && tracked.parent !== group) {
            removeChild(tracked)
            if (isLeft) leftChildRef = null else rightChildRef = null
        }
        // C-M1：只注入 FrameLayout 且无 layoutTransition 的壳；其余跳过并按类名打一次 inject_skip
        if (group !is FrameLayout || group.layoutTransition != null) {
            val cls = group.javaClass.name
            if (injectSkipLoggedClasses.add(cls)) {
                val msg = "[ISLAND_SHIMMER] inject_skip class=$cls transition=${group.layoutTransition != null}"
                YLog.info(msg = msg)
                EventHistoryStore.recordDiagOnly(msg)
            }
            return
        }
        // C-M2 + v1.4.5-fix19 P1-2：同实例子 View 直接复用；若发现旧实例残留子 View 先摘除，杜绝双实例残骸
        var reusable: IslandShimmerChildView? = null
        val stale = ArrayList<View>()
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            if (c is IslandShimmerChildView && c.tag == CHILD_TAG) {
                if (c.manager === this && reusable == null) {
                    reusable = c
                } else {
                    stale.add(c)
                }
            }
        }
        for (s in stale) {
            runCatching { group.removeView(s) }
        }
        if (reusable != null) {
            if (isLeft) leftChildRef = WeakReference(reusable) else rightChildRef = WeakReference(reusable)
            return
        }
        val child = IslandShimmerChildView(group.context, this, isLeft)
        child.tag = CHILD_TAG
        val added = runCatching {
            group.addView(
                child, 0,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            true
        }.getOrDefault(false)
        if (added) lastInjectElapsedMs = SystemClock.elapsedRealtime()
        if (isLeft) leftChildRef = WeakReference(child) else rightChildRef = WeakReference(child)
    }

    private fun visualLeftInRoot(shell: View): Float {
        shell.getLocationInWindow(shellLoc)
        return (shellLoc[0] - rootLoc[0]).toFloat()
    }

    /** v1.4.5-fix18 实施审 M2：壳到窗口根的累计 scaleX（getLocationInWindow 给视觉原点、长度须乘此值才是视觉量） */
    private fun accumulatedScaleX(v: View): Float {
        var s = 1f
        var cur: View? = v
        var hops = 0
        while (cur != null && hops < 10) {
            s *= cur.scaleX
            cur = cur.parent as? View
            hops++
        }
        return if (s > 0.01f) s else 1f
    }

    /** 进入宽态打一次结构诊断（Quick fix14 指定项）：类名/背景/子 View 构成，下轮定论药丸归属与注入安全性 */
    private fun logTreeDiag(root: View?, capLeft: View, capRight: View) {
        fun describe(v: View?): String {
            if (v == null) return "null"
            val bg = v.background
            val parent = v.parent as? View
            val idx = if (parent is ViewGroup) parent.indexOfChild(v) else -1
            return "${v.javaClass.name} bg=${bg?.javaClass?.name ?: "null"} willNotDraw=${v.willNotDraw()} " +
                "clipToOutline=${v.clipToOutline} elev=${v.elevation} children=${(v as? ViewGroup)?.childCount ?: -1} idx=$idx"
        }
        fun childList(v: View): String {
            val g = v as? ViewGroup ?: return "-"
            val sb = StringBuilder()
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i)
                if (sb.isNotEmpty()) sb.append(',')
                sb.append(c.javaClass.simpleName).append('#').append(c.id)
            }
            return sb.toString()
        }
        val msg = "[ISLAND_SHIMMER] tree root=[${describe(root)}] left=[${describe(capLeft)}] " +
            "right=[${describe(capRight)}] leftParent=[${describe(capLeft.parent as? View)}] " +
            "rightParent=[${describe(capRight.parent as? View)}] " +
            "leftChildren=[${childList(capLeft)}] rightChildren=[${childList(capRight)}]"
        YLog.info(msg = msg)
        EventHistoryStore.recordDiagOnly(msg)
        // v1.4.5-fix16（Quick 二轮裁决补充三）：两壳父级 identity＋父级子 View 明细（fix17 起兼作模板拔插问题的结构基线）
        val lp = capLeft.parent
        val rp = capRight.parent
        val pMsg = StringBuilder()
        pMsg.append("[ISLAND_SHIMMER] parent sameParent=").append(lp === rp)
            .append(" leftParentId=").append(if (lp != null) System.identityHashCode(lp) else 0)
            .append(" rightParentId=").append(if (rp != null) System.identityHashCode(rp) else 0)
        val pg = lp as? ViewGroup
        if (pg != null) {
            pMsg.append(" children=").append(pg.childCount)
            for (i in 0 until pg.childCount) {
                val c = pg.getChildAt(i)
                pMsg.append(" | #").append(i).append(' ').append(c.javaClass.simpleName)
                    .append(" id=").append(c.id)
                    .append(" vis=").append(c.visibility)
                    .append(" bounds=[").append(c.left).append("..").append(c.right).append(']')
                    .append(" willNotDraw=").append(c.willNotDraw())
                    .append(" z=").append(c.z)
                    .append(" bg=").append(c.background?.javaClass?.simpleName ?: "null")
            }
        }
        val pStr = pMsg.toString()
        YLog.info(msg = pStr)
        EventHistoryStore.recordDiagOnly(pStr)
    }

    /** C-S1：全量还原只停输入、走淡出，不拆子 View（拖动期 restore 约 1 次/秒，拆装会搅动变形动画） */
    fun unwrapAll(reason: String) {
        islandWanted = false
        countdownWanted = false
        gateOk = false
        resolve()
    }

    /** 两路流光开关都关（或总开关关）时调用：输入清零并真正摘除子 View */
    fun detachAll(reason: String) {
        islandWanted = false
        countdownWanted = false
        gateOk = false
        islandWeight = 0f
        countdownWeight = 0f
        stopLoop(reason)
        removeChild(leftChildRef?.get())
        removeChild(rightChildRef?.get())
        leftChildRef = null
        rightChildRef = null
    }

    /** v1.4.5-fix19 P1-2：判定该子 View 是否归属当前流光管理器，供 ORPHAN 区分本实例与旧实例残骸 */
    fun ownsChild(view: View): Boolean =
        view === leftChildRef?.get() || view === rightChildRef?.get()

    /** v1.4.5-fix19 P1-2：重建前完整销毁旧实例——停循环、摘除全部子 View、清引用与宿主注册表 */
    fun destroy(reason: String = "destroy") {
        detachAll(reason)
        rootRef?.get()?.let { r ->
            if (hostRegistry[r] === this) hostRegistry.remove(r)
        }
        rootRef = null
        leftShellRef = null
        rightShellRef = null
        treeDiagLogged = false
        scaleDiagLogged = false
    }

    private fun removeChild(child: IslandShimmerChildView?) {
        if (child == null) return
        runCatching { (child.parent as? ViewGroup)?.removeView(child) }
    }

    companion object {
        // v1.4.5-fix19 P1-2：按宿主 IslandWindowView 单实例去重注册表（UI 线程单线程读写）
        private val hostRegistry = java.util.WeakHashMap<View, IslandShimmerManager>()
        private const val CHILD_TAG = "island_shimmer_child"
        private const val MODE_NONE = 0
        private const val MODE_ISLAND = 1
        private const val MODE_COUNTDOWN = 2
        // 整层光统一压暗系数（Quick fix13 裁决 ×0.6 起步；追加裁决：倒计时这一路铺满整颗药丸后沿用 ×0.6 防抢歌名）
        private const val LAYER_ALPHA_SCALE = 0.6f
        // v1.4.5-fix18：镜头孔镂空半径（X100s 孔径约 4.3mm @ 密度 3.5 ≈ 59px 直径）——宁小勿大，
        // 光宁可贴到孔沿、不可留黑环；真机目视后可微调
        private const val LENS_RADIUS_PX = 30f
    }
}
