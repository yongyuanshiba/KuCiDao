package com.vivo.islandlyric.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.animation.DecelerateInterpolator
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextSwitcher
import android.widget.TextView
import com.highcapable.yukihookapi.hook.log.YLog
import com.vivo.islandlyric.Const
import com.vivo.islandlyric.util.EventHistoryStore
import com.vivo.islandlyric.util.HookCircuitBreaker
import com.vivo.islandlyric.util.LrcTimelineParser
import com.vivo.islandlyric.util.LogFormat
import com.vivo.islandlyric.util.RuntimePrefs
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 原子岛收起态胶囊渲染器（v1.2.8）：
 * - 左列动态算宽 + 3 个 0.1dp 几何微调滑杆（左歌名左缘间距 / 左歌名右缘内缩 / 右歌词右缘内缩），永不溢出 333px 切字；
 * - 双模板 padding 在 onMeasure 内 super 之前同步锁定 + OnLayoutChangeListener 同帧修正；右模板为 LinearLayout 时快照锁定 gravity = CENTER_VERTICAL|START，消灭 +40/-2 双态；
 * - preWriteWideConstraintsInStack 增加退场保护（双 cap 均不可见或 scale <= 0.55 禁止写回 333），彻底消灭关音乐卡一下；
 * - 非播放态 state_changed_hide 还原后节流回放最近 40 条 [HISTORY]。
 */
class CapsuleLyricRenderer(
    private val mainHandler: Handler,
    private val readPrefs: () -> RuntimePrefs,
    private val positionProvider: () -> Long,
    private val onNativeVisibilityChanged: (() -> Unit)? = null
) {
    private enum class RenderMode {
        NONE,
        NARROW_V10,
        WIDE_V11
    }

    private data class ModeMetrics(
        val mode: RenderMode,
        val unionVisualPx: Float,
        val unionVisualDp: Float,
        val capLeftRect: RectF?,
        val capRightRect: RectF?,
        val hold: Boolean,
        val scaleLX: Float,
        val scaleLY: Float,
        val scaleRX: Float,
        val scaleRY: Float
    )

    private data class SavedTextViewStyle(
        val textSizePx: Float,
        val ellipsize: TextUtils.TruncateAt?,
        val marqueeRepeatLimit: Int,
        val maxWidth: Int,
        val fadingEdgeEnabled: Boolean,
        val fadingEdgeLength: Int,
        val gravity: Int,
        val selected: Boolean
    )

    private data class SavedSwitcherStyle(
        val visibility: Int,
        val lpWidth: Int,
        val lpHeight: Int
    )

    private data class SavedWidthConstraint(
        val viewRef: WeakReference<View>,
        val lpWidth: Int
    )

    private data class SavedPadding(
        val viewRef: WeakReference<View>,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private data class SavedClipState(
        val viewRef: WeakReference<ViewGroup>,
        val clipChildren: Boolean,
        val clipToPadding: Boolean,
        val clipToOutline: Boolean
    )

    private val renderBreaker = HookCircuitBreaker("Island.CapsuleRenderer")
    private val layoutGuardBreaker = HookCircuitBreaker("Island.LayoutGuard")
    private val glowRenderer = CapsuleGlowRenderer(readPrefs = readPrefs)
    // v1.4.5-fix13 C：整岛流光管理器（两壳背景层），与 glowRenderer 在 syncGlowWithCurrentState 同点驱动
    private val islandShimmer = IslandShimmerManager()

    private var rootWindowRef: WeakReference<View>? = null
    private var leftTemplateRef: WeakReference<ViewGroup>? = null
    private var rightTemplateRef: WeakReference<ViewGroup>? = null
    private var switcherRef: WeakReference<ViewGroup>? = null
    private var capLeftRef: WeakReference<View>? = null
    private var capRightRef: WeakReference<View>? = null
    private var capExpandedRef: WeakReference<View>? = null
    private var realCapContainerRef: WeakReference<View>? = null
    private var musicWaveRef: WeakReference<View>? = null

    private var leftInfoContainerRef: WeakReference<LinearLayout>? = null
    // v1.4.5-fix20.2：注入容器宿主模板的弱引用——leftInfoContainerRef/leftTemplateRef 被解绑或换绑清空后，
    // 仍能按宿主回退找到注入容器并藏掉，堵「引用先清、还原落空」的残留竞态；只在确认容器已藏好后才清空
    private var injectedLeftHostRef: WeakReference<ViewGroup>? = null
    private var leftTitleViewRef: WeakReference<TrackMarqueeView>? = null
    private var leftArtistViewRef: WeakReference<TrackMarqueeView>? = null
    private var rightIntroCdViewRef: WeakReference<InterludeCountdownView>? = null
    private var rightInterludeCdViewRef: WeakReference<InterludeCountdownView>? = null
    private var rightLyricViewRef: WeakReference<LyricScrollView>? = null

    private var savedSwitcherStyle: SavedSwitcherStyle? = null
    private val savedTvStyles = HashMap<Int, SavedTextViewStyle>(2)
    private val savedWidthConstraints = HashMap<Int, SavedWidthConstraint>(4)
    private val savedClipStates = HashMap<Int, SavedClipState>(4)
    private var savedLeftTemplatePadding: SavedPadding? = null
    private var savedLeftTemplateGravity: Pair<WeakReference<LinearLayout>, Int>? = null
    private var savedRightTemplatePadding: SavedPadding? = null
    private var savedRightTemplateGravity: Pair<WeakReference<LinearLayout>, Int>? = null
    private var lastEdgeCheckKey: String = ""

    // v1.4.5-fix16：stable_wide 型 EDGE_CHECK 限频（500ms 至多一条，几何计算前拦截）
    private var lastEdgeCheckMs: Long = 0L
    private var preDrawLockOwnerRef: WeakReference<View>? = null
    // v1.4.5-fix9 P2：同一监听器的「重入模式」——state_changed_hide 后不注销、切模式，
    // 在重展开首帧（activeMode 仍 NONE 而 shouldShow 已真）同步直达宽态并按需取消该帧脏绘制，堵原厂波浪闪现
    private var preDrawReentryMode = false
    private var reentryCancelUsed = false
    // v1.4.5-fix12 F12-1：展开类 hide 压制的兜底在展开未收完时推迟标记；收起落地后由 onLayoutStateChanged 重新计时
    private var suppressAwaitCollapse = false
    // v1.4.5-fix12：最近一次 restoreAllToFactory（wasShowing）的时刻，供 [TAKEOVER] 诊断计接管间隔
    private var lastHideElapsedMs: Long = 0L

    @Volatile
    private var lastExpandedElapsedMs: Long = 0L

    @Volatile
    private var awaitingValidVisibleReentry: Boolean = false
    private var savedWaveVisibility: Int? = null
    private var savedWaveIdentity: Int = 0

    // v1.4.5-fix19 P0-1（Quick 定版 v2 + Gemini 审查收口）：
    // 进程级波浪自写账本（以 View 本身为键，跨 Renderer 实例共享；不做全局 setVisibility Hook，归属靠惰性校验）
    private data class WaveLedger(
        var lastSelfVal: Int,
        var selfWriteUptime: Long,
        val preSelfOrigVis: Int,
        var src: String
    )

    private var lastHideReason: String = ""
    private var waveAbsentStartElapsedMs: Long = 0L

    /** v1.4.5-fix19 P0-1：惰性校验——账本命中且当前值==lastSelfVal 为我方所写；不符说明原厂已改写，立即清账本 */
    private fun resolveValidWaveLedger(wave: View?): WaveLedger? {
        if (wave == null) return null
        val entry = waveLedgers[wave] ?: return null
        if (wave.visibility == entry.lastSelfVal) return entry
        waveLedgers.remove(wave)
        return null
    }

    private fun isWaveSelfWritten(wave: View?): Boolean = resolveValidWaveLedger(wave) != null

    /**
     * v1.4.5-fix19 P0-1：全模块唯一的波浪 visibility 写入入口。
     * - 首次自写（当前 View 无有效账本）时记录写入前的 wave.visibility 为 preSelfOrigVis，后续连续自写不覆盖；
     * - clearLedgerAfter=true 用于还原/撤销我方写入，写回后直接移除该 View 账本，不把写回值再记成新自写。
     */
    private fun writeWaveVis(
        wave: View,
        value: Int,
        src: String,
        clearLedgerAfter: Boolean = false
    ) {
        val existing = resolveValidWaveLedger(wave)
        // v1.4.5-fix19 实施审 M1（Quick）：无账本且当前值已等于目标值——并未发生我方写入，
        // 不写、不登记、不打印；否则原厂真隐藏（如已 GONE）会被误记成我方自写、门禁误放行（破坏 P0-3）
        if (!clearLedgerAfter && existing == null && wave.visibility == value) return
        // v1.4.5-fix19 实施审 M2（Quick）：账本有效且值未变——同值重复调用直接返回，
        // 不刷新账本、不打 [WAVE_WRITE]，防高频路径（接管/持续布局）重演写风暴式日志
        if (!clearLedgerAfter && existing != null && existing.lastSelfVal == value && wave.visibility == value) return
        val preOrig = existing?.preSelfOrigVis ?: wave.visibility
        val prevInternal = isInternalUpdate
        isInternalUpdate = true
        try {
            if (wave.visibility != value) {
                wave.visibility = value
            }
        } finally {
            isInternalUpdate = prevInternal
        }
        if (clearLedgerAfter) {
            waveLedgers.remove(wave)
        } else {
            waveLedgers[wave] = WaveLedger(
                lastSelfVal = value,
                selfWriteUptime = SystemClock.uptimeMillis(),
                preSelfOrigVis = preOrig,
                src = src
            )
        }
        val wMsg = "[WAVE_WRITE] src=$src val=$value"
        YLog.info(msg = wMsg)
        EventHistoryStore.recordDiagOnly(wMsg)
    }

    // v1.4.5-fix18 防抖：最近一次接管时刻与最短保持绕过标记（延迟复核的二次进入须直行）
    private var lastTakeoverElapsedMs: Long = 0L
    private var minHoldBypass: Boolean = false
    // v1.4.5-fix18 实施审 M6：防抖延迟任务单一句柄——窗口内重复触发只更新 reason、不重复 post/打日志
    private var pendingMinHoldRunnable: Runnable? = null
    private var pendingMinHoldReason: String = ""
    // v1.4.5-fix18 实施审 M7：倒计时淡入守门的监听/兜底句柄（打断时统一拆除）
    private var cdGateListener: ViewTreeObserver.OnPreDrawListener? = null
    private var cdGateFallback: Runnable? = null
    private var cdGateViewRef: WeakReference<InterludeCountdownView>? = null
    // v1.4.5-fix18 实施审确认轮：守门独立代次——绝不与 soloWaveFadeGeneration 共用：看门狗约 250ms
    // 触发 finishSoloWaveFade 会递增淡出代次，若守门绑同一计数器，cdLeft 未稳时守门被误杀、倒计时整段隐形
    private var cdGateGeneration: Int = 0

    private companion object {
        const val MIN_HOLD_AFTER_TAKEOVER_MS: Long = 300L
        // v1.4.5-fix19 P0-1：进程级弱引用账本，跨 Renderer 实例共享（UI 线程单线程读写）
        val waveLedgers = java.util.WeakHashMap<View, WaveLedger>()
    }

    /** v1.4.5-fix18 实施审 M6：取消挂起的防抖延迟还原（接管/真还原/切歌/暂停等路径共用此点收口） */
    private fun cancelPendingMinHold() {
        pendingMinHoldRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingMinHoldRunnable = null
    }

    // v1.4.5-fix10 F10-1：state_changed_hide 且必将快速恢复时，原厂波浪以 INVISIBLE 还原（源头压制），
    // 防守卫放行前系统先画 2~3 帧原厂波浪；savedWaveVisibility 仍记原厂本意，退出路径照常完整还原
    private var waveRestoreSuppressed: Boolean = false

    // v1.4.5-fix16（Quick 二轮裁决）：压制中波浪被系统 detach 时保留其引用——快照不清、还原不放行，
    // 兜底与换实例路径靠它把旧实例捞回来，防「只跳过还原却清快照」导致波浪永久压死
    private var suppressedWaveRef: WeakReference<View>? = null
    // v1.4.5-fix13 A（Quick 裁决）：压制类型必须落盘——fix12 只在武装日志里带 kind，释放点无从按类型设门，
    // 暂停回调/布局回调按「当前阻断原因」判断，展开变形中途阻断原因一变就误放。本字段武装时写、释放时清。
    private var waveSuppressKind: String? = null
    private var narrowSuppressReleasePending: Boolean = false
    private var narrowSuppressReleaseRunnable: Runnable? = null
    private var narrowSuppressReleaseAttempts: Int = 0
    private var waveSuppressFallbackRunnable: Runnable? = null
    private var waveSuppressFallbackChecks: Int = 0
    // v1.4.5-fix20.3 C2：暂停起始时刻与声波延迟释放任务（同时只保留一个，暂停/收起落地时排期、到点复核）
    private var pauseStartUptimeMs: Long = 0L
    private var pauseWaveReleaseRunnable: Runnable? = null
    private var pauseWaveReleaseSrc: String? = null
    // v1.4.5-fix12（Quick 审查 S1）：展开类兜底推迟的有界重投计数（每 5s 一次、最多 3 次，防布局回调缺席时压制悬置）
    private var waveSuppressDeferredChecks: Int = 0
    // v1.4.5-fix10 F10-1B：shouldShow 最近一次判 false 的守卫名与重入日志去重键（仅诊断）
    private var lastShouldShowBlocker: String = ""
    private var lastReentryLogKey: String = ""

    private var lastLoggedMode: RenderMode? = null
    private var lastLoggedHold: Boolean? = null

    private var wideTreeDumped: Boolean = false
    private var wideTreeFallbackRunnable: Runnable? = null
    private var wideTreeRetryScheduled: Boolean = false
    private var positiveMeasurePollRunnable: Runnable? = null

    private val tempVisualRect = RectF()

    @Volatile
    private var isInternalUpdate: Boolean = false

    @Volatile
    private var activeMode: RenderMode = RenderMode.NONE

    @Volatile
    private var isWideLatched: Boolean = false

    /** 仅在真会话结束 / 关总开关 / 正测量确认窄态时清 false；窗口 detach 或展开期间保持 */
    @Volatile
    private var lastConfirmedWide: Boolean = false

    @Volatile
    private var sessionActive: Boolean = false

    @Volatile
    private var isPlaybackPlaying: Boolean = false

    @Volatile
    private var currentLyric: String = ""
    @Volatile
    private var currentNextLyric: String = ""
    private var lastRenderedNextLyric: String = ""
    private var lastRenderedWordSpans: List<LrcTimelineParser.WordSpan> = emptyList()
    private var currentWordSpans: List<LrcTimelineParser.WordSpan> = emptyList()
    private var currentLyricLineSeq: Long = 0L
    private var lastRenderedLineSeq: Long = -1L
    private var currentLyricStartUptimeMs: Long = 0L
    private var currentLyricPausedAccumMs: Long = 0L
    private var currentLyricPauseStartUptimeMs: Long = 0L
    private var wideClipLoggedOnce: Boolean = false

    @Volatile
    private var currentEstimatedWindowMs: Long = Const.SCROLL_DEFAULT_WINDOW_MS

    @Volatile
    private var currentKaraokeDurationMs: Long = Const.SCROLL_DEFAULT_WINDOW_MS

    @Volatile
    private var inInterludeMode: Boolean = false

    @Volatile
    private var currentInterludeTargetMs: Long = -1L

    // v1.4.5-fix10 F10-3：间奏段界（起点由 Source 按静默模型给出；未知 = -1 时不进波浪相位、直接数字，准优先于有）
    private var currentInterludeStartMs: Long = -1L
    private var interludeSkipWaveLead: Boolean = false
    private var interludeWaveEndRunnable: Runnable? = null

    @Volatile
    private var suppressPreWriteUntilPlay: Boolean = false

    @Volatile
    private var inIntroMode: Boolean = false

    @Volatile
    private var currentIntroLearnedMs: Long = -1L
    // v1.4.5-fix14（Quick 裁决＋实施审 L-M1/L-M2）：收起落地→接管耗时测量。每个 shouldShow 出口都报 blocker 名；
    // 状态离开 expand 类（root_height/cap_expanded）的那一刻盖 landStampMs（＝空胶囊开始露出的时刻），
    // 首次放行盖 passStampMs；进入 expand 类时清两戳，防上一轮未消费的时间戳串到下一轮 TAKEOVER 造假值
    private var landBlockerState: String = ""
    private var landStampMs: Long = 0L
    private var passStampMs: Long = 0L
    // v1.4.5-fix16（Quick 二轮裁决）：本轮首次放行戳——不随后续 ->pass 跳变刷新，量「第一次能接管却没接上」的真实空窗
    private var firstPassStampMs: Long = 0L
    // v1.4.5-fix15（Quick 裁决）：本轮 blocker 迁移序列，随 TAKEOVER 一并打出，一眼看清中间来回切了几次
    private val landBlockerSeq = StringBuilder()

    private fun isExpandBlockerName(name: String): Boolean =
        name == "root_height" || name == "cap_expanded"

    private fun noteLandBlocker(name: String) {
        val wasExpand = isExpandBlockerName(landBlockerState)
        val isExpand = isExpandBlockerName(name)
        if (isExpand && !wasExpand) {
            landStampMs = 0L
            passStampMs = 0L
            firstPassStampMs = 0L
        }
        if (!isExpand && wasExpand && landStampMs == 0L) {
            landStampMs = SystemClock.elapsedRealtime()
        }
        if (landBlockerState != name) {
            if (landBlockerSeq.isNotEmpty()) landBlockerSeq.append('>')
            if (landBlockerSeq.length < 160) landBlockerSeq.append(name)
            // v1.4.5-fix15（Gemini 元凶 A）：passToTakeoverMs 的 116–238ms 空窗来自重入轮询的 120ms 间隔——
            // 条件一放行就当帧派发一次接管对账（post 不递归），轮询保留作兜底；纯黑空胶囊压到 1 帧内
            if (name == "pass" && activeMode == RenderMode.NONE && sessionActive) {
                com.vivo.islandlyric.util.PerfStats.reconcilePass.incrementAndGet()
                mainHandler.post {
                    renderBreaker.execute {
                        if (activeMode == RenderMode.NONE && sessionActive) {
                            // v1.4.5-fix16（Quick 二轮裁决）：[LAND_RECONCILE]——第一次放行派发的对账为何没接管，
                            // 就看这一行的 shouldShow/mode/blocker（225ms 空窗定因专用）
                            val lrMsg = "[LAND_RECONCILE] shouldShow=${shouldShowOnCollapsedCapsule(readPrefs())} " +
                                "mode=$activeMode blocker=$landBlockerState firstPassAgeMs=" +
                                "${if (firstPassStampMs > 0L) SystemClock.elapsedRealtime() - firstPassStampMs else -1L}"
                            YLog.info(msg = lrMsg)
                            EventHistoryStore.recordDiagOnly(lrMsg)
                            reconcileState(forceText = true)
                        }
                    }
                }
            }
            // v1.4.5-fix15（Gemini 元凶 C 语义）：pass 戳在每次 ->pass 跳变时刷新，量的是最后一程黑窗；
            // 进轮清理仍按 Quick 口径在 restoreAllToFactory 清空
            if (name == "pass") {
                passStampMs = SystemClock.elapsedRealtime()
                if (firstPassStampMs == 0L) {
                    firstPassStampMs = passStampMs
                }
            }
            // v1.4.5-fix16（Quick 二轮裁决）：[COLLAPSE_LAND] 改全量迁移——不再只打 expand/pass 相关跳变
            val msg = "[COLLAPSE_LAND] blocker=${landBlockerState.ifEmpty { "pass" }}->$name"
            YLog.info(msg = msg)
            EventHistoryStore.recordDiagOnly(msg)
            // v1.4.5-fix17（Quick fix17 裁决）：[ROOT_GROW]——root_height 触发瞬间的根/双壳高度与大卡可见性，
            // 与 [TAKEOVER_WRITES] 对表定性：展开动画过渡帧（高会回落）还是真展开（大卡可见）
            if (name == "root_height") {
                val rH = rootWindowRef?.get()?.height ?: -1
                val lH = capLeftRef?.get()?.height ?: -1
                val rCapH = capRightRef?.get()?.height ?: -1
                val exp = capExpandedRef?.get()
                val expVis = exp != null && exp.visibility == View.VISIBLE && exp.height > 0
                val gMsg = "[ROOT_GROW] rootH=$rH capLH=$lH capRH=$rCapH expandedVisible=$expVis"
                YLog.info(msg = gMsg)
                EventHistoryStore.recordDiagOnly(gMsg)
            }
            landBlockerState = name
        }
    }

    private data class LandStamps(
        val landMs: Long,
        val passMs: Long,
        val firstPassMs: Long,
        val seq: String
    )

    /** TAKEOVER 侧消费时间戳：land/pass/firstPass 耗时与 blocker 序列，无戳为 -1，消费后全清 */
    private fun consumeLandStamps(): LandStamps {
        val now = SystemClock.elapsedRealtime()
        val land = if (landStampMs > 0L) now - landStampMs else -1L
        val pass = if (passStampMs > 0L) now - passStampMs else -1L
        val firstPass = if (firstPassStampMs > 0L) now - firstPassStampMs else -1L
        val seq = landBlockerSeq.toString()
        landStampMs = 0L
        passStampMs = 0L
        firstPassStampMs = 0L
        landBlockerSeq.setLength(0)
        return LandStamps(land, pass, firstPass, seq)
    }

    /** v1.4.5-fix15（Quick 裁决）：一轮以 restoreAllToFactory 为界——进还原先把落地测量整轮清零，杜绝跨轮旧值 */
    private fun resetLandRound() {
        landStampMs = 0L
        passStampMs = 0L
        firstPassStampMs = 0L
        // v1.4.5-fix16（Quick 二轮裁决）：不清 landBlockerState——清了会让紧接着的判定把同一迁移
        // 再打一条 pass->xxx，日志看着像两条检测链打架（Gemini 误报来源）；只清时间戳与序列
        landBlockerSeq.setLength(0)
    }
    private var introSkipWaveLead: Boolean = false
    private var introForceWaveOnly: Boolean = false
    private var soloWaveTransformApplied: Boolean = false
    private var soloWaveTransformIdentity: Int = 0
    private var appliedSoloWaveOffsetX: Float = 0f
    private var savedWavePivotWasSet: Boolean = false
    private var savedWavePivotX: Float = 0f
    private var savedWavePivotY: Float = 0f
    private var soloWaveFadeActive: Boolean = false
    private var soloWaveFadeGeneration: Int = 0
    private var soloWaveFadeViewRef: WeakReference<View>? = null
    private var soloWaveFadeWatchdogRunnable: Runnable? = null
    private var waveHiddenByOurFade: Boolean = false
    private var pendingWaveReveal: Boolean = false
    private var pendingWaveRevealGeneration: Int = 0
    private var pendingWaveRevealTimeoutRunnable: Runnable? = null
    private var soloWaveScaleInAnimator: ValueAnimator? = null
    private var soloWaveScaleInGeneration: Int = 0
    private var soloWaveScaleInWatchdogRunnable: Runnable? = null

    @Volatile
    private var currentKaraokeSungColor: Int = Const.KARAOKE_AMBER_FALLBACK
    private var currentGlowPalette: IntArray? = null

    @Volatile
    private var cachedTrackTitle: String = ""

    @Volatile
    private var cachedTrackArtist: String = ""

    @Volatile
    private var musicWaveAttached: Boolean = false

    @Volatile
    private var lastRenderedNarrowText: String = ""

    @Volatile
    private var lastRenderedRightText: String = ""

    /** 0 = 歌词, 1 = 间奏倒计时(原厂波浪+数字), 2 = 前奏纯倒计时, 3 = 前奏波浪段, 4 = 间奏波浪段(仅波浪、无数字) */
    @Volatile
    private var lastRenderedRightState: Int = -1

    private var lastReassertElapsed: Long = 0L
    private var lastLeftReassertUptimeMs: Long = 0L
    private var pendingLeftReassertRunnable: Runnable? = null
    private var marqueeKickRunnable: Runnable? = null
    private var holdSettleRunnable: Runnable? = null
    private var introWaveLeadEndRunnable: Runnable? = null
    private var reentryVisibilityPollRunnable: Runnable? = null
    private var reentryVisibilityPollAttempts: Int = 0

    // v1.3.18：切歌 600ms 暂停宽限 + 多任务 350ms 隐现沉降 + 可见性世代计数 + 主线程减负缓存
    private val PAUSE_TEARDOWN_GRACE_MS = 1000L
    private val HIDE_SETTLE_GRACE_MS = 350L
    @Volatile
    private var pauseGraceActive: Boolean = false
    private var pendingPauseRestoreRunnable: Runnable? = null
    @Volatile
    private var hideSettleActive: Boolean = false
    private var pendingHideSettleRunnable: Runnable? = null
    @Volatile
    private var wideGeneration: Long = 0L
    private val wideEntrySampleRunnables = ArrayList<Runnable>(2)
    private var lastAppliedWidePrefs: RuntimePrefs? = null

    /** v1.3.20：多任务整岛离屏隐藏时的轻量快恢复快照（仅限同模板、同曲目、同配置、播放中） */
    private data class WideFastResumeState(
        val leftTemplateRef: WeakReference<ViewGroup>,
        val rightTemplateRef: WeakReference<ViewGroup>,
        val trackKey: String,
        val prefs: RuntimePrefs,
        val rightState: Int
    )

    private var fastResumeState: WideFastResumeState? = null

    private fun currentTrackKey(): String = "$cachedTrackTitle|$cachedTrackArtist"

    private fun clearFastResumeState() {
        fastResumeState = null
    }

    private fun isLeftShellVisible(): Boolean {
        val leftTemplate = leftTemplateRef?.get() ?: return false
        return isVisibleUpToBoundary(leftTemplate, capLeftRef?.get())
    }
    private var lastStableLayoutCheckUptimeMs: Long = 0L
    private var lastSyncWideBindUptimeMs: Long = 0L
    private var albumIconRef: WeakReference<View>? = null
    // v1.3.19：120Hz OnPreDraw 零分配坐标复用缓冲，避免每帧创建 5 个 RectF 触发 GC 微卡顿
    private val lockCapLeftRect = RectF()
    private val lockCapRightRect = RectF()
    private val lockAlbumRect = RectF()
    private val lockLeftInfoRect = RectF()
    private val lockRightRect = RectF()
    private val lockWaveRect = RectF()

    private fun isEffectivelyPlaying(): Boolean = isPlaybackPlaying || pauseGraceActive

    private fun cancelPendingPauseRestore() {
        pauseGraceActive = false
        pendingPauseRestoreRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingPauseRestoreRunnable = null
    }

    private fun cancelPendingHideSettle() {
        hideSettleActive = false
        pendingHideSettleRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingHideSettleRunnable = null
    }

    private fun cancelWideEntryWidthSamples() {
        if (wideEntrySampleRunnables.isEmpty()) return
        for (r in wideEntrySampleRunnables) {
            mainHandler.removeCallbacks(r)
        }
        wideEntrySampleRunnables.clear()
    }

    private val templateLayoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (isInternalUpdate) return@OnLayoutChangeListener
            layoutGuardBreaker.execute {
                onLayoutStateChanged()
            }
        }

    private val rootLayoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (isInternalUpdate) return@OnLayoutChangeListener
            layoutGuardBreaker.execute {
                onLayoutStateChanged()
            }
        }

    /**
     * v1.2.9 S1/S2/S5 宽态轻量 OnPreDrawListener 视觉坐标闭环锁：
     * 在原厂所有 onMeasure/onLayout/setPadding 完成后、绘制前同帧用 translationX 拉齐三者视觉左缘；
     * 仅在稳定宽态（scale >= 0.92）且偏差 >= 0.5px 时微调，零二次 layout、零多余重绘。
     */
    private val preDrawVisualLockListener = ViewTreeObserver.OnPreDrawListener {
        com.vivo.islandlyric.util.PerfStats.predrawCallbacks.incrementAndGet()
        if (preDrawReentryMode) {
            handlePreDrawReentry()
        } else {
            if (activeMode == RenderMode.WIDE_V11) {
                layoutGuardBreaker.execute {
                    applyPreDrawVisualLock()
                }
            }
            true
        }
    }

    /**
     * v1.4.5-fix9 P2 重入拦截（Quick 四点版＋Gemini 风险 1 顺序）：
     * - 未回桌面/不该显示（shouldShow=false）时只放行、不注销、不走宽态锁逻辑（模式分支首行隔离）；
     * - 停播/会话结束/不再等待重入时立即注销退出，防常驻空转；
     * - 真正接管帧（shouldShow && activeMode==NONE && 可快速恢复）：先注销自己＋熔断置位，再同步 fastResume；
     *   仅当恢复触发了重新测量（isLayoutRequested）时才 return false 取消这一帧；每 hide→show 周期最多取消 1 帧。
     */
    /** v1.4.5-fix10 F10-1B：重入窗口逐帧证据（状态元组变化才打一行，免 120Hz 刷盘；Quick 裁决的 B 项日志） */
    private fun logReentryPreDraw(canceled: Boolean?, shouldShow: Boolean?) {
        val capExp = capExpandedRef?.get()
        val root = rootWindowRef?.get()
        val wave = musicWaveRef?.get()
        // v1.4.5-fix10（Quick S7）：去重 key 补 root 高与 scaleY 0.05 分桶——收起动画主要就表现在这两处，不进 key 等于没留证据
        val scaleYBucket = capExp?.scaleY?.let { (it * 20).toInt() }
        val key = "$canceled|$shouldShow|$lastShouldShowBlocker|${capExp?.visibility}|${capExp?.height}|${root?.height}|$scaleYBucket|${wave?.visibility}|$waveRestoreSuppressed|$activeMode"
        if (key == lastReentryLogKey) return
        lastReentryLogKey = key
        val msg = "[REENTRY_PREDRAW] canceled=$canceled shouldShow=$shouldShow blocker=$lastShouldShowBlocker " +
            "capExpVis=${capExp?.visibility} capExpAlpha=${capExp?.alpha} capExpH=${capExp?.height} capExpScaleY=${capExp?.scaleY} capExpTransY=${capExp?.translationY} " +
            "rootW=${root?.width} rootH=${root?.height} waveVis=${wave?.visibility} waveAlpha=${wave?.alpha} suppressed=$waveRestoreSuppressed activeMode=$activeMode"
        YLog.info(msg = msg)
        EventHistoryStore.recordDiagOnly(msg)
    }

    private fun handlePreDrawReentry(): Boolean {
        com.vivo.islandlyric.util.PerfStats.reconcilePredraw.incrementAndGet()
        if (!awaitingValidVisibleReentry || !sessionActive || !isEffectivelyPlaying()) {
            logReentryPreDraw(canceled = null, shouldShow = null)
            preDrawReentryMode = false
            unregisterPreDrawVisualLock()
            return true
        }
        val prefs = readPrefs()
        if (!shouldShowOnCollapsedCapsule(prefs)) {
            logReentryPreDraw(canceled = false, shouldShow = false)
            return true
        }
        if (activeMode != RenderMode.NONE) {
            // 正常路径已接管：只退模式、不注销、不在 reentry 语境误跑宽态锁平移（Gemini 护栏 二.3，真机经验优先于 Quick M-5 推断）
            logReentryPreDraw(canceled = false, shouldShow = true)
            preDrawReentryMode = false
            return true
        }
        if (!canFastResumeWide(prefs)) {
            // 同上：只退模式保留监听器，注销仅限停播/会话结束与接管分支（Gemini 护栏 二.3）
            logReentryPreDraw(canceled = false, shouldShow = true)
            preDrawReentryMode = false
            return true
        }
        preDrawReentryMode = false
        unregisterPreDrawVisualLock()
        if (reentryCancelUsed) {
            logReentryPreDraw(canceled = false, shouldShow = true)
            return true
        }
        reentryCancelUsed = true
        fastResumeWideMode(prefs)
        val root = rootWindowRef?.get()
        val canceledFrame = root != null && root.isLayoutRequested
        logReentryPreDraw(canceled = canceledFrame, shouldShow = true)
        return if (canceledFrame) false else true
    }

    /** vc55 横屏判定（与悬浮窗 isCurrentlyLandscape 同口径：配置方向或宽>高）；无视图可读时返回 false（尚无接管可闸） */
    private fun isLandscapeNow(): Boolean {
        val view = leftTemplateRef?.get() ?: rootWindowRef?.get() ?: return false
        val cfg = view.resources.configuration
        if (cfg.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) return true
        val dm = view.resources.displayMetrics
        return dm.widthPixels > dm.heightPixels
    }

    /** vc55 横屏总开关：横屏且模式非「歌词岛」时，渲染器不接管、不绘制（进度跟踪链不在本类状态机内、不受影响）。
     * 含 masterEnabled（门检 M5）：总开关关闭时闸门视为解除，reconcileState 顶部的还原检查才会执行 */
    private fun landscapeGateActive(prefs: RuntimePrefs): Boolean {
        return prefs.masterEnabled && prefs.landscapeMode != Const.LANDSCAPE_MODE_LYRIC && isLandscapeNow()
    }

    // vc55 摘岛：被总开关藏起的原厂岛根视图记账，闸门解除（回竖屏/改模式/关总开关）后按原值还原
    private var landscapeGateHidNative: Boolean = false
    private var landscapeGateSavedVisibility: Int = View.VISIBLE
    // vc55（门检 L3）：记住「被我们藏的那个 view 本人」——SystemUI 重建岛窗口后 rootWindowRef 会指向新 view，还原只动旧 view、不误改新 view
    private var landscapeGateHiddenViewRef: java.lang.ref.WeakReference<View>? = null

    // vc56 D：摘岛即时藏回——闸门藏下期间给 root 挂 OnPreDrawListener，系统把 root 改回 VISIBLE 时
    // 在绘制前藏回并 return false 取消该帧（治横屏滑状态栏闪一下）；只在闸门期存在，竖屏零开销
    private var gatePreDrawListener: android.view.ViewTreeObserver.OnPreDrawListener? = null
    private var gatePreDrawRootRef: java.lang.ref.WeakReference<View>? = null
    // vc56（Hark diff 建议）：记账挂上的那个 VTO 本人——root 重挂新窗口时 VTO 会换，凭它判迁移与摘除
    private var gatePreDrawVtoRef: java.lang.ref.WeakReference<android.view.ViewTreeObserver>? = null
    private var gateRehideCount: Int = 0
    private var gateRehideRapidCount: Int = 0
    private var gateRehideLastMs: Long = 0L
    private var gateRehideSurrendered: Boolean = false

    private fun attachGatePreDrawListener(root: View) {
        val vto = runCatching { root.viewTreeObserver }.getOrNull()
        if (gatePreDrawListener != null && gatePreDrawRootRef?.get() === root &&
            (vto == null || gatePreDrawVtoRef?.get() === vto)
        ) {
            return // 同 root 同 VTO 已挂，幂等返回
        }
        detachGatePreDrawListener()
        // 挂不上（VTO 取不到/已死）就不记账，下次 hide 路径再试（Hark：防记了账却没守护、幂等检查一直早退）
        if (vto == null || !vto.isAlive) return
        val listener = android.view.ViewTreeObserver.OnPreDrawListener {
            if (!landscapeGateHidNative) {
                // 闸门已解除但 listener 残留：自摘除，不做其他动作
                detachGatePreDrawListener()
                true
            } else {
                val r = gatePreDrawRootRef?.get()
                if (r != null && r.visibility == View.VISIBLE && !gateRehideSurrendered) {
                    r.visibility = View.INVISIBLE
                    gateRehideCount++
                    val nowMs = SystemClock.uptimeMillis()
                    gateRehideRapidCount = if (nowMs - gateRehideLastMs < 1000L) gateRehideRapidCount + 1 else 1
                    gateRehideLastMs = nowMs
                    if (gateRehideCount == 1) {
                        val gMsg = "[LANDSCAPE_GATE] rehide"
                        YLog.info(msg = gMsg)
                        EventHistoryStore.recordDiagOnly(gMsg)
                    }
                    if (gateRehideRapidCount > 3) {
                        // 防乒乓（Hark）：系统持续改回可见时停止对抗，避免我方引出窗外帧与日志刷屏
                        gateRehideSurrendered = true
                        val gMsg = "[LANDSCAPE_GATE] rehide_surrender count=$gateRehideCount"
                        YLog.info(msg = gMsg)
                        EventHistoryStore.recordDiagOnly(gMsg)
                    }
                    false
                } else {
                    true
                }
            }
        }
        // 先挂成功再记账（记账含 VTO 本人），挂失败保持无记账
        val added = runCatching { vto.addOnPreDrawListener(listener); true }.getOrDefault(false)
        if (!added) return
        gatePreDrawListener = listener
        gatePreDrawRootRef = java.lang.ref.WeakReference(root)
        gatePreDrawVtoRef = java.lang.ref.WeakReference(vto)
    }

    private fun detachGatePreDrawListener() {
        val listener = gatePreDrawListener ?: return
        gatePreDrawListener = null
        val root = gatePreDrawRootRef?.get()
        gatePreDrawRootRef = null
        val recordedVto = gatePreDrawVtoRef?.get()
        gatePreDrawVtoRef = null
        // 优先从记账的 VTO 上摘（root 现任 VTO 可能已换）；记账 VTO 已死或不同，再试现任 VTO
        runCatching { if (recordedVto != null && recordedVto.isAlive) recordedVto.removeOnPreDrawListener(listener) }
        if (root != null) {
            runCatching {
                val vto = root.viewTreeObserver
                if (vto.isAlive && vto !== recordedVto) vto.removeOnPreDrawListener(listener)
            }
        }
    }

    /** vc55 摘岛（老大口径：横屏时连原厂岛一起藏）：仅当根视图当前 VISIBLE 时才藏并记原值（门检 M5——
     * 系统自己设的 INVISIBLE 不许替它改回 VISIBLE）；已由我们藏起时不重复记账 */
    private fun hideNativeIslandForLandscapeGate() {
        val root = rootWindowRef?.get() ?: return
        if (landscapeGateHidNative) {
            if (landscapeGateHiddenViewRef?.get() === root) {
                // 已记账且是同一个 view：系统若又把它改回 VISIBLE，再藏回去（原值记账不变）
                if (root.visibility == View.VISIBLE) root.visibility = View.INVISIBLE
                // vc56 D：即时藏回 listener 幂等补挂（防挂失败/被摘后无守护）
                attachGatePreDrawListener(root)
                return
            }
            // vc55（确认轮 R1，Hark 口径）：岛窗口已重建、root 是新 view——先走一遍 unhide 把旧 view 还原并清记账，
            // 然后对新 view 走首次藏流程；否则新 view 被藏却没记账、还原只动旧 view，新 view 永远回不来
            // vc56 D：unhide 内含 listener 摘除，R1 路径的旧 view listener 随此摘净（Quick 补点）
            unhideNativeIslandAfterLandscapeGate()
        }
        if (root.visibility != View.VISIBLE) return
        landscapeGateSavedVisibility = root.visibility
        root.visibility = View.INVISIBLE
        landscapeGateHidNative = true
        landscapeGateHiddenViewRef = java.lang.ref.WeakReference(root)
        // vc56 D：藏下即挂即时藏回 listener 并重置计数，补闸门日志
        gateRehideCount = 0
        gateRehideRapidCount = 0
        gateRehideLastMs = 0L
        gateRehideSurrendered = false
        attachGatePreDrawListener(root)
        val gMsg = "[LANDSCAPE_GATE] hide rootVis=$landscapeGateSavedVisibility"
        YLog.info(msg = gMsg)
        EventHistoryStore.recordDiagOnly(gMsg)
    }

    /** vc55：闸门解除后把「被我们藏的那个 view」还原为藏它之前的值；view 已被回收/重建则只清记账 */
    private fun unhideNativeIslandAfterLandscapeGate() {
        if (!landscapeGateHidNative) return
        // vc56 D（Hark 顺序）：先置标记 false（残留回调只会自摘除）→摘 listener→再还原可见性
        landscapeGateHidNative = false
        detachGatePreDrawListener()
        val hidden = landscapeGateHiddenViewRef?.get()
        landscapeGateHiddenViewRef = null
        if (hidden != null && hidden.visibility == View.INVISIBLE) {
            hidden.visibility = landscapeGateSavedVisibility
        }
        val gMsg = "[LANDSCAPE_GATE] unhide restored=$landscapeGateSavedVisibility rehides=$gateRehideCount"
        YLog.info(msg = gMsg)
        EventHistoryStore.recordDiagOnly(gMsg)
        gateRehideCount = 0
        // vc56 C：闸门期被延期的波浪兜底在解除时立即重跑
        rerunWaveFallbackIfGateDeferred()
    }

    // vc55（门检 L7）：旋转动画期间配置变化会连发，延迟 150ms 合并成一次 reconcile，防反复藏/还原/接管
    private val landscapeReconcileRunnable = Runnable {
        runCatching { reconcileState(forceText = false) }
    }

    /** vc55：配置变化（旋转）时主动 reconcile 一次——回竖屏立即解除横屏闸门/还原摘岛藏起的原厂岛，不等下一次岛状态变化 */
    fun onConfigurationChanged() {
        mainHandler.removeCallbacks(landscapeReconcileRunnable)
        mainHandler.postDelayed(landscapeReconcileRunnable, 150L)
    }

    fun isNativeIslandShowing(): Boolean {
        if (activeMode == RenderMode.NONE) return false
        val lt = leftTemplateRef?.get() ?: return false
        return lt.isAttachedToWindow && lt.visibility == View.VISIBLE
    }

    fun nativeWindowType(): Int? {
        val lp = rootWindowRef?.get()?.layoutParams as? android.view.WindowManager.LayoutParams
        return lp?.type
    }

    fun queryIntroVisualState(): Pair<Boolean, String> {
        val cdView = rightIntroCdViewRef?.get()
        val cdVisible = cdView != null && cdView.visibility == View.VISIBLE
        val rightMode = when {
            activeMode != RenderMode.WIDE_V11 -> activeMode.name
            inIntroMode -> if (currentIntroLearnedMs <= 0L || isInIntroWaveLeadPhase()) "INTRO_WAVE" else "INTRO_CD"
            inInterludeMode -> if (isInInterludeWavePhase()) "INTERLUDE_WAVE" else "INTERLUDE"
            currentLyric.isNotBlank() -> "LYRIC"
            else -> "EMPTY"
        }
        return cdVisible to rightMode
    }

    fun resolveForcedWideMeasureSpec(targetView: View): Int? {
        val prefs = readPrefs()
        val leftTemplate = leftTemplateRef?.get()
        val rightTemplate = resolveWaveParent()
        val leftFrame = leftTemplate?.parent as? View
        val rightFrame = rightTemplate?.parent as? View
        val capLeft = capLeftRef?.get()
        val capRight = capRightRef?.get()

        // v1.2.8：在 super.onMeasure 之前同步锁定左右双模板 padding 与右模板 gravity，消灭头像跳动与右侧 +40/-2 双态
        if (activeMode == RenderMode.WIDE_V11 && prefs.masterEnabled) {
            if (leftTemplate != null && (targetView === leftTemplate || targetView === leftFrame || targetView === capLeft)) {
                enforceLeftTemplatePaddingSync(leftTemplate, prefs)
            }
            if (rightTemplate != null && (targetView === rightTemplate || targetView === rightFrame || targetView === capRight)) {
                enforceRightTemplatePaddingSync(rightTemplate, prefs)
            }
        }

        if (!shouldForceWideMeasure(prefs)) return null

        val rightTargetPx = computeRightTemplateWidthPx(targetView.context, prefs)
        val leftTargetPx = Const.WIDE_TEMPLATE_EXPLICIT_WIDTH_PX

        val desiredPx = when {
            targetView === leftTemplate || (leftFrame != null && leftFrame !== capLeft && targetView === leftFrame) -> {
                if (leftTemplate != null) enforceLeftTemplatePaddingSync(leftTemplate, prefs)
                leftTargetPx
            }
            targetView === rightTemplate || (rightFrame != null && rightFrame !== capRight && targetView === rightFrame) -> {
                if (rightTemplate != null) enforceRightTemplatePaddingSync(rightTemplate, prefs)
                rightTargetPx
            }
            // v1.3.14：不再对共用外层壳 capLeft/capRight 强灌 EXACTLY 333px，仅定宽音乐自有模板与直接 FrameLayout，
            // 防止充电岛等非音乐卡片复用外层壳入场首帧时被强制测成全宽黑条。
            else -> return null
        }

        return View.MeasureSpec.makeMeasureSpec(desiredPx, View.MeasureSpec.EXACTLY)
    }

    private fun isCollapsingFromExpanded(): Boolean {
        val ts = lastExpandedElapsedMs
        return ts > 0L && (SystemClock.elapsedRealtime() - ts) <= Const.EXPANDED_COLLAPSE_WINDOW_MS
    }

    private fun computeCurrentCapsMinScale(root: View): Float {
        val capLeft = capLeftRef?.get()
        val capRight = capRightRef?.get()
        val leftVisible = capLeft != null && capLeft.isAttachedToWindow && capLeft.visibility == View.VISIBLE
        val rightVisible = capRight != null && capRight.isAttachedToWindow && capRight.visibility == View.VISIBLE
        if (!leftVisible || !rightVisible) return 0f
        val sxL = effectiveScaleX(capLeft, root)
        val syL = effectiveScaleY(capLeft, root)
        val sxR = effectiveScaleX(capRight, root)
        val syR = effectiveScaleY(capRight, root)
        return min(min(sxL, syL), min(sxR, syR))
    }

    private fun shouldForceWideMeasure(prefs: RuntimePrefs): Boolean {
        // v1.3.18：播放中或 600ms 切歌宽限期内允许测量期强制定宽；非展开卡收起且出现退场缩小时立即放手
        if (!sessionActive || !prefs.masterEnabled || suppressPreWriteUntilPlay || !isEffectivelyPlaying()) return false
        if (activeMode != RenderMode.WIDE_V11 && !lastConfirmedWide) return false

        val root = rootWindowRef?.get() ?: leftTemplateRef?.get()?.rootView
        if (root != null && root.height > Const.ISLAND_EXPANDED_HEIGHT_THRESHOLD_PX) {
            lastExpandedElapsedMs = SystemClock.elapsedRealtime()
            return false
        }
        val capExpanded = capExpandedRef?.get()
        if (capExpanded != null && capExpanded.visibility == View.VISIBLE && capExpanded.height > 0) {
            lastExpandedElapsedMs = SystemClock.elapsedRealtime()
            return false
        }
        // v1.3.15：胶囊内音乐链路可见性检查（leftTemplate / waveParent / wave 及至 capLeft/capRight 祖先全部 attached 且 VISIBLE），
        // 既避免外层窗口动画期窗口级 isShown 误拦首帧定宽，又在充电占岛置隐 wave 时立刻放手。
        val capLeft = capLeftRef?.get()
        val capRight = capRightRef?.get()
        val leftTemplate = leftTemplateRef?.get() ?: return false
        if (!isVisibleUpToBoundary(leftTemplate, capLeft)) return false

        val wave = findMusicWaveInSameCapsule(leftTemplate)
            ?: musicWaveRef?.get()?.takeIf { isSameCapsule(leftTemplate, it) }
            ?: return false
        if (!wave.isAttachedToWindow) return false
        val waveParent = wave.parent as? View ?: return false
        if (!isVisibleUpToBoundary(waveParent, capRight)) return false

        return if (activeMode == RenderMode.WIDE_V11 && savedWaveVisibility != null) {
            true
        } else {
            wave.visibility == View.VISIBLE
        }
    }

    fun bindLeftTemplate(template: ViewGroup) {
        val confirmedMusicWave = findMusicWaveInSameCapsule(template)
        // v1.3.16：以入参新模板与同胶囊 confirmedWave 直接校验并同步预写 333px，不再依赖尚未更新的旧 leftTemplateRef
        if (confirmedMusicWave != null) {
            preApplyWideLpIfEligible(template, isRight = false, confirmedWave = confirmedMusicWave)
        }
        val task = {
            renderBreaker.execute {
                val old = leftTemplateRef?.get()
                // v1.4.5-fix20.2 自愈：非宽态时模板里还亮着注入容器必是历史残留，当场藏掉（宽态进入时内容层会重新显示）
                if (activeMode == RenderMode.NONE) {
                    hideInjectedLeftInfo(template, "residue_healed")
                }
                if (old === template && switcherRef?.get() != null) {
                    val nowUp = SystemClock.uptimeMillis()
                    if (activeMode == RenderMode.WIDE_V11 && nowUp - lastSyncWideBindUptimeMs <= 16L) {
                        return@execute
                    }
                    refreshRefsFromLeftTemplate(template)
                    reconcileState(forceText = true)
                    return@execute
                }
                // v1.3.11 归属守卫（兼顾首次绑定）：若当前已持有活跃音乐岛左模板，且新附着模板子树无声波，则属充电岛等非音乐场景直接放行不抢绑；
                // 若尚未绑定有效音乐岛（首次绑定声波未到），允许暂存引用待声波到达后由 shouldShowOnCollapsedCapsule / onMusicWaveAttachChanged 确认。
                val postWave = confirmedMusicWave ?: findMusicWaveInSameCapsule(template)
                if (postWave == null && old != null && old !== template && old.isAttachedToWindow) {
                    val oldWave = findMusicWaveInSameCapsule(old) ?: musicWaveRef?.get()?.takeIf { isSameCapsule(old, it) }
                    if (oldWave != null && oldWave.isAttachedToWindow) {
                        return@execute
                    }
                }

                wideGeneration++
                clearFastResumeState()
                albumIconRef = null
                old?.removeOnLayoutChangeListener(templateLayoutListener)
                leftTemplateRef = WeakReference(template)
                template.addOnLayoutChangeListener(templateLayoutListener)

                // v1.4.5-fix20.2：换绑清引用前，先把旧模板上的注入容器藏掉，防旧模板被重附着时残留显形
                if (old != null && old !== template) hideInjectedLeftInfo(old, "bind_rebind")
                leftInfoContainerRef = null
                leftTitleViewRef = null
                leftArtistViewRef = null
                savedSwitcherStyle = null
                savedTvStyles.clear()
                isWideLatched = false
                cancelPositiveMeasurePoll()
                cancelHoldSettleCheck()
                cancelIntroWaveLeadEndCheck()
                cancelReentryVisibilityPoll(resetAttempts = true)
                lastRenderedNarrowText = ""

                refreshRefsFromLeftTemplate(template)
                reconcileState(forceText = true)
            }
        }
        // v1.3.16：主线程触发时直接同步完成绑定与 reconcile，让引用与 333px 在首次 onMeasure 前同帧就位
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task()
        } else {
            mainHandler.post { task() }
        }
    }

    fun unbindLeftTemplate(template: ViewGroup) {
        val capturedGen = wideGeneration
        mainHandler.post {
            renderBreaker.execute {
                // v1.3.18：世代校验 + 重附着校验，彻底杜绝陈旧 detach 拆掉新一轮宽态
                if (capturedGen != wideGeneration) return@execute
                if (template.isAttachedToWindow) return@execute
                if (leftTemplateRef?.get() === template) {
                    template.removeOnLayoutChangeListener(templateLayoutListener)
                    restoreAllToFactory("left_template_detached")
                    // v1.4.5-fix20.2：还原可能被 MIN_HOLD 延后，清引用前先按宿主把注入容器藏掉，杜绝残留
                    hideInjectedLeftInfo(template, "unbind")
                    leftTemplateRef = null
                    albumIconRef = null
                    switcherRef = null
                    leftInfoContainerRef = null
                    leftTitleViewRef = null
                    leftArtistViewRef = null
                    savedSwitcherStyle = null
                    savedTvStyles.clear()
                    isWideLatched = false
                }
            }
        }
    }

    fun bindRightTemplate(template: ViewGroup) {
        val confirmedWave = findChildByIdName(template, Const.ID_MUSIC_WAVE)
            ?: musicWaveRef?.get()?.takeIf { it.parent === template }
        // v1.3.16：仅确认含音乐声波的右模板才同步预写宽尺寸，直接校验入参新模板与 confirmedWave
        if (confirmedWave != null) {
            preApplyWideLpIfEligible(template, isRight = true, confirmedWave = confirmedWave)
        }
        val task = {
            renderBreaker.execute {
                val old = rightTemplateRef?.get()
                val postWave = confirmedWave
                    ?: findChildByIdName(template, Const.ID_MUSIC_WAVE)
                    ?: musicWaveRef?.get()?.takeIf { it.parent === template }
                if (postWave == null && old != null && old !== template && old.isAttachedToWindow) {
                    val oldHasWave = findChildByIdName(old, Const.ID_MUSIC_WAVE) != null ||
                        musicWaveRef?.get()?.parent === old
                    if (oldHasWave) {
                        return@execute
                    }
                }
                val changed = old !== template
                if (changed) {
                    wideGeneration++
                    clearFastResumeState()
                    old?.removeOnLayoutChangeListener(templateLayoutListener)
                    rightTemplateRef = WeakReference(template)
                    template.addOnLayoutChangeListener(templateLayoutListener)
                    rightLyricViewRef = null
                    rightIntroCdViewRef = null
                    rightInterludeCdViewRef = null
                    lastRenderedRightText = ""
                    lastRenderedRightState = -1
                }
                (postWave ?: findChildByIdName(template, Const.ID_MUSIC_WAVE))?.let { wave ->
                    captureWaveReference(wave, attached = wave.isAttachedToWindow)
                }
                val nowUp = SystemClock.uptimeMillis()
                if (!changed && activeMode == RenderMode.WIDE_V11 && nowUp - lastSyncWideBindUptimeMs <= 16L) {
                    return@execute
                }
                reconcileState(forceText = true)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task()
        } else {
            mainHandler.post { task() }
        }
    }

    fun unbindRightTemplate(template: ViewGroup) {
        mainHandler.post {
            renderBreaker.execute {
                if (template.isAttachedToWindow) return@execute
                if (rightTemplateRef?.get() === template) {
                    template.removeOnLayoutChangeListener(templateLayoutListener)
                    restoreWideRightToFactory("right_template_detached")
                    rightTemplateRef = null
                    rightLyricViewRef = null
                    rightIntroCdViewRef = null
                    rightInterludeCdViewRef = null
                    lastRenderedRightText = ""
                    lastRenderedRightState = -1
                }
            }
        }
    }

    /** v1.3.16：直接校验入参新模板与同胶囊 confirmedWave，消灭重附着首帧因旧 leftTemplateRef 为空导致的预写空转 */
    private fun preApplyWideLpIfEligible(template: ViewGroup, isRight: Boolean, confirmedWave: View) {
        runCatching {
            val prefs = readPrefs()
            if (!sessionActive || !prefs.masterEnabled || suppressPreWriteUntilPlay || !isEffectivelyPlaying()) return
            if (activeMode != RenderMode.WIDE_V11 && !lastConfirmedWide) return

            val root = rootWindowRef?.get() ?: template.rootView
            if (root != null && root.height > Const.ISLAND_EXPANDED_HEIGHT_THRESHOLD_PX) {
                lastExpandedElapsedMs = SystemClock.elapsedRealtime()
                return
            }
            val capExpanded = capExpandedRef?.get()
            if (capExpanded != null && capExpanded.visibility == View.VISIBLE && capExpanded.height > 0) {
                lastExpandedElapsedMs = SystemClock.elapsedRealtime()
                return
            }

            if (template.visibility != View.VISIBLE) return
            if (confirmedWave.visibility != View.VISIBLE) return

            val targetPx = if (isRight) {
                computeRightTemplateWidthPx(template.context, prefs)
            } else {
                Const.WIDE_TEMPLATE_EXPLICIT_WIDTH_PX
            }
            isInternalUpdate = true
            try {
                snapshotAndSetExplicitWidth(template, targetPx)
                val parent = template.parent as? View
                if (parent != null && parent !== capLeftRef?.get() && parent !== capRightRef?.get()) {
                    snapshotAndSetExplicitWidth(parent, targetPx)
                }
                if (isRight) {
                    applyRightTemplateEdgePadding(template, prefs)
                } else {
                    applyLeftTemplateEdgePadding(template, prefs)
                }
            } finally {
                isInternalUpdate = false
            }
        }
    }

    fun onMusicWaveAttachChanged(waveView: View, attached: Boolean) {
        if (attached) {
            val task = {
                renderBreaker.execute {
                    captureWaveReference(waveView, attached = true)
                    (waveView.parent as? ViewGroup)?.let { parent ->
                        preApplyWideLpIfEligible(parent, isRight = true, confirmedWave = waveView)
                        if (rightTemplateRef?.get() !== parent) {
                            wideGeneration++
                            rightTemplateRef?.get()?.removeOnLayoutChangeListener(templateLayoutListener)
                            rightTemplateRef = WeakReference(parent)
                            parent.addOnLayoutChangeListener(templateLayoutListener)
                            rightLyricViewRef = null
                            rightIntroCdViewRef = null
                            rightInterludeCdViewRef = null
                            lastRenderedRightText = ""
                            lastRenderedRightState = -1
                        }
                    }
                    // v1.3.11：声波附着时若左模板尚未绑定或指向其他卡片，从同胶囊容器自动回溯补绑音乐岛左模板
                    val curLeft = leftTemplateRef?.get()
                    if (curLeft == null || !curLeft.isAttachedToWindow || !isSameCapsule(curLeft, waveView)) {
                        findCapsuleContainerForView(waveView)?.let { capContainer ->
                            findByClassName(capContainer, Const.CLS_LEFT_TEMPLATE)?.let { lt ->
                                if (lt is ViewGroup && lt.isAttachedToWindow && lt !== curLeft) {
                                    preApplyWideLpIfEligible(lt, isRight = false, confirmedWave = waveView)
                                    wideGeneration++
                                    albumIconRef = null
                                    curLeft?.removeOnLayoutChangeListener(templateLayoutListener)
                                    leftTemplateRef = WeakReference(lt)
                                    lt.addOnLayoutChangeListener(templateLayoutListener)
                                    // v1.4.5-fix20.2：回溯补绑清引用前，先把旧左模板上的注入容器藏掉，防残留显形
                                    if (curLeft != null && curLeft !== lt) hideInjectedLeftInfo(curLeft, "wave_rebind")
                                    leftInfoContainerRef = null
                                    leftTitleViewRef = null
                                    leftArtistViewRef = null
                                    savedSwitcherStyle = null
                                    savedTvStyles.clear()
                                    isWideLatched = false
                                    lastRenderedNarrowText = ""
                                    refreshRefsFromLeftTemplate(lt)
                                }
                            }
                        }
                    }
                    val nowUp = SystemClock.uptimeMillis()
                    if (activeMode == RenderMode.WIDE_V11 && nowUp - lastSyncWideBindUptimeMs <= 16L) {
                        return@execute
                    }
                    reconcileState(forceText = true)
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                task()
            } else {
                mainHandler.post { task() }
            }
        } else {
            mainHandler.post {
                renderBreaker.execute {
                    if (waveView.isAttachedToWindow) return@execute
                    if (musicWaveRef?.get() === waveView) {
                        if (!isPlaybackPlaying) {
                            suppressPreWriteUntilPlay = true
                        }
                        cancelSoloFade(preserveTransform = false)
                        resetSoloWaveTransformIfMatching(waveView, resetTranslation = true)
                        if (waveRestoreSuppressed) {
                            // v1.4.5-fix16（Quick 二轮裁决二-1）：压制中 detach 是 7/7 泄漏源——不还原、不清快照，
                            // 只留引用给兜底/换实例路径；同实例重挂由接管消费，新实例走 capture 时先还原旧实例
                            suppressedWaveRef = WeakReference(waveView)
                            val hMsg = "[WAVE_SUPPRESS] hold reason=wave_detached_while_suppressed"
                            YLog.info(msg = hMsg)
                            EventHistoryStore.recordDiagOnly(hMsg)
                        } else {
                            restoreWaveVisibilityIfSaved(waveView)
                            savedWaveVisibility = null
                            savedWaveIdentity = 0
                        }
                        musicWaveRef = null
                        musicWaveAttached = false
                        reconcileState(forceText = false)
                    }
                }
            }
        }
    }

    fun onRootWindowAttached(root: View) {
        mainHandler.post {
            renderBreaker.execute {
                val old = rootWindowRef?.get()
                if (old !== root) {
                    old?.removeOnLayoutChangeListener(rootLayoutListener)
                    rootWindowRef = WeakReference(root)
                    root.addOnLayoutChangeListener(rootLayoutListener)
                }
                // 每次 IslandWindowView 重新 attach 重置视图树转储标志，确保新窗口必有非零转储
                wideTreeDumped = false
                wideTreeRetryScheduled = false
                wideTreeFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
                wideTreeFallbackRunnable = null

                glowRenderer.attachToRoot(root)
                if (leftTemplateRef?.get() == null) {
                    findByClassName(root, Const.CLS_LEFT_TEMPLATE)?.let {
                        if (it is ViewGroup) bindLeftTemplate(it)
                    }
                }
                if (rightTemplateRef?.get() == null) {
                    findByClassName(root, Const.CLS_RIGHT_TEMPLATE)?.let {
                        if (it is ViewGroup) bindRightTemplate(it)
                    }
                }
                if (musicWaveRef?.get() == null) {
                    findMusicWaveInTree(root)?.let {
                        captureWaveReference(it, attached = it.isAttachedToWindow)
                    }
                }
                reconcileState(forceText = true)
            }
        }
    }

    /**
     * A 项关键修复：IslandWindowView detach 仅做 UI 还原，
     * 严格保留 sessionActive、currentLyric、标题/歌手缓存与 lastConfirmedWide！
     */
    fun onRootWindowDetached(reason: String) {
        mainHandler.post {
            renderBreaker.execute {
                restoreAllToFactory(reason)
            }
        }
    }

    fun updateTrackInfo(displayTitle: String, artist: String) {
        val task = {
            renderBreaker.execute {
                val prevTitle = cachedTrackTitle
                cachedTrackTitle = displayTitle
                cachedTrackArtist = artist
                if (fastResumeState?.trackKey != currentTrackKey()) {
                    clearFastResumeState()
                }
                val prefs = readPrefs()
                if (activeMode == RenderMode.WIDE_V11) {
                    val leftTemplate = leftTemplateRef?.get()
                    if (leftTemplate != null && displayTitle.isNotBlank()) {
                        ensureLeftInfoInjected(leftTemplate, prefs)
                    }
                    // F3：标题暂未到达时不把左侧置 GONE（保持上一帧占位）；标题到达后立即补注并刷新左侧
                    if (displayTitle.isNotBlank()) {
                        updateLeftInfoContent(prefs)
                    }
                } else if (prevTitle.isBlank() && displayTitle.isNotBlank() && shouldShowOnCollapsedCapsule(prefs)) {
                    reconcileState(forceText = true)
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper() && !isInternalUpdate) {
            task()
        } else {
            mainHandler.post { task() }
        }
    }

    fun updateGlowPalette(colors: IntArray) {
        mainHandler.post {
            renderBreaker.execute {
                currentGlowPalette = colors
                currentKaraokeSungColor = CapsuleGlowRenderer.deriveKaraokeSungColor(colors)
                rightLyricViewRef?.get()?.apply {
                    setKaraokeSungColor(currentKaraokeSungColor)
                    setPaletteColors(colors)
                }
                rightIntroCdViewRef?.get()?.setAccentColor(currentKaraokeSungColor)
                rightInterludeCdViewRef?.get()?.setAccentColor(currentKaraokeSungColor)
                glowRenderer.updatePalette(colors)
            }
        }
    }

    // v1.4.5-fix16：[PERF] 窗口记账（播放状态变化时输出一行；>60s 无输出时在下一次状态事件补出）
    private var perfWindowStartMs: Long = 0L
    private var perfPlayingSinceMs: Long = 0L
    private var perfPlayingAccumMs: Long = 0L
    private var perfLastPlaying: Boolean = false
    // vc55（fix21）：隐藏时长记账——restoreAllToFactory 落 hide 时间戳时起算、接管时结算进窗口
    private var perfHiddenSinceMs: Long = 0L
    private var perfHiddenAccumMs: Long = 0L
    // vc56：息屏时长记账——SCREEN_OFF/ON 广播边沿起止（与 hiddenMs 同款结算），供息屏窗直接读帧计数
    private var perfScreenOffSinceMs: Long = 0L
    private var perfScreenOffAccumMs: Long = 0L

    private fun emitPerfIfDue(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (perfWindowStartMs == 0L) {
            perfWindowStartMs = now
            return
        }
        val windowMs = now - perfWindowStartMs
        if (!force && windowMs < 60_000L) return
        var playingMs = perfPlayingAccumMs
        if (perfPlayingSinceMs > 0L) playingMs += now - perfPlayingSinceMs
        var hiddenMs = perfHiddenAccumMs
        if (perfHiddenSinceMs > 0L) hiddenMs += now - perfHiddenSinceMs
        var screenOffMs = perfScreenOffAccumMs
        if (perfScreenOffSinceMs > 0L) screenOffMs += now - perfScreenOffSinceMs
        val screenOn = runCatching {
            val ctx = leftTemplateRef?.get()?.context ?: rootWindowRef?.get()?.context
            val pm = ctx?.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm == null) -1 else if (pm.isInteractive) 1 else 0
        }.getOrDefault(-1)
        val perfMsg = "[PERF] " + com.vivo.islandlyric.util.PerfStats.collectAndReset(windowMs / 1000L, playingMs, hiddenMs, screenOn, screenOffMs)
        YLog.info(msg = perfMsg)
        EventHistoryStore.recordDiagOnly(perfMsg)
        perfWindowStartMs = now
        perfPlayingAccumMs = 0L
        if (perfPlayingSinceMs > 0L) perfPlayingSinceMs = now
        perfHiddenAccumMs = 0L
        if (perfHiddenSinceMs > 0L) perfHiddenSinceMs = now
        perfScreenOffAccumMs = 0L
        if (perfScreenOffSinceMs > 0L) perfScreenOffSinceMs = now
    }

    // vc56：息屏广播接收器（E2 双审定版）——进程内只注册一次、applicationContext 注册、失败整体静默失效；
    // 边沿强制结算 PERF 窗口，使息屏时段有独立窗口可直接读帧计数；兼作波浪兜底 C 的亮屏重跑触发
    private var screenReceiverRegistered: Boolean = false
    // vc56（双审 diff 门检）：注册失败退避——reconcile 高频调用，失败最多试 3 次后本进程内放弃，免反复建 IntentFilter/抛异常
    private var screenReceiverFailures: Int = 0
    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val action = intent?.action ?: return
            // onReceive 只转发：结算与重跑都回主线程并过 renderBreaker（Quick 口径，与其他入口一致）
            mainHandler.post {
                renderBreaker.execute {
                    when (action) {
                        android.content.Intent.ACTION_SCREEN_OFF -> {
                            // 先结算息屏前窗口（其 screenOffMs 不含本次息屏段），再记 since；双 OFF 不覆盖
                            emitPerfIfDue(force = true)
                            if (perfScreenOffSinceMs == 0L) {
                                perfScreenOffSinceMs = SystemClock.elapsedRealtime()
                            }
                        }
                        android.content.Intent.ACTION_SCREEN_ON -> {
                            // 先折算累计并清零 since，再结算息屏窗（防重复计，Quick 口径）
                            var offSegmentMs = 0L
                            if (perfScreenOffSinceMs > 0L) {
                                offSegmentMs = SystemClock.elapsedRealtime() - perfScreenOffSinceMs
                                perfScreenOffAccumMs += offSegmentMs
                                perfScreenOffSinceMs = 0L
                            }
                            if (offSegmentMs > 0L) {
                                emitPerfIfDue(force = true)
                                val sMsg = "[SCREEN] on offMs=$offSegmentMs"
                                YLog.info(msg = sMsg)
                                EventHistoryStore.recordDiagOnly(sMsg)
                            }
                            // vc56 C：息屏期被延期的波浪兜底在亮屏事件上立即重跑
                            rerunWaveFallbackIfGateDeferred()
                        }
                    }
                }
            }
        }
    }

    private fun ensureScreenReceiverRegistered() {
        if (screenReceiverRegistered || screenReceiverFailures >= 3) return
        val ctx = (rootWindowRef?.get()?.context ?: leftTemplateRef?.get()?.context)?.applicationContext ?: return
        val ok = runCatching {
            val filter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_OFF)
                addAction(android.content.Intent.ACTION_SCREEN_ON)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(screenStateReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                ctx.registerReceiver(screenStateReceiver, filter)
            }
            true
        }.getOrDefault(false)
        if (!ok) {
            screenReceiverFailures++
            return
        }
        if (ok) {
            screenReceiverRegistered = true
            // Hark：注册时先采样——若此刻屏幕已熄，先行记 since，免漏掉注册前已开始的息屏段
            val interactive = runCatching {
                val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                pm?.isInteractive
            }.getOrNull()
            if (interactive == false && perfScreenOffSinceMs == 0L) {
                perfScreenOffSinceMs = SystemClock.elapsedRealtime()
            }
        }
    }

    fun updatePlaybackPlaying(isPlaying: Boolean) {
        // v1.3.18：切歌 600ms 暂停宽限——暂停且宽态显示中时先保宽停动画，600ms 内恢复无缝衔接新曲，超时再拆宽
        // v1.4.5-fix16：[PERF] 记账与输出（状态变化必出、长窗口补出）；进入暂停时补存事件历史
        val nowPerf = SystemClock.elapsedRealtime()
        if (perfWindowStartMs == 0L) perfWindowStartMs = nowPerf
        if (isPlaying != perfLastPlaying) {
            if (isPlaying) {
                perfPlayingSinceMs = nowPerf
            } else {
                if (perfPlayingSinceMs > 0L) {
                    perfPlayingAccumMs += nowPerf - perfPlayingSinceMs
                    perfPlayingSinceMs = 0L
                }
                EventHistoryStore.flushPersist("playback_pause")
            }
            perfLastPlaying = isPlaying
            com.vivo.islandlyric.util.PerfStats.reconcileState.incrementAndGet()
            emitPerfIfDue(force = true)
        } else {
            emitPerfIfDue(force = false)
        }
        // v1.4.5-fix20.3 C2：记录暂停起始时刻（供延迟释放复核「暂停满 300ms」）；恢复播放清零并取消待释放任务
        if (isPlaying) {
            pauseStartUptimeMs = 0L
            cancelPauseWaveRelease()
        } else if (pauseStartUptimeMs == 0L) {
            // v1.4.5-fix20.3 实施审要改 2：不依赖 isPlaybackPlaying 的先后（updateLyric 可能先行置 false），
            // 只要处于暂停且未记过就记——记晚只会让 300ms 复核更保守，不会提前释放
            pauseStartUptimeMs = SystemClock.uptimeMillis()
        }
        isPlaybackPlaying = isPlaying
        if (isPlaying) {
            sessionActive = true
            suppressPreWriteUntilPlay = false
            pauseGraceActive = false
        } else if (activeMode != RenderMode.NONE) {
            pauseGraceActive = true
        } else {
            pauseGraceActive = false
            suppressPreWriteUntilPlay = true
        }
        mainHandler.post {
            renderBreaker.execute {
                val nowUptime = SystemClock.uptimeMillis()
                if (!isPlaying) {
                    if (currentLyricStartUptimeMs > 0L && currentLyricPauseStartUptimeMs == 0L) {
                        currentLyricPauseStartUptimeMs = nowUptime
                    }
                } else {
                    if (currentLyricPauseStartUptimeMs > 0L) {
                        currentLyricPausedAccumMs += (nowUptime - currentLyricPauseStartUptimeMs).coerceAtLeast(0L)
                        currentLyricPauseStartUptimeMs = 0L
                    }
                }
                rightLyricViewRef?.get()?.setPlaying(isPlaying)
                rightIntroCdViewRef?.get()?.setPlaying(isPlaying)
                rightInterludeCdViewRef?.get()?.setPlaying(isPlaying)
                if (!isPlaying) {
                    clearFastResumeState()
                    cancelPositiveMeasurePoll()
                    cancelHoldSettleCheck()
                    cancelIntroWaveLeadEndCheck()
                    cancelInterludeWaveEndCheck()
                    // v1.4.5-fix10（Quick S3）：NONE 且压制中暂停——重入已注销，不必等兜底 5s，立即把波浪还给原厂暂停胶囊
                    // v1.4.5-fix13 A（Quick 裁决必须改 1）：展开类除外——展开大卡里拖进度/切歌必带一次瞬时 state 2，
                    // 此处无条件还原正是 fix12 一大半轮次的漏放点（日志逐行对位实证）；展开类暂停时只清快速恢复，波浪留给接管/保险
                    // v1.4.5-fix20.3 C3：改走延迟释放——fix16 起波浪脱落时引用转存 suppressedWaveRef，本行旧写法
                    // 只查 musicWaveRef 必然扑空、只能等 2.5s 兜底；且同步释放挡不住控制中心拖进度条的瞬时 state=2。
                    // 不再排除 expand 类：此处只排期，到点由 C2 的「物理收起」复核决定放不放，A-1 仍守得住
                    if (activeMode == RenderMode.NONE && waveRestoreSuppressed) {
                        schedulePauseWaveRelease("pause_immediate", 300L)
                    }
                    cancelReentryVisibilityPoll(resetAttempts = true)
                    if (activeMode != RenderMode.NONE) {
                        pauseGraceActive = true
                        if (pendingPauseRestoreRunnable == null) {
                            val capturedGen = wideGeneration
                            val task = Runnable {
                                pendingPauseRestoreRunnable = null
                                renderBreaker.execute {
                                    if (capturedGen != wideGeneration || isPlaybackPlaying) {
                                        pauseGraceActive = false
                                        return@execute
                                    }
                                    pauseGraceActive = false
                                    suppressPreWriteUntilPlay = true
                                    if (activeMode != RenderMode.NONE) {
                                        restoreAllToFactory("playback_paused")
                                    } else {
                                        glowRenderer.hideAndStop("playback_paused")
                                    }
                                }
                            }
                            pendingPauseRestoreRunnable = task
                            mainHandler.postDelayed(task, PAUSE_TEARDOWN_GRACE_MS)
                        }
                    } else {
                        cancelPendingPauseRestore()
                        suppressPreWriteUntilPlay = true
                        glowRenderer.hideAndStop("playback_paused")
                    }
                } else {
                    cancelPendingPauseRestore()
                    cancelPendingHideSettle()
                    reconcileState(forceText = false)
                }
            }
        }
    }

    fun updateLyric(
        lyric: String,
        isPlaying: Boolean = isPlaybackPlaying,
        estimatedWindowMs: Long = currentEstimatedWindowMs,
        karaokeDurationMs: Long = estimatedWindowMs,
        wordSpans: List<LrcTimelineParser.WordSpan> = emptyList(),
        lineSeq: Long = currentLyricLineSeq,
        nextLyric: String = ""
    ) {
        sessionActive = true
        isPlaybackPlaying = isPlaying
        if (isPlaying) {
            suppressPreWriteUntilPlay = false
        }
        mainHandler.post {
            renderBreaker.execute {
                val seqChanged = lineSeq > 0L && lineSeq != currentLyricLineSeq
                if (seqChanged || lyric != currentLyric || currentLyricStartUptimeMs <= 0L) {
                    resetCurrentLyricClock(startNow = lyric.isNotBlank(), isPlaying = isPlaying)
                }
                if (lineSeq > 0L) {
                    currentLyricLineSeq = lineSeq
                }
                val hotUpgraded = !seqChanged && lyric == currentLyric &&
                    (nextLyric != currentNextLyric || wordSpans != currentWordSpans)
                currentLyric = lyric
                currentNextLyric = nextLyric
                currentWordSpans = wordSpans
                currentEstimatedWindowMs = estimatedWindowMs
                currentKaraokeDurationMs = karaokeDurationMs
                inInterludeMode = false
                currentInterludeTargetMs = -1L
                currentInterludeStartMs = -1L
                interludeSkipWaveLead = false
                cancelInterludeWaveEndCheck()
                if (lyric.isNotBlank() && inIntroMode) {
                    inIntroMode = false
                    introSkipWaveLead = false
                    introForceWaveOnly = false
                    cancelIntroWaveLeadEndCheck()
                    // v1.4.5-fix18 实施审 S：不经 cancelSoloFade 的收尾路径也拆淡入守门并复位 alpha，防守门残留把下轮倒计时压成隐形
                    cancelCountdownFadeGate(resetAlpha = true)
                    rightIntroCdViewRef?.get()?.stopAndHide()
                }
                reconcileState(forceText = hotUpgraded)
            }
        }
    }

    /** v1.4.5-fix10 F10-3（Quick M3 合并版）：间奏状态与段界一次 post 原子写入，免分帧闪数字；
     * 段界由 Source 随武装同发（startMs=-1 为未知：如 GapTail 目标修正——Quick S1 此时清段界回直接数字，准优先于有） */
    fun updateInterlude(inInterlude: Boolean, targetNextLineMs: Long = -1L, startMs: Long = -1L, skipWaveLead: Boolean = false) {
        mainHandler.post {
            renderBreaker.execute {
                val nextTarget = if (inInterlude) targetNextLineMs else -1L
                val nextStart = if (inInterlude) startMs else -1L
                val nextSkip = inInterlude && skipWaveLead
                if (inInterludeMode == inInterlude && currentInterludeTargetMs == nextTarget &&
                    currentInterludeStartMs == nextStart && interludeSkipWaveLead == nextSkip
                ) return@execute
                inInterludeMode = inInterlude
                currentInterludeTargetMs = nextTarget
                currentInterludeStartMs = nextStart
                interludeSkipWaveLead = nextSkip
                if (!inInterlude) cancelInterludeWaveEndCheck()
                if (!sessionActive) return@execute
                reconcileState(forceText = true)
            }
        }
    }

    fun updateIntro(
        inIntro: Boolean,
        learnedFirstLineMs: Long,
        skipWaveLead: Boolean = false,
        forceWaveOnly: Boolean = false
    ) {
        mainHandler.post {
            renderBreaker.execute {
                val nextSkip = inIntro && skipWaveLead && !forceWaveOnly
                val nextForceWave = inIntro && forceWaveOnly
                val changed = inIntroMode != inIntro ||
                    currentIntroLearnedMs != learnedFirstLineMs ||
                    introSkipWaveLead != nextSkip ||
                    introForceWaveOnly != nextForceWave
                inIntroMode = inIntro
                currentIntroLearnedMs = learnedFirstLineMs
                introSkipWaveLead = nextSkip
                introForceWaveOnly = nextForceWave
                if (!inIntro) {
                    cancelIntroWaveLeadEndCheck()
                    // v1.4.5-fix18 实施审 S：同上，不经 cancelSoloFade 的收尾路径先拆守门
                    cancelCountdownFadeGate(resetAlpha = true)
                    rightIntroCdViewRef?.get()?.stopAndHide()
                    // v1.4.5-fix12 F12-C 诊断：前奏结束瞬间歌词是否仍为空——与 renderer_restore 的 blocker= 对照，
                    // 实证「切第一句空转还原」是否源于 updateLyric 尚未到达的时序窗；零行为改动
                    val ciMsg = "[INTRO_END] currentLyricBlank=${currentLyric.isBlank()} changed=$changed"
                    YLog.info(msg = ciMsg)
                    EventHistoryStore.recordDiagOnly(ciMsg)
                }
                if (inIntro && isPlaybackPlaying) {
                    sessionActive = true
                }
                if (!sessionActive) return@execute
                val desiredState = resolveDesiredRightState()
                if (!changed && (!inIntro || desiredState == lastRenderedRightState)) {
                    if (inIntro && desiredState == 3) {
                        scheduleIntroWaveLeadEndCheck()
                    }
                    return@execute
                }
                reconcileState(forceText = true)
            }
        }
    }

    fun clearForTrackSwitch(
        newInitialTitle: String,
        isPlaying: Boolean = isPlaybackPlaying,
        estimatedWindowMs: Long = Const.SCROLL_DEFAULT_WINDOW_MS
    ) {
        mainHandler.post {
            renderBreaker.execute {
                sessionActive = true
                isPlaybackPlaying = isPlaying
                if (isPlaying) {
                    suppressPreWriteUntilPlay = false
                }
                currentEstimatedWindowMs = estimatedWindowMs
                cancelSoloFade(preserveTransform = false)
                inInterludeMode = false
                currentInterludeTargetMs = -1L
                currentInterludeStartMs = -1L
                interludeSkipWaveLead = false
                cancelInterludeWaveEndCheck()
                lastRenderedNarrowText = ""
                lastRenderedRightText = ""
                lastRenderedNextLyric = ""
                lastRenderedWordSpans = emptyList()
                lastRenderedRightState = -1
                lastRenderedLineSeq = -1L
                currentLyric = ""
                currentNextLyric = ""
                currentWordSpans = emptyList()
                clearActiveLyricTextOnly()
                // vc55（确认轮 G1 定版）：明确通知歌词视图换歌了（清双行标记/过渡，视图侧猜不到——lineSeq 会话内单调递增）
                rightLyricViewRef?.get()?.onTrackSwitched()
                if (newInitialTitle.isNotBlank()) {
                    resetCurrentLyricClock(startNow = true, isPlaying = isPlaying)
                    currentLyric = newInitialTitle
                    reconcileState(forceText = true)
                } else {
                    resetCurrentLyricClock(startNow = false, isPlaying = false)
                    reconcileState(forceText = false)
                }
            }
        }
    }

    /** 仅真会话结束或关总开关时调用：入口同步清 @Volatile 标志，已在主线程则零延迟同步还原 */
    fun clearAndRestore(reason: String) {
        sessionActive = false
        isPlaybackPlaying = false
        pauseGraceActive = false
        hideSettleActive = false
        inInterludeMode = false
        currentInterludeTargetMs = -1L
        currentInterludeStartMs = -1L
        interludeSkipWaveLead = false
        cancelInterludeWaveEndCheck()
        suppressPreWriteUntilPlay = true
        inIntroMode = false
        currentIntroLearnedMs = -1L
        lastConfirmedWide = false
        clearFastResumeState()

        val task = {
            renderBreaker.execute {
                cancelPendingPauseRestore()
                cancelPendingHideSettle()
                resetCurrentLyricClock(startNow = false, isPlaying = false)
                currentLyric = ""
                currentNextLyric = ""
                currentWordSpans = emptyList()
                currentEstimatedWindowMs = Const.SCROLL_DEFAULT_WINDOW_MS
                currentKaraokeDurationMs = Const.SCROLL_DEFAULT_WINDOW_MS
                cachedTrackTitle = ""
                cachedTrackArtist = ""
                lastRenderedNarrowText = ""
                lastRenderedRightText = ""
                lastRenderedNextLyric = ""
                lastRenderedWordSpans = emptyList()
                lastRenderedRightState = -1
                lastEdgeCheckKey = ""
                // v1.4.5-fix20.3 C6：会话销毁/关总开关后不会再有接管，先同步释放声波压制，免等 2.5s 兜底黑空
                releaseSuppressNow("clear_and_restore:$reason")
                restoreAllToFactory(reason)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task()
        } else {
            mainHandler.post { task() }
        }
    }

    /** 仅在窄形态非间奏期阻止模板把 switcher 误置回 GONE；间奏期显式放行 */
    fun shouldPreventSwitcherHide(targetView: View): Boolean {
        if (isInternalUpdate || inInterludeMode) return false
        if (activeMode != RenderMode.NARROW_V10) return false
        val switcher = switcherRef?.get() ?: return false
        if (targetView !== switcher) return false
        return shouldShowOnCollapsedCapsule(readPrefs())
    }

    /**
     * D 项严格分离：
     * - 仅间奏期（inInterludeMode && !inIntroMode）放行原厂波形可见；
     * - 前奏期右侧用自家 9 竖条 IntroEqualizerView，原厂小波形继续让位 GONE。
     */
    fun shouldPreventWaveShow(targetView: View): Boolean {
        if (isInternalUpdate) return false
        if (inInterludeMode && !inIntroMode) return false
        if (inIntroMode && (currentIntroLearnedMs <= 0L || isInIntroWaveLeadPhase())) return false
        if (activeMode != RenderMode.WIDE_V11) return false
        val prefs = readPrefs()
        if (prefs.persistentWaveEnabled) return false
        val wave = musicWaveRef?.get() ?: return false
        if (targetView !== wave) return false
        return shouldShowOnCollapsedCapsule(prefs)
    }

    /** v1.3.15：wave 被原厂置为 VISIBLE 且当前处于 NONE 时在主线程同步重入宽态，在首帧 measure/draw 前完成预写，消除先短后长 */
    fun onMusicSurfaceBecameVisible(waveView: View) {
        if (isInternalUpdate) return
        // v1.4.5-fix19 P0-1：归属改由 resolveValidWaveLedger 惰性校验，此处不做回调清账本
        if ((!hideSettleActive && activeMode != RenderMode.NONE) || !sessionActive || !isEffectivelyPlaying()) return
        val currentWave = musicWaveRef?.get()
        if (currentWave != null && currentWave !== waveView) return
        val task = {
            renderBreaker.execute {
                if (hideSettleActive && activeMode != RenderMode.NONE && isEffectivelyPlaying()) {
                    cancelPendingHideSettle()
                    rightLyricViewRef?.get()?.setPlaying(isPlaybackPlaying)
                    rightIntroCdViewRef?.get()?.setPlaying(isPlaybackPlaying)
                    rightInterludeCdViewRef?.get()?.setPlaying(isPlaybackPlaying)
                    syncGlowWithCurrentState()
                    return@execute
                }
                if (activeMode == RenderMode.NONE && sessionActive && isEffectivelyPlaying()) {
                    reconcileState(forceText = true)
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task()
        } else {
            mainHandler.post { task() }
        }
    }

    /** v1.3.15：非内部操作且原厂将音乐声波置为非 VISIBLE（如充电卡接管）时，赶在充电首帧测量/布局前同步清场还原 */
    fun onMusicWaveHiddenBySystem(waveView: View) {
        if (isInternalUpdate) return
        // v1.4.5-fix19 P0-1：归属改由 resolveValidWaveLedger 惰性校验，此处不做回调清账本
        if (activeMode == RenderMode.NONE) {
            // v1.4.5-fix10（Quick M2）：NONE 且压制中系统隐波（如充电卡）——压制使命已终，清标记取消兜底，
            // 防兜底到点把 VISIBLE 盖回系统设的 GONE 上
            if (waveRestoreSuppressed && musicWaveRef?.get() === waveView) {
                clearWaveSuppress("system_hide_wave")
            }
            return
        }
        val currentWave = musicWaveRef?.get()
        if (currentWave != null && currentWave !== waveView) return
        val task = {
            renderBreaker.execute {
                if (activeMode != RenderMode.NONE && !isInternalUpdate) {
                    restoreAllToFactory("wave_hidden_by_system")
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task()
        } else {
            mainHandler.post { task() }
        }
    }

    private fun isVisibleUpToBoundary(view: View, boundary: View?): Boolean {
        if (!view.isAttachedToWindow || view.visibility != View.VISIBLE) return false
        var cur: View? = view
        var hops = 0
        while (cur != null && hops < 8) {
            if (cur.visibility != View.VISIBLE) return false
            if (boundary != null && cur === boundary) return true
            cur = cur.parent as? View
            hops++
        }
        return true
    }

    private var lastVisFailDetail: String = ""

    // v1.4.5-fix17：[TPL_DETACH] 用的拔出前位置缓存（链完整时由 logVisFailOnce 刷新）
    private var lastTplFrameIndex: Int = -1
    private var lastTplShellChildren: Int = -1

    /**
     * v1.4.5-fix15（Quick 问题二诊断）：left_not_visible 判定失败时逐层打出链上每层的
     * 类名/id/vis/alpha/attached/height，定位到底是哪一层先不可见；同内容只打一次防刷屏。
     */
    private fun logVisFailOnce(view: View, boundary: View?) {
        val sb = StringBuilder()
        sb.append("self[attached=").append(view.isAttachedToWindow)
            .append(" vis=").append(view.visibility)
            .append(" alpha=").append(view.alpha)
            .append(" h=").append(view.height).append(']')
        var cur: View? = view
        var hops = 0
        while (cur != null && hops < 8) {
            sb.append(" <- ").append(cur.javaClass.simpleName)
                .append('#').append(cur.id)
                .append("[vis=").append(cur.visibility)
                .append(" alpha=").append(cur.alpha)
                .append(" attached=").append(cur.isAttachedToWindow)
                .append(" h=").append(cur.height).append(']')
            if (cur.visibility != View.VISIBLE) {
                sb.append(" FAIL_HERE")
                break
            }
            if (boundary != null && cur === boundary) break
            cur = cur.parent as? View
            hops++
        }
        val detail = sb.toString()
        // v1.4.5-fix17 实施审 M1（Quick）：[TPL_DETACH] 整块置于去重 return 之前——它靠 attached 状态
        // 变化自触发，套在 VIS_FAIL 去重之后会被吞（连续两次拔出之间无 attached=true 的 VIS_FAIL 时漏打）
        if (!view.isAttachedToWindow) {
            val shellNow = capLeftRef?.get() as? ViewGroup
            val sinceInject = if (islandShimmer.lastInjectElapsedMs > 0L) {
                SystemClock.elapsedRealtime() - islandShimmer.lastInjectElapsedMs
            } else {
                -1L
            }
            val tMsg = "[TPL_DETACH] frameIdxBefore=$lastTplFrameIndex shellChildrenBefore=$lastTplShellChildren " +
                "shellChildrenNow=${shellNow?.childCount ?: -1} msSinceLastInject=$sinceInject"
            YLog.info(msg = tMsg)
            EventHistoryStore.recordDiagOnly(tMsg)
        } else {
            // 链还完整：刷新缓存——左模板框架（view 的父）在壳内的 index 与壳子 View 总数
            val frame = view.parent as? View
            val shellG = frame?.parent as? ViewGroup
            if (frame != null && shellG != null) {
                lastTplFrameIndex = shellG.indexOfChild(frame)
                lastTplShellChildren = shellG.childCount
            }
        }
        if (detail == lastVisFailDetail) return
        lastVisFailDetail = detail
        val msg = "[VIS_FAIL] $detail"
        YLog.info(msg = msg)
        EventHistoryStore.recordDiagOnly(msg)
    }

    private fun isTransientAppSwitchHide(prefs: RuntimePrefs): Boolean {
        if (!prefs.masterEnabled || !isEffectivelyPlaying() || !sessionActive) return false
        if (activeMode == RenderMode.NONE) return false
        val leftTemplate = leftTemplateRef?.get() ?: return false
        if (!leftTemplate.isAttachedToWindow) return false
        // v1.3.19：若左模板至 capLeft 链路仍在屏可见（如充电卡借壳接管），绝不走 350ms 沉降窗，0ms 即时清场防叠画
        if (isVisibleUpToBoundary(leftTemplate, capLeftRef?.get())) return false
        val root = rootWindowRef?.get() ?: leftTemplate.rootView
        if (root != null && root.height > Const.ISLAND_EXPANDED_HEIGHT_THRESHOLD_PX) {
            lastExpandedElapsedMs = SystemClock.elapsedRealtime()
            return false
        }
        val capExpanded = capExpandedRef?.get()
        if (capExpanded != null && capExpanded.visibility == View.VISIBLE && capExpanded.height > 0) {
            lastExpandedElapsedMs = SystemClock.elapsedRealtime()
            return false
        }
        // v1.3.20：整岛不可见（已过上方壳可见守卫拦截借壳接管）、非展开、播放中且模板已附着，歌词态与声波态均进 350ms 沉降窗
        return true
    }

    private fun scheduleTransientHideSettle() {
        hideSettleActive = true
        // 仓侧直修（Quick Q6）：收起沉降开始即打断 solo 进场动画并释放显形门控，防动画继续往正在隐藏的视图写 scale/位移
        cancelSoloWaveScaleIn()
        clearPendingWaveReveal(restoreAlpha = true)
        glowRenderer.hideAndStop("transient_hide_settle")
        rightLyricViewRef?.get()?.setPlaying(false)
        rightIntroCdViewRef?.get()?.setPlaying(false)
        rightInterludeCdViewRef?.get()?.setPlaying(false)
        if (pendingHideSettleRunnable != null) return
        val capturedGen = wideGeneration
        val task = Runnable {
            pendingHideSettleRunnable = null
            renderBreaker.execute {
                if (capturedGen != wideGeneration || !hideSettleActive) {
                    hideSettleActive = false
                    return@execute
                }
                hideSettleActive = false
                val p = readPrefs()
                if (!shouldShowOnCollapsedCapsule(p) && activeMode != RenderMode.NONE) {
                    restoreAllToFactory("state_changed_hide")
                } else if (activeMode != RenderMode.NONE) {
                    rightLyricViewRef?.get()?.setPlaying(isPlaybackPlaying)
                    rightIntroCdViewRef?.get()?.setPlaying(isPlaybackPlaying)
                    rightInterludeCdViewRef?.get()?.setPlaying(isPlaybackPlaying)
                    syncGlowWithCurrentState()
                }
            }
        }
        pendingHideSettleRunnable = task
        mainHandler.postDelayed(task, HIDE_SETTLE_GRACE_MS)
    }

    private fun onLayoutStateChanged() {
        val prefs = readPrefs()
        val shouldShow = shouldShowOnCollapsedCapsule(prefs)
        if (!shouldShow) {
            if (!isEffectivelyPlaying()) {
                suppressPreWriteUntilPlay = true
            }
            if (activeMode != RenderMode.NONE) {
                if (isTransientAppSwitchHide(prefs)) {
                    scheduleTransientHideSettle()
                } else {
                    cancelPendingHideSettle()
                    restoreAllToFactory("state_changed_hide")
                }
            } else {
                cancelPendingHideSettle()
                cancelPositiveMeasurePoll()
                // v1.4.5-fix20.3 C4：收起落地且已暂停——声波即时释放：暂停满 300ms 直接放、未满按剩余时间排
                // C2 延迟任务复核；守 fix13 A-1/A-3：须物理收起＋非播放＋非展开阻断，卡内瞬时 state=2 由 300ms 挡住
                if (waveRestoreSuppressed && !isPlaybackPlaying && !pauseGraceActive &&
                    !isExpandBlocker() && !isIslandPhysicallyExpanded() && pauseStartUptimeMs > 0L
                ) {
                    val pausedMs = SystemClock.uptimeMillis() - pauseStartUptimeMs
                    if (pausedMs >= 300L) {
                        releaseSuppressNow("collapse_paused_release")
                    } else {
                        schedulePauseWaveRelease("collapse_paused_release", 300L - pausedMs)
                    }
                }
                // v1.4.5-fix12 F12-1 释放点：展开类压制的兜底曾被推迟时，收起落地（阻断原因已非展开类）重新计时；
                // 压制悬置且确定不进宽态（非展开类原因、且快速恢复未武装）时立即完整还原波浪，不等兜底
                if (suppressAwaitCollapse && !isExpandBlocker() && !isIslandPhysicallyExpanded()) {
                    suppressAwaitCollapse = false
                    if (waveRestoreSuppressed) {
                        scheduleWaveSuppressFallback()
                    }
                }
                // v1.4.5-fix13 A（Quick 裁决必须改 3）：展开类不在此放——收起中途阻断原因会从 root_height 变成
                // scale 一类，!isExpandBlocker() 瞬时成立就误放；展开类只由接管/窄态双确认/卡死保险释放
                // v1.4.5-fix18 P0-4（三方收口）：layout_none 不再释放压制——转场中左模板刚挂回、离 pass
                // 还有 100-400ms，此时放波浪必闪（fix17 全日志 12/12 实证）。压制只许接管消费/暂停结束/
                // 兜底（2.5s）三处释放；此处仅确保兜底已武装（上方 suppressAwaitCollapse 分支已重排时不重复）
                if (waveRestoreSuppressed && waveSuppressKind != "expand" && !isExpandBlocker() &&
                    fastResumeState == null && !preDrawReentryMode &&
                    waveSuppressFallbackRunnable == null
                ) {
                    scheduleWaveSuppressFallback()
                }
                if (isAwaitingVisibleReentry(prefs)) {
                    scheduleReentryVisibilityPoll()
                } else {
                    cancelReentryVisibilityPoll(resetAttempts = true)
                }
            }
            return
        }
        if (hideSettleActive) {
            cancelPendingHideSettle()
            rightLyricViewRef?.get()?.setPlaying(isPlaybackPlaying)
            rightIntroCdViewRef?.get()?.setPlaying(isPlaybackPlaying)
            rightInterludeCdViewRef?.get()?.setPlaying(isPlaybackPlaying)
        }
        cancelReentryVisibilityPoll(resetAttempts = true)

        if (activeMode == RenderMode.NONE) {
            if (canFastResumeWide(prefs)) {
                fastResumeWideMode(prefs)
            } else {
                reconcileState(forceText = true)
            }
            return
        }

        val metrics = evaluateTargetMode()
        logModeDiagIfChanged(metrics)
        if (metrics.mode != activeMode) {
            reconcileState(forceText = true)
            return
        }

        if (metrics.hold) {
            // v1.2.10：已移除 closing_scale_drop 误杀分支，仅保持光晕缩放门控（纯视觉不砍宽）
            syncGlowWithCurrentState(metrics)
            scheduleHoldSettleCheck()
            return
        }
        cancelHoldSettleCheck()

        // 间奏 / 前奏 / 歌词三态严格反向校验
        val needsReassert = when (activeMode) {
            RenderMode.NARROW_V10 -> {
                val sw = switcherRef?.get()
                if (inInterludeMode) {
                    sw != null && sw.visibility != View.GONE
                } else {
                    sw != null && sw.visibility != View.VISIBLE
                }
            }
            RenderMode.WIDE_V11 -> {
                // Q2：仅 padding 漂移时零节流同步写回，不走 80ms 延迟与整套 reconcile
                val leftTemplate = leftTemplateRef?.get()
                val rightTemplate = resolveWaveParent()
                if (leftTemplate != null) {
                    enforceLeftTemplatePaddingSync(leftTemplate, prefs)
                }
                if (rightTemplate != null) {
                    enforceRightTemplatePaddingSync(rightTemplate, prefs)
                }
                val leftInfo = leftInfoContainerRef?.get()
                // F3 + M6 护栏：前置 leftTemplate != null + 250ms 限频，只查 parent 与 visibility，绝不查 alpha
                val nowLeftCheck = SystemClock.uptimeMillis()
                val leftMissingOrHidden = leftTemplate != null && cachedTrackTitle.isNotBlank() && (
                    leftInfo == null ||
                        leftInfo.parent !== leftTemplate ||
                        leftInfo.visibility != View.VISIBLE
                    )
                val elapsedSinceLeftReassert = nowLeftCheck - lastLeftReassertUptimeMs
                val leftNeedsReassert = if (!leftMissingOrHidden) {
                    cancelPendingLeftReassert()
                    false
                } else if (elapsedSinceLeftReassert >= 250L) {
                    cancelPendingLeftReassert()
                    lastLeftReassertUptimeMs = nowLeftCheck
                    true
                } else {
                    // N4：限频命中时挂单次 postDelayed 补检，避免无后续事件时左侧一直缺失（零轮询）
                    schedulePendingLeftReassert((250L - elapsedSinceLeftReassert).coerceAtLeast(16L))
                    false
                }
                val rightView = rightLyricViewRef?.get()
                val rightIntroCd = rightIntroCdViewRef?.get()
                val interludeCd = rightInterludeCdViewRef?.get()
                val wave = musicWaveRef?.get()
                leftNeedsReassert || when {
                    inIntroMode -> {
                        val inWaveLead = currentIntroLearnedMs <= 0L || isInIntroWaveLeadPhase()
                        if (inWaveLead) {
                            val expectedWaveVis = savedWaveVisibility ?: View.VISIBLE
                            (rightIntroCd != null && rightIntroCd.visibility != View.GONE) ||
                                (rightView != null && rightView.visibility != View.GONE) ||
                                (interludeCd != null && interludeCd.visibility != View.GONE) ||
                                (wave != null && wave.visibility != expectedWaveVis)
                        } else {
                            (rightIntroCd == null || rightIntroCd.visibility != View.VISIBLE) ||
                                (rightView != null && rightView.visibility != View.GONE) ||
                                (interludeCd != null && interludeCd.visibility != View.GONE) ||
                                (!soloWaveFadeActive && wave != null && wave.visibility == View.VISIBLE)
                        }
                    }
                    inInterludeMode -> {
                        val expectedWaveVis = savedWaveVisibility ?: View.VISIBLE
                        val expectCd = currentInterludeTargetMs > 0L
                        (rightView != null && rightView.visibility != View.GONE) ||
                            (rightIntroCd != null && rightIntroCd.visibility != View.GONE) ||
                            (wave != null && wave.visibility != expectedWaveVis) ||
                            (expectCd && (interludeCd == null || interludeCd.visibility != View.VISIBLE)) ||
                            (!expectCd && interludeCd != null && interludeCd.visibility != View.GONE)
                    }
                    else -> {
                        val expectLyric = currentLyric.isNotBlank()
                        val expectedWaveVis = if (prefs.persistentWaveEnabled) (savedWaveVisibility ?: View.VISIBLE) else View.GONE
                        (expectLyric && (rightView == null || rightView.visibility != View.VISIBLE || !rightView.hasDisplayContent())) ||
                            (rightIntroCd != null && rightIntroCd.visibility != View.GONE) ||
                            (interludeCd != null && interludeCd.visibility != View.GONE) ||
                            (wave != null && wave.visibility != expectedWaveVis)
                    }
                }
            }
            RenderMode.NONE -> true
        }

        if (needsReassert) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastReassertElapsed >= 80L) {
                lastReassertElapsed = now
                reconcileState(forceText = true)
            }
        } else {
            if (activeMode == RenderMode.WIDE_V11) {
                val prefsChanged = prefs != lastAppliedWidePrefs
                if (prefsChanged) {
                    applyHotPrefsInWideMode(prefs)
                }
                val nowUp = SystemClock.uptimeMillis()
                // v1.3.18：同帧多监听器（左模板/右模板/根窗口）布局回调去重，避免 3ms 内连打 3–4 次树扫描与光晕重绘
                if (!prefsChanged && nowUp - lastStableLayoutCheckUptimeMs < 16L) {
                    return
                }
                lastStableLayoutCheckUptimeMs = nowUp
                enforceUnlockedClipStatesInWide()
                if (metrics.unionVisualPx >= Const.WIDE_TREE_MIN_UNION_PX) {
                    val root = rootWindowRef?.get() ?: leftTemplateRef?.get()?.rootView
                    if (root != null) {
                        logEdgeCheckIfNeeded(root, reason = "stable_wide", prefs = prefs)
                    }
                }
            }
            syncGlowWithCurrentState(metrics)
        }
    }

    private fun schedulePendingLeftReassert(delayMs: Long) {
        if (pendingLeftReassertRunnable != null) return
        val task = Runnable {
            pendingLeftReassertRunnable = null
            renderBreaker.execute {
                if (activeMode != RenderMode.WIDE_V11) return@execute
                val lt = leftTemplateRef?.get() ?: return@execute
                val li = leftInfoContainerRef?.get()
                val stillMissing = cachedTrackTitle.isNotBlank() &&
                    (li == null || li.parent !== lt || li.visibility != View.VISIBLE)
                if (stillMissing) {
                    lastLeftReassertUptimeMs = SystemClock.uptimeMillis()
                    reconcileState(forceText = true)
                }
            }
        }
        pendingLeftReassertRunnable = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun cancelPendingLeftReassert() {
        pendingLeftReassertRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingLeftReassertRunnable = null
    }

    private fun applyHotPrefsInWideMode(prefs: RuntimePrefs) {
        val leftTemplate = leftTemplateRef?.get()
        val rightTemplate = resolveWaveParent()

        leftTitleViewRef?.get()?.setCenterWhenStatic(prefs.leftCenterAlign)
        leftArtistViewRef?.get()?.setCenterWhenStatic(prefs.leftCenterAlign)

        if (leftTemplate != null && rightTemplate != null) {
            val waveView = musicWaveRef?.get()
            val targetRightTemplatePx = computeRightTemplateWidthPx(rightTemplate.context, prefs)
            val targetBaseLyricPx = computeRightLyricWidthPx(rightTemplate.context, prefs)
            val targetLyricPx = computeRightLyricWidthForModePx(rightTemplate.context, prefs, waveView)
            val targetLyricEndMargin = computeRightLyricEndMarginPx(rightTemplate, prefs, waveView)
            val targetLinearLeftMargin = resolveLinearLyricLeftMarginPx(rightTemplate.context, prefs)
            val targetAlbumPad = resolveAlbumLeftPaddingPx(prefs)
            val targetRightPadRight = resolveRightLyricRightInsetPx(rightTemplate.context, prefs)
            val rightView = rightLyricViewRef?.get()
            val rightIntroCd = rightIntroCdViewRef?.get()
            var layoutChanged = false

            val paddingDrifted = leftTemplate.paddingLeft != targetAlbumPad ||
                rightTemplate.paddingLeft != 0 ||
                rightTemplate.paddingRight != targetRightPadRight
            if (rightTemplate.layoutParams?.width != targetRightTemplatePx || paddingDrifted) {
                isInternalUpdate = true
                try {
                    if (rightTemplate.layoutParams?.width != targetRightTemplatePx) {
                        applyExplicitTemplateWidthsForWide(leftTemplate, rightTemplate, prefs)
                    }
                    applyLeftTemplateEdgePadding(leftTemplate, prefs)
                    applyRightTemplateEdgePadding(rightTemplate, prefs)
                    layoutChanged = true
                } finally {
                    isInternalUpdate = false
                }
            }
            leftInfoContainerRef?.get()?.let { lc ->
                val targetLeftW = computeLeftInfoWidthPx(leftTemplate, prefs)
                val albumIcon = findChildByIdName(leftTemplate, Const.ID_ICON_IMAGE_LEFT)
                val targetLeftMarginStart = computeLeftInfoMarginStartForParent(leftTemplate, albumIcon, prefs)
                val lclp = lc.layoutParams
                val marginDiff = lclp is ViewGroup.MarginLayoutParams &&
                    lclp.marginStart != targetLeftMarginStart
                if (lclp != null && (lclp.width != targetLeftW || marginDiff)) {
                    isInternalUpdate = true
                    try {
                        lclp.width = targetLeftW
                        if (lclp is ViewGroup.MarginLayoutParams) {
                            lclp.marginStart = targetLeftMarginStart
                        }
                        lc.layoutParams = lclp
                        layoutChanged = true
                    } finally {
                        isInternalUpdate = false
                    }
                }
            }
            if (!inIntroMode && !inInterludeMode && currentLyric.isNotBlank() && waveView != null) {
                isInternalUpdate = true
                try {
                    if (prefs.persistentWaveEnabled) {
                        // v1.4.5-fix16（Quick 二轮裁决二-1）：未接管且压制中时不许在此放行还原（泄漏同类口）
                        if (!(activeMode == RenderMode.NONE && waveRestoreSuppressed)) {
                            restoreWaveVisibilityIfSaved(waveView, "persistent_wave")
                        }
                    } else {
                        hideMusicWaveWithSnapshot(waveView)
                    }
                    layoutChanged = true
                } finally {
                    isInternalUpdate = false
                }
            }
            if (rightView != null) {
                rightView.setTextSizeSp(prefs.lyricTextSizeSp)
                rightView.setKaraokeEnabled(prefs.karaokeEnabled)
                rightView.setKaraokeSungColor(currentKaraokeSungColor)
                rightView.setDualLineEnabled(prefs.dualLineLyricEnabled)
                rightView.setSubTextSizeSp(prefs.dualLineSubTextSizeSp)
                rightView.setCenterShortText(prefs.lyricCenterAlign)
                rightView.setLineDissolveEnabled(prefs.lineDissolveEnabled)
                        rightView.setLyricStyle(prefs.lyricStyle)
                        rightView.setLineTransitionMode(prefs.lineTransitionMode)
                rightView.setInterludeDotsEnabled(prefs.interludeDotsEnabled)
                rightView.setPreferredWidthPx(targetLyricPx)
                val rlp = rightView.layoutParams
                val leftMarginChanged = rightTemplate is LinearLayout &&
                    rlp is ViewGroup.MarginLayoutParams &&
                    rlp.leftMargin != targetLinearLeftMargin
                val endMarginChanged = rlp is ViewGroup.MarginLayoutParams &&
                    (if (rightTemplate is LinearLayout) rlp.rightMargin != targetLyricEndMargin else rlp.marginEnd != targetLyricEndMargin)
                if (rlp != null && (rlp.width != targetLyricPx || leftMarginChanged || endMarginChanged)) {
                    isInternalUpdate = true
                    try {
                        rlp.width = targetLyricPx
                        if (rlp is ViewGroup.MarginLayoutParams) {
                            if (rightTemplate is LinearLayout) {
                                rlp.leftMargin = targetLinearLeftMargin
                                rlp.rightMargin = targetLyricEndMargin
                            } else {
                                rlp.marginEnd = targetLyricEndMargin
                            }
                        }
                        rightView.layoutParams = rlp
                        layoutChanged = true
                    } finally {
                        isInternalUpdate = false
                    }
                }
            }
            if (rightIntroCd != null) {
                rightIntroCd.setPreferredWidthPx(targetBaseLyricPx)
                val ilp = rightIntroCd.layoutParams
                val marginChanged = rightTemplate is LinearLayout &&
                    ilp is ViewGroup.MarginLayoutParams &&
                    ilp.leftMargin != targetLinearLeftMargin
                if (ilp != null && (ilp.width != targetBaseLyricPx || marginChanged)) {
                    isInternalUpdate = true
                    try {
                        ilp.width = targetBaseLyricPx
                        if (rightTemplate is LinearLayout && ilp is ViewGroup.MarginLayoutParams) {
                            ilp.leftMargin = targetLinearLeftMargin
                        }
                        rightIntroCd.layoutParams = ilp
                        layoutChanged = true
                    } finally {
                        isInternalUpdate = false
                    }
                }
            }
            val prefsChanged = prefs != lastAppliedWidePrefs
            lastAppliedWidePrefs = prefs
            // S1/S2/S5：热滑杆变更立即同步触发一次视觉闭环锁，确保整块偏移滑杆零延迟生效
            applyPreDrawVisualLock()
            if (layoutChanged) {
                leftTemplate.requestLayout()
                rightTemplate.requestLayout()
                (rightTemplate.parent as? View)?.requestLayout()
                capRightRef?.get()?.requestLayout()
            }
            if (prefsChanged || layoutChanged) {
                val root = rootWindowRef?.get() ?: leftTemplate.rootView
                if (root != null) {
                    mainHandler.post {
                        if (activeMode == RenderMode.WIDE_V11) {
                            applyPreDrawVisualLock()
                            logEdgeCheckIfNeeded(root, reason = "hot_pref_changed", prefs = readPrefs())
                        }
                    }
                }
            }
        }
    }

    /** v1.3.20：校验是否满足返场快恢复白名单（同模板、同曲目、同配置、播放中、已知宽态） */
    private fun canFastResumeWide(prefs: RuntimePrefs): Boolean {
        val state = fastResumeState ?: return false
        if (!prefs.masterEnabled || !lastConfirmedWide || !isEffectivelyPlaying() || !sessionActive) {
            clearFastResumeState()
            return false
        }
        if (state.prefs != prefs || state.trackKey != currentTrackKey()) {
            clearFastResumeState()
            return false
        }
        val leftTemplate = leftTemplateRef?.get()
        val rightTemplate = resolveWaveParent()
        val savedLeft = state.leftTemplateRef.get()
        val savedRight = state.rightTemplateRef.get()
        if (leftTemplate == null || rightTemplate == null ||
            leftTemplate !== savedLeft || rightTemplate !== savedRight ||
            !leftTemplate.isAttachedToWindow || !rightTemplate.isAttachedToWindow
        ) {
            clearFastResumeState()
            return false
        }
        if (currentLyric.isBlank() && !inInterludeMode && !inIntroMode) {
            clearFastResumeState()
            return false
        }
        return true
    }

    /** v1.3.20：单帧单次事务直达原宽态——免 reconcile 全套、免正测量轮询、免双次写宽，左侧复用已挂容器、右侧增量同步到当前进度 */
    private fun fastResumeWideMode(prefs: RuntimePrefs) {
        // v1.4.5-fix14 L-M2：快速恢复不走 applyWideMode 的 NONE 分支，[TAKEOVER] 在此补打并消费落地时间戳
        val frStamps = consumeLandStamps()
        val frMsg = "[TAKEOVER] via=fast_resume landToTakeoverMs=${frStamps.landMs} passToTakeoverMs=${frStamps.passMs} firstPassToTakeoverMs=${frStamps.firstPassMs} blockers=${frStamps.seq}"
        YLog.info(msg = frMsg)
        EventHistoryStore.recordDiagOnly(frMsg)
        clearFastResumeState()
        cancelPositiveMeasurePoll()
        cancelHoldSettleCheck()
        cancelPendingHideSettle()
        cancelWideEntryWidthSamples()
        cancelReentryVisibilityPoll(resetAttempts = true)
        awaitingValidVisibleReentry = false
        wideGeneration++
        lastSyncWideBindUptimeMs = SystemClock.uptimeMillis()
        lastAppliedWidePrefs = prefs

        applyWideMode(forceText = true, prefs = prefs)

        val rootForLock = rootWindowRef?.get() ?: leftTemplateRef?.get()?.rootView
        if (rootForLock != null) {
            registerPreDrawVisualLock(rootForLock)
            applyPreDrawVisualLock()
            val minScale = computeCurrentCapsMinScale(rootForLock)
            if (minScale >= Const.GLOW_AND_LOCK_MIN_SCALE) {
                lastExpandedElapsedMs = 0L
                syncGlowWithCurrentState()
            } else {
                glowRenderer.hideAndStop("fast_resume_scale_hold")
                scheduleHoldSettleCheck()
            }
        } else {
            syncGlowWithCurrentState()
        }
    }

    private fun reconcileState(forceText: Boolean) {
        com.vivo.islandlyric.util.PerfStats.reconcileOther.incrementAndGet()
        val prefs = readPrefs()
        // vc56：息屏接收器惰性注册（首次有上下文时注册一次，失败下次 reconcile 再试）
        ensureScreenReceiverRegistered()
        // vc56（Hark 确认轮遗留）：接收器注册失败且 30s 安全网已投过时，亮屏后靠 reconcile 解开息屏延期
        //（布尔短路：接收器正常时零成本；亮屏后岛重挂必触发 reconcile，正好接住）
        if (waveFallbackGateDeferred && !screenReceiverRegistered && !isScreenOffNow()) {
            rerunWaveFallbackIfGateDeferred()
        }
        // vc55：横屏闸门已解除且此前藏过原厂岛 → 先还原可见性再走正常流程
        if (!landscapeGateActive(prefs)) {
            unhideNativeIslandAfterLandscapeGate()
        }
        if (!prefs.masterEnabled) {
            lastConfirmedWide = false
            if (activeMode != RenderMode.NONE) restoreAllToFactory("master_switch_off")
            else {
                cancelPositiveMeasurePoll()
                cancelHoldSettleCheck()
                cancelIntroWaveLeadEndCheck()
                cancelReentryVisibilityPoll(resetAttempts = true)
            }
            return
        }

        // vc55 横屏总开关（Quick 口径：放在账本与兜底逻辑之前）：横屏非歌词岛模式 → 还原一次并直接返回。
        // 默认岛＝完全让位原厂；摘岛＝同样让位且不接管（连原厂一起藏的可见性写入在独立 hideNativeIslandForLandscapeGate 处理）
        if (landscapeGateActive(prefs)) {
            if (activeMode != RenderMode.NONE) {
                restoreAllToFactory("landscape_gate")
            } else {
                cancelPositiveMeasurePoll()
                cancelHoldSettleCheck()
                cancelIntroWaveLeadEndCheck()
                cancelReentryVisibilityPoll(resetAttempts = true)
            }
            if (prefs.landscapeMode == Const.LANDSCAPE_MODE_OFF) {
                hideNativeIslandForLandscapeGate()
            } else {
                // vc55（门检 L1 补）：摘岛→默认岛切换时把先前藏起的原厂岛放回来（闸门仍开、顶部还原检查不会跑到）
                unhideNativeIslandAfterLandscapeGate()
            }
            return
        }

        if (!shouldShowOnCollapsedCapsule(prefs)) {
            if (activeMode != RenderMode.NONE) {
                restoreAllToFactory("not_collapsed_music_capsule")
            } else {
                cancelPositiveMeasurePoll()
                cancelHoldSettleCheck()
                cancelIntroWaveLeadEndCheck()
                if (isAwaitingVisibleReentry(prefs)) {
                    scheduleReentryVisibilityPoll()
                } else {
                    cancelReentryVisibilityPoll(resetAttempts = true)
                }
            }
            return
        }
        cancelReentryVisibilityPoll(resetAttempts = true)

        // A 项定版机制：收起首帧若已知上次为宽态（activeMode==NONE && lastConfirmedWide），
        // 即使正处于 scale<0.95 等待正测量期，也在同栈立即预写显式宽与 8px 贴边，杜绝先短后长
        if (activeMode == RenderMode.NONE && lastConfirmedWide) {
            preWriteWideConstraintsInStack(prefs)
        }

        val metrics = evaluateTargetMode()
        logModeDiagIfChanged(metrics)

        when (metrics.mode) {
            RenderMode.WIDE_V11 -> {
                cancelPositiveMeasurePoll()
                cancelPendingHideSettle()
                val enteringWide = activeMode != RenderMode.WIDE_V11
                if (enteringWide) {
                    wideGeneration++
                }
                lastSyncWideBindUptimeMs = SystemClock.uptimeMillis()
                lastAppliedWidePrefs = prefs
                if (activeMode == RenderMode.NARROW_V10) {
                    restoreNarrowSwitcherToFactory("switch_to_wide")
                }
                if (!metrics.hold) {
                    cancelHoldSettleCheck()
                    // S3 边界补强：收起落地并稳定进入宽态（scale >= 0.95）即刻清零展开时间窗，防紧接着关音乐被误判
                    lastExpandedElapsedMs = 0L
                }
                val desiredRightState = resolveDesiredRightState()
                if (metrics.hold && !enteringWide) {
                    // v1.3.13：缩放动画期放行右侧三态内容切换，不再吞掉前奏/间奏/歌词更新；光晕仍由 hold 门控
                    if (forceText || desiredRightState != lastRenderedRightState) {
                        applyWideMode(true, prefs)
                    }
                    syncGlowWithCurrentState(metrics)
                    scheduleHoldSettleCheck()
                } else {
                    applyWideMode(forceText || enteringWide, prefs)
                    val rootForLock = rootWindowRef?.get() ?: leftTemplateRef?.get()?.rootView
                    if (rootForLock != null) {
                        registerPreDrawVisualLock(rootForLock)
                        applyPreDrawVisualLock()
                    }
                    syncGlowWithCurrentState(metrics)
                    if (metrics.unionVisualPx >= Const.WIDE_TREE_MIN_UNION_PX) {
                        val root = rootWindowRef?.get() ?: leftTemplateRef?.get()?.rootView
                        if (root != null) {
                            mainHandler.post {
                                if (activeMode == RenderMode.WIDE_V11) {
                                    logEdgeCheckIfNeeded(root, reason = "enter_wide", prefs = readPrefs())
                                }
                            }
                        }
                    }
                    if (enteringWide) {
                        scheduleWideEntryWidthSamples()
                    }
                }
            }
            RenderMode.NARROW_V10 -> {
                cancelPositiveMeasurePoll()
                // v1.4.5-fix12 F12-1 释放点（Quick 实施审查 M1 修正）：已定走窄态、快速恢复不可能再发生——
                // 先清快速恢复状态（残留会拦住还原、令窄态波浪等兜底 5~15s 才出现，属 fix10 回归），再无条件还原；
                // 重入模式若还开着一并注销监听退出，免留空转重入
                if (waveRestoreSuppressed && waveSuppressKind == "expand") {
                    // v1.4.5-fix13 A（Quick 裁决必须改 2）：展开类在窄态分支不立即放——收起变形中途并集宽度会扫过
                    // 窄态阈值、短暂判成 NARROW 就地还原正是漏放嫌疑第一位。改双确认：本帧只武装复核，复核时刷新
                    // 阻断原因（高度判据即在其中），仍非展开类才释放；变形未完则 120ms 自重投复核（上限 16 次约 2s），
                    // 既不在中途漏放、也不让真窄态的波浪干等兜底（保住 fix12 在此加立即还原的本意）
                    if (!narrowSuppressReleasePending) {
                        narrowSuppressReleasePending = true
                        narrowSuppressReleaseAttempts = 0
                        val task = object : Runnable {
                            override fun run() {
                                narrowSuppressReleaseRunnable = null
                                if (!waveRestoreSuppressed || waveSuppressKind != "expand") {
                                    narrowSuppressReleasePending = false
                                    return
                                }
                                if (activeMode == RenderMode.WIDE_V11) {
                                    narrowSuppressReleasePending = false
                                    return
                                }
                                shouldShowOnCollapsedCapsule(readPrefs())
                                if (!isExpandBlocker() && !isIslandPhysicallyExpanded()) {
                                    narrowSuppressReleasePending = false
                                    clearFastResumeState()
                                    if (preDrawReentryMode) {
                                        unregisterPreDrawVisualLock()
                                    }
                                    // v1.4.5-fix20.3 C5：统一走 releaseSuppressNow（带 suppressedWaveRef 回退，防脱落后漏放）
                                    releaseSuppressNow("narrow_confirmed")
                                    return
                                }
                                narrowSuppressReleaseAttempts++
                                if (narrowSuppressReleaseAttempts < 16) {
                                    narrowSuppressReleaseRunnable = this
                                    com.vivo.islandlyric.util.PerfStats.delayedNarrow.incrementAndGet()
                                    mainHandler.postDelayed(this, 120L)
                                } else {
                                    // v1.4.5-fix13 实施审 A-M1：复核 2s 仍判展开类，说明阻断判据本身已不可信——
                                    // 宁可强制释放，也不能让窄胶囊波浪永久 INVISIBLE（兜底在 NARROW 不兜这条）
                                    narrowSuppressReleasePending = false
                                    clearFastResumeState()
                                    if (preDrawReentryMode) {
                                        unregisterPreDrawVisualLock()
                                    }
                                    // v1.4.5-fix20.3 C5：统一走 releaseSuppressNow（带 suppressedWaveRef 回退，防脱落后漏放）
                                    releaseSuppressNow("narrow_exhausted")
                                }
                            }
                        }
                        narrowSuppressReleaseRunnable = task
                        com.vivo.islandlyric.util.PerfStats.delayedNarrow.incrementAndGet()
                        mainHandler.postDelayed(task, 90L)
                    }
                } else if (waveRestoreSuppressed) {
                    clearFastResumeState()
                    if (preDrawReentryMode) {
                        unregisterPreDrawVisualLock()
                    }
                    // v1.4.5-fix20.3 C5：统一走 releaseSuppressNow（带 suppressedWaveRef 回退，防脱落后漏放）
                    releaseSuppressNow("narrow_immediate")
                }
                if (activeMode == RenderMode.WIDE_V11) {
                    restoreWideLeftToFactory()
                    restoreWideRightToFactory("switch_to_narrow")
                    restoreContainerWidthConstraints("switch_to_narrow")
                    restoreLeftTemplatePadding()
                    restoreRightTemplatePadding()
                }
                if (!metrics.hold) {
                    cancelHoldSettleCheck()
                }
                if (metrics.hold && activeMode == RenderMode.NARROW_V10) {
                    if (forceText) {
                        applyNarrowMode(true)
                    }
                    syncGlowWithCurrentState()
                    scheduleHoldSettleCheck()
                } else {
                    applyNarrowMode(forceText || activeMode != RenderMode.NARROW_V10)
                    syncGlowWithCurrentState()
                }
            }
            RenderMode.NONE -> {
                if (activeMode == RenderMode.NONE) {
                    schedulePositiveMeasurePoll()
                }
            }
        }
    }

    private fun preWriteWideConstraintsInStack(prefs: RuntimePrefs) {
        if (suppressPreWriteUntilPlay || !isEffectivelyPlaying()) return
        val wave = musicWaveRef?.get()
        if (!((wave != null && wave.isAttachedToWindow) || musicWaveAttached)) return
        val leftTemplate = leftTemplateRef?.get() ?: return
        val rightTemplate = resolveWaveParent() ?: return

        // v1.2.10：仅播放态（isPlaybackPlaying && !suppressPreWriteUntilPlay）全场景放行首帧预写 333px，
        // 无论从展开卡、控制中心还是多任务返回桌面均一次到位；非播放态已在入口直接拦截，绝不回弹。
        awaitingValidVisibleReentry = false
        isInternalUpdate = true
        try {
            applyExplicitTemplateWidthsForWide(leftTemplate, rightTemplate, prefs)
            applyLeftTemplateEdgePadding(leftTemplate, prefs)
            applyRightTemplateEdgePadding(rightTemplate, prefs)
            // F3：预写宽约束同栈补注左侧歌名歌手，消除「先撑宽、左空黑」窗口（不收紧 preWrite 入口条件）
            if (cachedTrackTitle.isNotBlank()) {
                ensureLeftInfoInjected(leftTemplate, prefs)
                updateLeftInfoContentLocked(prefs, allowAlphaRestore = false)
            }
            leftTemplate.requestLayout()
            rightTemplate.requestLayout()
            (leftTemplate.parent as? View)?.requestLayout()
            (rightTemplate.parent as? View)?.requestLayout()
            capLeftRef?.get()?.requestLayout()
            capRightRef?.get()?.requestLayout()
        } finally {
            isInternalUpdate = false
        }
    }

    private fun scheduleWideEntryWidthSamples() {
        cancelWideEntryWidthSamples()
        val capturedGen = wideGeneration
        val offsets = longArrayOf(80L, 260L)
        for (delayMs in offsets) {
            val task = Runnable {
                renderBreaker.execute {
                    if (capturedGen != wideGeneration || activeMode != RenderMode.WIDE_V11) return@execute
                    val leftTemplate = leftTemplateRef?.get() ?: return@execute
                    val root = rootWindowRef?.get() ?: leftTemplate.rootView ?: return@execute
                    val capLeft = capLeftRef?.get()
                    val capRight = capRightRef?.get()
                    val lRect = resolveVisualRectInRoot(capLeft, root)
                    val rRect = resolveVisualRectInRoot(capRight, root)
                    val density = leftTemplate.resources.displayMetrics.density
                    val unionPx = if (lRect != null && rRect != null) {
                        max(lRect.right, rRect.right) - min(lRect.left, rRect.left)
                    } else {
                        -1f
                    }
                    val unionDp = if (unionPx >= 0f) unionPx / density else -1f
                    val sampleMsg = "[CAPSULE_MODE_DIAG] sampleT=+${delayMs}ms | mode=$activeMode | " +
                        "unionVisual=${String.format(Locale.US, "%.1f", unionPx)}px(${String.format(Locale.US, "%.1f", unionDp)}dp) | " +
                        "capL=${formatRectF(lRect)} | capR=${formatRectF(rRect)}"
                    YLog.info(msg = sampleMsg)
                    EventHistoryStore.recordDiagOnly(sampleMsg)
                }
            }
            wideEntrySampleRunnables.add(task)
            mainHandler.postDelayed(task, delayMs)
        }
    }

    // v1.4.5-fix16：[PREFS] 变更检测键（流光/诊断相关项变化才打一行，定 01:09:27 流光停摆类问题）
    private var lastPrefsLogKey: String = ""

    // v1.4.5-fix17：已处理的清空时间戳（设置页「清空日志」经 prefs 传到岛进程）
    // vc55（fix21）：初值 -1L 哨兵——新进程首次同步只对齐基线不清行，否则用户历史上点过一次「清空日志」后，
    // 每个新进程一进宽态就误触 clearAll、把未落盘的启动行（[PROC]/[EXIT_INFO] 等）一锅端（2026-11-25 已回源码坐实）
    private var lastDiagClearAt: Long = -1L

    private fun syncGlowWithCurrentState(metrics: ModeMetrics? = null) {
        val prefs = readPrefs()
        EventHistoryStore.detailedDiag = prefs.diagDetailed
        EventHistoryStore.logEnabled = prefs.diagLogEnabled
        // v1.4.5-fix17：设置页清空日志——时间戳变了就清岛进程侧的内存环与队列（文件本体由设置页同进程截断）
        // vc55（fix21）：首次同步（哨兵 -1）只对齐基线、不清行；之后时间戳再变才真清
        if (lastDiagClearAt < 0L) {
            lastDiagClearAt = prefs.diagClearAt
        } else if (prefs.diagClearAt > lastDiagClearAt) {
            lastDiagClearAt = prefs.diagClearAt
            if (prefs.diagClearAt > 0L) {
                EventHistoryStore.clearAll()
                val cMsg = "[DIAG] cleared by settings clearAt=${prefs.diagClearAt}"
                YLog.info(msg = cMsg)
            }
        }
        val prefsKey = "master=${prefs.masterEnabled}|island=${prefs.islandShimmerEnabled}|cd=${prefs.shimmerCountdownEnabled}" +
            "|style=${prefs.shimmerStyle}|level=${prefs.shimmerLevel}|diagDetailed=${prefs.diagDetailed}|diagLog=${prefs.diagLogEnabled}"
        if (prefsKey != lastPrefsLogKey) {
            if (lastPrefsLogKey.isNotEmpty()) {
                val pMsg = "[PREFS] island=${prefs.islandShimmerEnabled} countdown=${prefs.shimmerCountdownEnabled} " +
                    "style=${prefs.shimmerStyle} level=${prefs.shimmerLevel} diagDetailed=${prefs.diagDetailed} diagLog=${prefs.diagLogEnabled}"
                YLog.info(msg = pMsg)
                EventHistoryStore.recordDiagOnly(pMsg)
            }
            lastPrefsLogKey = prefsKey
        }
        // v1.4.5-fix14 C-S1＋追加裁决：总开关关、或整岛与倒计时两路开关都关时，才真正摘除流光子 View；
        // 其余门控关闭（暂停/收起）只停画不拆
        if (!prefs.masterEnabled || (!prefs.islandShimmerEnabled && !prefs.shimmerCountdownEnabled)) {
            islandShimmer.detachAll("prefs_off")
        }
        val root = rootWindowRef?.get() ?: leftTemplateRef?.get()?.rootView
        val scaleReady = when {
            metrics != null -> {
                !metrics.hold &&
                    min(min(metrics.scaleLX, metrics.scaleLY), min(metrics.scaleRX, metrics.scaleRY)) >= Const.GLOW_AND_LOCK_MIN_SCALE
            }
            root != null -> computeCurrentCapsMinScale(root) >= Const.GLOW_AND_LOCK_MIN_SCALE
            else -> false
        }
        val visible = shouldShowOnCollapsedCapsule(prefs) && activeMode != RenderMode.NONE && scaleReady
        if (!visible) {
            glowRenderer.hideAndStop("scale_or_state_hide")
            islandShimmer.sync(
                active = false, gateOk = false, root = null, capLeft = null, capRight = null,
                style = prefs.shimmerStyle, level = prefs.shimmerLevel,
                accentColor = currentKaraokeSungColor
            )
            return
        }
        glowRenderer.syncState(
            visible = true,
            isPlaying = isPlaybackPlaying,
            isWideMode = (activeMode == RenderMode.WIDE_V11),
            root = root,
            capLeft = capLeftRef?.get(),
            capRight = capRightRef?.get()
        )
        // v1.4.5-fix14 合并层：整岛入口只存输入（想亮＝整岛开关开 && 宽态 && 播放中），gateOk＝本函数 visible
        // 基线（shouldShow && scaleReady）；倒计时一路走 syncCountdown、由 Manager.resolve 统一仲裁，
        // fix13 的右壳让位与 countdownOnRight 推算（漏间奏）已按追加裁决作废删除
        islandShimmer.sync(
            active = prefs.islandShimmerEnabled && activeMode == RenderMode.WIDE_V11,
            // v1.4.5-fix14 实施审 M2：播放中并入 gateOk 基线，两路共用——倒计时段暂停时合并层同样停（原视图行为）
            gateOk = isPlaybackPlaying,
            root = root,
            capLeft = capLeftRef?.get(),
            capRight = capRightRef?.get(),
            style = prefs.shimmerStyle,
            level = prefs.shimmerLevel,
            accentColor = currentKaraokeSungColor
        )
    }

    private fun evaluateTargetMode(): ModeMetrics {
        val leftTemplate = leftTemplateRef?.get()
            ?: return ModeMetrics(RenderMode.NONE, 0f, 0f, null, null, true, 1f, 1f, 1f, 1f)
        val root = rootWindowRef?.get() ?: leftTemplate.rootView
            ?: return ModeMetrics(RenderMode.NONE, 0f, 0f, null, null, true, 1f, 1f, 1f, 1f)

        val capLeft = capLeftRef?.get()
        val capRight = capRightRef?.get()
        val density = leftTemplate.resources.displayMetrics.density

        val leftRect = resolveVisualRectInRoot(capLeft, root)
        val rightRect = resolveVisualRectInRoot(capRight, root)
        val scaleLX = effectiveScaleX(capLeft, root)
        val scaleLY = effectiveScaleY(capLeft, root)
        val scaleRX = effectiveScaleX(capRight, root)
        val scaleRY = effectiveScaleY(capRight, root)
        val hasWaveParent = resolveWaveParent() != null

        val unionVisualPx = if (leftRect != null && rightRect != null) {
            max(leftRect.right, rightRect.right) - min(leftRect.left, rightRect.left)
        } else {
            0f
        }
        val unionVisualDp = unionVisualPx / density

        val isScalingAnim = scaleLX < Const.CAP_ANIM_SCALE_FREEZE_THRESHOLD ||
            scaleLY < Const.CAP_ANIM_SCALE_FREEZE_THRESHOLD ||
            scaleRX < Const.CAP_ANIM_SCALE_FREEZE_THRESHOLD ||
            scaleRY < Const.CAP_ANIM_SCALE_FREEZE_THRESHOLD

        val positiveMeasureReady = leftRect != null && rightRect != null && hasWaveParent && !isScalingAnim

        if (!positiveMeasureReady) {
            return ModeMetrics(
                mode = activeMode,
                unionVisualPx = unionVisualPx,
                unionVisualDp = unionVisualDp,
                capLeftRect = leftRect,
                capRightRect = rightRect,
                hold = true,
                scaleLX = scaleLX,
                scaleLY = scaleLY,
                scaleRX = scaleRX,
                scaleRY = scaleRY
            )
        }

        val targetMode = when {
            unionVisualDp >= Const.WIDE_UNION_ENTER_DP -> {
                isWideLatched = true
                lastConfirmedWide = true
                RenderMode.WIDE_V11
            }
            unionVisualDp < Const.WIDE_UNION_EXIT_DP -> {
                isWideLatched = false
                lastConfirmedWide = false
                RenderMode.NARROW_V10
            }
            else -> {
                if (isWideLatched || activeMode == RenderMode.WIDE_V11) {
                    isWideLatched = true
                    lastConfirmedWide = true
                    RenderMode.WIDE_V11
                } else {
                    isWideLatched = false
                    lastConfirmedWide = false
                    RenderMode.NARROW_V10
                }
            }
        }

        return ModeMetrics(
            mode = targetMode,
            unionVisualPx = unionVisualPx,
            unionVisualDp = unionVisualDp,
            capLeftRect = leftRect,
            capRightRect = rightRect,
            hold = false,
            scaleLX = scaleLX,
            scaleLY = scaleLY,
            scaleRX = scaleRX,
            scaleRY = scaleRY
        )
    }

    private fun schedulePositiveMeasurePoll() {
        if (positiveMeasurePollRunnable != null) return
        val task = Runnable {
            positiveMeasurePollRunnable = null
            com.vivo.islandlyric.util.PerfStats.reconcileReentry.incrementAndGet()
            renderBreaker.execute {
                if (activeMode == RenderMode.NONE && sessionActive && isPlaybackPlaying) {
                    reconcileState(forceText = true)
                }
            }
        }
        positiveMeasurePollRunnable = task
        com.vivo.islandlyric.util.PerfStats.delayedReentry.incrementAndGet()
        mainHandler.postDelayed(task, 120L)
    }

    private fun cancelPositiveMeasurePoll() {
        positiveMeasurePollRunnable?.let { mainHandler.removeCallbacks(it) }
        positiveMeasurePollRunnable = null
    }

    private fun scheduleHoldSettleCheck() {
        if (holdSettleRunnable != null) return
        val task = Runnable {
            holdSettleRunnable = null
            renderBreaker.execute {
                if (activeMode != RenderMode.NONE && sessionActive && isPlaybackPlaying) {
                    reconcileState(forceText = false)
                }
            }
        }
        holdSettleRunnable = task
        mainHandler.postDelayed(task, Const.HOLD_SETTLE_DELAY_MS)
    }

    private fun cancelHoldSettleCheck() {
        holdSettleRunnable?.let { mainHandler.removeCallbacks(it) }
        holdSettleRunnable = null
    }

    /** v1.3.14：判断是否仅因祖先容器尚未翻 VISIBLE（isShown=false）而暂未进宽态，排除展开卡与非播放态 */
    private fun isAwaitingVisibleReentry(prefs: RuntimePrefs): Boolean {
        if (!prefs.masterEnabled || !sessionActive || !isPlaybackPlaying) return false
        if (currentLyric.isBlank() && !inInterludeMode && !inIntroMode) return false
        val leftTemplate = leftTemplateRef?.get() ?: return false
        if (!leftTemplate.isAttachedToWindow) return false
        val root = rootWindowRef?.get() ?: leftTemplate.rootView
        if (root != null && root.height > Const.ISLAND_EXPANDED_HEIGHT_THRESHOLD_PX) return false
        val capExpanded = capExpandedRef?.get()
        if (capExpanded != null && capExpanded.visibility == View.VISIBLE && capExpanded.height > 0) return false
        val wave = findMusicWaveInSameCapsule(leftTemplate)
            ?: musicWaveRef?.get()?.takeIf { isSameCapsule(leftTemplate, it) }
            ?: return false
        val waveParent = wave.parent as? View ?: return false
        if (!wave.isAttachedToWindow || !waveParent.isAttachedToWindow) return false
        return !leftTemplate.isShown || !waveParent.isShown || !wave.isShown
    }

    /** v1.3.14：上限 3 次 × 120ms（最多 360ms）短窗单发复检，360ms 仍不可见自动停止，零无限轮询 */
    private fun scheduleReentryVisibilityPoll() {
        if (reentryVisibilityPollRunnable != null) return
        if (reentryVisibilityPollAttempts >= 3) return
        reentryVisibilityPollAttempts += 1
        val task = Runnable {
            reentryVisibilityPollRunnable = null
            renderBreaker.execute {
                if (activeMode == RenderMode.NONE && sessionActive && isPlaybackPlaying) {
                    reconcileState(forceText = true)
                }
            }
        }
        reentryVisibilityPollRunnable = task
        mainHandler.postDelayed(task, 120L)
    }

    private fun cancelReentryVisibilityPoll(resetAttempts: Boolean) {
        reentryVisibilityPollRunnable?.let { mainHandler.removeCallbacks(it) }
        reentryVisibilityPollRunnable = null
        if (resetAttempts) {
            reentryVisibilityPollAttempts = 0
        }
    }

    // v1.4.5-fix10 F10-2/F10-3：波浪段时长统一纯函数（前奏与间奏共用，用户 2026-11-24 改定，Quick 裁决整数口径）：
    // 段长 ≤10s → 4s；10s<段长<20s → 6s；≥20s → 8s＋每多 10s 加 6s（整数 ms 运算）；段长 ≤ 波浪时长则全程波浪
    private fun waveLeadMs(segLenMs: Long): Long = when {
        segLenMs >= Const.INTRO_WAVE_LEAD_LONG_THRESHOLD_MS ->
            Const.INTRO_WAVE_LEAD_LONG_MS + 6_000L * ((segLenMs - Const.INTRO_WAVE_LEAD_LONG_THRESHOLD_MS) / 10_000L)
        segLenMs > Const.INTRO_WAVE_LEAD_MEDIUM_THRESHOLD_MS -> Const.INTRO_WAVE_LEAD_MEDIUM_MS
        else -> Const.INTRO_WAVE_LEAD_SHORT_MS
    }

    private fun resolveIntroWaveLeadMs(introTotalMs: Long): Long = waveLeadMs(introTotalMs)

    private fun isInIntroWaveLeadPhase(): Boolean {
        if (!inIntroMode || currentIntroLearnedMs <= 0L) return false
        val curPos = positionProvider()
        // v1.4.5-fix7 v3：前奏到点后保留 1500ms 衔接宽限（覆盖在线 +350ms 与无时间轴学习值 +1200ms），与 InterludeCountdownView 撤字阈值对齐
        if (curPos >= 0L && currentIntroLearnedMs - curPos <= -1_500L) return true // 仓侧直修（Quick 必改 M1）：与 InterludeCountdownView 撤字条件 <= -1500 两侧统一，防恰达边界时卡空白倒计时
        // X4 + v1.4.5-fix3：统一在单一出口消费武装期锁定的 forceWaveOnly / skipWaveLead，避免 applyWideMode 与 needsReapply 口径分裂
        if (introForceWaveOnly) return true
        if (introSkipWaveLead) return false
        val waveLeadMs = resolveIntroWaveLeadMs(currentIntroLearnedMs)
        return curPos < 0L || curPos < waveLeadMs
    }

    // v1.4.5-fix10 F10-3：间奏波浪子相位（INTERLUDE 内部相位，activeMode 不变、不释放接管）：
    // 段界未知或拖回开头跳波浪时恒 false（直接数字）；段长 ≤ 波浪时长则全程 true（全程波浪）
    private fun isInInterludeWavePhase(): Boolean {
        if (!inInterludeMode || interludeSkipWaveLead) return false
        val start = currentInterludeStartMs
        val target = currentInterludeTargetMs
        if (start < 0L || target <= start) return false
        val gapMs = target - start
        val waveMs = waveLeadMs(gapMs)
        if (gapMs <= waveMs) return true
        val curPos = positionProvider()
        if (curPos < 0L) return false
        return (curPos - start) < waveMs
    }

    private fun resolveDesiredRightState(): Int = when {
        inIntroMode -> if (currentIntroLearnedMs > 0L && !isInIntroWaveLeadPhase()) 2 else 3
        inInterludeMode -> if (isInInterludeWavePhase()) 4 else 1
        else -> 0
    }

    private fun scheduleInterludeWaveEndCheck() {
        cancelInterludeWaveEndCheck()
        if (!inInterludeMode || !isPlaybackPlaying) return
        if (interludeSkipWaveLead) return
        val start = currentInterludeStartMs
        val target = currentInterludeTargetMs
        if (start < 0L || target <= start) return
        val gapMs = target - start
        val waveMs = waveLeadMs(gapMs)
        if (gapMs <= waveMs) return
        val curPos = positionProvider()
        val elapsed = if (curPos >= 0L) curPos - start else -1L
        val delayMs = when {
            elapsed < 0L -> 250L
            elapsed < waveMs -> (waveMs - elapsed).coerceAtLeast(16L)
            else -> return
        }
        val task = Runnable {
            interludeWaveEndRunnable = null
            renderBreaker.execute {
                if (!inInterludeMode || !sessionActive || !isPlaybackPlaying) return@execute
                if (isInInterludeWavePhase()) {
                    scheduleInterludeWaveEndCheck()
                } else {
                    reconcileState(forceText = true)
                }
            }
        }
        interludeWaveEndRunnable = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun cancelInterludeWaveEndCheck() {
        interludeWaveEndRunnable?.let { mainHandler.removeCallbacks(it) }
        interludeWaveEndRunnable = null
    }

    private fun scheduleIntroWaveLeadEndCheck() {
        cancelIntroWaveLeadEndCheck()
        if (!inIntroMode || !isPlaybackPlaying || currentIntroLearnedMs <= 0L) return
        if (introForceWaveOnly || introSkipWaveLead) return
        val waveLeadMs = resolveIntroWaveLeadMs(currentIntroLearnedMs)
        // 短于波浪段的迷你前奏全程保持波浪，无需预约切倒计时
        if (currentIntroLearnedMs <= waveLeadMs) return
        val curPos = positionProvider()
        val delayMs = when {
            curPos < 0L -> 250L
            curPos < waveLeadMs -> (waveLeadMs - curPos).coerceAtLeast(16L)
            else -> return
        }
        val task = Runnable {
            introWaveLeadEndRunnable = null
            renderBreaker.execute {
                if (!inIntroMode || !sessionActive || !isPlaybackPlaying) return@execute
                if (isInIntroWaveLeadPhase()) {
                    scheduleIntroWaveLeadEndCheck()
                } else {
                    rightLyricViewRef?.get()?.setInterludeCountdownBlocking(true)
                    reconcileState(forceText = true)
                }
            }
        }
        introWaveLeadEndRunnable = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun cancelIntroWaveLeadEndCheck() {
        introWaveLeadEndRunnable?.let { mainHandler.removeCallbacks(it) }
        introWaveLeadEndRunnable = null
    }

    private fun resolveVisualRectInRoot(view: View?, root: View): RectF? {
        if (view == null || !view.isAttachedToWindow) return null
        if (view.visibility != View.VISIBLE || view.alpha <= 0.05f) return null
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 1f || h <= 1f) return null

        tempVisualRect.set(0f, 0f, w, h)
        var cur: View? = view
        var reachedRoot = false
        while (cur != null) {
            if (cur === root) {
                reachedRoot = true
                break
            }
            if (cur.visibility != View.VISIBLE || cur.alpha <= 0.05f) return null
            val m = cur.matrix
            if (!m.isIdentity) {
                m.mapRect(tempVisualRect)
            }
            val parent = cur.parent
            if (parent is ViewGroup) {
                tempVisualRect.offset(
                    (cur.left - parent.scrollX).toFloat(),
                    (cur.top - parent.scrollY).toFloat()
                )
                cur = parent
            } else {
                break
            }
        }
        if (!reachedRoot) return null
        if (tempVisualRect.width() <= 1f || tempVisualRect.height() <= 1f) return null
        return RectF(tempVisualRect)
    }

    /** v1.3.19：零堆分配坐标投影，供 120Hz OnPreDraw 视觉锁复用 */
    private fun resolveVisualRectInRootInto(
        view: View?,
        root: View,
        out: RectF,
        ignoreSelfAlpha: Boolean = false
    ): Boolean {
        if (view == null || !view.isAttachedToWindow) return false
        if (view.visibility != View.VISIBLE || (!ignoreSelfAlpha && view.alpha <= 0.05f)) return false
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 1f || h <= 1f) return false

        out.set(0f, 0f, w, h)
        var cur: View? = view
        var reachedRoot = false
        while (cur != null) {
            if (cur === root) {
                reachedRoot = true
                break
            }
            if (cur.visibility != View.VISIBLE || ((cur !== view || !ignoreSelfAlpha) && cur.alpha <= 0.05f)) return false
            val m = cur.matrix
            if (!m.isIdentity) {
                m.mapRect(out)
            }
            val parent = cur.parent
            if (parent is ViewGroup) {
                out.offset(
                    (cur.left - parent.scrollX).toFloat(),
                    (cur.top - parent.scrollY).toFloat()
                )
                cur = parent
            } else {
                break
            }
        }
        if (!reachedRoot) return false
        return out.width() > 1f && out.height() > 1f
    }

    private fun effectiveScaleX(view: View?, root: View): Float {
        if (view == null) return 1f
        var scale = 1f
        var cur: View? = view
        while (cur != null && cur !== root) {
            scale *= cur.scaleX
            cur = cur.parent as? View
        }
        return scale
    }

    private fun effectiveScaleY(view: View?, root: View): Float {
        if (view == null) return 1f
        var scale = 1f
        var cur: View? = view
        while (cur != null && cur !== root) {
            scale *= cur.scaleY
            cur = cur.parent as? View
        }
        return scale
    }

    private fun shouldShowOnCollapsedCapsule(prefs: RuntimePrefs): Boolean {
        // v1.4.5-fix10 F10-1B：记录最近一次判 false 的守卫名，仅供 [REENTRY_PREDRAW] 诊断，不参与判定
        if (!prefs.masterEnabled) { lastShouldShowBlocker = "master"; noteLandBlocker("master"); return false }
        // v1.3.18：非播放态且不在 600ms 切歌宽限期内禁止进入或维持宽态
        if (!isEffectivelyPlaying()) { lastShouldShowBlocker = "not_playing"; noteLandBlocker("not_playing"); return false }
        if (!sessionActive || (currentLyric.isBlank() && !inInterludeMode && !inIntroMode)) { lastShouldShowBlocker = "session_or_blank"; noteLandBlocker("session_or_blank"); return false }

        val capLeft = capLeftRef?.get()
        val capRight = capRightRef?.get()
        val leftTemplate = leftTemplateRef?.get() ?: run { lastShouldShowBlocker = "no_left_template"; noteLandBlocker("no_left_template"); return false }
        if (!isVisibleUpToBoundary(leftTemplate, capLeft)) { lastShouldShowBlocker = "left_not_visible"; noteLandBlocker("left_not_visible"); logVisFailOnce(leftTemplate, capLeft); return false }

        val root = rootWindowRef?.get() ?: leftTemplate.rootView
        if (root != null && root.height > Const.ISLAND_EXPANDED_HEIGHT_THRESHOLD_PX) {
            lastExpandedElapsedMs = SystemClock.elapsedRealtime()
            lastShouldShowBlocker = "root_height"
            noteLandBlocker("root_height")
            return false
        }
        val capExpanded = capExpandedRef?.get()
        if (capExpanded != null && capExpanded.visibility == View.VISIBLE && capExpanded.height > 0) {
            lastExpandedElapsedMs = SystemClock.elapsedRealtime()
            lastShouldShowBlocker = "cap_expanded"
            noteLandBlocker("cap_expanded")
            return false
        }

        // v1.3.15：校验声波视图与当前左模板同属一个原子岛卡片容器，且胶囊内音乐链路均 attached + VISIBLE
        val wave = findMusicWaveInSameCapsule(leftTemplate)
            ?: musicWaveRef?.get()?.takeIf { isSameCapsule(leftTemplate, it) }
        val waveParent = wave?.parent as? View
        // v1.4.5-fix10 F10-1：波浪被我方源头压制（INVISIBLE 而快照本意 VISIBLE）时视为在场，否则重入永远等不到 shouldShow
        // v1.4.5-fix18 P0-1（三方收口、Quick 改法）：只忽略与「我方最后一次自写值」一致的可见性——
        // 接管藏波（GONE）/压制（INVISIBLE）/切倒计时藏波都是我方所写，不得据此判波浪丢失（fix17 三次卡死主链）；
        // 与自写值不符＝原厂意图（如右岛切其它业务、波浪本就 GONE），照旧判 absent，不许把歌词盖到别人业务的岛上
        // v1.4.5-fix19 P0-1：先做结构检查，再查可见性；我方自写（进程级账本惰性校验命中）0ms 即判即行，不做定时兜底
        val structOk = wave != null && wave.isAttachedToWindow &&
            waveParent != null && isVisibleUpToBoundary(waveParent, capRight)
        val selfWritten = if (wave != null) isWaveSelfWritten(wave) else false
        val visOk = wave != null && (wave.visibility == View.VISIBLE || selfWritten)
        if (!structOk || !visOk) {
            if (!orphanCheckDone) {
                orphanCheckDone = true
                runOrphanCleanup(wave)
            }
            val nowEl = SystemClock.elapsedRealtime()
            if (waveAbsentStartElapsedMs == 0L) {
                waveAbsentStartElapsedMs = nowEl
            }
            val durMs = nowEl - waveAbsentStartElapsedMs
            if (nowEl - lastWaveAbsentDiagMs >= 2_000L) {
                lastWaveAbsentDiagMs = nowEl
                val procStartEl = if (Build.VERSION.SDK_INT >= 31) Process.getStartElapsedRealtime() else rendererInitElapsedMs
                val kind = if (!structOk) "struct" else "vis"
                val sysHid = lastHideReason == "wave_hidden_by_system"
                val chain = StringBuilder()
                var pv: View? = waveParent
                var hops = 0
                while (pv != null && hops < 6) {
                    chain.append(pv.javaClass.simpleName).append("[vis=").append(pv.visibility)
                        .append(" attached=").append(pv.isAttachedToWindow).append("] <- ")
                    pv = pv.parent as? View
                    hops++
                }
                val waMsg = "[WAVE_ABSENT] kind=$kind self=$selfWritten sysHid=$sysHid dur=${durMs}ms " +
                    "procAgeMs=${nowEl - procStartEl} waveNull=${wave == null} " +
                    "attached=${wave?.isAttachedToWindow} vis=${wave?.visibility} parentChain=$chain"
                YLog.info(msg = waMsg)
                EventHistoryStore.recordDiagOnly(waMsg)
            }
            lastShouldShowBlocker = "wave_absent"
            noteLandBlocker("wave_absent")
            return false
        }

        waveAbsentStartElapsedMs = 0L
        lastShouldShowBlocker = ""
        noteLandBlocker("pass")
        return true
    }

    // v1.4.5-fix17：孤儿残留检查只跑一次（进程内首次 wave_absent 触发）
    private var orphanCheckDone: Boolean = false

    // v1.4.5-fix17 实施审补诊断：渲染器建时时刻（低版本无 Process.getStartElapsedRealtime 时的进程龄兜底）＋[WAVE_ABSENT] 节流戳
    private val rendererInitElapsedMs: Long = SystemClock.elapsedRealtime()
    private var lastWaveAbsentDiagMs: Long = 0L

    /**
     * v1.4.5-fix17（Quick fix17 裁决）：旧进程被杀后可能残留我方修改（注入的流光子 View、被压 INVISIBLE 的波浪），
     * 新进程无快照会持续判 wave_absent＝「卡小胶囊」。启动首次遇到 wave_absent 时扫一遍：
     * 壳内 IslandShimmerChildView 残骸直接摘除；波浪的还原只在确有残骸时做（无残骸时 INVISIBLE 可能是原厂常态，不动）。
     */
    private fun runOrphanCleanup(wave: View?) {
        // v1.4.5-fix18 P0-3：只在未接管态清扫——接管中触发会误伤本轮刚注入的子 View（fix17 卡死链实证）；
        // 且只摘残骸、绝不写 wave 可见性，波浪写回只归还原路径按接管前原值执行（Quick 审核改法）
        if (activeMode != RenderMode.NONE) return
        var leftovers = 0
        for (shellRef in listOf(capLeftRef, capRightRef)) {
            val group = shellRef?.get() as? ViewGroup ?: continue
            val stale = ArrayList<View>()
            for (i in 0 until group.childCount) {
                val c = group.getChildAt(i)
                if (c.javaClass.simpleName == "IslandShimmerChildView" && !islandShimmer.ownsChild(c)) {
                    stale.add(c)
                }
            }
            for (c in stale) {
                runCatching { group.removeView(c) }
                leftovers++
            }
        }
        val waveSelf = isWaveSelfWritten(wave)
        val oMsg = "[ORPHAN] shimmerLeftovers=$leftovers waveVis=${wave?.visibility} waveRestored=false waveSelf=$waveSelf"
        YLog.info(msg = oMsg)
        EventHistoryStore.recordDiagOnly(oMsg)
    }

    // ==================== 宽形态渲染 ====================

    private fun applyWideMode(forceText: Boolean, prefs: RuntimePrefs) {
        // vc55 横屏总开关兜底：快速恢复等绕过 reconcileState 的路径也不许在横屏非歌词岛模式下接管绘制
        if (landscapeGateActive(prefs)) return
        // v1.4.5-fix20.3 C2：接管消费压制，待释放任务作废
        cancelPauseWaveRelease()
        val leftTemplate = leftTemplateRef?.get() ?: return
        val waveParent = resolveWaveParent() ?: run {
            applyNarrowMode(forceText)
            return
        }
        val waveView = musicWaveRef?.get()
        val lyric = currentLyric
        if (lyric.isBlank() && !inInterludeMode && !inIntroMode) return

        // v1.4.5-fix12 F12-1 验收锚点：自 NONE 接管宽态的瞬间记录波浪可见性与压制标记——
        // 展开类收起后应为 waveVisBefore=4 suppressed=true；若真机还闪且此行如此，F12-1 根因判错、回滚
        if (activeMode == RenderMode.NONE) {
            lastTakeoverElapsedMs = SystemClock.elapsedRealtime()
            cancelPendingMinHold()
            val dtSinceHide = if (lastHideElapsedMs > 0L) {
                SystemClock.elapsedRealtime() - lastHideElapsedMs
            } else {
                -1L
            }
            // vc55（fix21）：接管即结算本段隐藏时长进 PERF 窗口
            if (perfHiddenSinceMs > 0L) {
                perfHiddenAccumMs += SystemClock.elapsedRealtime() - perfHiddenSinceMs
                perfHiddenSinceMs = 0L
            }
            val landStamps = consumeLandStamps()
            val tMsg = "[TAKEOVER] waveVisBefore=${waveView?.visibility} suppressed=$waveRestoreSuppressed dtSinceHide=${dtSinceHide}ms landToTakeoverMs=${landStamps.landMs} passToTakeoverMs=${landStamps.passMs} firstPassToTakeoverMs=${landStamps.firstPassMs} blockers=${landStamps.seq}"
            YLog.info(msg = tMsg)
            EventHistoryStore.recordDiagOnly(tMsg)
            // v1.4.5-fix17 实施审 M2（Quick）：接管当帧刷新 [TPL_DETACH] 缓存——以稳定接管时的壳内结构
            // 作拔出对照（VIS_FAIL 时的刷新点是刚插回的过渡态、index 未必稳定）
            val ltFrame = leftTemplate.parent as? View
            val ltShell = ltFrame?.parent as? ViewGroup
            if (ltFrame != null && ltShell != null) {
                lastTplFrameIndex = ltShell.indexOfChild(ltFrame)
                lastTplShellChildren = ltShell.childCount
            }
            // v1.4.5-fix13 A（Quick 裁决）：接管消费压制只清标记、绝不在此还原——波浪全程保持 INVISIBLE，
            // 可见性由宽态各分支（常驻波浪点亮/接管藏波/前奏间奏揭示门控）在同一临界区内决定，杜绝 VISIBLE 中间帧
            if (waveRestoreSuppressed) {
                clearWaveSuppress("wide_takeover")
            }
        }

        val enteringWide = activeMode == RenderMode.NONE
        // v1.4.5-fix19 P0-2：每次宽态接管均按当前波浪实例补拍快照（优先取账本 preSelfOrigVis），
        // 避免前奏/间奏/常驻波浪分支未调用 hideMusicWaveWithSnapshot 时 savedWaveVisibility 为空导致压制跳过
        if (waveView != null) {
            snapshotWaveVisibilityIfNeeded(waveView)
        }

        isInternalUpdate = true
        var wideLayoutChanged = enteringWide
        // v1.4.5-fix20 P1-1：本段开启 changed= 收集（其余调用点不收集、不受影响）
        writeChangeNotes.clear()
        writeChangeCollecting = true
        try {
            if (applyExplicitTemplateWidthsForWide(leftTemplate, waveParent, prefs)) wideLayoutChanged = true
            if (applyLeftTemplateEdgePadding(leftTemplate, prefs)) wideLayoutChanged = true
            if (applyRightTemplateEdgePadding(waveParent, prefs)) wideLayoutChanged = true

            switcherRef?.get()?.let { sw ->
                if (sw.visibility != View.GONE) {
                    noteWriteChange("sw:${sw.visibility}->${View.GONE}")
                    sw.visibility = View.GONE
                    wideLayoutChanged = true
                }
            }

            // v1.4.5-fix19 P1-1：仅在宽态首入或实际 View 属性发生变化时打 [TAKEOVER_WRITES]（消除间奏 12Hz 写风暴）
            if (wideLayoutChanged) {
                val changedNotes = if (writeChangeNotes.isEmpty() && enteringWide) "enter" else writeChangeNotes.joinToString("|")
                val wMsg = "[TAKEOVER_WRITES] leftTplW=${leftTemplate.layoutParams?.width} leftTplH=${leftTemplate.layoutParams?.height} " +
                    "waveParentW=${waveParent.layoutParams?.width} waveParentH=${waveParent.layoutParams?.height} " +
                    "switcherVis=${switcherRef?.get()?.visibility} rootH=${rootWindowRef?.get()?.height ?: -1} changed=$changedNotes"
                YLog.info(msg = wMsg)
                EventHistoryStore.recordDiagOnly(wMsg)
            }
            writeChangeCollecting = false

            // 直令一：左侧始终保持歌名 + 歌手不变（无竖条）
            val leftContainer = ensureLeftInfoInjected(leftTemplate, prefs)
            updateLeftInfoContentLocked(prefs)

            // 右侧四态：前奏波浪段（按总长分档 8s/6s/4s）/ 前奏 16sp 纯倒计时 / 间奏原厂波浪+全程倒计时 / 正常歌词
            when {
                inIntroMode -> {
                    rightInterludeCdViewRef?.get()?.stopAndHide()
                    val inWaveLead = currentIntroLearnedMs <= 0L || isInIntroWaveLeadPhase()
                    // R3：前奏波浪段（inWaveLead）与前奏倒计时段（INTRO_CD）均不画呼吸点
                    rightLyricViewRef?.get()?.apply {
                        setInterludeCountdownBlocking(!inWaveLead)
                        clearAndHide()
                    }
                    if (inWaveLead) {
                        val wasFadingBack = soloWaveFadeActive
                        // v1.4.5-fix7/fix8：淡出中被拖回前奏波浪段时，取消淡出、alpha 复 1，保留居中 1.45x 变换不触发复位
                        if (wasFadingBack) {
                            cancelSoloFade(preserveTransform = true)
                        }
                        rightIntroCdViewRef?.get()?.apply {
                            stopAndHide()
                            // v1.4.5-fix11：波浪段显式关流光意图（Quick 口径：非倒计时分支一律 false）
                            setShimmerEnabled(false)
                        }
                        if (waveView != null) {
                            val needRevealGate = !wasFadingBack && (waveView.visibility != View.VISIBLE || lastRenderedRightState != 3)
                            if (needRevealGate) {
                                armPendingWaveReveal(waveView)
                            }
                            restoreWaveVisibilityIfSaved(waveView, "intro_wave_lead")
                            // 仓侧直修（Quick Q1）+ v1.4.5-fix19 P0-1：波浪段显形经唯一入口写回 VISIBLE 并清账本
                            if (waveView.visibility != View.VISIBLE || isWaveSelfWritten(waveView)) {
                                writeWaveVis(waveView, View.VISIBLE, "intro_wave_lead_show", clearLedgerAfter = true)
                            }
                            waveHiddenByOurFade = false
                        }
                        if (currentIntroLearnedMs > 0L) {
                            scheduleIntroWaveLeadEndCheck()
                        } else {
                            cancelIntroWaveLeadEndCheck()
                        }
                        lastRenderedRightState = 3
                    } else {
                        cancelIntroWaveLeadEndCheck()
                        val prevRightState = lastRenderedRightState
                        rightLyricViewRef?.get()?.setInterludeCountdownBlocking(true)
                        val introCd = ensureRightIntroCountdownInjected(waveParent, prefs)
                        introCd?.apply {
                            setPreferredWidthPx(computeRightLyricWidthPx(waveParent.context, prefs))
                            setAccentColor(currentKaraokeSungColor)
                            // v1.4.5-fix12：流光强度档位与样式随设置热生效（每次进倒计时分支按当前 prefs 刷新）
                            setShimmerLevel(prefs.shimmerLevel)
                            setShimmerStyle(prefs.shimmerStyle)
                            // v1.4.5-fix11（用户定版口径 + Quick M2）：流光意图先于 startCountdown 声明——
                            // 仅当前奏总长（=首句开口时刻）>= 28s 时启用，倒计时段持续到开口；startCountdown 内的 kick 读到的须是本轮新值
                            setShimmerEnabled(currentIntroLearnedMs >= 28_000L && prefs.shimmerCountdownEnabled) // fix13 C：倒计时流光独立开关
                            startCountdown(currentIntroLearnedMs, isPlaybackPlaying)
                        }
                        if (prevRightState == 3 && waveView != null && waveView.visibility == View.VISIBLE) {
                            startSoloWaveCrossFadeOut(waveView, introCd)
                        } else if (!soloWaveFadeActive) {
                            // v1.4.5-fix16（Quick 二轮裁决）：切换帧先 INVISIBLE（不改布局），下一帧再 GONE+requestLayout——
                            // 当帧直接 GONE 会让右模板重排、倒计时按旧位置多画一帧（日志实证 +97px 跳变）
                            if (waveView != null) {
                                writeWaveVis(waveView, View.INVISIBLE, "countdown_hide")
                                val wv = waveView
                                mainHandler.post {
                                    renderBreaker.execute {
                                        // 期间被波浪段重新点亮（VISIBLE）则作罢，不误伤回拖场景
                                        if (wv.visibility == View.INVISIBLE) {
                                            isInternalUpdate = true
                                            try {
                                                hideMusicWaveWithSnapshot(wv)
                                                wv.requestLayout()
                                            } finally {
                                                isInternalUpdate = false
                                            }
                                        }
                                    }
                                }
                            }
                            resetSoloWaveTransformIfMatching(waveView, resetTranslation = true)
                            if (introCd != null && introCd.alpha != 1f) introCd.alpha = 1f
                        }
                        lastRenderedRightState = 2
                        if (prevRightState == 3) {
                            val curPos = positionProvider()
                            val rem = if (curPos >= 0L) (currentIntroLearnedMs - curPos).coerceAtLeast(0L) else -1L
                            val cdVis = introCd != null && introCd.visibility == View.VISIBLE
                            val msg = "[INTRO_STATE] inIntro=true firstVocalMs=$currentIntroLearnedMs waveLeadMs=${resolveIntroWaveLeadMs(currentIntroLearnedMs)} pos=$curPos remaining=$rem countdownVisible=$cdVis rightMode=INTRO_CD reason=wave_lead_done"
                            YLog.info(msg = msg)
                            EventHistoryStore.recordDiagOnly(msg)
                            // v1.4.5-fix16（Quick 二轮裁决）：[INTRO_SWITCH] 连打 3 帧验证无叠影、无 +97px 跳变
                            logIntroSwitchFrame(0, waveView, introCd)
                            mainHandler.post {
                                logIntroSwitchFrame(1, waveView, introCd)
                                mainHandler.post { logIntroSwitchFrame(2, waveView, introCd) }
                            }
                        }
                    }
                }
                inInterludeMode -> {
                    cancelIntroWaveLeadEndCheck()
                    cancelSoloFade(preserveTransform = false)
                    rightIntroCdViewRef?.get()?.stopAndHide()
                    if (waveView != null) {
                        restoreWaveVisibilityIfSaved(waveView, "interlude_wave")
                    }
                    // v1.4.5-fix10 F10-3：间奏先波浪后倒计时——波浪相位内倒计时视图只驻留占位（INVISIBLE、不启动），
                    // 相位结束才启动并 150ms 淡入；波浪全程为原厂波浪原位显示，不释放接管、不走 GONE 免布局跳动
                    val interludeWavePhase = isInInterludeWavePhase()
                    val interludeCdOccupying = currentInterludeTargetMs > 0L && !interludeWavePhase
                    // R3：间奏波浪段（interludeWavePhase）抑制呼吸点；仅非波浪且无倒计时占槽位时允许呼吸点
                    if (!interludeWavePhase && !interludeCdOccupying && prefs.interludeDotsEnabled) {
                        val rv = ensureRightLyricInjected(waveParent, prefs, waveView)
                        rv?.apply {
                            setLyricStyle(prefs.lyricStyle)
                            setLineTransitionMode(prefs.lineTransitionMode)
                            setInterludeDotsEnabled(true)
                            setInterludeCountdownBlocking(false)
                            if (hasDisplayContent()) {
                                setLyric("", 0L, 1000L, isPlaybackPlaying)
                            } else {
                                setPlaying(isPlaybackPlaying)
                            }
                            if (visibility != View.VISIBLE) visibility = View.VISIBLE
                        }
                    } else {
                        rightLyricViewRef?.get()?.apply {
                            setInterludeCountdownBlocking(interludeCdOccupying)
                            clearAndHide()
                        }
                    }
                    if (currentInterludeTargetMs > 0L) {
                        val cdView = ensureRightInterludeCountdownInjected(waveParent, waveView)
                        if (interludeWavePhase) {
                            val wasCountdown = lastRenderedRightState == 1
                            cdView?.apply {
                                if (wasCountdown) stopAndHide()
                                if (visibility != View.INVISIBLE) visibility = View.INVISIBLE
                                animate().cancel()
                                alpha = 1f
                            }
                            scheduleInterludeWaveEndCheck()
                            lastRenderedRightState = 4
                        } else {
                            rightLyricViewRef?.get()?.setInterludeCountdownBlocking(true)
                            cancelInterludeWaveEndCheck()
                            val cameFromWave = lastRenderedRightState == 4
                            cdView?.apply {
                                setAccentColor(currentKaraokeSungColor)
                                animate().cancel()
                                if (cameFromWave) alpha = 0f
                                startCountdown(currentInterludeTargetMs, isPlaybackPlaying)
                                if (cameFromWave) {
                                    animate().alpha(1f).setDuration(150L).start()
                                }
                            }
                            lastRenderedRightState = 1
                        }
                    } else {
                        cancelInterludeWaveEndCheck()
                        rightInterludeCdViewRef?.get()?.stopAndHide()
                        lastRenderedRightState = 1
                    }
                }
                else -> {
                    cancelIntroWaveLeadEndCheck()
                    cancelInterludeWaveEndCheck()
                    if (soloWaveFadeActive) {
                        cancelSoloFade(preserveTransform = false)
                    }
                    rightIntroCdViewRef?.get()?.stopAndHide()
                    rightInterludeCdViewRef?.get()?.stopAndHide()
                    if (prefs.persistentWaveEnabled && waveView != null) {
                        restoreWaveVisibilityIfSaved(waveView, "lyric_wave")
                    } else {
                        hideMusicWaveWithSnapshot(waveView)
                    }

                    val rightView = ensureRightLyricInjected(waveParent, prefs, waveView)
                    if (rightView != null && lyric.isNotBlank()) {
                        val targetLyricPx = computeRightLyricWidthForModePx(waveParent.context, prefs, waveView)
                        rightView.setPreferredWidthPx(targetLyricPx)
                        rightView.setTextSizeSp(prefs.lyricTextSizeSp)
                        rightView.setKaraokeEnabled(prefs.karaokeEnabled)
                        rightView.setKaraokeSungColor(currentKaraokeSungColor)
                        rightView.setDualLineEnabled(prefs.dualLineLyricEnabled)
                        rightView.setSubTextSizeSp(prefs.dualLineSubTextSizeSp)
                        rightView.setCenterShortText(prefs.lyricCenterAlign)
                        rightView.setLineDissolveEnabled(prefs.lineDissolveEnabled)
                        rightView.setLyricStyle(prefs.lyricStyle)
                        rightView.setLineTransitionMode(prefs.lineTransitionMode)
                        rightView.setInterludeDotsEnabled(prefs.interludeDotsEnabled)
                        rightView.setInterludeCountdownBlocking(false)
                        if (rightView.visibility != View.VISIBLE) {
                            rightView.visibility = View.VISIBLE
                        }

                        val baseWindowMs = if (prefs.scrollAuto) {
                            currentEstimatedWindowMs
                        } else {
                            Const.SCROLL_DEFAULT_WINDOW_MS
                        }
                        val speedFactor = prefs.scrollSpeedFactor
                            .coerceIn(Const.SCROLL_SPEED_FACTOR_MIN, Const.SCROLL_SPEED_FACTOR_MAX)
                        val scrollDurationMs = (baseWindowMs.toFloat() / speedFactor).toLong().coerceAtLeast(600L)
                        val karaokeDurationMs = currentKaraokeDurationMs.coerceAtLeast(1000L)

                        val lineSeqChanged = currentLyricLineSeq > 0L && currentLyricLineSeq != lastRenderedLineSeq
                        val wordSpansChanged = currentWordSpans !== lastRenderedWordSpans && (currentWordSpans.size != lastRenderedWordSpans.size || currentWordSpans != lastRenderedWordSpans)
                        if (forceText || lineSeqChanged || wordSpansChanged || lastRenderedRightState != 0 || lyric != lastRenderedRightText || currentNextLyric != lastRenderedNextLyric || !rightView.hasDisplayContent()) {
                            rightView.setLyric(
                                text = lyric,
                                scrollDurationMs = scrollDurationMs,
                                karaokeDurationMs = karaokeDurationMs,
                                playing = isPlaybackPlaying,
                                initialElapsedMs = computeCurrentLyricElapsedMs(),
                                wordSpans = currentWordSpans,
                                lineSeq = currentLyricLineSeq,
                                nextLyric = currentNextLyric
                            )
                            lastRenderedRightText = lyric
                            lastRenderedNextLyric = currentNextLyric
                            lastRenderedWordSpans = currentWordSpans
                            lastRenderedLineSeq = currentLyricLineSeq
                            lastRenderedRightState = 0
                        } else {
                            rightView.setPlaying(isPlaybackPlaying)
                        }
                    } else {
                        rightView?.clearAndHide()
                    }
                }
            }

            activeMode = RenderMode.WIDE_V11
            onNativeVisibilityChanged?.invoke()
            if (wideLayoutChanged) {
                leftTemplate.requestLayout()
                waveParent.requestLayout()
                (leftTemplate.parent as? View)?.requestLayout()
                (waveParent.parent as? View)?.requestLayout()
                capLeftRef?.get()?.requestLayout()
                capRightRef?.get()?.requestLayout()
            }

            armWideTreeDumpAfterLayout(leftContainer)
        } finally {
            isInternalUpdate = false
            // v1.4.5-fix20 P1-1（Quick 实施审建议）：异常路径也关收集开关，防列表无谓增长
            writeChangeCollecting = false
        }
    }

    private fun snapshotWaveVisibilityIfNeeded(waveView: View, forceRefresh: Boolean = false) {
        val waveId = System.identityHashCode(waveView)
        val ledger = resolveValidWaveLedger(waveView)
        if (forceRefresh || savedWaveVisibility == null || savedWaveIdentity != waveId) {
            // v1.4.5-fix19 P0-2：优先取进程级账本的 preSelfOrigVis（我方写过则折算为写入前的原厂原值），
            // 严禁把我方写的 INVISIBLE(4) 记成原值
            val resolvedOrig = when {
                ledger != null -> ledger.preSelfOrigVis
                waveView.visibility == View.INVISIBLE && (waveHiddenByOurFade || waveRestoreSuppressed) -> View.VISIBLE
                else -> waveView.visibility
            }
            savedWaveVisibility = resolvedOrig
            savedWaveIdentity = waveId
        }
    }

    private fun hideMusicWaveWithSnapshot(waveView: View?) {
        if (waveView == null) return
        // v1.4.5-fix19 P0-2：每次接管用当前实例重拍快照（按账本 preSelfOrigVis 折算，绝不把 4 记成原值）
        snapshotWaveVisibilityIfNeeded(waveView, forceRefresh = true)
        writeWaveVis(waveView, View.GONE, "takeover_hide")
        waveHiddenByOurFade = false
        // v1.4.5-fix10 F10-1：接管藏波即消费压制标记（与兜底还原互斥，接管优先）
        if (waveRestoreSuppressed) {
            clearWaveSuppress("takeover_hide_wave")
        }
    }

    /** v1.4.5-fix7 v3：按视图实例校验精确还原等待波浪的 scaleX / pivot / translationX；实例已更换则直接作废旧记录 */
    private fun resetSoloWaveTransformIfMatching(wv: View?, resetTranslation: Boolean = true) {
        if (!soloWaveTransformApplied) {
            soloWaveTransformIdentity = 0
            appliedSoloWaveOffsetX = 0f
            savedWavePivotWasSet = false
            return
        }
        if (wv == null) {
            soloWaveTransformApplied = false
            soloWaveTransformIdentity = 0
            appliedSoloWaveOffsetX = 0f
            savedWavePivotWasSet = false
            return
        }
        val wvId = System.identityHashCode(wv)
        if (soloWaveTransformIdentity != 0 && soloWaveTransformIdentity != wvId) {
            soloWaveTransformApplied = false
            soloWaveTransformIdentity = 0
            appliedSoloWaveOffsetX = 0f
            savedWavePivotWasSet = false
            return
        }
        if (wv.scaleX != 1f) wv.scaleX = 1f
        if (savedWavePivotWasSet) {
            wv.pivotX = savedWavePivotX
            wv.pivotY = savedWavePivotY
        } else {
            wv.resetPivot()
        }
        if (resetTranslation && wv.translationX != 0f) {
            wv.translationX = 0f
        }
        soloWaveTransformApplied = false
        soloWaveTransformIdentity = 0
        appliedSoloWaveOffsetX = 0f
        savedWavePivotWasSet = false
    }

    /** v1.4.5-fix8 F6：波浪显形门控——恢复可见前先置 alpha=0，待首帧 solo 变换成功写入后再释放，100ms 超时强制显形并打日志 */
    private fun armPendingWaveReveal(waveView: View) {
        // 仓侧直修（Quick Q1）：已 pending 时不重置超时截止——反复 reapply 重新 arm 会把 100ms 超时饿死、alpha 永远卡 0
        if (pendingWaveReveal) return
        clearPendingWaveReveal(restoreAlpha = false)
        cancelSoloWaveScaleIn()
        val gen = ++pendingWaveRevealGeneration
        pendingWaveReveal = true
        if (waveView.alpha != 0f) waveView.alpha = 0f
        val timeoutTask = Runnable {
            pendingWaveRevealTimeoutRunnable = null
            if (!pendingWaveReveal || pendingWaveRevealGeneration != gen) return@Runnable
            pendingWaveReveal = false
            val targetWave = musicWaveRef?.get() ?: waveView
            if (targetWave.alpha != 1f) targetWave.alpha = 1f
            if (targetWave.visibility != View.VISIBLE) {
                // 仓侧直修（Quick Q1）+ v1.4.5-fix19 P0-1：超时显形经唯一入口写回并清账本
                writeWaveVis(targetWave, View.VISIBLE, "reveal_timeout_restore", clearLedgerAfter = true)
                waveHiddenByOurFade = false
                val invMsg = "[WAVE_REVEAL_TIMEOUT_INVISIBLE] inIntro=$inIntroMode lastRightState=$lastRenderedRightState w=${targetWave.width}"
                YLog.info(msg = invMsg)
                EventHistoryStore.record(invMsg)
            } else {
                val msg = "[WAVE_REVEAL_TIMEOUT] inIntro=$inIntroMode lastRightState=$lastRenderedRightState vis=${targetWave.visibility} w=${targetWave.width}"
                YLog.info(msg = msg)
                EventHistoryStore.record(msg)
                // 仓侧直修（Quick F6/F7）：超时显形也要有进场过渡，不许静态跳成 1.45
                if (inIntroMode && lastRenderedRightState == 3 && targetWave.isAttachedToWindow) {
                    startSoloWaveScaleIn(targetWave, targetWave.rootView)
                }
            }
        }
        pendingWaveRevealTimeoutRunnable = timeoutTask
        mainHandler.postDelayed(timeoutTask, 100L)
    }

    private fun clearPendingWaveReveal(restoreAlpha: Boolean) {
        pendingWaveRevealTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingWaveRevealTimeoutRunnable = null
        pendingWaveRevealGeneration++
        val wasPending = pendingWaveReveal
        pendingWaveReveal = false
        if (wasPending && restoreAlpha) {
            musicWaveRef?.get()?.let { wv ->
                if (wv.alpha != 1f) wv.alpha = 1f
            }
        }
    }

    /** v1.4.5-fix8 F7：solo 波浪进场 150ms scaleX 1.0→1.45 过渡，逐帧同步重算居中 translationX，带代次守卫与看门狗 */
    private fun cancelSoloWaveScaleIn() {
        soloWaveScaleInWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        soloWaveScaleInWatchdogRunnable = null
        soloWaveScaleInGeneration++
        soloWaveScaleInAnimator?.cancel()
        soloWaveScaleInAnimator = null
    }

    private fun startSoloWaveScaleIn(waveView: View, root: View) {
        cancelSoloWaveScaleIn()
        val gen = ++soloWaveScaleInGeneration
        if (!ValueAnimator.areAnimatorsEnabled()) {
            waveView.scaleX = 1.45f
            syncSoloWaveCenterInFrame(waveView, root, ignoreSelfAlpha = false)
            return
        }
        val anim = ValueAnimator.ofFloat(1.0f, 1.45f).apply {
            duration = 150L
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                if (soloWaveScaleInGeneration != gen || soloWaveFadeActive) return@addUpdateListener
                val sx = va.animatedValue as Float
                if (waveView.width > 0) {
                    val targetPivotX = waveView.width * 0.5f
                    if (abs(waveView.pivotX - targetPivotX) >= 0.5f) {
                        waveView.pivotX = targetPivotX
                    }
                    waveView.scaleX = sx
                    syncSoloWaveCenterInFrame(waveView, root, ignoreSelfAlpha = false)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (soloWaveScaleInGeneration != gen) return
                    soloWaveScaleInWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                    soloWaveScaleInWatchdogRunnable = null
                    soloWaveScaleInAnimator = null
                    if (!soloWaveFadeActive && inIntroMode && lastRenderedRightState == 3) {
                        waveView.scaleX = 1.45f
                        syncSoloWaveCenterInFrame(waveView, root, ignoreSelfAlpha = false)
                    }
                }
            })
        }
        soloWaveScaleInAnimator = anim
        val watchdog = Runnable {
            soloWaveScaleInWatchdogRunnable = null
            if (soloWaveScaleInGeneration != gen) return@Runnable
            // 仓侧直修（Quick）：cancel 前先消费代次，让看门狗做唯一收尾者，防 onAnimationEnd 与看门狗双跑
            soloWaveScaleInGeneration++
            soloWaveScaleInAnimator?.cancel()
            soloWaveScaleInAnimator = null
            if (!soloWaveFadeActive && inIntroMode && lastRenderedRightState == 3) {
                waveView.scaleX = 1.45f
                syncSoloWaveCenterInFrame(waveView, root, ignoreSelfAlpha = false)
            }
        }
        soloWaveScaleInWatchdogRunnable = watchdog
        // 仓侧直修（Quick 必改）：与淡出看门狗同口径按动画缩放计算，固定 260ms 在大缩放下会提前掐断进场动画
        mainHandler.postDelayed(watchdog, (150L * ValueAnimator.getDurationScale()).toLong() + 100L)
        anim.start()
    }

    /** v1.4.5-fix8 F3：按本帧新鲜 capR 矩形与祖先累计 scaleX 精确换算本地 translationX 并单次写入 */
    private fun syncSoloWaveCenterInFrame(wv: View, root: View, ignoreSelfAlpha: Boolean): Boolean {
        // 仓侧直修（Quick F6/F7）：capR 矩形只要求可见、不要求 alpha——展开早期 capR alpha<=0.05 时原判据会让 sync 恒失败、门控被迫走超时路径
        if (!resolveVisualRectInRootInto(capRightRef?.get(), root, lockCapRightRect, ignoreSelfAlpha = true)) return false
        if (!resolveVisualRectInRootInto(wv, root, lockWaveRect, ignoreSelfAlpha = ignoreSelfAlpha)) return false
        val desiredCenterX = (lockCapRightRect.left + lockCapRightRect.right) * 0.5f
        val curCenterX = (lockWaveRect.left + lockWaveRect.right) * 0.5f
        val centerDelta = desiredCenterX - curCenterX
        if (abs(centerDelta) >= Const.PRE_DRAW_LOCK_MIN_DELTA_PX) {
            val rawParentScaleX = effectiveScaleX(wv.parent as? View, root)
            // 仓侧直修（Quick Q6）：祖先缩放过小时矩形取整误差会被放大成巨大位移，本帧不写、等展开到位再居中
            if (rawParentScaleX < 0.3f) return false
            val parentScaleX = rawParentScaleX.coerceAtLeast(0.05f)
            val localDelta = centerDelta / parentScaleX
            val nextSoloOffset = appliedSoloWaveOffsetX + localDelta
            appliedSoloWaveOffsetX = nextSoloOffset
            wv.translationX = nextSoloOffset
        }
        return true
    }

    /** v1.4.5-fix8 F4（仓侧直修，Quick Q3）：统一淡出收尾——恢复 M3 的 active 守卫、消费代次并掐断残留动画（只靠代次校验挡不住看门狗先行收尾后 endAction 二次执行，会把已重新显示的前奏波浪再置 INVISIBLE）；置 INVISIBLE 不收缩布局并立 waveHiddenByOurFade 占位标记 */
    private fun finishSoloWaveFade(gen: Int, waveView: View) {
        if (soloWaveFadeGeneration != gen || !soloWaveFadeActive) return
        soloWaveFadeGeneration++
        soloWaveFadeWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        soloWaveFadeWatchdogRunnable = null
        soloWaveFadeActive = false
        soloWaveFadeViewRef = null
        waveView.animate().cancel()
        waveHiddenByOurFade = true
        writeWaveVis(waveView, View.INVISIBLE, "solo_fade_finish")
        resetSoloWaveTransformIfMatching(waveView, resetTranslation = true)
        if (!pendingWaveReveal && waveView.alpha != 1f) {
            waveView.alpha = 1f
        }
    }

    /** v1.4.5-fix7/fix8：统一切歌/收起/detach/还原/回拖的淡出与进场打断入口（代次递增 + cancel + 遵守 F6 门控 alpha 优先权） */
    private fun cancelSoloFade(preserveTransform: Boolean) {
        val wasActive = soloWaveFadeActive
        // 仓侧直修（Quick Q2 加固）：在清 pending 之前记下门控是否由我方持有，alpha 只恢复我方动过的，防切歌/收起时强行改写原厂 alpha
        val wasPending = pendingWaveReveal
        soloWaveFadeWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        soloWaveFadeWatchdogRunnable = null
        soloWaveFadeGeneration++
        soloWaveFadeActive = false
        cancelSoloWaveScaleIn()
        if (!preserveTransform) {
            clearPendingWaveReveal(restoreAlpha = false)
        }
        val fadingWave = soloWaveFadeViewRef?.get() ?: musicWaveRef?.get()
        soloWaveFadeViewRef = null
        fadingWave?.let { wv ->
            if (wasActive) {
                wv.animate().cancel()
                if (!pendingWaveReveal && wv.alpha != 1f) wv.alpha = 1f
            } else if (!preserveTransform && wasPending && wv.alpha != 1f) {
                // 仓侧直修（Quick Q2 加固）：非淡出打断时只恢复我方门控压住的 alpha（wasPending 在清 pending 前捕获）
                wv.alpha = 1f
            }
            if (!preserveTransform) {
                resetSoloWaveTransformIfMatching(wv, resetTranslation = true)
            }
        }
        // v1.4.5-fix18 实施审 M7：打断时先拆倒计时淡入守门并复位其 alpha，再走原有 cd 收尾
        cancelCountdownFadeGate(resetAlpha = true)
        rightIntroCdViewRef?.get()?.let { cd ->
            if (wasActive || cd.alpha != 1f) {
                cd.animate().cancel()
                if (cd.alpha != 1f) cd.alpha = 1f
            }
        }
    }

    /** v1.4.5-fix16：[INTRO_SWITCH] 单帧采样（波浪可见性/倒计时左缘与透明度），f0/f1/f2 三帧对照验叠影与跳变 */
    private fun logIntroSwitchFrame(frame: Int, waveView: View?, introCd: InterludeCountdownView?) {
        val sMsg = "[INTRO_SWITCH] f$frame waveVis=${waveView?.visibility} cdLeft=${introCd?.left} cdVis=${introCd?.visibility} cdAlpha=${introCd?.alpha}"
        YLog.info(msg = sMsg)
        EventHistoryStore.recordDiagOnly(sMsg)
    }

    /** v1.4.5-fix8 F4/F6 原版：wave_lead_done 切入 INTRO_CD 时 150ms 交叉淡出。
     *  v1.4.5-fix18 P0-5（三方收口）：[INTRO_SWITCH] 实证旧版头三帧 waveVis=0 与倒计时叠画、cdLeft 跳变——
     *  改为同一调用内同步置 INVISIBLE（不触发重排，区别于 GONE）并当场归位；倒计时待 cdLeft 稳定后才淡入 */
    private fun startSoloWaveCrossFadeOut(waveView: View, introCd: InterludeCountdownView?) {
        clearPendingWaveReveal(restoreAlpha = false)
        cancelSoloWaveScaleIn()
        snapshotWaveVisibilityIfNeeded(waveView)
        soloWaveFadeWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        soloWaveFadeWatchdogRunnable = null
        val gen = ++soloWaveFadeGeneration
        soloWaveFadeActive = true
        soloWaveFadeViewRef = WeakReference(waveView)
        // 看门狗保留作收尾兜底（按系统动画时长缩放）：此时可见性已同步落定，它只做状态清扫
        val watchdogDelayMs = (150L * ValueAnimator.getDurationScale()).toLong() + 100L
        val watchdog = Runnable {
            soloWaveFadeWatchdogRunnable = null
            finishSoloWaveFade(gen, waveView)
        }
        soloWaveFadeWatchdogRunnable = watchdog
        mainHandler.postDelayed(watchdog, watchdogDelayMs)
        waveView.animate().cancel()
        waveHiddenByOurFade = true
        writeWaveVis(waveView, View.INVISIBLE, "countdown_hide")
        resetSoloWaveTransformIfMatching(waveView, resetTranslation = true)
        if (waveView.alpha != 1f) waveView.alpha = 1f
        introCd?.let { cd ->
            cd.animate().cancel()
            cd.alpha = 0f
            gateCountdownFadeInOnStableLeft(cd)
        }
    }

    /** v1.4.5-fix18 P0-5：倒计时淡入守门——cdLeft 连续两帧不变才 120ms 淡入；450ms 兜底强显防守门永不放行。
     *  v1.4.5-fix18 实施审 M7：守门绑定 soloWaveFadeGeneration——切歌/还原/cancelSoloFade 一律递增代次，
     *  旧守门的监听与兜底到点先验代次、过期即自拆；句柄存字段，打断入口统一拆除并复位 alpha */
    private fun gateCountdownFadeInOnStableLeft(cd: InterludeCountdownView) {
        cancelCountdownFadeGate(resetAlpha = false)
        val gen = ++cdGateGeneration
        val cdRef = WeakReference(cd)
        cdGateViewRef = cdRef
        var lastLeft = Int.MIN_VALUE
        var stableFrames = 0
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val v = cdRef.get() ?: return true
                if (gen != cdGateGeneration || !v.isAttachedToWindow) {
                    runCatching { v.viewTreeObserver.removeOnPreDrawListener(this) }
                    if (cdGateListener === this) cdGateListener = null
                    return true
                }
                val l = v.left
                if (l == lastLeft) {
                    stableFrames++
                } else {
                    stableFrames = 0
                    lastLeft = l
                }
                if (stableFrames >= 2) {
                    cancelCountdownFadeGate(resetAlpha = false)
                    if (v.alpha < 1f) v.animate().alpha(1f).setDuration(120L).start()
                }
                return true
            }
        }
        cdGateListener = listener
        cd.viewTreeObserver.addOnPreDrawListener(listener)
        val fallback = Runnable {
            cdGateFallback = null
            if (gen != cdGateGeneration) return@Runnable
            val v = cdRef.get() ?: return@Runnable
            cancelCountdownFadeGate(resetAlpha = false)
            if (v.alpha < 1f) v.animate().alpha(1f).setDuration(120L).start()
        }
        cdGateFallback = fallback
        mainHandler.postDelayed(fallback, 450L)
    }

    /** v1.4.5-fix18 实施审 M7：拆除倒计时淡入守门（监听＋兜底）；resetAlpha 时把视图 alpha 复位为 1 防残留隐形 */
    private fun cancelCountdownFadeGate(resetAlpha: Boolean) {
        // 独立代次递增：任何已排队的旧监听/兜底到点验代次即自弃（与淡出代次互不干扰）
        cdGateGeneration++
        val v = cdGateViewRef?.get()
        cdGateListener?.let { l ->
            if (v != null) runCatching { v.viewTreeObserver.removeOnPreDrawListener(l) }
        }
        cdGateListener = null
        cdGateFallback?.let { mainHandler.removeCallbacks(it) }
        cdGateFallback = null
        if (resetAlpha && v != null) {
            v.animate().cancel()
            if (v.alpha != 1f) v.alpha = 1f
        }
        cdGateViewRef = null
    }

    private fun resolveAlbumLeftPaddingPx(prefs: RuntimePrefs): Int {
        return prefs.albumLeftPaddingPx.coerceIn(
            Const.ALBUM_LEFT_PADDING_MIN_PX,
            Const.ALBUM_LEFT_PADDING_MAX_PX
        )
    }

    private fun resolveLyricLeftOffsetPx(ctx: Context, prefs: RuntimePrefs): Int {
        val clampedDp = prefs.lyricLeftOffsetDp.coerceIn(
            Const.LYRIC_LEFT_OFFSET_MIN_DP,
            Const.LYRIC_LEFT_OFFSET_MAX_DP
        )
        return (clampedDp * ctx.resources.displayMetrics.density).roundToInt()
    }

    private fun resolveLeftInfoOffsetPx(ctx: Context, prefs: RuntimePrefs): Int {
        val clampedDp = prefs.leftInfoOffsetDp.coerceIn(
            Const.LEFT_INFO_OFFSET_MIN_DP,
            Const.LEFT_INFO_OFFSET_MAX_DP
        )
        return (clampedDp * ctx.resources.displayMetrics.density).roundToInt()
    }

    private fun resolveLeftInfoMarginStartPx(ctx: Context, prefs: RuntimePrefs): Int {
        val clampedDp = prefs.leftInfoMarginStartDp.coerceIn(
            Const.LEFT_INFO_MARGIN_START_MIN_DP,
            Const.LEFT_INFO_MARGIN_START_MAX_DP
        )
        return (clampedDp * ctx.resources.displayMetrics.density).roundToInt()
    }

    private fun resolveLeftInfoRightInsetPx(ctx: Context, prefs: RuntimePrefs): Int {
        val clampedDp = prefs.leftInfoRightInsetDp.coerceIn(
            Const.LEFT_INFO_RIGHT_INSET_MIN_DP,
            Const.LEFT_INFO_RIGHT_INSET_MAX_DP
        )
        return (clampedDp * ctx.resources.displayMetrics.density).roundToInt()
    }

    private fun resolveRightLyricRightInsetPx(ctx: Context, prefs: RuntimePrefs): Int {
        val clampedDp = prefs.rightLyricRightInsetDp.coerceIn(
            Const.RIGHT_LYRIC_RIGHT_INSET_MIN_DP,
            Const.RIGHT_LYRIC_RIGHT_INSET_MAX_DP
        )
        return (clampedDp * ctx.resources.displayMetrics.density).roundToInt()
    }

    private fun computeLeftInfoMarginStartForParent(
        parent: ViewGroup,
        albumIcon: View?,
        prefs: RuntimePrefs
    ): Int {
        val ctx = parent.context
        val marginStartPx = resolveLeftInfoMarginStartPx(ctx, prefs)
        if (parent is FrameLayout) {
            val albumW = albumIcon?.width?.takeIf { it > 0 }
                ?: albumIcon?.measuredWidth?.takeIf { it > 0 }
                ?: dp2px(ctx, 20)
            val albumMlp = albumIcon?.layoutParams as? ViewGroup.MarginLayoutParams
            val albumEndInContent = if (albumIcon != null && albumIcon.right > 0) {
                (albumIcon.right - parent.paddingLeft).coerceAtLeast(albumW)
            } else {
                albumW + (albumMlp?.marginStart ?: 0)
            }
            return (albumEndInContent + (albumMlp?.marginEnd ?: 0) + marginStartPx).coerceAtLeast(0)
        }
        return marginStartPx
    }

    /** v1.2.9：按锁定后的专辑图目标左边距 + 专辑图实宽与双侧 margin 精确算宽，默认右缘 <= capL.right - 12px 绝不切字 */
    private fun computeLeftInfoWidthPx(leftTemplate: ViewGroup, prefs: RuntimePrefs): Int {
        val ctx = leftTemplate.context
        val targetAlbumPad = resolveAlbumLeftPaddingPx(prefs)
        val albumIcon = findChildByIdName(leftTemplate, Const.ID_ICON_IMAGE_LEFT)
        val albumW = albumIcon?.width?.takeIf { it > 0 }
            ?: albumIcon?.measuredWidth?.takeIf { it > 0 }
            ?: dp2px(ctx, 20)
        val albumMlp = albumIcon?.layoutParams as? ViewGroup.MarginLayoutParams
        val albumExtraMargins = (albumMlp?.marginStart ?: 0).coerceAtLeast(0) +
            (albumMlp?.marginEnd ?: 0).coerceAtLeast(0)
        val leftMarginPx = resolveLeftInfoMarginStartPx(ctx, prefs)
        val rightInsetPx = resolveLeftInfoRightInsetPx(ctx, prefs)
        val startInCapL = targetAlbumPad + albumW + albumExtraMargins + leftMarginPx
        return (Const.WIDE_TEMPLATE_EXPLICIT_WIDTH_PX - startInCapL - rightInsetPx)
            .coerceIn(80, 280)
    }

    private fun resolveLinearLyricLeftMarginPx(ctx: Context, prefs: RuntimePrefs): Int {
        return Const.RIGHT_TEMPLATE_EDGE_PADDING_PX + resolveLyricLeftOffsetPx(ctx, prefs)
    }

    private fun computeRightTemplateWidthPx(ctx: Context, prefs: RuntimePrefs): Int {
        val clampedDp = prefs.wideLyricWidthDp
            .coerceIn(Const.WIDE_LYRIC_WIDTH_DP_MIN, Const.WIDE_LYRIC_WIDTH_DP_MAX)
        val sliderPx = dp2px(ctx, clampedDp)
        return max(Const.WIDE_TEMPLATE_EXPLICIT_WIDTH_PX, sliderPx + Const.RIGHT_TEMPLATE_EDGE_PADDING_PX * 2)
    }

    /** v1.2.8 全程单一线性公式：右模板 padLeft=0/padRight=rightInsetPx、右缘固定，lyricWidth = templateW - rightInsetPx - (8 + offsetPx) */
    private fun computeRightLyricWidthPx(ctx: Context, prefs: RuntimePrefs): Int {
        val templateW = computeRightTemplateWidthPx(ctx, prefs)
        val offsetPx = resolveLyricLeftOffsetPx(ctx, prefs)
        val padRight = resolveRightLyricRightInsetPx(ctx, prefs)
        val targetLeft = Const.RIGHT_TEMPLATE_EDGE_PADDING_PX + offsetPx
        return (templateW - padRight - targetLeft).coerceIn(100, templateW + 140)
    }

    /** v1.4.1：按 musicWave 实际占位（含 margin）+ 2dp 紧贴间距重算歌词宽，硬约束不挤出右圆弧且消除大段留白 */
    private fun resolveWaveOccupiedWidthPx(ctx: Context, waveView: View): Int {
        val rawW = waveView.width.takeIf { it > 0 } ?: waveView.measuredWidth.takeIf { it > 0 } ?: dp2px(ctx, 22)
        val mlp = waveView.layoutParams as? ViewGroup.MarginLayoutParams
        val marginH = (mlp?.marginStart ?: 0).coerceAtLeast(0) + (mlp?.marginEnd ?: 0).coerceAtLeast(0)
        return rawW + marginH
    }

    private fun computeRightLyricWidthForModePx(ctx: Context, prefs: RuntimePrefs, waveView: View?): Int {
        val baseW = computeRightLyricWidthPx(ctx, prefs)
        if (!prefs.persistentWaveEnabled || waveView == null) return baseW
        val templateW = computeRightTemplateWidthPx(ctx, prefs)
        val padRight = resolveRightLyricRightInsetPx(ctx, prefs)
        val leftMargin = resolveLinearLyricLeftMarginPx(ctx, prefs)
        val waveOccupied = resolveWaveOccupiedWidthPx(ctx, waveView)
        val waveGapPx = dp2px(ctx, 2)
        val safeEdgePx = dp2px(ctx, 4)
        val hardCap = templateW - padRight - safeEdgePx - leftMargin - waveGapPx - waveOccupied
        return min(baseW - waveOccupied - waveGapPx, hardCap).coerceAtLeast(80)
    }

    private fun computeRightLyricEndMarginPx(parent: ViewGroup, prefs: RuntimePrefs, waveView: View?): Int {
        if (!prefs.persistentWaveEnabled || waveView == null) return 0
        val ctx = parent.context
        val gapPx = dp2px(ctx, 2)
        if (parent is LinearLayout) return gapPx
        val waveOccupied = resolveWaveOccupiedWidthPx(ctx, waveView)
        return waveOccupied + gapPx
    }

    /** 单一权威同步锁定：左模板 paddingLeft */
    private fun enforceLeftTemplatePaddingSync(leftTemplate: ViewGroup, prefs: RuntimePrefs) {
        val targetPad = resolveAlbumLeftPaddingPx(prefs)
        if (leftTemplate.paddingLeft == targetPad) return
        val prevInternal = isInternalUpdate
        isInternalUpdate = true
        try {
            applyLeftTemplateEdgePadding(leftTemplate, prefs)
        } finally {
            isInternalUpdate = prevInternal
        }
    }

    /** 单一权威同步锁定：右模板 paddingLeft=0/paddingRight=rightInsetPx + LinearLayout gravity 锁定 */
    private fun enforceRightTemplatePaddingSync(rightTemplate: ViewGroup, prefs: RuntimePrefs) {
        val targetRightPad = resolveRightLyricRightInsetPx(rightTemplate.context, prefs)
        val targetGravity = Gravity.CENTER_VERTICAL or Gravity.START
        val gravityDrifted = rightTemplate is LinearLayout && rightTemplate.gravity != targetGravity
        if (rightTemplate.paddingLeft == 0 && rightTemplate.paddingRight == targetRightPad && !gravityDrifted) {
            return
        }
        val prevInternal = isInternalUpdate
        isInternalUpdate = true
        try {
            applyRightTemplateEdgePadding(rightTemplate, prefs)
        } finally {
            isInternalUpdate = prevInternal
        }
    }

    // v1.4.5-fix20 P1-1：[TAKEOVER_WRITES] changed= 诊断收集器——仅宽态应用段开启收集，
    // 各写入函数把「哪一项、旧值->新值」记进来，只加诊断、不改任何写入逻辑
    private val writeChangeNotes = ArrayList<String>(6)
    private var writeChangeCollecting = false

    private fun noteWriteChange(note: String) {
        if (writeChangeCollecting) writeChangeNotes.add(note)
    }

    private fun applyExplicitTemplateWidthsForWide(
        leftTemplate: ViewGroup,
        rightTemplate: ViewGroup,
        prefs: RuntimePrefs
    ): Boolean {
        val leftTargetPx = Const.WIDE_TEMPLATE_EXPLICIT_WIDTH_PX
        val rightTargetPx = computeRightTemplateWidthPx(rightTemplate.context, prefs)
        val capLeft = capLeftRef?.get()
        val capRight = capRightRef?.get()

        var changed = false
        if (snapshotAndSetExplicitWidth(leftTemplate, leftTargetPx, "lpW_L")) changed = true
        (leftTemplate.parent as? View)?.let { p ->
            if (p !== capLeft && snapshotAndSetExplicitWidth(p, leftTargetPx, "lpW_Lp")) changed = true
        }
        if (snapshotAndSetExplicitWidth(rightTemplate, rightTargetPx, "lpW_R")) changed = true
        (rightTemplate.parent as? View)?.let { p ->
            if (p !== capRight && snapshotAndSetExplicitWidth(p, rightTargetPx, "lpW_Rp")) changed = true
        }
        return changed
    }

    private fun snapshotAndSetExplicitWidth(v: View, targetPx: Int, label: String = "lpW"): Boolean {
        val lp = v.layoutParams ?: return false
        val key = System.identityHashCode(v)
        if (!savedWidthConstraints.containsKey(key)) {
            savedWidthConstraints[key] = SavedWidthConstraint(
                viewRef = WeakReference(v),
                lpWidth = lp.width
            )
        }
        if (lp.width != targetPx) {
            noteWriteChange("$label:${lp.width}->$targetPx")
            lp.width = targetPx
            v.layoutParams = lp
            return true
        }
        return false
    }

    /** v1.2.9：左模板对称解锁裁剪 + 锁定 paddingRight=0 + LinearLayout gravity=CENTER_VERTICAL|START */
    private fun applyLeftTemplateEdgePadding(leftTemplate: ViewGroup, prefs: RuntimePrefs): Boolean {
        val existing = savedLeftTemplatePadding
        if (existing == null || existing.viewRef.get() !== leftTemplate) {
            val rawLeft = leftTemplate.paddingLeft
            val baselineLeft = if (rawLeft > 0) rawLeft else Const.LEFT_TEMPLATE_FACTORY_PADDING_PX
            savedLeftTemplatePadding = SavedPadding(
                viewRef = WeakReference(leftTemplate),
                left = baselineLeft,
                top = leftTemplate.paddingTop,
                right = leftTemplate.paddingRight,
                bottom = leftTemplate.paddingBottom
            )
        }
        var changed = false
        val targetPad = resolveAlbumLeftPaddingPx(prefs)
        if (leftTemplate.paddingLeft != targetPad || leftTemplate.paddingRight != 0) {
            if (leftTemplate.paddingLeft != targetPad) noteWriteChange("padL:${leftTemplate.paddingLeft}->$targetPad")
            if (leftTemplate.paddingRight != 0) noteWriteChange("padLR:${leftTemplate.paddingRight}->0")
            leftTemplate.setPadding(targetPad, leftTemplate.paddingTop, 0, leftTemplate.paddingBottom)
            changed = true
        }
        if (leftTemplate is LinearLayout) {
            val savedG = savedLeftTemplateGravity
            if (savedG == null || savedG.first.get() !== leftTemplate) {
                savedLeftTemplateGravity = WeakReference(leftTemplate) to leftTemplate.gravity
            }
            val targetG = Gravity.CENTER_VERTICAL or Gravity.START
            if (leftTemplate.gravity != targetG) {
                noteWriteChange("gravityL:${leftTemplate.gravity}->$targetG")
                leftTemplate.gravity = targetG
                changed = true
            }
        }
        disableClipAncestorChain(leftTemplate, side = "L")
        return changed
    }

    private fun restoreLeftTemplatePadding() {
        val savedG = savedLeftTemplateGravity
        savedLeftTemplateGravity = null
        val saved = savedLeftTemplatePadding
        savedLeftTemplatePadding = null
        isInternalUpdate = true
        try {
            savedG?.first?.get()?.let { ll ->
                if (ll.gravity != savedG.second) ll.gravity = savedG.second
            }
            saved?.viewRef?.get()?.let { v ->
                v.setPadding(saved.left, saved.top, saved.right, saved.bottom)
                v.requestLayout()
            }
        } finally {
            isInternalUpdate = false
        }
    }

    /** v1.2.8：宽态恒定右模板 paddingLeft=0 / paddingRight=rightInsetPx / clipChildren=false；LinearLayout 同步快照锁定 gravity = CENTER_VERTICAL|START */
    private fun applyRightTemplateEdgePadding(rightTemplate: ViewGroup, prefs: RuntimePrefs): Boolean {
        val existing = savedRightTemplatePadding
        if (existing == null || existing.viewRef.get() !== rightTemplate) {
            savedRightTemplatePadding = SavedPadding(
                viewRef = WeakReference(rightTemplate),
                left = rightTemplate.paddingLeft,
                top = rightTemplate.paddingTop,
                right = rightTemplate.paddingRight,
                bottom = rightTemplate.paddingBottom
            )
        }
        var changed = false
        val padRight = resolveRightLyricRightInsetPx(rightTemplate.context, prefs)
        if (rightTemplate.paddingLeft != 0 || rightTemplate.paddingRight != padRight) {
            if (rightTemplate.paddingLeft != 0) noteWriteChange("padRL:${rightTemplate.paddingLeft}->0")
            if (rightTemplate.paddingRight != padRight) noteWriteChange("padR:${rightTemplate.paddingRight}->$padRight")
            rightTemplate.setPadding(0, rightTemplate.paddingTop, padRight, rightTemplate.paddingBottom)
            changed = true
        }
        if (rightTemplate is LinearLayout) {
            val savedG = savedRightTemplateGravity
            if (savedG == null || savedG.first.get() !== rightTemplate) {
                savedRightTemplateGravity = WeakReference(rightTemplate) to rightTemplate.gravity
            }
            val targetG = Gravity.CENTER_VERTICAL or Gravity.START
            if (rightTemplate.gravity != targetG) {
                noteWriteChange("gravityR:${rightTemplate.gravity}->$targetG")
                rightTemplate.gravity = targetG
                changed = true
            }
        }
        disableClipAncestorChain(rightTemplate, side = "R")
        return changed
    }

    private fun resetCurrentLyricClock(startNow: Boolean, isPlaying: Boolean) {
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

    private fun computeCurrentLyricElapsedMs(): Long {
        if (currentLyricStartUptimeMs <= 0L) return 0L
        val now = SystemClock.uptimeMillis()
        val pendingPause = if (currentLyricPauseStartUptimeMs > 0L) {
            (now - currentLyricPauseStartUptimeMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return (now - currentLyricStartUptimeMs - currentLyricPausedAccumMs - pendingPause).coerceAtLeast(0L)
    }

    /** v1.3.11：仅对内层模板链解除 clipChildren / clipToPadding，绝不触碰 clipToOutline（防展开首帧圆角变直角）；
     *  v1.4.5-fix18 起共同父容器 realCapContainer 一并纳入（只动 clipChildren/clipToPadding 且快照还原）——
     *  流光子 View 须向两壳间隙延伸作画，父容器不解锁则间隙段被裁掉；纳入后即止步，窗口根仍不碰 */
    private fun disableClipAncestorChain(start: ViewGroup, side: String) {
        val stopAt = realCapContainerRef?.get()
        val rootWin = rootWindowRef?.get()
        var cur: ViewGroup? = start
        var hops = 0
        val diagItems = if (!wideClipLoggedOnce) ArrayList<String>(4) else null
        while (cur != null && hops < 6) {
            if (cur === rootWin) break
            val orig = disableClipWithSnapshot(cur)
            diagItems?.add(
                "${cur.javaClass.simpleName}(cc=${if (orig.clipChildren) 1 else 0}," +
                    "cp=${if (orig.clipToPadding) 1 else 0}," +
                    "co=${if (orig.clipToOutline) 1 else 0})"
            )
            if (cur === stopAt) break
            cur = cur.parent as? ViewGroup
            hops++
        }
        if (diagItems != null && diagItems.isNotEmpty()) {
            wideClipLoggedOnce = true
            val msg = "[WIDE_CLIP] side=$side unlocked=${diagItems.joinToString(" -> ")}"
            YLog.info(msg = msg)
            EventHistoryStore.record(msg)
        }
    }

    private fun disableClipWithSnapshot(vg: ViewGroup): SavedClipState {
        val key = System.identityHashCode(vg)
        val saved = savedClipStates[key] ?: SavedClipState(
            viewRef = WeakReference(vg),
            clipChildren = vg.clipChildren,
            clipToPadding = vg.clipToPadding,
            clipToOutline = vg.clipToOutline
        ).also { savedClipStates[key] = it }
        if (vg.clipChildren) vg.clipChildren = false
        if (vg.clipToPadding) vg.clipToPadding = false
        return saved
    }

    private fun enforceUnlockedClipStatesInWide() {
        if (savedClipStates.isEmpty()) return
        for (saved in savedClipStates.values) {
            val vg = saved.viewRef.get() ?: continue
            if (vg.clipChildren) vg.clipChildren = false
            if (vg.clipToPadding) vg.clipToPadding = false
        }
    }

    private fun restoreClipStates() {
        if (savedClipStates.isEmpty()) return
        for (saved in savedClipStates.values) {
            val vg = saved.viewRef.get() ?: continue
            vg.clipChildren = saved.clipChildren
            vg.clipToPadding = saved.clipToPadding
        }
        savedClipStates.clear()
    }

    private fun restoreRightTemplatePadding() {
        val savedG = savedRightTemplateGravity
        savedRightTemplateGravity = null
        val saved = savedRightTemplatePadding
        savedRightTemplatePadding = null
        isInternalUpdate = true
        try {
            savedG?.first?.get()?.let { ll ->
                if (ll.gravity != savedG.second) ll.gravity = savedG.second
            }
            saved?.viewRef?.get()?.let { v ->
                v.setPadding(saved.left, saved.top, saved.right, saved.bottom)
                v.requestLayout()
            }
        } finally {
            isInternalUpdate = false
        }
    }

    private fun restoreContainerWidthConstraints(
        reason: String,
        shellStillVisible: Boolean = isLeftShellVisible()
    ): String {
        if (savedWidthConstraints.isEmpty()) return "none"
        val details = ArrayList<String>(savedWidthConstraints.size)
        val isChargingTakeover = reason == "wave_hidden_by_system"
        var anyWidthChanged = false
        isInternalUpdate = true
        try {
            for (saved in savedWidthConstraints.values) {
                val v = saved.viewRef.get() ?: continue
                val lp = v.layoutParams
                if (lp != null) {
                    val curW = lp.width
                    if (curW != saved.lpWidth) {
                        lp.width = saved.lpWidth
                        v.layoutParams = lp
                        anyWidthChanged = true
                        if (isChargingTakeover || shellStillVisible) {
                            v.requestLayout()
                        }
                    } else if (isChargingTakeover) {
                        v.requestLayout()
                    }
                    details.add("${v.javaClass.simpleName}:${curW}->${saved.lpWidth}")
                }
            }
            savedWidthConstraints.clear()
            // v1.3.20：按壳可见性分流——仅充电接管或壳仍在屏（如充电借壳、手动暂停收窄）时同步重排外层壳与根窗口；整岛离屏与展开大卡静默写回宽度
            val needsFullTreeRelayout = isChargingTakeover || (anyWidthChanged && shellStillVisible)
            if (needsFullTreeRelayout) {
                capLeftRef?.get()?.requestLayout()
                capRightRef?.get()?.requestLayout()
                rootWindowRef?.get()?.requestLayout()
            }
        } finally {
            isInternalUpdate = false
        }
        val needsFullTreeRelayout = isChargingTakeover || (anyWidthChanged && shellStillVisible)
        if (needsFullTreeRelayout) {
            val capturedGen = wideGeneration
            mainHandler.post {
                if (capturedGen == wideGeneration && activeMode == RenderMode.NONE) {
                    leftTemplateRef?.get()?.requestLayout()
                    rightTemplateRef?.get()?.requestLayout()
                    capLeftRef?.get()?.requestLayout()
                    capRightRef?.get()?.requestLayout()
                    rootWindowRef?.get()?.requestLayout()
                }
            }
        }
        return if (details.isEmpty()) "none" else details.joinToString(",")
    }

    private fun ensureLeftInfoInjected(leftTemplate: ViewGroup, prefs: RuntimePrefs): LinearLayout? {
        val existing = leftInfoContainerRef?.get()?.takeIf { it.parent === leftTemplate }
            ?: (leftTemplate.findViewWithTag<View>(Const.TAG_LEFT_INFO_CONTAINER) as? LinearLayout)
        if (existing != null) {
            leftInfoContainerRef = WeakReference(existing)
            injectedLeftHostRef = WeakReference(leftTemplate)
            leftTitleViewRef = (existing.findViewWithTag<View>(Const.TAG_LEFT_TITLE_TV) as? TrackMarqueeView)
                ?.let { WeakReference(it) }
            leftArtistViewRef = (existing.findViewWithTag<View>(Const.TAG_LEFT_ARTIST_TV) as? TrackMarqueeView)
                ?.let { WeakReference(it) }
            val dynamicW = computeLeftInfoWidthPx(leftTemplate, prefs)
            val albumIcon = findChildByIdName(leftTemplate, Const.ID_ICON_IMAGE_LEFT)
            val dynamicMarginStart = computeLeftInfoMarginStartForParent(leftTemplate, albumIcon, prefs)
            val lp = existing.layoutParams
            if (lp != null) {
                var changed = false
                if (lp.width != dynamicW) {
                    lp.width = dynamicW
                    changed = true
                }
                if (lp is ViewGroup.MarginLayoutParams && lp.marginStart != dynamicMarginStart) {
                    lp.marginStart = dynamicMarginStart
                    changed = true
                }
                if (changed) existing.layoutParams = lp
            }
            return existing
        }

        val ctx = leftTemplate.context

        val titleView = TrackMarqueeView(
            context = ctx,
            textSizeSp = Const.LEFT_TITLE_TEXT_SIZE_SP,
            textColor = Const.LEFT_TITLE_COLOR
        ).apply {
            tag = Const.TAG_LEFT_TITLE_TV
            setCenterWhenStatic(prefs.leftCenterAlign)
        }

        val artistView = TrackMarqueeView(
            context = ctx,
            textSizeSp = Const.LEFT_ARTIST_TEXT_SIZE_SP,
            textColor = Const.LEFT_ARTIST_COLOR
        ).apply {
            tag = Const.TAG_LEFT_ARTIST_TV
            setCenterWhenStatic(prefs.leftCenterAlign)
        }

        val container = LinearLayout(ctx).apply {
            tag = Const.TAG_LEFT_INFO_CONTAINER
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            addView(
                titleView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                artistView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp2px(ctx, 1)
                }
            )
        }

        val albumIcon = findChildByIdName(leftTemplate, Const.ID_ICON_IMAGE_LEFT)
        val lp = buildLeftInfoLayoutParams(leftTemplate, albumIcon, prefs)
        val insertIndex = if (leftTemplate is LinearLayout && albumIcon != null) {
            val idx = leftTemplate.indexOfChild(albumIcon)
            if (idx >= 0) idx + 1 else leftTemplate.childCount
        } else {
            leftTemplate.childCount
        }

        leftTemplate.addView(container, insertIndex, lp)
        leftInfoContainerRef = WeakReference(container)
        injectedLeftHostRef = WeakReference(leftTemplate)
        leftTitleViewRef = WeakReference(titleView)
        leftArtistViewRef = WeakReference(artistView)
        return container
    }

    private fun updateLeftInfoContent(prefs: RuntimePrefs) {
        isInternalUpdate = true
        try {
            updateLeftInfoContentLocked(prefs)
        } finally {
            isInternalUpdate = false
        }
    }

    private fun updateLeftInfoContentLocked(prefs: RuntimePrefs, allowAlphaRestore: Boolean = true) {
        val container = leftInfoContainerRef?.get() ?: return
        val titleView = leftTitleViewRef?.get() ?: return
        val artistView = leftArtistViewRef?.get() ?: return

        titleView.setCenterWhenStatic(prefs.leftCenterAlign)
        artistView.setCenterWhenStatic(prefs.leftCenterAlign)

        if (cachedTrackTitle.isBlank()) {
            return
        }

        titleView.setText(cachedTrackTitle)
        if (titleView.visibility != View.VISIBLE) {
            titleView.visibility = View.VISIBLE
        }
        if (cachedTrackArtist.isNotBlank()) {
            artistView.setText(cachedTrackArtist)
            if (artistView.visibility != View.VISIBLE) {
                artistView.visibility = View.VISIBLE
            }
        } else {
            // F3：缺歌手时显式清空歌手文本并隐藏歌手行，防残留上一首歌手；LinearLayout.CENTER_VERTICAL 自动垂直居中歌名
            artistView.clearText()
            if (artistView.visibility != View.GONE) {
                artistView.visibility = View.GONE
            }
        }
        if (container.visibility != View.VISIBLE) {
            container.visibility = View.VISIBLE
        }
        // F3 + M6 护栏：仅已确认宽态且拿到根视图确认非动画期（minScale >= 阈值）且非预写路径时才恢复 alpha=1f；判据不明绝不碰 alpha
        val rootForAlpha = rootWindowRef?.get() ?: leftTemplateRef?.get()?.rootView
        val stableWideConfirmed = allowAlphaRestore &&
            activeMode == RenderMode.WIDE_V11 &&
            rootForAlpha != null &&
            computeCurrentCapsMinScale(rootForAlpha) >= Const.GLOW_AND_LOCK_MIN_SCALE
        if (stableWideConfirmed) {
            if (container.alpha != 1f) container.alpha = 1f
            if (titleView.alpha != 1f) titleView.alpha = 1f
            if (cachedTrackArtist.isNotBlank() && artistView.alpha != 1f) artistView.alpha = 1f
        }
    }

    private fun ensureRightIntroCountdownInjected(waveParent: ViewGroup, prefs: RuntimePrefs): InterludeCountdownView? {
        val targetWidthPx = computeRightLyricWidthPx(waveParent.context, prefs)
        val existing = rightIntroCdViewRef?.get()?.takeIf { it.parent === waveParent }
            ?: (waveParent.findViewWithTag<View>(Const.TAG_RIGHT_INTRO_COUNTDOWN) as? InterludeCountdownView)
        if (existing != null) {
            rightIntroCdViewRef = WeakReference(existing)
            existing.setPreferredWidthPx(targetWidthPx)
            val targetLinearLeftMargin = resolveLinearLyricLeftMarginPx(waveParent.context, prefs)
            val lp = existing.layoutParams
            if (lp != null) {
                var changed = false
                if (lp.width != targetWidthPx) {
                    lp.width = targetWidthPx
                    changed = true
                }
                if (waveParent is LinearLayout && lp is ViewGroup.MarginLayoutParams && lp.leftMargin != targetLinearLeftMargin) {
                    lp.leftMargin = targetLinearLeftMargin
                    changed = true
                }
                if (changed) existing.layoutParams = lp
            }
            return existing
        }

        val ctx = waveParent.context
        val view = InterludeCountdownView(
            context = ctx,
            positionProvider = positionProvider,
            enforceMaxRemainingWindow = false,
            centerInView = true,
            // v1.4.5-fix11：仅前奏实例带流光能力；间奏实例不传、结构性禁用
            shimmerCapable = true,
            // v1.4.5-fix14 追加裁决：合并层可用时倒计时视图彻底不画背景（防两层叠光），不可用时框内椭圆降级自画
            shimmerLayerAvailableProvider = { islandShimmer.isLayerAvailable() },
            // v1.4.5-fix14 实施审 M1：流光意图唯一出口——setShimmerEnabled/stopAndHide 都经此进合并层
            onShimmerIntentChanged = { enabled ->
                val p = readPrefs()
                islandShimmer.syncCountdown(enabled, p.shimmerStyle, p.shimmerLevel, currentKaraokeSungColor)
            },
            onBoundaryEvent = { boundaryReason, curPos, remaining ->
                val cdVis = rightIntroCdViewRef?.get()?.visibility == View.VISIBLE
                val rightMode = if (isInIntroWaveLeadPhase()) "INTRO_WAVE" else "INTRO_CD"
                val msg = "[INTRO_STATE] inIntro=$inIntroMode firstVocalMs=$currentIntroLearnedMs pos=$curPos remaining=$remaining countdownVisible=$cdVis rightMode=$rightMode reason=$boundaryReason"
                YLog.info(msg = msg)
                EventHistoryStore.recordDiagOnly(msg)
                if (boundaryReason == "grace_expired") {
                    mainHandler.post {
                        renderBreaker.execute {
                            if (inIntroMode && sessionActive) {
                                reconcileState(forceText = true)
                            }
                        }
                    }
                }
            }
        ).apply {
            tag = Const.TAG_RIGHT_INTRO_COUNTDOWN
            // v1.4.5-fix10 F10-4：待机 INVISIBLE 先完成测量，免首次显形首帧 rightContent=[-1..-1]；停用仍走 stopAndHide 的 GONE，不长期占位
            visibility = View.INVISIBLE
            setPreferredWidthPx(targetWidthPx)
            setAccentColor(currentKaraokeSungColor)
        }

        val lp = buildRightLyricLayoutParams(waveParent, targetWidthPx, prefs)
        waveParent.addView(view, lp)
        rightIntroCdViewRef = WeakReference(view)
        return view
    }

    private fun ensureRightInterludeCountdownInjected(
        waveParent: ViewGroup,
        waveView: View?
    ): InterludeCountdownView? {
        val ctx = waveParent.context
        val waveW = (waveView?.width?.takeIf { it > 0 } ?: waveView?.measuredWidth?.takeIf { it > 0 } ?: dp2px(ctx, 22))
        val endMarginPx = waveW + dp2px(ctx, 6)

        val existing = rightInterludeCdViewRef?.get()?.takeIf { it.parent === waveParent }
            ?: (waveParent.findViewWithTag<View>(Const.TAG_RIGHT_INTERLUDE_COUNTDOWN) as? InterludeCountdownView)
        if (existing != null) {
            rightInterludeCdViewRef = WeakReference(existing)
            val lp = existing.layoutParams
            if (lp is ViewGroup.MarginLayoutParams && waveParent !is LinearLayout && lp.marginEnd != endMarginPx) {
                lp.marginEnd = endMarginPx
                existing.layoutParams = lp
            }
            return existing
        }

        val view = InterludeCountdownView(
            context = ctx,
            positionProvider = positionProvider,
            enforceMaxRemainingWindow = false,
            centerInView = false
        ).apply {
            tag = Const.TAG_RIGHT_INTERLUDE_COUNTDOWN
            // v1.4.5-fix10 F10-4：同前奏倒计时，待机 INVISIBLE 先测量；间奏波浪相位也靠它占位防布局跳动
            visibility = View.INVISIBLE
            setAccentColor(currentKaraokeSungColor)
        }

        val lp = buildRightInterludeCountdownLayoutParams(waveParent, waveView, endMarginPx)
        if (waveParent is LinearLayout && waveView != null) {
            val idx = waveParent.indexOfChild(waveView)
            if (idx >= 0) {
                waveParent.addView(view, idx, lp)
            } else {
                waveParent.addView(view, lp)
            }
        } else {
            waveParent.addView(view, lp)
        }
        rightInterludeCdViewRef = WeakReference(view)
        return view
    }

    private fun buildRightInterludeCountdownLayoutParams(
        parent: ViewGroup,
        waveView: View?,
        endMarginPx: Int
    ): ViewGroup.LayoutParams {
        val gapPx = dp2px(parent.context, 6)
        return when (parent) {
            is LinearLayout -> LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = gapPx
            }
            is RelativeLayout -> RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.CENTER_VERTICAL)
                if (waveView != null && waveView.id != View.NO_ID) {
                    addRule(RelativeLayout.START_OF, waveView.id)
                    marginEnd = gapPx
                } else {
                    addRule(RelativeLayout.ALIGN_PARENT_END)
                    marginEnd = endMarginPx
                }
            }
            is FrameLayout -> FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.END
            ).apply {
                marginEnd = endMarginPx
            }
            else -> buildGenericMarginOrConstraintLp(
                parent = parent,
                widthPx = ViewGroup.LayoutParams.WRAP_CONTENT,
                anchorStartToEndId = View.NO_ID,
                alignEndToParent = true,
                marginStartPx = 0,
                marginEndPx = endMarginPx
            )
        }
    }

    private fun ensureRightLyricInjected(
        waveParent: ViewGroup,
        prefs: RuntimePrefs,
        waveView: View? = musicWaveRef?.get()
    ): LyricScrollView? {
        val targetLyricPx = computeRightLyricWidthForModePx(waveParent.context, prefs, waveView)
        val targetEndMargin = computeRightLyricEndMarginPx(waveParent, prefs, waveView)
        val existing = rightLyricViewRef?.get()?.takeIf { it.parent === waveParent }
            ?: (waveParent.findViewWithTag<View>(Const.TAG_RIGHT_LYRIC_VIEW) as? LyricScrollView)
        if (existing != null) {
            rightLyricViewRef = WeakReference(existing)
            existing.setPreferredWidthPx(targetLyricPx)
            existing.setDualLineEnabled(prefs.dualLineLyricEnabled)
            existing.setSubTextSizeSp(prefs.dualLineSubTextSizeSp)
            existing.setCenterShortText(prefs.lyricCenterAlign)
            existing.setLineDissolveEnabled(prefs.lineDissolveEnabled)
            existing.setLyricStyle(prefs.lyricStyle)
            existing.setLineTransitionMode(prefs.lineTransitionMode)
            existing.setInterludeDotsEnabled(prefs.interludeDotsEnabled)
            currentGlowPalette?.let { existing.setPaletteColors(it) }
            val targetLinearLeftMargin = resolveLinearLyricLeftMarginPx(waveParent.context, prefs)
            val lp = existing.layoutParams
            if (lp != null) {
                var changed = false
                if (lp.width != targetLyricPx) {
                    lp.width = targetLyricPx
                    changed = true
                }
                if (lp is ViewGroup.MarginLayoutParams) {
                    if (waveParent is LinearLayout) {
                        if (lp.leftMargin != targetLinearLeftMargin) {
                            lp.leftMargin = targetLinearLeftMargin
                            changed = true
                        }
                        if (lp.rightMargin != targetEndMargin) {
                            lp.rightMargin = targetEndMargin
                            changed = true
                        }
                    } else if (lp.marginEnd != targetEndMargin) {
                        lp.marginEnd = targetEndMargin
                        changed = true
                    }
                }
                if (changed) existing.layoutParams = lp
            }
            return existing
        }

        val ctx = waveParent.context
        val view = LyricScrollView(ctx).apply {
            tag = Const.TAG_RIGHT_LYRIC_VIEW
            setPreferredWidthPx(targetLyricPx)
            setTextSizeSp(prefs.lyricTextSizeSp)
            setKaraokeEnabled(prefs.karaokeEnabled)
            setKaraokeSungColor(currentKaraokeSungColor)
            setDualLineEnabled(prefs.dualLineLyricEnabled)
            setSubTextSizeSp(prefs.dualLineSubTextSizeSp)
            setCenterShortText(prefs.lyricCenterAlign)
            setLineDissolveEnabled(prefs.lineDissolveEnabled)
            setLyricStyle(prefs.lyricStyle)
            setLineTransitionMode(prefs.lineTransitionMode)
            setInterludeDotsEnabled(prefs.interludeDotsEnabled)
            currentGlowPalette?.let { setPaletteColors(it) }
        }

        val lp = buildRightLyricLayoutParams(waveParent, targetLyricPx, prefs, targetEndMargin)
        if (waveParent is LinearLayout && waveView != null) {
            val idx = waveParent.indexOfChild(waveView)
            if (idx >= 0) {
                waveParent.addView(view, idx, lp)
            } else {
                waveParent.addView(view, lp)
            }
        } else {
            waveParent.addView(view, lp)
        }
        rightLyricViewRef = WeakReference(view)
        return view
    }

    private fun buildLeftInfoLayoutParams(
        parent: ViewGroup,
        albumIcon: View?,
        prefs: RuntimePrefs
    ): ViewGroup.LayoutParams {
        val ctx = parent.context
        val marginStartPx = resolveLeftInfoMarginStartPx(ctx, prefs)
        val explicitW = computeLeftInfoWidthPx(parent, prefs)
        return when (parent) {
            is LinearLayout -> LinearLayout.LayoutParams(
                explicitW,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = marginStartPx
            }
            is RelativeLayout -> RelativeLayout.LayoutParams(
                explicitW,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.CENTER_VERTICAL)
                if (albumIcon != null && albumIcon.id != View.NO_ID) {
                    addRule(RelativeLayout.END_OF, albumIcon.id)
                }
                marginStart = marginStartPx
            }
            is FrameLayout -> FrameLayout.LayoutParams(
                explicitW,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.START
            ).apply {
                marginStart = computeLeftInfoMarginStartForParent(parent, albumIcon, prefs)
            }
            else -> buildGenericMarginOrConstraintLp(
                parent = parent,
                widthPx = explicitW,
                anchorStartToEndId = albumIcon?.id ?: View.NO_ID,
                alignEndToParent = false,
                marginStartPx = marginStartPx,
                marginEndPx = 0
            )
        }
    }

    private fun buildRightLyricLayoutParams(
        parent: ViewGroup,
        explicitWidthPx: Int,
        prefs: RuntimePrefs,
        endMarginPx: Int = 0
    ): ViewGroup.LayoutParams {
        val linearLeftMargin = resolveLinearLyricLeftMarginPx(parent.context, prefs)
        return when (parent) {
            is LinearLayout -> LinearLayout.LayoutParams(
                explicitWidthPx,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                leftMargin = linearLeftMargin
                rightMargin = endMarginPx
            }
            is RelativeLayout -> RelativeLayout.LayoutParams(
                explicitWidthPx,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.CENTER_VERTICAL)
                addRule(RelativeLayout.ALIGN_PARENT_END)
                marginStart = 0
                marginEnd = endMarginPx
            }
            is FrameLayout -> FrameLayout.LayoutParams(
                explicitWidthPx,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.END
            ).apply {
                marginStart = 0
                marginEnd = endMarginPx
            }
            else -> buildGenericMarginOrConstraintLp(
                parent = parent,
                widthPx = explicitWidthPx,
                anchorStartToEndId = View.NO_ID,
                alignEndToParent = true,
                marginStartPx = 0,
                marginEndPx = endMarginPx
            )
        }
    }

    private fun buildGenericMarginOrConstraintLp(
        parent: ViewGroup,
        widthPx: Int,
        anchorStartToEndId: Int,
        alignEndToParent: Boolean,
        marginStartPx: Int,
        marginEndPx: Int
    ): ViewGroup.LayoutParams {
        val lp = runCatching {
            val m = ViewGroup::class.java.getDeclaredMethod("generateDefaultLayoutParams")
            m.isAccessible = true
            m.invoke(parent) as? ViewGroup.LayoutParams
        }.getOrNull() ?: ViewGroup.MarginLayoutParams(
            widthPx,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        lp.width = widthPx
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        if (lp is ViewGroup.MarginLayoutParams) {
            lp.marginStart = marginStartPx
            lp.marginEnd = marginEndPx
        }
        runCatching {
            val cls = lp.javaClass
            if (cls.name.contains("ConstraintLayout")) {
                cls.getField("topToTop").setInt(lp, 0)
                cls.getField("bottomToBottom").setInt(lp, 0)
                if (anchorStartToEndId != View.NO_ID) {
                    cls.getField("startToEnd").setInt(lp, anchorStartToEndId)
                }
                if (alignEndToParent) {
                    cls.getField("endToEnd").setInt(lp, 0)
                }
            }
        }
        return lp
    }

    // ==================== 窄形态渲染（冻结不变） ====================

    private fun applyNarrowMode(forceText: Boolean) {
        val template = leftTemplateRef?.get() ?: return
        val switcher = switcherRef?.get() ?: return

        if (inInterludeMode) {
            restoreNarrowSwitcherToFactory("interlude")
            activeMode = RenderMode.NARROW_V10
            return
        }

        val text = currentLyric
        if (text.isBlank()) return

        isInternalUpdate = true
        try {
            captureOriginalSwitcherStylesIfNeeded(switcher)

            val textViews = collectSwitcherTextViews(switcher)
            for (tv in textViews) {
                configureNarrowMarqueeTextView(tv)
            }

            val lp = switcher.layoutParams
            if (lp != null && lp.width == 0) {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                switcher.layoutParams = lp
            }
            if (switcher.visibility != View.VISIBLE) {
                switcher.visibility = View.VISIBLE
            }

            if (forceText || text != lastRenderedNarrowText) {
                setTextViaSwitcher(switcher, textViews, text)
                lastRenderedNarrowText = text
                scheduleNarrowMarqueeKick(switcher, textViews)
            }

            activeMode = RenderMode.NARROW_V10
            switcher.requestLayout()
            template.requestLayout()
        } finally {
            isInternalUpdate = false
        }
    }

    private fun setTextViaSwitcher(switcher: ViewGroup, textViews: List<TextView>, text: String) {
        if (switcher is TextSwitcher) {
            switcher.setText(text)
            return
        }
        val invoked = runCatching {
            val m = switcher.javaClass.getMethod("setText", CharSequence::class.java)
            m.invoke(switcher, text)
            true
        }.getOrDefault(false)

        if (!invoked) {
            val invokedCurr = runCatching {
                val m = switcher.javaClass.getMethod("setCurrentText", CharSequence::class.java)
                m.invoke(switcher, text)
                true
            }.getOrDefault(false)

            if (!invokedCurr) {
                val primary = textViews.firstOrNull { resolveIdName(it) == Const.ID_TEXT_LEFT1 }
                    ?: textViews.firstOrNull()
                primary?.let {
                    it.visibility = View.VISIBLE
                    it.text = text
                }
            }
        }
    }

    private fun clearActiveLyricTextOnly() {
        isInternalUpdate = true
        try {
            switcherRef?.get()?.let { sw ->
                for (tv in collectSwitcherTextViews(sw)) {
                    tv.text = ""
                    tv.isSelected = false
                }
            }
            rightLyricViewRef?.get()?.clearAndHide()
            rightIntroCdViewRef?.get()?.stopAndHide()
            rightInterludeCdViewRef?.get()?.stopAndHide()
        } finally {
            isInternalUpdate = false
        }
    }

    // ==================== 逐项快照还原 ====================

    // v1.4.5-fix12 F12-1：展开态阻断原因判定（展开卡撑高根视图 / 展开卡可见两条守卫）
    private fun isExpandBlocker(): Boolean =
        lastShouldShowBlocker == "root_height" || lastShouldShowBlocker == "cap_expanded"

    // v1.4.5-fix20 P0-1（Gemini 方案、Quick 裁决）：几何直判——展开卡打开约 330ms 后系统藏掉收缩态
    // 左模板，blocker 短路成 left_not_visible，isExpandBlocker() 永远读不到展开态，兜底延期从未生效；
    // 改为直接读视图几何（只读、零副作用），与 blocker 语义解耦
    private fun isIslandPhysicallyExpanded(): Boolean {
        val root = rootWindowRef?.get() ?: leftTemplateRef?.get()?.rootView
        if (root != null && root.height > Const.ISLAND_EXPANDED_HEIGHT_THRESHOLD_PX) return true
        val capExp = capExpandedRef?.get()
        if (capExp != null && capExp.visibility == View.VISIBLE && capExp.height > 0) return true
        return false
    }

    /** v1.4.5-fix20 P0-1：兜底日志的几何证据字段——验收 physExp 前提是否成立就看这三项 */
    private fun physExpDiag(): String {
        val capExp = capExpandedRef?.get()
        val capExpVis = capExp != null && capExp.visibility == View.VISIBLE && capExp.height > 0
        return "physExp=${isIslandPhysicallyExpanded()} rootH=${rootWindowRef?.get()?.height ?: -1} capExpVis=$capExpVis"
    }

    // v1.4.5-fix12 F12-1（R1 保险）：波浪若位于展开卡视图内部（与胶囊共用实例），压制会误伤展开卡自身显示——
    // 沿父链最多 8 跳核对，命中即不压制，本修法对该机型自动失效而不引入新问题
    private fun waveUnderCapExpanded(): Boolean {
        val capExp = capExpandedRef?.get() ?: return false
        // 沿 View 父链上行（ViewParent 接口本身没有 parent 属性，逐级 as? View，到非 View 父级自然终止）
        var v: View? = musicWaveRef?.get()
        var hops = 0
        while (v != null && hops < 8) {
            if (v === capExp) return true
            v = v.parent as? View
            hops++
        }
        return false
    }

    private fun restoreAllToFactory(reason: String) {
        // v1.4.5-fix18 防抖轻量版（Quick 坚持入 P0）：接管后 300ms 内由瞬时阻断（模板脱落/状态 hide、
        // 非展开类、仍在播、会话在）触发的还原先挂起，满保持期复核仍不过才真还原——41/73 次接管
        // <0.6s 被还原是闪与默认岛露出的直接来源；暂停/会话结束/展开收起被条件天然排除，不走此路
        if (!minHoldBypass && activeMode != RenderMode.NONE &&
            (reason == "left_template_detached" || reason == "state_changed_hide") &&
            !isExpandBlocker() &&
            lastTakeoverElapsedMs > 0L &&
            SystemClock.elapsedRealtime() - lastTakeoverElapsedMs < MIN_HOLD_AFTER_TAKEOVER_MS &&
            isEffectivelyPlaying() && sessionActive
        ) {
            // v1.4.5-fix18 实施审 M6：单一句柄——已挂起时只更新 reason，不重复 post、不重复打日志
            pendingMinHoldReason = reason
            if (pendingMinHoldRunnable == null) {
                val delayMs = MIN_HOLD_AFTER_TAKEOVER_MS -
                    (SystemClock.elapsedRealtime() - lastTakeoverElapsedMs) + 20L
                val mhMsg = "[MIN_HOLD] defer restore reason=$reason delayMs=$delayMs"
                YLog.info(msg = mhMsg)
                EventHistoryStore.recordDiagOnly(mhMsg)
                val task = Runnable {
                    pendingMinHoldRunnable = null
                    if (activeMode == RenderMode.NONE) return@Runnable
                    if (shouldShowOnCollapsedCapsule(readPrefs())) return@Runnable
                    minHoldBypass = true
                    try {
                        restoreAllToFactory(pendingMinHoldReason)
                    } finally {
                        minHoldBypass = false
                    }
                }
                pendingMinHoldRunnable = task
                mainHandler.postDelayed(task, delayMs)
            }
            return
        }
        cancelPendingMinHold()
        lastHideReason = reason
        // v1.4.5-fix15：新一轮从这里开始，落地时间戳与 blocker 序列先清零（Quick 裁决，治 passToTakeoverMs 跨轮旧值）
        resetLandRound()
        // v1.4.5-fix13 C：全量还原先把整岛流光的背景层解开，原厂背景原样归还（其余还原逻辑不感知此层）
        islandShimmer.unwrapAll("restore_all:$reason")
        // v1.4.5-fix13 实施审 A-S2：窄态压制复核一并显式取消，免清场后残留 Runnable 空跑一轮（其入口自查标记、本无害）
        narrowSuppressReleaseRunnable?.let { mainHandler.removeCallbacks(it) }
        narrowSuppressReleaseRunnable = null
        narrowSuppressReleasePending = false
        val wasShowing = activeMode != RenderMode.NONE
        val prevMode = activeMode
        val prevRightState = lastRenderedRightState
        val prevPrefs = lastAppliedWidePrefs
        val shellStillVisible = isLeftShellVisible()
        val leftTplForFast = leftTemplateRef?.get()
        val rightTplForFast = resolveWaveParent()

        cancelPendingPauseRestore()
        cancelPendingHideSettle()
        cancelPositiveMeasurePoll()
        cancelHoldSettleCheck()
        cancelIntroWaveLeadEndCheck()
        cancelInterludeWaveEndCheck()
        cancelWideEntryWidthSamples()
        cancelReentryVisibilityPoll(resetAttempts = true)
        lastAppliedWidePrefs = null
        // v1.4.5-fix11（Quick 二轮补）：重入取消帧预算在公共路径复位——左壳可见路径不走快速恢复分支，
        // 旧版只在该分支复位会让预算首次用完后永久失效（fix12 删 ENTER_CANCEL 后此复位保留，fix9 重入仍在用）
        reentryCancelUsed = false
        if (wasShowing) {
            lastHideElapsedMs = SystemClock.elapsedRealtime()
            // vc55（fix21）：PERF 隐藏时长起算（已有起算点则不覆盖，取最早一次 hide）
            if (perfHiddenSinceMs == 0L) perfHiddenSinceMs = lastHideElapsedMs
        }

        val hasAnySnapshot = savedWidthConstraints.isNotEmpty() ||
            savedClipStates.isNotEmpty() ||
            savedLeftTemplatePadding != null ||
            savedRightTemplatePadding != null ||
            savedRightTemplateGravity != null ||
            savedSwitcherStyle != null ||
            savedTvStyles.isNotEmpty() ||
            savedWaveVisibility != null
        // v1.3.18：wasShowing=false 且全部快照为空时纯空转返回，零 requestLayout、零快照写回、零测量轮询
        if (!wasShowing && !hasAnySnapshot) {
            if (reason != "state_changed_hide" || shellStillVisible) {
                clearFastResumeState()
            }
            unregisterPreDrawVisualLock()
            resetVisualLockTranslations()
            if (reason == "state_changed_hide" || reason == "left_template_detached" || reason == "wave_hidden_by_system") {
                awaitingValidVisibleReentry = true
            }
            return
        }

        val armFastResume = reason == "state_changed_hide" &&
            !shellStillVisible &&
            prevMode == RenderMode.WIDE_V11 &&
            isEffectivelyPlaying() &&
            sessionActive &&
            leftTplForFast != null &&
            rightTplForFast != null &&
            prevPrefs != null

        // v1.4.5-fix12 F12-1（Quick 两轮收敛）：展开卡收起的 hide 左壳仍可见、快速恢复不武装，波浪被原样还原成
        // VISIBLE 后停在原厂偏左位置，收起动画窗内被画出即「那一帧闪」。仅对「阻断原因＝展开态」这一类 hide
        // 同样压制（不进重入模式）；暂停/离屏/充电/关岛等其余 hide 一律不碰，释放点全部事件驱动（见兜底与布局回调）
        val suppressExpand = !armFastResume &&
            reason == "state_changed_hide" &&
            isExpandBlocker() &&
            prevMode == RenderMode.WIDE_V11 &&
            isEffectivelyPlaying() &&
            sessionActive &&
            !waveUnderCapExpanded()

        cancelPendingLeftReassert()
        // v1.4.5-fix9 P2：可快速恢复的 hide 不注销监听器、切重入模式待重展开首帧拦截；其余照旧注销
        if (armFastResume && preDrawLockOwnerRef?.get() != null) {
            preDrawReentryMode = true
            // reentryCancelUsed 已在本函数公共路径统一复位（fix11），此处不再重复
            lastReentryLogKey = ""
        } else {
            unregisterPreDrawVisualLock()
        }
        resetVisualLockTranslations()
        glowRenderer.hideAndStop(reason)
        restoreNarrowSwitcherToFactory(reason)
        restoreWideLeftToFactory()
        // v1.4.5-fix10 F10-1 + fix12 F12-1：必将快速恢复的 hide 与展开类 hide 压制波浪还原；其余 hide 照常完整还原
        restoreWideRightToFactory(
            reason,
            suppressWaveRestore = armFastResume || suppressExpand,
            suppressKind = if (suppressExpand) "expand" else null
        )
        val restoredDetail = restoreContainerWidthConstraints(reason, shellStillVisible = shellStillVisible)
        restoreClipStates()
        restoreLeftTemplatePadding()
        restoreRightTemplatePadding()
        activeMode = RenderMode.NONE
        lastRenderedNarrowText = ""
        lastRenderedRightText = ""
        lastRenderedNextLyric = ""
        lastRenderedWordSpans = emptyList()
        lastRenderedRightState = -1
        fastResumeState = if (armFastResume) {
            WideFastResumeState(
                leftTemplateRef = WeakReference(leftTplForFast!!),
                rightTemplateRef = WeakReference(rightTplForFast!!),
                trackKey = currentTrackKey(),
                prefs = prevPrefs!!,
                rightState = prevRightState
            )
        } else {
            null
        }
        if (reason == "state_changed_hide" || reason == "left_template_detached" || reason == "wave_hidden_by_system") {
            awaitingValidVisibleReentry = true
        }
        // v1.4.5-fix12 F12-C 诊断：还原日志附最近一次 shouldShow 阻断原因，零行为改动，供定位前奏切首句的空转还原
        val rrMsg = "[SESSION_LIFE] renderer_restore reason=$reason | wasShowing=$wasShowing | blocker=$lastShouldShowBlocker | containers=[$restoredDetail]"
        YLog.info(msg = rrMsg)
        EventHistoryStore.record(rrMsg)
        if (wasShowing) {
            val crMsg = "[CAPSULE_RESTORE] 已按快照还原原厂胶囊外观 (reason=$reason)"
            YLog.info(msg = crMsg)
            EventHistoryStore.record(crMsg)
        }
        if (reason == "state_changed_hide" && !isPlaybackPlaying) {
            EventHistoryStore.replayRecentThrottled("state_changed_hide_non_playing")
        }
    }

    private fun restoreNarrowSwitcherToFactory(reason: String) {
        marqueeKickRunnable?.let { mainHandler.removeCallbacks(it) }
        marqueeKickRunnable = null
        // v1.3.15：仅在确实进入过窄态并改过 switcher 样式时才还原文本与可见性；
        // 宽态退场绝不清空 tv.text 或强设 GONE，防止抹掉原厂充电卡共用 VivoTextSwitcher 的「正在充电」文案。
        if (savedSwitcherStyle == null && savedTvStyles.isEmpty()) {
            lastRenderedNarrowText = ""
            return
        }

        val switcher = switcherRef?.get() ?: return
        val template = leftTemplateRef?.get()

        isInternalUpdate = true
        try {
            val textViews = collectSwitcherTextViews(switcher)
            for (tv in textViews) {
                tv.text = ""
                tv.isSelected = false
                savedTvStyles[System.identityHashCode(tv)]?.let { orig ->
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, orig.textSizePx)
                    tv.ellipsize = orig.ellipsize
                    tv.marqueeRepeatLimit = orig.marqueeRepeatLimit
                    tv.maxWidth = orig.maxWidth
                    tv.isHorizontalFadingEdgeEnabled = orig.fadingEdgeEnabled
                    tv.setFadingEdgeLength(orig.fadingEdgeLength)
                    tv.gravity = orig.gravity
                    tv.isSelected = orig.selected
                }
            }

            savedSwitcherStyle?.let { orig ->
                val lp = switcher.layoutParams
                if (lp != null && (lp.width != orig.lpWidth || lp.height != orig.lpHeight)) {
                    lp.width = orig.lpWidth
                    lp.height = orig.lpHeight
                    switcher.layoutParams = lp
                }
                switcher.visibility = orig.visibility
            } ?: run {
                switcher.visibility = View.GONE
            }

            switcher.requestLayout()
            template?.requestLayout()
            savedSwitcherStyle = null
            savedTvStyles.clear()
            lastRenderedNarrowText = ""
        } finally {
            isInternalUpdate = false
        }
    }

    // v1.4.5-fix20.2：按宿主模板回退藏掉注入的左侧信息容器（清文案＋GONE），幂等。
    // 解绑/换绑会先清 leftInfoContainerRef，还原便无从下手，注入容器残留在模板里撑宽收起胶囊；
    // 本函数不依赖容器引用、只认宿主与 tag，供还原回退、解绑、换绑、自愈四处共用。
    // 藏成功后若宿主正是 injectedLeftHostRef 所指，一并清掉该引用（它的使命已完成）。
    private fun hideInjectedLeftInfo(host: ViewGroup?, src: String): Boolean {
        if (host == null) return false
        val container = host.findViewWithTag<View>(Const.TAG_LEFT_INFO_CONTAINER) as? LinearLayout
            ?: return false
        val oldInternal = isInternalUpdate
        isInternalUpdate = true
        try {
            val wasVisible = container.visibility != View.GONE
            (container.findViewWithTag<View>(Const.TAG_LEFT_TITLE_TV) as? TrackMarqueeView)?.clearText()
            (container.findViewWithTag<View>(Const.TAG_LEFT_ARTIST_TV) as? TrackMarqueeView)?.clearText()
            if (wasVisible) {
                container.visibility = View.GONE
                host.requestLayout()
                val msg = "[LEFT_INFO] hide src=$src"
                YLog.info(msg = msg)
                EventHistoryStore.recordDiagOnly(msg)
            }
        } finally {
            isInternalUpdate = oldInternal
        }
        if (injectedLeftHostRef?.get() === host) {
            injectedLeftHostRef = null
        }
        return true
    }

    private fun restoreWideLeftToFactory() {
        val container = leftInfoContainerRef?.get()
        if (container == null) {
            // v1.4.5-fix20.2：容器引用已被解绑/换绑清掉时，按模板/宿主回退藏容器，不再直接 return 留残留；
            // 实施审建议：两个宿主不是同一个时都试一次，防残留恰在另一个模板上
            val tplHost = leftTemplateRef?.get()
            val injHost = injectedLeftHostRef?.get()
            if (tplHost != null) hideInjectedLeftInfo(tplHost, "restore_fallback")
            if (injHost != null && injHost !== tplHost) hideInjectedLeftInfo(injHost, "restore_fallback")
            return
        }
        isInternalUpdate = true
        try {
            leftTitleViewRef?.get()?.clearText()
            leftArtistViewRef?.get()?.clearText()
            if (container.visibility != View.GONE) {
                container.visibility = View.GONE
            }
            leftTemplateRef?.get()?.requestLayout()
        } finally {
            isInternalUpdate = false
        }
        // v1.4.5-fix20.2：容器已确认藏好，宿主回退引用使命完成，一并清掉
        if (container.visibility == View.GONE && injectedLeftHostRef?.get() === container.parent) {
            injectedLeftHostRef = null
        }
    }

    private fun restoreWideRightToFactory(
        reason: String,
        suppressWaveRestore: Boolean = false,
        suppressKind: String? = null
    ) {
        isInternalUpdate = true
        try {
            rightLyricViewRef?.get()?.clearAndHide()
            rightIntroCdViewRef?.get()?.stopAndHide()
            rightInterludeCdViewRef?.get()?.stopAndHide()
            // v1.3.17：仅原厂显式置隐声波（如充电卡接管）时跳过恢复，避免展开卡 state_changed_hide 将模块自藏的 GONE 声波锁死导致收起后卡窄态
            val systemHidWave = reason == "wave_hidden_by_system"
            cancelSoloFade(preserveTransform = false)
            val wave = musicWaveRef?.get()
            if (systemHidWave) {
                if (suppressWaveRestore || reason == "state_changed_hide") {
                    val skMsg = "[WAVE_SUPPRESS] skip cond=suppress:$suppressWaveRestore,savedVis:$savedWaveVisibility,idMatch:${wave != null && savedWaveIdentity == System.identityHashCode(wave)},sysHid:true"
                    YLog.info(msg = skMsg)
                    EventHistoryStore.recordDiagOnly(skMsg)
                }
            } else if (wave != null) {
                resetSoloWaveTransformIfMatching(wave, resetTranslation = true)
                val waveId = System.identityHashCode(wave)
                val ledger = resolveValidWaveLedger(wave)
                val idMatch = savedWaveIdentity == waveId
                val rebased = !idMatch && ledger != null
                val underExpanded = waveUnderCapExpanded()
                val rightIsMusic = (wave.parent as? View)?.javaClass?.name == Const.CLS_RIGHT_TEMPLATE
                val effectiveOrigVis = when {
                    idMatch && savedWaveVisibility != null -> savedWaveVisibility
                    rebased -> ledger!!.preSelfOrigVis
                    savedWaveVisibility == null && wave.visibility == View.VISIBLE -> View.VISIBLE
                    else -> savedWaveVisibility
                }
                val canArmSuppress = suppressWaveRestore &&
                    !underExpanded &&
                    rightIsMusic &&
                    effectiveOrigVis == View.VISIBLE &&
                    (idMatch || rebased || (savedWaveVisibility == null && wave.visibility == View.VISIBLE))

                if (canArmSuppress) {
                    if (rebased || savedWaveVisibility == null) {
                        savedWaveVisibility = effectiveOrigVis
                        savedWaveIdentity = waveId
                    }
                    writeWaveVis(wave, View.INVISIBLE, "suppress_arm")
                    if (suppressKind == "expand" && waveSuppressKind != "expand" && waveRestoreSuppressed) {
                        waveSuppressKind = "expand"
                        val uMsg = "[WAVE_SUPPRESS] kind_upgrade reason=$reason"
                        YLog.info(msg = uMsg)
                        EventHistoryStore.recordDiagOnly(uMsg)
                    }
                    if (!waveRestoreSuppressed) {
                        waveRestoreSuppressed = true
                        waveSuppressKind = suppressKind
                        suppressAwaitCollapse = false
                        val rebaseTag = if (rebased) " rebased=true" else ""
                        val sMsg = if (suppressKind != null) {
                            "[WAVE_SUPPRESS] armed reason=$reason kind=$suppressKind blocker=$lastShouldShowBlocker$rebaseTag"
                        } else {
                            "[WAVE_SUPPRESS] armed reason=$reason$rebaseTag"
                        }
                        YLog.info(msg = sMsg)
                        EventHistoryStore.recordDiagOnly(sMsg)
                    }
                    scheduleWaveSuppressFallback()
                } else if (waveRestoreSuppressed) {
                    val hMsg = "[WAVE_SUPPRESS] hold reason=snapshot_restore_while_suppressed restoreReason=$reason"
                    YLog.info(msg = hMsg)
                    EventHistoryStore.recordDiagOnly(hMsg)
                } else {
                    if (suppressWaveRestore || reason == "state_changed_hide") {
                        val skMsg = "[WAVE_SUPPRESS] skip cond=suppress:$suppressWaveRestore,savedVis:$savedWaveVisibility,idMatch:$idMatch,sysHid:false,underExp:$underExpanded,rightMusic:$rightIsMusic"
                        YLog.info(msg = skMsg)
                        EventHistoryStore.recordDiagOnly(skMsg)
                    }
                    restoreWaveVisibilityIfSaved(wave)
                }
            } else if (suppressWaveRestore || reason == "state_changed_hide") {
                val skMsg = "[WAVE_SUPPRESS] skip cond=suppress:$suppressWaveRestore,savedVis:$savedWaveVisibility,idMatch:false,sysHid:false,waveNull:true"
                YLog.info(msg = skMsg)
                EventHistoryStore.recordDiagOnly(skMsg)
            }
            rightTemplateRef?.get()?.requestLayout()
            lastRenderedRightText = ""
            lastRenderedRightState = -1
        } finally {
            isInternalUpdate = false
        }
    }

    // v1.4.5-fix13 A（Quick 裁决必须改 4）：压制释放全收拢一处并打日志——fix12 盲区正是各释放点散落且不打日志，
    // 定位只能靠猜。此后任何路径清标记必经此函数（只清标记型）或 restoreWaveVisibilityIfSaved（还原落地型），
    // 日志 [WAVE_SUPPRESS] released reason= 即释放点的完整名单
    // v1.4.5-fix20.3 C1：统一声波压制释放——引用带 suppressedWaveRef 回退（fix16 转存后 musicWaveRef 常为空）；
    // 只写回账本确认的自家值；波浪已不可达/非自家写/快照对不上时不动视图、只清压制状态防悬置（*_unowned）
    private fun releaseSuppressNow(src: String) {
        if (!waveRestoreSuppressed) return
        val wave = musicWaveRef?.get() ?: suppressedWaveRef?.get()
        if (wave != null && isWaveSelfWritten(wave)) {
            restoreWaveVisibilityIfSaved(wave, src)
        }
        if (waveRestoreSuppressed) {
            clearWaveSuppress("${src}_unowned")
        }
    }

    // v1.4.5-fix20.3 C2：暂停后延迟释放——排期后到点复核（暂停满 300ms＋NONE＋压制中＋物理收起＋非重入），
    // 全过才释放；因「卡片还开着」不满足时不自重排，等 onLayoutStateChanged 收起落地（C4）再触发
    private fun schedulePauseWaveRelease(src: String, delayMs: Long) {
        cancelPauseWaveRelease()
        pauseWaveReleaseSrc = src
        val task = Runnable {
            pauseWaveReleaseRunnable = null
            val pendingSrc = pauseWaveReleaseSrc ?: return@Runnable
            pauseWaveReleaseSrc = null
            if (isPlaybackPlaying || pauseGraceActive) return@Runnable
            if (pauseStartUptimeMs == 0L ||
                SystemClock.uptimeMillis() - pauseStartUptimeMs < 300L
            ) return@Runnable
            if (activeMode != RenderMode.NONE) return@Runnable
            if (!waveRestoreSuppressed) return@Runnable
            shouldShowOnCollapsedCapsule(readPrefs())
            if (isExpandBlocker() || isIslandPhysicallyExpanded()) return@Runnable
            if (preDrawReentryMode) return@Runnable
            releaseSuppressNow(pendingSrc)
        }
        pauseWaveReleaseRunnable = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun cancelPauseWaveRelease() {
        pauseWaveReleaseRunnable?.let { mainHandler.removeCallbacks(it) }
        pauseWaveReleaseRunnable = null
        pauseWaveReleaseSrc = null
    }

    private fun clearWaveSuppress(reason: String) {
        if (!waveRestoreSuppressed) return
        cancelPauseWaveRelease()
        val rMsg = "[WAVE_SUPPRESS] released reason=$reason kind=$waveSuppressKind"
        YLog.info(msg = rMsg)
        EventHistoryStore.recordDiagOnly(rMsg)
        waveRestoreSuppressed = false
        // vc56 C：压制解除时延期标记一并复位（Hark：解除/takeover/取消三处都要复位，此处是共同出口）
        waveFallbackGateDeferred = false
        waveFallbackGateDeferredLogged = false
        waveFallbackScreenOffRetryPosted = false
        waveSuppressKind = null
        suppressAwaitCollapse = false
        narrowSuppressReleasePending = false
        narrowSuppressReleaseRunnable?.let { mainHandler.removeCallbacks(it) }
        narrowSuppressReleaseRunnable = null
        suppressedWaveRef = null
        cancelWaveSuppressFallback()
    }

    private fun restoreWaveVisibilityIfSaved(wave: View, releaseReason: String = "restore_landed") {
        // v1.4.5-fix19 P0-1：惰性校验——仅当当前值确为我方所写时撤销写回 preSelfOrigVis 并清账本；
        // 非我方所写（原厂已接管/隐藏）绝不覆盖，严禁无条件写死 VISIBLE
        val ledger = resolveValidWaveLedger(wave)
        val savedVis = if (savedWaveIdentity == System.identityHashCode(wave)) savedWaveVisibility else null
        if (ledger != null) {
            val targetVis = ledger.preSelfOrigVis
            writeWaveVis(wave, targetVis, "restore:$releaseReason", clearLedgerAfter = true)
            if (waveRestoreSuppressed && wave.visibility == targetVis) {
                clearWaveSuppress(releaseReason)
            }
        } else if (waveRestoreSuppressed && savedVis != null && wave.visibility == savedVis) {
            clearWaveSuppress(releaseReason)
        }
    }

    // vc56 C：兜底被闸门/息屏延期中的标记（解除靠事件重跑，不轮询）；延期日志只打首次
    private var waveFallbackGateDeferred: Boolean = false
    private var waveFallbackGateDeferredLogged: Boolean = false
    // vc56（Quick diff 门检）：30s 安全网重投只许投一次，防息屏期变成每 30s 唤醒一次
    private var waveFallbackScreenOffRetryPosted: Boolean = false

    private fun rerunWaveFallbackIfGateDeferred() {
        if (!waveFallbackGateDeferred) return
        waveFallbackGateDeferred = false
        waveFallbackScreenOffRetryPosted = false
        scheduleWaveSuppressFallback()
    }

    /** vc56 C：一次性采样屏幕是否熄灭（采样失败按未熄灭，不拦兜底） */
    private fun isScreenOffNow(): Boolean {
        return runCatching {
            val ctx = leftTemplateRef?.get()?.context ?: rootWindowRef?.get()?.context
            val pm = ctx?.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            pm != null && !pm.isInteractive
        }.getOrDefault(false)
    }

    // v1.4.5-fix10 F10-1：压制兜底——长时间未接管且重入窗口已关时完整还原，防无歌词曲目等永不接管场景波浪永久缺席；
    // 重入仍武装时最多再等两轮（共约 15s），与接管互斥：接管路径会先消费标记并取消本兜底
    private fun scheduleWaveSuppressFallback() {
        cancelWaveSuppressFallback()
        waveSuppressFallbackChecks = 0
        waveSuppressDeferredChecks = 0
        val task = object : Runnable {
            // v1.4.5-fix20.1：着陆宽限标记必须是实例成员——写成 run() 局部变量会在每次重入时重置、宽限无限续期
            var passDeferred = false
            override fun run() {
                waveSuppressFallbackRunnable = null
                if (!waveRestoreSuppressed) return
                // v1.4.5-fix13 实施审 A-M1：只在 WIDE 让行（接管自会消费）；NARROW 压制中兜底须照常兜，
                // 否则窄态复核若被其它路径取消、波浪将无人释放
                if (activeMode == RenderMode.WIDE_V11) return
                // v1.4.5-fix12 F12-1 + 审查 S1：兜底到点时先刷新阻断原因——展开还没收完（用户在展开卡里停留 >5s）就推迟：
                // 立 suppressAwaitCollapse 并有界重投（每 5s、最多 3 次），不单靠 onLayoutStateChanged 解除（布局回调可能缺席）；
                // 次数用尽仍展开类则按非展开类处理、走下方还原，压制绝不无限悬置
                // v1.4.5-fix20.1：接住既有返回值供下方着陆宽限判断，调用次数不变（仍为 1 次）
                val canShowNow = shouldShowOnCollapsedCapsule(readPrefs())
                // v1.4.5-fix20 P0-1：延期条件补几何直判——blocker 被 left_not_visible 短路时，
                // 只要岛物理上还展开着就照常有界顺延（仍最多 3 次×5s，用尽强制还原，安全兜底不变）
                if ((isExpandBlocker() || isIslandPhysicallyExpanded()) && waveSuppressDeferredChecks < 3) {
                    waveSuppressDeferredChecks++
                    suppressAwaitCollapse = true
                    waveSuppressFallbackRunnable = this
                    com.vivo.islandlyric.util.PerfStats.delayedHold.incrementAndGet()
                    mainHandler.postDelayed(this, 5_000L)
                    val aMsg = "[WAVE_SUPPRESS] fallback_deferred blocker=$lastShouldShowBlocker checks=$waveSuppressDeferredChecks ${physExpDiag()}"
                    YLog.info(msg = aMsg)
                    EventHistoryStore.recordDiagOnly(aMsg)
                    return
                }
                if (isExpandBlocker() || isIslandPhysicallyExpanded()) {
                    suppressAwaitCollapse = false
                    val aMsg = "[WAVE_SUPPRESS] fallback_deferred_exhausted blocker=$lastShouldShowBlocker ${physExpDiag()}"
                    YLog.info(msg = aMsg)
                    EventHistoryStore.recordDiagOnly(aMsg)
                }
                // v1.4.5-fix20.1（Gemini 独案＋Quick 收口）：着陆宽限——展开后约 2.3–2.5s 收起时，2.5s 兜底醒来
                // 正撞 LAND_RECONCILE 约 230ms 着陆窗，此刻还原会与接管撞出约 238ms 瞬闪（063413 日志实证）。
                // 仅展开类、仅一次、仅未走过几何延期（deferredChecks==0，耗尽后不给、不延长 17.5s 上限）时，
                // 再等 500ms 让接管先落地消费压制；500ms 后仍未消费则照常走下方还原，压制不悬置。
                if (waveSuppressKind == "expand" && canShowNow && !passDeferred && waveSuppressDeferredChecks == 0) {
                    passDeferred = true
                    waveSuppressFallbackRunnable = this
                    com.vivo.islandlyric.util.PerfStats.delayedHold.incrementAndGet()
                    mainHandler.postDelayed(this, 500L)
                    val gMsg = "[WAVE_SUPPRESS] fallback_grace reason=pass_landing ms=500"
                    YLog.info(msg = gMsg)
                    EventHistoryStore.recordDiagOnly(gMsg)
                    return
                }
                if (preDrawReentryMode && waveSuppressFallbackChecks < 2) {
                    waveSuppressFallbackChecks++
                    waveSuppressFallbackRunnable = this
                    com.vivo.islandlyric.util.PerfStats.delayedHold.incrementAndGet()
                    mainHandler.postDelayed(this, 5_000L)
                    return
                }
                // v1.4.5-fix10（Quick M2/S2/M1）：仅当波浪仍是我方压制的 INVISIBLE 时才写回——已被系统改成 GONE 等
                // 说明原厂另有安排，只清标记不覆盖；写回包内部更新标记，免被自家 Hook 当原厂显形触发非预期重入；
                // 无论还原与否标记一律清掉，日志如实标注 restored
                // vc56 C（双审缩版）：摘岛已藏或息屏时不写原厂可见性——不清记账、不耗预算、不轮询，
                // 置延期标记后就此结束；解除靠事件重跑（unhide / SCREEN_ON → rerunWaveFallbackIfGateDeferred）
                val gateBlocked = landscapeGateHidNative
                val screenOffBlocked = !gateBlocked && isScreenOffNow()
                if (gateBlocked || screenOffBlocked) {
                    waveFallbackGateDeferred = true
                    if (!waveFallbackGateDeferredLogged) {
                        waveFallbackGateDeferredLogged = true
                        val dMsg = "[WAVE_SUPPRESS] fallback_deferred reason=${if (gateBlocked) "gate" else "screenoff"}"
                        YLog.info(msg = dMsg)
                        EventHistoryStore.recordDiagOnly(dMsg)
                    }
                    if (screenOffBlocked && !screenReceiverRegistered && !waveFallbackScreenOffRetryPosted) {
                        // 接收器注册失败时没有亮屏事件可等：只留一次 30s 重投作安全网（Quick 门检：防变成周期唤醒）
                        waveFallbackScreenOffRetryPosted = true
                        waveSuppressFallbackRunnable = this
                        com.vivo.islandlyric.util.PerfStats.delayedHold.incrementAndGet()
                        mainHandler.postDelayed(this, 30_000L)
                    }
                    return
                }
                // v1.4.5-fix16：当前引用已 detach 被清时，兜底也要能捞回压制中保留的旧实例
                val wave = musicWaveRef?.get() ?: suppressedWaveRef?.get()
                var restored = false
                if (wave != null && isWaveSelfWritten(wave)) {
                    restoreWaveVisibilityIfSaved(wave, "fallback_restore")
                    restored = true
                }
                clearWaveSuppress("fallback_restore")
                val fMsg = "[WAVE_SUPPRESS] fallback_restore restored=$restored checks=$waveSuppressFallbackChecks ${physExpDiag()}"
                YLog.info(msg = fMsg)
                EventHistoryStore.recordDiagOnly(fMsg)
            }
        }
        waveSuppressFallbackRunnable = task
        com.vivo.islandlyric.util.PerfStats.delayedHold.incrementAndGet()
        // v1.4.5-fix18 P0-4（Quick 改法）：首查 2.5s——8s 级别的黑波浪本身即用户可感知故障，5s 起查过长；
        // 展开类推迟重查仍按原 5s 节奏（用户停留在展开卡内的有意等待，不在此列）
        mainHandler.postDelayed(task, 2_500L)
    }

    private fun cancelWaveSuppressFallback() {
        waveSuppressFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        waveSuppressFallbackRunnable = null
    }

    // ==================== 诊断与布局落定非零视图树转储 ====================

    private fun logModeDiagIfChanged(metrics: ModeMetrics) {
        if (metrics.mode == lastLoggedMode && metrics.hold == lastLoggedHold) return
        lastLoggedMode = metrics.mode
        lastLoggedHold = metrics.hold
        val leftTemplate = leftTemplateRef?.get()
        val rightTemplate = resolveWaveParent()
        val realCapW = realCapContainerRef?.get()?.width
            ?: (capLeftRef?.get()?.parent as? View)?.width
            ?: -1
        YLog.info(
            msg = "[CAPSULE_MODE_DIAG] mode=${metrics.mode} | hold=${metrics.hold} | " +
                "unionVisual=${String.format(Locale.US, "%.1f", metrics.unionVisualPx)}px(${String.format(Locale.US, "%.1f", metrics.unionVisualDp)}dp) | " +
                "capL=${formatRectF(metrics.capLeftRect)} | capR=${formatRectF(metrics.capRightRect)} | " +
                "scaleL=(${String.format(Locale.US, "%.2f", metrics.scaleLX)},${String.format(Locale.US, "%.2f", metrics.scaleLY)}) | " +
                "scaleR=(${String.format(Locale.US, "%.2f", metrics.scaleRX)},${String.format(Locale.US, "%.2f", metrics.scaleRY)}) | " +
                "realCapW=${realCapW}px | leftLpW=${leftTemplate?.layoutParams?.width ?: -99} | " +
                "rightLpW=${rightTemplate?.layoutParams?.width ?: -99}"
        )
    }

    private fun formatRectF(r: RectF?): String {
        if (r == null) return "null"
        return "[${r.left.roundToInt()},${r.top.roundToInt()}-${r.right.roundToInt()},${r.bottom.roundToInt()}]"
    }

    private fun armWideTreeDumpAfterLayout(leftContainer: LinearLayout?) {
        if (wideTreeDumped) return

        if (leftContainer != null) {
            if (leftContainer.width > 0 && leftContainer.height > 0 && !leftContainer.isLayoutRequested) {
                mainHandler.post { executeWideTreeDump(allowRetry = true) }
            } else {
                val oneShot = object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View,
                        left: Int,
                        top: Int,
                        right: Int,
                        bottom: Int,
                        oldLeft: Int,
                        oldTop: Int,
                        oldRight: Int,
                        oldBottom: Int
                    ) {
                        if (v.width > 0 && v.height > 0) {
                            v.removeOnLayoutChangeListener(this)
                            mainHandler.post { executeWideTreeDump(allowRetry = true) }
                        }
                    }
                }
                leftContainer.addOnLayoutChangeListener(oneShot)
            }
        }

        if (wideTreeFallbackRunnable == null) {
            val fallback = Runnable {
                wideTreeFallbackRunnable = null
                executeWideTreeDump(allowRetry = true)
            }
            wideTreeFallbackRunnable = fallback
            mainHandler.postDelayed(fallback, Const.WIDE_TREE_DUMP_FALLBACK_MS)
        }
    }

    private fun executeWideTreeDump(allowRetry: Boolean) {
        if (wideTreeDumped) return
        runCatching {
            val root = rootWindowRef?.get() ?: leftTemplateRef?.get()?.rootView ?: return@runCatching
            if (!root.isAttachedToWindow || root.width <= 0 || root.height <= 0) return@runCatching
            if (activeMode != RenderMode.WIDE_V11) return@runCatching

            // 问题三门控：仅在稳定真宽态（并集 >= 700px）落盘视图树与 EDGE_CHECK
            val capLeftRect = resolveVisualRectInRoot(capLeftRef?.get(), root)
            val capRightRect = resolveVisualRectInRoot(capRightRef?.get(), root)
            val unionPx = if (capLeftRect != null && capRightRect != null) {
                max(capLeftRect.right, capRightRect.right) - min(capLeftRect.left, capRightRect.left)
            } else {
                0f
            }
            if (unionPx < Const.WIDE_TREE_MIN_UNION_PX) {
                if (allowRetry && !wideTreeRetryScheduled) {
                    wideTreeRetryScheduled = true
                    mainHandler.postDelayed({
                        wideTreeRetryScheduled = false
                        executeWideTreeDump(allowRetry = false)
                    }, Const.WIDE_TREE_DUMP_FALLBACK_MS)
                }
                return@runCatching
            }

            val nodes = ArrayList<String>(64)
            dumpTreeNodes(root, depth = 0, out = nodes)
            if (nodes.isEmpty()) return@runCatching

            val hasZeroInjected = hasAnyVisibleInjectedZeroSize()
            YLog.info(
                msg = "=== [ISLAND_WIDE_TREE] BEGIN root=${root.javaClass.name} size=${root.width}x${root.height} nodes=${nodes.size} zeroInjected=$hasZeroInjected ==="
            )
            logEdgeCheckIfNeeded(root, reason = "tree_dump", prefs = readPrefs(), force = true)
            val limit = minOf(nodes.size, Const.WIDE_TREE_DUMP_NODE_LIMIT)
            for (i in 0 until limit) {
                YLog.info(msg = "[ISLAND_WIDE_TREE] ${nodes[i]}")
            }
            if (nodes.size > limit) {
                YLog.info(msg = "[ISLAND_WIDE_TREE] ... 省略 ${nodes.size - limit} 个节点 ...")
            }
            YLog.info(msg = "=== [ISLAND_WIDE_TREE] END ===")

            if (!hasZeroInjected) {
                wideTreeDumped = true
                wideTreeFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
                wideTreeFallbackRunnable = null
            } else if (allowRetry && !wideTreeRetryScheduled) {
                wideTreeRetryScheduled = true
                mainHandler.postDelayed({
                    executeWideTreeDump(allowRetry = false)
                }, Const.WIDE_TREE_DUMP_FALLBACK_MS)
            }
        }.onFailure { err ->
            YLog.warn(msg = "[ISLAND_WIDE_TREE] dump 失败: ${err.message}")
        }
    }

    /** P5 修复：独立解耦的 [EDGE_CHECK]，每次进入稳定宽态或拖动两个新滑杆即打一条（去重防刷屏） */
    private fun logEdgeCheckIfNeeded(
        root: View,
        reason: String,
        prefs: RuntimePrefs,
        force: Boolean = false
    ) {
        // v1.4.5-fix16（Quick 二轮裁决）：stable_wide 限频——转场动画期 50ms 连发 3–4 条的几何+拼串成本先砍掉
        if (reason == "stable_wide" && !force) {
            val nowE = SystemClock.uptimeMillis()
            if (nowE - lastEdgeCheckMs < 500L) return
            lastEdgeCheckMs = nowE
        }
        val leftTemplate = leftTemplateRef?.get() ?: return
        val albumIcon = findChildByIdName(leftTemplate, Const.ID_ICON_IMAGE_LEFT)
        val capLeftRect = resolveVisualRectInRoot(capLeftRef?.get(), root)
        val capRightRect = resolveVisualRectInRoot(capRightRef?.get(), root)
        val albumRect = resolveVisualRectInRoot(albumIcon, root)
        val rightActiveView: View? = when {
            inIntroMode -> rightIntroCdViewRef?.get()
            inInterludeMode -> rightInterludeCdViewRef?.get()
            else -> rightLyricViewRef?.get()
        }
        val rightActiveRect = resolveVisualRectInRoot(rightActiveView, root)
        val padLeft = leftTemplate.paddingLeft
        val albumDelta = if (albumRect != null && capLeftRect != null) {
            (albumRect.left - capLeftRect.left).roundToInt()
        } else {
            -999
        }
        val lyricLeftDelta = if (rightActiveRect != null && capRightRect != null) {
            (rightActiveRect.left - capRightRect.left).roundToInt()
        } else {
            -999
        }
        val leftInfoView = leftInfoContainerRef?.get()
        val leftInfoRect = resolveVisualRectInRoot(leftInfoView, root)
        val leftInfoRightDelta = if (leftInfoRect != null && capLeftRect != null) {
            (leftInfoRect.right - capLeftRect.right).roundToInt()
        } else {
            -999
        }
        val offsetPx = resolveLyricLeftOffsetPx(leftTemplate.context, prefs)
        val rightInsetPx = resolveRightLyricRightInsetPx(leftTemplate.context, prefs)
        val leftOffsetPx = resolveLeftInfoOffsetPx(leftTemplate.context, prefs)
        val leftInfoW = leftInfoView?.width ?: computeLeftInfoWidthPx(leftTemplate, prefs)
        val dedupKey = "$cachedTrackTitle|$padLeft|$albumDelta|$offsetPx|$rightInsetPx|$leftOffsetPx|" +
            "${prefs.albumLeftPaddingPx}|${prefs.leftInfoMarginStartDp}|${prefs.leftInfoRightInsetDp}|" +
            "$inIntroMode|$inInterludeMode|$lyricLeftDelta|$leftInfoRightDelta"
        if (!force && dedupKey == lastEdgeCheckKey) return
        lastEdgeCheckKey = dedupKey
        val edgeMsg = "[EDGE_CHECK] reason=$reason | leftTemplatePadLeft=$padLeft | " +
            "albumLeftMinusCapLLeft=${albumDelta}px | leftInfoW=${leftInfoW}px | " +
            "leftInfoRect=[${leftInfoRect?.left?.roundToInt() ?: -1}..${leftInfoRect?.right?.roundToInt() ?: -1}] | " +
            "leftInfoRightMinusCapLRight=${leftInfoRightDelta}px | " +
            "capL=[${capLeftRect?.left?.roundToInt() ?: -1}..${capLeftRect?.right?.roundToInt() ?: -1}] | " +
            "lyricLeftMinusCapRLeft=${lyricLeftDelta}px | " +
            "rightContent=[${rightActiveRect?.left?.roundToInt() ?: -1}..${rightActiveRect?.right?.roundToInt() ?: -1}] | " +
            "capR=[${capRightRect?.left?.roundToInt() ?: -1}..${capRightRect?.right?.roundToInt() ?: -1}] | " +
            "prefLyricOffsetDp=${String.format(Locale.US, "%.1f", prefs.lyricLeftOffsetDp)}(${offsetPx}px) | " +
            "prefRightInsetDp=${String.format(Locale.US, "%.1f", prefs.rightLyricRightInsetDp)}(${rightInsetPx}px) | " +
            "prefAlbumPadPx=${prefs.albumLeftPaddingPx} | " +
            "prefLeftOffsetDp=${String.format(Locale.US, "%.1f", prefs.leftInfoOffsetDp)}(${leftOffsetPx}px) | " +
            "prefLeftMarginDp=${String.format(Locale.US, "%.1f", prefs.leftInfoMarginStartDp)} | " +
            "prefLeftRightInsetDp=${String.format(Locale.US, "%.1f", prefs.leftInfoRightInsetDp)}"
        com.vivo.islandlyric.util.PerfStats.edgeChecks.incrementAndGet()
        YLog.info(msg = edgeMsg)
        EventHistoryStore.recordDiagOnly(edgeMsg, dedupKey = dedupKey)
    }

    private fun registerPreDrawVisualLock(root: View) {
        val cur = preDrawLockOwnerRef?.get()
        if (cur === root) return
        unregisterPreDrawVisualLock()
        val vto = root.viewTreeObserver
        if (vto != null && vto.isAlive) {
            vto.addOnPreDrawListener(preDrawVisualLockListener)
            preDrawLockOwnerRef = WeakReference(root)
        }
    }

    private fun unregisterPreDrawVisualLock() {
        preDrawReentryMode = false
        val owner = preDrawLockOwnerRef?.get()
        preDrawLockOwnerRef = null
        if (owner != null) {
            runCatching {
                val vto = owner.viewTreeObserver
                if (vto != null && vto.isAlive) {
                    vto.removeOnPreDrawListener(preDrawVisualLockListener)
                }
            }
        }
    }

    private fun resetVisualLockTranslations() {
        leftTemplateRef?.get()?.let { lt ->
            findChildByIdName(lt, Const.ID_ICON_IMAGE_LEFT)?.let { icon ->
                if (icon.translationX != 0f) icon.translationX = 0f
            }
        }
        leftInfoContainerRef?.get()?.let { lc ->
            if (lc.translationX != 0f) lc.translationX = 0f
        }
        rightLyricViewRef?.get()?.let { rv ->
            if (rv.translationX != 0f) rv.translationX = 0f
        }
        rightIntroCdViewRef?.get()?.let { iv ->
            if (iv.translationX != 0f) iv.translationX = 0f
        }
        rightInterludeCdViewRef?.get()?.let { iv ->
            if (iv.translationX != 0f) iv.translationX = 0f
        }
        cancelSoloFade(preserveTransform = false)
        musicWaveRef?.get()?.let { wv ->
            resetSoloWaveTransformIfMatching(wv, resetTranslation = true)
            if (wv.translationX != 0f) wv.translationX = 0f
        }
    }

    /**
     * S1/S2/S5 视觉坐标闭环锁：
     * - 仅在稳定宽态（minScale >= 0.92）执行，偏差 >= 0.5px 才微调 translationX；
     * - 免疫原厂 AtomMainIslandLeftTemplate/RightTemplate 在布局帧中重置 paddingLeft=35 或 gravity 漂移。
     */
    private fun resolveCachedAlbumIcon(leftTemplate: ViewGroup): View? {
        val cached = albumIconRef?.get()
        if (cached != null && cached.isAttachedToWindow) return cached
        val found = findChildByIdName(leftTemplate, Const.ID_ICON_IMAGE_LEFT)
        albumIconRef = found?.let { WeakReference(it) }
        return found
    }

    private fun applyPreDrawVisualLock() {
        if (activeMode != RenderMode.WIDE_V11 || hideSettleActive) return
        val leftTemplate = leftTemplateRef?.get() ?: return
        val root = rootWindowRef?.get() ?: leftTemplate.rootView ?: return
        if (!root.isAttachedToWindow) return

        // v1.4.5-fix8 F3/F5/F6/F7：前奏等待波浪使用锁存态（inIntroMode && lastRenderedRightState == 3），
        // 前置于 minScale/unionPx 守卫执行，按新鲜 capR 与祖先累计 scaleX 换算居中，首次落定同步释放显形门控并启动 150ms 1.0→1.45 进场缩放
        val isSoloWaitingWave = inIntroMode && lastRenderedRightState == 3
        // 仓侧直修（Quick Q6）：收起沉降期（hideSettleActive）不执行 solo 变换——祖先缩放趋 0 时位移会被放大且复位分支被锁存态挡住
        if (isSoloWaitingWave && !soloWaveFadeActive && !hideSettleActive) {
            musicWaveRef?.get()?.let { wv ->
                if (wv.visibility == View.VISIBLE && wv.width > 0) {
                    // 仓侧直修（Quick Q2 加固）：alpha 自愈——非门控、非淡出时 alpha 不该小于 1（防个别出口漏恢复后永久半透明/不可见）
                    if (!pendingWaveReveal && wv.alpha < 1f) wv.alpha = 1f
                    val wvId = System.identityHashCode(wv)
                    val firstApplyOnInstance = !soloWaveTransformApplied || soloWaveTransformIdentity != wvId
                    if (firstApplyOnInstance) {
                        savedWavePivotWasSet = wv.isPivotSet
                        if (savedWavePivotWasSet) {
                            savedWavePivotX = wv.pivotX
                            savedWavePivotY = wv.pivotY
                        }
                        soloWaveTransformApplied = true
                        soloWaveTransformIdentity = wvId
                        appliedSoloWaveOffsetX = 0f
                        // 仓侧直修（Quick 必改 M2b）：新建 solo 记录时先清零可能残留的旧位移，防第一帧居中算错跳一帧
                        if (wv.translationX != 0f) wv.translationX = 0f
                    }
                    val targetPivotX = wv.width * 0.5f
                    if (abs(wv.pivotX - targetPivotX) >= 0.5f) {
                        wv.pivotX = targetPivotX
                    }
                    val revealingNow = pendingWaveReveal
                    if (revealingNow) {
                        wv.scaleX = 1.0f
                    } else if (soloWaveScaleInAnimator == null && wv.scaleX != 1.45f) {
                        wv.scaleX = 1.45f
                    }
                    if (syncSoloWaveCenterInFrame(wv, root, ignoreSelfAlpha = revealingNow)) {
                        if (revealingNow) {
                            clearPendingWaveReveal(restoreAlpha = false)
                            if (wv.alpha != 1f) wv.alpha = 1f
                            startSoloWaveScaleIn(wv, root)
                        }
                    }
                }
            }
        }

        if (computeCurrentCapsMinScale(root) < Const.GLOW_AND_LOCK_MIN_SCALE) return

        val hasCapLeft = resolveVisualRectInRootInto(capLeftRef?.get(), root, lockCapLeftRect)
        val hasCapRight = resolveVisualRectInRootInto(capRightRef?.get(), root, lockCapRightRect)
        // v1.3.18/19：手势收拢动画期（并集宽度已小于完整宽态阈值）直接让出控制权，不与原厂弹簧抢位；移除 geoHash 早退杜绝 15px 闪跳
        if (hasCapLeft && hasCapRight) {
            val unionPx = max(lockCapLeftRect.right, lockCapRightRect.right) - min(lockCapLeftRect.left, lockCapRightRect.left)
            if (unionPx < Const.WIDE_TREE_MIN_UNION_PX) return
        }
        val prefs = readPrefs()

        // S1：专辑图视觉左缘恒等于 prefAlbumPadPx（±0.5px）
        var lockedAlbumRight = -1f
        if (hasCapLeft) {
            val albumIcon = resolveCachedAlbumIcon(leftTemplate)
            if (albumIcon != null && resolveVisualRectInRootInto(albumIcon, root, lockAlbumRect)) {
                val targetAlbumLeft = lockCapLeftRect.left + resolveAlbumLeftPaddingPx(prefs).toFloat()
                val albumDelta = targetAlbumLeft - lockAlbumRect.left
                if (abs(albumDelta) >= Const.PRE_DRAW_LOCK_MIN_DELTA_PX) {
                    albumIcon.translationX += albumDelta
                    lockedAlbumRight = lockAlbumRect.right + albumDelta
                } else {
                    lockedAlbumRight = lockAlbumRect.right
                }
            }

            // S2：左侧歌名块按专辑图真实右缘 + marginStart + 整块横向偏移（leftInfoOffsetDp）同帧拉齐
            val leftInfo = leftInfoContainerRef?.get()
            if (leftInfo != null && lockedAlbumRight > 0f && resolveVisualRectInRootInto(leftInfo, root, lockLeftInfoRect)) {
                val albumMlp = albumIcon?.layoutParams as? ViewGroup.MarginLayoutParams
                val albumEndMargin = (albumMlp?.marginEnd ?: 0).coerceAtLeast(0).toFloat()
                val marginStartPx = resolveLeftInfoMarginStartPx(leftTemplate.context, prefs).toFloat()
                val blockOffsetPx = resolveLeftInfoOffsetPx(leftTemplate.context, prefs).toFloat()
                val targetLeftInfoLeft = lockedAlbumRight + albumEndMargin + marginStartPx + blockOffsetPx
                val infoDelta = targetLeftInfoLeft - lockLeftInfoRect.left
                if (abs(infoDelta) >= Const.PRE_DRAW_LOCK_MIN_DELTA_PX) {
                    leftInfo.translationX += infoDelta
                }
            }
        }

        // v1.4.5-fix8：非 solo 态（锁存态）统一在此收尾复位；淡出期间禁止提前复位
        var pendingSoloOffsetForSingleWrite = 0f
        if (!isSoloWaitingWave && !soloWaveFadeActive) {
            musicWaveRef?.get()?.let { wv ->
                val wvId = System.identityHashCode(wv)
                val deferTxToPersistentWave = !inIntroMode && !inInterludeMode && prefs.persistentWaveEnabled
                if (deferTxToPersistentWave) {
                    if (soloWaveTransformApplied && soloWaveTransformIdentity == wvId) {
                        pendingSoloOffsetForSingleWrite = appliedSoloWaveOffsetX
                    }
                    // 先归位 scaleX 与 pivot，translationX 留给下方常驻声波分支合并为单次赋值
                    resetSoloWaveTransformIfMatching(wv, resetTranslation = false)
                } else {
                    resetSoloWaveTransformIfMatching(wv, resetTranslation = true)
                    // 仓侧直修（Quick 必改 M2a）：恢复 v1.4.2 无条件清零——reset 函数在无 solo 记录时不碰 translationX，常驻声波残留位移会叠加到间奏/前奏
                    if (wv.translationX != 0f) wv.translationX = 0f
                }
            }
        }

        // S5 + v1.4.5 G7：右侧歌词 / 前奏倒计时视觉左缘锁定，且前奏倒计时右缘不越过 capR.right - 4dp
        if (hasCapRight && !inInterludeMode) {
            val rightActiveView: View? = if (inIntroMode) {
                rightIntroCdViewRef?.get()
            } else {
                rightLyricViewRef?.get()
            }
            var lockedLyricRight = -1f
            if (rightActiveView != null && resolveVisualRectInRootInto(rightActiveView, root, lockRightRect)) {
                var targetRightLeft = lockCapRightRect.left +
                    Const.RIGHT_TEMPLATE_EDGE_PADDING_PX.toFloat() +
                    resolveLyricLeftOffsetPx(leftTemplate.context, prefs).toFloat()
                if (inIntroMode) {
                    val maxRightEdge = lockCapRightRect.right - dp2px(leftTemplate.context, 4).toFloat()
                    val viewW = lockRightRect.width()
                    if (targetRightLeft + viewW > maxRightEdge) {
                        targetRightLeft = (maxRightEdge - viewW).coerceAtLeast(lockCapRightRect.left + Const.RIGHT_TEMPLATE_EDGE_PADDING_PX.toFloat())
                    }
                }
                val rightDelta = targetRightLeft - lockRightRect.left
                if (abs(rightDelta) >= Const.PRE_DRAW_LOCK_MIN_DELTA_PX) {
                    rightActiveView.translationX += rightDelta
                    lockedLyricRight = lockRightRect.right + rightDelta
                } else {
                    lockedLyricRight = lockRightRect.right
                }
            }
            // v1.4.2/v1.4.5 G7/fix7 v3：歌词常驻声波开启时，合并 soloOffset 剥离与 safeRightLimit 基础偏移为单次 translationX 赋值
            if (!inIntroMode && prefs.persistentWaveEnabled && !soloWaveFadeActive) {
                val waveView = musicWaveRef?.get()
                if (waveView != null && waveView.visibility == View.VISIBLE &&
                    resolveVisualRectInRootInto(waveView, root, lockRightRect)
                ) {
                    val curBaseTx = waveView.translationX - pendingSoloOffsetForSingleWrite
                    val baseWaveLeft = lockRightRect.left - pendingSoloOffsetForSingleWrite
                    val baseWaveRight = lockRightRect.right - pendingSoloOffsetForSingleWrite
                    val safeRightLimit = lockCapRightRect.right - dp2px(leftTemplate.context, 6).toFloat()
                    val minWaveLeft = if (lockedLyricRight > 0f) {
                        lockedLyricRight + dp2px(leftTemplate.context, 2).toFloat()
                    } else {
                        lockCapRightRect.left + Const.RIGHT_TEMPLATE_EDGE_PADDING_PX.toFloat()
                    }
                    var nextBaseTx = curBaseTx
                    if (baseWaveRight > safeRightLimit) {
                        val rawPull = safeRightLimit - baseWaveRight
                        val minAllowedPull = (minWaveLeft - baseWaveLeft).coerceAtMost(0f)
                        val pullDelta = rawPull.coerceAtLeast(minAllowedPull)
                        if (abs(pullDelta) >= Const.PRE_DRAW_LOCK_MIN_DELTA_PX) {
                            nextBaseTx = curBaseTx + pullDelta
                        }
                    } else if (curBaseTx < 0f) {
                        val slack = (safeRightLimit - baseWaveRight).coerceAtLeast(0f)
                        val releaseDelta = slack.coerceAtMost(-curBaseTx)
                        if (releaseDelta >= Const.PRE_DRAW_LOCK_MIN_DELTA_PX) {
                            nextBaseTx = curBaseTx + releaseDelta
                        }
                    }
                    if (pendingSoloOffsetForSingleWrite != 0f || abs(nextBaseTx - waveView.translationX) >= Const.PRE_DRAW_LOCK_MIN_DELTA_PX) {
                        waveView.translationX = nextBaseTx
                    }
                } else if (waveView != null && pendingSoloOffsetForSingleWrite != 0f) {
                    waveView.translationX = waveView.translationX - pendingSoloOffsetForSingleWrite
                }
            }
        } else if (hasCapRight && inInterludeMode) {
            // v1.4.5 G7/fix1：间奏倒计时视觉左缘锁定在 capR 安全区内，同时右缘避让声波与 capR.right
            val interludeCd = rightInterludeCdViewRef?.get()
            if (interludeCd != null && interludeCd.visibility == View.VISIBLE &&
                resolveVisualRectInRootInto(interludeCd, root, lockRightRect)
            ) {
                val cdWidth = lockRightRect.width()
                val minLeft = lockCapRightRect.left + dp2px(leftTemplate.context, 4).toFloat()
                var maxRight = lockCapRightRect.right - dp2px(leftTemplate.context, 4).toFloat()
                val waveView = musicWaveRef?.get()
                if (waveView != null && waveView.visibility == View.VISIBLE &&
                    resolveVisualRectInRootInto(waveView, root, lockWaveRect)
                ) {
                    maxRight = min(maxRight, lockWaveRect.left - dp2px(leftTemplate.context, 4).toFloat())
                }
                val desiredLeft = lockCapRightRect.left +
                    Const.RIGHT_TEMPLATE_EDGE_PADDING_PX.toFloat() +
                    resolveLyricLeftOffsetPx(leftTemplate.context, prefs).toFloat()
                val maxLeft = (maxRight - cdWidth).coerceAtLeast(minLeft)
                val clampedLeft = desiredLeft.coerceIn(minLeft, maxLeft)
                val delta = clampedLeft - lockRightRect.left
                if (abs(delta) >= Const.PRE_DRAW_LOCK_MIN_DELTA_PX) {
                    interludeCd.translationX += delta
                }
            }
        }
    }

    private fun hasAnyVisibleInjectedZeroSize(): Boolean {
        val leftContainer = leftInfoContainerRef?.get()
        if (leftContainer != null && leftContainer.visibility == View.VISIBLE &&
            (leftContainer.width <= 0 || leftContainer.height <= 0)
        ) {
            return true
        }
        val titleView = leftTitleViewRef?.get()
        if (titleView != null && leftContainer?.visibility == View.VISIBLE &&
            (titleView.width <= 0 || titleView.height <= 0)
        ) {
            return true
        }
        if (inIntroMode) {
            val rightIntroCd = rightIntroCdViewRef?.get()
            if (rightIntroCd != null && rightIntroCd.visibility == View.VISIBLE &&
                (rightIntroCd.width <= 0 || rightIntroCd.height <= 0)
            ) {
                return true
            }
        } else if (!inInterludeMode) {
            val rightView = rightLyricViewRef?.get()
            if (rightView != null && rightView.visibility == View.VISIBLE &&
                (rightView.width <= 0 || rightView.height <= 0)
            ) {
                return true
            }
        }
        return false
    }

    private fun dumpTreeNodes(view: View, depth: Int, out: MutableList<String>) {
        if (out.size >= Const.WIDE_TREE_DUMP_NODE_LIMIT + 1) return
        val indent = "  ".repeat(depth.coerceAtMost(10))
        val idName = resolveIdName(view).ifEmpty { "NO_ID" }
        val vis = when (view.visibility) {
            View.VISIBLE -> "V"
            View.INVISIBLE -> "I"
            View.GONE -> "G"
            else -> "?"
        }
        val tagPart = (view.tag as? String)?.takeIf { it.startsWith("vivo_island_lyric_") }
            ?.let { " tag=$it" }.orEmpty()
        val textPart = when (view) {
            is TextView -> " text=${LogFormat.preview(view.text?.toString(), 40)} px=${view.textSize.toInt()}"
            is LyricScrollView -> " text=${LogFormat.preview(view.currentText(), 40)} px=${view.currentTextSizePx()}"
            is TrackMarqueeView -> " text=${LogFormat.preview(view.currentText(), 40)} px=${view.currentTextSizePx()}"
            else -> ""
        }

        val isKeyContainer = idName == Const.ID_CAP_LEFT ||
            idName == Const.ID_CAP_RIGHT ||
            idName == Const.ID_REAL_CAP_CONTAINER ||
            idName == Const.ID_NEW_DYNAMIC_CAPS ||
            idName == Const.ID_ICON_IMAGE_LEFT ||
            idName == Const.ID_MUSIC_WAVE ||
            view.javaClass.name == Const.CLS_LEFT_TEMPLATE ||
            view.javaClass.name == Const.CLS_RIGHT_TEMPLATE ||
            view.javaClass.name == Const.CLS_ISLAND_WINDOW_VIEW ||
            view.javaClass.name.contains("IslandTemplateFrameLayout") ||
            tagPart.isNotEmpty()

        val detailPart = if (isKeyContainer) {
            val lp = view.layoutParams
            val lpStr = if (lp != null) " lp=(${lp.width},${lp.height})" else ""
            val padStr = " pad=[${view.paddingLeft},${view.paddingTop},${view.paddingRight},${view.paddingBottom}]"
            val scaleStr = if (view.scaleX != 1f || view.scaleY != 1f || view.translationX != 0f) {
                " tr=(sx=${String.format(Locale.US, "%.2f", view.scaleX)},sy=${String.format(Locale.US, "%.2f", view.scaleY)},tx=${view.translationX.roundToInt()})"
            } else {
                ""
            }
            "$lpStr$padStr$scaleStr"
        } else {
            ""
        }

        out.add(
            "${indent}d=$depth ${view.javaClass.name} id=$idName vis=$vis " +
                "[${view.left},${view.top}-${view.right},${view.bottom}](${view.width}x${view.height})$tagPart$textPart$detailPart"
        )
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i) ?: continue
                dumpTreeNodes(child, depth + 1, out)
            }
        }
    }

    // ==================== 辅助工具 ====================

    private fun captureWaveReference(wave: View, attached: Boolean) {
        val id = System.identityHashCode(wave)
        if (savedWaveIdentity != id) {
            if (savedWaveIdentity != 0) {
                val wiMsg = "[WAVE_INST] change oldId=$savedWaveIdentity newId=$id"
                YLog.info(msg = wiMsg)
                EventHistoryStore.recordDiagOnly(wiMsg)
            }
            // v1.4.5-fix19 P0-1/P0-2：换实例只清快照、不清进程级账本（账本跟 View 走）；旧压制实例仅在确为我方所写时撤销
            if (waveRestoreSuppressed) {
                val oldWave = suppressedWaveRef?.get()
                if (oldWave != null) {
                    val oldLedger = resolveValidWaveLedger(oldWave)
                    if (oldLedger != null) {
                        writeWaveVis(oldWave, oldLedger.preSelfOrigVis, "instance_change_restore", clearLedgerAfter = true)
                    }
                }
                suppressedWaveRef = null
            }
            savedWaveVisibility = null
            savedWaveIdentity = id
            if (waveRestoreSuppressed) {
                clearWaveSuppress("wave_instance_changed")
            }
        }
        musicWaveRef = WeakReference(wave)
        musicWaveAttached = attached
    }

    private fun resolveWaveParent(): ViewGroup? {
        musicWaveRef?.get()?.parent?.let { if (it is ViewGroup) return it }
        return rightTemplateRef?.get()
    }

    private fun captureOriginalSwitcherStylesIfNeeded(switcher: ViewGroup) {
        if (savedSwitcherStyle == null) {
            val lp = switcher.layoutParams
            savedSwitcherStyle = SavedSwitcherStyle(
                visibility = View.GONE,
                lpWidth = lp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                lpHeight = lp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        for (tv in collectSwitcherTextViews(switcher)) {
            val key = System.identityHashCode(tv)
            if (!savedTvStyles.containsKey(key)) {
                savedTvStyles[key] = SavedTextViewStyle(
                    textSizePx = tv.textSize,
                    ellipsize = tv.ellipsize,
                    marqueeRepeatLimit = tv.marqueeRepeatLimit,
                    maxWidth = tv.maxWidth,
                    fadingEdgeEnabled = tv.isHorizontalFadingEdgeEnabled,
                    fadingEdgeLength = tv.horizontalFadingEdgeLength,
                    gravity = tv.gravity,
                    selected = tv.isSelected
                )
            }
        }
    }

    private fun configureNarrowMarqueeTextView(tv: TextView) {
        val ctx = tv.context
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, Const.LYRIC_TEXT_SIZE_SP)
        tv.isSingleLine = true
        tv.maxLines = 1
        tv.setHorizontallyScrolling(true)
        tv.ellipsize = TextUtils.TruncateAt.MARQUEE
        tv.marqueeRepeatLimit = 1
        tv.isHorizontalFadingEdgeEnabled = true
        tv.setFadingEdgeLength(dp2px(ctx, Const.LYRIC_FADING_EDGE_DP))
        tv.maxWidth = dp2px(ctx, Const.LYRIC_MAX_WIDTH_DP)
        tv.gravity = Gravity.CENTER_VERTICAL or Gravity.START
    }

    private fun scheduleNarrowMarqueeKick(switcher: ViewGroup, textViews: List<TextView>) {
        marqueeKickRunnable?.let { mainHandler.removeCallbacks(it) }
        val kick = Runnable {
            renderBreaker.execute {
                if (activeMode != RenderMode.NARROW_V10 || inInterludeMode) return@execute
                val active = resolveCurrentTextView(switcher, textViews) ?: return@execute
                if (active.visibility == View.VISIBLE && !active.text.isNullOrEmpty()) {
                    active.isSelected = false
                    active.isSelected = true
                }
            }
        }
        marqueeKickRunnable = kick
        mainHandler.postDelayed(kick, 160L)
    }

    private fun resolveCurrentTextView(switcher: ViewGroup, fallbackList: List<TextView>): TextView? {
        if (switcher is TextSwitcher) {
            (switcher.currentView as? TextView)?.let { return it }
        }
        return fallbackList.firstOrNull { it.visibility == View.VISIBLE && !it.text.isNullOrEmpty() }
            ?: fallbackList.firstOrNull { it.visibility == View.VISIBLE }
            ?: fallbackList.firstOrNull()
    }

    private fun collectSwitcherTextViews(switcher: ViewGroup): List<TextView> {
        val list = ArrayList<TextView>(2)
        for (i in 0 until switcher.childCount) {
            val child = switcher.getChildAt(i)
            if (child is TextView) list.add(child)
        }
        return list
    }

    private fun refreshRefsFromLeftTemplate(template: ViewGroup) {
        val sw = findChildByIdName(template, Const.ID_TEXT_SWITCHER_LEFT) as? ViewGroup
        if (sw != null) {
            switcherRef = WeakReference(sw)
        }

        var cur: View? = template
        var capContainer: ViewGroup? = null
        while (cur != null) {
            val idName = resolveIdName(cur)
            if (idName == Const.ID_CAP_LEFT) {
                capLeftRef = WeakReference(cur)
                capContainer = cur.parent as? ViewGroup
            }
            if (idName == Const.ID_REAL_CAP_CONTAINER) {
                realCapContainerRef = WeakReference(cur)
            }
            if (cur.javaClass.name == Const.CLS_ISLAND_WINDOW_VIEW) {
                val oldRoot = rootWindowRef?.get()
                if (oldRoot !== cur) {
                    oldRoot?.removeOnLayoutChangeListener(rootLayoutListener)
                    rootWindowRef = WeakReference(cur)
                    cur.addOnLayoutChangeListener(rootLayoutListener)
                }
                glowRenderer.attachToRoot(cur)
            }
            cur = cur.parent as? View
        }

        if (capContainer != null) {
            if (realCapContainerRef?.get() == null) {
                realCapContainerRef = WeakReference(capContainer)
            }
            findDirectChildByIdName(capContainer, Const.ID_CAP_RIGHT)?.let { capRight ->
                capRightRef = WeakReference(capRight)
                findByClassName(capRight, Const.CLS_RIGHT_TEMPLATE)?.let { rt ->
                    if (rt is ViewGroup && rightTemplateRef?.get() !== rt) {
                        rightTemplateRef?.get()?.removeOnLayoutChangeListener(templateLayoutListener)
                        rightTemplateRef = WeakReference(rt)
                        rt.addOnLayoutChangeListener(templateLayoutListener)
                    }
                }
                findChildByIdName(capRight, Const.ID_MUSIC_WAVE)?.let { wave ->
                    captureWaveReference(wave, attached = wave.isAttachedToWindow)
                }
            }
            findDirectChildByIdName(capContainer, Const.ID_CAP_EXPANDED)?.let { capExp ->
                capExpandedRef = WeakReference(capExp)
            }
        }
    }

    private fun findCapsuleContainerForView(view: View): ViewGroup? {
        var cur: View? = view
        var hops = 0
        var capSideParent: ViewGroup? = null
        while (cur != null && hops < 8) {
            val idName = resolveIdName(cur)
            if (idName == Const.ID_REAL_CAP_CONTAINER && cur is ViewGroup) {
                return cur
            }
            if ((idName == Const.ID_CAP_LEFT || idName == Const.ID_CAP_RIGHT) && capSideParent == null) {
                capSideParent = cur.parent as? ViewGroup
            }
            if (cur.javaClass.name == Const.CLS_ISLAND_WINDOW_VIEW) {
                break
            }
            cur = cur.parent as? View
            hops++
        }
        return capSideParent
    }

    private fun findMusicWaveInSameCapsule(template: ViewGroup): View? {
        val container = findCapsuleContainerForView(template) ?: return null
        val capRight = findDirectChildByIdName(container, Const.ID_CAP_RIGHT)
        val wave = if (capRight is ViewGroup) {
            findChildByIdName(capRight, Const.ID_MUSIC_WAVE)
        } else {
            findChildByIdName(container, Const.ID_MUSIC_WAVE)
        }
        if (wave != null) {
            captureWaveReference(wave, attached = wave.isAttachedToWindow)
        }
        return wave
    }

    private fun isSameCapsule(a: View?, b: View?): Boolean {
        if (a == null || b == null) return false
        if (a.rootView !== b.rootView) return false
        val ca = findCapsuleContainerForView(a)
        val cb = findCapsuleContainerForView(b)
        if (ca != null && cb != null) return ca === cb
        return true
    }

    private fun findMusicWaveFromContainer(template: ViewGroup): View? {
        val capRight = capRightRef?.get() ?: run {
            refreshRefsFromLeftTemplate(template)
            capRightRef?.get()
        } ?: return null
        val wave = findChildByIdName(capRight, Const.ID_MUSIC_WAVE)
        if (wave != null) {
            captureWaveReference(wave, attached = wave.isAttachedToWindow)
        }
        return wave
    }

    private fun findByClassName(view: View, targetClassName: String): View? {
        if (view.javaClass.name == targetClassName) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findByClassName(view.getChildAt(i), targetClassName)?.let { return it }
            }
        }
        return null
    }

    private fun findMusicWaveInTree(view: View): View? {
        if (view.javaClass.name == Const.CLS_MUSIC_WAVE_VIEW || resolveIdName(view) == Const.ID_MUSIC_WAVE) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findMusicWaveInTree(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun findDirectChildByIdName(parent: ViewGroup, targetIdName: String): View? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (resolveIdName(child) == targetIdName) return child
        }
        return null
    }

    private fun findChildByIdName(view: View, targetIdName: String): View? {
        if (resolveIdName(view) == targetIdName) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findChildByIdName(view.getChildAt(i), targetIdName)?.let { return it }
            }
        }
        return null
    }

    private fun resolveIdName(view: View): String {
        val id = view.id
        if (id == View.NO_ID) return ""
        return runCatching { view.resources.getResourceEntryName(id) }.getOrDefault("")
    }

    private fun dp2px(ctx: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            ctx.resources.displayMetrics
        ).toInt()
    }
}
