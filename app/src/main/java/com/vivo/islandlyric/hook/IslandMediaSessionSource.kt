package com.vivo.islandlyric.hook

import android.app.ActivityManager
import android.content.Context
import android.media.MediaMetadata
import android.os.Build
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Process
import android.os.SystemClock
import com.highcapable.yukihookapi.hook.log.YLog
import com.vivo.islandlyric.Const
import com.vivo.islandlyric.util.EventHistoryStore
import com.vivo.islandlyric.util.HookCircuitBreaker
import com.vivo.islandlyric.util.LogFormat
import com.vivo.islandlyric.util.LrcTimelineParser
import com.vivo.islandlyric.util.RuntimePrefs
import com.vivo.islandlyric.util.TrackFingerprintV2
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 运行在 com.vivo.systemuiplugin:island（UID 10077）单进程内的媒体会话数据源（v1.2.7）：
 * - Q1 修复：
 *   * 切歌锚点仅采纳 0..5000ms 的 state.position，否则建虚拟零点锚，杜绝旧曲末尾位置毒化前奏武装；
 *   * 统一 tryArmIntroIfEligible(reason) 入口在时间轴返回、切歌确认、位置回落三处共用；
 *   * 歌名变体识别扩充（括号剥离 + 前缀/版本后缀 + 前 4s 包含），命中一律不进右侧、不置 hasRealVocalLineInTrack；
 *   * 前奏期内每秒输出 [INTRO_STATE] 自证日志（暂停/退出即停）；
 * - Q4：关键事件（[SESSION_LIFE]/[TIMELINE]/[TIMELINE_GATE]/[COUNTDOWN]/[INTRO_STATE] 切换点）同步写入 EventHistoryStore 200 条环形历史。
 */
class IslandMediaSessionSource(
    private val mainHandler: Handler,
    private val readPrefs: () -> RuntimePrefs,
    private val listener: Listener,
    private val introStateQuery: () -> Pair<Boolean, String> = { false to "UNKNOWN" }
) {
    interface Listener {
        fun onTrackInfo(displayTitle: String, artist: String)
        fun onCoverPalette(colors: IntArray)
        fun onPlaybackPlayingChanged(isPlaying: Boolean)
        fun onLyricLine(
            lyric: String,
            isPlaying: Boolean,
            estimatedWindowMs: Long,
            karaokeDurationMs: Long = estimatedWindowMs,
            wordSpans: List<LrcTimelineParser.WordSpan> = emptyList(),
            lineSeq: Long = 0L,
            nextLyric: String = ""
        )
        /** v1.4.5-fix10 F10-3（Quick M3 合并版）：段界随间奏状态一次原子送达，免两次回调分帧闪一帧数字；startMs=-1 表示段界未知 */
        fun onInterludeChanged(inInterlude: Boolean, targetNextLineMs: Long = -1L, startMs: Long = -1L, skipWaveLead: Boolean = false)
        fun onIntroChanged(inIntro: Boolean, learnedFirstLineMs: Long, skipWaveLead: Boolean = false, forceWaveOnly: Boolean = false)
        fun onTrackSwitched(newInitialTitle: String, isPlaying: Boolean, estimatedWindowMs: Long)
        fun onSessionEnded(reason: String)
        fun onTrackConfirmedForDiag() {}
    }

    private data class BoundSession(
        val controller: MediaController,
        val callback: MediaController.Callback
    )

    private data class InterludeSpan(
        val startMs: Long,
        val endMs: Long
    )

    private data class TimelineLineTiming(
        val scrollDurationMs: Long,
        val karaokeDurationMs: Long,
        val wordSpans: List<LrcTimelineParser.WordSpan> = emptyList(),
        val isSliced: Boolean = false,
        val sliceRemainderText: String = ""
    )

    private enum class GateState {
        NONE,
        PENDING,
        PASSED,
        FAILED
    }

    private val started = AtomicBoolean(false)
    private val initBreaker = HookCircuitBreaker("Island.MSM.init")
    private val sessionsBreaker = HookCircuitBreaker("Island.MSM.sessionsChanged")
    private val metadataBreaker = HookCircuitBreaker("Island.MC.metadata")
    private val playbackBreaker = HookCircuitBreaker("Island.MC.playbackState")

    private val boundKugouSessions = ConcurrentHashMap<MediaSession.Token, BoundSession>()
    private val timelineExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "IslandTimelineFetch").apply { isDaemon = true }
    }

    @Volatile
    private var hostAppContext: Context? = null

    @Volatile
    private var versionChecked = false

    @Volatile
    private var versionAllowed = true

    @Volatile
    private var kugouPlaybackState = PlaybackState.STATE_NONE

    @Volatile
    private var otherPlayerPlaying = false

    @Volatile
    private var lastDeliveredLyric = ""
    @Volatile
    private var lastDeliveredNextLyric = ""
    private var lastDeliveredWordSpans: List<LrcTimelineParser.WordSpan> = emptyList()
    private var lastDeliveredKaraokeMs: Long = Const.SCROLL_DEFAULT_WINDOW_MS
    private var currentLyricLineSeq: Long = 0L

    @Volatile
    private var anchorPosMs: Long = -1L

    @Volatile
    private var anchorElapsedMs: Long = 0L

    @Volatile
    private var anchorSpeed: Float = 1f

    @Volatile
    private var anchorSource: String = "none"

    @Volatile
    private var currentTrackDurationMs: Long = -1L

    private var trackStartElapsedMs: Long = 0L
    private var lastObservedRawPosMs: Long = -1L
    private var activeEvalMetadata: MediaMetadata? = null

    private val recentLineDurationsMs = ArrayDeque<Long>(Const.SCROLL_SAMPLE_CAPACITY)
    private var currentTrackFp: String = ""
    private var currentTrackDisplayTitle: String = ""
    private var currentTrackArtist: String = ""
    private var currentLineStartPosMs: Long = -1L
    private var hasRealVocalLineInTrack: Boolean = false
    private var playingSinceUptimeMs: Long = -1L
    private var currentLineEffectivePlayMs: Long = 0L
    private var inInterlude: Boolean = false
    private var outroClearedInTrack: Boolean = false
    private var interludeCheckRunnable: Runnable? = null
    private var timelineInterludeRunnable: Runnable? = null

    // 在线时间轴状态与三道闸门
    @Volatile
    private var timelineRequestSeq: Long = 0L
    @Volatile
    private var fallbackRequestSeq: Long = 0L
    private var activeTimeline: LrcTimelineParser.ParsedTimeline? = null
    private var activeTimelineOwnerFp: String = ""
    private var activeTimelineIdentity: String = ""
    private var activeTimelineKugouId: String = ""
    private var lastTimelineReloadKept: Boolean = false
    private data class GateSample(
        val vocalIndex: Int,
        val offsetMs: Long,
        val sliceStartOffsetMs: Long = 0L,
        val source: String = "bt"
    )
    private data class OutlierSample(
        val vocalIndex: Int,
        val offsetMs: Long,
        val sliceStartOffsetMs: Long = 0L
    )
    private data class TrackFallbackState(
        val triedKugouIds: LinkedHashSet<String> = LinkedHashSet(),
        val pendingRejectKugouIds: LinkedHashSet<String> = LinkedHashSet(),
        var kugouFallbackCount: Int = 0,
        var lrclibFallbackTried: Boolean = false,
        var inFlight: Boolean = false,
        var softFailCount: Int = 0
    )
    private val trackFallbackStates = object : LinkedHashMap<String, TrackFallbackState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackFallbackState>?): Boolean {
            return size > 32
        }
    }

    private var timelineGateState: GateState = GateState.NONE
    private var gateVocalAttempts: Int = 0
    private var gateMissCount: Int = 0
    private var gateOutlierCount: Int = 0
    private val gateSamples = ArrayList<GateSample>(8)
    private val gateOutliers = ArrayList<OutlierSample>(8)
    private var trackTimeOffsetMs: Long = 0L
    private val recentOffsetSamples = ArrayDeque<Long>(3)
    private var lastMatchedVocalIndex: Int = -1
    // v1.4.5 G4/G6：按位置判定半句续播 gateJumpPosMs + 挂载初始位置 + 真实观测首句起点
    private var gateJumpPosMs: Long = -1L
    private var skipNextInterludeLearnAfterJump: Boolean = false
    private var skipNextFirstLineLearnAfterJump: Boolean = false
    private var trackAttachedInitialPosMs: Long = -1L
    private var firstObservedVocalPosInTrack: Long = -1L
    private var lastJumpEventUptimeMs: Long = 0L
    private val currentSessionValidFps = HashSet<String>(4)
    private val pendingFirstLineFixMap = HashMap<String, Long>(16)

    // 前奏状态、每秒 [INTRO_STATE] 自证日志与 200 条 LRU 学习表
    private var inIntro: Boolean = false
    private var activeIntroTargetMs: Long = -1L
    private var activeIntroSkipWaveLead: Boolean = false
    private var activeIntroForceWaveOnly: Boolean = false
    private var lastIntroEnterUptimeMs: Long = 0L
    private var lastSeekIntroArmUptimeMs: Long = 0L
    private var lastSeekIntroArmRawPos: Long = -1L
    private var lastSeekIntroArmFp: String = ""
    private var introSafetyTimeoutRunnable: Runnable? = null
    private var introTargetExpiryRunnable: Runnable? = null
    private var introStateLogRunnable: Runnable? = null

    // v1.2.11：切歌缓冲防误武装（记录 BUFFERING/rawPos<0，归零延后 1200ms 复核）
    private var sawBufferingOrInvalidPos: Boolean = false
    private var pendingRewindArmRunnable: Runnable? = null
    // v1.3.18：otherPlaying=true 瞬时抢占 2000ms 防抖任务，避免 1–2s 短暂提示音/短视频误毁前奏状态
    private var pendingOtherPlayingEndRunnable: Runnable? = null
    private val OTHER_PLAYING_CONFIRM_DELAY_MS = 2000L
    // v1.3.10：跨状态同曲重播记账（防止 PAUSED/BUFFERING 先报 rawPos=0 吃掉重播判定）+ B4 seek 观测时间戳
    private var pendingSameTrackRewind: Boolean = false
    // F1：当前已武装的间奏目标时间点（用于幂等去重，防区间内来回拖动重复下发/重启倒计时）
    private var activeInterludeTargetMs: Long = -1L
    // v1.4.5-fix10 F10-3：当前间奏段界（起点＝目标−段长，静默模型口径）与拖回开头跳波浪标记
    private var activeInterludeStartMs: Long = -1L
    private var activeInterludeSkipWaveLead: Boolean = false
    // F2：基于 PlaybackState.lastPositionUpdateTime 的上次观测时间（elapsedRealtime），取代回调墙钟作为 driftMs 基准
    private var lastObservedRawPosElapsedMs: Long = 0L
    // F5 + M1：切歌特征冻结窗（带主动 postDelayed 到期补偿与解冻重放标记，绝不吞事件）
    private var trackSwitchFreezeUntilUptimeMs: Long = 0L
    private var trackSwitchFrozenFp: String = ""
    private var trackSwitchFreezeTimeoutRunnable: Runnable? = null
    private var pendingPostFreezeReplay: Boolean = false
    private var trackConfirmedUptimeMs: Long = 0L

    private sealed class TrackSegment {
        data class Intro(val firstVocalMs: Long) : TrackSegment()
        data class Line(val idx: Int) : TrackSegment()
        data class GapLong(val prevIdx: Int, val nextIdx: Int, val targetNextPosMs: Long, val gapMs: Long) : TrackSegment()
        data class GapTail(val prevIdx: Int, val nextIdx: Int, val targetNextPosMs: Long) : TrackSegment()
        data class GapShort(val prevIdx: Int) : TrackSegment()
        data class Outro(val lastLineIdx: Int) : TrackSegment()
    }
    private var lastObservedRawPosUptimeMs: Long = 0L

    // v1.2.11：时间轴自主歌词兜底引擎（零轮询 postDelayed，独立追踪蓝牙歌词时间戳，绝不污染校准）
    private var lastBluetoothLyricUptimeMs: Long = 0L
    private var lastBluetoothLyricPosMs: Long = -1L
    private var lastTimelineFallbackIndex: Int = -1
    private var timelineFallbackActive: Boolean = false
    private var timelineFallbackRunnable: Runnable? = null

    private val learnedFirstLineStartMap = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > Const.INTRO_LEARN_CACHE_MAX
        }
    }
    private val learnedInterludesMap = object : LinkedHashMap<String, MutableList<InterludeSpan>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<InterludeSpan>>?): Boolean {
            return size > Const.INTRO_LEARN_CACHE_MAX
        }
    }

    private val paletteCache = object : LinkedHashMap<String, IntArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?): Boolean {
            return size > Const.GLOW_PALETTE_CACHE_MAX
        }
    }

    private val fingerprintV2 = TrackFingerprintV2(
        handler = mainHandler,
        onInitialTrack = { fp, displayTitle, artist ->
            if (readPrefs().masterEnabled && versionAllowed && isKugouActive()) {
                EventHistoryStore.replayRecentThrottled("initial_track")
                listener.onTrackConfirmedForDiag()
                val initTitle = activeEvalMetadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
                updateDurationFromMetadata(activeEvalMetadata)
                // v1.2.10：首次挂载已在播放中的曲目允许采纳任意有效 rawPos>=0（仅真实切歌用 5000ms 防旧曲尾残影）
                resetTrackTimingState(fp, displayTitle, artist, acceptAnyInitialPos = true)
                emitTrackInfo(displayTitle, artist)
                emitCoverPalette(fp, activeEvalMetadata)
                val initIsNonVocal = initTitle.isEmpty() ||
                    LrcTimelineParser.isCreditOrNonVocalLine(
                        initTitle,
                        displayTitle,
                        artist,
                        withinTrackIntroWindow = true
                    )
                if (initIsNonVocal) {
                    tryArmIntroIfEligible(reason = "initial_track")
                } else {
                    hasRealVocalLineInTrack = true
                    exitIntroIfActive("initial_vocal")
                }
                requestOnlineTimelineIfEnabled(fp, displayTitle, artist, currentTrackDurationMs)
            }
        },
        onTrackInfoHealed = { fp, displayTitle, artist ->
            if (readPrefs().masterEnabled && versionAllowed && isKugouActive()) {
                val oldFp = currentTrackFp
                val oldTitle = currentTrackDisplayTitle
                val oldArtist = currentTrackArtist
                val fpChanged = fp.isNotBlank() && fp != oldFp
                val titleChanged = displayTitle.isNotBlank() && displayTitle != oldTitle
                currentTrackFp = fp
                if (fp.isNotBlank()) {
                    currentSessionValidFps.add(fp)
                }
                if (fpChanged && oldFp.isNotBlank()) {
                    if (activeTimelineOwnerFp == oldFp) {
                        activeTimelineOwnerFp = fp
                    }
                    val oldFbKey = "$oldFp|${LrcTimelineParser.normalizeCacheKey(oldTitle, oldArtist)}"
                    val newFbKey = "$fp|${LrcTimelineParser.normalizeCacheKey(displayTitle, artist)}"
                    trackFallbackStates.remove(oldFbKey)?.let { migrated ->
                        trackFallbackStates[newFbKey] = migrated
                    }
                    YLog.info(msg = "[TRACK_V3_HEAL_MIGRATE] oldFp=$oldFp -> newFp=$fp ownerMigrated=${activeTimelineOwnerFp == fp}")
                }
                currentTrackDisplayTitle = displayTitle
                currentTrackArtist = artist
                emitTrackInfo(displayTitle, artist)
                if (fpChanged) {
                    // N3 + 建议项：归属与回退状态迁移完成后再触发解冻补偿，并同步刷新 trackConfirmedUptimeMs
                    trackConfirmedUptimeMs = SystemClock.uptimeMillis()
                    clearTrackSwitchFreeze(triggerReplay = true, reason = "heal_fp_changed")
                }
                if ((fpChanged || titleChanged) && activeTimeline == null && timelineGateState == GateState.NONE) {
                    requestOnlineTimelineIfEnabled(fp, displayTitle, artist, currentTrackDurationMs)
                }
            }
        },
        onTrackConfirmed = { _, newFp, bufferedTitle, displayTitle, artist ->
            if (readPrefs().masterEnabled && versionAllowed && isKugouActive()) {
                EventHistoryStore.replayRecentThrottled("track_confirmed")
                listener.onTrackConfirmedForDiag()
                val latestMeta = boundKugouSessions.values.firstOrNull()?.controller?.metadata
                updateDurationFromMetadata(latestMeta)
                resetTrackTimingState(newFp, displayTitle, artist)
                emitTrackInfo(displayTitle, artist)
                emitCoverPalette(newFp, latestMeta)

                val isPlaying = kugouPlaybackState == PlaybackState.STATE_PLAYING
                val bufferedNonVocal = bufferedTitle.isBlank() ||
                    LrcTimelineParser.isCreditOrNonVocalLine(
                        bufferedTitle,
                        displayTitle,
                        artist,
                        withinTrackIntroWindow = true
                    )

                if (bufferedNonVocal) {
                    lastDeliveredLyric = ""
                    lastDeliveredNextLyric = ""
                    currentLineStartPosMs = estimateCurrentPositionMs()
                    restartLineClock(isPlaying)
                    listener.onTrackSwitched("", isPlaying, currentEstimatedWindowMs())
                    tryArmIntroIfEligible(reason = "track_confirmed")
                } else {
                    hasRealVocalLineInTrack = true
                    lastDeliveredLyric = bufferedTitle
                    lastDeliveredNextLyric = ""
                    currentLineStartPosMs = estimateCurrentPositionMs()
                    restartLineClock(isPlaying)
                    listener.onTrackSwitched(bufferedTitle, isPlaying, currentEstimatedWindowMs())
                    exitIntroIfActive("confirmed_vocal")
                }

                requestOnlineTimelineIfEnabled(newFp, displayTitle, artist, currentTrackDurationMs)
                scheduleInterludeCheckIfNeeded()
            }
        }
    )

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        sessionsBreaker.execute {
            if (!readPrefs().masterEnabled) {
                listener.onSessionEnded("master_switch_off")
                return@execute
            }
            syncActiveSessions(controllers.orEmpty(), trigger = "listener")
        }
    }

    fun estimateCurrentPositionMs(): Long {
        val basePos = anchorPosMs
        if (basePos < 0L) return -1L
        if (kugouPlaybackState != PlaybackState.STATE_PLAYING) return basePos
        val updateElapsed = anchorElapsedMs
        if (updateElapsed <= 0L) return basePos
        val deltaMs = (SystemClock.elapsedRealtime() - updateElapsed).coerceAtLeast(0L)
        val speed = max(0f, anchorSpeed)
        return basePos + (deltaMs * speed).toLong()
    }

    fun currentPositionSource(): String = anchorSource

    // v1.4.5-fix16：惰性初始化——暂停态启动时 init_2s 二次同步推迟到第一次播放
    @Volatile
    private var mediaSessionManager: MediaSessionManager? = null

    @Volatile
    private var deferredInitPull: Boolean = false

    fun start(ctx: Context) {
        if (!started.compareAndSet(false, true)) return
        val appCtx = ctx.applicationContext ?: ctx
        hostAppContext = appCtx
        EventHistoryStore.init(appCtx)
        // v1.4.5-fix16（Quick 二轮裁决）：[PROC] 进程诊断——pid/启动时刻/上次退出原因，
        // 一行区分 LMK 低内存杀、崩溃、系统电源管理杀与用户主动杀（约 130s 一轮的重启定因专用）
        runCatching {
            val startElapsed = if (Build.VERSION.SDK_INT >= 31) Process.getStartElapsedRealtime() else -1L
            var prevExit = "none"
            if (Build.VERSION.SDK_INT >= 30) {
                val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val list = am?.getHistoricalProcessExitReasons(appCtx.packageName, 0, 1)
                val info = list?.firstOrNull()
                if (info != null) {
                    // v1.4.5-fix17（Quick fix17 裁决）：补 description/pss/rss——区分内存回收杀与系统主动杀
                    prevExit = "reason=${info.reason} importance=${info.importance} pid=${info.pid} ts=${info.timestamp} " +
                        "desc=${info.description ?: ""} pss=${info.pss} rss=${info.rss}"
                }
            }
            val procMsg = "[PROC] pid=${Process.myPid()} startElapsed=$startElapsed uptime=${SystemClock.elapsedRealtime()} prevExit=$prevExit"
            YLog.info(msg = procMsg)
            EventHistoryStore.record(procMsg)
        }
        loadLearnedCacheFromHostPrefs(appCtx)
        checkKugouVersion(appCtx)

        initBreaker.execute {
            val msm = appCtx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            if (msm == null) {
                YLog.error(msg = "[ISLAND_MSM_FAIL] MediaSessionManager 为 null")
                return@execute
            }

            runCatching {
                msm.addOnActiveSessionsChangedListener(sessionsListener, null, mainHandler)
                YLog.info(
                    msg = "[ISLAND_MSM_OK] :island (uid=${Process.myUid()}) 已注册 MediaSessionManager 监听"
                )
            }.onFailure { err ->
                YLog.error(
                    msg = "[ISLAND_MSM_FAIL] 注册 OnActiveSessionsChangedListener 失败: ${err.javaClass.name}: ${err.message}",
                    e = err
                )
            }

            mediaSessionManager = msm
            pullSessions(msm, "init_now")
            mainHandler.postDelayed({
                initBreaker.execute {
                    if (readPrefs().masterEnabled) {
                        // v1.4.5-fix16：暂停态不起二次同步（每次进程重启都全量重跑是静置耗电的一环），
                        // 推迟到第一次进入播放时补（handlePlaybackState 消费 deferredInitPull）
                        if (kugouPlaybackState == PlaybackState.STATE_PLAYING) {
                            pullSessions(msm, "init_2s")
                        } else {
                            deferredInitPull = true
                            val dMsg = "[PROC] init_2s deferred state=$kugouPlaybackState"
                            YLog.info(msg = dMsg)
                            EventHistoryStore.record(dMsg)
                        }
                    }
                }
            }, 2000L)
        }
    }

    // ==================== 位置锚点纪律（Q1 + P2） ====================

    /** 仅采纳 [0..maxAcceptablePosMs] 内的控制器位置，避免切歌首帧读取到旧曲结尾位置 */
    private fun reanchorFromControllers(maxAcceptablePosMs: Long = Long.MAX_VALUE): Boolean {
        val now = SystemClock.elapsedRealtime()
        var bestState: PlaybackState? = null
        for (bound in boundKugouSessions.values) {
            val st = bound.controller.playbackState ?: continue
            if (st.position < 0L || st.position > maxAcceptablePosMs) continue
            if (st.state == PlaybackState.STATE_PLAYING) {
                bestState = st
                break
            }
            if (bestState == null) {
                bestState = st
            }
        }
        val st = bestState ?: return false
        val prevSource = anchorSource
        val prevEstPos = estimateCurrentPositionMs()
        anchorPosMs = st.position
        anchorElapsedMs = if (st.lastPositionUpdateTime > 0L) st.lastPositionUpdateTime else now
        anchorSpeed = if (st.playbackSpeed > 0f) st.playbackSpeed else 1f
        anchorSource = "state"
        if (trackAttachedInitialPosMs < 0L) {
            trackAttachedInitialPosMs = st.position
        }
        handleAnchorUpgradedToState(prevSource, prevEstPos, st.position)
        return true
    }

    /** v1.4.3 锁 2：virtual->state 清 PENDING 样本与计次；timeline_anchor->state 若漂移 >8s 回 PENDING 重验；extrap->state 不清样 */
    private fun handleAnchorUpgradedToState(prevSource: String, prevEstPosMs: Long, newRawPosMs: Long) {
        if (prevSource == "virtual" && timelineGateState == GateState.PENDING) {
            resetGateCountersOnly()
            YLog.info(msg = "[TIMELINE_GATE] RESET reason=virtual_to_state newPos=$newRawPosMs")
        } else if (prevSource == "timeline_anchor" &&
            timelineGateState == GateState.PASSED &&
            prevEstPosMs >= 0L &&
            abs(newRawPosMs - prevEstPosMs) > 8_000L
        ) {
            timelineGateState = GateState.PENDING
            resetGateCountersOnly()
            recentOffsetSamples.clear()
            trackTimeOffsetMs = 0L
            lastMatchedVocalIndex = -1
            val msg = "[TIMELINE_GATE] RE_PENDING reason=timeline_anchor_to_state prevEst=$prevEstPosMs newPos=$newRawPosMs"
            YLog.info(msg = msg)
            EventHistoryStore.record(msg)
        }
    }

    private fun ensureVirtualAnchorIfMissing() {
        if (anchorPosMs >= 0L) return
        val now = SystemClock.elapsedRealtime()
        val startRef = if (trackStartElapsedMs > 0L) trackStartElapsedMs else now
        anchorPosMs = 0L
        anchorElapsedMs = startRef
        anchorSpeed = 1f
        anchorSource = "virtual"
    }

    private fun freezeAnchorAtNow() {
        val cur = estimateCurrentPositionMs()
        if (cur >= 0L) {
            anchorPosMs = cur
            anchorElapsedMs = SystemClock.elapsedRealtime()
            if (anchorSource == "state") {
                anchorSource = "extrap"
            }
        }
    }

    // ==================== 统一前奏武装入口（Q1：三处共用 + 每秒自证日志） ====================

    private fun resolveOwnedFirstVocalMs(): Long {
        if (currentTrackFp.isEmpty()) return -1L
        val ownedTimelineFirst = activeTimeline
            ?.takeIf {
                activeTimelineOwnerFp == currentTrackFp &&
                    timelineGateState != GateState.FAILED &&
                    !it.isInstrumental &&
                    it.firstVocalMs >= Const.INTRO_MIN_VALID_START_MS
            }?.firstVocalMs ?: -1L
        val learnedMs = synchronized(learnedFirstLineStartMap) {
            learnedFirstLineStartMap[currentTrackFp] ?: -1L
        }
        // G6：PENDING 且学习值与时间轴差 >5s 时先按学习值武装，PASS 后再切精确值
        if (timelineGateState == GateState.PENDING &&
            ownedTimelineFirst > 0L &&
            learnedMs >= Const.INTRO_MIN_VALID_START_MS &&
            abs(ownedTimelineFirst - learnedMs) > 5_000L
        ) {
            return learnedMs
        }
        return if (ownedTimelineFirst > 0L) ownedTimelineFirst else learnedMs
    }

    private fun isIsolatedTrackFp(fp: String): Boolean {
        return fp.substringAfterLast('|', "").startsWith("u_")
    }

    private fun tryArmIntroIfEligible(reason: String, skipWaveLead: Boolean = false): Boolean {
        if (hasRealVocalLineInTrack || currentTrackFp.isEmpty()) return false
        if (!readPrefs().masterEnabled || !versionAllowed || !isKugouActive()) return false

        // G2：严守归属键一致性，禁止使用上一首残值时间轴武装
        val timelineFirst = activeTimeline
            ?.takeIf {
                activeTimelineOwnerFp == currentTrackFp &&
                    timelineGateState != GateState.FAILED &&
                    !it.isInstrumental &&
                    it.firstVocalMs >= Const.INTRO_MIN_VALID_START_MS
            }?.firstVocalMs ?: -1L
        val learnedMs = synchronized(learnedFirstLineStartMap) {
            learnedFirstLineStartMap[currentTrackFp] ?: -1L
        }
        val usePendingLearned = timelineGateState == GateState.PENDING &&
            timelineFirst > 0L &&
            learnedMs >= Const.INTRO_MIN_VALID_START_MS &&
            abs(timelineFirst - learnedMs) > 5_000L
        val targetMs = when {
            usePendingLearned -> learnedMs
            timelineFirst > 0L -> timelineFirst
            else -> learnedMs
        }
        val src = when {
            usePendingLearned -> "learned_pending_guard"
            timelineFirst > 0L -> "timeline"
            learnedMs >= Const.INTRO_MIN_VALID_START_MS -> "learned"
            else -> "none"
        }
        if (targetMs < Const.INTRO_MIN_VALID_START_MS) return false

        ensureVirtualAnchorIfMissing()
        val curPos = estimateCurrentPositionMs()
        // G3/G6 统一保护阈值 1500ms（防回退晚到对已过去的前奏弹倒计时）
        val guardMs = if (reason == "seek_back_to_intro" || reason == "timeline_loaded" || reason == "gate_pass_refine") Const.TIMELINE_INTRO_GUARD_MS else 500L
        if (curPos >= targetMs - guardMs) return false

        // X4 + Y2：武装时一次性锁定；gate_pass_refine 沿用当前锁定值不被冲掉；前奏内 seek 按新剩余时长重算
        val remAtArmMs = if (curPos >= 0L) (targetMs - curPos) else targetMs
        val lockedSkipWaveLead: Boolean
        val lockedForceWaveOnly: Boolean
        if (reason == "gate_pass_refine") {
            lockedSkipWaveLead = activeIntroSkipWaveLead
            lockedForceWaveOnly = activeIntroForceWaveOnly
        } else if (skipWaveLead) {
            lockedSkipWaveLead = remAtArmMs >= 3_000L
            lockedForceWaveOnly = remAtArmMs < 3_000L
        } else if (reason == "seek_back_to_intro" || reason == "seek_in_intro") {
            // v1.4.5-fix13 B（Quick 裁决＋老大 19:54 定）：前奏内拖动不再跳波浪——Renderer 的
            // isInIntroWaveLeadPhase 本就按落点算剩余波浪段（剩多少走多少、过窗直接出数字）；
            // 仅落点离首句不足 3s 时整段只显波浪不出数字，防数字一闪而过
            lockedSkipWaveLead = false
            lockedForceWaveOnly = remAtArmMs < 3_000L
        } else {
            lockedSkipWaveLead = false
            lockedForceWaveOnly = false
        }

        val alreadyArmedSameTarget = inIntro &&
            activeIntroTargetMs == targetMs &&
            activeIntroSkipWaveLead == lockedSkipWaveLead &&
            activeIntroForceWaveOnly == lockedForceWaveOnly
        inIntro = true
        activeIntroTargetMs = targetMs
        activeIntroSkipWaveLead = lockedSkipWaveLead
        activeIntroForceWaveOnly = lockedForceWaveOnly
        lastIntroEnterUptimeMs = SystemClock.uptimeMillis()
        if (reason == "seek_back_to_intro" || reason == "seek_in_intro") {
            lastSeekIntroArmUptimeMs = lastIntroEnterUptimeMs
            // 仓侧直修（Quick Q5 疑点＋编译修正）：直接记本次 arm 实际用的 curPos——原回包写的 lastPlaybackPosMs 在本类根本不存在（实为 lastObservedRawPosMs，且拖动帧可能是旧位置），会让 F2 的 ≤100ms 判据永不命中；curPos<0 时守卫自然不命中，安全
            lastSeekIntroArmRawPos = curPos
            lastSeekIntroArmFp = currentTrackFp
        }

        if (!alreadyArmedSameTarget) {
            val modeTag = when {
                lockedSkipWaveLead -> " skipWaveLead=true"
                lockedForceWaveOnly -> " forceWaveOnly=true"
                else -> ""
            }
            val msg = "[COUNTDOWN] arm target=intro firstVocalMs=$targetMs pos=$curPos posSource=$anchorSource reason=$reason src=$src fp=$currentTrackFp$modeTag"
            YLog.info(msg = msg)
            EventHistoryStore.record(msg)
            listener.onIntroChanged(
                inIntro = true,
                learnedFirstLineMs = targetMs,
                skipWaveLead = lockedSkipWaveLead,
                forceWaveOnly = lockedForceWaveOnly
            )
            if (inInterlude) {
                inInterlude = false
                activeInterludeTargetMs = -1L
                listener.onInterludeChanged(false, -1L)
            }
            // v1.2.11：延后一拍到渲染器 mainHandler.post 执行完毕后再采样首次 [INTRO_STATE]，避免抢跑误报 NONE
            mainHandler.post {
                if (inIntro && activeIntroTargetMs == targetMs) {
                    logIntroStateOnce(recordToHistory = true)
                }
            }
        }
        if (kugouPlaybackState == PlaybackState.STATE_PLAYING) {
            scheduleIntroTargetExpiry()
            scheduleIntroSafetyTimeout()
            scheduleIntroStateLogger()
        }
        return true
    }

    private fun exitIntroIfActive(reason: String) {
        cancelIntroTargetExpiry()
        cancelIntroSafetyTimeout()
        cancelIntroStateLogger()
        if (inIntro) {
            inIntro = false
            activeIntroTargetMs = -1L
            activeIntroSkipWaveLead = false
            activeIntroForceWaveOnly = false
            listener.onIntroChanged(inIntro = false, learnedFirstLineMs = -1L)
            val (cdVis, rightMode) = introStateQuery()
            val msg = "[INTRO_STATE] inIntro=false firstVocalMs=-1 pos=${estimateCurrentPositionMs()} remaining=0 countdownVisible=$cdVis rightMode=$rightMode reason=$reason"
            YLog.info(msg = msg)
            EventHistoryStore.record(msg)
        }
    }

    private fun scheduleIntroStateLogger() {
        cancelIntroStateLogger()
        if (!inIntro || kugouPlaybackState != PlaybackState.STATE_PLAYING) return
        val task = object : Runnable {
            override fun run() {
                if (!inIntro || kugouPlaybackState != PlaybackState.STATE_PLAYING) {
                    introStateLogRunnable = null
                    return
                }
                logIntroStateOnce(recordToHistory = false)
                mainHandler.postDelayed(this, 1000L)
            }
        }
        introStateLogRunnable = task
        mainHandler.postDelayed(task, 1000L)
    }

    private fun cancelIntroStateLogger() {
        introStateLogRunnable?.let { mainHandler.removeCallbacks(it) }
        introStateLogRunnable = null
    }

    private fun logIntroStateOnce(recordToHistory: Boolean) {
        val pos = estimateCurrentPositionMs()
        val target = activeIntroTargetMs
        val rem = if (target > 0L && pos >= 0L) (target - pos).coerceAtLeast(0L) else -1L
        val (cdVis, rightMode) = introStateQuery()
        val msg = "[INTRO_STATE] inIntro=$inIntro firstVocalMs=$target pos=$pos remaining=$rem countdownVisible=$cdVis rightMode=$rightMode"
        YLog.info(msg = msg)
        if (recordToHistory) {
            EventHistoryStore.record(msg)
        }
    }

    // ==================== 在线时间轴异步拉取（零阻塞主线程） ====================

    private fun logTimelineResult(fp: String, outcome: String, firstVocalMs: Long) {
        val msg = "[TIMELINE_RESULT] fp=$fp outcome=$outcome firstVocalMs=$firstVocalMs"
        YLog.info(msg = msg)
        EventHistoryStore.record(msg)
    }

    private fun resetGateCountersOnly() {
        gateVocalAttempts = 0
        gateMissCount = 0
        gateOutlierCount = 0
        gateSamples.clear()
        gateOutliers.clear()
    }

    private fun getOrCreateFallbackState(fp: String, title: String, artist: String): TrackFallbackState {
        val key = "${fp}|${LrcTimelineParser.normalizeCacheKey(title, artist)}"
        return trackFallbackStates.getOrPut(key) { TrackFallbackState() }
    }

    private fun requestOnlineTimelineIfEnabled(
        fp: String,
        title: String,
        artist: String,
        durationMs: Long,
        excludeKugouIds: Set<String> = emptySet(),
        forceLrclibOnly: Boolean = false,
        isFallback: Boolean = false
    ) {
        val learnedMs = synchronized(learnedFirstLineStartMap) { learnedFirstLineStartMap[fp] ?: -1L }
        if (!readPrefs().onlineTimelineEnabled) {
            val outcome = if (learnedMs >= Const.INTRO_MIN_VALID_START_MS) "learned_only" else "disabled_by_pref"
            logTimelineResult(fp, outcome, if (learnedMs >= Const.INTRO_MIN_VALID_START_MS) learnedMs else -1L)
            return
        }
        if (title.isBlank()) return
        val ctx = hostAppContext ?: return
        val fbState = getOrCreateFallbackState(fp, title, artist)
        if (isFallback) {
            if (fbState.inFlight) return
            fbState.inFlight = true
        }
        // G7：回退请求使用独立序号 fallbackRequestSeq，不被普通重载递增的 timelineRequestSeq 丢弃
        val reqSeq = if (isFallback) ++fallbackRequestSeq else ++timelineRequestSeq
        val excludeCsv = excludeKugouIds.filter { it.isNotBlank() }.joinToString(",")

        val submitted = try {
            timelineExecutor.execute {
                var postedReset = false
                try {
                    var callErrDetail = ""
                    val bundle = try {
                        val extras = Bundle().apply {
                            putString(Const.EXTRA_TITLE, title)
                            putString(Const.EXTRA_ARTIST, artist)
                            putLong(Const.EXTRA_DURATION_MS, durationMs)
                            if (excludeCsv.isNotEmpty()) {
                                putString(Const.EXTRA_EXCLUDE_KUGOU_IDS, excludeCsv)
                            }
                            if (forceLrclibOnly) {
                                putBoolean(Const.EXTRA_FORCE_LRCLIB_ONLY, true)
                            }
                        }
                        ctx.contentResolver.call(
                            Uri.parse(Const.LYRIC_PROVIDER_URI),
                            Const.METHOD_FETCH_TIMELINE,
                            null,
                            extras
                        )
                    } catch (t: Throwable) {
                        callErrDetail = "${t.javaClass.simpleName}:${t.message}"
                        null
                    }

                    val status = bundle?.getString(Const.EXTRA_STATUS) ?: "miss"
                    val src = bundle?.getString(Const.EXTRA_SOURCE) ?: if (callErrDetail.isNotEmpty()) "call_err" else "provider_null"
                    val errDetail = bundle?.getString(Const.EXTRA_ERROR_DETAIL).orEmpty().ifBlank { callErrDetail }
                    val lrc = bundle?.getString(Const.EXTRA_LRC).orEmpty()
                    val inst = bundle?.getBoolean(Const.EXTRA_INSTRUMENTAL, false) ?: false
                    val elapsedMs = bundle?.getLong(Const.EXTRA_ELAPSED_MS, -1L) ?: -1L
                    val kugouId = bundle?.getString(Const.EXTRA_KUGOU_ID).orEmpty()
                    val isNetErr = (bundle?.getBoolean(Const.EXTRA_NETWORK_ERROR, false) ?: false) ||
                        src == "call_err" || src == "provider_null" || src == "net_err" || src == "fallback_net_err"

                    val parsed = if (status == "hit" && (lrc.isNotBlank() || inst)) {
                        LrcTimelineParser.parseAuto(lrc, title, artist, flaggedInstrumental = inst)
                    } else {
                        null
                    }

                    val errPart = if (errDetail.isNotBlank()) " err=$errDetail" else ""
                    val idPart = if (kugouId.isNotBlank()) " kugouId=$kugouId" else ""
                    val tlMsg = "[TIMELINE] $status src=$src$idPart$errPart ms=$elapsedMs vocalLines=${parsed?.vocalLines?.size ?: 0} " +
                        "firstVocal=${parsed?.firstVocalMs ?: -1L} krc=${parsed?.hasWordTiming ?: false} inst=${parsed?.isInstrumental ?: inst} " +
                        "track=${LogFormat.preview(title, 32)}"
                    YLog.info(msg = tlMsg)
                    EventHistoryStore.record(tlMsg)

                    postedReset = mainHandler.post {
                        if (isFallback) {
                            fbState.inFlight = false
                        }
                        val seqMismatch = if (isFallback) (reqSeq != fallbackRequestSeq) else (reqSeq != timelineRequestSeq)
                        val fpValidInSession = fp == currentTrackFp || currentSessionValidFps.contains(fp)
                        if (seqMismatch || !fpValidInSession) return@post
                        val effectiveFp = currentTrackFp.ifBlank { fp }
                        val effectiveTitle = currentTrackDisplayTitle.ifBlank { title }
                        val effectiveArtist = currentTrackArtist.ifBlank { artist }
                        // Y1：回包侧统一按当前有效 fp + 当前标题/歌手取回退状态，与 heal 迁移后的键逐字一致
                        val activeFbState = getOrCreateFallbackState(effectiveFp, effectiveTitle, effectiveArtist)
                        if (isFallback) {
                            activeFbState.inFlight = false
                        }
                        if (!isKugouActive() || !readPrefs().onlineTimelineEnabled) return@post

                        val learnedFallback = synchronized(learnedFirstLineStartMap) { learnedFirstLineStartMap[effectiveFp] ?: -1L }
                        if (parsed == null || parsed.isInstrumental || parsed.vocalLines.isEmpty()) {
                            val outcome = when {
                                src == "neg_cache" -> "cache_negative"
                                src == "auth_denied" -> "auth_denied($errDetail)"
                                src == "call_err" || src == "provider_null" -> "$src($errDetail)"
                                learnedFallback >= Const.INTRO_MIN_VALID_START_MS -> "learned_only($src)"
                                else -> "fetch_fail($src${if (errDetail.isNotBlank()) ":$errDetail" else ""})"
                            }
                            logTimelineResult(effectiveFp, outcome, if (learnedFallback >= Const.INTRO_MIN_VALID_START_MS) learnedFallback else -1L)
                            if (isFallback) {
                                if (isNetErr) {
                                    // F13：网络失败不升级歌曲级负缓存、不计入耗尽，退回本次回退额度
                                    if (forceLrclibOnly) {
                                        activeFbState.lrclibFallbackTried = false
                                    } else if (activeFbState.kugouFallbackCount > 0) {
                                        activeFbState.kugouFallbackCount--
                                    }
                                    val netMsg = "[TIMELINE_FALLBACK] stage=net_err fp=$effectiveFp reason=$src err=$errDetail -> 不写负缓存且保留回退额度"
                                    YLog.warn(msg = netMsg)
                                    EventHistoryStore.record(netMsg)
                                } else if (!forceLrclibOnly && !activeFbState.lrclibFallbackTried) {
                                    activeFbState.lrclibFallbackTried = true
                                    val fbMsg = "[TIMELINE_FALLBACK] stage=lrclib fp=$effectiveFp reason=kugou_next_miss($src) tried=${activeFbState.triedKugouIds.joinToString(",")}"
                                    YLog.info(msg = fbMsg)
                                    EventHistoryStore.recordDiagOnly(fbMsg)
                                    requestOnlineTimelineIfEnabled(
                                        fp = effectiveFp,
                                        title = effectiveTitle,
                                        artist = effectiveArtist,
                                        durationMs = durationMs,
                                        excludeKugouIds = activeFbState.triedKugouIds.toSet(),
                                        forceLrclibOnly = true,
                                        isFallback = true
                                    )
                                } else {
                                    if (activeFbState.pendingRejectKugouIds.isNotEmpty()) {
                                        val reverted = activeFbState.pendingRejectKugouIds.joinToString(",")
                                        activeFbState.pendingRejectKugouIds.clear()
                                        val revMsg = "[CAND_REJECT] stage=reverted fp=$effectiveFp kugouIds=$reverted reason=fallback_exhausted"
                                        YLog.info(msg = revMsg)
                                        EventHistoryStore.record(revMsg)
                                    }
                                    val exMsg = "[TIMELINE_FALLBACK] stage=exhausted fp=$effectiveFp reason=$src -> 升级歌曲级负缓存"
                                    YLog.warn(msg = exMsg)
                                    EventHistoryStore.record(exMsg)
                                    rejectCurrentTrackTimelineAsync(effectiveTitle, effectiveArtist, scope = Const.REJECT_SCOPE_TRACK)
                                }
                            } else if (learnedFallback >= Const.INTRO_MIN_VALID_START_MS) {
                                tryArmIntroIfEligible(reason = "learned_fallback_after_miss")
                            }
                            return@post
                        }
                        if (!isFallback && kugouId.isNotBlank()) {
                            val isKnownBad = activeFbState.pendingRejectKugouIds.contains(kugouId) ||
                                ((activeFbState.kugouFallbackCount > 0 || activeFbState.lrclibFallbackTried) &&
                                    kugouId != activeTimelineKugouId &&
                                    activeFbState.triedKugouIds.contains(kugouId))
                            if (isKnownBad) {
                                val dropMsg = "[TIMELINE_RESULT] fp=$effectiveFp outcome=reload_dropped_rejected_cand kugouId=$kugouId"
                                YLog.info(msg = dropMsg)
                                EventHistoryStore.record(dropMsg)
                                return@post
                            }
                        }
                        if (kugouId.isNotBlank()) {
                            activeFbState.triedKugouIds.add(kugouId)
                        }

                        // v1.4.4 第 1 条：比对时间轴身份（fp + vocalLines 内容指纹 + firstVocalMs + kugouId）；身份不变时保留 gate 状态
                        val contentFp = LrcTimelineParser.computeTimelineContentFingerprint(parsed)
                        val newIdentity = "$fp|$contentFp|${parsed.firstVocalMs}|$kugouId"
                        val reloadKept = !isFallback &&
                            activeTimeline != null &&
                            activeTimelineIdentity.isNotEmpty() &&
                            newIdentity == activeTimelineIdentity
                        lastTimelineReloadKept = reloadKept

                        // F7：本会话软失败已达上限（首次软失败 + 最多重验 1 次）时保持 FAILED 走学习模式，不再反复进 PENDING
                        if (!reloadKept && !isFallback && activeFbState.softFailCount >= 2) {
                            val capMsg = "[TIMELINE_RESULT] fp=$fp outcome=soft_fail_capped softFailCount=${activeFbState.softFailCount} reloadKept=false -> 保持学习模式"
                            YLog.info(msg = capMsg)
                            EventHistoryStore.record(capMsg)
                            timelineGateState = GateState.FAILED
                            activeTimeline = null
                            activeTimelineIdentity = ""
                            activeTimelineKugouId = ""
                            return@post
                        }

                        val hitOutcome = if (src == "cache" || src == "cache_krc") "cache_timeline" else "fetch_ok"
                        val resMsg = "[TIMELINE_RESULT] fp=$fp outcome=$hitOutcome firstVocalMs=${parsed.firstVocalMs} reloadKept=$reloadKept" +
                            if (kugouId.isNotBlank()) " kugouId=$kugouId" else ""
                        YLog.info(msg = resMsg)
                        EventHistoryStore.record(resMsg)

                        if (reloadKept) {
                            activeTimeline = parsed
                            activeTimelineOwnerFp = effectiveFp
                            activeTimelineKugouId = kugouId
                        } else {
                            activeTimeline = parsed
                            activeTimelineOwnerFp = effectiveFp
                            activeTimelineIdentity = newIdentity
                            activeTimelineKugouId = kugouId
                            timelineGateState = GateState.PENDING
                            resetGateCountersOnly()
                            recentOffsetSamples.clear()
                            trackTimeOffsetMs = 0L
                            lastMatchedVocalIndex = -1
                            lastTimelineFallbackIndex = -1
                        }

                        tryArmIntroIfEligible(reason = "timeline_loaded")
                        tryUpgradeCurrentEstimateLineWithTimeline()
                        scheduleTimelineLyricFallback(trigger = "timeline_loaded", immediate = !inIntro && lastDeliveredLyric.isBlank())
                    }
                } catch (t: Throwable) {
                    // G7 F10：捕获执行体未预料异常并打日志，随后由 finally 复位 inFlight
                    YLog.warn(msg = "[TIMELINE_FALLBACK] runnable_err fp=$fp isFallback=$isFallback err=${t.javaClass.simpleName}:${t.message}")
                } finally {
                    // F10：保证任何异常或 post 失败路径均复位 inFlight，防止该曲本进程永久锁死回退
                    if (isFallback && !postedReset) {
                        mainHandler.post {
                            fbState.inFlight = false
                            if (fp == currentTrackFp || currentSessionValidFps.contains(fp)) {
                                val effFp = currentTrackFp.ifBlank { fp }
                                val effTitle = currentTrackDisplayTitle.ifBlank { title }
                                val effArtist = currentTrackArtist.ifBlank { artist }
                                getOrCreateFallbackState(effFp, effTitle, effArtist).inFlight = false
                            }
                            YLog.warn(msg = "[TIMELINE_FALLBACK] inFlight_force_reset fp=$fp")
                        }
                    }
                }
            }
            true
        } catch (t: Throwable) {
            if (isFallback) {
                fbState.inFlight = false
                YLog.warn(msg = "[TIMELINE_FALLBACK] execute_rejected fp=$fp err=${t.javaClass.simpleName}")
            }
            false
        }
        if (!submitted) return
    }

    private fun tryTriggerFallbackFetch(
        fp: String,
        title: String,
        artist: String,
        durationMs: Long,
        failedKugouId: String
    ): Boolean {
        val fbState = getOrCreateFallbackState(fp, title, artist)
        if (failedKugouId.isNotBlank()) {
            fbState.triedKugouIds.add(failedKugouId)
        }
        if (fbState.inFlight) return true
        if (fbState.kugouFallbackCount < 1 && failedKugouId.isNotBlank()) {
            fbState.kugouFallbackCount++
            val fbMsg = "[TIMELINE_FALLBACK] stage=kugou_next fp=$fp excluded=${fbState.triedKugouIds.joinToString(",")} count=${fbState.kugouFallbackCount}"
            YLog.info(msg = fbMsg)
            EventHistoryStore.recordDiagOnly(fbMsg)
            requestOnlineTimelineIfEnabled(
                fp = fp,
                title = title,
                artist = artist,
                durationMs = durationMs,
                excludeKugouIds = fbState.triedKugouIds.toSet(),
                forceLrclibOnly = false,
                isFallback = true
            )
            return true
        }
        if (!fbState.lrclibFallbackTried) {
            fbState.lrclibFallbackTried = true
            val fbMsg = "[TIMELINE_FALLBACK] stage=lrclib fp=$fp excluded=${fbState.triedKugouIds.joinToString(",")}"
            YLog.info(msg = fbMsg)
            EventHistoryStore.recordDiagOnly(fbMsg)
            requestOnlineTimelineIfEnabled(
                fp = fp,
                title = title,
                artist = artist,
                durationMs = durationMs,
                excludeKugouIds = fbState.triedKugouIds.toSet(),
                forceLrclibOnly = true,
                isFallback = true
            )
            return true
        }
        val exMsg = "[TIMELINE_FALLBACK] stage=exhausted fp=$fp tried=${fbState.triedKugouIds.joinToString(",")} -> 升级歌曲级负缓存"
        YLog.warn(msg = exMsg)
        EventHistoryStore.record(exMsg)
        return false
    }

    private fun rejectCurrentTrackTimelineAsync(
        title: String,
        artist: String,
        scope: String = Const.REJECT_SCOPE_TRACK,
        kugouId: String = ""
    ) {
        val ctx = hostAppContext ?: return
        if (title.isBlank()) return
        timelineExecutor.execute {
            runCatching {
                val extras = Bundle().apply {
                    putString(Const.EXTRA_TITLE, title)
                    putString(Const.EXTRA_ARTIST, artist)
                    putString(Const.EXTRA_REJECT_SCOPE, scope)
                    if (kugouId.isNotBlank()) {
                        putString(Const.EXTRA_KUGOU_ID, kugouId)
                    }
                }
                ctx.contentResolver.call(
                    Uri.parse(Const.LYRIC_PROVIDER_URI),
                    Const.METHOD_REJECT_TIMELINE,
                    null,
                    extras
                )
            }
        }
    }

    /** v1.4.3 P1-4：校验门 PASS 后异步通知 Provider 清零该曲拒表计数 */
    private fun clearTrackRejectCountAsync(title: String, artist: String) {
        val ctx = hostAppContext ?: return
        if (title.isBlank()) return
        timelineExecutor.execute {
            runCatching {
                val extras = Bundle().apply {
                    putString(Const.EXTRA_TITLE, title)
                    putString(Const.EXTRA_ARTIST, artist)
                }
                ctx.contentResolver.call(
                    Uri.parse(Const.LYRIC_PROVIDER_URI),
                    "clear_reject_count",
                    null,
                    extras
                )
            }
        }
    }

    // ==================== 宿主私有 SharedPreferences 200 条 LRU 持久化 ====================

    private fun loadLearnedCacheFromHostPrefs(ctx: Context) {
        runCatching {
            val sp = ctx.getSharedPreferences(Const.INTRO_LEARN_PREF_NAME, Context.MODE_PRIVATE)
            val raw = sp.getString(Const.INTRO_LEARN_PREF_KEY, null)
            var loadedIntro = 0
            if (!raw.isNullOrBlank()) {
                synchronized(learnedFirstLineStartMap) {
                    for (line in raw.split('\n')) {
                        if (line.isBlank()) continue
                        val sep = line.indexOf('=')
                        if (sep <= 0 || sep >= line.length - 1) continue
                        val key = URLDecoder.decode(line.substring(0, sep), "UTF-8")
                        val value = line.substring(sep + 1).toLongOrNull() ?: continue
                        if (key.isNotEmpty() && value > 0L) {
                            learnedFirstLineStartMap[key] = value
                            loadedIntro++
                        }
                    }
                }
            }

            val rawInterludes = sp.getString(Const.INTRO_LEARN_INTERLUDES_KEY, null)
            var loadedInterludeTracks = 0
            if (!rawInterludes.isNullOrBlank()) {
                synchronized(learnedInterludesMap) {
                    for (line in rawInterludes.split('\n')) {
                        if (line.isBlank()) continue
                        val sep = line.indexOf('=')
                        if (sep <= 0 || sep >= line.length - 1) continue
                        val key = URLDecoder.decode(line.substring(0, sep), "UTF-8")
                        val spansPart = line.substring(sep + 1)
                        val spans = ArrayList<InterludeSpan>(4)
                        for (token in spansPart.split(';')) {
                            val parts = token.split(',')
                            if (parts.size != 2) continue
                            val s = parts[0].toLongOrNull() ?: continue
                            val e = parts[1].toLongOrNull() ?: continue
                            if (s >= 0L && e - s >= Const.INTERLUDE_MIN_THRESHOLD_MS) {
                                spans.add(InterludeSpan(s, e))
                            }
                        }
                        if (key.isNotEmpty() && spans.isNotEmpty()) {
                            learnedInterludesMap[key] = spans
                            loadedInterludeTracks++
                        }
                    }
                }
            }

            if (loadedIntro > 0 || loadedInterludeTracks > 0) {
                YLog.info(
                    msg = "[INTRO_CACHE_LOAD] 已从宿主私有配置载入 前奏=$loadedIntro 条 | 间奏曲目=$loadedInterludeTracks 首"
                )
            }
        }
    }

    private fun saveLearnedCacheToHostPrefs() {
        val ctx = hostAppContext ?: return
        runCatching {
            val serializedIntro = synchronized(learnedFirstLineStartMap) {
                val sb = StringBuilder(learnedFirstLineStartMap.size * 32)
                for ((k, v) in learnedFirstLineStartMap) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(URLEncoder.encode(k, "UTF-8")).append('=').append(v)
                }
                sb.toString()
            }
            val serializedInterludes = synchronized(learnedInterludesMap) {
                val sb = StringBuilder(learnedInterludesMap.size * 48)
                for ((k, spans) in learnedInterludesMap) {
                    if (spans.isEmpty()) continue
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(URLEncoder.encode(k, "UTF-8")).append('=')
                    for (i in spans.indices) {
                        if (i > 0) sb.append(';')
                        sb.append(spans[i].startMs).append(',').append(spans[i].endMs)
                    }
                }
                sb.toString()
            }
            ctx.getSharedPreferences(Const.INTRO_LEARN_PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(Const.INTRO_LEARN_PREF_KEY, serializedIntro)
                .putString(Const.INTRO_LEARN_INTERLUDES_KEY, serializedInterludes)
                .apply()
        }
    }

    private fun updateDurationFromMetadata(metadata: MediaMetadata?) {
        val dur = runCatching {
            metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
        }.getOrDefault(-1L)
        if (dur > 0L) {
            currentTrackDurationMs = dur
        }
    }

    private fun emitTrackInfo(displayTitle: String, artist: String) {
        if (displayTitle.isBlank()) {
            listener.onTrackInfo("", "")
        } else {
            listener.onTrackInfo(displayTitle, artist)
        }
    }

    private fun emitCoverPalette(fp: String, metadata: MediaMetadata?) {
        if (!readPrefs().glowEnabled) return
        val cached = synchronized(paletteCache) { paletteCache[fp] }
        if (cached != null) {
            listener.onCoverPalette(cached)
            return
        }
        val bmp = runCatching {
            metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        }.getOrNull()
        if (bmp != null) {
            val colors = CapsuleGlowRenderer.extractPalette(bmp)
            synchronized(paletteCache) { paletteCache[fp] = colors }
            listener.onCoverPalette(colors)
        } else {
            listener.onCoverPalette(Const.GLOW_SILVER_PALETTE.copyOf())
        }
    }

    private fun pullSessions(msm: MediaSessionManager, trigger: String) {
        runCatching {
            val list = msm.getActiveSessions(null)
            syncActiveSessions(list, trigger)
        }.onFailure { err ->
            YLog.error(
                msg = "[ISLAND_MSM_FAIL] getActiveSessions(null) 失败 ($trigger): ${err.javaClass.name}: ${err.message}",
                e = err
            )
        }
    }

    private fun syncActiveSessions(controllers: List<MediaController>, trigger: String) {
        var anyOtherPlaying = false
        val activeKugouTokens = HashSet<MediaSession.Token>()

        for (c in controllers) {
            if (c.packageName == Const.PKG_KUGOU) {
                activeKugouTokens.add(c.sessionToken)
                bindKugouController(c)
            } else {
                val st = c.playbackState?.state ?: PlaybackState.STATE_NONE
                if (st == PlaybackState.STATE_PLAYING) {
                    anyOtherPlaying = true
                }
            }
        }
        otherPlayerPlaying = anyOtherPlaying
        if (!anyOtherPlaying) {
            cancelOtherPlayingEndConfirm()
        }

        var removed = 0
        val it = boundKugouSessions.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (!activeKugouTokens.contains(entry.key)) {
                runCatching { entry.value.controller.unregisterCallback(entry.value.callback) }
                it.remove()
                removed++
            }
        }

        val slMsg = "[SESSION_LIFE] syncActiveSessions trigger=$trigger | total=${controllers.size} | " +
            "kugou=${boundKugouSessions.size} | removed=$removed | otherPlaying=$anyOtherPlaying | " +
            "state=$kugouPlaybackState | pos=${estimateCurrentPositionMs()} | posSource=$anchorSource"
        YLog.info(msg = slMsg)
        EventHistoryStore.recordDiagOnly(slMsg)

        if (boundKugouSessions.isEmpty()) {
            resetAndEnd("no_kugou_session:$trigger")
        } else if (otherPlayerPlaying && kugouPlaybackState != PlaybackState.STATE_PLAYING) {
            // v1.3.18：别家瞬时出声走 2000ms 防抖确认，先通知普通暂停且保留前奏/曲目上下文
            pauseLineClock()
            cancelInterludeCheck()
            cancelTimelineInterludeCheck()
            cancelIntroSafetyTimeout()
            cancelIntroStateLogger()
            listener.onPlaybackPlayingChanged(false)
            scheduleOtherPlayingEndConfirm("yield_to_other_player")
        }
    }

    private fun bindKugouController(controller: MediaController) {
        val token = controller.sessionToken
        if (boundKugouSessions.containsKey(token)) return
        if (!versionAllowed) return

        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                metadataBreaker.execute {
                    if (!readPrefs().masterEnabled || !versionAllowed) {
                        listener.onSessionEnded("disabled")
                        return@execute
                    }
                    if (metadata == null) return@execute
                    handleMetadata(metadata)
                }
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                playbackBreaker.execute {
                    if (!readPrefs().masterEnabled || !versionAllowed) {
                        listener.onSessionEnded("disabled")
                        return@execute
                    }
                    handlePlaybackState(state)
                }
            }

            override fun onSessionDestroyed() {
                runCatching {
                    boundKugouSessions.remove(token)
                    val sdMsg = "[SESSION_LIFE] onSessionDestroyed | remainingKugou=${boundKugouSessions.size} | fp=$currentTrackFp"
                    YLog.info(msg = sdMsg)
                    EventHistoryStore.record(sdMsg)
                    if (boundKugouSessions.isEmpty()) {
                        resetAndEnd("session_destroyed")
                    }
                }
            }
        }

        runCatching {
            controller.registerCallback(callback, mainHandler)
            boundKugouSessions[token] = BoundSession(controller, callback)
            YLog.info(msg = "[ISLAND_MC_ATTACH] 已绑定酷狗会话 (${runCatching { controller.tag }.getOrDefault("?")})")

            controller.playbackState?.let { handlePlaybackState(it) }
            controller.metadata?.let { handleMetadata(it) }
        }.onFailure { err ->
            YLog.error(msg = "[ISLAND_MC_ATTACH_FAIL] 绑定酷狗会话失败: ${err.message}", e = err)
        }
    }

    private fun handlePlaybackState(state: PlaybackState?) {
        val prevState = kugouPlaybackState
        val code = state?.state ?: PlaybackState.STATE_NONE
        // v1.4.5-fix16：第一次进入播放时消费被推迟的 init_2s 同步
        if (code == PlaybackState.STATE_PLAYING && prevState != PlaybackState.STATE_PLAYING && deferredInitPull) {
            deferredInitPull = false
            mediaSessionManager?.let { pullSessions(it, "init_deferred_play") }
        }
        val rawPos = state?.position ?: -1L
        val speed = state?.playbackSpeed ?: 1f
        val stateUpdateTime = state?.lastPositionUpdateTime ?: 0L
        val nowElapsed = SystemClock.elapsedRealtime()

        if (rawPos >= 0L) {
            val prevSource = anchorSource
            val prevEstPos = estimateCurrentPositionMs()
            anchorPosMs = rawPos
            anchorElapsedMs = if (stateUpdateTime > 0L) stateUpdateTime else nowElapsed
            anchorSpeed = if (speed > 0f) speed else 1f
            anchorSource = "state"
            if (trackAttachedInitialPosMs < 0L) {
                trackAttachedInitialPosMs = rawPos
            }
            handleAnchorUpgradedToState(prevSource, prevEstPos, rawPos)
        } else if (code == PlaybackState.STATE_PAUSED || code == PlaybackState.STATE_BUFFERING) {
            freezeAnchorAtNow()
        } else if (code == PlaybackState.STATE_PLAYING && prevState != PlaybackState.STATE_PLAYING) {
            if (anchorPosMs >= 0L) {
                anchorElapsedMs = nowElapsed
                anchorSpeed = if (speed > 0f) speed else 1f
                if (anchorSource == "state") anchorSource = "extrap"
            } else {
                if (!reanchorFromControllers()) {
                    ensureVirtualAnchorIfMissing()
                }
            }
        }

        val nowUptime = SystemClock.uptimeMillis()
        if (code == PlaybackState.STATE_BUFFERING || rawPos < 0L) {
            sawBufferingOrInvalidPos = true
        }
        // F5 + M1：出现 rawPos == -1 时冻结武装与对账迁移，并挂单次 postDelayed 到期补偿；
        // 若新歌元数据刚刚确认（<=1200ms 且尚未唱出正歌），不误冻新曲前奏武装
        val freshlyConfirmedNewTrack = trackConfirmedUptimeMs > 0L &&
            (nowUptime - trackConfirmedUptimeMs) <= 1200L &&
            !hasRealVocalLineInTrack
        if (rawPos < 0L && currentTrackFp.isNotEmpty() && code != PlaybackState.STATE_NONE && !freshlyConfirmedNewTrack) {
            armTrackSwitchFreeze(currentTrackFp, "invalid_pos_state_$code")
        }
        val prevRawPos = lastObservedRawPosMs
        val prevPosElapsed = lastObservedRawPosElapsedMs
        val curPosElapsed = if (stateUpdateTime > 0L) stateUpdateTime else nowElapsed
        // S1：BUFFERING 回调携带的位置不覆写 lastObservedRawPosMs，保留缓冲前位置以便 3->6->3 / 2->6->2 判出跳变
        if (rawPos >= 0L && code != PlaybackState.STATE_BUFFERING) {
            lastObservedRawPosMs = rawPos
            lastObservedRawPosElapsedMs = curPosElapsed
            lastObservedRawPosUptimeMs = nowUptime
        }
        val observedRewindTransition = prevRawPos > Const.POS_REWIND_FROM_MS &&
            rawPos in 0L until Const.POS_REWIND_TO_MS &&
            currentTrackFp.isNotEmpty() &&
            (nowUptime - lastIntroEnterUptimeMs) > Const.INTRO_REENTER_DEBOUNCE_MS
        if (observedRewindTransition) {
            pendingSameTrackRewind = true
        }
        val isSameTrackRewind = code == PlaybackState.STATE_PLAYING &&
            (observedRewindTransition || pendingSameTrackRewind) &&
            currentTrackFp.isNotEmpty()
        // F2：driftMs 基准改为 PlaybackState.lastPositionUpdateTime（elapsedRealtime）外推，不再使用回调墙钟
        val deltaPosTimeMs = if (prevPosElapsed > 0L) (curPosElapsed - prevPosElapsed).coerceAtLeast(0L) else -1L
        val deltaPosMs = if (prevRawPos >= 0L && rawPos >= 0L) rawPos - prevRawPos else 0L
        val effSpeed = if (speed > 0f) speed else 1f
        val expectedAdvanceMs = if (deltaPosTimeMs >= 0L) (deltaPosTimeMs * effSpeed).toLong() else 0L
        val driftMs = if (deltaPosTimeMs >= 0L) deltaPosMs - expectedAdvanceMs else 0L // N1：无时间基准时 drift 回退 0L，不许误判跳变（仓侧直修，勿回退）
        val isPlayingSeekJump = code == PlaybackState.STATE_PLAYING &&
            prevState == PlaybackState.STATE_PLAYING &&
            prevRawPos >= 0L && rawPos >= 0L &&
            abs(driftMs) >= 2500L
        val isPauseResumeJump = code == PlaybackState.STATE_PLAYING &&
            prevState != PlaybackState.STATE_PLAYING &&
            prevRawPos >= 0L && rawPos >= 0L &&
            abs(deltaPosMs) >= 2500L
        // F2：收紧 state_jump——仅 3->2(按 driftMs) 或 2->2(按 deltaPosMs) 判跳变，彻底消除 2->3 普通暂停恢复误报
        val isPlayingToPausedSeek = prevState == PlaybackState.STATE_PLAYING &&
            code == PlaybackState.STATE_PAUSED &&
            prevRawPos >= 0L && rawPos >= 0L &&
            abs(driftMs) >= 2500L
        val isPausedScrubJump = prevState == PlaybackState.STATE_PAUSED &&
            code == PlaybackState.STATE_PAUSED &&
            prevRawPos >= 0L && rawPos >= 0L &&
            abs(deltaPosMs) >= 2500L
        val isStateJump = !isPlayingSeekJump && !isPauseResumeJump && (isPlayingToPausedSeek || isPausedScrubJump)
        val isJumpEvent = isPlayingSeekJump || isPauseResumeJump || isStateJump
        if (isJumpEvent) {
            val jumpKind = when {
                isPlayingSeekJump -> "seek"
                isPauseResumeJump -> "pause_resume"
                else -> "state_jump"
            }
            val seekMsg = "[SEEK_DIAG] kind=$jumpKind rawPos=$rawPos prevRawPos=$prevRawPos deltaPos=$deltaPosMs deltaTimeMs=$deltaPosTimeMs driftMs=$driftMs fp=$currentTrackFp"
            YLog.info(msg = seekMsg)
            EventHistoryStore.recordDiagOnly(seekMsg)
            gateJumpPosMs = rawPos
            lastJumpEventUptimeMs = nowUptime
            YLog.info(msg = "[GATE_JUMP_MARK] set jumpPosMs=$gateJumpPosMs reason=$jumpKind fp=$currentTrackFp")
            skipNextInterludeLearnAfterJump = true
            skipNextFirstLineLearnAfterJump = true
            if (lastBluetoothLyricPosMs >= 0L && abs(rawPos - lastBluetoothLyricPosMs) >= 2500L) {
                lastBluetoothLyricPosMs = -1L
                lastBluetoothLyricUptimeMs = 0L
            }
            if (timelineGateState == GateState.PENDING) {
                resetGateCountersOnly()
                YLog.info(msg = "[TIMELINE_GATE] RESET reason=${jumpKind}_jump rawPos=$rawPos prevRawPos=$prevRawPos")
            }
        } else if (code == PlaybackState.STATE_PLAYING && prevState != PlaybackState.STATE_PLAYING && hasRealVocalLineInTrack) {
            // 暂停恢复时的半句续播，按位置记录 gateJumpPosMs（G4）
            val resumePos = if (rawPos >= 0L) rawPos else estimateCurrentPositionMs()
            if (resumePos >= 0L) {
                gateJumpPosMs = resumePos
                YLog.info(msg = "[GATE_JUMP_MARK] set jumpPosMs=$gateJumpPosMs reason=pause_resume_inline fp=$currentTrackFp")
            }
            skipNextInterludeLearnAfterJump = true
        }

        kugouPlaybackState = code
        val effectivePos = estimateCurrentPositionMs()
        if (code != prevState) {
            val pbMsg = "[SESSION_LIFE] playbackState $prevState -> $code | rawPos=$rawPos | estPos=$effectivePos | " +
                "posSource=$anchorSource | fp=$currentTrackFp | bound=${boundKugouSessions.size}"
            YLog.info(msg = pbMsg)
            EventHistoryStore.recordDiagOnly(pbMsg)
        }
        fingerprintV2.onPlaybackPosition(effectivePos)

        when (code) {
            PlaybackState.STATE_PLAYING -> {
                cancelOtherPlayingEndConfirm()
                val hadBufferingGuard = sawBufferingOrInvalidPos
                if (rawPos >= 0L) {
                    sawBufferingOrInvalidPos = false
                }
                resumeLineClock()
                listener.onPlaybackPlayingChanged(true)
                if (currentTrackDisplayTitle.isNotBlank()) {
                    listener.onTrackInfo(currentTrackDisplayTitle, currentTrackArtist)
                }

                // F5 + M1：切歌冻结期不吞事件，登记解冻补偿并保留 pendingSameTrackRewind 到解冻后消费
                if (isTrackSwitchFrozen()) {
                    pendingPostFreezeReplay = true
                    return
                }

                if (isSameTrackRewind) {
                    // v1.4.5-fix8 F2：拖回 0 后 80ms 内起播帧若命中同曲、≤300ms、rawPos 差 ≤100ms 的 seek_back_to_intro/seek_in_intro，不覆写 skipWaveLead、不闪波浪，同时补齐清场与解冻倒计时
                    val nowForSeekGuard = SystemClock.uptimeMillis()
                    val skipRewindOverwrite = inIntro &&
                        activeIntroTargetMs > 0L &&
                        lastSeekIntroArmUptimeMs > 0L &&
                        (nowForSeekGuard - lastSeekIntroArmUptimeMs) <= 300L &&
                        currentTrackFp.isNotEmpty() &&
                        currentTrackFp == lastSeekIntroArmFp &&
                        rawPos >= 0L &&
                        lastSeekIntroArmRawPos >= 0L &&
                        abs(rawPos - lastSeekIntroArmRawPos) <= 100L
                    if (skipRewindOverwrite) {
                        pendingSameTrackRewind = false
                        lastBluetoothLyricUptimeMs = 0L
                        lastBluetoothLyricPosMs = -1L
                        cancelPendingRewindArm()
                        restartLineClock(isPlaying = true)
                        scheduleIntroTargetExpiry()
                        scheduleIntroSafetyTimeout()
                        scheduleIntroStateLogger()
                        scheduleTimelineLyricFallback(trigger = "seek_intro_resume", immediate = false)
                        return
                    }
                    pendingSameTrackRewind = false
                    inInterlude = true
                    activeInterludeTargetMs = -1L
                    outroClearedInTrack = false
                    inIntro = false
                    activeIntroTargetMs = -1L
                    activeIntroSkipWaveLead = false
                    activeIntroForceWaveOnly = false
                    hasRealVocalLineInTrack = false
                    lastMatchedVocalIndex = -1
                    lastDeliveredLyric = ""
                    lastDeliveredNextLyric = ""
                    lastDeliveredWordSpans = emptyList()
                    lastBluetoothLyricUptimeMs = 0L
                    lastBluetoothLyricPosMs = -1L
                    lastTimelineFallbackIndex = -1
                    timelineFallbackActive = false
                    cancelStaleTimersOnMigration()
                    cancelIntroTargetExpiry()
                    cancelIntroSafetyTimeout()
                    cancelIntroStateLogger()
                    restartLineClock(isPlaying = true)
                    listener.onInterludeChanged(true, -1L)
                    if (hadBufferingGuard) {
                        scheduleDeferredRewindArm(currentTrackFp)
                    } else {
                        cancelPendingRewindArm()
                        tryArmIntroIfEligible(reason = "same_track_rewind")
                    }
                    return
                }

                // F1：位置驱动的统一区间对账（覆盖 GAP_LONG / GAP_SHORT / LINE / OUTRO 及 INTRO 边界）
                val reconciledBySegment = if (effectivePos >= 0L) {
                    reconcileSegment(
                        posMs = effectivePos,
                        reason = if (isJumpEvent) "play_jump" else "state_playing",
                        isPlayingNow = true,
                        isJumpEvent = isJumpEvent
                    )
                } else {
                    false
                }

                if (!reconciledBySegment) {
                    if (!inIntro && effectivePos >= 0L && (hasRealVocalLineInTrack || lastDeliveredLyric.isNotBlank() || inInterlude) &&
                        resolveOwnedFirstVocalMs().let { fv -> fv >= Const.INTRO_MIN_VALID_START_MS && effectivePos < fv - Const.TIMELINE_INTRO_GUARD_MS }
                    ) {
                        val wasInInterludeBeforeSeekBack = inInterlude
                        hasRealVocalLineInTrack = false
                        inInterlude = false
                        activeInterludeTargetMs = -1L
                        if (wasInInterludeBeforeSeekBack) {
                            listener.onInterludeChanged(false, -1L)
                        }
                        lastDeliveredLyric = ""
                        lastDeliveredNextLyric = ""
                        lastDeliveredWordSpans = emptyList()
                        lastMatchedVocalIndex = -1
                        lastTimelineFallbackIndex = -1
                        timelineFallbackActive = false
                        currentLyricLineSeq++
                        restartLineClock(isPlaying = false)
                        cancelStaleTimersOnMigration()
                        val armedIntro = tryArmIntroIfEligible(reason = "seek_back_to_intro", skipWaveLead = false) // fix13 B：按落点走波浪，不再写死跳过
                        if (!armedIntro) {
                            listener.onLyricLine("", true, currentEstimatedWindowMs(), currentEstimatedWindowMs(), emptyList(), currentLyricLineSeq, "")
                        }
                    } else if (!hasRealVocalLineInTrack && !inIntro && effectivePos >= 0L) {
                        if (hadBufferingGuard) {
                            scheduleDeferredRewindArm(currentTrackFp)
                        } else {
                            tryArmIntroIfEligible(reason = if (rawPos in 0L..5000L) "pos_in_intro" else "resume_in_intro")
                        }
                    } else if (inIntro) {
                        val expiryMargin = if (isJumpEvent) 0L else if (activeTimeline != null) 350L else 1200L
                        if (activeIntroTargetMs > 0L && effectivePos >= activeIntroTargetMs + expiryMargin) {
                            exitIntroIfActive("seek_past_intro")
                        } else if (isJumpEvent &&
                            activeIntroTargetMs > 0L &&
                            effectivePos >= 0L &&
                            effectivePos < activeIntroTargetMs - Const.TIMELINE_INTRO_GUARD_MS
                        ) {
                            tryArmIntroIfEligible(reason = "seek_in_intro", skipWaveLead = false) // fix13 B：同 seek_back_to_intro
                        } else {
                            if (activeIntroTargetMs > 0L) {
                                listener.onIntroChanged(
                                    inIntro = true,
                                    learnedFirstLineMs = activeIntroTargetMs,
                                    skipWaveLead = activeIntroSkipWaveLead,
                                    forceWaveOnly = activeIntroForceWaveOnly
                                )
                            }
                            scheduleIntroTargetExpiry()
                            scheduleIntroSafetyTimeout()
                            scheduleIntroStateLogger()
                        }
                    }

                    val upgradedOnPlay = if (prevState != PlaybackState.STATE_PLAYING && !isJumpEvent) {
                        tryUpgradeCurrentEstimateLineWithTimeline()
                    } else {
                        false
                    }
                    if (!upgradedOnPlay && lastDeliveredLyric.isNotBlank() && !inInterlude && !inIntro) {
                        listener.onLyricLine(
                            lyric = lastDeliveredLyric,
                            isPlaying = true,
                            estimatedWindowMs = currentEstimatedWindowMs(),
                            karaokeDurationMs = lastDeliveredKaraokeMs,
                            wordSpans = lastDeliveredWordSpans,
                            lineSeq = currentLyricLineSeq,
                            nextLyric = lastDeliveredNextLyric
                        )
                    }
                    val allowImmediateFb = timelineGateState != GateState.PENDING
                    scheduleTimelineLyricFallback(
                        trigger = if (isJumpEvent) "seek_jump" else "state_playing",
                        immediate = allowImmediateFb && (isJumpEvent || (!inIntro && lastDeliveredLyric.isBlank()))
                    )
                    if (!timelineFallbackActive) {
                        scheduleInterludeCheckIfNeeded()
                    }
                }
            }
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> {
                // v1.4.1：切歌/缓冲过渡态仅暂停行时钟与定时器，不触发暂停拆宽，保住宽壳无缝衔接
                pauseLineClock()
                cancelInterludeCheck()
                cancelTimelineInterludeCheck()
                cancelTimelineLyricFallback()
                cancelPendingRewindArm()
                cancelIntroTargetExpiry()
                cancelIntroSafetyTimeout()
                cancelIntroStateLogger()
            }
            PlaybackState.STATE_PAUSED -> {
                pauseLineClock()
                cancelInterludeCheck()
                cancelTimelineInterludeCheck()
                cancelTimelineLyricFallback()
                cancelPendingRewindArm()
                cancelIntroTargetExpiry()
                cancelIntroSafetyTimeout()
                cancelIntroStateLogger()
                listener.onPlaybackPlayingChanged(false)
                // F1.3：在第一条带新位置的 PAUSED 回调（3->2 或 2->2）当帧完成区间对账并冻结在目标显示上，不等 2->3
                if (rawPos >= 0L) {
                    if (isTrackSwitchFrozen()) {
                        pendingPostFreezeReplay = true
                    } else {
                        reconcileSegment(
                            posMs = effectivePos,
                            reason = if (isJumpEvent) "paused_seek" else "state_paused",
                            isPlayingNow = false,
                            isJumpEvent = isJumpEvent
                        )
                    }
                }
                if (otherPlayerPlaying) {
                    // v1.3.18：paused_and_other_playing 改 2000ms 防抖，期间保留前奏与曲目状态，避免瞬时抢占误拆建
                    scheduleOtherPlayingEndConfirm("paused_and_other_playing")
                } else {
                    cancelOtherPlayingEndConfirm()
                }
            }
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_NONE,
            PlaybackState.STATE_ERROR -> {
                resetAndEnd("state=$code")
            }
        }
    }

    private fun handleMetadata(metadata: MediaMetadata) {
        if (!isKugouActive()) return

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        if (title.isEmpty()) return
        updateDurationFromMetadata(metadata)

        activeEvalMetadata = metadata
        val decision = try {
            fingerprintV2.evaluate(metadata, title)
        } finally {
            activeEvalMetadata = null
        }

        if (decision == TrackFingerprintV2.Decision.SAME_TRACK) {
            // v1.2.8：同专辑相邻曲目显式歌名变化检测（零改动冻结的 TrackFingerprintV2.kt）
            val rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
            val (explicitSong, isExplicit) = TrackFingerprintV2.extractDisplayTitle(metadata, rawArtist, title)
            if (isExplicit &&
                explicitSong.isNotBlank() &&
                currentTrackDisplayTitle.isNotBlank() &&
                explicitSong != currentTrackDisplayTitle
            ) {
                val baseFp = currentTrackFp.substringBefore("#")
                val newFp = "$baseFp#$explicitSong"
                val normArtist = TrackFingerprintV2.normalizeArtist(rawArtist, explicitSong)
                    .ifEmpty { currentTrackArtist }
                val swMsg = "[SESSION_LIFE] same_album_switch ${LogFormat.preview(currentTrackDisplayTitle, 24)} -> ${LogFormat.preview(explicitSong, 24)} | fp=$newFp"
                YLog.info(msg = swMsg)
                EventHistoryStore.record(swMsg)
                EventHistoryStore.replayRecentThrottled("same_album_switch")
                listener.onTrackConfirmedForDiag()
                resetTrackTimingState(newFp, explicitSong, normArtist)
                emitTrackInfo(explicitSong, normArtist)
                emitCoverPalette(newFp, metadata)
                val isPlayingNow = kugouPlaybackState == PlaybackState.STATE_PLAYING
                val titleIsNonVocal = LrcTimelineParser.isCreditOrNonVocalLine(
                    title,
                    explicitSong,
                    normArtist,
                    withinTrackIntroWindow = true
                )
                if (titleIsNonVocal) {
                    lastDeliveredLyric = ""
                    lastDeliveredNextLyric = ""
                    currentLineStartPosMs = estimateCurrentPositionMs()
                    restartLineClock(isPlayingNow)
                    listener.onTrackSwitched("", isPlayingNow, currentEstimatedWindowMs())
                    tryArmIntroIfEligible(reason = "same_album_switch")
                    requestOnlineTimelineIfEnabled(newFp, explicitSong, normArtist, currentTrackDurationMs)
                    scheduleInterludeCheckIfNeeded()
                    return
                } else {
                    lastDeliveredLyric = ""
                    lastDeliveredNextLyric = ""
                    listener.onTrackSwitched("", isPlayingNow, currentEstimatedWindowMs())
                    requestOnlineTimelineIfEnabled(newFp, explicitSong, normArtist, currentTrackDurationMs)
                }
            }

            if (title == lastDeliveredLyric) {
                val curPosCheck = estimateCurrentPositionMs()
                val isNewRepeatedLine = isGenuineConsecutiveIdenticalLine(title, curPosCheck)
                if (!isNewRepeatedLine) {
                    if (timelineFallbackActive) {
                        lastBluetoothLyricUptimeMs = SystemClock.uptimeMillis()
                        lastBluetoothLyricPosMs = curPosCheck
                        timelineFallbackActive = false
                        scheduleTimelineLyricFallback(trigger = "bt_reclaim_same", immediate = false)
                    }
                    return
                }
            }

            // v1.4.5-fix6 护栏 2/3/4/5/6：所有窗口一律按 curPos 判（删尽挂钟），且拦截 return 位于 skipNextFirstLineLearnAfterJump 之前、零副作用
            val curPosForFilter = estimateCurrentPositionMs()
            val ownedTlForGuard = activeTimeline?.takeIf {
                activeTimelineOwnerFp == currentTrackFp &&
                    timelineGateState != GateState.FAILED &&
                    !it.isInstrumental &&
                    it.allLines.isNotEmpty()
            }
            val isPassedGate = timelineGateState == GateState.PASSED
            val isPassedIntroSeg = isPassedGate &&
                ownedTlForGuard != null &&
                ownedTlForGuard.vocalLines.isNotEmpty() &&
                curPosForFilter >= 0L &&
                segmentAt(curPosForFilter, ownedTlForGuard) is TrackSegment.Intro
            // 护栏 4：PASSED 以位置处于 TrackSegment.Intro 为准，PENDING 首播沿用 !hasRealVocalLineInTrack
            val inUnvocalPhase = if (isPassedGate) isPassedIntroSeg else !hasRealVocalLineInTrack
            // 护栏 6：歌名紧窗口与演职员宽窗口均仅按 curPos 判定，杜绝切歌秒拖副歌被挂钟误杀
            val withinTitleWindow = inUnvocalPhase && curPosForFilter in 0L..4_000L
            val withinColonCreditWindow = (inIntro || inUnvocalPhase) && curPosForFilter in 0L..45_000L
            val isNonVocal = LrcTimelineParser.isCreditOrNonVocalLine(
                title,
                currentTrackDisplayTitle,
                currentTrackArtist,
                withinTrackIntroWindow = withinTitleWindow,
                withinColonCreditWindow = withinColonCreditWindow
            )
            if (isNonVocal) {
                return
            }
            // v1.4.5-fix6 层 3 + 护栏 1/4/5：时间轴 isCredit 主防线（PENDING/PASSED 均覆盖；curPos<0 只记不拦；PENDING 未匹配行只记 [INTRO_HOLD_UNMATCHED] 不拦）
            if (ownedTlForGuard != null &&
                ownedTlForGuard.firstVocalMs >= 0L &&
                inUnvocalPhase &&
                anchorSource == "state" &&
                !isTrackSwitchFrozen()
            ) {
                if (curPosForFilter < 0L) {
                    val skipMsg = "[BT_LYRIC_DROP_SKIP] fp=$currentTrackFp reason=no_pos_layer3 gate=$timelineGateState line=${LogFormat.preview(title, 24)}"
                    YLog.info(msg = skipMsg)
                    EventHistoryStore.recordDiagOnly(skipMsg)
                } else {
                    val effectiveOffset = if (isPassedGate) trackTimeOffsetMs else 0L
                    val introBoundaryMs = if (isPassedGate) {
                        ownedTlForGuard.firstVocalMs + effectiveOffset - Const.TIMELINE_INTRO_GUARD_MS
                    } else {
                        ownedTlForGuard.firstVocalMs - 3_000L
                    }
                    if (curPosForFilter < introBoundaryMs) {
                        if (LrcTimelineParser.isMarkedCreditInTimeline(
                                ownedTlForGuard,
                                title,
                                curPosForFilter,
                                effectiveOffset
                            )
                        ) {
                            val dropMsg = "[BT_LYRIC_DROP] fp=$currentTrackFp reason=timeline_credit gate=$timelineGateState pos=$curPosForFilter boundary=$introBoundaryMs line=${LogFormat.preview(title, 24)}"
                            YLog.info(msg = dropMsg)
                            EventHistoryStore.recordDiagOnly(dropMsg)
                            return
                        }
                        if (!isPassedGate &&
                            ownedTlForGuard.vocalLines.isNotEmpty() &&
                            findBestMatchingVocalIndex(ownedTlForGuard, title, curPosForFilter) < 0
                        ) {
                            val holdMsg = "[INTRO_HOLD_UNMATCHED] fp=$currentTrackFp pos=$curPosForFilter firstVocal=${ownedTlForGuard.firstVocalMs} line=${LogFormat.preview(title, 24)}"
                            YLog.info(msg = holdMsg)
                            EventHistoryStore.recordDiagOnly(holdMsg)
                        }
                    }
                }
            }

            ensureVirtualAnchorIfMissing()
            val curPos = estimateCurrentPositionMs()
            val isPlayingNow = kugouPlaybackState == PlaybackState.STATE_PLAYING
            val nearTrackEnd = currentTrackDurationMs > 0L && curPos >= currentTrackDurationMs - 2500L
            // v1.4.1：尾奏已清词或曲末暂停态直接拦截播放器补发的陈旧歌词，杜绝切曲/循环首帧闪词
            if (outroClearedInTrack) {
                if (isPlayingNow && !nearTrackEnd && !isInTrackOutroRegion(curPos)) {
                    outroClearedInTrack = false
                } else {
                    return
                }
            } else if (!isPlayingNow && nearTrackEnd) {
                return
            }
            val hadPreviousVocal = hasRealVocalLineInTrack && lastDeliveredLyric.isNotEmpty()
            val prevDurationMs = accumulatedLinePlayMs()
            val prevLineStartPos = currentLineStartPosMs

            // F1.4：有可信归属时间轴（PASSED）时以区间对账为准，与当前区间对不上的迟到蓝牙推送一律丢弃
            val passedTlForArb = activeTimeline?.takeIf {
                activeTimelineOwnerFp == currentTrackFp &&
                    timelineGateState == GateState.PASSED &&
                    !it.isInstrumental &&
                    it.vocalLines.isNotEmpty()
            }
            if (passedTlForArb != null && curPos >= 0L && !isTrackSwitchFrozen()) {
                when (val seg = segmentAt(curPos, passedTlForArb)) {
                    is TrackSegment.Intro -> {
                        if (anchorSource == "state" && curPos < seg.firstVocalMs - Const.TIMELINE_INTRO_GUARD_MS) {
                            val btIdx = findBestMatchingVocalIndex(passedTlForArb, title, curPos)
                            if (btIdx != 0) {
                                if (btIdx > 0) {
                                    val dropMsg = "[INTRO_DROP_STALE] fp=$currentTrackFp idx=$btIdx pos=$curPos firstVocal=${seg.firstVocalMs} line=${LogFormat.preview(title, 24)}"
                                    YLog.info(msg = dropMsg)
                                    EventHistoryStore.record(dropMsg)
                                } else {
                                    val nearestText = passedTlForArb.allLines
                                        .filter { !it.isEmptyMarker && it.text.isNotBlank() }
                                        .minByOrNull { abs((it.startMs + trackTimeOffsetMs) - curPos) }
                                        ?.text
                                        .orEmpty()
                                    val dropMsg = "[BT_LYRIC_DROP] fp=$currentTrackFp reason=intro_non_vocal pos=$curPos firstVocal=${seg.firstVocalMs} line=${LogFormat.preview(title, 24)} nearest=${LogFormat.preview(nearestText, 24)}"
                                    YLog.info(msg = dropMsg)
                                    EventHistoryStore.recordDiagOnly(dropMsg)
                                }
                                return
                            }
                        }
                    }
                    is TrackSegment.GapLong -> {
                        if (curPos < seg.targetNextPosMs - 1500L) {
                            val dropMsg = "[BT_LYRIC_DROP] fp=$currentTrackFp reason=in_gap_long pos=$curPos target=${seg.targetNextPosMs} line=${LogFormat.preview(title, 24)}"
                            YLog.info(msg = dropMsg)
                            EventHistoryStore.recordDiagOnly(dropMsg)
                            return
                        }
                    }
                    is TrackSegment.GapTail -> {
                        // N1：距下一行 <1s，仅放行与 nextIdx 匹配的蓝牙句，丢弃远行迟到推送
                        val btIdx = findBestMatchingVocalIndex(passedTlForArb, title, curPos)
                        if (btIdx >= 0 && btIdx != seg.nextIdx) {
                            val dropMsg = "[BT_LYRIC_DROP] fp=$currentTrackFp reason=gap_tail_mismatch btIdx=$btIdx nextIdx=${seg.nextIdx} pos=$curPos line=${LogFormat.preview(title, 24)}"
                            YLog.info(msg = dropMsg)
                            EventHistoryStore.recordDiagOnly(dropMsg)
                            return
                        }
                    }
                    is TrackSegment.Outro -> {
                        val dropMsg = "[BT_LYRIC_DROP] fp=$currentTrackFp reason=in_outro pos=$curPos line=${LogFormat.preview(title, 24)}"
                        YLog.info(msg = dropMsg)
                        EventHistoryStore.recordDiagOnly(dropMsg)
                        return
                    }
                    is TrackSegment.Line -> {
                        val btIdx = findBestMatchingVocalIndex(passedTlForArb, title, curPos)
                        if (btIdx >= 0 && abs(btIdx - seg.idx) >= 2) {
                            val dropMsg = "[BT_LYRIC_DROP] fp=$currentTrackFp reason=line_mismatch btIdx=$btIdx segIdx=${seg.idx} pos=$curPos line=${LogFormat.preview(title, 24)}"
                            YLog.info(msg = dropMsg)
                            EventHistoryStore.recordDiagOnly(dropMsg)
                            return
                        }
                    }
                    is TrackSegment.GapShort -> {
                        val btIdx = findBestMatchingVocalIndex(passedTlForArb, title, curPos)
                        if (btIdx >= 0 && abs(btIdx - seg.prevIdx) >= 2) {
                            val dropMsg = "[BT_LYRIC_DROP] fp=$currentTrackFp reason=gap_short_mismatch btIdx=$btIdx segIdx=${seg.prevIdx} pos=$curPos line=${LogFormat.preview(title, 24)}"
                            YLog.info(msg = dropMsg)
                            EventHistoryStore.recordDiagOnly(dropMsg)
                            return
                        }
                    }
                }
            }
            var shouldExitIntroAfterDeliver = false
            val skipFirstLineLearn = skipNextFirstLineLearnAfterJump
            skipNextFirstLineLearnAfterJump = false
            if (!hasRealVocalLineInTrack && currentTrackFp.isNotEmpty()) {
                if (!skipFirstLineLearn &&
                    !isIsolatedTrackFp(currentTrackFp) &&
                    anchorSource == "state" &&
                    trackAttachedInitialPosMs in 0L until 5_000L &&
                    timelineGateState != GateState.FAILED &&
                    curPos >= Const.INTRO_MIN_VALID_START_MS
                ) {
                    // X3：有可信归属时间轴时，要求观测首句匹配 idx==0 或距 firstVocal+offset <= 3s，防 PENDING 旧行冒充首句
                    val ownedTl = activeTimeline?.takeIf { activeTimelineOwnerFp == currentTrackFp }
                    val passesTimelineFirstCheck = if (ownedTl != null) {
                        val obsIdx = findBestMatchingVocalIndex(ownedTl, title, curPos)
                        obsIdx == 0 || abs(curPos - (ownedTl.firstVocalMs + trackTimeOffsetMs)) <= 3_000L
                    } else {
                        true
                    }
                    if (passesTimelineFirstCheck) {
                        if (firstObservedVocalPosInTrack < 0L) {
                            firstObservedVocalPosInTrack = curPos
                            val obsMsg = "[INTRO_OBSERVED] fp=$currentTrackFp pos=$curPos attachInit=$trackAttachedInitialPosMs"
                            YLog.info(msg = obsMsg)
                            EventHistoryStore.record(obsMsg)
                        }
                        recordOrFixLearnedFirstLinePosition(currentTrackFp, curPos)
                    }
                }
                shouldExitIntroAfterDeliver = inIntro
            }

            val wasInterlude = inInterlude
            val skipInterludeLearn = skipNextInterludeLearnAfterJump
            skipNextInterludeLearnAfterJump = false
            if (!skipInterludeLearn &&
                (wasInterlude || prevDurationMs >= Const.INTERLUDE_MIN_THRESHOLD_MS) &&
                hadPreviousVocal &&
                currentTrackFp.isNotEmpty() &&
                prevLineStartPos >= 0L &&
                curPos > prevLineStartPos + Const.INTERLUDE_MIN_THRESHOLD_MS
            ) {
                val estInterludeStart = prevLineStartPos + 3500L
                if (curPos - estInterludeStart >= Const.INTERLUDE_MIN_THRESHOLD_MS) {
                    recordLearnedInterludeSpan(currentTrackFp, estInterludeStart, curPos)
                }
            }

            if (hadPreviousVocal &&
                !inInterlude &&
                prevDurationMs >= Const.SCROLL_MIN_VALID_SAMPLE_MS &&
                prevDurationMs <= interludeThresholdMs()
            ) {
                recordLineDurationSample(prevDurationMs)
            }

            hasRealVocalLineInTrack = true
            inInterlude = false
            activeInterludeTargetMs = -1L
            cancelInterludeCheck()
            cancelTimelineInterludeCheck()

            var plannedScrollMs = currentEstimatedWindowMs()
            var plannedKaraokeMs = plannedScrollMs
            var plannedWords: List<LrcTimelineParser.WordSpan> = emptyList()
            var scrollSrc = "estimate"
            val timing = processLineAgainstTimeline(title, curPos)
            if (timing != null) {
                plannedScrollMs = timing.scrollDurationMs
                plannedKaraokeMs = timing.karaokeDurationMs
                plannedWords = timing.wordSpans
                scrollSrc = when {
                    timing.isSliced -> "krc_slice"
                    plannedWords.isNotEmpty() -> "krc"
                    else -> "timeline"
                }
            }

            lastBluetoothLyricUptimeMs = SystemClock.uptimeMillis()
            timelineFallbackActive = false
            currentLyricLineSeq++
            val seq = currentLyricLineSeq
            val nextLyricText = resolveNextLyricForIndex(if (timing != null) lastMatchedVocalIndex else -1, timing?.sliceRemainderText.orEmpty())
            lastDeliveredLyric = title
            lastDeliveredNextLyric = nextLyricText
            lastDeliveredWordSpans = plannedWords
            lastDeliveredKaraokeMs = plannedKaraokeMs
            currentLineStartPosMs = estimateCurrentPositionMs()
            lastBluetoothLyricPosMs = currentLineStartPosMs
            val isPlaying = kugouPlaybackState == PlaybackState.STATE_PLAYING
            restartLineClock(isPlaying)

            val tlStatus = when {
                activeTimeline != null && timelineGateState == GateState.PASSED -> "online"
                synchronized(learnedFirstLineStartMap) { learnedFirstLineStartMap.containsKey(currentTrackFp) } ||
                    synchronized(learnedInterludesMap) { learnedInterludesMap.containsKey(currentTrackFp) } -> "learned"
                else -> "none"
            }
            val diagMsg = "[SCROLL_DIAG] src=$scrollSrc lineSeq=$seq words=${plannedWords.size} tl=$tlStatus " +
                "planned=${plannedScrollMs}ms karaoke=${plannedKaraokeMs}ms " +
                "prevActualDelta=${if (hadPreviousVocal) prevDurationMs else -1L}ms " +
                "pos=${currentLineStartPosMs} posSource=$anchorSource line=${LogFormat.preview(title, 28)}"
            YLog.info(msg = diagMsg)
            EventHistoryStore.record(diagMsg)
            listener.onLyricLine(title, isPlaying, plannedScrollMs, plannedKaraokeMs, plannedWords, seq, nextLyricText)
            // v1.3.11：先推送首句/新句歌词，再退出前奏与间奏态，彻底消除 1 帧空态闪回原厂胶囊
            if (shouldExitIntroAfterDeliver) {
                exitIntroIfActive("first_vocal_line")
            }
            if (wasInterlude) {
                listener.onInterludeChanged(false, -1L)
            }
            scheduleInterludeCheckIfNeeded()
            scheduleTimelineLyricFallback(trigger = "bt_watch", immediate = false)
        }
    }

    /**
     * v1.3.1：区分“同一句歌词重复推送 Metadata”与“副歌连续两句文本完全相同的新句”：
     * - 有在线时间轴时，确认下一索引行文本匹配且进度已接近下一行起点（>= nextStart - 1200ms）；
     * - 无在线时间轴时，要求当前句已播放时长达到正常新句门槛（>= max(2000ms, 估测窗*60%)）。
     */
    /** v1.4.4 第 9 条：解析下一句预览文本；落空(idx<0)返回空串，半句且切片未到行尾时返回本行后半句，末句后半句唱完返回空串 */
    private fun resolveNextLyricForIndex(idx: Int, sliceRemainderText: String = ""): String {
        val tl = activeTimeline ?: return ""
        if (timelineGateState == GateState.FAILED) return ""
        if (idx < 0 || idx > tl.vocalLines.lastIndex) return ""
        if (sliceRemainderText.isNotBlank()) return sliceRemainderText.trim()
        if (idx >= tl.vocalLines.lastIndex) return ""
        val nextLine = tl.vocalLines.getOrNull(idx + 1) ?: return ""
        val vocalEndMs = LrcTimelineParser.estimateVocalEndMs(tl, idx)
        val gapMs = nextLine.effectiveStartMs() - vocalEndMs
        if (gapMs >= Const.TIMELINE_INTERLUDE_MIN_GAP_MS) return ""
        return nextLine.text.trim()
    }

    private fun isGenuineConsecutiveIdenticalLine(lyricText: String, curPosMs: Long): Boolean {
        val timeline = activeTimeline
        if (timeline != null && timelineGateState != GateState.FAILED && lastMatchedVocalIndex >= 0 && curPosMs >= 0L) {
            val nextIdx = lastMatchedVocalIndex + 1
            val nextLine = timeline.vocalLines.getOrNull(nextIdx)
            if (nextLine != null &&
                LrcTimelineParser.fuzzyMatchScore(lyricText, nextLine.text) >= 0.65f &&
                curPosMs >= (nextLine.effectiveStartMs() + trackTimeOffsetMs) - 1200L
            ) {
                return true
            }
            return false
        }
        val minElapsed = max(2000L, (currentEstimatedWindowMs() * 0.60f).toLong())
        return accumulatedLinePlayMs() >= minElapsed
    }

    /** v1.4.4 第 5 条：检查 gateSamples 是否形成合法配对（virtual 维持相邻；state 允许 idx 递增 1..3 或同句切片递增，禁止同 idx 倒带重采） */
    private fun findAgreeingGatePair(isVirtualAnchor: Boolean): Pair<GateSample, GateSample>? {
        if (gateSamples.size < 2) return null
        for (i in 0 until gateSamples.size) {
            for (j in i + 1 until gateSamples.size) {
                val a = gateSamples[i]
                val b = gateSamples[j]
                val idxOk = if (isVirtualAnchor) {
                    b.vocalIndex == a.vocalIndex + 1
                } else {
                    val forwardLine = b.vocalIndex > a.vocalIndex && (b.vocalIndex - a.vocalIndex) <= 3
                    val forwardSliceSameLine = b.vocalIndex == a.vocalIndex && b.sliceStartOffsetMs > a.sliceStartOffsetMs
                    forwardLine || forwardSliceSameLine
                }
                if (idxOk && abs(b.offsetMs - a.offsetMs) <= Const.TIMELINE_GATE_MAX_OFFSET_ERROR_MS) {
                    return a to b
                }
            }
        }
        return null
    }

    /** F3：在 accepted 样本中查找不限 idx 间距（仍须按播放顺序前进且不同 idx 或同 idx 切片递增）、偏移差 <= 4000ms 的一致对 */
    private fun findAgreeingGatePairUnboundedGap(): Pair<GateSample, GateSample>? {
        if (gateSamples.size < 2) return null
        for (i in 0 until gateSamples.size) {
            for (j in i + 1 until gateSamples.size) {
                val a = gateSamples[i]
                val b = gateSamples[j]
                val forward = b.vocalIndex > a.vocalIndex ||
                    (b.vocalIndex == a.vocalIndex && b.sliceStartOffsetMs > a.sliceStartOffsetMs)
                if (forward && abs(b.offsetMs - a.offsetMs) <= Const.TIMELINE_GATE_MAX_OFFSET_ERROR_MS) {
                    return a to b
                }
            }
        }
        return null
    }

    /** v1.4.4 F6 + v1.4.5 G5：判断 outlier 样本中是否存在 >=2 个按播放顺序前进的一致对（<=4000ms）或大偏移同号漂移一致对（|offset|>=10s 且差值 < 15%） */
    private fun hasConsistentOutlierOffsets(): Boolean {
        if (gateOutliers.size < 2) return false
        for (i in 0 until gateOutliers.size) {
            for (j in i + 1 until gateOutliers.size) {
                val a = gateOutliers[i]
                val b = gateOutliers[j]
                val forward = b.vocalIndex > a.vocalIndex ||
                    (b.vocalIndex == a.vocalIndex && b.sliceStartOffsetMs > a.sliceStartOffsetMs)
                if (!forward) continue
                val diff = abs(b.offsetMs - a.offsetMs)
                if (diff <= Const.TIMELINE_GATE_MAX_OFFSET_ERROR_MS) return true
                val sameSign = (a.offsetMs > 0L && b.offsetMs > 0L) || (a.offsetMs < 0L && b.offsetMs < 0L)
                val minAbs = min(abs(a.offsetMs), abs(b.offsetMs))
                if (sameSign && minAbs >= 10_000L && diff < (minAbs * 0.15).toLong()) {
                    return true
                }
            }
        }
        return false
    }

    /** v1.4.5 G5：accepted==0 且连续 2 句 outlier 同号、|offset|>=10s、差值 < |offset|*15% -> 提前触发回退 */
    private fun hasEarlyLargeConsistentOutliers(): Boolean {
        if (gateSamples.isNotEmpty() || gateOutliers.size < 2) return false
        val a = gateOutliers[gateOutliers.size - 2]
        val b = gateOutliers[gateOutliers.size - 1]
        val forward = b.vocalIndex > a.vocalIndex ||
            (b.vocalIndex == a.vocalIndex && b.sliceStartOffsetMs > a.sliceStartOffsetMs)
        if (!forward) return false
        val sameSign = (a.offsetMs > 0L && b.offsetMs > 0L) || (a.offsetMs < 0L && b.offsetMs < 0L)
        val minAbs = min(abs(a.offsetMs), abs(b.offsetMs))
        val diff = abs(b.offsetMs - a.offsetMs)
        return sameSign && minAbs >= 10_000L && diff < (minAbs * 0.15).toLong()
    }

    /** v1.4.4 第 10 条 + F15：校验规范化子串必须属于 matchedIdx 本行且在 ±15s 窗内唯一出现 */
    private fun isSubLineUniqueInWindow(
        timeline: LrcTimelineParser.ParsedTimeline,
        matchedIdx: Int,
        normSub: String,
        curPosMs: Long
    ): Boolean {
        if (normSub.length < 4 || curPosMs < 0L) return false
        val matchedLine = timeline.vocalLines.getOrNull(matchedIdx) ?: return false
        if (!LrcTimelineParser.normalizeForMatch(matchedLine.text).contains(normSub)) return false
        var hits = 0
        for (line in timeline.vocalLines) {
            val expectedPos = line.effectiveStartMs() + trackTimeOffsetMs
            if (abs(curPosMs - expectedPos) > 15_000L) continue
            val normLine = LrcTimelineParser.normalizeForMatch(line.text)
            if (normLine.contains(normSub)) {
                hits++
                if (hits > 1) return false
            }
        }
        return hits == 1
    }

    private fun completeGatePass(
        timeline: LrcTimelineParser.ParsedTimeline,
        agreeingPair: Pair<GateSample, GateSample>,
        isVirtualAnchor: Boolean,
        matchedIdx: Int,
        lineStartForAnchor: Long,
        maxAttempts: Int
    ) {
        val (s1, s2) = agreeingPair
        timelineGateState = GateState.PASSED
        trackTimeOffsetMs = (s1.offsetMs + s2.offsetMs) / 2L
        recentOffsetSamples.clear()
        recentOffsetSamples.addLast(s1.offsetMs)
        recentOffsetSamples.addLast(s2.offsetMs)
        if (isVirtualAnchor && matchedIdx >= 0) {
            anchorPosMs = if (lineStartForAnchor >= 0L) lineStartForAnchor else timeline.vocalLines[matchedIdx].effectiveStartMs()
            anchorElapsedMs = SystemClock.elapsedRealtime()
            anchorSpeed = 1f
            anchorSource = "timeline_anchor"
            trackTimeOffsetMs = 0L
        }
        val passMsg = "[TIMELINE_GATE] PASS accepted=${gateSamples.size} miss=$gateMissCount outlier=$gateOutlierCount " +
            "reloadKept=$lastTimelineReloadKept attempts=$gateVocalAttempts/$maxAttempts " +
            "offsetMs=$trackTimeOffsetMs posSource=$anchorSource"
        YLog.info(msg = passMsg)
        EventHistoryStore.record(passMsg)
        clearTrackRejectCountAsync(currentTrackDisplayTitle, currentTrackArtist)
        // G5：回退候选 PASS 后，才将内存待定拒表持久化到 Provider
        val fbState = getOrCreateFallbackState(currentTrackFp, currentTrackDisplayTitle, currentTrackArtist)
        if (fbState.pendingRejectKugouIds.isNotEmpty()) {
            val persisted = fbState.pendingRejectKugouIds.toList()
            fbState.pendingRejectKugouIds.clear()
            for (cid in persisted) {
                rejectCurrentTrackTimelineAsync(
                    currentTrackDisplayTitle,
                    currentTrackArtist,
                    scope = Const.REJECT_SCOPE_CANDIDATE,
                    kugouId = cid
                )
            }
            val pMsg = "[CAND_REJECT] stage=persisted fp=$currentTrackFp kugouIds=${persisted.joinToString(",")}"
            YLog.info(msg = pMsg)
            EventHistoryStore.record(pMsg)
        }
        calibrateLearnedCachesAfterTimelinePass(timeline, allowFirstLineCalibrate = !isVirtualAnchor)
        // X8：PASS 后若仍处于前奏区，按时间轴精确首句时间重武装一次
        if (inIntro && activeTimelineOwnerFp == currentTrackFp) {
            val curPosPass = estimateCurrentPositionMs()
            if (curPosPass >= 0L && timeline.firstVocalMs >= Const.INTRO_MIN_VALID_START_MS &&
                curPosPass < timeline.firstVocalMs - Const.TIMELINE_INTRO_GUARD_MS
            ) {
                tryArmIntroIfEligible(reason = "gate_pass_refine", skipWaveLead = false)
            }
        }
    }

    private fun completeGateFail(isVirtualAnchor: Boolean, maxAttempts: Int) {
        val hasConsistentOutliers = hasConsistentOutlierOffsets()
        val hasDeterministicConflict = gateSamples.size >= 3
        val failType = when {
            isVirtualAnchor -> "virtual_soft"
            hasConsistentOutliers -> "consistent_outlier"
            hasDeterministicConflict -> "deterministic_conflict"
            else -> "insufficient_evidence"
        }
        val failedKugouId = activeTimelineKugouId
        val failTitle = currentTrackDisplayTitle
        val failArtist = currentTrackArtist
        val failFp = currentTrackFp
        val failDuration = currentTrackDurationMs

        timelineGateState = GateState.FAILED
        val actionDesc = when (failType) {
            "virtual_soft" -> "virtual软失败(不写负缓存)"
            "consistent_outlier" -> "偏差一致型FAIL(候选拒表并触发回退拉取)"
            "deterministic_conflict" -> "确定性FAIL(写歌曲级负缓存)"
            else -> "证据不足软失败(不写负缓存/保留正缓存)"
        }
        val failMsg = "[TIMELINE_GATE] FAIL accepted=${gateSamples.size} miss=$gateMissCount outlier=$gateOutlierCount " +
            "reloadKept=$lastTimelineReloadKept attempts=$gateVocalAttempts/$maxAttempts failType=$failType " +
            "anchor=$anchorSource track=${LogFormat.preview(failTitle, 32)} -> $actionDesc"
        YLog.warn(msg = failMsg)
        EventHistoryStore.record(failMsg)
        logTimelineResult(failFp, "gate_fail_$failType", -1L)

        activeTimeline = null
        activeTimelineOwnerFp = ""
        activeTimelineIdentity = ""
        activeTimelineKugouId = ""
        trackTimeOffsetMs = 0L
        lastMatchedVocalIndex = -1
        lastTimelineFallbackIndex = -1
        timelineFallbackActive = false
        cancelTimelineInterludeCheck()
        cancelTimelineLyricFallback()

        if (anchorSource == "state") {
            when (failType) {
                "consistent_outlier" -> {
                    // G5：候选拒表先入内存，回退候选 PASS 后再持久化；若回退失败/耗尽则撤销内存拒表
                    val fbState = getOrCreateFallbackState(failFp, failTitle, failArtist)
                    if (failedKugouId.isNotBlank()) {
                        fbState.pendingRejectKugouIds.add(failedKugouId)
                        val mMsg = "[CAND_REJECT] stage=memory_commit fp=$failFp kugouId=$failedKugouId"
                        YLog.info(msg = mMsg)
                        EventHistoryStore.record(mMsg)
                    }
                    val triggered = tryTriggerFallbackFetch(failFp, failTitle, failArtist, failDuration, failedKugouId)
                    if (!triggered) {
                        if (fbState.pendingRejectKugouIds.isNotEmpty()) {
                            val rev = fbState.pendingRejectKugouIds.joinToString(",")
                            fbState.pendingRejectKugouIds.clear()
                            val rMsg = "[CAND_REJECT] stage=reverted fp=$failFp kugouIds=$rev reason=no_fallback_left"
                            YLog.info(msg = rMsg)
                            EventHistoryStore.record(rMsg)
                        }
                        rejectCurrentTrackTimelineAsync(failTitle, failArtist, scope = Const.REJECT_SCOPE_TRACK)
                    }
                }
                "deterministic_conflict" -> {
                    rejectCurrentTrackTimelineAsync(failTitle, failArtist, scope = Const.REJECT_SCOPE_TRACK)
                }
                else -> {
                    // insufficient_evidence：软失败，不写负缓存、不删正缓存；F7 记录软失败次数（本会话至多再重验 1 次）
                    getOrCreateFallbackState(failFp, failTitle, failArtist).softFailCount++
                }
            }
        }
    }

    private fun processLineAgainstTimeline(
        lyricText: String,
        curPosMs: Long,
        sampleGate: Boolean = true
    ): TimelineLineTiming? {
        val timeline = activeTimeline ?: return null
        if (timelineGateState == GateState.FAILED || curPosMs < 0L) return null

        val matchedIdx = findBestMatchingVocalIndex(timeline, lyricText, curPosMs)
        var currentLineAcceptedByGate = false
        if (timelineGateState == GateState.PENDING) {
            val isVirtualAnchor = anchorSource == "virtual"
            val maxAttempts = if (isVirtualAnchor) 6 else 4
            if (!sampleGate) {
                // v1.4.4 第 2 条：热升级在 state 锚且命中行在 ±15s 窗内时供 gate 样本，但不递增 gateVocalAttempts；virtual 锚不供样本
                if (matchedIdx >= 0 && !isVirtualAnchor) {
                    val matchedForUpgrade = timeline.vocalLines[matchedIdx]
                    val upgradeSlice = LrcTimelineParser.sliceWordSpansForSubLine(matchedForUpgrade, lyricText)
                    val upgradeSliceStart = upgradeSlice?.sliceStartOffsetMs ?: 0L
                    // v1.4.5-fix9：gate 期望起点取「可听见的起点」——切片起点与复唱点取大者（全行样本即复唱点）
                    val upgradeLineStart = matchedForUpgrade.startMs + max(upgradeSliceStart, matchedForUpgrade.vocalResumeOffsetMs)
                    val upgradeOffset = curPosMs - upgradeLineStart
                    if (abs(upgradeOffset) <= 15_000L) {
                        currentLineAcceptedByGate = true
                        if (anchorSource == "state" && currentLineStartPosMs > 0L && (gateJumpPosMs < 0L || upgradeLineStart >= gateJumpPosMs - 800L)) {
                            val alreadyHas = gateSamples.any {
                                it.vocalIndex == matchedIdx && it.sliceStartOffsetMs == upgradeSliceStart
                            }
                            // v1.4.5-fix9 P0-2：|offset|>3000 一律记 outlier、不进样本（防 +9427 型污染学偏）；outlier 计数保留，G5 同号漂移检测不断
                            if (abs(upgradeOffset) > 3000L) {
                                gateOutlierCount++
                            } else if (!alreadyHas) {
                                gateSamples.add(
                                    GateSample(
                                        vocalIndex = matchedIdx,
                                        offsetMs = upgradeOffset,
                                        sliceStartOffsetMs = upgradeSliceStart,
                                        source = "upgrade"
                                    )
                                )
                            }
                            val agreeingPair = findAgreeingGatePair(isVirtualAnchor = false)
                            val upMsg = "[TIMELINE_GATE_ATTEMPT] fp=$currentTrackFp src=upgrade idx=$matchedIdx " +
                                "lineStart=$upgradeLineStart curPos=$curPosMs offset=$upgradeOffset anchor=$anchorSource " +
                                "accepted=true acceptedCount=${gateSamples.size} miss=$gateMissCount outlier=$gateOutlierCount " +
                                "dup=false consistent=${agreeingPair != null} attempts=$gateVocalAttempts/$maxAttempts"
                            YLog.info(msg = upMsg)
                            EventHistoryStore.record(upMsg)
                            if (agreeingPair != null) {
                                completeGatePass(timeline, agreeingPair, false, matchedIdx, upgradeLineStart, maxAttempts)
                            }
                        }
                    }
                }
            } else {
                // v1.4.5 G4：按位置判定半句续播，仅当 lineStart < jumpPosMs - 800ms 才跳过，消费即清
                var isJumpResumeLine = false
                if (gateJumpPosMs >= 0L && matchedIdx >= 0) {
                    val savedJump = gateJumpPosMs
                    gateJumpPosMs = -1L
                    val matchedCheck = timeline.vocalLines[matchedIdx]
                    val sliceCheck = LrcTimelineParser.sliceWordSpansForSubLine(matchedCheck, lyricText)
                    val candStart = matchedCheck.startMs + max(sliceCheck?.sliceStartOffsetMs ?: 0L, matchedCheck.vocalResumeOffsetMs)
                    isJumpResumeLine = candStart < savedJump - 800L
                    YLog.info(msg = "[GATE_JUMP_MARK] consume jumpPosMs=$savedJump lineStart=$candStart skipped=$isJumpResumeLine fp=$currentTrackFp")
                }

                if (isJumpResumeLine) {
                    val skipMsg = "[TIMELINE_GATE_ATTEMPT] fp=$currentTrackFp idx=$matchedIdx curPos=$curPosMs anchor=$anchorSource " +
                        "skipped=half_line_resume acceptedCount=${gateSamples.size} miss=$gateMissCount outlier=$gateOutlierCount " +
                        "attempts=$gateVocalAttempts/$maxAttempts"
                    YLog.info(msg = skipMsg)
                    EventHistoryStore.record(skipMsg)
                } else {
                    // v1.4.4 第 4 条：单字句且未精确命中直接跳过，不烧计次；matchedIdx == -1 仅记 miss，不烧计次
                    val normLyric = LrcTimelineParser.normalizeForMatch(lyricText)
                    val isSingleCharUnmatched = normLyric.length <= 1 && (
                        matchedIdx < 0 ||
                            LrcTimelineParser.normalizeForMatch(timeline.vocalLines[matchedIdx].text) != normLyric
                        )
                    if (isSingleCharUnmatched) {
                        val skipMsg = "[TIMELINE_GATE_ATTEMPT] fp=$currentTrackFp idx=$matchedIdx curPos=$curPosMs anchor=$anchorSource " +
                            "skipped=single_char acceptedCount=${gateSamples.size} miss=$gateMissCount outlier=$gateOutlierCount " +
                            "attempts=$gateVocalAttempts/$maxAttempts"
                        YLog.info(msg = skipMsg)
                        EventHistoryStore.record(skipMsg)
                        return null
                    }

                    if (matchedIdx < 0) {
                        val preVocal = timeline.firstVocalMs >= 0L && curPosMs < timeline.firstVocalMs - Const.GATE_PREVOCAL_GUARD_MS
                        if (!preVocal) {
                            gateMissCount++
                        }
                        val preTag = if (preVocal) " preVocal=true" else ""
                        val missMsg = "[TIMELINE_GATE_ATTEMPT] fp=$currentTrackFp idx=-1 lineStart=-1 curPos=$curPosMs " +
                            "offset=0 anchor=$anchorSource accepted=false acceptedCount=${gateSamples.size} miss=$gateMissCount " +
                            "outlier=$gateOutlierCount dup=false consistent=false$preTag attempts=$gateVocalAttempts/$maxAttempts"
                        YLog.info(msg = missMsg)
                        EventHistoryStore.record(missMsg)
                        if (gateMissCount >= 6) {
                            completeGateFail(isVirtualAnchor, maxAttempts)
                        }
                        return null
                    }

                    gateVocalAttempts++
                    val matchedForGate = timeline.vocalLines[matchedIdx]
                    val gateSlice = LrcTimelineParser.sliceWordSpansForSubLine(matchedForGate, lyricText)
                    val gateSliceStart = gateSlice?.sliceStartOffsetMs ?: 0L
                    // v1.4.5-fix9：gate 期望起点同 upgrade 口径（切片起点与复唱点取大者）
                    val lineStart = matchedForGate.startMs + max(gateSliceStart, matchedForGate.vocalResumeOffsetMs)
                    val offset = curPosMs - lineStart
                    val normTarget = LrcTimelineParser.normalizeForMatch(matchedForGate.text)
                    val isDuplicateText = normTarget.isNotEmpty() &&
                        timeline.vocalLines.count { LrcTimelineParser.normalizeForMatch(it.text) == normTarget } > 1
                    var virtualAmbiguous = false

                    if (isVirtualAnchor) {
                        var bestMatchScore = 0f
                        var secondBestScore = 0f
                        for (i in timeline.vocalLines.indices) {
                            val sc = LrcTimelineParser.fuzzyMatchScore(lyricText, timeline.vocalLines[i].text)
                            if (sc >= bestMatchScore) {
                                secondBestScore = bestMatchScore
                                bestMatchScore = sc
                            } else if (sc > secondBestScore) {
                                secondBestScore = sc
                            }
                        }
                        virtualAmbiguous = (bestMatchScore - secondBestScore) < 0.15f
                    }

                    val sampleEligible = if (isVirtualAnchor) {
                        !isDuplicateText && !virtualAmbiguous
                    } else {
                        // v1.4.5-fix9 P0-2：非 virtual 锚样本硬顶 3000（原 15000）；超限走下方 else 记 outlier＋OutlierSample，G5 换源检测不断
                        abs(offset) <= 3000L
                    }
                    if (sampleEligible) {
                        val alreadyHas = gateSamples.any {
                            it.vocalIndex == matchedIdx && it.sliceStartOffsetMs == gateSliceStart
                        }
                        // Quick 建议 3：virtual 锚样本同样受 3000 硬顶（只限入样本，本行接受判定不变，防弱证据大偏移污染）
                        val sampleCapped = isVirtualAnchor && abs(offset) > 3000L
                        if (!alreadyHas && !sampleCapped) {
                            gateSamples.add(
                                GateSample(
                                    vocalIndex = matchedIdx,
                                    offsetMs = offset,
                                    sliceStartOffsetMs = gateSliceStart,
                                    source = "bt"
                                )
                            )
                        }
                        currentLineAcceptedByGate = true
                    } else if (!isVirtualAnchor) {
                        gateOutlierCount++
                        gateOutliers.add(OutlierSample(vocalIndex = matchedIdx, offsetMs = offset, sliceStartOffsetMs = gateSliceStart))
                    }

                    val agreeingPair = findAgreeingGatePair(isVirtualAnchor)
                    val attemptMsg = "[TIMELINE_GATE_ATTEMPT] fp=$currentTrackFp idx=$matchedIdx lineStart=$lineStart curPos=$curPosMs " +
                        "offset=$offset anchor=$anchorSource accepted=$currentLineAcceptedByGate " +
                        "acceptedCount=${gateSamples.size} miss=$gateMissCount outlier=$gateOutlierCount " +
                        "dup=${isDuplicateText || virtualAmbiguous} consistent=${agreeingPair != null} attempts=$gateVocalAttempts/$maxAttempts"
                    YLog.info(msg = attemptMsg)
                    EventHistoryStore.record(attemptMsg)

                    if (agreeingPair != null) {
                        completeGatePass(timeline, agreeingPair, isVirtualAnchor, matchedIdx, lineStart, maxAttempts)
                    } else if (!isVirtualAnchor && hasEarlyLargeConsistentOutliers()) {
                        // v1.4.5 G5：accepted==0 且连续 2 句同号大偏移（>=10s 且差值 <15%）立即提前回退
                        completeGateFail(isVirtualAnchor, maxAttempts)
                        return null
                    } else if (gateVocalAttempts >= maxAttempts) {
                        // v1.4.5 G7 F3：attempts 耗尽时，只要 accepted 样本 >=2 先查不限 idx 间距一致对，命中直接走同一 PASS 收口
                        val widePair = if (!isVirtualAnchor && !hasConsistentOutlierOffsets() && gateSamples.size >= 2) {
                            findAgreeingGatePairUnboundedGap()
                        } else {
                            null
                        }
                        if (widePair != null) {
                            completeGatePass(timeline, widePair, false, matchedIdx, lineStart, maxAttempts)
                        } else {
                            completeGateFail(isVirtualAnchor, maxAttempts)
                            return null
                        }
                    }
                }
            }
        }

        // v1.4.4 第 10 条：首句 PENDING 放行维持 fuzzy >= 0.72；半句 subSlice 精确命中时需同时满足 规范化长度>=4 + ±15s窗内唯一 + 非virtual
        val pendingSlice = if (matchedIdx >= 0 && timelineGateState == GateState.PENDING) {
            LrcTimelineParser.sliceWordSpansForSubLine(timeline.vocalLines[matchedIdx], lyricText)
        } else {
            null
        }
        val normPendingLyric = if (pendingSlice != null) LrcTimelineParser.normalizeForMatch(lyricText) else ""
        val subSlicePendingAllowed = pendingSlice != null &&
            anchorSource != "virtual" &&
            normPendingLyric.length >= 4 &&
            isSubLineUniqueInWindow(timeline, matchedIdx, normPendingLyric, curPosMs)
        val allowPendingFirstLine = timelineGateState == GateState.PENDING &&
            matchedIdx >= 0 &&
            currentLineAcceptedByGate &&
            (LrcTimelineParser.fuzzyMatchScore(lyricText, timeline.vocalLines[matchedIdx].text) >= 0.72f || subSlicePendingAllowed)
        if ((timelineGateState != GateState.PASSED && !allowPendingFirstLine) || matchedIdx < 0) return null

        val matchedLine = timeline.vocalLines[matchedIdx]
        val subSlice = pendingSlice ?: LrcTimelineParser.sliceWordSpansForSubLine(matchedLine, lyricText)
        val lineStart = matchedLine.startMs
        // Quick Q-a 连带：偏移采样基准同样取「可听见的起点」（切片起点与复唱点取大者），防 resume 漏进 trackTimeOffsetMs
        val effectiveLineStart = lineStart + max(subSlice?.sliceStartOffsetMs ?: 0L, matchedLine.vocalResumeOffsetMs)
        if (timelineGateState == GateState.PASSED) {
            val sampleOffset = curPosMs - effectiveLineStart
            if (abs(sampleOffset - trackTimeOffsetMs) <= 8_000L) {
                if (recentOffsetSamples.size >= 3) recentOffsetSamples.removeFirst()
                recentOffsetSamples.addLast(sampleOffset)
                val targetOffset = medianOf(recentOffsetSamples)
                val delta = (targetOffset - trackTimeOffsetMs).coerceIn(-1500L, 1500L)
                val newOffset = trackTimeOffsetMs + delta
                if (abs(delta) > Const.TIMELINE_OFFSET_LOG_DELTA_MS) {
                    YLog.info(msg = "[OFFSET] prev=${trackTimeOffsetMs}ms -> new=${newOffset}ms (delta=${delta}ms)")
                }
                trackTimeOffsetMs = newOffset
            }
        }

        lastMatchedVocalIndex = matchedIdx
        val nextLine = timeline.vocalLines.getOrNull(matchedIdx + 1)
        val vocalEndMs = LrcTimelineParser.estimateVocalEndMs(timeline, matchedIdx)
        val gapMs = if (nextLine != null) nextLine.effectiveStartMs() - vocalEndMs else -1L
        if (timelineGateState == GateState.PASSED && nextLine != null && gapMs >= Const.TIMELINE_INTERLUDE_MIN_GAP_MS) {
            scheduleTimelineInterlude(
                vocalIndex = matchedIdx,
                triggerPosMs = vocalEndMs + trackTimeOffsetMs,
                targetNextPosMs = nextLine.effectiveStartMs() + trackTimeOffsetMs,
                gapMs = gapMs
            )
        }

        val rawLineSpan = if (nextLine != null) nextLine.effectiveStartMs() - lineStart else -1L
        val realDur = when {
            nextLine != null && rawLineSpan in 1000L..25_000L && gapMs < Const.TIMELINE_INTERLUDE_MIN_GAP_MS -> rawLineSpan
            vocalEndMs > lineStart -> (vocalEndMs - lineStart).coerceIn(1000L, 25_000L)
            else -> return null
        }
        if (subSlice != null && subSlice.words.isNotEmpty()) {
            val subDur = (subSlice.sliceEndOffsetMs - subSlice.sliceStartOffsetMs).coerceIn(800L, realDur)
            val subScrollDur = subDur.coerceIn(1000L, realDur)
            return TimelineLineTiming(
                scrollDurationMs = subScrollDur,
                karaokeDurationMs = subDur,
                wordSpans = subSlice.words,
                isSliced = true,
                sliceRemainderText = if (subSlice.hasRemainingAfterSlice) subSlice.remainderText else ""
            )
        }
        val resumeOff = matchedLine.vocalResumeOffsetMs
        // v1.4.5-fix9：带复唱点的行，卡拉OK 时长扣除行内静默段（词表另以 effective 为原点重排，见下）
        val vocalDur = ((vocalEndMs - lineStart) - resumeOff).coerceIn(min(1200L, realDur), realDur)
        val btNormSingle = LrcTimelineParser.normalizeForMatch(lyricText)
        val effectiveWords = when {
            matchedLine.words.isEmpty() -> emptyList()
            btNormSingle.length == 1 && matchedLine.words.size > 1 ->
                listOf(LrcTimelineParser.WordSpan(startOffsetMs = 0L, durationMs = vocalDur, text = lyricText.trim()))
            resumeOff > 0L -> LrcTimelineParser.compressedDisplayWords(matchedLine.words, resumeOff)
            else -> matchedLine.words
        }
        return TimelineLineTiming(
            scrollDurationMs = realDur,
            karaokeDurationMs = vocalDur,
            wordSpans = effectiveWords,
            isSliced = false,
            sliceRemainderText = ""
        )
    }

    /** v1.4.3/v1.4.4 锁 1 修正版：仅在播放中且非 virtual/none 锚点、pos > 0 时原地热升级估测首句 */
    private fun tryUpgradeCurrentEstimateLineWithTimeline(): Boolean {
        if (inIntro || inInterlude) return false
        if (kugouPlaybackState != PlaybackState.STATE_PLAYING) return false
        if (anchorSource == "virtual" || anchorSource == "none") return false
        val curTitle = lastDeliveredLyric
        if (curTitle.isBlank() || lastDeliveredWordSpans.isNotEmpty() || currentLyricLineSeq <= 0L) return false
        if (activeTimeline == null || timelineGateState == GateState.FAILED) return false

        val lineStartPos = if (currentLineStartPosMs > 0L) currentLineStartPosMs else estimateCurrentPositionMs()
        if (lineStartPos <= 0L) return false
        val timing = processLineAgainstTimeline(curTitle, lineStartPos, sampleGate = false) ?: return false
        val nextLyricText = resolveNextLyricForIndex(lastMatchedVocalIndex, timing.sliceRemainderText)
        if (timing.wordSpans.isEmpty() && nextLyricText.isEmpty()) return false

        lastDeliveredWordSpans = timing.wordSpans
        lastDeliveredKaraokeMs = timing.karaokeDurationMs
        lastDeliveredNextLyric = nextLyricText
        val scrollSrc = when {
            timing.isSliced -> "krc_slice_upgrade"
            timing.wordSpans.isNotEmpty() -> "krc_upgrade"
            else -> "timeline_upgrade"
        }
        val tlStatus = if (timelineGateState == GateState.PASSED) "online" else "pending_krc"
        val diagMsg = "[SCROLL_DIAG] src=$scrollSrc lineSeq=$currentLyricLineSeq words=${timing.wordSpans.size} tl=$tlStatus " +
            "planned=${timing.scrollDurationMs}ms karaoke=${timing.karaokeDurationMs}ms " +
            "pos=$lineStartPos posSource=$anchorSource line=${LogFormat.preview(curTitle, 28)}"
        YLog.info(msg = diagMsg)
        EventHistoryStore.record(diagMsg)
        listener.onLyricLine(
            curTitle,
            true,
            timing.scrollDurationMs,
            timing.karaokeDurationMs,
            timing.wordSpans,
            currentLyricLineSeq,
            nextLyricText
        )
        return true
    }

    /** v1.4.4 第 3 条：PENDING 非 virtual 窗内(±15s)优先 + 两遍比较防链式下滑 */
    private fun findBestMatchingVocalIndex(
        timeline: LrcTimelineParser.ParsedTimeline,
        lyricText: String,
        curPosMs: Long
    ): Int {
        val lines = timeline.vocalLines
        val allIdx = ArrayList<Int>(8)
        val allScore = ArrayList<Float>(8)
        val allDiff = ArrayList<Long>(8)
        val winIdx = ArrayList<Int>(4)
        val winScore = ArrayList<Float>(4)
        val winDiff = ArrayList<Long>(4)

        val preferInWindow = timelineGateState == GateState.PENDING &&
            anchorSource != "virtual" &&
            curPosMs >= 0L

        for (i in lines.indices) {
            val score = LrcTimelineParser.fuzzyMatchScore(lyricText, lines[i].text)
            if (score < 0.55f) continue
            val candSlice = LrcTimelineParser.sliceWordSpansForSubLine(lines[i], lyricText)
            val expectedPos = lines[i].startMs + max(candSlice?.sliceStartOffsetMs ?: 0L, lines[i].vocalResumeOffsetMs) + trackTimeOffsetMs
            val diff = if (curPosMs >= 0L) abs(curPosMs - expectedPos) else Long.MAX_VALUE
            allIdx.add(i)
            allScore.add(score)
            allDiff.add(diff)
            if (preferInWindow && diff <= 15_000L) {
                winIdx.add(i)
                winScore.add(score)
                winDiff.add(diff)
            }
        }

        val useWin = preferInWindow && winIdx.isNotEmpty()
        val idxs = if (useWin) winIdx else allIdx
        val scores = if (useWin) winScore else allScore
        val diffs = if (useWin) winDiff else allDiff

        if (idxs.isNotEmpty()) {
            // 两遍法：先求最高分，再在 [maxScore - 0.08] 内取时间差最小者，彻底消除链式下滑
            var maxScore = 0f
            for (k in scores.indices) {
                if (scores[k] > maxScore) maxScore = scores[k]
            }
            var bestIdx = -1
            var bestDiff = Long.MAX_VALUE
            var bestTieScore = -1f
            val threshold = maxScore - 0.08f
            for (k in idxs.indices) {
                val sc = scores[k]
                if (sc >= threshold) {
                    val df = diffs[k]
                    if (df < bestDiff || (df == bestDiff && sc > bestTieScore)) {
                        bestDiff = df
                        bestTieScore = sc
                        bestIdx = idxs[k]
                    }
                }
            }
            if (bestIdx >= 0) return bestIdx
        }

        if (timelineGateState == GateState.PASSED &&
            curPosMs >= 0L &&
            LrcTimelineParser.normalizeForMatch(lyricText).length == 1
        ) {
            var nearestIdx = -1
            var nearestDiff = 1500L
            for (i in lines.indices) {
                if (lines[i].words.isEmpty()) continue
                val diff = abs(curPosMs - (lines[i].effectiveStartMs() + trackTimeOffsetMs))
                if (diff <= nearestDiff) {
                    nearestDiff = diff
                    nearestIdx = i
                }
            }
            if (nearestIdx >= 0) return nearestIdx
        }
        return -1
    }

    private fun scheduleTimelineInterlude(
        vocalIndex: Int,
        triggerPosMs: Long,
        targetNextPosMs: Long,
        gapMs: Long
    ) {
        cancelTimelineInterludeCheck()
        if (kugouPlaybackState != PlaybackState.STATE_PLAYING) return
        val curPos = estimateCurrentPositionMs()
        if (curPos < 0L) return
        val fvGuard = resolveOwnedFirstVocalMs()
        if (fvGuard >= Const.INTRO_MIN_VALID_START_MS && curPos < fvGuard - Const.TIMELINE_INTRO_GUARD_MS) return
        val delayMs = (triggerPosMs - curPos).coerceAtLeast(0L)
        val task = Runnable {
            timelineInterludeRunnable = null
            if (!readPrefs().masterEnabled || !versionAllowed || !isKugouActive()) return@Runnable
            if (kugouPlaybackState != PlaybackState.STATE_PLAYING) return@Runnable
            if (lastMatchedVocalIndex != vocalIndex || inIntro) return@Runnable
            inInterlude = true
            activeInterludeTargetMs = targetNextPosMs
            lastDeliveredLyric = ""
            lastDeliveredNextLyric = ""
            lastDeliveredWordSpans = emptyList()
            cancelInterludeCheck()
            // v1.4.5-fix10 F10-3：自然播放的时间线武装同样发段界（自然进入不跳波浪）
            activeInterludeStartMs = targetNextPosMs - gapMs
            activeInterludeSkipWaveLead = false
            val cdMsg = "[COUNTDOWN] arm target=interlude nextStartMs=$targetNextPosMs gapMs=$gapMs startMs=$activeInterludeStartMs skipWaveLead=false posSource=$anchorSource src=timeline"
            YLog.info(msg = cdMsg)
            EventHistoryStore.record(cdMsg)
            // v1.4.5-fix9：下一行带行内静默复唱点时留痕（rawTarget 与 newTarget 之差即修正量）
            val nextLineForResume = activeTimeline?.vocalLines?.getOrNull(vocalIndex + 1)
            if (nextLineForResume != null && nextLineForResume.vocalResumeOffsetMs > 0L) {
                val resumeMsg = "[INTERLUDE_RESUME] rawTarget=${nextLineForResume.startMs + trackTimeOffsetMs} newTarget=$targetNextPosMs offset=${nextLineForResume.vocalResumeOffsetMs} rule=${nextLineForResume.resumeRule}"
                YLog.info(msg = resumeMsg)
                EventHistoryStore.record(resumeMsg)
            }
            listener.onInterludeChanged(true, targetNextPosMs, activeInterludeStartMs, activeInterludeSkipWaveLead)
        }
        timelineInterludeRunnable = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun cancelTimelineInterludeCheck() {
        timelineInterludeRunnable?.let { mainHandler.removeCallbacks(it) }
        timelineInterludeRunnable = null
    }

    /** v1.4.3 6.2-A：仅在 state 锚且挂载位置 <5s 时学习首句起点；覆盖旧值须连续 2 次一致 */
    private fun recordOrFixLearnedFirstLinePosition(fp: String, pos: Long) {
        if (fp.isBlank() || isIsolatedTrackFp(fp)) return
        if (anchorSource != "state" || timelineGateState == GateState.FAILED) return
        if (trackAttachedInitialPosMs < 0L || trackAttachedInitialPosMs >= 5_000L) return
        val dur = currentTrackDurationMs
        val valid = pos >= Const.INTRO_MIN_VALID_START_MS && (dur <= 0L || pos < dur)
        if (!valid) return
        var changed = false
        var prevVal = -1L
        synchronized(learnedFirstLineStartMap) {
            val existing = learnedFirstLineStartMap[fp]
            if (existing == null) {
                learnedFirstLineStartMap[fp] = pos
                pendingFirstLineFixMap.remove(fp)
                changed = true
            } else if (abs(pos - existing) > Const.INTRO_LEARN_FIX_THRESHOLD_MS) {
                val pending = pendingFirstLineFixMap[fp]
                if (pending != null && abs(pos - pending) <= 1500L) {
                    prevVal = existing
                    learnedFirstLineStartMap[fp] = (pending + pos) / 2L
                    pendingFirstLineFixMap.remove(fp)
                    changed = true
                } else {
                    pendingFirstLineFixMap[fp] = pos
                }
            } else {
                pendingFirstLineFixMap.remove(fp)
            }
        }
        if (changed) {
            if (prevVal > 0L) {
                YLog.info(msg = "[INTRO_LEARN_FIX] fp=$fp | old=${prevVal}ms -> new=${learnedFirstLineStartMap[fp]}ms")
            } else {
                YLog.info(msg = "[INTRO_LEARN] fp=$fp | firstLineStartMs=$pos | dur=$dur")
            }
            saveLearnedCacheToHostPrefs()
        }
    }

    /** v1.4.3 6.2-A/B + 附带修正 2：gate 通过后校准学习表（virtual 来源不写首句校准），并删除与时间轴人声行冲突的间奏学习段 */
    private fun calibrateLearnedCachesAfterTimelinePass(
        timeline: LrcTimelineParser.ParsedTimeline,
        allowFirstLineCalibrate: Boolean
    ) {
        val fp = currentTrackFp
        if (fp.isBlank() || isIsolatedTrackFp(fp)) return
        var dirty = false
        // v1.4.5 G6 + fix1 X3/X12：观测值与时间轴推导值差 <=800ms 取时间轴值（防蓝牙到达晚偏），否则取观测值
        val timelineDerived = timeline.firstVocalMs + trackTimeOffsetMs
        val calSrc: String
        val calibratedFirst: Long
        if (firstObservedVocalPosInTrack >= Const.INTRO_MIN_VALID_START_MS) {
            if (abs(firstObservedVocalPosInTrack - timelineDerived) <= 800L) {
                calibratedFirst = timelineDerived
                calSrc = "timeline+offset"
            } else {
                calibratedFirst = firstObservedVocalPosInTrack
                calSrc = "observed"
            }
        } else {
            calibratedFirst = timelineDerived
            calSrc = "timeline+offset"
        }
        val dur = currentTrackDurationMs
        if (allowFirstLineCalibrate &&
            timeline.firstVocalMs >= Const.INTRO_MIN_VALID_START_MS &&
            calibratedFirst >= Const.INTRO_MIN_VALID_START_MS &&
            (dur <= 0L || calibratedFirst < dur)
        ) {
            synchronized(learnedFirstLineStartMap) {
                val old = learnedFirstLineStartMap[fp]
                if (old == null || abs(old - calibratedFirst) > 500L) {
                    learnedFirstLineStartMap[fp] = calibratedFirst
                    pendingFirstLineFixMap.remove(fp)
                    dirty = true
                }
            }
            val calMsg = "[INTRO_CALIBRATE] fp=$fp value=$calibratedFirst src=$calSrc observed=$firstObservedVocalPosInTrack timelineDerived=$timelineDerived"
            YLog.info(msg = calMsg)
            EventHistoryStore.record(calMsg)
        }
        if (timeline.vocalLines.isNotEmpty()) {
            synchronized(learnedInterludesMap) {
                val list = learnedInterludesMap[fp]
                if (list != null && list.isNotEmpty()) {
                    val removed = list.removeAll { span ->
                        timeline.vocalLines.any { line ->
                            val realStart = line.effectiveStartMs() + trackTimeOffsetMs
                            realStart > span.startMs + 2000L && realStart < span.endMs - 2000L
                        }
                    }
                    if (removed) dirty = true
                }
            }
        }
        if (dirty) {
            saveLearnedCacheToHostPrefs()
        }
    }

    private fun isInTrackOutroRegion(curPosMs: Long): Boolean {
        if (currentTrackDurationMs > 0L && curPosMs >= currentTrackDurationMs - 2000L) {
            return true
        }
        val tl = activeTimeline
        if (tl != null && timelineGateState == GateState.PASSED && tl.vocalLines.isNotEmpty()) {
            val lastIdx = tl.vocalLines.lastIndex
            if (lastMatchedVocalIndex >= lastIdx) return true
            val lastStartReal = tl.vocalLines[lastIdx].effectiveStartMs() + trackTimeOffsetMs
            if (curPosMs >= lastStartReal) return true
        }
        return false
    }

    private fun recordLearnedInterludeSpan(fp: String, startMs: Long, endMs: Long) {
        // v1.3.11：尾奏段（目标临近或达到曲长，或已过末句人声起点）禁止写入间奏学习表
        if (currentTrackDurationMs > 0L && endMs >= currentTrackDurationMs - 2000L) {
            return
        }
        val tl = activeTimeline
        if (tl != null && timelineGateState == GateState.PASSED && tl.vocalLines.isNotEmpty()) {
            val lastStartReal = tl.vocalLines.last().effectiveStartMs() + trackTimeOffsetMs
            if (startMs >= lastStartReal) return
            // 附带修正 4：常态化时间轴冲突校验——若该区间内存在人声行，禁止写入假间奏
            val conflictsWithVocal = tl.vocalLines.any { line ->
                val realStart = line.effectiveStartMs() + trackTimeOffsetMs
                realStart > startMs + 2000L && realStart < endMs - 2000L
            }
            if (conflictsWithVocal) return
        }
        var added = false
        if (isIsolatedTrackFp(fp)) return
        synchronized(learnedInterludesMap) {
            val list = learnedInterludesMap.getOrPut(fp) { ArrayList(3) }
            val duplicate = list.any { abs(it.startMs - startMs) < 3000L && abs(it.endMs - endMs) < 3000L }
            if (!duplicate) {
                if (list.size >= 6) {
                    list.removeAt(0)
                }
                list.add(InterludeSpan(startMs, endMs))
                list.sortBy { it.startMs }
                added = true
            }
        }
        if (added) {
            YLog.info(msg = "[INTERLUDE_LEARN] fp=$fp | span=[$startMs..$endMs] (${endMs - startMs}ms)")
            saveLearnedCacheToHostPrefs()
        }
    }

    private fun findLearnedInterludeEndMs(fp: String, curPosMs: Long): Long {
        if (fp.isEmpty() || curPosMs < 0L) return -1L
        synchronized(learnedInterludesMap) {
            val list = learnedInterludesMap[fp] ?: return -1L
            for (span in list) {
                if (curPosMs in (span.startMs - 4000L)..(span.endMs - 1000L)) {
                    return span.endMs
                }
            }
        }
        return -1L
    }

    private fun scheduleIntroSafetyTimeout() {
        cancelIntroSafetyTimeout()
        if (!inIntro) return
        val task = Runnable {
            introSafetyTimeoutRunnable = null
            if (inIntro) {
                YLog.info(msg = "[INTRO_TIMEOUT] 前奏超 ${Const.INTRO_SAFETY_TIMEOUT_MS}ms 未见首行，自动退出")
                exitIntroIfActive("timeout")
            }
        }
        introSafetyTimeoutRunnable = task
        mainHandler.postDelayed(task, Const.INTRO_SAFETY_TIMEOUT_MS)
    }

    private fun cancelIntroSafetyTimeout() {
        introSafetyTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        introSafetyTimeoutRunnable = null
    }

    /** v1.2.11：前奏到点硬到期定时器（有在线时间轴 +350ms 紧贴首句，仅学习值 +1200ms），杜绝卡死 60 秒超时 */
    private fun scheduleIntroTargetExpiry() {
        cancelIntroTargetExpiry()
        if (!inIntro || kugouPlaybackState != PlaybackState.STATE_PLAYING) return
        val targetMs = activeIntroTargetMs
        if (targetMs <= 0L) return
        val curPos = estimateCurrentPositionMs()
        if (curPos < 0L) return
        val marginMs = if (activeTimeline != null) 350L else 1200L
        val expiryPosMs = targetMs + marginMs
        val delayMs = (expiryPosMs - curPos).coerceAtLeast(0L)
        val task = Runnable {
            introTargetExpiryRunnable = null
            if (!inIntro || kugouPlaybackState != PlaybackState.STATE_PLAYING) return@Runnable
            val nowPos = estimateCurrentPositionMs()
            val curTarget = activeIntroTargetMs
            if (curTarget > 0L && nowPos >= curTarget + (marginMs - 150L)) {
                val exitReason = if (activeTimeline != null) "timeline_first_vocal" else "intro_target_reached"
                exitIntroIfActive(exitReason)
                scheduleTimelineLyricFallback(trigger = exitReason, immediate = true)
            } else if (inIntro) {
                scheduleIntroTargetExpiry()
            }
        }
        introTargetExpiryRunnable = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun cancelIntroTargetExpiry() {
        introTargetExpiryRunnable?.let { mainHandler.removeCallbacks(it) }
        introTargetExpiryRunnable = null
    }

    /** v1.2.11：切歌缓冲防误武装（1200ms 后若仍为同曲且无真歌词则补武装，兼顾单曲循环） */
    private fun scheduleDeferredRewindArm(expectedFp: String) {
        cancelPendingRewindArm()
        if (expectedFp.isEmpty()) return
        val task = Runnable {
            pendingRewindArmRunnable = null
            if (expectedFp != currentTrackFp) return@Runnable
            if (kugouPlaybackState != PlaybackState.STATE_PLAYING) return@Runnable
            if (hasRealVocalLineInTrack) return@Runnable
            if (inIntro) return@Runnable
            tryArmIntroIfEligible(reason = "same_track_rewind_confirmed")
        }
        pendingRewindArmRunnable = task
        mainHandler.postDelayed(task, 1200L)
    }

    private fun cancelPendingRewindArm() {
        pendingRewindArmRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRewindArmRunnable = null
    }

    private fun scheduleOtherPlayingEndConfirm(reason: String) {
        if (pendingOtherPlayingEndRunnable != null) return
        val msg = "[SESSION_LIFE] other_playing_debounce_armed reason=$reason delay=${OTHER_PLAYING_CONFIRM_DELAY_MS}ms"
        YLog.info(msg = msg)
        EventHistoryStore.recordDiagOnly(msg)
        val task = Runnable {
            pendingOtherPlayingEndRunnable = null
            if (!otherPlayerPlaying || kugouPlaybackState == PlaybackState.STATE_PLAYING) return@Runnable
            inInterlude = false
            activeInterludeTargetMs = -1L
            inIntro = false
            activeIntroTargetMs = -1L
            activeIntroSkipWaveLead = false
            activeIntroForceWaveOnly = false
            lastDeliveredLyric = ""
            lastDeliveredNextLyric = ""
            lastDeliveredWordSpans = emptyList()
            timelineFallbackActive = false
            listener.onSessionEnded(reason)
        }
        pendingOtherPlayingEndRunnable = task
        mainHandler.postDelayed(task, OTHER_PLAYING_CONFIRM_DELAY_MS)
    }

    private fun cancelOtherPlayingEndConfirm() {
        pendingOtherPlayingEndRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingOtherPlayingEndRunnable = null
    }

    /**
     * v1.2.11：时间轴自主歌词兜底引擎（零轮询 postDelayed）：
     * - 蓝牙歌词健康推送时仅静默观察，绝不干扰；
     * - 蓝牙歌词停推（当前为空或超行末 1.2s 未更新）时按 activeTimeline 精确接管逐句推送，
     *   独立记录时间戳，绝不污染 TIMELINE_GATE 校准或学习表；
     * - 遇长间奏无缝交由 scheduleTimelineInterlude 亮起间奏倒计时，到下一句自动切回歌词。
     */
    // ==================== F1 + F5 + M1/M2/M4：位置驱动区间对账与切歌冻结 ====================

    private fun armTrackSwitchFreeze(fp: String, reason: String) {
        val nowUptime = SystemClock.uptimeMillis()
        if (!isTrackSwitchFrozen()) {
            val frMsg = "[TRACK_SWITCH_FREEZE] armed reason=$reason fp=$fp timeout=1800ms"
            YLog.info(msg = frMsg)
            EventHistoryStore.recordDiagOnly(frMsg)
        }
        trackSwitchFreezeUntilUptimeMs = nowUptime + 1800L
        trackSwitchFrozenFp = fp
        cancelTrackSwitchFreezeTimeout()
        val task = Runnable {
            trackSwitchFreezeTimeoutRunnable = null
            if (trackSwitchFreezeUntilUptimeMs <= 0L) return@Runnable
            val expiredFp = trackSwitchFrozenFp
            trackSwitchFreezeUntilUptimeMs = 0L
            trackSwitchFrozenFp = ""
            val unfrMsg = "[TRACK_SWITCH_FREEZE] timeout_unfreeze fp=$currentTrackFp prevFrozenFp=$expiredFp replay=$pendingPostFreezeReplay"
            YLog.info(msg = unfrMsg)
            EventHistoryStore.recordDiagOnly(unfrMsg)
            replayAfterTrackSwitchUnfreeze("freeze_timeout")
        }
        trackSwitchFreezeTimeoutRunnable = task
        mainHandler.postDelayed(task, 1800L)
    }

    private fun cancelTrackSwitchFreezeTimeout() {
        trackSwitchFreezeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        trackSwitchFreezeTimeoutRunnable = null
    }

    private fun clearTrackSwitchFreeze(triggerReplay: Boolean, reason: String) {
        val wasFrozen = trackSwitchFreezeUntilUptimeMs > 0L
        cancelTrackSwitchFreezeTimeout()
        trackSwitchFreezeUntilUptimeMs = 0L
        trackSwitchFrozenFp = ""
        if (triggerReplay && (wasFrozen || pendingPostFreezeReplay)) {
            replayAfterTrackSwitchUnfreeze(reason)
        } else {
            pendingPostFreezeReplay = false
        }
    }

    /** M1：解冻补偿——在冻结到期或新曲 fp 确认后，对当前 fp 补跑同曲重播、前奏武装与区间对账 */
    private fun replayAfterTrackSwitchUnfreeze(reason: String) {
        val hadPending = pendingPostFreezeReplay || pendingSameTrackRewind
        pendingPostFreezeReplay = false
        if (!hadPending && kugouPlaybackState != PlaybackState.STATE_PLAYING) return
        if (!readPrefs().masterEnabled || !versionAllowed || !isKugouActive()) return
        val isPlayingNow = kugouPlaybackState == PlaybackState.STATE_PLAYING
        val curPos = estimateCurrentPositionMs()
        if (isPlayingNow && pendingSameTrackRewind && currentTrackFp.isNotEmpty()) {
            pendingSameTrackRewind = false
            inInterlude = true
            activeInterludeTargetMs = -1L
            outroClearedInTrack = false
            inIntro = false
            activeIntroTargetMs = -1L
            activeIntroSkipWaveLead = false
            activeIntroForceWaveOnly = false
            hasRealVocalLineInTrack = false
            lastMatchedVocalIndex = -1
            lastDeliveredLyric = ""
            lastDeliveredNextLyric = ""
            lastDeliveredWordSpans = emptyList()
            lastBluetoothLyricUptimeMs = 0L
            lastBluetoothLyricPosMs = -1L
            lastTimelineFallbackIndex = -1
            timelineFallbackActive = false
            cancelStaleTimersOnMigration()
            cancelIntroTargetExpiry()
            cancelIntroSafetyTimeout()
            cancelIntroStateLogger()
            restartLineClock(isPlaying = true)
            listener.onInterludeChanged(true, -1L)
            tryArmIntroIfEligible(reason = "same_track_rewind_after_unfreeze")
            return
        }
        val reconciled = if (curPos >= 0L) {
            reconcileSegment(
                posMs = curPos,
                reason = reason,
                isPlayingNow = isPlayingNow,
                isJumpEvent = false
            )
        } else {
            false
        }
        if (!reconciled && isPlayingNow && !hasRealVocalLineInTrack && !inIntro && curPos >= 0L) {
            tryArmIntroIfEligible(reason = reason)
        }
    }

    private fun isTrackSwitchFrozen(): Boolean {
        val until = trackSwitchFreezeUntilUptimeMs
        if (until <= 0L) return false
        if (currentTrackFp != trackSwitchFrozenFp || SystemClock.uptimeMillis() >= until) {
            cancelTrackSwitchFreezeTimeout()
            trackSwitchFreezeUntilUptimeMs = 0L
            trackSwitchFrozenFp = ""
            pendingPostFreezeReplay = false
            return false
        }
        return true
    }

    private fun cancelStaleTimersOnMigration() {
        cancelInterludeCheck()
        cancelTimelineInterludeCheck()
        cancelTimelineLyricFallback()
        cancelPendingRewindArm()
    }

    /**
     * F1.1 + M4：区间纯函数——「上一行是否已结束」单一真源为 LrcTimelineParser.estimateVocalEndMs(timeline, idx)。
     * 恒定规则：首行之前永远为 INTRO；距下一行起点不足 1 秒的长间隙返回 GapTail（不重发上一行、不新武装）。
     */
    private fun segmentAt(posMs: Long, timeline: LrcTimelineParser.ParsedTimeline): TrackSegment {
        val lines = timeline.vocalLines
        val tlPos = posMs - trackTimeOffsetMs
        // v1.4.5-fix9：定位一律用 effectiveStartMs（原始 startMs 仅学习/缓存/指纹用）
        if (tlPos < lines[0].effectiveStartMs()) {
            return TrackSegment.Intro(firstVocalMs = lines[0].effectiveStartMs() + trackTimeOffsetMs)
        }
        var idx = 0
        for (i in lines.indices) {
            if (lines[i].effectiveStartMs() <= tlPos) {
                idx = i
            } else {
                break
            }
        }
        val nextLine = lines.getOrNull(idx + 1)
        val vocalEndMs = LrcTimelineParser.estimateVocalEndMs(timeline, idx)
        if (nextLine == null) {
            val outroClearPosMs = if (vocalEndMs > 0L) {
                vocalEndMs + trackTimeOffsetMs + Const.OUTRO_CLEAR_GRACE_MS
            } else {
                -1L
            }
            return if (outroClearPosMs > 0L && posMs >= outroClearPosMs) {
                TrackSegment.Outro(lastLineIdx = idx)
            } else {
                TrackSegment.Line(idx = idx)
            }
        }
        val gapMs = nextLine.effectiveStartMs() - vocalEndMs
        val nextTargetPosMs = nextLine.effectiveStartMs() + trackTimeOffsetMs
        if (gapMs >= Const.TIMELINE_INTERLUDE_MIN_GAP_MS && tlPos >= vocalEndMs) {
            if (nextTargetPosMs - posMs < 1000L) {
                return TrackSegment.GapTail(
                    prevIdx = idx,
                    nextIdx = idx + 1,
                    targetNextPosMs = nextTargetPosMs
                )
            }
            return TrackSegment.GapLong(
                prevIdx = idx,
                nextIdx = idx + 1,
                targetNextPosMs = nextTargetPosMs,
                gapMs = gapMs
            )
        }
        if (tlPos >= vocalEndMs) {
            return TrackSegment.GapShort(prevIdx = idx)
        }
        return TrackSegment.Line(idx = idx)
    }

    /**
     * F1.2–F1.8 + M2/M4：统一位置对账入口。返回 true 表示已由时间轴区间接管完毕。
     */
    private fun reconcileSegment(
        posMs: Long,
        reason: String,
        isPlayingNow: Boolean,
        isJumpEvent: Boolean
    ): Boolean {
        if (!readPrefs().masterEnabled || !versionAllowed || posMs < 0L) return false
        if (isTrackSwitchFrozen()) {
            pendingPostFreezeReplay = true
            return true
        }

        val ownedTl = activeTimeline?.takeIf {
            activeTimelineOwnerFp == currentTrackFp &&
                !it.isInstrumental &&
                it.vocalLines.isNotEmpty()
        }

        // F1.4：门控 PENDING 时只清行、不武装（防错版时间轴误武装倒计时）
        if (ownedTl != null && timelineGateState == GateState.PENDING) {
            if (isJumpEvent) {
                cancelStaleTimersOnMigration()
                if (inInterlude) {
                    inInterlude = false
                    activeInterludeTargetMs = -1L
                    listener.onInterludeChanged(false, -1L)
                }
                if (lastDeliveredLyric.isNotBlank()) {
                    lastDeliveredLyric = ""
                    lastDeliveredNextLyric = ""
                    lastDeliveredWordSpans = emptyList()
                    lastMatchedVocalIndex = -1
                    lastTimelineFallbackIndex = -1
                    currentLyricLineSeq++
                    listener.onLyricLine(
                        lyric = "",
                        isPlaying = isPlayingNow,
                        estimatedWindowMs = currentEstimatedWindowMs(),
                        karaokeDurationMs = currentEstimatedWindowMs(),
                        wordSpans = emptyList(),
                        lineSeq = currentLyricLineSeq,
                        nextLyric = ""
                    )
                }
            }
            return false
        }

        // F1.8：无可用时间轴（null 或 FAILED）时，若拖动前挂着旧间奏倒计时，迁移清旧目标不许继续挂着
        if (ownedTl == null || timelineGateState != GateState.PASSED) {
            if (isJumpEvent && inInterlude && !outroClearedInTrack) {
                cancelStaleTimersOnMigration()
                activeInterludeTargetMs = -1L
                if (lastDeliveredLyric.isNotBlank()) {
                    inInterlude = false
                    activeInterludeTargetMs = -1L
                    listener.onInterludeChanged(false, -1L)
                } else {
                    listener.onInterludeChanged(true, -1L)
                }
            }
            return false
        }

        // 已通过门控（PASSED）：以 timeline 区间为准执行幂等迁移
        val seg = segmentAt(posMs, ownedTl)
        when (seg) {
            is TrackSegment.Intro -> {
                if (inInterlude) {
                    inInterlude = false
                    activeInterludeTargetMs = -1L
                    listener.onInterludeChanged(false, -1L)
                }
                outroClearedInTrack = false
                if (!isPlayingNow && !inIntro && (hasRealVocalLineInTrack || lastDeliveredLyric.isNotBlank()) &&
                    posMs < seg.firstVocalMs - Const.TIMELINE_INTRO_GUARD_MS
                ) {
                    cancelStaleTimersOnMigration()
                    hasRealVocalLineInTrack = false
                    lastDeliveredLyric = ""
                    lastDeliveredNextLyric = ""
                    lastDeliveredWordSpans = emptyList()
                    lastMatchedVocalIndex = -1
                    lastTimelineFallbackIndex = -1
                    timelineFallbackActive = false
                    currentLyricLineSeq++
                    val armed = tryArmIntroIfEligible(reason = "seek_back_to_intro", skipWaveLead = false) // fix13 B：按落点走波浪，不再写死跳过
                    if (!armed) {
                        listener.onLyricLine("", false, currentEstimatedWindowMs(), currentEstimatedWindowMs(), emptyList(), currentLyricLineSeq, "")
                    }
                    return true
                }
                if (!isPlayingNow && inIntro && isJumpEvent && posMs < seg.firstVocalMs - Const.TIMELINE_INTRO_GUARD_MS) {
                    tryArmIntroIfEligible(reason = "seek_in_intro", skipWaveLead = false) // fix13 B：同 seek_back_to_intro
                    return true
                }
                return false
            }

            is TrackSegment.GapLong -> {
                if (inIntro) {
                    exitIntroIfActive("seek_past_intro")
                }
                outroClearedInTrack = false
                val alreadySameGap = inInterlude && activeInterludeTargetMs == seg.targetNextPosMs
                if (!alreadySameGap) {
                    cancelStaleTimersOnMigration()
                    inInterlude = true
                    activeInterludeTargetMs = seg.targetNextPosMs
                    lastDeliveredLyric = ""
                    lastDeliveredNextLyric = ""
                    lastDeliveredWordSpans = emptyList()
                    lastMatchedVocalIndex = seg.prevIdx
                    lastTimelineFallbackIndex = seg.prevIdx
                    timelineFallbackActive = true
                    // v1.4.5-fix10 F10-3：段界随武装一并发给 Renderer——起点取静默模型口径（目标−段长）；
                    // 跳跃落点在段头 1500ms 内视为「拖回开头」，跳过波浪段直接出数字（与前奏 seek_back_to_intro 同规，用户 2026-11-24 拍板）
                    activeInterludeStartMs = seg.targetNextPosMs - seg.gapMs
                    activeInterludeSkipWaveLead = isJumpEvent && posMs >= activeInterludeStartMs &&
                        (posMs - activeInterludeStartMs) <= 1_500L
                    val cdMsg = "[COUNTDOWN] arm target=interlude nextStartMs=${seg.targetNextPosMs} gapMs=${seg.gapMs} startMs=$activeInterludeStartMs skipWaveLead=$activeInterludeSkipWaveLead posSource=$anchorSource src=seek_interlude reason=$reason"
                    YLog.info(msg = cdMsg)
                    EventHistoryStore.record(cdMsg)
                    listener.onInterludeChanged(true, seg.targetNextPosMs, activeInterludeStartMs, activeInterludeSkipWaveLead)
                } else if (isJumpEvent) {
                    // v1.4.5-fix10（Quick M4）：同段内跳跃不重武装，但须重算跳波浪标记——拖回段头要跳、同段往后拖要恢复波浪
                    val segStartMs = seg.targetNextPosMs - seg.gapMs
                    val newSkip = posMs >= segStartMs && (posMs - segStartMs) <= 1_500L
                    if (newSkip != activeInterludeSkipWaveLead || activeInterludeStartMs != segStartMs) {
                        activeInterludeStartMs = segStartMs
                        activeInterludeSkipWaveLead = newSkip
                        listener.onInterludeChanged(true, seg.targetNextPosMs, segStartMs, newSkip)
                    }
                }
                if (isPlayingNow) {
                    val nextDelay = (seg.targetNextPosMs - posMs).coerceAtLeast(80L)
                    postTimelineFallbackTick(nextDelay, "interlude_end")
                }
                return true
            }

            is TrackSegment.GapTail -> {
                // M4 + N1：距下一行起点不足 1 秒——复位尾奏标记；若正在间奏且目标不一致则修正为本间隙目标；若非间奏且跳变自远行则清旧行
                if (inIntro) {
                    exitIntroIfActive("seek_past_intro")
                }
                outroClearedInTrack = false
                if (inInterlude) {
                    if (activeInterludeTargetMs != seg.targetNextPosMs) {
                        cancelStaleTimersOnMigration()
                        activeInterludeTargetMs = seg.targetNextPosMs
                        lastDeliveredLyric = ""
                        lastDeliveredNextLyric = ""
                        lastDeliveredWordSpans = emptyList()
                        val cdMsg = "[COUNTDOWN] arm target=interlude nextStartMs=${seg.targetNextPosMs} posSource=$anchorSource src=seek_interlude reason=${reason}_gap_tail"
                        YLog.info(msg = cdMsg)
                        EventHistoryStore.record(cdMsg)
                        listener.onInterludeChanged(true, seg.targetNextPosMs)
                    }
                } else if (isJumpEvent && lastMatchedVocalIndex != seg.prevIdx && lastMatchedVocalIndex != seg.nextIdx) {
                    cancelStaleTimersOnMigration()
                    if (lastDeliveredLyric.isNotBlank()) {
                        lastDeliveredLyric = ""
                        lastDeliveredNextLyric = ""
                        lastDeliveredWordSpans = emptyList()
                        lastMatchedVocalIndex = -1
                        lastTimelineFallbackIndex = -1
                        currentLyricLineSeq++
                        listener.onLyricLine(
                            lyric = "",
                            isPlaying = isPlayingNow,
                            estimatedWindowMs = currentEstimatedWindowMs(),
                            karaokeDurationMs = currentEstimatedWindowMs(),
                            wordSpans = emptyList(),
                            lineSeq = currentLyricLineSeq,
                            nextLyric = ""
                        )
                    }
                }
                if (isPlayingNow) {
                    val nextDelay = (seg.targetNextPosMs - posMs).coerceAtLeast(80L)
                    postTimelineFallbackTick(nextDelay, "interlude_end")
                }
                return true
            }

            is TrackSegment.GapShort -> {
                if (inIntro) {
                    exitIntroIfActive("seek_past_intro")
                }
                // M2：去重严格按行号判定（不比文本）；非跳变下蓝牙先到下一行（prevIdx+1）视为已对齐不反切
                val alreadyAligned = !inInterlude &&
                    !outroClearedInTrack &&
                    lastDeliveredLyric.isNotBlank() &&
                    (lastMatchedVocalIndex == seg.prevIdx || (!isJumpEvent && lastMatchedVocalIndex == seg.prevIdx + 1))
                if (alreadyAligned) {
                    if (isPlayingNow) {
                        val nextLine = ownedTl.vocalLines.getOrNull(seg.prevIdx + 1)
                        if (nextLine != null) {
                            val nextDelay = ((nextLine.effectiveStartMs() + trackTimeOffsetMs) - posMs).coerceAtLeast(80L)
                            postTimelineFallbackTick(nextDelay, "next_line")
                        }
                    }
                    return true
                }
                deliverReconciledLine(ownedTl, seg.prevIdx, posMs, isPlayingNow)
                return true
            }

            is TrackSegment.Line -> {
                if (inIntro) {
                    val expiryMargin = if (isJumpEvent) 0L else 350L
                    if (activeIntroTargetMs <= 0L || posMs >= activeIntroTargetMs + expiryMargin) {
                        exitIntroIfActive("seek_past_intro")
                    } else {
                        return false
                    }
                }
                // M2：去重严格按行号判定（不比文本）；非跳变下蓝牙先到下一行（idx+1）视为已对齐不反切
                val alreadyAligned = !inInterlude &&
                    !outroClearedInTrack &&
                    lastDeliveredLyric.isNotBlank() &&
                    (lastMatchedVocalIndex == seg.idx || (!isJumpEvent && lastMatchedVocalIndex == seg.idx + 1))
                if (alreadyAligned) {
                    if (isPlayingNow) {
                        val nextLine = ownedTl.vocalLines.getOrNull(seg.idx + 1)
                        if (nextLine != null) {
                            val vocalEndMs = LrcTimelineParser.estimateVocalEndMs(ownedTl, seg.idx)
                            val gapToNextMs = nextLine.effectiveStartMs() - vocalEndMs
                            val triggerPos = vocalEndMs + trackTimeOffsetMs
                            if (gapToNextMs >= Const.TIMELINE_INTERLUDE_MIN_GAP_MS && posMs < triggerPos) {
                                scheduleTimelineInterlude(
                                    vocalIndex = seg.idx,
                                    triggerPosMs = triggerPos,
                                    targetNextPosMs = nextLine.effectiveStartMs() + trackTimeOffsetMs,
                                    gapMs = gapToNextMs
                                )
                            }
                            val nextDelay = ((nextLine.effectiveStartMs() + trackTimeOffsetMs) - posMs).coerceAtLeast(80L)
                            postTimelineFallbackTick(nextDelay, "next_line")
                        }
                    }
                    return true
                }
                deliverReconciledLine(ownedTl, seg.idx, posMs, isPlayingNow)
                return true
            }

            is TrackSegment.Outro -> {
                if (inIntro) {
                    exitIntroIfActive("outro_clear")
                }
                val alreadyOutro = outroClearedInTrack && inInterlude && activeInterludeTargetMs == -1L && lastDeliveredLyric.isBlank()
                if (!alreadyOutro) {
                    cancelStaleTimersOnMigration()
                    inInterlude = true
                    activeInterludeTargetMs = -1L
                    outroClearedInTrack = true
                    lastDeliveredLyric = ""
                    lastDeliveredNextLyric = ""
                    lastDeliveredWordSpans = emptyList()
                    lastTimelineFallbackIndex = -1
                    val vocalEndMs = LrcTimelineParser.estimateVocalEndMs(ownedTl, seg.lastLineIdx)
                    val outroMsg = "[TIMELINE_FALLBACK] outro_clear idx=${seg.lastLineIdx} pos=$posMs vocalEndMs=$vocalEndMs track=${LogFormat.preview(currentTrackDisplayTitle, 28)}"
                    YLog.info(msg = outroMsg)
                    EventHistoryStore.record(outroMsg)
                    listener.onInterludeChanged(true, -1L)
                }
                return true
            }
        }
    }

    private fun deliverReconciledLine(
        timeline: LrcTimelineParser.ParsedTimeline,
        idx: Int,
        posMs: Long,
        isPlayingNow: Boolean
    ) {
        val line = timeline.vocalLines[idx]
        val nextLine = timeline.vocalLines.getOrNull(idx + 1)
        val vocalEndMs = LrcTimelineParser.estimateVocalEndMs(timeline, idx)
        cancelStaleTimersOnMigration()
        if (inInterlude) {
            inInterlude = false
            activeInterludeTargetMs = -1L
            listener.onInterludeChanged(false, -1L)
        }
        outroClearedInTrack = false
        currentLyricLineSeq++
        val seq = currentLyricLineSeq
        hasRealVocalLineInTrack = true
        lastMatchedVocalIndex = idx
        lastTimelineFallbackIndex = idx
        timelineFallbackActive = true
        val nextLyricText = resolveNextLyricForIndex(idx)
        lastDeliveredLyric = line.text
        lastDeliveredNextLyric = nextLyricText
        lastDeliveredWordSpans = LrcTimelineParser.compressedDisplayWords(line.words, line.vocalResumeOffsetMs)
        currentLineStartPosMs = posMs
        restartLineClock(isPlaying = isPlayingNow)

        val rawDur = nextLine?.effectiveStartMs()?.minus(line.effectiveStartMs()) ?: Const.SCROLL_DEFAULT_WINDOW_MS
        val plannedScrollMs = rawDur.coerceIn(Const.SCROLL_MIN_WINDOW_MS, 25_000L)
        // Quick M-6：卡拉OK 时长以 effective 为起点（与 BT 路径 S-7 同口径），词表同步换显示用重排版
        val plannedKaraokeMs = (vocalEndMs - line.effectiveStartMs()).coerceIn(1200L, plannedScrollMs)
        lastDeliveredKaraokeMs = plannedKaraokeMs

        val fbSrc = if (line.words.isNotEmpty()) "krc_reconcile" else "tl_reconcile"
        val diagMsg = "[SCROLL_DIAG] src=$fbSrc lineSeq=$seq words=${line.words.size} tl=online " +
            "planned=${plannedScrollMs}ms karaoke=${plannedKaraokeMs}ms " +
            "prevActualDelta=-1ms pos=$posMs posSource=$anchorSource line=${LogFormat.preview(line.text, 28)}"
        YLog.info(msg = diagMsg)
        EventHistoryStore.record(diagMsg)
        listener.onLyricLine(line.text, isPlayingNow, plannedScrollMs, plannedKaraokeMs, LrcTimelineParser.compressedDisplayWords(line.words, line.vocalResumeOffsetMs), seq, nextLyricText)

        if (isPlayingNow) {
            val gapToNextMs = if (nextLine != null) nextLine.effectiveStartMs() - vocalEndMs else -1L
            val triggerPos = vocalEndMs + trackTimeOffsetMs
            // M4：若 pos 已过本行 estimateVocalEndMs，不调度过去时间的间奏武装
            if (nextLine != null && gapToNextMs >= Const.TIMELINE_INTERLUDE_MIN_GAP_MS && posMs < triggerPos) {
                scheduleTimelineInterlude(
                    vocalIndex = idx,
                    triggerPosMs = triggerPos,
                    targetNextPosMs = nextLine.effectiveStartMs() + trackTimeOffsetMs,
                    gapMs = gapToNextMs
                )
            }
            if (nextLine != null) {
                val nextDelay = ((nextLine.effectiveStartMs() + trackTimeOffsetMs) - posMs).coerceAtLeast(80L)
                postTimelineFallbackTick(nextDelay, "next_line")
            } else if (vocalEndMs > 0L) {
                val outroDelay = ((vocalEndMs + trackTimeOffsetMs + Const.OUTRO_CLEAR_GRACE_MS) - posMs).coerceAtLeast(100L)
                postTimelineFallbackTick(outroDelay, "outro_clear")
            }
        }
    }
    private fun scheduleTimelineLyricFallback(trigger: String, immediate: Boolean) {
        cancelTimelineLyricFallback()
        if (!readPrefs().masterEnabled || !versionAllowed || !isKugouActive()) return
        if (kugouPlaybackState != PlaybackState.STATE_PLAYING) return
        val timeline = activeTimeline ?: return
        if (timelineGateState == GateState.FAILED || timeline.isInstrumental || timeline.vocalLines.isEmpty()) return

        ensureVirtualAnchorIfMissing()
        val curPos = estimateCurrentPositionMs()
        if (curPos < 0L) return

        val lines = timeline.vocalLines
        val tlPos = curPos - trackTimeOffsetMs

        // 1. 尚在首句人声前：仅预约到首句时间点 + 350ms 唤醒
        val firstStartMs = lines[0].effectiveStartMs()
        if (tlPos < firstStartMs) {
            val wakeDelay = ((firstStartMs + trackTimeOffsetMs + 350L) - curPos).coerceAtLeast(80L)
            postTimelineFallbackTick(wakeDelay, "wait_first_vocal")
            return
        }

        // 2. 定位当前进度对应的人声句索引
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].effectiveStartMs() <= tlPos) {
                idx = i
            } else {
                break
            }
        }
        if (idx < 0) return

        val line = lines[idx]
        val nextLine = lines.getOrNull(idx + 1)
        val vocalEndMs = LrcTimelineParser.estimateVocalEndMs(timeline, idx)

        // v1.3.10：全曲最后一句（nextLine == null）唱完超过尾奏宽限后自动清词退场，拖动至尾奏也不重推
        val outroClearPosMs = if (nextLine == null && vocalEndMs > 0L) {
            vocalEndMs + trackTimeOffsetMs + Const.OUTRO_CLEAR_GRACE_MS
        } else {
            -1L
        }
        if (nextLine == null && outroClearPosMs > 0L && curPos >= outroClearPosMs) {
            // v1.3.11 方案 A：尾奏清词切入宽胶囊纯净原厂声波态（target=-1L 无倒计时），保持宽胶囊不变短、不触发还原抖动
            if (lastDeliveredLyric.isNotBlank() || !inInterlude || inIntro) {
                inInterlude = true
                outroClearedInTrack = true
                lastDeliveredLyric = ""
                lastDeliveredNextLyric = ""
                lastDeliveredWordSpans = emptyList()
                lastTimelineFallbackIndex = -1
                cancelInterludeCheck()
                cancelTimelineInterludeCheck()
                val outroMsg = "[TIMELINE_FALLBACK] outro_clear idx=$idx pos=$curPos vocalEndMs=$vocalEndMs track=${LogFormat.preview(currentTrackDisplayTitle, 28)}"
                YLog.info(msg = outroMsg)
                EventHistoryStore.record(outroMsg)
                listener.onInterludeChanged(true, -1L)
                exitIntroIfActive("outro_clear")
            }
            return
        }

        // v1.3.10：若前奏态已到首句接管点，先记账延后至首句 onLyricLine 推送后再退前奏，避免 1ms 空词闪回原厂胶囊
        var shouldExitIntroAfterDeliver = false
        if (inIntro) {
            if (activeIntroTargetMs <= 0L || curPos >= activeIntroTargetMs + 350L) {
                shouldExitIntroAfterDeliver = true
            } else {
                val waitIntro = ((activeIntroTargetMs + 350L) - curPos).coerceAtLeast(80L)
                postTimelineFallbackTick(waitIntro, "wait_intro_exit")
                return
            }
        }

        // 3. 蓝牙歌词新鲜度判断：仅当蓝牙已覆盖本句（pos 落在本句起点 -1s 之后）或刚推送 <1.2s，才继续静默看护
        val nowUptime = SystemClock.uptimeMillis()
        val lineStartRealMs = line.effectiveStartMs() + trackTimeOffsetMs
        val btCoveredThisLine = lastBluetoothLyricPosMs >= lineStartRealMs - 1000L ||
            (nowUptime - lastBluetoothLyricUptimeMs) < 1200L ||
            curPos < lineStartRealMs + 1200L
        val btFresh = lastBluetoothLyricUptimeMs > 0L &&
            !timelineFallbackActive &&
            lastDeliveredLyric.isNotBlank() &&
            !immediate &&
            btCoveredThisLine
        if (btFresh) {
            if (shouldExitIntroAfterDeliver) {
                exitIntroIfActive("timeline_first_vocal")
            }
            val thisLineDeadline = lineStartRealMs + 1200L
            val watchTarget = if (lastBluetoothLyricPosMs < lineStartRealMs - 1000L && curPos < thisLineDeadline) {
                thisLineDeadline
            } else if (nextLine != null) {
                nextLine.effectiveStartMs() + trackTimeOffsetMs + 1200L
            } else {
                -1L
            }
            if (watchTarget > 0L) {
                val watchDelay = (watchTarget - curPos).coerceAtLeast(250L)
                postTimelineFallbackTick(watchDelay, "bt_silence_watch")
            }
            return
        }

        // 4. 正式进入/维持时间轴自主兜底推送（不写校准偏移、不写学习表）
        val enteringFallback = !timelineFallbackActive
        timelineFallbackActive = true
        if (enteringFallback) {
            val fbMsg = "[TIMELINE_FALLBACK] take_over trigger=$trigger idx=$idx pos=$curPos track=${LogFormat.preview(currentTrackDisplayTitle, 28)}"
            YLog.info(msg = fbMsg)
            EventHistoryStore.recordDiagOnly(fbMsg)
        }

        // 若当前已落入长间奏区间（超过本句估测唱完点且距下一句 >= 间奏阈值），直接进间奏倒计时并预约下一句唤醒
        val gapToNextMs = if (nextLine != null) nextLine.effectiveStartMs() - vocalEndMs else -1L
        if (nextLine != null && timelineGateState == GateState.PASSED && gapToNextMs >= Const.TIMELINE_INTERLUDE_MIN_GAP_MS && tlPos >= vocalEndMs) {
            val nextTargetPosMs = nextLine.effectiveStartMs() + trackTimeOffsetMs
            // S2：与对账一致按 activeInterludeTargetMs 去重，目标相同不重发、不重启倒计时
            if (!inInterlude || activeInterludeTargetMs != nextTargetPosMs) {
                inInterlude = true
                activeInterludeTargetMs = nextTargetPosMs
                lastDeliveredLyric = ""
                lastDeliveredNextLyric = ""
                lastDeliveredWordSpans = emptyList()
                cancelInterludeCheck()
                cancelTimelineInterludeCheck()
                // v1.4.5-fix10 F10-3：兜底武装同样发段界（起点＝目标−间隔，位置域同口径；自然进入不跳波浪）
                activeInterludeStartMs = nextTargetPosMs - gapToNextMs
                activeInterludeSkipWaveLead = false
                val cdMsg = "[COUNTDOWN] arm target=interlude nextStartMs=$nextTargetPosMs gapMs=$gapToNextMs startMs=$activeInterludeStartMs skipWaveLead=false posSource=$anchorSource src=timeline_fallback"
                YLog.info(msg = cdMsg)
                EventHistoryStore.record(cdMsg)
                listener.onInterludeChanged(true, nextTargetPosMs, activeInterludeStartMs, activeInterludeSkipWaveLead)
            }
            val nextDelay = (nextTargetPosMs - curPos).coerceAtLeast(80L)
            postTimelineFallbackTick(nextDelay, "interlude_end")
            return
        }

        // 推送当前句（防同句重复刷新，除非刚清空或触发立即重同步）
        val needDeliver = immediate || idx != lastTimelineFallbackIndex || lastDeliveredLyric != line.text || inInterlude
        if (needDeliver) {
            val wasInterlude = inInterlude
            inInterlude = false
            activeInterludeTargetMs = -1L
            cancelInterludeCheck()
            cancelTimelineInterludeCheck()
            if (wasInterlude) {
                listener.onInterludeChanged(false, -1L)
            }

            val isNewFallbackLine = idx != lastTimelineFallbackIndex || lastDeliveredLyric != line.text
            if (isNewFallbackLine || currentLyricLineSeq <= 0L) {
                currentLyricLineSeq++
            }
            val seq = currentLyricLineSeq
            hasRealVocalLineInTrack = true
            lastTimelineFallbackIndex = idx
            val nextLyricText = resolveNextLyricForIndex(idx)
            lastMatchedVocalIndex = idx
            lastDeliveredLyric = line.text
            lastDeliveredNextLyric = nextLyricText
            lastDeliveredWordSpans = LrcTimelineParser.compressedDisplayWords(line.words, line.vocalResumeOffsetMs)
            currentLineStartPosMs = curPos
            restartLineClock(isPlaying = true)

            val rawDur = nextLine?.effectiveStartMs()?.minus(line.effectiveStartMs()) ?: Const.SCROLL_DEFAULT_WINDOW_MS
            val plannedScrollMs = rawDur.coerceIn(Const.SCROLL_MIN_WINDOW_MS, 25_000L)
            // Quick M-6：卡拉OK 时长以 effective 为起点（与 BT 路径 S-7 同口径），词表同步换显示用重排版
            val plannedKaraokeMs = (vocalEndMs - line.effectiveStartMs()).coerceIn(1200L, plannedScrollMs)
            lastDeliveredKaraokeMs = plannedKaraokeMs

            val fbSrc = if (line.words.isNotEmpty()) "krc_fallback" else "timeline_fallback"
            val diagMsg = "[SCROLL_DIAG] src=$fbSrc lineSeq=$seq words=${line.words.size} tl=online " +
                "planned=${plannedScrollMs}ms karaoke=${plannedKaraokeMs}ms " +
                "prevActualDelta=-1ms pos=$curPos posSource=$anchorSource line=${LogFormat.preview(line.text, 28)}"
            YLog.info(msg = diagMsg)
            EventHistoryStore.record(diagMsg)
            listener.onLyricLine(line.text, true, plannedScrollMs, plannedKaraokeMs, LrcTimelineParser.compressedDisplayWords(line.words, line.vocalResumeOffsetMs), seq, nextLyricText)

            // 协作长间奏：若本句后存在长间奏，复用时间轴间奏定时器在唱完点亮起倒计时
            if (nextLine != null && gapToNextMs >= Const.TIMELINE_INTERLUDE_MIN_GAP_MS) {
                scheduleTimelineInterlude(
                    vocalIndex = idx,
                    triggerPosMs = vocalEndMs + trackTimeOffsetMs,
                    targetNextPosMs = nextLine.effectiveStartMs() + trackTimeOffsetMs,
                    gapMs = gapToNextMs
                )
            }
        }

        if (shouldExitIntroAfterDeliver) {
            exitIntroIfActive("timeline_first_vocal")
        }

        if (nextLine != null) {
            val nextDelay = ((nextLine.effectiveStartMs() + trackTimeOffsetMs) - curPos).coerceAtLeast(80L)
            postTimelineFallbackTick(nextDelay, "next_line")
        } else if (outroClearPosMs > 0L) {
            // v1.3.10：单次 postDelayed 到尾奏清词点，零轮询
            val outroDelay = (outroClearPosMs - curPos).coerceAtLeast(100L)
            postTimelineFallbackTick(outroDelay, "outro_clear")
        }
    }

    private fun postTimelineFallbackTick(delayMs: Long, reason: String) {
        cancelTimelineLyricFallback()
        val task = Runnable {
            timelineFallbackRunnable = null
            scheduleTimelineLyricFallback(trigger = reason, immediate = false)
        }
        timelineFallbackRunnable = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun cancelTimelineLyricFallback() {
        timelineFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        timelineFallbackRunnable = null
    }

    private fun resetTrackTimingState(
        fp: String,
        displayTitle: String,
        artist: String,
        acceptAnyInitialPos: Boolean = false
    ) {
        cancelInterludeCheck()
        cancelTimelineInterludeCheck()
        cancelTimelineLyricFallback()
        cancelPendingRewindArm()
        cancelIntroTargetExpiry()
        cancelIntroSafetyTimeout()
        cancelIntroStateLogger()
        sawBufferingOrInvalidPos = false
        pendingSameTrackRewind = false
        activeInterludeTargetMs = -1L
        lastObservedRawPosElapsedMs = 0L
        cancelTrackSwitchFreezeTimeout()
        trackSwitchFreezeUntilUptimeMs = 0L
        trackSwitchFrozenFp = ""
        pendingPostFreezeReplay = false
        trackConfirmedUptimeMs = SystemClock.uptimeMillis()
        lastObservedRawPosMs = -1L
        lastObservedRawPosUptimeMs = 0L
        lastDeliveredLyric = ""
        lastDeliveredNextLyric = ""
        lastDeliveredWordSpans = emptyList()
        lastBluetoothLyricUptimeMs = 0L
        lastBluetoothLyricPosMs = -1L
        lastTimelineFallbackIndex = -1
        timelineFallbackActive = false
        recentLineDurationsMs.clear()
        currentTrackFp = fp
        currentSessionValidFps.clear()
        if (fp.isNotBlank()) currentSessionValidFps.add(fp)
        lastJumpEventUptimeMs = 0L
        currentTrackDisplayTitle = displayTitle
        currentTrackArtist = artist
        trackStartElapsedMs = SystemClock.elapsedRealtime()
        currentLineStartPosMs = -1L
        inInterlude = false
        activeInterludeTargetMs = -1L
        outroClearedInTrack = false
        inIntro = false
        activeIntroTargetMs = -1L
        activeIntroSkipWaveLead = false
        activeIntroForceWaveOnly = false
        hasRealVocalLineInTrack = false

        fallbackRequestSeq++
        activeTimeline = null
        activeTimelineOwnerFp = ""
        activeTimelineIdentity = ""
        activeTimelineKugouId = ""
        lastTimelineReloadKept = false
        timelineGateState = GateState.NONE
        resetGateCountersOnly()
        gateJumpPosMs = -1L
        skipNextInterludeLearnAfterJump = false
        skipNextFirstLineLearnAfterJump = false
        trackAttachedInitialPosMs = -1L
        firstObservedVocalPosInTrack = -1L
        recentOffsetSamples.clear()
        trackTimeOffsetMs = 0L
        lastMatchedVocalIndex = -1

        // v1.2.10：真实切歌仅采纳 0..5000ms 防旧曲尾残影；首次中段挂载（acceptAnyInitialPos=true）采纳任意 rawPos>=0
        val maxPos = if (acceptAnyInitialPos) Long.MAX_VALUE else 5000L
        if (!reanchorFromControllers(maxAcceptablePosMs = maxPos)) {
            anchorPosMs = 0L
            anchorElapsedMs = trackStartElapsedMs
            anchorSpeed = 1f
            anchorSource = "virtual"
        }

        currentLineEffectivePlayMs = 0L
        playingSinceUptimeMs = if (kugouPlaybackState == PlaybackState.STATE_PLAYING) {
            SystemClock.uptimeMillis()
        } else {
            -1L
        }
    }

    private fun recordLineDurationSample(durationMs: Long) {
        if (recentLineDurationsMs.size >= Const.SCROLL_SAMPLE_CAPACITY) {
            recentLineDurationsMs.removeFirst()
        }
        recentLineDurationsMs.addLast(durationMs)
    }

    private fun currentEstimatedWindowMs(): Long {
        if (recentLineDurationsMs.isEmpty()) return Const.SCROLL_DEFAULT_WINDOW_MS
        return medianOf(recentLineDurationsMs).coerceIn(Const.SCROLL_MIN_WINDOW_MS, Const.SCROLL_MAX_WINDOW_MS)
    }

    private fun medianOf(values: Collection<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) {
            sorted[n / 2]
        } else {
            (sorted[n / 2 - 1] + sorted[n / 2]) / 2L
        }
    }

    private fun interludeThresholdMs(): Long {
        val scaled = (currentEstimatedWindowMs() * Const.INTERLUDE_MULTIPLIER).toLong()
        return max(Const.INTERLUDE_MIN_THRESHOLD_MS, scaled)
    }

    private fun restartLineClock(isPlaying: Boolean) {
        currentLineEffectivePlayMs = 0L
        playingSinceUptimeMs = if (isPlaying) SystemClock.uptimeMillis() else -1L
    }

    private fun resumeLineClock() {
        if (playingSinceUptimeMs < 0L) {
            playingSinceUptimeMs = SystemClock.uptimeMillis()
        }
    }

    private fun pauseLineClock() {
        if (playingSinceUptimeMs > 0L) {
            val delta = (SystemClock.uptimeMillis() - playingSinceUptimeMs).coerceAtLeast(0L)
            currentLineEffectivePlayMs += delta
            playingSinceUptimeMs = -1L
        }
    }

    private fun accumulatedLinePlayMs(): Long {
        return if (kugouPlaybackState == PlaybackState.STATE_PLAYING && playingSinceUptimeMs > 0L) {
            currentLineEffectivePlayMs + (SystemClock.uptimeMillis() - playingSinceUptimeMs).coerceAtLeast(0L)
        } else {
            currentLineEffectivePlayMs
        }
    }

    private fun scheduleInterludeCheckIfNeeded() {
        cancelInterludeCheck()
        if (!readPrefs().masterEnabled || !versionAllowed || !isKugouActive()) return
        if (kugouPlaybackState != PlaybackState.STATE_PLAYING) return
        if (inInterlude || inIntro || !hasRealVocalLineInTrack || lastDeliveredLyric.isBlank()) return
        // M5：有归属时间轴且门控 != FAILED（PENDING 与 PASSED 均拦截）时，learned 不许武装、不许改目标
        if (activeTimeline != null && activeTimelineOwnerFp == currentTrackFp && timelineGateState != GateState.FAILED) return

        val threshold = interludeThresholdMs()
        val remaining = (threshold - accumulatedLinePlayMs()).coerceAtLeast(0L)
        val task = Runnable {
            interludeCheckRunnable = null
            if (!readPrefs().masterEnabled || !versionAllowed || !isKugouActive()) return@Runnable
            if (kugouPlaybackState != PlaybackState.STATE_PLAYING) return@Runnable
            if (inInterlude || inIntro || !hasRealVocalLineInTrack) return@Runnable
            // M5：Runnable 触发时再查一次门控，防止 PENDING 期间排上的定时器在 PASS 后误改目标
            if (activeTimeline != null && activeTimelineOwnerFp == currentTrackFp && timelineGateState != GateState.FAILED) return@Runnable

            val elapsed = accumulatedLinePlayMs()
            val curThreshold = interludeThresholdMs()
            if (elapsed >= curThreshold) {
                inInterlude = true
                val curPos = estimateCurrentPositionMs()
                // v1.3.11：已过末句或目标临近/达到歌曲总时长时禁止武装间奏倒计时，仅切纯净声波态
                val rawLearnedEnd = if (isInTrackOutroRegion(curPos)) {
                    -1L
                } else {
                    findLearnedInterludeEndMs(currentTrackFp, curPos)
                }
                val learnedEnd = if (
                    rawLearnedEnd > curPos + 1500L &&
                    (currentTrackDurationMs <= 0L || rawLearnedEnd < currentTrackDurationMs - 2000L)
                ) {
                    rawLearnedEnd
                } else {
                    -1L
                }
                if (learnedEnd > 0L) {
                    val cdMsg = "[COUNTDOWN] arm target=interlude nextStartMs=$learnedEnd posSource=$anchorSource src=learned"
                    YLog.info(msg = cdMsg)
                    EventHistoryStore.record(cdMsg)
                }
                activeInterludeTargetMs = learnedEnd
                listener.onInterludeChanged(true, learnedEnd)
            } else {
                scheduleInterludeCheckIfNeeded()
            }
        }
        interludeCheckRunnable = task
        mainHandler.postDelayed(task, remaining)
    }

    private fun cancelInterludeCheck() {
        interludeCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        interludeCheckRunnable = null
    }

    private fun isKugouActive(): Boolean {
        if (boundKugouSessions.isEmpty()) return false
        return when (kugouPlaybackState) {
            PlaybackState.STATE_PLAYING -> true
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_BUFFERING -> !otherPlayerPlaying
            else -> false
        }
    }

    private fun resetAndEnd(reason: String) {
        // v1.3.10：空闲幂等早退，避免酷狗未运行时其他媒体会话触发数百次无意义空转日志与写盘
        if (currentTrackFp.isEmpty() &&
            kugouPlaybackState == PlaybackState.STATE_NONE &&
            lastDeliveredLyric.isEmpty() &&
            !inIntro &&
            !inInterlude &&
            boundKugouSessions.isEmpty()
        ) {
            return
        }
        val reMsg = "[SESSION_LIFE] resetAndEnd reason=$reason | prevState=$kugouPlaybackState | " +
            "pos=${estimateCurrentPositionMs()} | posSource=$anchorSource | fp=$currentTrackFp"
        YLog.info(msg = reMsg)
        EventHistoryStore.record(reMsg)
        timelineRequestSeq++
        cancelInterludeCheck()
        cancelTimelineInterludeCheck()
        cancelTimelineLyricFallback()
        cancelPendingRewindArm()
        cancelOtherPlayingEndConfirm()
        cancelIntroTargetExpiry()
        cancelIntroSafetyTimeout()
        cancelIntroStateLogger()
        sawBufferingOrInvalidPos = false
        pendingSameTrackRewind = false
        activeInterludeTargetMs = -1L
        lastObservedRawPosElapsedMs = 0L
        cancelTrackSwitchFreezeTimeout()
        trackSwitchFreezeUntilUptimeMs = 0L
        trackSwitchFrozenFp = ""
        pendingPostFreezeReplay = false
        trackConfirmedUptimeMs = SystemClock.uptimeMillis()
        lastObservedRawPosUptimeMs = 0L
        lastBluetoothLyricUptimeMs = 0L
        lastBluetoothLyricPosMs = -1L
        lastTimelineFallbackIndex = -1
        timelineFallbackActive = false
        recentLineDurationsMs.clear()
        currentTrackFp = ""
        currentTrackDisplayTitle = ""
        currentTrackArtist = ""
        currentTrackDurationMs = -1L
        trackStartElapsedMs = 0L
        currentLineStartPosMs = -1L
        anchorPosMs = -1L
        anchorElapsedMs = 0L
        anchorSpeed = 1f
        anchorSource = "none"
        lastObservedRawPosMs = -1L
        hasRealVocalLineInTrack = false
        currentLineEffectivePlayMs = 0L
        playingSinceUptimeMs = -1L
        inInterlude = false
        activeInterludeTargetMs = -1L
        outroClearedInTrack = false
        inIntro = false
        activeIntroTargetMs = -1L
        activeIntroSkipWaveLead = false
        activeIntroForceWaveOnly = false
        fallbackRequestSeq++
        activeTimeline = null
        activeTimelineOwnerFp = ""
        activeTimelineIdentity = ""
        activeTimelineKugouId = ""
        lastTimelineReloadKept = false
        timelineGateState = GateState.NONE
        resetGateCountersOnly()
        gateJumpPosMs = -1L
        skipNextInterludeLearnAfterJump = false
        skipNextFirstLineLearnAfterJump = false
        trackAttachedInitialPosMs = -1L
        firstObservedVocalPosInTrack = -1L
        recentOffsetSamples.clear()
        trackTimeOffsetMs = 0L
        lastMatchedVocalIndex = -1
        kugouPlaybackState = PlaybackState.STATE_NONE
        lastDeliveredLyric = ""
        lastDeliveredNextLyric = ""
        lastDeliveredWordSpans = emptyList()
        lastJumpEventUptimeMs = 0L
        currentSessionValidFps.clear()
        fingerprintV2.reset()
        listener.onSessionEnded(reason)
        EventHistoryStore.replayRecentThrottled("reset_and_end")
    }

    private fun checkKugouVersion(ctx: Context) {
        if (versionChecked) return
        versionChecked = true
        runCatching {
            val info = ctx.packageManager.getPackageInfo(Const.PKG_KUGOU, 0)
            val code = info.longVersionCode
            val name = info.versionName ?: "unknown"
            if (code == Const.TARGET_KUGOU_VERSION_CODE) {
                YLog.info(msg = "[VERSION_OK] 酷狗版本匹配: $name ($code)")
            } else if (Const.STRICT_VERSION_GATE) {
                versionAllowed = false
                YLog.warn(
                    msg = "[VERSION_BLOCKED] 酷狗版本 $name ($code) != ${Const.TARGET_KUGOU_VERSION_CODE}，严格版本门已停用"
                )
            } else {
                YLog.warn(
                    msg = "[VERSION_WARN] 酷狗版本 $name ($code) != ${Const.TARGET_KUGOU_VERSION_CODE}，软告警模式继续运行"
                )
            }
        }.onFailure {
            YLog.warn(msg = "[VERSION_ERR] 查询酷狗版本失败: ${it.message}")
        }
    }
}
