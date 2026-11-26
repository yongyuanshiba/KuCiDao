package com.vivo.islandlyric.hook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.highcapable.yukihookapi.hook.log.YLog
import com.vivo.islandlyric.Const
import com.vivo.islandlyric.util.EventHistoryStore
import com.vivo.islandlyric.util.HookCircuitBreaker
import com.vivo.islandlyric.util.IslandFrameGrid
import com.vivo.islandlyric.util.PerfStats
import com.vivo.islandlyric.util.RuntimePrefs
import java.lang.ref.WeakReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 原子岛窗口层双层呼吸光圈渲染器（v1.4.6-a1：性能根治 P1-P6 ＋ 浅色模式补偿 L1-L9）：
 * - P1：帧调度改 Choreographer.postFrameCallbackDelayed ＋ 跟随式预约（piggyback 100ms 内 60ms 退让，否则 33ms）；
 * - P2/P3：删除 syncState 无条件 kickDraw，gate 300ms 防抖与日志去抖；
 * - P4：关闭即停（INVISIBLE ＋ removeFrameCallback），配置与昼夜轮询移出 onDraw 至 1 秒节拍；
 * - P5：彻底移除全部 4 处路径模板裁剪，软光点改为外法线 (tan.y, -tan.x) 外移 +7px、半径 ≤7px；
 * - P6：PerfStats 拆分 haloFrame（自驱帧）与 haloPiggyback（顺带重绘帧）；
 * - L1-L9：浅色模式外层独立色板/alpha/底环乘数/波纹指数补偿，12 槽 Shader 预建（深色复用内侧不重复新建），
 *   深色模式所有数值与出货版逐字节一致，内层红线（L≥0.65、细边 ≤2.5px/≤102、漫射 18/22/26）两模式不变。
 */
class CapsuleGlowRenderer(
    private val readPrefs: () -> RuntimePrefs
) {
    private val glowBreaker = HookCircuitBreaker("Island.CapsuleGlow", threshold = 1)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootRef: WeakReference<ViewGroup>? = null
    private var glowViewRef: WeakReference<GlowView>? = null
    private var lastCapLeftRef: WeakReference<View>? = null
    private var lastCapRightRef: WeakReference<View>? = null
    private var preDrawOwnerRef: WeakReference<View>? = null

    @Volatile
    private var permanentlyDisabled: Boolean = false

    @Volatile
    private var desiredVisible: Boolean = false

    @Volatile
    private var playingState: Boolean = false

    @Volatile
    private var wideModeState: Boolean = false

    @Volatile
    private var targetPalette: IntArray = Const.GLOW_SILVER_PALETTE.copyOf()

    @Volatile
    private var hasReceivedPalette: Boolean = false

    // 复用临时几何对象，preDraw 热路径零分配
    private val tempRect = RectF()
    private val leftRectBuf = RectF()
    private val rightRectBuf = RectF()
    private val unionRectBuf = RectF()
    private val drawRectBuf = RectF()
    private val gvOriginBuf = FloatArray(2)

    // 整数几何缓存：preDraw 仅做整数比对，未变直接返回 false
    private var lastGeomL: Int = Int.MIN_VALUE
    private var lastGeomT: Int = Int.MIN_VALUE
    private var lastGeomR: Int = Int.MIN_VALUE
    private var lastGeomB: Int = Int.MIN_VALUE
    private var lastRootW: Int = -1
    private var lastRootH: Int = -1

    // P3：gate 300ms 防抖状态与日志去抖缓存
    private var lastEffectiveGate: Boolean = false
    private var lastGateFalseUptimeMs: Long = 0L
    private var lastDiagPollUptimeMs: Long = 0L
    private var lastPreDrawGeomCheckUptimeMs: Long = 0L

    private var lastDiagGate: Boolean = false
    private var lastDiagStyle: Int = -1
    private var lastDiagIntensity: Int = -1
    private var lastDiagPeriodMs: Int = -1
    private var lastDiagOuter: Boolean = false
    private var lastDiagOrbit: Boolean = true
    private var lastDiagOuterLv: Int = -1
    private var hasLoggedDiag: Boolean = false

    private var pendingDiagGate: Boolean = false
    private var pendingDiagStyle: Int = -1
    private var pendingDiagIntensity: Int = -1
    private var pendingDiagPeriodMs: Int = -1
    private var pendingDiagOuter: Boolean = false
    private var pendingDiagOrbit: Boolean = true
    private var pendingDiagOuterLv: Int = Const.HALO_OUTER_LEVEL_DEFAULT
    private var gateLogPending: Boolean = false

    private val gateLogRunnable = Runnable {
        gateLogPending = false
        runGlowSafe {
            emitHaloStateLogNow(
                gate = pendingDiagGate,
                style = pendingDiagStyle,
                intensity = pendingDiagIntensity,
                periodMs = pendingDiagPeriodMs,
                outer = pendingDiagOuter,
                orbit = pendingDiagOrbit,
                outerLv = pendingDiagOuterLv
            )
        }
    }

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        runGlowSafe {
            if (!desiredVisible) {
                unregisterPreDraw()
                return@runGlowSafe
            }
            val gv = glowViewRef?.get() ?: return@runGlowSafe
            val now = SystemClock.uptimeMillis()

            // P4 & L5：1 秒节拍配置与昼夜位轮询（移出 onDraw，仅门控开启时生效，关闭期 0 开销）
            if (now - lastDiagPollUptimeMs >= DIAG_POLL_INTERVAL_MS) {
                lastDiagPollUptimeMs = now
                gv.resolveLightMode(logChange = true)
                val prefs = readPrefs()
                val enabled = prefs.masterEnabled && prefs.haloEnabled
                if (!enabled) {
                    desiredVisible = false
                    if (lastEffectiveGate) {
                        lastEffectiveGate = false
                        lastGateFalseUptimeMs = now
                    }
                    scheduleHaloStateLog(
                        gate = false,
                        style = prefs.glowStyle,
                        intensity = prefs.glowIntensity,
                        periodMs = prefs.glowPeriodMs,
                        outer = prefs.glowOuterEnabled,
                        orbit = prefs.glowOrbitEnabled,
                        outerLv = prefs.haloOuterLevel
                    )
                    hideInternal()
                    return@runGlowSafe
                }
                val cfgChange = gv.applyHaloConfig(
                    haloEnabled = true,
                    style = prefs.glowStyle,
                    intensity = prefs.glowIntensity,
                    periodMs = prefs.glowPeriodMs,
                    outerEnabled = prefs.glowOuterEnabled,
                    orbitEnabled = prefs.glowOrbitEnabled,
                    outerLevel = prefs.haloOuterLevel
                )
                scheduleHaloStateLog(
                    gate = true,
                    style = prefs.glowStyle,
                    intensity = prefs.glowIntensity,
                    periodMs = prefs.glowPeriodMs,
                    outer = prefs.glowOuterEnabled,
                    orbit = prefs.glowOrbitEnabled,
                    outerLv = prefs.haloOuterLevel
                )
                if (cfgChange != 0 && gv.visibility == View.VISIBLE && !gv.isFrameLoopRunning()) {
                    gv.kickDraw()
                }
            }

            // 稳态 VISIBLE 时限 33ms 采样一次几何，降低 120Hz predraw CPU 树遍历开销
            if (gv.visibility == View.VISIBLE && now - lastPreDrawGeomCheckUptimeMs < GEOM_PREDRAW_MIN_INTERVAL_MS) {
                return@runGlowSafe
            }
            lastPreDrawGeomCheckUptimeMs = now

            val geomResult = updateGeometryInternal(
                gv = gv,
                capLeft = lastCapLeftRef?.get(),
                capRight = lastCapRightRef?.get(),
                fromPreDraw = true
            )
            if (geomResult == GEOM_CHANGED_READY) {
                val wasInvisible = gv.visibility != View.VISIBLE
                if (wasInvisible) {
                    gv.visibility = View.VISIBLE
                }
                if (!gv.isFrameLoopRunning()) {
                    gv.kickDraw()
                }
            }
        }
        true
    }

    fun attachToRoot(root: View) {
        runGlowSafe {
            if (root !is ViewGroup) return@runGlowSafe
            val old = rootRef?.get()
            if (old !== root) {
                unregisterPreDraw()
                rootRef = WeakReference(root)
                resetGeomCache()
            }
            ensureGlowView(root)
            if (desiredVisible) {
                registerPreDrawIfNeeded(root)
            }
        }
    }

    fun updatePalette(colors: IntArray) {
        if (colors.size != 5) return
        runGlowSafe {
            val copy = colors.copyOf()
            val firstPalette = !hasReceivedPalette
            hasReceivedPalette = true
            targetPalette = copy

            val gv = glowViewRef?.get() ?: return@runGlowSafe
            val shouldAnimateFade = !firstPalette && gv.visibility == View.VISIBLE
            val changed = gv.setPalette(copy, animateFade = shouldAnimateFade)
            // P2：配色变化且帧循环未在跑时补一帧
            if (changed && desiredVisible && gv.visibility == View.VISIBLE && !gv.isFrameLoopRunning()) {
                gv.kickDraw()
            }
        }
    }

    fun syncState(
        visible: Boolean,
        isPlaying: Boolean,
        isWideMode: Boolean,
        root: View?,
        capLeft: View?,
        capRight: View?
    ) {
        runGlowSafe {
            playingState = isPlaying
            wideModeState = isWideMode
            lastCapLeftRef = capLeft?.let { WeakReference(it) }
            lastCapRightRef = capRight?.let { WeakReference(it) }

            val now = SystemClock.uptimeMillis()
            val prefs = readPrefs()
            val enabledByPrefs = prefs.masterEnabled && prefs.haloEnabled
            val effectiveVisible = visible && enabledByPrefs && isWideMode && isPlaying
            val prevGate = lastEffectiveGate
            desiredVisible = effectiveVisible
            lastEffectiveGate = effectiveVisible

            if (!effectiveVisible) {
                if (prevGate) {
                    lastGateFalseUptimeMs = now
                }
                // 关闭期间不再重复刷 [HALO] gate= 日志（仅状态真正变化时经 300ms 防抖记一次）
                scheduleHaloStateLog(
                    gate = false,
                    style = prefs.glowStyle,
                    intensity = prefs.glowIntensity,
                    periodMs = prefs.glowPeriodMs,
                    outer = prefs.glowOuterEnabled,
                    orbit = prefs.glowOrbitEnabled,
                    outerLv = prefs.haloOuterLevel
                )
                hideInternal()
                return@runGlowSafe
            }

            scheduleHaloStateLog(
                gate = true,
                style = prefs.glowStyle,
                intensity = prefs.glowIntensity,
                periodMs = prefs.glowPeriodMs,
                outer = prefs.glowOuterEnabled,
                orbit = prefs.glowOrbitEnabled,
                outerLv = prefs.haloOuterLevel
            )

            val rootGroup = (root as? ViewGroup) ?: rootRef?.get() ?: return@runGlowSafe
            if (rootRef?.get() !== rootGroup) {
                unregisterPreDraw()
                rootRef = WeakReference(rootGroup)
                resetGeomCache()
            }

            val gv = ensureGlowView(rootGroup) ?: return@runGlowSafe
            gv.resolveLightMode(logChange = true)
            val cfgChange = gv.applyHaloConfig(
                haloEnabled = true,
                style = prefs.glowStyle,
                intensity = prefs.glowIntensity,
                periodMs = prefs.glowPeriodMs,
                outerEnabled = prefs.glowOuterEnabled,
                orbitEnabled = prefs.glowOrbitEnabled,
                outerLevel = prefs.haloOuterLevel
            )
            gv.setWideMode(isWideMode)
            gv.setPlaying(isPlaying)
            registerPreDrawIfNeeded(rootGroup)

            val geomResult = updateGeometryInternal(
                gv = gv,
                capLeft = capLeft,
                capRight = capRight,
                fromPreDraw = false
            )
            val boundsReady = geomResult != GEOM_NOT_READY
            val geomChanged = geomResult == GEOM_CHANGED_READY

            if (boundsReady) {
                val wasInvisible = gv.visibility != View.VISIBLE
                if (wasInvisible) {
                    gv.visibility = View.VISIBLE
                }
                // P2 & P3：删除无条件 kickDraw，仅在三种必要情形补一帧
                val gateReopenStable = !prevGate &&
                    (lastGateFalseUptimeMs == 0L || (now - lastGateFalseUptimeMs) >= GATE_DEBOUNCE_MS)
                val switchOrStyleChanged = (cfgChange and (CFG_CHANGED_SWITCH or CFG_CHANGED_STYLE)) != 0
                val geomChangedWhileIdle = geomChanged && !gv.isFrameLoopRunning()

                if (gateReopenStable || switchOrStyleChanged || geomChangedWhileIdle) {
                    gv.kickDraw()
                } else if (!wasInvisible && !gv.isFrameLoopRunning()) {
                    // P3：gate=false 后 300ms 内回到 true，不强行 kickDraw，交给 Choreographer 调度画
                    gv.ensureTickScheduled()
                }
            } else if (gv.visibility != View.INVISIBLE) {
                gv.stopAndHide()
            }
        }
    }

    fun hideAndStop(reason: String) {
        if (permanentlyDisabled) {
            forceDisableAfterFault()
            return
        }
        runGlowSafe {
            val now = SystemClock.uptimeMillis()
            val prevGate = lastEffectiveGate
            desiredVisible = false
            lastEffectiveGate = false
            if (prevGate) {
                lastGateFalseUptimeMs = now
            }
            val prefs = readPrefs()
            scheduleHaloStateLog(
                gate = false,
                style = prefs.glowStyle,
                intensity = prefs.glowIntensity,
                periodMs = prefs.glowPeriodMs,
                outer = prefs.glowOuterEnabled,
                orbit = prefs.glowOrbitEnabled,
                outerLv = prefs.haloOuterLevel
            )
            hideInternal()
        }
    }

    private fun hideInternal() {
        unregisterPreDraw()
        glowViewRef?.get()?.stopAndHide()
    }

    private fun resetGeomCache() {
        lastGeomL = Int.MIN_VALUE
        lastGeomT = Int.MIN_VALUE
        lastGeomR = Int.MIN_VALUE
        lastGeomB = Int.MIN_VALUE
        lastRootW = -1
        lastRootH = -1
        lastPreDrawGeomCheckUptimeMs = 0L
    }

    /** P3：状态稳定 300ms 后仅输出一条 [HALO] gate= 日志；300ms 内来回翻转自动消抖不刷屏 */
    private fun scheduleHaloStateLog(
        gate: Boolean,
        style: Int,
        intensity: Int,
        periodMs: Int,
        outer: Boolean,
        orbit: Boolean,
        outerLv: Int
    ) {
        if (gateLogPending) {
            mainHandler.removeCallbacks(gateLogRunnable)
            gateLogPending = false
        }
        if (hasLoggedDiag &&
            gate == lastDiagGate &&
            style == lastDiagStyle &&
            intensity == lastDiagIntensity &&
            periodMs == lastDiagPeriodMs &&
            outer == lastDiagOuter &&
            orbit == lastDiagOrbit &&
            outerLv == lastDiagOuterLv
        ) {
            return
        }
        pendingDiagGate = gate
        pendingDiagStyle = style
        pendingDiagIntensity = intensity
        pendingDiagPeriodMs = periodMs
        pendingDiagOuter = outer
        pendingDiagOrbit = orbit
        pendingDiagOuterLv = outerLv
        gateLogPending = true
        mainHandler.postDelayed(gateLogRunnable, GATE_DEBOUNCE_MS)
    }

    private fun emitHaloStateLogNow(
        gate: Boolean,
        style: Int,
        intensity: Int,
        periodMs: Int,
        outer: Boolean,
        orbit: Boolean,
        outerLv: Int
    ) {
        if (hasLoggedDiag &&
            gate == lastDiagGate &&
            style == lastDiagStyle &&
            intensity == lastDiagIntensity &&
            periodMs == lastDiagPeriodMs &&
            outer == lastDiagOuter &&
            orbit == lastDiagOrbit &&
            outerLv == lastDiagOuterLv
        ) {
            return
        }
        hasLoggedDiag = true
        lastDiagGate = gate
        lastDiagStyle = style
        lastDiagIntensity = intensity
        lastDiagPeriodMs = periodMs
        lastDiagOuter = outer
        lastDiagOrbit = orbit
        lastDiagOuterLv = outerLv
        val key = "$gate|$style|$intensity|$periodMs|$outer|$orbit|$outerLv"
        val msg = "[HALO] gate=$gate style=$style intensity=$intensity periodMs=$periodMs outer=$outer orbit=$orbit outerLv=$outerLv"
        YLog.info(msg = msg)
        EventHistoryStore.recordDiagOnly(msg, dedupKey = key)
    }

    private fun registerPreDrawIfNeeded(root: View) {
        if (permanentlyDisabled || !desiredVisible) return
        val currentOwner = preDrawOwnerRef?.get()
        if (currentOwner === root) return
        unregisterPreDraw()
        val vto = root.viewTreeObserver
        if (vto != null && vto.isAlive) {
            vto.addOnPreDrawListener(preDrawListener)
            preDrawOwnerRef = WeakReference(root)
        }
    }

    private fun unregisterPreDraw() {
        val owner = preDrawOwnerRef?.get()
        preDrawOwnerRef = null
        if (owner != null) {
            runCatching {
                val vto = owner.viewTreeObserver
                if (vto != null && vto.isAlive) {
                    vto.removeOnPreDrawListener(preDrawListener)
                }
            }
        }
    }

    private fun ensureGlowView(root: ViewGroup): GlowView? {
        val existing = glowViewRef?.get()?.takeIf { it.parent === root }
            ?: (root.findViewWithTag<View>(Const.TAG_GLOW_VIEW) as? GlowView)
        if (existing != null) {
            glowViewRef = WeakReference(existing)
            return existing
        }

        val prefs = readPrefs()
        val gv = GlowView(
            context = root.context,
            safeRunner = this::runGlowSafe
        ).apply {
            tag = Const.TAG_GLOW_VIEW
            visibility = View.INVISIBLE
            applyHaloConfig(
                haloEnabled = prefs.haloEnabled && prefs.masterEnabled,
                style = prefs.glowStyle,
                intensity = prefs.glowIntensity,
                periodMs = prefs.glowPeriodMs,
                outerEnabled = prefs.glowOuterEnabled,
                orbitEnabled = prefs.glowOrbitEnabled,
                outerLevel = prefs.haloOuterLevel
            )
            setWideMode(wideModeState)
            setPalette(targetPalette, animateFade = false)
            setPlaying(playingState)
        }

        val lp = if (root is FrameLayout) {
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        } else {
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(gv, lp)
        glowViewRef = WeakReference(gv)
        return gv
    }

    private fun updateGeometryInternal(
        gv: GlowView,
        capLeft: View?,
        capRight: View?,
        fromPreDraw: Boolean
    ): Int {
        val root = rootRef?.get() ?: (gv.parent as? ViewGroup) ?: return GEOM_NOT_READY
        val rootW = root.width
        val rootH = root.height
        if (!root.isAttachedToWindow || rootW <= 0 || rootH <= 0) return GEOM_NOT_READY

        val hasLeft = resolveVisualRectInRoot(capLeft, root, leftRectBuf)
        val hasRight = resolveVisualRectInRoot(capRight, root, rightRectBuf)
        if (!hasLeft && !hasRight) return GEOM_NOT_READY

        if (hasLeft && hasRight) {
            unionRectBuf.set(
                min(leftRectBuf.left, rightRectBuf.left),
                min(leftRectBuf.top, rightRectBuf.top),
                max(leftRectBuf.right, rightRectBuf.right),
                max(leftRectBuf.bottom, rightRectBuf.bottom)
            )
        } else if (hasLeft) {
            unionRectBuf.set(leftRectBuf)
        } else {
            unionRectBuf.set(rightRectBuf)
        }

        resolveGlowViewOriginInRoot(gv, root, gvOriginBuf)
        toLocalSafeRect(unionRectBuf, gvOriginBuf[0], gvOriginBuf[1], drawRectBuf)

        val l = drawRectBuf.left.roundToInt()
        val t = drawRectBuf.top.roundToInt()
        val r = drawRectBuf.right.roundToInt()
        val b = drawRectBuf.bottom.roundToInt()
        if (r - l <= 2 || b - t <= 2) return GEOM_NOT_READY

        val geomChanged = (l != lastGeomL || t != lastGeomT || r != lastGeomR || b != lastGeomB ||
            rootW != lastRootW || rootH != lastRootH)

        if (!geomChanged) {
            return if (fromPreDraw && gv.visibility != View.VISIBLE) GEOM_CHANGED_READY else GEOM_UNCHANGED_READY
        }

        lastGeomL = l
        lastGeomT = t
        lastGeomR = r
        lastGeomB = b
        lastRootW = rootW
        lastRootH = rootH

        var cutCx = rootW * 0.5f - gvOriginBuf[0]
        var cutHalfW = Const.HALO_FALLBACK_CUTOUT_HALF_W_PX
        val cut = root.rootWindowInsets?.displayCutout
        if (cut != null) {
            val rects = cut.boundingRects
            for (i in 0 until rects.size) {
                val cr = rects[i]
                if (cr.top <= 48 && cr.right > cr.left) {
                    cutCx = (cr.left + cr.right) * 0.5f - gvOriginBuf[0]
                    cutHalfW = (cr.right - cr.left) * 0.5f + 4f
                    break
                }
            }
        }

        gv.updateGeometry(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), cutCx, cutHalfW)
        return GEOM_CHANGED_READY
    }

    private fun toLocalSafeRect(src: RectF, originX: Float, originY: Float, outRect: RectF) {
        val l = src.left - originX
        val t = src.top - originY
        val r = src.right - originX
        val b = src.bottom - originY
        outRect.set(l, t, max(l, r), max(t, b))
    }

    private fun resolveGlowViewOriginInRoot(gv: GlowView, root: ViewGroup, outOrigin: FloatArray) {
        if (gv.width <= 0 && gv.height <= 0 && gv.left == 0 && gv.top == 0) {
            outOrigin[0] = root.paddingLeft.toFloat()
            outOrigin[1] = root.paddingTop.toFloat()
            return
        }
        var x = 0f
        var y = 0f
        var cur: View? = gv
        var reachedRoot = false
        while (cur != null) {
            if (cur === root) {
                reachedRoot = true
                break
            }
            x += cur.translationX
            y += cur.translationY
            val parent = cur.parent
            if (parent is ViewGroup) {
                x += (cur.left - parent.scrollX).toFloat()
                y += (cur.top - parent.scrollY).toFloat()
                cur = parent
            } else {
                break
            }
        }
        if (reachedRoot) {
            outOrigin[0] = x
            outOrigin[1] = y
        } else {
            outOrigin[0] = root.paddingLeft.toFloat()
            outOrigin[1] = root.paddingTop.toFloat()
        }
    }

    private fun resolveVisualRectInRoot(view: View?, root: View, outRect: RectF): Boolean {
        if (view == null || !view.isAttachedToWindow) return false
        if (view.visibility != View.VISIBLE || view.alpha <= 0.05f) return false
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 1f || h <= 1f) return false

        tempRect.set(0f, 0f, w, h)
        var cur: View? = view
        var reachedRoot = false
        while (cur != null) {
            if (cur === root) {
                reachedRoot = true
                break
            }
            if (cur.visibility != View.VISIBLE || cur.alpha <= 0.05f) return false
            val m = cur.matrix
            if (!m.isIdentity) {
                m.mapRect(tempRect)
            }
            val parent = cur.parent
            if (parent is ViewGroup) {
                tempRect.offset(
                    (cur.left - parent.scrollX).toFloat(),
                    (cur.top - parent.scrollY).toFloat()
                )
                cur = parent
            } else {
                break
            }
        }
        if (!reachedRoot) return false
        if (tempRect.width() <= 1f || tempRect.height() <= 1f) return false
        outRect.set(tempRect)
        return true
    }

    private fun runGlowSafe(block: () -> Unit) {
        if (permanentlyDisabled || glowBreaker.isTripped()) return
        glowBreaker.execute(block)
        if (glowBreaker.isTripped()) {
            permanentlyDisabled = true
            forceDisableAfterFault()
        }
    }

    private fun forceDisableAfterFault() {
        runCatching {
            desiredVisible = false
            lastEffectiveGate = false
            if (gateLogPending) {
                mainHandler.removeCallbacks(gateLogRunnable)
                gateLogPending = false
            }
            unregisterPreDraw()
            glowViewRef?.get()?.stopAndHide()
        }
    }

    private class GlowView(
        context: Context,
        private val safeRunner: (() -> Unit) -> Unit
    ) : View(context) {

        private var haloEnabled: Boolean = Const.HALO_ENABLED_DEFAULT
        private var haloStyle: Int = Const.GLOW_STYLE_DEFAULT
        private var haloIntensity: Int = Const.GLOW_INTENSITY_DEFAULT
        private var haloPeriodMs: Int = Const.GLOW_PERIOD_MS_DEFAULT
        private var haloOuterEnabled: Boolean = Const.GLOW_OUTER_ENABLED_DEFAULT
        private var haloOrbitEnabled: Boolean = Const.HALO_ORBIT_ENABLED_DEFAULT
        private var haloOuterLevel: Int = Const.HALO_OUTER_LEVEL_DEFAULT
        private var cachedLutOuterLevel: Int = -1
        private var lastConfigHash: Int = 0

        // L2：浅色模式与外侧上限字段
        private var isLightMode: Boolean = false
        private var cachedLutLight: Boolean = false
        private var maxOuterAlpha: Int = MAX_OUTER_ALPHA_DARK
        private var mainBandBaseAlpha: Int = 58
        private var innerRippleBaseAlpha0: Int = 42

        private var isWideMode: Boolean = false
        private var isPlaying: Boolean = false
        private var windowVisible: Boolean = true

        // 几何状态
        private val pillRect = RectF()
        private var pillRadius: Float = 0f
        private var arcX0: Float = 0f
        private var arcX1: Float = 0f
        private var hasValidGeom: Boolean = false
        private var lastGeomChangeUptimeMs: Long = 0L
        private var cutoutCx: Float = 630f
        private var cutoutHalfW: Float = Const.HALO_FALLBACK_CUTOUT_HALF_W_PX

        // Path & PathMeasure 全复用成员
        private val pillPath = Path()
        private val outerPaths = Array(OUTER_LAYER_COUNT) { Path() }
        private val tempRingPath = Path()
        private val segPath = Path()
        private val topArcPath = Path()
        private val botArcPath = Path()
        private val pathMeasure = PathMeasure()
        private val outerPathMeasure = PathMeasure()
        private var pillPerimeter: Float = 0f
        private var outerPerimeter: Float = 0f
        private val posBuf = FloatArray(2)
        private val tanBuf = FloatArray(2)
        private val workRect = RectF()
        private val spotDstRect = RectF()
        private val hslBuf = FloatArray(3)

        // L2/L3：内侧色板（L>=0.65 不变）＋ 原始色板缓存 ＋ 外侧色板（浅色独立钳制，深色直接拷贝内侧）
        private val rawPaletteBuf = IntArray(5)
        private val fromColors = IntArray(5)
        private val toColors = IntArray(5)
        private val blendedColors = IntArray(5)
        private val pendingPaletteBuf = IntArray(5)
        private val outerFrom = IntArray(5)
        private val outerTo = IntArray(5)
        private val outerBlended = IntArray(5)
        private val pendingOuterBuf = IntArray(5)
        private var fadeActive: Boolean = false
        private var fadeStartUptimeMs: Long = 0L

        // 预计算查找表（仅强度档、density 或昼夜模式变化时重算）
        private var cachedLutIntensity: Int = -1
        private var cachedLutDensity: Float = -1f
        private val outerLayerWidthsPx = FloatArray(OUTER_LAYER_COUNT)
        private val outerLayerOutsetsPx = FloatArray(OUTER_LAYER_COUNT)
        private val outerLayerAlphas = IntArray(OUTER_LAYER_COUNT)
        private var innerRimWidthPx: Float = 1.6f
        private var innerRimMaxAlpha: Int = 58
        private var innerDiffusePeakAlpha: Int = 18
        private var innerDiffuseDepthPx: Float = 10f
        private var cometOuterStrokePx: Float = 4.0f

        // 几何无关的 64×64 ALPHA_8 径向软光点（启动烘焙一次）
        private var radialSpotBitmap: Bitmap? = null

        // 预分配 Paint（零模糊、零 shadowLayer、零扫描渐变）
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        private val diffusePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val spotPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // S1 & L7：上下弧内漫射 Shader ＋ 12 槽底环/细边镜头孔衰减 RadialGradient（稳态零分配）
        // 0-4=外侧色板 0-4、5=外侧 silver；6=内侧 1、7=内侧 2、8=内侧 silver、9=内侧 whiteTint；10=内缩环 2、11=内缩环 3
        private var topDiffuseShader: LinearGradient? = null
        private var botDiffuseShader: LinearGradient? = null
        private val ringCutoutShaders = arrayOfNulls<RadialGradient>(RING_SHADER_SLOT_COUNT)
        private var silverColor: Int = 0
        private var outerSilverColor: Int = 0
        private var whiteTintColor: Int = 0
        private var badSlotLogged: Boolean = false
        private var shadersDirty: Boolean = true
        private var lastShaderRebuildUptimeMs: Long = 0L
        private val botDiffuseColors = IntArray(5)
        private val topDiffuseColors = IntArray(7)
        private val topDiffuseStops = FloatArray(7)
        private val ringCutoutColors = IntArray(3)
        private val diffuseMatrix = Matrix()

        // P1：Choreographer 缓存实例 ＋ 跟随式预约状态
        private val choreographer: Choreographer = Choreographer.getInstance()
        private var tickScheduled: Boolean = false
        private var lastHaloDrawUptimeMs: Long = 0L
        private var lastPiggybackUptimeMs: Long = 0L
        private var selfTickPending: Boolean = false
        private var selfTickSetUptimeMs: Long = 0L

        private val tickCb: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val self = this
                tickScheduled = false
                safeRunner {
                    if (!canRunFrameLoop()) {
                        selfTickPending = false
                        return@safeRunner
                    }
                    val now = SystemClock.uptimeMillis()
                    val targetInterval = currentTargetIntervalMs(now)
                    val elapsed = now - lastHaloDrawUptimeMs
                    val isGridMode = targetInterval <= 34L
                    val minElapsed = if (isGridMode) 16L else (targetInterval - TICK_JITTER_TOLERANCE_MS)
                    if (elapsed >= minElapsed) {
                        selfTickPending = true
                        selfTickSetUptimeMs = now
                        invalidate()
                    } else {
                        // R6：33ms 网格模式补约对齐 IslandFrameGrid，60ms 退让模式保持原剩余时间兜底
                        val remain = if (isGridMode) {
                            IslandFrameGrid.delayToNextSlot(now)
                        } else {
                            (targetInterval - elapsed).coerceAtLeast(8L)
                        }
                        tickScheduled = true
                        choreographer.postFrameCallbackDelayed(self, remain)
                    }
                }
            }
        }

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            isLightMode = readLightModeFromConfig(resources.configuration)
            outerSilverColor = hslToArgb(OUTER_SILVER_H, OUTER_SILVER_S, OUTER_SILVER_L)
            for (i in 0 until 5) {
                val raw = Const.GLOW_SILVER_PALETTE[i]
                rawPaletteBuf[i] = raw
                val innerC = clampLightnessHsl(raw)
                val outerC = if (isLightMode) clampOuterHsl(raw) else innerC
                fromColors[i] = innerC
                toColors[i] = innerC
                blendedColors[i] = innerC
                outerFrom[i] = outerC
                outerTo[i] = outerC
                outerBlended[i] = outerC
            }
            rebuildTablesIfNeeded(force = true)
            ensureRadialSpotBitmap()
        }

        override fun onTouchEvent(event: MotionEvent?): Boolean = false

        override fun onConfigurationChanged(newConfig: Configuration?) {
            super.onConfigurationChanged(newConfig)
            safeRunner {
                resolveLightMode(logChange = true, configOverride = newConfig)
            }
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            windowVisible = (visibility == VISIBLE)
            if (!windowVisible) {
                cancelTick()
            } else if (canRunFrameLoop()) {
                kickDraw()
            }
        }

        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            windowVisible = isVisible
            if (!isVisible) {
                cancelTick()
            } else if (canRunFrameLoop()) {
                kickDraw()
            }
        }

        override fun onDetachedFromWindow() {
            cancelTick()
            super.onDetachedFromWindow()
        }

        fun isFrameLoopRunning(): Boolean =
            tickScheduled || (selfTickPending && SystemClock.uptimeMillis() - selfTickSetUptimeMs < 100L)

        fun ensureTickScheduled() {
            clearStaleSelfTick(SystemClock.uptimeMillis())
            if (tickScheduled || !canRunFrameLoop()) return
            rescheduleNextTick(SystemClock.uptimeMillis())
        }

        /** L5：检查昼夜位变化；模式变化时重钳外侧色板并标脏，关闭/隐藏时不 kickDraw */
        fun resolveLightMode(logChange: Boolean, configOverride: Configuration? = null): Boolean {
            val cfg = configOverride ?: resources.configuration
            val newLight = readLightModeFromConfig(cfg)
            if (newLight == isLightMode) return false
            isLightMode = newLight
            lastConfigHash = computeConfigHash(
                haloEnabled,
                haloStyle,
                haloIntensity,
                haloPeriodMs,
                haloOuterEnabled,
                isLightMode,
                haloOrbitEnabled,
                haloOuterLevel
            )

            // 用原始色板重钳外侧色板（深色直接拷贝内侧）
            for (i in 0 until 5) {
                pendingOuterBuf[i] = if (isLightMode) clampOuterHsl(rawPaletteBuf[i]) else toColors[i]
            }
            val now = SystemClock.uptimeMillis()
            if (canRunFrameLoop()) {
                snapshotCurrentColorsInto(fromColors, outerFrom, now)
                System.arraycopy(pendingOuterBuf, 0, outerTo, 0, 5)
                fadeActive = true
                fadeStartUptimeMs = now
            } else {
                fadeActive = false
                System.arraycopy(pendingOuterBuf, 0, outerTo, 0, 5)
                System.arraycopy(outerTo, 0, outerFrom, 0, 5)
                System.arraycopy(outerTo, 0, outerBlended, 0, 5)
            }

            rebuildTablesIfNeeded(force = true)
            shadersDirty = true
            if (logChange) {
                val msg = "[HALO] light=$isLightMode"
                YLog.info(msg = msg)
                EventHistoryStore.recordDiagOnly(msg, dedupKey = "halo_light_$isLightMode")
            }
            return true
        }

        private fun readLightModeFromConfig(cfg: Configuration?): Boolean {
            if (cfg == null) return false
            val nightMask = cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return nightMask != Configuration.UI_MODE_NIGHT_YES
        }

        fun applyHaloConfig(
            haloEnabled: Boolean,
            style: Int,
            intensity: Int,
            periodMs: Int,
            outerEnabled: Boolean,
            orbitEnabled: Boolean,
            outerLevel: Int = Const.HALO_OUTER_LEVEL_DEFAULT
        ): Int {
            val clampedOuterLv = outerLevel.coerceIn(Const.HALO_OUTER_LEVEL_MIN, Const.HALO_OUTER_LEVEL_MAX)
            val hash = computeConfigHash(
                haloEnabled, style, intensity, periodMs, outerEnabled, isLightMode, orbitEnabled, clampedOuterLv
            )
            if (hash == lastConfigHash) return 0
            val clampedStyle = style.coerceIn(Const.GLOW_STYLE_MIN, Const.GLOW_STYLE_MAX)
            var flags = CFG_CHANGED_OTHER
            if (this.haloEnabled != haloEnabled) flags = flags or CFG_CHANGED_SWITCH
            if (this.haloStyle != clampedStyle) flags = flags or CFG_CHANGED_STYLE
            if (this.haloOrbitEnabled != orbitEnabled) flags = flags or CFG_CHANGED_STYLE

            lastConfigHash = hash
            this.haloEnabled = haloEnabled
            this.haloStyle = clampedStyle
            this.haloIntensity = intensity.coerceIn(Const.GLOW_INTENSITY_MIN, Const.GLOW_INTENSITY_MAX)
            this.haloPeriodMs = when (periodMs) {
                Const.GLOW_PERIOD_MS_FAST, Const.GLOW_PERIOD_MS_SLOW -> periodMs
                else -> Const.GLOW_PERIOD_MS_DEFAULT
            }
            this.haloOuterEnabled = outerEnabled
            this.haloOrbitEnabled = orbitEnabled
            this.haloOuterLevel = clampedOuterLv
            rebuildTablesIfNeeded(force = false)
            shadersDirty = true
            return flags
        }

        fun setWideMode(wide: Boolean) {
            if (isWideMode == wide) return
            isWideMode = wide
            if (!wide) {
                stopAndHide()
            }
        }

        fun setPlaying(playing: Boolean) {
            if (isPlaying == playing) return
            isPlaying = playing
            if (!playing) {
                stopAndHide()
            }
        }

        /** L3：同时维护原始色板、内侧色板与外侧色板；内外都未变才早退 */
        fun setPalette(rawColors: IntArray, animateFade: Boolean): Boolean {
            if (rawColors.size != 5) return false
            var changed = false
            for (i in 0 until 5) {
                val raw = rawColors[i]
                rawPaletteBuf[i] = raw
                val clampedInner = clampLightnessHsl(raw)
                val clampedOuter = if (isLightMode) clampOuterHsl(raw) else clampedInner
                pendingPaletteBuf[i] = clampedInner
                pendingOuterBuf[i] = clampedOuter
                if (clampedInner != toColors[i] || clampedOuter != outerTo[i]) {
                    changed = true
                }
            }
            if (!changed) return false

            val now = SystemClock.uptimeMillis()
            snapshotCurrentColorsInto(fromColors, outerFrom, now)
            System.arraycopy(pendingPaletteBuf, 0, toColors, 0, 5)
            System.arraycopy(pendingOuterBuf, 0, outerTo, 0, 5)

            if (animateFade && canRunFrameLoop()) {
                fadeActive = true
                fadeStartUptimeMs = now
            } else {
                fadeActive = false
                System.arraycopy(toColors, 0, fromColors, 0, 5)
                System.arraycopy(toColors, 0, blendedColors, 0, 5)
                System.arraycopy(outerTo, 0, outerFrom, 0, 5)
                System.arraycopy(outerTo, 0, outerBlended, 0, 5)
            }
            shadersDirty = true
            return true
        }

        fun updateGeometry(l: Float, t: Float, r: Float, b: Float, cutCx: Float, cutHalfW: Float) {
            pillRect.set(l, t, r, b)
            pillRadius = min(pillRect.width(), pillRect.height()) * 0.5f
            cutoutCx = cutCx
            cutoutHalfW = cutHalfW
            hasValidGeom = pillRect.width() > 2f && pillRect.height() > 2f
            lastGeomChangeUptimeMs = SystemClock.uptimeMillis()
            rebuildGeometryPaths()
            shadersDirty = true
        }

        fun kickDraw() {
            clearStaleSelfTick(SystemClock.uptimeMillis())
            if (!canRunFrameLoop()) {
                cancelTick()
                return
            }
            selfTickPending = true
            selfTickSetUptimeMs = SystemClock.uptimeMillis()
            invalidate()
        }

        fun stopAndHide() {
            cancelTick()
            fadeActive = false
            if (visibility != INVISIBLE) {
                visibility = INVISIBLE
            }
        }

        private fun cancelTick() {
            if (tickScheduled) {
                choreographer.removeFrameCallback(tickCb)
                tickScheduled = false
            }
            selfTickPending = false
        }

        private fun currentTargetIntervalMs(nowUptimeMs: Long): Long {
            val recentPiggyback = lastPiggybackUptimeMs > 0L &&
                (nowUptimeMs - lastPiggybackUptimeMs) < PIGGYBACK_FOLLOW_WINDOW_MS
            return if (recentPiggyback) PIGGYBACK_BACKOFF_DELAY_MS else Const.GLOW_STEADY_FRAME_DELAY_MS
        }

        private fun rescheduleNextTick(nowUptimeMs: Long) {
            if (!canRunFrameLoop() || !isAnimatedNow()) return
            choreographer.removeFrameCallback(tickCb)
            val targetInterval = currentTargetIntervalMs(nowUptimeMs)
            val delay = if (targetInterval <= 34L) {
                IslandFrameGrid.delayToNextSlot(nowUptimeMs)
            } else {
                val elapsed = SystemClock.uptimeMillis() - lastHaloDrawUptimeMs
                max(0L, targetInterval - elapsed)
            }
            tickScheduled = true
            choreographer.postFrameCallbackDelayed(tickCb, delay)
        }

        private fun canRunFrameLoop(): Boolean {
            return haloEnabled &&
                isWideMode &&
                isPlaying &&
                hasValidGeom &&
                isAttachedToWindow &&
                windowVisible &&
                visibility == VISIBLE
        }

        /** a2：环绕关闭时②双峰(1)/⑤水银(4)/⑥星环(5) 只剩静态画面，不续约帧循环；①双晕(0)/③Siri(2)/④光波(3) 本身含呼吸或波环动画、配色渐变期也照常跑帧。仅用于续约点门控，不拦 kickDraw/onDraw 入口，保证静态帧能被补画 */
        private fun isAnimatedNow(): Boolean {
            if (haloOrbitEnabled) return true
            if (fadeActive) return true
            return haloStyle == 0 || haloStyle == 2 || haloStyle == 3
        }

        /** a2：清掉已超 100ms 仍未等到 onDraw 的自驱标志，避免停住后首帧被误记为自驱帧 */
        private fun clearStaleSelfTick(now: Long) {
            if (selfTickPending && now - selfTickSetUptimeMs >= 100L) selfTickPending = false
        }

        private fun computeConfigHash(
            enabled: Boolean,
            style: Int,
            intensity: Int,
            periodMs: Int,
            outer: Boolean,
            lightMode: Boolean,
            orbit: Boolean,
            outerLevel: Int
        ): Int {
            var h = if (enabled) 1 else 0
            h = 31 * h + style
            h = 31 * h + intensity
            h = 31 * h + periodMs
            h = 31 * h + (if (outer) 1 else 0)
            h = 31 * h + (if (lightMode) 1 else 0)
            h = 31 * h + (if (orbit) 1 else 0)
            h = 31 * h + outerLevel
            return h
        }

        /** v1.4.6-b：内侧放开（40/54/68, 96/128/160）＋ 外圈亮度五档增益（0.85~1.35）＋ 主光带/光点直接查表 ＋ 深色外侧独立上限 120 */
        private fun rebuildTablesIfNeeded(force: Boolean) {
            val d = resources.displayMetrics.density
            if (!force &&
                cachedLutIntensity == haloIntensity &&
                cachedLutLight == isLightMode &&
                cachedLutOuterLevel == haloOuterLevel &&
                abs(cachedLutDensity - d) < 0.01f
            ) {
                return
            }
            cachedLutIntensity = haloIntensity
            cachedLutLight = isLightMode
            cachedLutOuterLevel = haloOuterLevel
            cachedLutDensity = d

            outerLayerWidthsPx[0] = 4f
            outerLayerWidthsPx[1] = 8f
            outerLayerWidthsPx[2] = 12f
            outerLayerWidthsPx[3] = min(Const.HALO_OUTER_MAX_PX, Const.HALO_OUTER_HARD_CAP_PX)
            for (i in 0 until OUTER_LAYER_COUNT) {
                outerLayerOutsetsPx[i] = outerLayerWidthsPx[i] * 0.5f
            }

            val idx = haloIntensity.coerceIn(0, 2)
            val lv = haloOuterLevel.coerceIn(Const.HALO_OUTER_LEVEL_MIN, Const.HALO_OUTER_LEVEL_MAX)
            val gain = OUTER_LEVEL_GAINS[lv]

            maxOuterAlpha = if (isLightMode) LIGHT_OUTER_CAP[idx] else MAX_OUTER_ALPHA_DARK

            val baseRipple0: Int
            val baseRimAlpha: Int
            val baseDiffuseAlpha: Int
            when (idx) {
                1 -> {
                    baseRipple0 = 60
                    innerRimWidthPx = 2.5f
                    baseRimAlpha = 128
                    baseDiffuseAlpha = 54
                    innerDiffuseDepthPx = 14f
                    cometOuterStrokePx = 4.6f
                }
                2 -> {
                    baseRipple0 = 82
                    innerRimWidthPx = Const.HALO_INNER_RIM_MAX_PX
                    baseRimAlpha = 160
                    baseDiffuseAlpha = 68
                    innerDiffuseDepthPx = Const.HALO_INNER_DIFFUSE_MAX_PX
                    cometOuterStrokePx = 5.0f
                }
                else -> {
                    baseRipple0 = 42
                    innerRimWidthPx = 2.0f
                    baseRimAlpha = 96
                    baseDiffuseAlpha = 40
                    innerDiffuseDepthPx = 12f
                    cometOuterStrokePx = 4.0f
                }
            }
            innerRippleBaseAlpha0 = (baseRipple0 * gain).roundToInt().coerceIn(0, MAX_INNER_RIM_ALPHA)
            innerRimMaxAlpha = (baseRimAlpha * gain).roundToInt().coerceIn(0, MAX_INNER_RIM_ALPHA)
            innerDiffusePeakAlpha = (baseDiffuseAlpha * gain).roundToInt().coerceIn(0, MAX_INNER_DIFFUSE_ALPHA)

            val rawMainBand = if (isLightMode) LIGHT_MAIN_BAND_ALPHAS[idx] else DARK_MAIN_BAND_ALPHAS[idx]
            mainBandBaseAlpha = (rawMainBand * gain).roundToInt().coerceIn(0, maxOuterAlpha)

            val rawOuterArr = if (isLightMode) LIGHT_OUTER_ALPHAS[idx] else DARK_OUTER_ALPHAS[idx]
            for (i in 0 until OUTER_LAYER_COUNT) {
                outerLayerAlphas[i] = (rawOuterArr[i] * gain).roundToInt().coerceIn(0, min(maxOuterAlpha, 200))
            }
            if (hasValidGeom) {
                rebuildGeometryPaths()
            }
        }

        private fun ensureRadialSpotBitmap(): Bitmap? {
            val existing = radialSpotBitmap
            if (existing != null && !existing.isRecycled) return existing
            val size = SPOT_BITMAP_SIZE
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
            val pixels = ByteArray(size * size)
            val half = (size - 1) * 0.5f
            for (y in 0 until size) {
                val dy = (y - half) / half
                for (x in 0 until size) {
                    val dx = (x - half) / half
                    val r2 = dx * dx + dy * dy
                    val a = if (r2 >= 1f) {
                        0
                    } else {
                        val edgeFade = (1f - r2)
                        (255f * exp(-3.4f * r2) * edgeFade).roundToInt().coerceIn(0, 255)
                    }
                    pixels[y * size + x] = a.toByte()
                }
            }
            bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pixels))
            radialSpotBitmap = bmp
            return bmp
        }

        private fun rebuildGeometryPaths() {
            pillPath.reset()
            pillPath.addRoundRect(pillRect, pillRadius, pillRadius, Path.Direction.CW)
            pathMeasure.setPath(pillPath, true)
            pillPerimeter = pathMeasure.length

            for (i in 0 until OUTER_LAYER_COUNT) {
                val outset = outerLayerOutsetsPx[i]
                workRect.set(
                    pillRect.left - outset,
                    pillRect.top - outset,
                    pillRect.right + outset,
                    pillRect.bottom + outset
                )
                val r = pillRadius + outset
                outerPaths[i].reset()
                outerPaths[i].addRoundRect(workRect, r, r, Path.Direction.CW)
            }

            // M3：outerPathMeasure 绑定 outerPaths[0]（外偏 2px），主光带走此路径内侧最多占 0.5px
            outerPathMeasure.setPath(outerPaths[0], true)
            outerPerimeter = outerPathMeasure.length

            val insetX = min(pillRadius * 0.85f, pillRect.width() * 0.22f)
            val halfD = innerDiffuseDepthPx * 0.5f
            arcX0 = pillRect.left + insetX
            arcX1 = pillRect.right - insetX
            topArcPath.reset()
            botArcPath.reset()
            if (arcX1 > arcX0 + 4f) {
                val topY = pillRect.top + halfD
                val botY = pillRect.bottom - halfD
                topArcPath.moveTo(arcX0, topY)
                topArcPath.lineTo(arcX1, topY)
                botArcPath.moveTo(arcX0, botY)
                botArcPath.lineTo(arcX1, botY)
            }
        }

        override fun onDraw(canvas: Canvas) {
            safeRunner {
                if (!canRunFrameLoop()) {
                    cancelTick()
                    return@safeRunner
                }
                // P1.3：onDraw 开头先撤回旧预约，再归因本帧为自驱帧或 piggyback 帧
                if (tickScheduled) {
                    choreographer.removeFrameCallback(tickCb)
                    tickScheduled = false
                }
                val now = SystemClock.uptimeMillis()
                if (selfTickPending) {
                    selfTickPending = false
                    PerfStats.haloFrames.incrementAndGet()
                } else {
                    lastPiggybackUptimeMs = now
                    PerfStats.haloPiggyback.incrementAndGet()
                }
                lastHaloDrawUptimeMs = now

                // 推进 400ms 切歌/昼夜配色插值（S3：渐变期 Shader 限每 100ms 重建一次）
                if (fadeActive) {
                    val t = ((now - fadeStartUptimeMs).toFloat() / Const.HALO_COLOR_FADE_MS.toFloat())
                        .coerceIn(0f, 1f)
                    for (i in 0 until 5) {
                        blendedColors[i] = lerpArgb(fromColors[i], toColors[i], t)
                        outerBlended[i] = if (isLightMode) lerpArgb(outerFrom[i], outerTo[i], t) else blendedColors[i]
                    }
                    if (t >= 1f) {
                        fadeActive = false
                        System.arraycopy(toColors, 0, fromColors, 0, 5)
                        System.arraycopy(outerTo, 0, outerFrom, 0, 5)
                        shadersDirty = true
                    } else if (now - lastShaderRebuildUptimeMs >= SHADER_REBUILD_MIN_INTERVAL_MS) {
                        shadersDirty = true
                    }
                } else {
                    System.arraycopy(toColors, 0, blendedColors, 0, 5)
                    if (isLightMode) {
                        System.arraycopy(outerTo, 0, outerBlended, 0, 5)
                    } else {
                        System.arraycopy(blendedColors, 0, outerBlended, 0, 5)
                    }
                }

                if (shadersDirty) {
                    rebuildDiffuseShaders(now)
                }

                val period = max(haloPeriodMs, 1000).toFloat()
                val phase = ((now % haloPeriodMs).toFloat() / period).coerceIn(0f, 1f)
                val inTransition = (now - lastGeomChangeUptimeMs) < GEOM_TRANSITION_WINDOW_MS
                val transAlphaMul = if (inTransition) 0.6f else 1.0f
                val subSegs = if (inTransition) 4 else 6

                when (haloStyle) {
                    1 -> drawDualComet(canvas, phase, transAlphaMul, subSegs)
                    2 -> drawSiriLiquid(canvas, phase, transAlphaMul, subSegs)
                    3 -> drawRippleWave(canvas, phase, transAlphaMul)
                    4 -> drawMercuryFlow(canvas, phase, transAlphaMul, subSegs)
                    5 -> drawOrbitalDual(canvas, phase, transAlphaMul, subSegs)
                    else -> drawAmbientDual(canvas, phase, transAlphaMul)
                }

                // P1.4：onDraw 末尾按跟随式间隔重新预约
                rescheduleNextTick(now)
            }
        }

        // ==================== 6 套光圈风格（共用 PathMeasure 引擎，单帧 drawPath ≤ 18，零路径模板裁剪） ====================

        /** 0: AMBIENT_DUAL — 里外双晕深海呼吸（drawPath = 7） */
        private fun drawAmbientDual(canvas: Canvas, phase: Float, transMul: Float) {
            val breath = 0.72f + 0.28f * (0.5f + 0.5f * sin(phase * TWO_PI).toFloat())
            if (haloOuterEnabled) {
                drawOuterBaseRings(canvas, SLOT_OUTER_2, breath * transMul, layerCount = 4)
            }
            drawInnerTopBottomDiffuse(canvas, breath * transMul)
            drawInnerThinRim(canvas, SLOT_INNER_1, (innerRimMaxAlpha * breath * transMul).roundToInt())
        }

        /** 1: DUAL_COMET — 里外双峰追逐流光（P5 零路径模板裁剪，drawPath ≤ 18） */
        private fun drawDualComet(canvas: Canvas, phase: Float, transMul: Float, subSegs: Int) {
            if (haloOuterEnabled) {
                val ringMul = if (isLightMode) max(0.38f, LIGHT_RING_MUL_FLOOR) else 0.38f
                drawOuterBaseRings(canvas, SLOT_OUTER_0, ringMul * transMul, layerCount = 3)
            }
            drawInnerTopBottomDiffuse(canvas, 0.85f * transMul)
            drawInnerThinRim(canvas, SLOT_INNER_1, (innerRimMaxAlpha * 0.55f * transMul).roundToInt())

            val useOuter = haloOuterEnabled
            val perim = if (useOuter) outerPerimeter else pillPerimeter
            val arcLen = perim * 0.22f
            val cap = if (useOuter) maxOuterAlpha else MAX_INNER_RIM_ALPHA
            val baseAlpha = (if (useOuter) mainBandBaseAlpha else min(innerRimMaxAlpha, MAX_INNER_RIM_ALPHA)).toFloat() * transMul
            val strokeW = if (useOuter) cometOuterStrokePx else innerRimWidthPx
            val c1 = if (useOuter) outerBlended[2] else blendedColors[2]
            val c2 = if (useOuter) outerBlended[3] else blendedColors[3]
            if (haloOrbitEnabled) {
                drawOrbitingComet(canvas, phase, arcLen, subSegs, c1, strokeW, baseAlpha, cap, clockwise = true, useOuterPath = useOuter)
                drawOrbitingComet(canvas, (phase + 0.5f) % 1f, arcLen, subSegs, c2, strokeW, baseAlpha, cap, clockwise = true, useOuterPath = useOuter)
            }

            if (haloOuterEnabled && haloOrbitEnabled) {
                val spotBase = mainBandBaseAlpha
                val spotA = (spotBase * 0.95f * transMul).roundToInt().coerceAtMost(maxOuterAlpha)
                drawSoftSpotOnPerimeter(canvas, phase, outerBlended[2], spotA, MAX_OUTER_SPOT_RADIUS_PX)
                drawSoftSpotOnPerimeter(canvas, (phase + 0.5f) % 1f, outerBlended[3], spotA, MAX_OUTER_SPOT_RADIUS_PX)
            }
        }

        /** 2: SIRI_LIQUID — Siri 液态柔融光环（P5 零路径模板裁剪，drawPath ≤ 14） */
        private fun drawSiriLiquid(canvas: Canvas, phase: Float, transMul: Float, subSegs: Int) {
            val pulse = 0.80f + 0.20f * sin(phase * TWO_PI).toFloat()
            if (haloOuterEnabled) {
                val ringMul = if (isLightMode) max(0.45f, LIGHT_RING_MUL_FLOOR) else 0.45f
                drawOuterBaseRings(canvas, SLOT_OUTER_1, ringMul * pulse * transMul, layerCount = 3)
                if (haloOrbitEnabled) {
                    val spotA = (outerLayerAlphas[0] * 1.15f * transMul).roundToInt().coerceAtMost(maxOuterAlpha)
                    for (i in 0 until 4) {
                        val p = (phase * (if (i % 2 == 0) 1f else -1f) + i * 0.25f + 2f) % 1f
                        drawSoftSpotOnPerimeter(canvas, p, outerBlended[i], spotA, MAX_OUTER_SPOT_RADIUS_PX)
                    }
                }
            }
            drawInnerTopBottomDiffuse(canvas, 0.90f * pulse * transMul)

            val useOuter = haloOuterEnabled
            val perim = if (useOuter) outerPerimeter else pillPerimeter
            val ribSegs = min(subSegs, 4)
            val arcLen = perim * 0.18f
            val cap = if (useOuter) maxOuterAlpha else MAX_INNER_RIM_ALPHA
            val ribAlpha = (if (useOuter) mainBandBaseAlpha * 0.95f else min(innerRimMaxAlpha * 0.95f, MAX_INNER_RIM_ALPHA.toFloat())) * transMul
            val ribW = if (useOuter) 3.4f else innerRimWidthPx
            val c1 = if (useOuter) outerBlended[2] else blendedColors[2]
            val c2 = if (useOuter) outerBlended[4] else blendedColors[4]
            if (haloOrbitEnabled) {
                drawOrbitingComet(canvas, phase, arcLen, ribSegs, c1, ribW, ribAlpha, cap, clockwise = true, useOuterPath = useOuter)
                drawOrbitingComet(canvas, (1f - phase + 0.35f) % 1f, arcLen, ribSegs, c2, ribW, ribAlpha, cap, clockwise = false, useOuterPath = useOuter)
            }

            drawInnerThinRim(canvas, SLOT_INNER_WHITE_TINT, (innerRimMaxAlpha * 0.85f * transMul).roundToInt())
        }

        /** 3: RIPPLE_WAVE — 呼吸光波外扩（最大扩散 ≤14px，上沿留 5px 缓冲，drawPath ≤ 9） */
        private fun drawRippleWave(canvas: Canvas, phase: Float, transMul: Float) {
            val p1 = phase
            val p2 = (phase + 0.5f) % 1f
            val launchPulse = max(1f - p1 * 3.5f, 1f - p2 * 3.5f).coerceIn(0f, 1f)
            val innerBoost = 0.72f + 0.28f * launchPulse

            drawInnerTopBottomDiffuse(canvas, innerBoost * transMul)
            drawInnerThinRim(canvas, SLOT_INNER_2, (innerRimMaxAlpha * innerBoost * transMul).roundToInt())

            if (haloOuterEnabled) {
                val ringMul = if (isLightMode) LIGHT_RIPPLE_RING_MUL else 0.30f
                drawOuterBaseRings(canvas, SLOT_OUTER_1, ringMul * transMul, layerCount = 2)
                drawSingleRippleRing(canvas, p1, SLOT_OUTER_2, transMul, outward = true)
                drawSingleRippleRing(canvas, p2, SLOT_OUTER_3, transMul, outward = true)
            } else {
                drawSingleRippleRing(canvas, p1, SLOT_INNER_RIPPLE_2, transMul, outward = false)
                drawSingleRippleRing(canvas, p2, SLOT_INNER_RIPPLE_3, transMul, outward = false)
            }
        }

        private fun drawSingleRippleRing(
            canvas: Canvas,
            progress: Float,
            colorSlot: Int,
            transMul: Float,
            outward: Boolean
        ) {
            val expVal = if (outward && isLightMode) LIGHT_RIPPLE_EXP else DARK_RIPPLE_EXP
            val fade = (1f - progress).coerceIn(0f, 1f).pow(expVal)
            if (fade <= 0.02f) return
            val maxSpread = if (outward) Const.HALO_OUTER_MAX_PX else innerDiffuseDepthPx
            val strokeW = if (outward) (2.2f + 1.8f * (1f - progress)) else 1.8f
            val offset = if (outward) {
                (progress * (maxSpread - strokeW * 0.5f)).coerceIn(0.5f, maxSpread - strokeW * 0.5f)
            } else {
                -(progress * (maxSpread - strokeW * 0.5f)).coerceIn(0.5f, maxSpread - strokeW * 0.5f)
            }

            workRect.set(
                pillRect.left - offset,
                pillRect.top - offset,
                pillRect.right + offset,
                pillRect.bottom + offset
            )
            val r = max(pillRadius + offset, 2f)
            tempRingPath.reset()
            tempRingPath.addRoundRect(workRect, r, r, Path.Direction.CW)

            val baseA = if (outward) outerLayerAlphas[0] else innerRippleBaseAlpha0
            val cap = if (outward) maxOuterAlpha else MAX_INNER_RIM_ALPHA
            val alpha = (baseA * 1.25f * fade * transMul).roundToInt().coerceIn(0, cap)
            if (alpha <= 1) return
            strokePaint.shader = obtainRingCutoutShader(colorSlot) ?: return
            strokePaint.strokeWidth = strokeW
            strokePaint.alpha = alpha
            canvas.drawPath(tempRingPath, strokePaint)
        }

        /** 4: MERCURY_FLOW — 液态水银内折光（P5 零路径模板裁剪，L4 浅色外侧用蓝银，drawPath ≤ 18） */
        private fun drawMercuryFlow(canvas: Canvas, phase: Float, transMul: Float, subSegs: Int) {
            val innerSilver = lerpArgb(blendedColors[2], Color.WHITE, 0.72f)
            val outerSilver = if (isLightMode) outerSilverColor else innerSilver
            if (haloOuterEnabled) {
                val ringMul = if (isLightMode) max(0.42f, LIGHT_RING_MUL_FLOOR) else 0.42f
                drawOuterBaseRings(canvas, SLOT_OUTER_SILVER, ringMul * transMul, layerCount = 3)
            }
            drawInnerTopBottomDiffuse(canvas, 0.80f * transMul)

            val useOuter = haloOuterEnabled
            val perim = if (useOuter) outerPerimeter else pillPerimeter
            val arcLen = perim * 0.28f
            val cap = if (useOuter) maxOuterAlpha else MAX_INNER_RIM_ALPHA
            val baseAlpha = (if (useOuter) mainBandBaseAlpha else min(innerRimMaxAlpha, MAX_INNER_RIM_ALPHA)).toFloat() * transMul
            val strokeW = if (useOuter) 3.4f else innerRimWidthPx
            val c1 = if (useOuter) outerSilver else innerSilver
            val c2 = if (useOuter) outerBlended[1] else blendedColors[1]
            if (haloOrbitEnabled) {
                drawOrbitingComet(canvas, phase, arcLen, subSegs, c1, strokeW, baseAlpha, cap, clockwise = true, useOuterPath = useOuter)
                drawOrbitingComet(canvas, (phase + 0.5f) % 1f, arcLen, subSegs, c2, strokeW, baseAlpha * 0.85f, cap, clockwise = true, useOuterPath = useOuter)
            }

            if (haloOuterEnabled && haloOrbitEnabled) {
                val spotBase = mainBandBaseAlpha
                val spotA = (spotBase * 0.90f * transMul).roundToInt().coerceAtMost(maxOuterAlpha)
                drawSoftSpotOnPerimeter(canvas, phase, outerSilver, spotA, MAX_OUTER_SPOT_RADIUS_PX)
            }
            drawInnerThinRim(canvas, SLOT_INNER_SILVER, (innerRimMaxAlpha * 0.92f * transMul).roundToInt())
        }

        /** 5: ORBITAL_DUAL — 双向星环交错（P5 零路径模板裁剪，⑥内环逆时针恒走内侧红线，drawPath ≤ 18） */
        private fun drawOrbitalDual(canvas: Canvas, phase: Float, transMul: Float, subSegs: Int) {
            if (haloOuterEnabled) {
                val ringMul = if (isLightMode) max(0.35f, LIGHT_RING_MUL_FLOOR) else 0.35f
                drawOuterBaseRings(canvas, SLOT_OUTER_0, ringMul * transMul, layerCount = 3)
            }
            drawInnerTopBottomDiffuse(canvas, 0.85f * transMul)
            drawInnerThinRim(canvas, SLOT_INNER_2, (innerRimMaxAlpha * 0.50f * transMul).roundToInt())

            val useOuter = haloOuterEnabled
            val cwPerim = if (useOuter) outerPerimeter else pillPerimeter
            val cwCap = if (useOuter) maxOuterAlpha else MAX_INNER_RIM_ALPHA
            val cwAlpha = (if (useOuter) mainBandBaseAlpha else min(innerRimMaxAlpha, MAX_INNER_RIM_ALPHA)).toFloat() * transMul
            val ccwAlpha = min(innerRimMaxAlpha * 0.90f, MAX_INNER_RIM_ALPHA.toFloat()) * transMul

            if (haloOrbitEnabled) drawOrbitingComet(
                canvas = canvas,
                headPhase = phase,
                arcLen = cwPerim * 0.24f,
                subSegs = subSegs,
                color = if (useOuter) outerBlended[1] else blendedColors[1],
                strokeW = if (useOuter) cometOuterStrokePx else innerRimWidthPx,
                maxAlpha = cwAlpha,
                alphaCap = cwCap,
                clockwise = true,
                useOuterPath = useOuter
            )
            val ccwPhase = (1f - phase) % 1f
            if (haloOrbitEnabled) drawOrbitingComet(
                canvas = canvas,
                headPhase = ccwPhase,
                arcLen = pillPerimeter * 0.24f,
                subSegs = subSegs,
                color = blendedColors[4],
                strokeW = innerRimWidthPx,
                maxAlpha = ccwAlpha,
                alphaCap = MAX_INNER_RIM_ALPHA,
                clockwise = false,
                useOuterPath = false
            )

            if (haloOuterEnabled && haloOrbitEnabled) {
                val spotBase = mainBandBaseAlpha
                val spotA1 = (spotBase * 0.85f * transMul).roundToInt().coerceAtMost(maxOuterAlpha)
                val spotA2 = (spotBase * 0.80f * transMul).roundToInt().coerceAtMost(maxOuterAlpha)
                drawSoftSpotOnPerimeter(canvas, phase, outerBlended[1], spotA1, MAX_OUTER_SPOT_RADIUS_PX)
                drawSoftSpotOnPerimeter(canvas, ccwPhase, outerBlended[4], spotA2, MAX_OUTER_SPOT_RADIUS_PX)
            }
        }

        // ==================== 基础绘制原语（全部零分配、硬件抗锯齿、零路径模板裁剪） ====================

        /** S1：底环上沿通过镜头孔 RadialGradient 衰减，下沿距离 > 衰减半径不受影响 */
        private fun drawOuterBaseRings(
            canvas: Canvas,
            colorSlot: Int,
            alphaMul: Float,
            layerCount: Int
        ) {
            strokePaint.shader = obtainRingCutoutShader(colorSlot) ?: return
            val count = min(layerCount, OUTER_LAYER_COUNT)
            for (i in count - 1 downTo 0) {
                val a = (outerLayerAlphas[i] * alphaMul).roundToInt().coerceIn(0, 255)
                if (a <= 1) continue
                strokePaint.strokeWidth = outerLayerWidthsPx[i]
                strokePaint.alpha = a
                canvas.drawPath(outerPaths[i], strokePaint)
            }
        }

        private fun drawInnerThinRim(canvas: Canvas, colorSlot: Int, alpha: Int) {
            val clamped = alpha.coerceIn(0, MAX_INNER_RIM_ALPHA)
            if (clamped <= 1) return
            strokePaint.shader = obtainRingCutoutShader(colorSlot) ?: return
            strokePaint.strokeWidth = innerRimWidthPx
            strokePaint.alpha = clamped
            canvas.drawPath(pillPath, strokePaint)
        }

        private fun drawInnerTopBottomDiffuse(canvas: Canvas, alphaMul: Float) {
            val topS = topDiffuseShader ?: return
            val botS = botDiffuseShader ?: return
            val a = (innerDiffusePeakAlpha * alphaMul).roundToInt().coerceIn(0, MAX_INNER_DIFFUSE_ALPHA)
            if (a <= 1) return
            diffusePaint.strokeWidth = innerDiffuseDepthPx
            diffusePaint.alpha = a
            diffusePaint.shader = topS
            canvas.drawPath(topArcPath, diffusePaint)
            diffusePaint.shader = botS
            canvas.drawPath(botArcPath, diffusePaint)
        }

        private fun drawOrbitingComet(
            canvas: Canvas,
            headPhase: Float,
            arcLen: Float,
            subSegs: Int,
            color: Int,
            strokeW: Float,
            maxAlpha: Float,
            alphaCap: Int,
            clockwise: Boolean,
            useOuterPath: Boolean
        ) {
            val pm = if (useOuterPath) outerPathMeasure else pathMeasure
            val perim = if (useOuterPath) outerPerimeter else pillPerimeter
            if (perim <= 4f || subSegs <= 0) return
            val headDist = ((headPhase % 1f) + 1f) % 1f * perim
            val step = arcLen / subSegs.toFloat()
            val clampedMaxAlpha = min(maxAlpha, alphaCap.toFloat())

            strokePaint.shader = null
            strokePaint.color = color
            strokePaint.strokeWidth = strokeW

            for (i in 0 until subSegs) {
                val tStart: Float
                val tEnd: Float
                if (clockwise) {
                    tEnd = headDist - i * step
                    tStart = tEnd - step
                } else {
                    tStart = headDist + i * step
                    tEnd = tStart + step
                }

                val mid = wrapDist((tStart + tEnd) * 0.5f, perim)
                val cutFactor = if (pm.getPosTan(mid, posBuf, tanBuf)) {
                    cutoutAlphaFactor(posBuf[0], posBuf[1])
                } else {
                    1f
                }

                val lutIdx = ((i * COMET_TAIL_LUT.size) / subSegs).coerceIn(0, COMET_TAIL_LUT.lastIndex)
                val segAlpha = (clampedMaxAlpha * COMET_TAIL_LUT[lutIdx] * cutFactor).roundToInt().coerceIn(0, alphaCap)
                if (segAlpha <= 1) continue

                val s0 = wrapDist(tStart, perim)
                val s1 = wrapDist(tEnd, perim)
                segPath.reset()
                if (s1 >= s0) {
                    pm.getSegment(s0, s1, segPath, true)
                } else {
                    pm.getSegment(s0, perim, segPath, true)
                    pm.getSegment(0f, s1, segPath, true)
                }
                strokePaint.alpha = segAlpha
                canvas.drawPath(segPath, strokePaint)
            }
        }

        /** P5（Quick M2 方案 A）：沿 CW 轮廓外法线 (tan.y, -tan.x) 外移 +7px、半径 ≤7px，零路径模板裁剪、不侵入内部 */
        private fun drawSoftSpotOnPerimeter(
            canvas: Canvas,
            phase: Float,
            color: Int,
            alpha: Int,
            radiusPx: Float
        ) {
            if (alpha <= 2) return
            if (!samplePointOnPill(phase)) return
            val cx = posBuf[0] + tanBuf[1] * SPOT_OUTWARD_OFFSET_PX
            val cy = posBuf[1] - tanBuf[0] * SPOT_OUTWARD_OFFSET_PX
            val cutFactor = cutoutAlphaFactor(cx, cy)
            val finalA = (alpha * cutFactor).roundToInt()
            if (finalA <= 2) return
            val r = min(radiusPx, MAX_OUTER_SPOT_RADIUS_PX).coerceAtMost(Const.HALO_SPOT_HARD_CAP_PX - SPOT_OUTWARD_OFFSET_PX)
            drawSpotAt(canvas, cx, cy, r, color, finalA)
        }

        /** v1.4.6-b：直接查表返回当前档位主光带/光点基数（已乘 outerGain 并受 maxOuterAlpha 钳制） */
        private fun lightOuterBaseAlpha(): Int = mainBandBaseAlpha

        private fun drawSpotAt(
            canvas: Canvas,
            cx: Float,
            cy: Float,
            radiusPx: Float,
            color: Int,
            alpha: Int
        ) {
            val bmp = ensureRadialSpotBitmap() ?: return
            val a = alpha.coerceIn(0, maxOuterAlpha)
            if (a <= 1) return
            spotPaint.color = color
            spotPaint.alpha = a
            spotDstRect.set(cx - radiusPx, cy - radiusPx, cx + radiusPx, cy + radiusPx)
            canvas.drawBitmap(bmp, null, spotDstRect, spotPaint)
        }

        private fun samplePointOnPill(phase: Float): Boolean {
            val perim = pillPerimeter
            if (perim <= 4f) return false
            val dist = (((phase % 1f) + 1f) % 1f) * perim
            return pathMeasure.getPosTan(dist, posBuf, tanBuf)
        }

        private fun wrapDist(d: Float, perim: Float): Float {
            var r = d % perim
            if (r < 0f) r += perim
            return r
        }

        private fun cutoutAlphaFactor(x: Float, y: Float): Float {
            if (y > pillRect.centerY() + 6f) return 1f
            val dx = abs(x - cutoutCx)
            if (dx <= cutoutHalfW) return CUTOUT_MIN_ALPHA_FACTOR
            val fadeEnd = cutoutHalfW + CUTOUT_FADE_MARGIN_PX
            if (dx >= fadeEnd) return 1f
            val t = (dx - cutoutHalfW) / CUTOUT_FADE_MARGIN_PX
            return CUTOUT_MIN_ALPHA_FACTOR + (1f - CUTOUT_MIN_ALPHA_FACTOR) * t
        }

        /** L7：12 槽预建 Shader 查找，越界打一次 [HALO] bad_slot 并跳过不画 */
        private fun obtainRingCutoutShader(slot: Int): Shader? {
            if (!hasValidGeom) return null
            if (slot !in 0 until RING_SHADER_SLOT_COUNT) {
                if (!badSlotLogged) {
                    badSlotLogged = true
                    EventHistoryStore.recordDiagOnly("[HALO] bad_slot slot=$slot", dedupKey = "halo_bad_slot")
                }
                return null
            }
            return ringCutoutShaders[slot]
        }

        private fun createRingCutoutGradient(color: Int): RadialGradient {
            val rgb = color and 0x00FFFFFF
            val dimAlpha = (255f * CUTOUT_MIN_ALPHA_FACTOR).roundToInt().coerceIn(0, 255)
            ringCutoutColors[0] = (dimAlpha shl 24) or rgb
            ringCutoutColors[1] = (0xFF shl 24) or rgb
            ringCutoutColors[2] = (0xFF shl 24) or rgb
            val fadeRadius = min(cutoutHalfW + CUTOUT_FADE_MARGIN_PX, pillRect.height() * 0.72f).coerceAtLeast(24f)
            return RadialGradient(
                cutoutCx,
                pillRect.top,
                fadeRadius,
                ringCutoutColors,
                RING_CUTOUT_STOPS,
                Shader.TileMode.CLAMP
            )
        }

        /** S1 + S3 + L7：重建上下弧内漫射与 12 槽镜头孔衰减 Shader（深色模式外侧槽直接指向内侧不重复新建） */
        private fun rebuildDiffuseShaders(nowUptimeMs: Long) {
            shadersDirty = false
            lastShaderRebuildUptimeMs = nowUptimeMs
            val span = arcX1 - arcX0
            if (!hasValidGeom || span <= 4f) {
                topDiffuseShader = null
                botDiffuseShader = null
                ringCutoutShaders.fill(null)
                return
            }
            val c1 = blendedColors[1] and 0x00FFFFFF
            val c2 = blendedColors[2] and 0x00FFFFFF
            val c3 = blendedColors[3] and 0x00FFFFFF

            // 下弧带：严格按 arcX0 → arcX1，两端点 alpha = 0
            botDiffuseColors[0] = c1
            botDiffuseColors[1] = (0xFF shl 24) or c1
            botDiffuseColors[2] = (0xFF shl 24) or c2
            botDiffuseColors[3] = (0xFF shl 24) or c3
            botDiffuseColors[4] = c3
            botDiffuseShader = LinearGradient(
                arcX0,
                pillRect.bottom,
                arcX1,
                pillRect.bottom,
                botDiffuseColors,
                BOT_DIFFUSE_STOPS,
                Shader.TileMode.CLAMP
            )

            // 上弧带：在镜头孔水平带加平滑凹陷（中心降至 12%），两端同样严格 0
            val cutLeft = ((cutoutCx - cutoutHalfW - CUTOUT_FADE_MARGIN_PX - arcX0) / span).coerceIn(0.20f, 0.45f)
            val cutCenter = ((cutoutCx - arcX0) / span).coerceIn(cutLeft + 0.02f, 0.75f)
            val cutRight = ((cutoutCx + cutoutHalfW + CUTOUT_FADE_MARGIN_PX - arcX0) / span).coerceIn(cutCenter + 0.02f, 0.80f)
            topDiffuseStops[0] = 0f
            topDiffuseStops[1] = 0.16f
            topDiffuseStops[2] = cutLeft
            topDiffuseStops[3] = cutCenter
            topDiffuseStops[4] = cutRight
            topDiffuseStops[5] = 0.84f
            topDiffuseStops[6] = 1f

            val dipAlpha = (255f * CUTOUT_MIN_ALPHA_FACTOR).roundToInt().coerceIn(0, 255)
            topDiffuseColors[0] = c1
            topDiffuseColors[1] = (0xFF shl 24) or c1
            topDiffuseColors[2] = (0xFF shl 24) or c2
            topDiffuseColors[3] = (dipAlpha shl 24) or c2
            topDiffuseColors[4] = (0xFF shl 24) or c2
            topDiffuseColors[5] = (0xFF shl 24) or c3
            topDiffuseColors[6] = c3
            topDiffuseShader = LinearGradient(
                arcX0,
                pillRect.top,
                arcX1,
                pillRect.top,
                topDiffuseColors,
                topDiffuseStops,
                Shader.TileMode.CLAMP
            )

            diffuseMatrix.reset()
            botDiffuseShader?.setLocalMatrix(diffuseMatrix)
            topDiffuseShader?.setLocalMatrix(diffuseMatrix)

            // L7：12 槽镜头孔衰减 Shader 预建（仅 shadersDirty 时重建，渐变期仍受 100ms 节流）
            silverColor = lerpArgb(blendedColors[2], Color.WHITE, 0.72f)
            whiteTintColor = lerpArgb(blendedColors[2], Color.WHITE, 0.55f)
            outerSilverColor = if (isLightMode) {
                hslToArgb(OUTER_SILVER_H, OUTER_SILVER_S, OUTER_SILVER_L)
            } else {
                silverColor
            }

            // 内侧组（6..11）：内侧 1、2、silver、whiteTint ＋ 内缩环 2（复用 7）、内缩环 3
            val innerS1 = createRingCutoutGradient(blendedColors[1])
            val innerS2 = createRingCutoutGradient(blendedColors[2])
            val innerS3 = createRingCutoutGradient(blendedColors[3])
            val innerSilverS = createRingCutoutGradient(silverColor)
            val innerWhiteS = createRingCutoutGradient(whiteTintColor)

            ringCutoutShaders[SLOT_INNER_1] = innerS1
            ringCutoutShaders[SLOT_INNER_2] = innerS2
            ringCutoutShaders[SLOT_INNER_SILVER] = innerSilverS
            ringCutoutShaders[SLOT_INNER_WHITE_TINT] = innerWhiteS
            ringCutoutShaders[SLOT_INNER_RIPPLE_2] = innerS2
            ringCutoutShaders[SLOT_INNER_RIPPLE_3] = innerS3

            // 外侧组（0..5）：深色模式直接指向内侧已有 Shader 不重复新建（仅 0/4 单独建，总计 7 个同 vc47）
            if (!isLightMode) {
                ringCutoutShaders[SLOT_OUTER_0] = createRingCutoutGradient(blendedColors[0])
                ringCutoutShaders[SLOT_OUTER_1] = innerS1
                ringCutoutShaders[SLOT_OUTER_2] = innerS2
                ringCutoutShaders[SLOT_OUTER_3] = innerS3
                ringCutoutShaders[SLOT_OUTER_4] = createRingCutoutGradient(blendedColors[4])
                ringCutoutShaders[SLOT_OUTER_SILVER] = innerSilverS
            } else {
                for (i in 0 until 5) {
                    ringCutoutShaders[SLOT_OUTER_0 + i] = createRingCutoutGradient(outerBlended[i])
                }
                ringCutoutShaders[SLOT_OUTER_SILVER] = createRingCutoutGradient(outerSilverColor)
            }
        }

        private fun snapshotCurrentColorsInto(dstInner: IntArray, dstOuter: IntArray, nowUptimeMs: Long) {
            if (fadeActive) {
                val t = ((nowUptimeMs - fadeStartUptimeMs).toFloat() / Const.HALO_COLOR_FADE_MS.toFloat())
                    .coerceIn(0f, 1f)
                for (i in 0 until 5) {
                    dstInner[i] = lerpArgb(fromColors[i], toColors[i], t)
                    dstOuter[i] = lerpArgb(outerFrom[i], outerTo[i], t)
                }
            } else {
                System.arraycopy(toColors, 0, dstInner, 0, 5)
                System.arraycopy(outerTo, 0, dstOuter, 0, 5)
            }
        }

        /** 内侧 HSL 亮度下限钳制（L >= 0.65，深浅模式均不变），复用 hslBuf 零分配 */
        private fun clampLightnessHsl(argb: Int): Int {
            val r = ((argb ushr 16) and 0xFF) / 255f
            val g = ((argb ushr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val cMax = max(r, max(g, b))
            val cMin = min(r, min(g, b))
            val delta = cMax - cMin
            val l = (cMax + cMin) * 0.5f
            if (l >= Const.HALO_MIN_HSL_LIGHTNESS) return (0xFF shl 24) or (argb and 0x00FFFFFF)

            val h = when {
                delta < 1e-5f -> 0f
                cMax == r -> 60f * (((g - b) / delta) % 6f)
                cMax == g -> 60f * (((b - r) / delta) + 2f)
                else -> 60f * (((r - g) / delta) + 4f)
            }.let { if (it < 0f) it + 360f else it }
            val s = if (delta < 1e-5f) 0f else delta / (1f - abs(2f * l - 1f))
            val newL = Const.HALO_MIN_HSL_LIGHTNESS

            hslBuf[0] = h
            hslBuf[1] = s.coerceIn(0f, 1f)
            hslBuf[2] = newL
            return hslToArgb(hslBuf[0], hslBuf[1], hslBuf[2])
        }

        /** L4：浅色模式外侧色板钳制——保留封面色相、S≥0.70、L∈[0.46, 0.55]，灰封面(S<0.15)回落冰蓝 */
        private fun clampOuterHsl(argb: Int): Int {
            val r = ((argb ushr 16) and 0xFF) / 255f
            val g = ((argb ushr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val cMax = max(r, max(g, b))
            val cMin = min(r, min(g, b))
            val delta = cMax - cMin
            val l = (cMax + cMin) * 0.5f
            val s = if (delta < 1e-5f) 0f else (delta / (1f - abs(2f * l - 1f))).coerceIn(0f, 1f)
            if (s < GRAY_S_THRESHOLD) {
                return hslToArgb(OUTER_FALLBACK_H, OUTER_FALLBACK_S, OUTER_FALLBACK_L)
            }
            val h = when {
                delta < 1e-5f -> 0f
                cMax == r -> 60f * (((g - b) / delta) % 6f)
                cMax == g -> 60f * (((b - r) / delta) + 2f)
                else -> 60f * (((r - g) / delta) + 4f)
            }.let { if (it < 0f) it + 360f else it }

            hslBuf[0] = h
            hslBuf[1] = max(s, OUTER_MIN_S).coerceIn(0f, 1f)
            hslBuf[2] = l.coerceIn(OUTER_MIN_L, OUTER_MAX_L)
            return hslToArgb(hslBuf[0], hslBuf[1], hslBuf[2])
        }

        private fun hslToArgb(h: Float, s: Float, l: Float): Int {
            val c = (1f - abs(2f * l - 1f)) * s
            val x = c * (1f - abs((h / 60f) % 2f - 1f))
            val m = l - c * 0.5f
            val r1: Float
            val g1: Float
            val b1: Float
            when {
                h < 60f -> { r1 = c; g1 = x; b1 = 0f }
                h < 120f -> { r1 = x; g1 = c; b1 = 0f }
                h < 180f -> { r1 = 0f; g1 = c; b1 = x }
                h < 240f -> { r1 = 0f; g1 = x; b1 = c }
                h < 300f -> { r1 = x; g1 = 0f; b1 = c }
                else -> { r1 = c; g1 = 0f; b1 = x }
            }
            val r = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
            val g = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
            val b = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        private fun lerpArgb(c0: Int, c1: Int, t: Float): Int {
            val a0 = (c0 ushr 24) and 0xFF
            val r0 = (c0 ushr 16) and 0xFF
            val g0 = (c0 ushr 8) and 0xFF
            val b0 = c0 and 0xFF

            val a1 = (c1 ushr 24) and 0xFF
            val r1 = (c1 ushr 16) and 0xFF
            val g1 = (c1 ushr 8) and 0xFF
            val b1 = c1 and 0xFF

            val a = (a0 + (a1 - a0) * t).roundToInt().coerceIn(0, 255)
            val r = (r0 + (r1 - r0) * t).roundToInt().coerceIn(0, 255)
            val g = (g0 + (g1 - g0) * t).roundToInt().coerceIn(0, 255)
            val b = (b0 + (b1 - b0) * t).roundToInt().coerceIn(0, 255)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        companion object {
            private const val OUTER_LAYER_COUNT = 4
            private const val SPOT_BITMAP_SIZE = 64
            private const val SPOT_OUTWARD_OFFSET_PX = Const.HALO_SPOT_OUTWARD_OFFSET_PX   // 8f：圆心沿外法线外移 +8px
            private const val MAX_OUTER_SPOT_RADIUS_PX = Const.HALO_SPOT_MAX_RADIUS_PX // 10f：光点半径 10px，硬顶 18px
            private const val PIGGYBACK_FOLLOW_WINDOW_MS = 100L
            private const val PIGGYBACK_BACKOFF_DELAY_MS = 60L
            private const val TICK_JITTER_TOLERANCE_MS = 4L
            private const val GEOM_TRANSITION_WINDOW_MS = 50L
            private const val SHADER_REBUILD_MIN_INTERVAL_MS = 100L // S3：400ms 渐变期最多每 100ms 重建一次
            private const val MAX_INNER_RIM_ALPHA = 170
            private const val MAX_INNER_DIFFUSE_ALPHA = 72
            private const val MAX_OUTER_ALPHA_DARK = 120
            private const val CUTOUT_MIN_ALPHA_FACTOR = 0.12f
            private const val CUTOUT_FADE_MARGIN_PX = 18f
            private const val DARK_RIPPLE_EXP = 1.75f
            private const val TWO_PI = (PI * 2.0).toFloat()

            // v1.4.6-b：外层四层基础表（深/浅）、主光带/光点直接查表（深/浅）、外圈亮度五档增益
            private val DARK_OUTER_ALPHAS = arrayOf(
                intArrayOf(42, 24, 14, 9),
                intArrayOf(60, 34, 20, 12),
                intArrayOf(82, 46, 26, 16)
            )
            private val LIGHT_OUTER_ALPHAS = arrayOf(
                intArrayOf(80, 45, 26, 15),
                intArrayOf(116, 66, 38, 21),
                intArrayOf(156, 88, 50, 28)
            )
            private val DARK_MAIN_BAND_ALPHAS = intArrayOf(58, 78, 98)
            private val LIGHT_MAIN_BAND_ALPHAS = intArrayOf(104, 140, 176)
            private val LIGHT_OUTER_CAP = intArrayOf(150, 178, 200)
            private val OUTER_LEVEL_GAINS = floatArrayOf(0.85f, 1.00f, 1.15f, 1.25f, 1.35f)
            private const val LIGHT_RING_MUL_FLOOR = 0.65f
            private const val LIGHT_RIPPLE_RING_MUL = 0.70f
            private const val LIGHT_RIPPLE_EXP = 1.45f
            private const val OUTER_MIN_S = 0.70f
            private const val OUTER_MIN_L = 0.46f
            private const val OUTER_MAX_L = 0.55f
            private const val GRAY_S_THRESHOLD = 0.15f
            private const val OUTER_FALLBACK_H = 214f
            private const val OUTER_FALLBACK_S = 0.78f
            private const val OUTER_FALLBACK_L = 0.52f
            private const val OUTER_SILVER_H = 212f
            private const val OUTER_SILVER_S = 0.60f
            private const val OUTER_SILVER_L = 0.52f

            // L7：12 槽底环/细边 Shader 索引
            private const val RING_SHADER_SLOT_COUNT = 12
            private const val SLOT_OUTER_0 = 0
            private const val SLOT_OUTER_1 = 1
            private const val SLOT_OUTER_2 = 2
            private const val SLOT_OUTER_3 = 3
            private const val SLOT_OUTER_4 = 4
            private const val SLOT_OUTER_SILVER = 5
            private const val SLOT_INNER_1 = 6
            private const val SLOT_INNER_2 = 7
            private const val SLOT_INNER_SILVER = 8
            private const val SLOT_INNER_WHITE_TINT = 9
            private const val SLOT_INNER_RIPPLE_2 = 10
            private const val SLOT_INNER_RIPPLE_3 = 11

            private val COMET_TAIL_LUT = floatArrayOf(
                1.00f, 0.78f, 0.58f, 0.42f, 0.28f, 0.18f, 0.10f, 0.05f
            )
            private val BOT_DIFFUSE_STOPS = floatArrayOf(0f, 0.22f, 0.50f, 0.78f, 1f)
            private val RING_CUTOUT_STOPS = floatArrayOf(0f, 0.74f, 1f)
        }
    }

    companion object {
        private const val GATE_DEBOUNCE_MS = 300L
        private const val DIAG_POLL_INTERVAL_MS = 1000L
        private const val GEOM_PREDRAW_MIN_INTERVAL_MS = 33L

        private const val GEOM_NOT_READY = 0
        private const val GEOM_UNCHANGED_READY = 1
        private const val GEOM_CHANGED_READY = 2

        private const val CFG_CHANGED_SWITCH = 1
        private const val CFG_CHANGED_STYLE = 2
        private const val CFG_CHANGED_OTHER = 4

        fun deriveKaraokeSungColor(palette: IntArray): Int {
            if (palette.size < 5) return Const.KARAOKE_AMBER_FALLBACK
            var isSilver = true
            for (i in 0 until 5) {
                if (palette[i] != Const.GLOW_SILVER_PALETTE[i]) {
                    isSilver = false
                    break
                }
            }
            if (isSilver) return Const.KARAOKE_AMBER_FALLBACK

            val hsv = FloatArray(3)
            Color.colorToHSV(palette[2], hsv)
            hsv[2] = max(hsv[2], Const.KARAOKE_MIN_VALUE)
            if (hsv[0] in 205f..285f) {
                hsv[1] = min(hsv[1], 0.62f)
                hsv[2] = max(hsv[2], 0.94f)
            }
            return Color.HSVToColor(0xFF, hsv)
        }

        fun extractPalette(src: Bitmap?): IntArray {
            if (src == null || src.isRecycled || src.width <= 0 || src.height <= 0) {
                return Const.GLOW_SILVER_PALETTE.copyOf()
            }

            return runCatching {
                val maxDim = Const.GLOW_SAMPLE_MAX_DIM
                val srcW = src.width
                val srcH = src.height
                val targetW: Int
                val targetH: Int
                if (srcW > maxDim || srcH > maxDim) {
                    val scale = maxDim.toFloat() / max(srcW, srcH).toFloat()
                    targetW = max(1, (srcW * scale).roundToInt())
                    targetH = max(1, (srcH * scale).roundToInt())
                } else {
                    targetW = srcW
                    targetH = srcH
                }

                val scaled = if (targetW != srcW || targetH != srcH) {
                    Bitmap.createScaledBitmap(src, targetW, targetH, true)
                } else {
                    src
                }

                val pixels = IntArray(targetW * targetH)
                try {
                    scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
                } finally {
                    if (scaled !== src && !scaled.isRecycled) {
                        scaled.recycle()
                    }
                }

                val hsv = FloatArray(3)
                var validCount = 0
                var sumSat = 0.0
                var sumWeight = 0.0
                var sumCos = 0.0
                var sumSin = 0.0
                var weightedSat = 0.0
                var weightedVal = 0.0
                var maxPixelWeight = -1.0
                var peakHue = 0f

                for (pixel in pixels) {
                    val alpha = (pixel ushr 24) and 0xFF
                    if (alpha < 128) continue
                    Color.colorToHSV(pixel, hsv)
                    val h = hsv[0]
                    val s = hsv[1]
                    val v = hsv[2]

                    validCount++
                    sumSat += s

                    val weight = (s * v).toDouble()
                    if (weight > 0.01) {
                        val rad = h.toDouble() * PI / 180.0
                        sumCos += cos(rad) * weight
                        sumSin += sin(rad) * weight
                        sumWeight += weight
                        weightedSat += s * weight
                        weightedVal += v * weight
                        if (weight > maxPixelWeight) {
                            maxPixelWeight = weight
                            peakHue = h
                        }
                    }
                }

                if (validCount == 0) {
                    return@runCatching Const.GLOW_SILVER_PALETTE.copyOf()
                }
                val avgSat = (sumSat / validCount).toFloat()
                if (avgSat < Const.GLOW_GRAYSCALE_SAT_THRESHOLD || sumWeight < 1e-3) {
                    return@runCatching Const.GLOW_SILVER_PALETTE.copyOf()
                }

                var domHue = if (sumCos * sumCos + sumSin * sumSin < 1e-6) {
                    peakHue
                } else {
                    (atan2(sumSin, sumCos) * 180.0 / PI).toFloat()
                }
                if (domHue < 0f) domHue += 360f
                val baseS = (weightedSat / sumWeight).toFloat().coerceIn(0.45f, 0.95f)
                val baseV = (weightedVal / sumWeight).toFloat().coerceIn(0.68f, 1.0f)

                val stopS = floatArrayOf(0.90f, 1.00f, 0.78f, 1.00f, 0.88f)
                val stopV = floatArrayOf(0.80f, 0.95f, 1.08f, 0.92f, 0.76f)
                val palette = IntArray(5)
                for (i in 0 until 5) {
                    hsv[0] = domHue
                    hsv[1] = (baseS * stopS[i]).coerceIn(0.30f, 1.0f)
                    hsv[2] = (baseV * stopV[i]).coerceIn(0.48f, 1.0f)
                    palette[i] = Color.HSVToColor(0xFF, hsv)
                }
                palette
            }.getOrElse {
                Const.GLOW_SILVER_PALETTE.copyOf()
            }
        }
    }
}
