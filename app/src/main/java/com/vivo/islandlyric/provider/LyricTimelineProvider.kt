package com.vivo.islandlyric.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import com.vivo.islandlyric.Const
import com.vivo.islandlyric.util.LrcTimelineParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.zip.InflaterInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.abs

/**
 * 模块 App 侧在线歌词时间轴与自有诊断日志 ContentProvider（v1.2.9）：
 * - S4 致命缺陷根治：
 *   1. 清单补齐 <queries><package android:name="com.vivo.systemuiplugin" /></queries>；
 *   2. isAuthorizedCaller 优先走 getPackagesForUid(Binder.getCallingUid())，原生不受 Android 11+ 包可见性拦截；
 *   3. 鉴权失败与 httpGet 异常均返回明确错误标识（auth_denied / net_err:...），禁止静默吞错返回 null。
 * - S4 自有诊断文件：新增 METHOD_APPEND_DIAG_LOG，追加写入 filesDir/island_diag.log（上限 512KB 自动滚动截断）。
 */
class LyricTimelineProvider : ContentProvider() {

    private data class HttpOutcome(
        val body: String?,
        val errorReason: String?
    )

    private data class FetchResult(
        val hit: Boolean,
        val source: String,
        val lrc: String,
        val instrumental: Boolean,
        val networkError: Boolean = false,
        val errorDetail: String = "",
        val kugouId: String = ""
    )

    private data class KugouCandidate(
        val id: String,
        val accessKey: String,
        val song: String,
        val singer: String,
        val durationMs: Long,
        val durDiffMs: Long,
        val score: Int,
        val artistTier: Int,
        val titleTier: Int,
        val versionTagBonus: Int
    )

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return buildErrorBundle("provider_no_ctx", "context_null")
        val authErr = checkAuthorizedCaller(ctx)
        if (authErr != null) {
            appendDiagLineInternal(ctx, "[PROVIDER_AUTH_DENIED] method=$method | $authErr")
            return buildErrorBundle("auth_denied", authErr)
        }

        if (method == Const.METHOD_APPEND_DIAG_LOG) {
            val line = extras?.getString(Const.EXTRA_DIAG_LINE)?.trim().orEmpty()
            if (line.isNotEmpty()) {
                appendDiagLineInternal(ctx, line)
            }
            return Bundle().apply { putString(Const.EXTRA_STATUS, "ok") }
        }

        // v1.4.5-fix16（Quick 补件审 H-M2）：批量诊断写——一次 call 落多行，旧单行方法保留兼容
        if (method == Const.METHOD_APPEND_DIAG_LOG_BATCH) {
            val lines = extras?.getStringArrayList(Const.EXTRA_DIAG_LINES).orEmpty()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    appendDiagLineInternal(ctx, trimmed)
                }
            }
            return Bundle().apply { putString(Const.EXTRA_STATUS, "ok") }
        }

        val title = extras?.getString(Const.EXTRA_TITLE)?.trim().orEmpty()
        val artist = extras?.getString(Const.EXTRA_ARTIST)?.trim().orEmpty()
        val durationMs = extras?.getLong(Const.EXTRA_DURATION_MS, -1L) ?: -1L
        if (title.isEmpty()) return buildErrorBundle("invalid_arg", "empty_title")

        val cacheKey = LrcTimelineParser.normalizeCacheKey(title, artist)

        return when (method) {
            Const.METHOD_REJECT_TIMELINE -> {
                val scope = extras?.getString(Const.EXTRA_REJECT_SCOPE)?.trim().orEmpty()
                val kugouId = extras?.getString(Const.EXTRA_KUGOU_ID)?.trim().orEmpty()
                if (scope == Const.REJECT_SCOPE_CANDIDATE && kugouId.isNotEmpty()) {
                    rejectCandidateOnly(ctx, cacheKey, kugouId)
                    Bundle().apply { putString(Const.EXTRA_STATUS, "candidate_rejected") }
                } else {
                    rejectAndNegativeCache(ctx, cacheKey)
                    Bundle().apply { putString(Const.EXTRA_STATUS, "rejected") }
                }
            }
            METHOD_CLEAR_REJECT_COUNT -> {
                clearRejectCount(ctx, cacheKey)
                Bundle().apply { putString(Const.EXTRA_STATUS, "cleared") }
            }
            Const.METHOD_FETCH_TIMELINE -> {
                val excludeRaw = extras?.getString(Const.EXTRA_EXCLUDE_KUGOU_IDS)?.trim().orEmpty()
                val excludeIds = if (excludeRaw.isEmpty()) {
                    emptySet()
                } else {
                    excludeRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                }
                val forceLrclibOnly = extras?.getBoolean(Const.EXTRA_FORCE_LRCLIB_ONLY, false) ?: false
                handleFetchTimeline(ctx, cacheKey, title, artist, durationMs, excludeIds, forceLrclibOnly)
            }
            else -> buildErrorBundle("unknown_method", method)
        }
    }

    /**
     * S4 根治：优先使用 getPackagesForUid(callingUid) 校验调用方 UID 包含 com.vivo.systemuiplugin，
     * 并在 AndroidManifest.xml 补齐 <queries> 双重兜底；返回 null 表示通过，否则返回具体拒绝原因。
     */
    private fun checkAuthorizedCaller(ctx: Context): String? {
        val callingUid = Binder.getCallingUid()
        val pkg = callingPackage
        if (pkg != Const.PKG_SYSTEMUI_PLUGIN) {
            return "bad_pkg=$pkg,uid=$callingUid"
        }
        val uidPkgs = runCatching {
            ctx.packageManager.getPackagesForUid(callingUid)
        }.getOrNull()
        if (uidPkgs != null && uidPkgs.contains(Const.PKG_SYSTEMUI_PLUGIN)) {
            return null
        }
        val fallbackMatch = runCatching {
            val expectedUid = ctx.packageManager.getApplicationInfo(Const.PKG_SYSTEMUI_PLUGIN, 0).uid
            callingUid == expectedUid
        }.getOrElse { err ->
            return "uid_check_err=${err.javaClass.simpleName}:${err.message},uid=$callingUid"
        }
        return if (fallbackMatch) null else "uid_mismatch:uid=$callingUid,pkgs=${uidPkgs?.joinToString(",")}"
    }

    private fun handleFetchTimeline(
        ctx: Context,
        cacheKey: String,
        title: String,
        artist: String,
        durationMs: Long,
        excludeKugouIds: Set<String> = emptySet(),
        forceLrclibOnly: Boolean = false
    ): Bundle {
        val startMs = SystemClock.elapsedRealtime()
        val isFallbackCall = excludeKugouIds.isNotEmpty() || forceLrclibOnly

        // 1. 常规拉取查歌曲级负缓存（v5 键空间：6h 无词 / 1h→7d 校验门拒表）；回退拉取是耗尽流程的一环（仅常规拉取通过且偏差一致型 FAIL 后才进入），跳过负缓存检查
        if (!isFallbackCall && isNegativeCached(ctx, cacheKey)) {
            return buildResponseBundle(
                hit = false,
                source = "neg_cache",
                lrc = "",
                instrumental = false,
                elapsedMs = SystemClock.elapsedRealtime() - startMs
            )
        }

        val persistedExcluded = getRejectedKugouIds(ctx, cacheKey)
        val mergedExcluded = if (excludeKugouIds.isEmpty()) {
            persistedExcluded
        } else {
            HashSet<String>(persistedExcluded.size + excludeKugouIds.size).apply {
                addAll(persistedExcluded)
                addAll(excludeKugouIds)
            }
        }

        // 2. 查正缓存（500 条 LRU，优先命中 .krc 逐字缓存，其次 .lrc）；若正缓存对应候选已被排除或本次为回退拉取则跳过
        if (!isFallbackCall) {
            readPositiveCache(ctx, cacheKey)?.let { (cachedSrc, cachedText, cachedKugouId) ->
                if (cachedKugouId.isEmpty() || !mergedExcluded.contains(cachedKugouId)) {
                    return buildResponseBundle(
                        hit = true,
                        source = cachedSrc,
                        lrc = cachedText,
                        instrumental = false,
                        elapsedMs = SystemClock.elapsedRealtime() - startMs,
                        kugouId = cachedKugouId
                    )
                }
            }
        }

        // 3. 在线查询（v1.4.4：支持单曲回退排除已失败 kugouId，或直降 LRCLIB）
        val deadline = startMs + Const.TIMELINE_TOTAL_TIMEOUT_MS
        var kugouRes = if (forceLrclibOnly) {
            FetchResult(false, "kugou", "", false, networkError = false, errorDetail = "skipped_force_lrclib")
        } else {
            fetchFromKugou(title, artist, durationMs, deadline, mergedExcluded)
        }
        var finalRes = kugouRes
        var kugouHealthyNoMatch = !forceLrclibOnly && !kugouRes.hit && !kugouRes.networkError &&
            !kugouRes.errorDetail.contains("dur_mismatch") && !kugouRes.errorDetail.contains("all_excluded")
        var lrclibHealthyNoMatch = false
        var lrcNetErr = false
        var lrcErrDetail = ""

        if (!finalRes.hit && (forceLrclibOnly || SystemClock.elapsedRealtime() + 600L < deadline)) {
            val lrcRes = fetchFromLrclib(title, artist, durationMs, deadline)
            lrcErrDetail = lrcRes.errorDetail
            lrcNetErr = lrcRes.networkError
            if (lrcRes.hit) {
                finalRes = lrcRes
            } else if (!lrcRes.networkError && !lrcRes.errorDetail.contains("dur_mismatch")) {
                lrclibHealthyNoMatch = true
            }
        }
        if (!forceLrclibOnly && !finalRes.hit && kugouRes.networkError && SystemClock.elapsedRealtime() + 1200L < deadline) {
            kugouRes = fetchFromKugou(title, artist, durationMs, deadline, mergedExcluded)
            if (kugouRes.hit) {
                finalRes = kugouRes
            } else if (!kugouRes.networkError && !kugouRes.errorDetail.contains("dur_mismatch")) {
                kugouHealthyNoMatch = true
            }
        }
        val bothSourcesHealthyNoMatch = !isFallbackCall && kugouHealthyNoMatch && lrclibHealthyNoMatch

        val elapsed = SystemClock.elapsedRealtime() - startMs
        if (finalRes.hit && (finalRes.lrc.isNotBlank() || finalRes.instrumental)) {
            if (finalRes.lrc.isNotBlank()) {
                writePositiveCache(
                    ctx,
                    cacheKey,
                    finalRes.lrc,
                    isKrc = finalRes.source == "kugou_krc",
                    kugouId = finalRes.kugouId
                )
            }
            return buildResponseBundle(
                hit = true,
                source = finalRes.source,
                lrc = finalRes.lrc,
                instrumental = finalRes.instrumental,
                elapsedMs = elapsed,
                kugouId = finalRes.kugouId
            )
        }

        val errSummary = listOfNotNull(
            kugouRes.errorDetail.takeIf { it.isNotBlank() }?.let { "kg=$it" },
            lrcErrDetail.takeIf { it.isNotBlank() }?.let { "lrc=$it" }
        ).joinToString(",")

        // v1.4.3/v1.4.4 分级负缓存：仅常规双源均网络正常且明确无词时写 6 小时负缓存
        if (bothSourcesHealthyNoMatch) {
            writeNegativeCache(ctx, cacheKey, Const.TIMELINE_NO_LYRIC_NEG_TTL_MS)
            return buildResponseBundle(
                hit = false,
                source = "no_lyric",
                lrc = "",
                instrumental = false,
                elapsedMs = elapsed,
                errorDetail = errSummary
            )
        }

        val hadNetErr = kugouRes.networkError || lrcNetErr
        val missSrc = when {
            isFallbackCall && hadNetErr -> "fallback_net_err"
            isFallbackCall -> "fallback_miss"
            else -> "net_err"
        }
        return buildResponseBundle(
            hit = false,
            source = missSrc,
            lrc = "",
            instrumental = false,
            elapsedMs = elapsed,
            errorDetail = errSummary.ifBlank { "timeout_or_io" },
            networkError = hadNetErr
        )
    }

    private fun buildErrorBundle(source: String, detail: String): Bundle {
        return buildResponseBundle(
            hit = false,
            source = source,
            lrc = "",
            instrumental = false,
            elapsedMs = 0L,
            errorDetail = detail
        )
    }

    private fun buildResponseBundle(
        hit: Boolean,
        source: String,
        lrc: String,
        instrumental: Boolean,
        elapsedMs: Long,
        errorDetail: String = "",
        kugouId: String = "",
        networkError: Boolean = false
    ): Bundle {
        return Bundle().apply {
            putString(Const.EXTRA_STATUS, if (hit) "hit" else "miss")
            putString(Const.EXTRA_SOURCE, source)
            putString(Const.EXTRA_LRC, lrc)
            putBoolean(Const.EXTRA_INSTRUMENTAL, instrumental)
            putLong(Const.EXTRA_ELAPSED_MS, elapsedMs)
            if (errorDetail.isNotBlank()) {
                putString(Const.EXTRA_ERROR_DETAIL, errorDetail)
            }
            if (kugouId.isNotBlank()) {
                putString(Const.EXTRA_KUGOU_ID, kugouId)
            }
            if (networkError) {
                putBoolean(Const.EXTRA_NETWORK_ERROR, true)
            }
        }
    }

    // ==================== 主源：酷狗公开 LRC 接口 ====================

    private fun normalizeCompareText(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val mapped = when {
                ch in '！'..'～' -> (ch.code - 0xFEE0).toChar()
                ch == '　' -> ' '
                else -> TRAD_TO_SIMP_MAP[ch] ?: ch
            }
            sb.append(mapped)
        }
        return LrcTimelineParser.normalizeForMatch(sb.toString())
    }

    private fun extractVersionTagNormalized(raw: String): String {
        for (m in VERSION_BRACKET_REGEX.findAll(raw)) {
            val tag = normalizeCompareText(m.groupValues[1])
            if (tag.isNotEmpty() && VERSION_TAG_KNOWN_REGEX.containsMatchIn(tag)) {
                return tag
            }
        }
        return ""
    }

    private fun splitArtistSet(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        val parts = ARTIST_SPLIT_REGEX.split(raw)
        val out = HashSet<String>(parts.size)
        for (part in parts) {
            val norm = normalizeCompareText(part)
            if (norm.isNotEmpty()) out.add(norm)
        }
        return out
    }

    private fun isObjectivelyMalformedTimeline(
        rawText: String,
        title: String,
        artist: String,
        durationMs: Long
    ): Boolean {
        if (durationMs < 90_000L) return false
        val parsed = LrcTimelineParser.parseAuto(rawText, title, artist, flaggedInstrumental = false)
        return !parsed.isInstrumental && parsed.vocalLines.size < 5
    }

    private fun fetchFromKugou(
        title: String,
        artist: String,
        durationMs: Long,
        deadlineMs: Long,
        excludedKugouIds: Set<String> = emptySet()
    ): FetchResult {
        return runCatching {
            val remaining1 = (deadlineMs - SystemClock.elapsedRealtime()).toInt()
            if (remaining1 < 400) {
                return@runCatching FetchResult(false, "kugou", "", false, networkError = true, errorDetail = "budget_exhausted")
            }
            val timeout1 = remaining1.coerceAtMost(Const.TIMELINE_SINGLE_SOURCE_TIMEOUT_MS)

            val keyword = if (artist.isNotBlank()) "$artist - $title" else title
            val encodedKw = URLEncoder.encode(keyword, "UTF-8")
            val durParam = if (durationMs > 0L) "&duration=$durationMs" else ""
            val searchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=$encodedKw$durParam"

            val searchOutcome = httpGet(searchUrl, timeout1)
            val searchBody = searchOutcome.body
                ?: return@runCatching FetchResult(
                    false, "kugou", "", false, networkError = true,
                    errorDetail = searchOutcome.errorReason ?: "search_io"
                )
            val root = JSONObject(searchBody)
            val candidates = root.optJSONArray("candidates")
                ?: return@runCatching FetchResult(false, "kugou", "", false, networkError = false, errorDetail = "no_candidates")
            if (candidates.length() == 0) {
                return@runCatching FetchResult(false, "kugou", "", false, networkError = false, errorDetail = "empty_candidates")
            }

            val queryArtistNorm = normalizeCompareText(artist)
            val queryArtistSet = splitArtistSet(artist)
            val queryCoreTitleNorm = normalizeCompareText(LrcTimelineParser.stripBrackets(title))
            val queryVersionTag = extractVersionTagNormalized(title)

            val ranked = ArrayList<KugouCandidate>(candidates.length())
            var sawAnyValidCandidate = false
            var sawExcludedCandidate = false

            for (i in 0 until candidates.length()) {
                val item = candidates.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val accessKey = item.optString("accesskey")
                if (id.isEmpty() || accessKey.isEmpty()) continue
                val candDur = item.optLong("duration", -1L)
                val durDiff = if (durationMs > 0L && candDur > 0L) abs(candDur - durationMs) else 0L
                if (durationMs > 0L && candDur > 0L && durDiff > Const.TIMELINE_KUGOU_MAX_DURATION_DIFF_MS) {
                    continue
                }
                sawAnyValidCandidate = true
                if (excludedKugouIds.contains(id)) {
                    sawExcludedCandidate = true
                    continue
                }

                val candSong = item.optString("song")
                val candSinger = item.optString("singer")
                val candScore = item.optInt("score", 0)

                // F8：歌名主干完全不匹配（titleTier == -1）直接硬过滤，避免同歌手异歌排在合唱版前面
                val candCoreTitleNorm = normalizeCompareText(LrcTimelineParser.stripBrackets(candSong))
                val titleTier = when {
                    queryCoreTitleNorm.isEmpty() -> 1
                    candCoreTitleNorm == queryCoreTitleNorm -> 2
                    candCoreTitleNorm.isNotEmpty() && (candCoreTitleNorm.contains(queryCoreTitleNorm) || queryCoreTitleNorm.contains(candCoreTitleNorm)) -> 1
                    LrcTimelineParser.fuzzyMatchScore(queryCoreTitleNorm, candCoreTitleNorm) >= 0.65f -> 0
                    else -> -1
                }
                if (titleTier < 0) continue

                // F9：多歌手按分隔符拆集合比对，顺序无关
                val candArtistNorm = normalizeCompareText(candSinger)
                val candArtistSet = splitArtistSet(candSinger)
                val artistTier = when {
                    queryArtistSet.isEmpty() -> 1
                    candArtistSet == queryArtistSet || candArtistNorm == queryArtistNorm -> 2
                    candArtistSet.any { it in queryArtistSet } ||
                        (candArtistNorm.isNotEmpty() && (candArtistNorm.contains(queryArtistNorm) || queryArtistNorm.contains(candArtistNorm))) -> 1
                    else -> 0
                }

                val candVersionTag = extractVersionTagNormalized(candSong)
                val versionBonus = if (queryVersionTag.isNotEmpty() && candVersionTag.isNotEmpty() &&
                    (candVersionTag == queryVersionTag || candVersionTag.contains(queryVersionTag) || queryVersionTag.contains(candVersionTag))
                ) 1 else 0

                ranked.add(
                    KugouCandidate(
                        id = id,
                        accessKey = accessKey,
                        song = candSong,
                        singer = candSinger,
                        durationMs = candDur,
                        durDiffMs = durDiff,
                        score = candScore,
                        artistTier = artistTier,
                        titleTier = titleTier,
                        versionTagBonus = versionBonus
                    )
                )
            }

            if (ranked.isEmpty()) {
                val err = when {
                    sawExcludedCandidate -> "all_excluded"
                    !sawAnyValidCandidate -> "dur_mismatch"
                    else -> "no_valid_candidate"
                }
                return@runCatching FetchResult(false, "kugou", "", false, networkError = false, errorDetail = err)
            }

            // v1.4.4 排序：歌手精确匹配 -> 歌名主干匹配 -> 版本标签一致加分 -> 时长差小 -> 酷狗 score 打破平分
            ranked.sortWith(
                compareByDescending<KugouCandidate> { it.artistTier }
                    .thenByDescending { it.titleTier }
                    .thenByDescending { it.versionTagBonus }
                    .thenBy { it.durDiffMs }
                    .thenByDescending { it.score }
            )

            var lastErr = "no_lrc_ts"
            var sawNetErr = false
            val maxTry = ranked.size.coerceAtMost(2)
            for (idx in 0 until maxTry) {
                val cand = ranked[idx]
                val remaining2 = (deadlineMs - SystemClock.elapsedRealtime()).toInt()
                if (remaining2 < 400) {
                    return@runCatching FetchResult(false, "kugou", "", false, networkError = true, errorDetail = "dl_budget_exhausted")
                }
                val timeout2 = remaining2.coerceAtMost(Const.TIMELINE_SINGLE_SOURCE_TIMEOUT_MS)

                val krcUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=${cand.id}&accesskey=${cand.accessKey}&fmt=krc&charset=utf8"
                val krcOutcome = httpGet(krcUrl, timeout2)
                val krcBody = krcOutcome.body
                if (krcBody != null) {
                    val krcB64 = runCatching { JSONObject(krcBody).optString("content") }.getOrDefault("")
                    if (krcB64.isNotBlank()) {
                        val krcText = decodeKrcContent(krcB64)
                        if (krcText != null && LrcTimelineParser.looksLikeKrc(krcText)) {
                            if (isObjectivelyMalformedTimeline(krcText, title, artist, durationMs)) {
                                lastErr = "malformed_krc"
                                continue
                            }
                            return@runCatching FetchResult(true, "kugou_krc", krcText, false, networkError = false, kugouId = cand.id)
                        }
                    }
                } else {
                    sawNetErr = true
                    lastErr = krcOutcome.errorReason ?: "krc_dl_io"
                }

                val remaining3 = (deadlineMs - SystemClock.elapsedRealtime()).toInt()
                if (remaining3 < 400) {
                    return@runCatching FetchResult(false, "kugou", "", false, networkError = true, errorDetail = "lrc_budget_exhausted")
                }
                val timeout3 = remaining3.coerceAtMost(Const.TIMELINE_SINGLE_SOURCE_TIMEOUT_MS)
                val dlUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=${cand.id}&accesskey=${cand.accessKey}&fmt=lrc&charset=utf8"
                val dlOutcome = httpGet(dlUrl, timeout3)
                val dlBody = dlOutcome.body
                if (dlBody == null) {
                    sawNetErr = true
                    lastErr = dlOutcome.errorReason ?: "dl_io"
                    continue
                }
                val dlJson = JSONObject(dlBody)
                val contentB64 = dlJson.optString("content")
                if (contentB64.isBlank()) {
                    lastErr = "empty_b64"
                    continue
                }
                val decoded = String(Base64.decode(contentB64, Base64.DEFAULT), Charsets.UTF_8)
                if (decoded.contains("[0")) {
                    if (isObjectivelyMalformedTimeline(decoded, title, artist, durationMs)) {
                        lastErr = "malformed_lrc"
                        continue
                    }
                    return@runCatching FetchResult(true, "kugou", decoded, false, networkError = false, kugouId = cand.id)
                } else {
                    lastErr = "no_lrc_ts"
                }
            }
            FetchResult(false, "kugou", "", false, networkError = sawNetErr, errorDetail = lastErr)
        }.getOrElse { err ->
            FetchResult(false, "kugou", "", false, networkError = true, errorDetail = err.javaClass.simpleName)
        }
    }

    // ==================== 备源：LRCLIB 公开接口 ====================

    private fun fetchFromLrclib(
        title: String,
        artist: String,
        durationMs: Long,
        deadlineMs: Long
    ): FetchResult {
        return runCatching {
            val remaining = (deadlineMs - SystemClock.elapsedRealtime()).toInt()
            if (remaining < 400) {
                return@runCatching FetchResult(false, "lrclib", "", false, networkError = true, errorDetail = "budget_exhausted")
            }
            val timeout = remaining.coerceAtMost(Const.TIMELINE_SINGLE_SOURCE_TIMEOUT_MS)

            val encodedTrack = URLEncoder.encode(title, "UTF-8")
            val artistParam = if (artist.isNotBlank()) "&artist_name=${URLEncoder.encode(artist, "UTF-8")}" else ""
            val url = "https://lrclib.net/api/search?track_name=$encodedTrack$artistParam"

            val outcome = httpGet(url, timeout)
            val body = outcome.body
                ?: return@runCatching FetchResult(
                    false, "lrclib", "", false, networkError = true,
                    errorDetail = outcome.errorReason ?: "io"
                )
            val arr = JSONArray(body)
            if (arr.length() == 0) {
                return@runCatching FetchResult(false, "lrclib", "", false, networkError = false, errorDetail = "empty")
            }

            var bestLrc = ""
            var bestInstrumental = false
            var bestDiff = Long.MAX_VALUE

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val synced = obj.optString("syncedLyrics", "").trim()
                val inst = obj.optBoolean("instrumental", false)
                if (synced.isEmpty() && !inst) continue

                val candDurSec = obj.optDouble("duration", -1.0)
                val candDurMs = if (candDurSec > 0.0) (candDurSec * 1000.0).toLong() else -1L
                if (durationMs > 0L && candDurMs > 0L) {
                    val diff = abs(candDurMs - durationMs)
                    if (diff <= Const.TIMELINE_DURATION_TOLERANCE_MS && diff < bestDiff) {
                        bestDiff = diff
                        bestLrc = synced
                        bestInstrumental = inst
                    }
                } else if (durationMs <= 0L && bestDiff == Long.MAX_VALUE) {
                    bestDiff = 0L
                    bestLrc = synced
                    bestInstrumental = inst
                }
            }

            if (bestLrc.isNotEmpty() || bestInstrumental) {
                FetchResult(true, "lrclib", bestLrc, bestInstrumental, networkError = false)
            } else {
                FetchResult(false, "lrclib", "", false, networkError = false, errorDetail = "no_synced")
            }
        }.getOrElse { err ->
            FetchResult(false, "lrclib", "", false, networkError = true, errorDetail = err.javaClass.simpleName)
        }
    }

    private fun httpGet(urlStr: String, timeoutMs: Int): HttpOutcome {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "VivoIslandKugouLyric/1.3.3 (Android)")
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return HttpOutcome(null, "http_$code")
            }
            val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { br ->
                br.readText()
            }
            HttpOutcome(body, null)
        } catch (t: Throwable) {
            HttpOutcome(null, t.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    // ==================== 模块自有诊断日志文件（512KB 自动滚动） ====================

    private fun appendDiagLineInternal(ctx: Context, line: String) {
        synchronized(DIAG_FILE_LOCK) {
            runCatching {
                val file = File(ctx.filesDir, Const.DIAG_LOG_FILE_NAME)
                if (file.exists() && file.length() > Const.DIAG_LOG_MAX_BYTES) {
                    val keepBytes = (Const.DIAG_LOG_MAX_BYTES * 3) / 4
                    val allBytes = file.readBytes()
                    val startIdx = (allBytes.size - keepBytes).coerceAtLeast(0)
                    var nlIdx = startIdx
                    while (nlIdx < allBytes.size && allBytes[nlIdx] != '\n'.code.toByte()) {
                        nlIdx++
                    }
                    val trimmedStart = if (nlIdx + 1 < allBytes.size) nlIdx + 1 else startIdx
                    file.writeBytes(allBytes.copyOfRange(trimmedStart, allBytes.size))
                }
                FileOutputStream(file, true).use { fos ->
                    fos.write(line.toByteArray(Charsets.UTF_8))
                    fos.write('\n'.code)
                }
            }
        }
    }

    // ==================== 本地正负缓存（正 500 条 LRU，负 v2 分级过期） ====================

    /** 解码酷狗 Base64 KRC（4 字节 "krc1" 头 + 16 字节循环 XOR 掩码 + Zlib 解压） */
    private fun decodeKrcContent(contentB64: String): String? {
        return runCatching {
            val raw = Base64.decode(contentB64, Base64.DEFAULT)
            if (raw.size <= 4) return@runCatching null
            if (raw[0] != 0x6B.toByte() || raw[1] != 0x72.toByte() ||
                raw[2] != 0x63.toByte() || raw[3] != 0x31.toByte()
            ) {
                return@runCatching null
            }
            val payload = ByteArray(raw.size - 4)
            for (i in payload.indices) {
                payload[i] = (raw[i + 4].toInt() xor KRC_XOR_KEY[i % KRC_XOR_KEY.size].toInt()).toByte()
            }
            InflaterInputStream(ByteArrayInputStream(payload)).use { zis ->
                zis.readBytes().toString(Charsets.UTF_8).removePrefix("﻿")
            }
        }.getOrNull()
    }

    private fun cacheDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "lrc_cache_v2")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun hashKey(cacheKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cacheKey.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(32)
        for (i in 0 until 16) {
            sb.append(String.format("%02x", digest[i]))
        }
        return sb.toString()
    }

    private fun isNegativeCached(ctx: Context, cacheKey: String): Boolean {
        return runCatching {
            val sp = ctx.getSharedPreferences(PREF_NEG_CACHE, Context.MODE_PRIVATE)
            val expiryMs = sp.getLong(cacheKey, 0L)
            if (expiryMs <= 0L) return@runCatching false
            val expired = System.currentTimeMillis() >= expiryMs
            if (expired) {
                sp.edit().remove(cacheKey).apply()
                false
            } else {
                true
            }
        }.getOrDefault(false)
    }

    private fun writeNegativeCache(ctx: Context, cacheKey: String, ttlMs: Long) {
        runCatching {
            val expiryMs = System.currentTimeMillis() + ttlMs
            ctx.getSharedPreferences(PREF_NEG_CACHE, Context.MODE_PRIVATE)
                .edit()
                .putLong(cacheKey, expiryMs)
                .apply()
        }
    }

    /** v1.4.4 第 8 条：候选级拒表，仅标记 (cacheKey, kugouId) 不再被选并移除对应错版正缓存，不写歌曲级负缓存 */
    private fun rejectCandidateOnly(ctx: Context, cacheKey: String, kugouId: String) {
        runCatching {
            val now = System.currentTimeMillis()
            val sp = ctx.getSharedPreferences(PREF_NEG_CACHE, Context.MODE_PRIVATE)
            sp.edit()
                .putLong("$CAND_REJECT_PREFIX$cacheKey|$kugouId", now + Const.TIMELINE_NEGATIVE_CACHE_TTL_MS)
                .apply()
            val metaSp = ctx.getSharedPreferences(PREF_POS_META, Context.MODE_PRIVATE)
            val cachedId = metaSp.getString(cacheKey, "").orEmpty()
            if (cachedId.isEmpty() || cachedId == kugouId) {
                val h = hashKey(cacheKey)
                val dir = cacheDir(ctx)
                File(dir, "$h.krc").takeIf { it.exists() }?.delete()
                File(dir, "$h.lrc").takeIf { it.exists() }?.delete()
                metaSp.edit().remove(cacheKey).apply()
            }
        }
    }

    private fun getRejectedKugouIds(ctx: Context, cacheKey: String): Set<String> {
        return runCatching {
            val sp = ctx.getSharedPreferences(PREF_NEG_CACHE, Context.MODE_PRIVATE)
            val prefix = "$CAND_REJECT_PREFIX$cacheKey|"
            val now = System.currentTimeMillis()
            val result = HashSet<String>()
            var editor: android.content.SharedPreferences.Editor? = null
            for ((k, v) in sp.all) {
                if (!k.startsWith(prefix)) continue
                val expiry = (v as? Long) ?: 0L
                if (expiry > now) {
                    val cid = k.substring(prefix.length)
                    if (cid.isNotEmpty()) result.add(cid)
                } else {
                    if (editor == null) editor = sp.edit()
                    editor.remove(k)
                }
            }
            editor?.apply()
            result
        }.getOrDefault(emptySet())
    }

    /** v1.4.3/v1.4.4 必补项 B + P1-4：歌曲级拒表写 1 小时短 TTL；跨会话 14 天内累计 >= 2 次才写 7 天长拒表；超 14 天计数衰减重置为 1 */
    private fun rejectAndNegativeCache(ctx: Context, cacheKey: String) {
        runCatching {
            val h = hashKey(cacheKey)
            val dir = cacheDir(ctx)
            File(dir, "$h.krc").takeIf { it.exists() }?.delete()
            File(dir, "$h.lrc").takeIf { it.exists() }?.delete()
            ctx.getSharedPreferences(PREF_POS_META, Context.MODE_PRIVATE).edit().remove(cacheKey).apply()
            val now = System.currentTimeMillis()
            val sp = ctx.getSharedPreferences(PREF_NEG_CACHE, Context.MODE_PRIVATE)
            val countKey = "$REJECT_COUNT_PREFIX$cacheKey"
            val tsKey = "$REJECT_TS_PREFIX$cacheKey"
            val prevCount = sp.getInt(countKey, 0)
            val lastTs = sp.getLong(tsKey, 0L)
            val nextCount = if (lastTs > 0L && (now - lastTs) > REJECT_DECAY_MS) {
                1
            } else {
                prevCount + 1
            }
            val ttlMs = if (nextCount >= 2) {
                Const.TIMELINE_NEGATIVE_CACHE_TTL_MS
            } else {
                REJECT_SHORT_TTL_MS
            }
            val expiryMs = now + ttlMs
            sp.edit()
                .putInt(countKey, nextCount)
                .putLong(tsKey, now)
                .putLong(cacheKey, expiryMs)
                .apply()
        }
    }

    /** v1.4.3 P1-4：仅在校验门 PASS 时由 Source 通知清零拒表计数与时间戳 */
    private fun clearRejectCount(ctx: Context, cacheKey: String) {
        runCatching {
            ctx.getSharedPreferences(PREF_NEG_CACHE, Context.MODE_PRIVATE)
                .edit()
                .remove("$REJECT_COUNT_PREFIX$cacheKey")
                .remove("$REJECT_TS_PREFIX$cacheKey")
                .apply()
        }
    }

    private fun readPositiveCache(ctx: Context, cacheKey: String): Triple<String, String, String>? {
        return runCatching {
            val h = hashKey(cacheKey)
            val dir = cacheDir(ctx)
            val cachedId = ctx.getSharedPreferences(PREF_POS_META, Context.MODE_PRIVATE)
                .getString(cacheKey, "").orEmpty()
            val krcFile = File(dir, "$h.krc")
            if (krcFile.exists()) {
                krcFile.setLastModified(System.currentTimeMillis())
                val txt = krcFile.readText(Charsets.UTF_8)
                if (txt.isNotBlank()) return@runCatching Triple("cache_krc", txt, cachedId)
            }
            val lrcFile = File(dir, "$h.lrc")
            if (lrcFile.exists()) {
                lrcFile.setLastModified(System.currentTimeMillis())
                val txt = lrcFile.readText(Charsets.UTF_8)
                if (txt.isNotBlank()) return@runCatching Triple("cache", txt, cachedId)
            }
            null
        }.getOrNull()
    }

    private fun writePositiveCache(ctx: Context, cacheKey: String, lrc: String, isKrc: Boolean, kugouId: String = "") {
        runCatching {
            val dir = cacheDir(ctx)
            val h = hashKey(cacheKey)
            val ext = if (isKrc) "krc" else "lrc"
            val otherExt = if (isKrc) "lrc" else "krc"
            val file = File(dir, "$h.$ext")
            file.writeText(lrc, Charsets.UTF_8)
            // F11：写一种格式时删除同 hash 的另一种扩展名，防止 LRCLIB 写 .lrc 后残留旧错版 .krc
            File(dir, "$h.$otherExt").takeIf { it.exists() }?.delete()
            val metaEd = ctx.getSharedPreferences(PREF_POS_META, Context.MODE_PRIVATE).edit()
            if (kugouId.isNotBlank()) {
                metaEd.putString(cacheKey, kugouId)
            } else {
                metaEd.remove(cacheKey)
            }
            metaEd.apply()
            // v1.4.3 P1-4：写正缓存时仅移除负缓存过期键，保留拒表计数，直到校验门 PASS 再清零
            ctx.getSharedPreferences(PREF_NEG_CACHE, Context.MODE_PRIVATE)
                .edit()
                .remove(cacheKey)
                .apply()
            val files = dir.listFiles() ?: return@runCatching
            if (files.size > Const.TIMELINE_POSITIVE_CACHE_MAX) {
                files.sortBy { it.lastModified() }
                val toDelete = files.size - Const.TIMELINE_POSITIVE_CACHE_MAX
                for (i in 0 until toDelete) {
                    files[i].delete()
                }
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        private const val PREF_NEG_CACHE = "timeline_neg_cache_v5"
        private const val PREF_POS_META = "timeline_pos_meta_v1"
        private const val METHOD_CLEAR_REJECT_COUNT = "clear_reject_count"
        private const val REJECT_COUNT_PREFIX = "rej_cnt:"
        private const val REJECT_TS_PREFIX = "rej_ts:"
        private const val CAND_REJECT_PREFIX = "cand_rej:"
        private const val REJECT_SHORT_TTL_MS = 60L * 60L * 1000L
        private const val REJECT_DECAY_MS = 14L * 24L * 3600L * 1000L
        private val VERSION_BRACKET_REGEX = Regex("[(\\[（【]([^)\\]）】]{1,24})[)\\]）】]")
        private val VERSION_TAG_KNOWN_REGEX = Regex("(live|remix|dj|ktv|inst|instrumental|acoustic|cover|ver|version|edit|现场|伴奏|纯音乐|合唱|原版|重制|完整|专辑|单曲)", RegexOption.IGNORE_CASE)
        private val ARTIST_SPLIT_REGEX = Regex("[、，,&/+×]|\\s+feat\\.?\\s+", RegexOption.IGNORE_CASE)
        private val TRAD_TO_SIMP_MAP = mapOf(
            '愛' to '爱', '國' to '国', '夢' to '梦', '聲' to '声', '聽' to '听', '說' to '说',
            '這' to '这', '誰' to '谁', '來' to '来', '時' to '时', '間' to '间', '過' to '过',
            '還' to '还', '會' to '会', '個' to '个', '們' to '们', '爲' to '为', '為' to '为',
            '與' to '与', '無' to '无', '離' to '离', '開' to '开', '關' to '关', '點' to '点',
            '風' to '风', '雲' to '云', '電' to '电', '車' to '车', '馬' to '马', '鳥' to '鸟',
            '魚' to '鱼', '龍' to '龙', '鳳' to '凤', '花' to '花', '葉' to '叶', '樹' to '树',
            '歲' to '岁', '幾' to '几', '經' to '经', '情' to '情', '侶' to '侣', '現' to '现',
            '場' to '场', '版' to '版', '樂' to '乐', '歌' to '歌', '詞' to '词', '語' to '语',
            '記' to '记', '憶' to '忆', '見' to '见', '親' to '亲', '淚' to '泪', '歡' to '欢',
            '難' to '难', '傷' to '伤', '痛' to '痛', '對' to '对', '錯' to '错', '裏' to '里',
            '裡' to '里', '邊' to '边', '遠' to '远', '長' to '长', '門' to '门', '問' to '问',
            '題' to '题', '號' to '号', '響' to '响', '陽' to '阳', '陰' to '阴', '險' to '险'
        )
        private val KRC_XOR_KEY = byteArrayOf(
            64, 71, 97, 119, 94, 50, 116, 71,
            81, 54, 49, 45, -50, -46, 110, 105
        )
        private val DIAG_FILE_LOCK = Any()
    }
}
