package com.vivo.islandlyric.util

/**
 * v1.4.6-b：共享 33.333ms 帧网格（30fps 槽对齐），
 * 供歌词动态（LyricScrollView）与呼吸光圈（CapsuleGlowRenderer）对齐下一次唤醒时间。
 * 零堆分配、纯 uptimeMillis 算术。
 */
object IslandFrameGrid {

    const val SLOT_DURATION_MS = 1000.0 / 30.0 // 33.333333...ms

    /**
     * 计算从 [nowUptimeMs] 到下一个（或第 [slotStep] 个）网格槽的延迟毫秒数。
     * 若距当前槽边界不足 4ms，则顺延到下一槽，避免 1–3ms 超短唤醒抖动。
     */
    fun delayToNextSlot(nowUptimeMs: Long, slotStep: Int = 1): Long {
        val step = if (slotStep < 1) 1 else slotStep
        val slotIndex = (nowUptimeMs.toDouble() / SLOT_DURATION_MS).toLong()
        var targetSlot = slotIndex + step
        var targetMs = (targetSlot.toDouble() * SLOT_DURATION_MS).toLong()
        if (step == 1 && targetMs - nowUptimeMs < 4L) {
            targetSlot += 1L
            targetMs = (targetSlot.toDouble() * SLOT_DURATION_MS).toLong()
        }
        val delay = targetMs - nowUptimeMs
        return if (delay < 4L) 4L else delay
    }
}
