package com.vivo.islandlyric.util

import android.media.MediaMetadata
import android.os.Handler
import android.os.SystemClock
import com.highcapable.yukihookapi.hook.log.YLog
import com.vivo.islandlyric.Const
import com.vivo.islandlyric.util.LrcTimelineParser

/**
 * 曲目指纹 v2（v1.1.0 增量携带曲目信息 displayTitle + artist）：
 * 1. 指纹仅使用「归一化 ARTIST + ALBUM」，彻底移出不可靠的 DURATION；
 * 2. ARTIST 归一化：若以 "-歌名" 结尾则截掉该后缀，否则截断第一个 "-" 及之后后缀；
 * 3. ALBUM 为空视为「未知」，同 ARTIST 下沿用上一次非空 ALBUM，不构成新指纹；
 * 4. 候选与已确认指纹仅在 ALBUM 空/非空间变化时归一为同一曲目；
 * 5. 播放进度 position 从 >10s 跳回 <3s 作为辅助佐证信号记录，不单独触发切歌；
 * 6. 1200ms 去抖确认后同步产出曲目信息（displayTitle, normalizedArtist）与最新歌词行。
 */
class TrackFingerprintV2(
    private val handler: Handler,
    private val debounceMs: Long = Const.TRACK_DEBOUNCE_MS,
    private val onInitialTrack: (fp: String, displayTitle: String, artist: String) -> Unit,
    private val onTrackInfoHealed: (fp: String, displayTitle: String, artist: String) -> Unit,
    private val onTrackConfirmed: (
        oldFp: String,
        newFp: String,
        bufferedTitle: String,
        displayTitle: String,
        artist: String
    ) -> Unit
) {
    enum class Decision {
        /** 初始曲目或与当前已确认曲目相同，可直接逐行更新 TITLE */
        SAME_TRACK,
        /** 检测到新曲目候选，正在 1200ms 去抖窗口内等待确认 */
        PENDING_SWITCH
    }

    private var confirmedArtist: String = ""
    private var confirmedAlbum: String = ""
    private var lastNonEmptyAlbum: String = ""
    private var hasConfirmed: Boolean = false
    private var confirmedDisplayTitle: String = ""
    private var confirmedHasExplicitTitle: Boolean = false
    private var confirmedIsolatedToken: String = ""
    private var healedCurrentTrack: Boolean = false

    private var pendingArtist: String? = null
    private var pendingAlbum: String? = null
    private var pendingTitle: String = ""
    private var pendingDisplayTitle: String = ""
    private var pendingHasExplicitDisplayTitle: Boolean = false
    private var pendingRunnable: Runnable? = null

    private var lastPositionMs: Long = -1L
    private var lastPosRewindElapsedMs: Long = 0L

    fun onPlaybackPosition(positionMs: Long) {
        if (positionMs < 0L) return
        if (lastPositionMs > Const.POS_REWIND_FROM_MS && positionMs < Const.POS_REWIND_TO_MS) {
            lastPosRewindElapsedMs = SystemClock.elapsedRealtime()
        }
        lastPositionMs = positionMs
    }

    fun evaluate(metadata: MediaMetadata, title: String): Decision {
        val rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        val rawAlbum = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim().orEmpty()
        val (songTitle, isExplicitSongTitle) = extractDisplayTitle(metadata, rawArtist, title)
        val normArtist = normalizeArtist(rawArtist, songTitle)

        // 首次收到元数据：直接建立初始曲目指纹并触发一次初始曲目信息回调
        if (!hasConfirmed) {
            confirmedArtist = normArtist
            confirmedAlbum = rawAlbum
            if (rawAlbum.isNotEmpty()) lastNonEmptyAlbum = rawAlbum
            confirmedDisplayTitle = songTitle
            confirmedHasExplicitTitle = isExplicitSongTitle
            confirmedIsolatedToken = if (isExplicitSongTitle && LrcTimelineParser.normalizeForMatch(songTitle).isNotEmpty()) "" else nextIsolatedToken()
            healedCurrentTrack = false
            hasConfirmed = true
            val initFp = formatFp(confirmedArtist, confirmedAlbum, confirmedDisplayTitle, confirmedHasExplicitTitle, confirmedIsolatedToken)
            YLog.info(
                msg = "[TRACK_V3_KEY] phase=init fp=$initFp | explicit=$confirmedHasExplicitTitle | displayTitle=${LogFormat.preview(songTitle)} | artist=${LogFormat.preview(normArtist)}"
            )
            onInitialTrack(initFp, songTitle, normArtist)
            return Decision.SAME_TRACK
        }

        // 规则 2 & 3 + G1：同专辑不同可靠歌名视为不同曲目，彻底避免同专辑串键
        if (isSameTrack(confirmedArtist, confirmedAlbum, confirmedDisplayTitle, confirmedHasExplicitTitle, normArtist, rawAlbum, songTitle, isExplicitSongTitle)) {
            if (rawAlbum.isNotEmpty()) {
                if (confirmedAlbum.isEmpty()) {
                    confirmedAlbum = rawAlbum
                }
                lastNonEmptyAlbum = rawAlbum
            }
            if (pendingRunnable != null) {
                YLog.info(
                    msg = "[TRACK_V2_CANCEL] 回滚回原曲目 ${formatFp(confirmedArtist, confirmedAlbum, confirmedDisplayTitle, confirmedHasExplicitTitle, confirmedIsolatedToken)}，取消待定切歌"
                )
                cancelPending()
            }
            // 曲目信息自愈：首次下发为非显式兜底歌名，后续同曲出现显式 DISPLAY_TITLE 且不同时补发一次
            if (!confirmedHasExplicitTitle && !healedCurrentTrack) {
                val explicitDisplayTitle =
                    metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.trim().orEmpty()
                if (explicitDisplayTitle.isNotEmpty() && explicitDisplayTitle != confirmedDisplayTitle) {
                    confirmedDisplayTitle = explicitDisplayTitle
                    confirmedHasExplicitTitle = true
                    if (LrcTimelineParser.normalizeForMatch(explicitDisplayTitle).isNotEmpty()) {
                        confirmedIsolatedToken = ""
                    } else if (confirmedIsolatedToken.isEmpty()) {
                        confirmedIsolatedToken = nextIsolatedToken()
                    }
                    healedCurrentTrack = true
                    val healedArtist = normalizeArtist(rawArtist, explicitDisplayTitle).ifEmpty { confirmedArtist }
                    val curFp = formatFp(confirmedArtist, confirmedAlbum, confirmedDisplayTitle, confirmedHasExplicitTitle, confirmedIsolatedToken)
                    YLog.info(
                        msg = "[TRACK_V3_KEY] phase=heal fp=$curFp | explicit=true | displayTitle=${LogFormat.preview(explicitDisplayTitle)} | artist=${LogFormat.preview(healedArtist)}"
                    )
                    onTrackInfoHealed(curFp, explicitDisplayTitle, healedArtist)
                }
            }
            return Decision.SAME_TRACK
        }

        // 规则 2：新曲目本身若 ALBUM 为空但 ARTIST 与当前待定一致，继承待定非空 ALBUM
        val effAlbum = if (rawAlbum.isEmpty() && normArtist == pendingArtist && !pendingAlbum.isNullOrEmpty()) {
            pendingAlbum!!
        } else {
            rawAlbum
        }

        // 已在等待同一候选曲目：刷新该新歌最新 TITLE，仅在拿到更可靠歌名时更新 pendingDisplayTitle
        if (pendingRunnable != null && isSameTrack(pendingArtist.orEmpty(), pendingAlbum.orEmpty(), pendingDisplayTitle, pendingHasExplicitDisplayTitle, normArtist, effAlbum, songTitle, isExplicitSongTitle)) {
            if (effAlbum.isNotEmpty() && pendingAlbum.isNullOrEmpty()) {
                pendingAlbum = effAlbum
            }
            if (title.isNotBlank()) {
                pendingTitle = title
            }
            if (songTitle.isNotBlank() && (pendingDisplayTitle.isBlank() || (isExplicitSongTitle && !pendingHasExplicitDisplayTitle))) {
                pendingDisplayTitle = songTitle
                pendingHasExplicitDisplayTitle = isExplicitSongTitle
            }
            return Decision.PENDING_SWITCH
        }

        // 出现新的候选曲目：启动或重置 1200ms 去抖窗口
        cancelPending()
        pendingArtist = normArtist
        pendingAlbum = effAlbum
        pendingTitle = title
        pendingDisplayTitle = songTitle
        pendingHasExplicitDisplayTitle = isExplicitSongTitle

        val hasPosRewind = (SystemClock.elapsedRealtime() - lastPosRewindElapsedMs) <= Const.POS_REWIND_VALID_WINDOW_MS
        val candFp = formatFp(normArtist, effAlbum, songTitle, isExplicitSongTitle, if (isExplicitSongTitle) "" else "u_pending")
        val currFp = formatFp(confirmedArtist, confirmedAlbum, confirmedDisplayTitle, confirmedHasExplicitTitle, confirmedIsolatedToken)
        YLog.info(
            msg = "[TRACK_V2_PENDING] $currFp -> $candFp (posRewind=$hasPosRewind, wait=${debounceMs}ms)"
        )

        val task = Runnable {
            val finalArtist = pendingArtist ?: return@Runnable
            val finalAlbum = pendingAlbum.orEmpty()
            val bufferedTitle = pendingTitle
            val confirmedSongTitle = pendingDisplayTitle
            val confirmedExplicit = pendingHasExplicitDisplayTitle
            val oldFp = formatFp(confirmedArtist, confirmedAlbum, confirmedDisplayTitle, confirmedHasExplicitTitle, confirmedIsolatedToken)

            pendingArtist = null
            pendingAlbum = null
            pendingTitle = ""
            pendingDisplayTitle = ""
            pendingHasExplicitDisplayTitle = false
            pendingRunnable = null

            // 二次复核：若最终确认与原曲目属同一曲目则不触发切歌
            if (isSameTrack(confirmedArtist, confirmedAlbum, confirmedDisplayTitle, confirmedHasExplicitTitle, finalArtist, finalAlbum, confirmedSongTitle, confirmedExplicit)) {
                if (finalAlbum.isNotEmpty()) {
                    confirmedAlbum = finalAlbum
                    lastNonEmptyAlbum = finalAlbum
                }
                return@Runnable
            }

            confirmedArtist = finalArtist
            confirmedAlbum = finalAlbum
            confirmedDisplayTitle = confirmedSongTitle
            confirmedHasExplicitTitle = confirmedExplicit
            confirmedIsolatedToken = if (confirmedExplicit && LrcTimelineParser.normalizeForMatch(confirmedSongTitle).isNotEmpty()) "" else nextIsolatedToken()
            healedCurrentTrack = false
            if (finalAlbum.isNotEmpty()) {
                lastNonEmptyAlbum = finalAlbum
            }
            val newFp = formatFp(confirmedArtist, confirmedAlbum, confirmedDisplayTitle, confirmedHasExplicitTitle, confirmedIsolatedToken)
            val oldPrefix = oldFp.substringBeforeLast('|', "")
            val newPrefix = newFp.substringBeforeLast('|', "")
            val titleOnlyTag = if (oldPrefix.isNotEmpty() && oldPrefix == newPrefix && oldFp != newFp) " | title_only=true" else ""
            YLog.info(
                msg = "[TRACK_V3_KEY] phase=confirmed oldFp=$oldFp -> fp=$newFp$titleOnlyTag | explicit=$confirmedExplicit | displayTitle=${LogFormat.preview(confirmedSongTitle)} | artist=${LogFormat.preview(finalArtist)}"
            )
            onTrackConfirmed(oldFp, newFp, bufferedTitle, confirmedSongTitle, finalArtist)
        }
        pendingRunnable = task
        handler.postDelayed(task, debounceMs)
        return Decision.PENDING_SWITCH
    }

    fun reset() {
        cancelPending()
        confirmedArtist = ""
        confirmedAlbum = ""
        lastNonEmptyAlbum = ""
        hasConfirmed = false
        confirmedDisplayTitle = ""
        confirmedHasExplicitTitle = false
        confirmedIsolatedToken = ""
        healedCurrentTrack = false
        lastPositionMs = -1L
        lastPosRewindElapsedMs = 0L
    }

    private fun cancelPending() {
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
        pendingArtist = null
        pendingAlbum = null
        pendingTitle = ""
        pendingDisplayTitle = ""
        pendingHasExplicitDisplayTitle = false
    }

    private fun nextIsolatedToken(): String {
        return "u_${SystemClock.elapsedRealtimeNanos().toString(36)}"
    }

    private fun isSameTrack(
        baseArtist: String,
        baseAlbum: String,
        baseTitle: String,
        baseHasExplicitTitle: Boolean,
        newArtist: String,
        newAlbum: String,
        newTitle: String,
        newHasExplicitTitle: Boolean
    ): Boolean {
        if (baseArtist != newArtist) return false
        if (baseHasExplicitTitle && newHasExplicitTitle) {
            val nb = LrcTimelineParser.normalizeForMatch(baseTitle)
            val nn = LrcTimelineParser.normalizeForMatch(newTitle)
            if (nb.isNotEmpty() && nn.isNotEmpty() && nb != nn) {
                return false
            }
        }
        if (baseAlbum.isEmpty() || newAlbum.isEmpty()) return true
        return baseAlbum == newAlbum
    }

    /** v1.4.5 G1：V3 键包含歌手 + 专辑 + 可靠归一化歌名（歌名不可靠时降级为本曲独立键 u_xxx，绝不跨曲共享） */
    private fun formatFp(
        artist: String,
        album: String,
        displayTitle: String,
        hasExplicitTitle: Boolean,
        isolatedToken: String
    ): String {
        val displayAlbum = album.ifEmpty { lastNonEmptyAlbum.takeIf { artist == confirmedArtist }.orEmpty() }
        val normTitle = if (hasExplicitTitle) LrcTimelineParser.normalizeForMatch(displayTitle) else ""
        val trackPart = when {
            normTitle.isNotEmpty() -> normTitle
            isolatedToken.isNotEmpty() -> isolatedToken
            else -> "u_fallback"
        }
        return "V3:$artist|$displayAlbum|$trackPart"
    }

    companion object {
        /**
         * 提取真实歌名（返回 Pair<歌名, 是否为显式可靠歌名>）：
         * 1. 优先读取 METADATA_KEY_DISPLAY_TITLE；
         * 2. 若为空且酷狗把 ARTIST 拼成了 "歌手-歌名"（中途绑定时 TITLE 已是歌词行），提取 "-" 后缀作为真实歌名；
         * 3. 均为空时用 METADATA_KEY_TITLE 兜底（新歌首帧即为纯歌名）。
         */
        fun extractDisplayTitle(
            metadata: MediaMetadata,
            rawArtist: String,
            fallbackTitle: String
        ): Pair<String, Boolean> {
            val displayTitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.trim().orEmpty()
            if (displayTitle.isNotEmpty()) {
                return displayTitle to true
            }
            val dashIdx = rawArtist.indexOf('-')
            if (dashIdx > 0 && dashIdx < rawArtist.length - 1) {
                val suffixSong = rawArtist.substring(dashIdx + 1).trim()
                if (suffixSong.isNotEmpty()) {
                    return suffixSong to true
                }
            }
            return fallbackTitle.trim() to false
        }

        fun normalizeArtist(rawArtist: String, knownSongTitle: String = ""): String {
            val trimmed = rawArtist.trim()
            if (trimmed.isEmpty()) return ""
            if (knownSongTitle.isNotEmpty()) {
                val suffix = "-$knownSongTitle"
                if (trimmed.endsWith(suffix) && trimmed.length > suffix.length) {
                    val stripped = trimmed.substring(0, trimmed.length - suffix.length).trim()
                    if (stripped.isNotEmpty()) return stripped
                }
            }
            val dashIdx = trimmed.indexOf('-')
            if (dashIdx <= 0) return trimmed
            val prefix = trimmed.substring(0, dashIdx).trim()
            return prefix.ifEmpty { trimmed }
        }
    }
}
