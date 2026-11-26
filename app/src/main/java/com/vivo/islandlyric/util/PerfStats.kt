package com.vivo.islandlyric.util

import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.4.5-fix16（Quick 二轮裁决）：性能计数器——全部内存计数、零定时器，不违反「暂停态零开销」红线。
 * v1.4.6-a1（P6）：拆分光圈自驱帧（haloFrame）与被其他重绘带出的帧（haloPiggyback）。
 * 由 Renderer 在播放状态变化（或距上次输出 >60s 的下一个事件）时调 collectAndReset 输出一行 [PERF]。
 * 判读：暂停＋横屏游戏时段内，帧回调与 postDelayed 计数必须全 0。
 */
object PerfStats {
    val edgeChecks = AtomicInteger(0)
    val reconcilePass = AtomicInteger(0)
    val reconcileReentry = AtomicInteger(0)
    val reconcilePredraw = AtomicInteger(0)
    val reconcileLayout = AtomicInteger(0)
    val reconcileState = AtomicInteger(0)
    val reconcileOther = AtomicInteger(0)
    val predrawCallbacks = AtomicInteger(0)
    val shimmerFrames = AtomicInteger(0)
    val countdownFrames = AtomicInteger(0)
    val haloFrames = AtomicInteger(0)
    val haloPiggyback = AtomicInteger(0)
    val delayedNarrow = AtomicInteger(0)
    val delayedHold = AtomicInteger(0)
    val delayedReentry = AtomicInteger(0)
    val diagLines = AtomicInteger(0)
    val overlayLyricUpdates = AtomicInteger(0)
    val overlayPlayUpdates = AtomicInteger(0)
    val overlayInterludeUpdates = AtomicInteger(0)
    val overlayIntroUpdates = AtomicInteger(0)
    val overlayDraws = AtomicInteger(0)
    val lyricFrames = AtomicInteger(0)

    fun collectAndReset(windowSec: Long, playingMs: Long, hiddenMs: Long = 0L, screenOn: Int = -1, screenOffMs: Long = 0L): String {
        fun drain(c: AtomicInteger): Int = c.getAndSet(0)
        val recCalls = EventHistoryStore.perfRecordCalls.getAndSet(0)
        val recSerMs = EventHistoryStore.perfRecordSerializeMs.getAndSet(0)
        val diagQMax = EventHistoryStore.perfDiagQueueMax.getAndSet(0)
        // vc55（fix21）：hiddenMs=本窗内岛处于隐藏态的累计毫秒、screenOn=出账时屏幕是否点亮（-1=未知），
        // 供判读「息屏/隐藏时段是否真零帧」——此前靠事件行拆账、易漏段（2026-11-26 教训）
        // vc56：screenOffMs=本窗内屏幕熄灭的累计毫秒（广播边沿记账＋双边强制结算，息屏窗可直接读帧计数）；
        // 与 hiddenMs 有重叠、不可相加（Hark 验收口径）
        return "window=${windowSec}s playingMs=$playingMs hiddenMs=$hiddenMs screenOffMs=$screenOffMs screenOn=$screenOn" +
            " edge=${drain(edgeChecks)}" +
            " reconcile{pass=${drain(reconcilePass)},reentry=${drain(reconcileReentry)},predraw=${drain(reconcilePredraw)},layout=${drain(reconcileLayout)},state=${drain(reconcileState)},other=${drain(reconcileOther)}}" +
            " predraw=${drain(predrawCallbacks)}" +
            " shimmerFrame=${drain(shimmerFrames)} cdFrame=${drain(countdownFrames)} haloFrame=${drain(haloFrames)} haloPiggyback=${drain(haloPiggyback)}" +
            " delayedRuns{narrow=${drain(delayedNarrow)},hold=${drain(delayedHold)},reentry=${drain(delayedReentry)}}" +
            " diagLines=${drain(diagLines)}" +
            " recordCalls=$recCalls recordSerializeMs=$recSerMs diagQueueMax=$diagQMax" +
            " overlayUpdates{lyric=${drain(overlayLyricUpdates)},play=${drain(overlayPlayUpdates)},interlude=${drain(overlayInterludeUpdates)},intro=${drain(overlayIntroUpdates)}}" +
            " overlayDraws=${drain(overlayDraws)} lyricFrame=${drain(lyricFrames)}"
    }
}
