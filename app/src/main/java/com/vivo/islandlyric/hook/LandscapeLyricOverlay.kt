package com.vivo.islandlyric.hook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.highcapable.yukihookapi.hook.log.YLog
import com.vivo.islandlyric.Const
import com.vivo.islandlyric.util.EventHistoryStore
import com.vivo.islandlyric.util.HookCircuitBreaker
import com.vivo.islandlyric.util.LrcTimelineParser
import com.vivo.islandlyric.util.RuntimePrefs
import kotlin.math.roundToInt

/**
 * v1.3.2：横屏/游戏顶部纯文字悬浮歌词控制器（运行于 com.vivo.systemuiplugin:island）：
 * - 无背景纯文字＋轻微阴影，未唱部分采用 84% 亮白（215/255），亮场景不发虚；
 * - 完全触摸穿透（FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE），不挡任何游戏操作；
 * - 仅当「横屏 + 原生原子岛隐藏 + 播放态 + 设置开关开启」同时满足时挂载，
 *   切回竖屏、暂停或原生原子岛恢复瞬间立即卸载，静态/暂停零重绘。
 */
class LandscapeLyricOverlay(
    private val mainHandler: Handler,
    private val readPrefs: () -> RuntimePrefs,
    private val positionProvider: () -> Long,
    private val isNativeIslandShowing: () -> Boolean,
    private val nativeWindowTypeProvider: () -> Int?
) {
    private val breaker = HookCircuitBreaker("Island.LandscapeOverlay")

    @Volatile
    private var hostContext: Context? = null

    @Volatile
    private var isPlaybackPlaying: Boolean = false

    @Volatile
    private var sessionActive: Boolean = false

    private var currentLyric: String = ""
    private var currentWordSpans: List<LrcTimelineParser.WordSpan> = emptyList()
    private var currentLineSeq: Long = 0L
    private var currentEstimatedWindowMs: Long = Const.SCROLL_DEFAULT_WINDOW_MS
    private var currentKaraokeDurationMs: Long = Const.SCROLL_DEFAULT_WINDOW_MS
    private var currentKaraokeSungColor: Int = Const.KARAOKE_AMBER_FALLBACK

    private var currentLyricStartUptimeMs: Long = 0L
    private var currentLyricPausedAccumMs: Long = 0L
    private var currentLyricPauseStartUptimeMs: Long = 0L

    private var inIntroMode: Boolean = false
    private var currentIntroTargetMs: Long = -1L
    private var introSkipWaveLead: Boolean = false
    private var introForceWaveOnly: Boolean = false
    private var inInterludeMode: Boolean = false
    private var currentInterludeTargetMs: Long = -1L

    private var windowManager: WindowManager? = null
    private var overlayRoot: FrameLayout? = null
    private var lyricView: LyricScrollView? = null
    private var introCdView: InterludeCountdownView? = null
    private var interludeCdView: InterludeCountdownView? = null
    private var overlayAttached: Boolean = false
    private var currentLayoutParams: WindowManager.LayoutParams? = null
    private var lastRenderedLineSeq: Long = -1L
    private var lastRenderedText: String = ""
    private var lastRenderedState: Int = -1
    private var attachLoggedOnce: Boolean = false

    fun attachHostContext(ctx: Context) {
        val appCtx = ctx.applicationContext ?: ctx
        hostContext = appCtx
        windowManager = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }

    fun onConfigurationChanged() {
        mainHandler.post {
            breaker.execute {
                reconcile("config_changed")
            }
        }
    }

    fun onNativeIslandStateChanged() {
        mainHandler.post {
            breaker.execute {
                reconcile("native_island_changed")
            }
        }
    }

    fun onPrefsChanged() {
        mainHandler.post {
            breaker.execute {
                reconcile("prefs_changed")
            }
        }
    }

    fun updateGlowPalette(colors: IntArray) {
        mainHandler.post {
            breaker.execute {
                currentKaraokeSungColor = CapsuleGlowRenderer.deriveKaraokeSungColor(colors)
                lyricView?.setKaraokeSungColor(currentKaraokeSungColor)
                introCdView?.setAccentColor(currentKaraokeSungColor)
                interludeCdView?.setAccentColor(currentKaraokeSungColor)
            }
        }
    }

    fun updatePlaybackPlaying(isPlaying: Boolean) {
        com.vivo.islandlyric.util.PerfStats.overlayPlayUpdates.incrementAndGet()
        isPlaybackPlaying = isPlaying
        if (isPlaying) {
            sessionActive = true
        }
        mainHandler.post {
            breaker.execute {
                val now = SystemClock.uptimeMillis()
                if (!isPlaying) {
                    if (currentLyricStartUptimeMs > 0L && currentLyricPauseStartUptimeMs == 0L) {
                        currentLyricPauseStartUptimeMs = now
                    }
                    lyricView?.setPlaying(false)
                    introCdView?.setPlaying(false)
                    interludeCdView?.setPlaying(false)
                    detachOverlay("playback_paused")
                } else {
                    if (currentLyricPauseStartUptimeMs > 0L) {
                        currentLyricPausedAccumMs += (now - currentLyricPauseStartUptimeMs).coerceAtLeast(0L)
                        currentLyricPauseStartUptimeMs = 0L
                    }
                    reconcile("playback_playing")
                }
            }
        }
    }

    fun updateLyric(
        lyric: String,
        isPlaying: Boolean,
        estimatedWindowMs: Long,
        karaokeDurationMs: Long,
        wordSpans: List<LrcTimelineParser.WordSpan>,
        lineSeq: Long
    ) {
        com.vivo.islandlyric.util.PerfStats.overlayLyricUpdates.incrementAndGet()
        sessionActive = true
        isPlaybackPlaying = isPlaying
        mainHandler.post {
            breaker.execute {
                val seqChanged = lineSeq > 0L && lineSeq != currentLineSeq
                if (seqChanged || lyric != currentLyric || currentLyricStartUptimeMs <= 0L) {
                    resetLineClock(startNow = lyric.isNotBlank(), isPlaying = isPlaying)
                }
                if (lineSeq > 0L) {
                    currentLineSeq = lineSeq
                }
                currentLyric = lyric
                currentWordSpans = wordSpans
                currentEstimatedWindowMs = estimatedWindowMs
                currentKaraokeDurationMs = karaokeDurationMs
                inInterludeMode = false
                currentInterludeTargetMs = -1L
                if (lyric.isNotBlank() && inIntroMode) {
                    inIntroMode = false
                    introSkipWaveLead = false
                    introForceWaveOnly = false
                }
                reconcile("lyric_update")
            }
        }
    }

    fun updateIntro(
        inIntro: Boolean,
        learnedFirstLineMs: Long,
        skipWaveLead: Boolean = false,
        forceWaveOnly: Boolean = false
    ) {
        com.vivo.islandlyric.util.PerfStats.overlayIntroUpdates.incrementAndGet()
        mainHandler.post {
            breaker.execute {
                inIntroMode = inIntro
                currentIntroTargetMs = if (inIntro) learnedFirstLineMs else -1L
                introSkipWaveLead = inIntro && skipWaveLead && !forceWaveOnly
                introForceWaveOnly = inIntro && forceWaveOnly
                if (inIntro && isPlaybackPlaying) {
                    sessionActive = true
                }
                reconcile("intro_changed")
            }
        }
    }

    fun updateInterlude(inInterlude: Boolean, targetNextLineMs: Long) {
        com.vivo.islandlyric.util.PerfStats.overlayInterludeUpdates.incrementAndGet()
        mainHandler.post {
            breaker.execute {
                inInterludeMode = inInterlude
                currentInterludeTargetMs = if (inInterlude) targetNextLineMs else -1L
                reconcile("interlude_changed")
            }
        }
    }

    fun clearForTrackSwitch(newInitialTitle: String, isPlaying: Boolean, estimatedWindowMs: Long) {
        mainHandler.post {
            breaker.execute {
                sessionActive = true
                isPlaybackPlaying = isPlaying
                currentEstimatedWindowMs = estimatedWindowMs
                currentKaraokeDurationMs = estimatedWindowMs
                inInterludeMode = false
                currentInterludeTargetMs = -1L
                currentWordSpans = emptyList()
                lastRenderedLineSeq = -1L
                lastRenderedText = ""
                lastRenderedState = -1
                if (newInitialTitle.isNotBlank()) {
                    currentLyric = newInitialTitle
                    resetLineClock(startNow = true, isPlaying = isPlaying)
                } else {
                    currentLyric = ""
                    resetLineClock(startNow = false, isPlaying = false)
                }
                reconcile("track_switch")
            }
        }
    }

    fun clearAndRestore(reason: String) {
        sessionActive = false
        isPlaybackPlaying = false
        mainHandler.post {
            breaker.execute {
                inIntroMode = false
                currentIntroTargetMs = -1L
                introSkipWaveLead = false
                introForceWaveOnly = false
                inInterludeMode = false
                currentInterludeTargetMs = -1L
                currentLyric = ""
                currentWordSpans = emptyList()
                lastRenderedLineSeq = -1L
                lastRenderedText = ""
                lastRenderedState = -1
                resetLineClock(startNow = false, isPlaying = false)
                detachOverlay(reason)
            }
        }
    }

    private fun reconcile(trigger: String) {
        val ctx = hostContext ?: return
        val prefs = readPrefs()
        // v1.3.11：三态互斥，进入无倒计时尾奏/重播声波态（target <= 0）时横屏同步隐藏不留末句
        val hasContent = when {
            inIntroMode -> currentIntroTargetMs > 0L && !introForceWaveOnly
            inInterludeMode -> currentInterludeTargetMs > 0L
            else -> currentLyric.isNotBlank()
        }

        // vc60：悬浮歌词与档位解绑——摘岛（OFF）与歌词悬浮窗（LYRIC）都可出，默认岛（NATIVE）不出（岛在场免双份）。
        // 摘岛档不查 isNativeIslandShowing()：该判定看的是我方接管态、横屏切换有 150ms 防抖竞态（双审 M1），且摘岛档岛由我方亲手藏、无撞车之虞。
        val shouldShow = prefs.masterEnabled &&
            prefs.landscapeLyricEnabled &&
            prefs.landscapeMode != Const.LANDSCAPE_MODE_NATIVE &&
            sessionActive &&
            isPlaybackPlaying &&
            hasContent &&
            isCurrentlyLandscape(ctx) &&
            (prefs.landscapeMode == Const.LANDSCAPE_MODE_OFF || !isNativeIslandShowing())

        if (!shouldShow) {
            if (overlayAttached) {
                detachOverlay("hide:$trigger")
            }
            return
        }

        if (!ensureOverlayAttached(ctx, prefs)) return
        renderContent(ctx, prefs)
    }

    private fun isCurrentlyLandscape(ctx: Context): Boolean {
        val cfg = ctx.resources.configuration
        if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) return true
        val dm = ctx.resources.displayMetrics
        return dm.widthPixels > dm.heightPixels
    }

    private fun ensureOverlayAttached(ctx: Context, prefs: RuntimePrefs): Boolean {
        // vc60 横屏档位收口：默认岛档绝不挂悬浮窗（闸放在 attach 收口处，不只 reconcile）；摘岛/歌词悬浮窗可挂
        if (prefs.landscapeMode == Const.LANDSCAPE_MODE_NATIVE) return false
        if (overlayAttached && overlayRoot != null) return true
        val wm = windowManager ?: (ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.also { windowManager = it } ?: return false

        val dm = ctx.resources.displayMetrics
        val targetWidthPx = (dm.widthPixels * 0.56f).roundToInt()
            .coerceIn(dp(ctx, 240f), dp(ctx, 440f))

        val container = overlayRoot ?: FrameLayout(ctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            setPadding(dp(ctx, 10f), dp(ctx, 4f), dp(ctx, 10f), dp(ctx, 4f))
        }.also { overlayRoot = it }

        if (lyricView == null) {
            val lv = LyricScrollView(ctx).apply {
                visibility = View.GONE
                maxFps = 30
                setCenterShortText(true)
                setShadowEnabled(true)
                setUnplayedAlpha(Const.LANDSCAPE_KARAOKE_UNPLAYED_ALPHA)
                setKaraokeSungColor(currentKaraokeSungColor)
                setPreferredWidthPx(targetWidthPx - dp(ctx, 20f))
            }
            lyricView = lv
            container.addView(
                lv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
                )
            )
        } else {
            lyricView?.setCenterShortText(true)
            lyricView?.setPreferredWidthPx(targetWidthPx - dp(ctx, 20f))
        }

        if (introCdView == null) {
            val iv = InterludeCountdownView(
                context = ctx,
                positionProvider = positionProvider,
                enforceMaxRemainingWindow = false,
                centerInView = true
            ).apply {
                visibility = View.GONE
                setAccentColor(currentKaraokeSungColor)
                setPreferredWidthPx(dp(ctx, 140f))
            }
            introCdView = iv
            container.addView(
                iv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
                )
            )
        }

        if (interludeCdView == null) {
            val mv = InterludeCountdownView(
                context = ctx,
                positionProvider = positionProvider,
                enforceMaxRemainingWindow = false,
                centerInView = true
            ).apply {
                visibility = View.GONE
                setAccentColor(currentKaraokeSungColor)
                setPreferredWidthPx(dp(ctx, 140f))
            }
            interludeCdView = mv
            container.addView(
                mv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
                )
            )
        }

        val candidateTypes = ArrayList<Int>(3)
        candidateTypes.add(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        nativeWindowTypeProvider()?.let { t ->
            if (t > 0 && !candidateTypes.contains(t)) candidateTypes.add(t)
        }

        // v1.4.5-fix16（Quick 补件审 O-M2）：窗口高度一次量定——三态子 View 高度都是字形度量定的，
        // 取三者最大＋容器上下 padding 作固定窗口高，切换时只重绘子 View、不再让 WM 重排窗口
        val fixedHeightPx = run {
            val wSpec = View.MeasureSpec.makeMeasureSpec(targetWidthPx, View.MeasureSpec.AT_MOST)
            val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            var maxH = 0
            for (v in listOf(lyricView, introCdView, interludeCdView)) {
                if (v == null) continue
                runCatching { v.measure(wSpec, hSpec) }
                if (v.measuredHeight > maxH) maxH = v.measuredHeight
            }
            (maxH + dp(ctx, 8f)).coerceAtLeast(dp(ctx, 40f))
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        var lastErr = ""
        for (winType in candidateTypes) {
            val lp = WindowManager.LayoutParams(
                targetWidthPx,
                fixedHeightPx,
                winType,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = dp(ctx, prefs.landscapeLyricOffsetXDp)
                y = dp(ctx, prefs.landscapeLyricTopMarginDp)
                title = "VivoIslandLandscapeLyric"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            val ok = runCatching {
                wm.addView(container, lp)
                true
            }.onFailure { err ->
                lastErr = "${err.javaClass.simpleName}:${err.message}"
            }.getOrDefault(false)
            if (ok) {
                overlayAttached = true
                // v1.4.5-fix16：[LAND_OVERLAY] 状态迁移日志（每次真实挂载/卸载各一行，验暂停撤窗是否生效）
                val laMsg = "[LAND_OVERLAY] state=attached"
                YLog.info(msg = laMsg)
                EventHistoryStore.recordDiagOnly(laMsg)
                currentLayoutParams = lp
                lastRenderedState = -1
                lastRenderedLineSeq = -1L
                lastRenderedText = ""
                if (!attachLoggedOnce) {
                    attachLoggedOnce = true
                    val msg = "[LANDSCAPE_OVERLAY] attached type=$winType width=${targetWidthPx}px"
                    YLog.info(msg = msg)
                    EventHistoryStore.record(msg)
                }
                return true
            }
        }
        val errMsg = "[LANDSCAPE_OVERLAY] attach_failed err=$lastErr"
        YLog.warn(msg = errMsg)
        EventHistoryStore.record(errMsg)
        return false
    }

    private fun renderContent(ctx: Context, prefs: RuntimePrefs) {
        com.vivo.islandlyric.util.PerfStats.overlayDraws.incrementAndGet()
        val dm = ctx.resources.displayMetrics
        val winWidthPx = (dm.widthPixels * 0.56f).roundToInt()
            .coerceIn(dp(ctx, 240f), dp(ctx, 440f))
        val targetWidthPx = winWidthPx - dp(ctx, 20f)
        updateWindowLayoutIfNeeded(ctx, prefs, winWidthPx)

        when {
            inIntroMode && currentIntroTargetMs > 0L -> {
                lyricView?.clearAndHide()
                interludeCdView?.stopAndHide()
                introCdView?.apply {
                    setAccentColor(currentKaraokeSungColor)
                    startCountdown(currentIntroTargetMs, isPlaybackPlaying)
                }
                lastRenderedState = 2
            }
            inInterludeMode && currentInterludeTargetMs > 0L -> {
                lyricView?.clearAndHide()
                introCdView?.stopAndHide()
                interludeCdView?.apply {
                    setAccentColor(currentKaraokeSungColor)
                    startCountdown(currentInterludeTargetMs, isPlaybackPlaying)
                }
                lastRenderedState = 1
            }
            currentLyric.isNotBlank() -> {
                introCdView?.stopAndHide()
                interludeCdView?.stopAndHide()
                val lv = lyricView ?: return
                lv.setPreferredWidthPx(targetWidthPx)
                lv.setCenterShortText(true)
                lv.setTextSizeSp(prefs.landscapeLyricTextSizeSp)
                lv.setKaraokeEnabled(prefs.karaokeEnabled)
                lv.setKaraokeSungColor(currentKaraokeSungColor)
                lv.setShadowEnabled(true)
                lv.setUnplayedAlpha(Const.LANDSCAPE_KARAOKE_UNPLAYED_ALPHA)
                if (lv.visibility != View.VISIBLE) {
                    lv.visibility = View.VISIBLE
                }

                val baseWindowMs = if (prefs.scrollAuto) currentEstimatedWindowMs else Const.SCROLL_DEFAULT_WINDOW_MS
                val speedFactor = prefs.scrollSpeedFactor
                    .coerceIn(Const.SCROLL_SPEED_FACTOR_MIN, Const.SCROLL_SPEED_FACTOR_MAX)
                val scrollDurMs = (baseWindowMs.toFloat() / speedFactor).toLong().coerceAtLeast(600L)
                val karaokeDurMs = currentKaraokeDurationMs.coerceAtLeast(1000L)

                val seqChanged = currentLineSeq > 0L && currentLineSeq != lastRenderedLineSeq
                if (seqChanged || lastRenderedState != 0 || currentLyric != lastRenderedText || !lv.hasDisplayContent()) {
                    lv.setLyric(
                        text = currentLyric,
                        scrollDurationMs = scrollDurMs,
                        karaokeDurationMs = karaokeDurMs,
                        playing = isPlaybackPlaying,
                        initialElapsedMs = computeCurrentElapsedMs(),
                        wordSpans = currentWordSpans,
                        lineSeq = currentLineSeq
                    )
                    lastRenderedText = currentLyric
                    lastRenderedLineSeq = currentLineSeq
                    lastRenderedState = 0
                } else {
                    lv.setPlaying(isPlaybackPlaying)
                }
            }
            else -> {
                detachOverlay("empty_content")
            }
        }
    }

    private fun updateWindowLayoutIfNeeded(ctx: Context, prefs: RuntimePrefs, winWidthPx: Int) {
        val lp = currentLayoutParams ?: return
        val root = overlayRoot ?: return
        val wm = windowManager ?: return
        if (!overlayAttached) return
        val desiredX = dp(ctx, prefs.landscapeLyricOffsetXDp)
        val desiredY = dp(ctx, prefs.landscapeLyricTopMarginDp)
        if (lp.x != desiredX || lp.y != desiredY || lp.width != winWidthPx) {
            lp.x = desiredX
            lp.y = desiredY
            lp.width = winWidthPx
            runCatching { wm.updateViewLayout(root, lp) }
        }
    }

    private fun detachOverlay(reason: String) {
        lyricView?.clearAndHide()
        introCdView?.stopAndHide()
        interludeCdView?.stopAndHide()
        lastRenderedState = -1
        lastRenderedLineSeq = -1L
        lastRenderedText = ""
        currentLayoutParams = null
        if (!overlayAttached) return
        val root = overlayRoot
        val wm = windowManager
        overlayAttached = false
        val ldMsg = "[LAND_OVERLAY] state=detached reason=$reason"
        YLog.info(msg = ldMsg)
        EventHistoryStore.recordDiagOnly(ldMsg)
        if (root != null && wm != null) {
            runCatching { wm.removeViewImmediate(root) }
                .onFailure { runCatching { wm.removeView(root) } }
        }
    }

    private fun resetLineClock(startNow: Boolean, isPlaying: Boolean) {
        if (!startNow) {
            currentLyricStartUptimeMs = 0L
            currentLyricPausedAccumMs = 0L
            currentLyricPauseStartUptimeMs = 0L
        } else {
            val now = SystemClock.uptimeMillis()
            currentLyricStartUptimeMs = now
            currentLyricPausedAccumMs = 0L
            currentLyricPauseStartUptimeMs = if (isPlaying) 0L else now
        }
    }

    private fun computeCurrentElapsedMs(): Long {
        if (currentLyricStartUptimeMs <= 0L) return 0L
        val now = SystemClock.uptimeMillis()
        val pendingPause = if (currentLyricPauseStartUptimeMs > 0L) {
            (now - currentLyricPauseStartUptimeMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return (now - currentLyricStartUptimeMs - currentLyricPausedAccumMs - pendingPause).coerceAtLeast(0L)
    }

    private fun dp(ctx: Context, value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            ctx.resources.displayMetrics
        ).roundToInt()
    }
}
