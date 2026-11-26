package com.vivo.islandlyric.util

import com.highcapable.yukihookapi.hook.log.YLog
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 标准 LRC 时间轴解析、制作人/歌名变体行过滤与文本模糊匹配工具（v1.2.7）：
 * - 扩充括号剥离、前缀/版本后缀、前奏窗包含匹配，确保酷狗歌名行变体与词曲行永不进右侧歌词区。
 */
object LrcTimelineParser {

    data class WordSpan(
        val startOffsetMs: Long,
        val durationMs: Long,
        val text: String
    )

    data class SubLineSlice(
        val words: List<WordSpan>,
        val sliceStartOffsetMs: Long,
        val sliceEndOffsetMs: Long,
        val startWordIndex: Int = 0,
        val endWordIndex: Int = -1,
        val hasRemainingAfterSlice: Boolean = false,
        val remainderText: String = ""
    )

    data class LyricLine(
        val startMs: Long,
        val text: String,
        val isCredit: Boolean,
        val isEmptyMarker: Boolean,
        val durationMs: Long = -1L,
        val words: List<WordSpan> = emptyList(),
        // v1.4.5-fix9：行内静默段推得的复唱点（相对行起点的偏移，0=无）；startMs 恒为原始值，消费端按需用 effectiveStartMs()
        val vocalResumeOffsetMs: Long = 0L,
        val resumeRule: String = ""
    ) {
        fun effectiveStartMs(): Long = startMs + vocalResumeOffsetMs
    }

    data class ParsedTimeline(
        val allLines: List<LyricLine>,
        val vocalLines: List<LyricLine>,
        val firstVocalMs: Long,
        val isInstrumental: Boolean,
        val hasWordTiming: Boolean = false
    )

    private val TIME_TAG_REGEX = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val KRC_LINE_REGEX = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val KRC_WORD_REGEX = Regex("<(\\d+),(\\d+),\\d+>([^<]*)")

    // v1.4.5-fix6 护栏 8：声部标记白名单（男：/女：/合：）始终按真歌词放行
    private val VOCAL_PART_WHITELIST_REGEX = Regex("^\\s*(男|女|合|合唱)\\s*[:：]")

    // v1.4.5-fix6 护栏 9：保留旧版（v1.4.5-fix5）前缀正则用于双跑 [CREDIT_RULE_DIFF] 诊断比对
    private val LEGACY_CREDIT_PREFIX_REGEX = Regex(
        "^\\s*(作?词|作?曲|编曲|制作人?|音乐总监|乐队总监|艺术总监|总导演|导演|乐器技师|技师|调音师?|音响师?|灯光师?|舞台|现场混音|混音师?|缩混|母带师?|录音师?|演唱|原唱|翻唱|和声|和音|伴唱|吉他|贝斯|鼓手?|打击乐|弦乐|大提琴|小提琴|中提琴|提琴|钢琴|键盘|合成器|长笛|萨克斯|小号|长号|圆号|二胡|古筝|琵琶|笛子|箫|唢呐|手风琴|口琴|程序|PGM|Program|监制|出品|策划|统筹|宣发|发行|版权|词曲|词/曲|词\\\\曲|OP|SP|Lyricist|Composer|Arranger|Producer|Mixed|Mastered|Vocal|Bass|Guitar|Drums?|Keyboards?|Strings?|Cello|Violin|Conductor|Director|Engineer|Technician|Mix|Studio)\\s*[:：\\-]",
        RegexOption.IGNORE_CASE
    )

    private const val CREDIT_PREFIX_TERMS =
        "作?词|作?曲|编曲制作|编曲|音乐制作人?|执行制作人?|联合制作人?|配唱制作人?|制作团队|制作公司|制作统筹|制作人?|制作|音乐总监|乐队总监|艺术总监|总导演|导演|乐器技师|技师|调音师?|音响师?|灯光师?|舞台|现场混音|分轨混音|后期混音|混音工程师?|混音师?|混音室|混音棚|缩混师?|母带处理|母带工程师?|母带制作|母带师?|母带室|母带棚|母带|录音工程师?|录音师?|录音室|录音棚|后期制作|后期处理|后期|音频后期|音频编辑|修音|杜比全景声|全景声|分轨|演唱|主唱|原唱|翻唱|合唱|配唱|声乐指导|人声编辑|人声录制|人声录音|人声处理|人声|和声编写|和音编写|和声设计|和声演唱|和声录制|合声编写|合声|合音|和声|和音|伴唱|伴奏|吉他编写|吉他演奏|吉他录音|吉他录制|木吉他|电吉他|箱琴|吉他|贝斯编写|贝斯演奏|贝斯录制|贝斯|鼓编写|鼓演奏|架子鼓|爵士鼓|鼓手?|打击乐|弦乐编写|弦乐演奏|弦乐监制|弦乐团|弦乐录制|弦乐|大提琴|小提琴|中提琴|提琴|钢琴演奏|钢琴录制|钢琴|键盘演奏|键盘|合成器|长笛|萨克斯|小号|长号|圆号|二胡|古筝|琵琶|笛子|箫|唢呐|手风琴|口琴|采样自?|程序|PGM|Program|总监制|执行监制|监制|总出品|联合出品|出品人|出品方|出品公司|出品|总策划|策划|企划营销|企划宣传|企划|营销推广|营销|推广|宣传推广|宣传|项目统筹|艺人统筹|音乐统筹|统筹|宣发|联合发行|发行方|发行公司|发行|词曲版权|版权运营|版权方|版权|视觉设计|封面设计|文案策划|文案|词曲|词/曲|词\\\\曲|OP/SP|OP\\\\SP|OP|SP|Lyricist|Composer|Arranger|Arrangement|Vocal Producer|Executive Producer|Co-Producer|Producer|Backing Vocals?|Recording(?: Studio| Engineer)?|Mixing(?: Studio| Engineer)?|Mastering(?: Studio| Engineer)?|Sound Engineer|Audio Engineer|Written by|Composed by|Lyrics(?: by)?|Music(?: by)?|Produced by|Recorded(?: at| by)?|Mixed(?: by)?|Mastered(?: by)?|Vocal|Bass|Guitar|Drums?|Keyboards?|Strings?|Cello|Violin|Conductor|Director|Engineer|Technician|Mix|Studio|Publisher|Copyright|Label|Marketing|Promotion"

    // v1.4.5-fix6 护栏 7：全曲只认冒号分隔；连字符 '-' 仅在宽窗口内生效
    private val CREDIT_PREFIX_COLON_REGEX = Regex("^\\s*($CREDIT_PREFIX_TERMS)\\s*[:：]", RegexOption.IGNORE_CASE)
    private val CREDIT_PREFIX_HYPHEN_REGEX = Regex("^\\s*($CREDIT_PREFIX_TERMS)\\s*\\-", RegexOption.IGNORE_CASE)

    // v1.3.10：曲首通用短演职员行（2~10字职务 + 冒号 + 人名/团队名），仅在前奏宽窗口启用，避免误杀正歌含冒号句子
    private val INTRO_COLON_CREDIT_REGEX = Regex(
        "^\\s*[\\u4e00-\\u9fffA-Za-z0-9/· ]{2,10}[:：]\\s*[^，。！？,.!?]{1,24}$"
    )

    // v1.4.5-fix6 护栏 8：空格规则收窄——仅真职务词；乐器与泛名词（吉他/贝斯/弦乐/和声/视觉/文案/宣传）须带编写/演奏/录制/设计后缀
    private val INTRO_SPACE_CREDIT_REGEX = Regex(
        "^\\s*(作词|作曲|词曲|编曲制作|编曲|制作人|音乐制作人|执行制作人|联合制作人|配唱制作人|配唱|声乐指导|和声编写|和音编写|合声编写|和声演唱|和声录制|和声设计|混音工程师?|混音师?|混音室|混音棚|录音工程师?|录音师?|录音室|录音棚|母带处理|母带工程师?|母带制作|母带师?|缩混师?|后期制作|后期处理|监制|总监制|执行监制|出品人|出品方|出品公司|出品|企划营销|企划宣传|企划|宣发|发行方|发行公司|发行|统筹|项目统筹|艺人统筹|音乐统筹|原唱|演唱|主唱|吉他编写|吉他演奏|吉他录制|吉他录音|贝斯编写|贝斯演奏|贝斯录制|鼓手|鼓编写|鼓演奏|弦乐编写|弦乐演奏|弦乐录制|弦乐监制|钢琴演奏|钢琴录制|键盘演奏|视觉设计|封面设计|文案策划|文案设计|宣传推广|OP/SP|OP|SP|Lyricist|Composer|Arranger|Producer|Mixed by|Mastered by|Recorded by|Recorded at|Produced by|Written by|Composed by|Lyrics by|Music by|Presented by)\\s+[^，。！？,.!?]{1,24}$",
        RegexOption.IGNORE_CASE
    )

    // v1.4.5-fix6 护栏 7：全曲无冒号版权/公司全称表（已移出"未经许可/未经授权"防误伤正歌）
    private val CREDIT_CONTAINS_GLOBAL_REGEX = Regex(
        "(版权所有|著作权人|商用授权|禁止翻唱|工作室出品|音乐出品|唱片出品|联合出品|文化传媒|娱乐有限公司|音乐有限公司|传媒有限公司|All rights reserved)",
        RegexOption.IGNORE_CASE
    )

    // v1.4.5-fix6 护栏 7："未经许可/未经授权"仅在宽窗口内或与著作权/翻唱/商用等共现时生效
    private val CREDIT_UNAUTH_REGEX = Regex("(未经许可|未经授权)")
    private val CREDIT_UNAUTH_COOCCUR_REGEX = Regex("(著作权|翻唱|商用|转载)")

    private val INSTRUMENTAL_REGEX = Regex("(纯音乐|请欣赏|无歌词|instrumental)", RegexOption.IGNORE_CASE)

    private val BRACKET_CONTENT_REGEX = Regex("[(\\[（【][^)\\]）】]{1,40}[)\\]）】]")

    private val VERSION_SUFFIX_REGEX = Regex(
        "^(live|remix|dj|ktv|inst|instrumental|ver|version|edit|acoustic|cover|伴奏|纯音乐|完整版|现场版|合唱版|专辑版|单曲版|重制版|原版|版)+$",
        RegexOption.IGNORE_CASE
    )

    fun stripBrackets(raw: String): String {
        return BRACKET_CONTENT_REGEX.replace(raw, "").trim()
    }

    fun isCreditOrNonVocalLine(
        rawText: String,
        songTitle: String = "",
        artist: String = "",
        withinTrackIntroWindow: Boolean = false,
        withinColonCreditWindow: Boolean = withinTrackIntroWindow,
        allowTitleRules: Boolean = true
    ): Boolean {
        val text = rawText.trim()
        if (text.isEmpty()) return true
        // 护栏 8：声部标记（男：/女：/合：）白名单直接放行
        if (VOCAL_PART_WHITELIST_REGEX.containsMatchIn(text)) return false
        if (CREDIT_PREFIX_COLON_REGEX.containsMatchIn(text)) return true
        if (withinColonCreditWindow && CREDIT_PREFIX_HYPHEN_REGEX.containsMatchIn(text)) return true
        if (CREDIT_CONTAINS_GLOBAL_REGEX.containsMatchIn(text)) return true
        if (CREDIT_UNAUTH_REGEX.containsMatchIn(text) &&
            (withinColonCreditWindow || CREDIT_UNAUTH_COOCCUR_REGEX.containsMatchIn(text))
        ) return true
        if (withinColonCreditWindow && text.length <= 32 &&
            (INTRO_COLON_CREDIT_REGEX.matches(text) || INTRO_SPACE_CREDIT_REGEX.matches(text))
        ) return true
        if (INSTRUMENTAL_REGEX.containsMatchIn(text)) return true

        val normText = normalizeForMatch(text)
        val normTextCore = normalizeForMatch(stripBrackets(text))
        val normSong = normalizeForMatch(songTitle)
        val normSongCore = normalizeForMatch(stripBrackets(songTitle))
        val normArtist = normalizeForMatch(artist)

        // v1.4.5-fix9 P0-1：歌名相等/组合/前缀诸分支只许在头部窗口（前奏/冒号窗口）内生效——窗口外文本=歌名的行是副歌不是名单行（《空空如也》曾被吞 6 行）；parseKrc 另以两遍法＋名单块存在性收紧，见 parseKrc
        if (normSong.isNotEmpty() && allowTitleRules && (withinColonCreditWindow || withinTrackIntroWindow)) {
            if (normText == normSong || (normSongCore.isNotEmpty() && normTextCore == normSongCore)) {
                return true
            }
            if (normArtist.isNotEmpty()) {
                val combo1 = "$normSong$normArtist"
                val combo2 = "$normArtist$normSong"
                if (normText == combo1 || normText == combo2) return true
                if (normSongCore.isNotEmpty()) {
                    val coreCombo1 = "$normSongCore$normArtist"
                    val coreCombo2 = "$normArtist$normSongCore"
                    if (normTextCore == coreCombo1 || normTextCore == coreCombo2) return true
                }
            }

            // 前缀 + 歌手/版本后缀识别（如 "城市稻草人 - 曹方 (Live)" / "城市稻草人 (完整版)"）
            val baseSong = normSongCore.ifEmpty { normSong }
            if (baseSong.length >= 2 && normTextCore.startsWith(baseSong)) {
                val rem = normTextCore.removePrefix(baseSong)
                if (rem.isEmpty() || rem == normArtist || VERSION_SUFFIX_REGEX.matches(rem)) {
                    return true
                }
                if (normArtist.isNotEmpty() && rem.startsWith(normArtist)) {
                    val afterArtist = rem.removePrefix(normArtist)
                    if (afterArtist.isEmpty() || VERSION_SUFFIX_REGEX.matches(afterArtist)) {
                        return true
                    }
                }
            }

            // 前奏窗口（按 curPos 判）包含完整歌名且总长接近歌名+歌手 -> 按歌名行拦截
            if (withinTrackIntroWindow && baseSong.length >= 2 && normText.contains(baseSong)) {
                val maxTitleLineLen = baseSong.length + normArtist.length + 12
                if (normText.length <= maxTitleLineLen) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * v1.4.5-fix11：头部「歌手 - 歌名 / 歌名 - 歌手」组合行直判，不依赖 fix9 两遍法的名单块门控。
     * fix9 回归实锤（《曾经是情侣 (Live)》）：组合行恰为全曲第一行时，第一遍（禁歌名规则）判其为人声、
     * firstVocalIdx=0 使第二遍永不触发，58.7s 前奏被打穿至 0.21s。组合形（归一化后与 artist+title /
     * title+artist 全等，含去括号核心形）不是可唱歌词、只可能是 KRC 头部标题行，故在头部窗口内直判；
     * 裸歌名相等/前缀/包含诸分支仍走两遍门控，避免误吞「首句即歌名、无名单块」的真首句与副歌。
     * 注：normalizeForMatch 已剥全部空白/标点并转小写，空格与全半角差异天然归一，无需另设归一表。
     */
    private fun isArtistTitleComboHeader(
        rawText: String,
        songTitle: String,
        artist: String,
        withinWindow: Boolean
    ): Boolean {
        if (!withinWindow) return false
        val normSong = normalizeForMatch(songTitle)
        val normArtist = normalizeForMatch(artist)
        if (normSong.isEmpty() || normArtist.isEmpty()) return false
        val normText = normalizeForMatch(rawText)
        if (normText.isEmpty()) return false
        val hit = when (normText) {
            normArtist + normSong -> "artist_title"
            normSong + normArtist -> "title_artist"
            else -> {
                val normSongCore = normalizeForMatch(stripBrackets(songTitle))
                val normTextCore = normalizeForMatch(stripBrackets(rawText))
                when {
                    normSongCore.isNotEmpty() && normTextCore == normArtist + normSongCore -> "artist_titlecore"
                    normSongCore.isNotEmpty() && normTextCore == normSongCore + normArtist -> "titlecore_artist"
                    else -> null
                }
            }
        } ?: return false
        val msg = "[CREDIT_COMBO] hit=$hit text=${LogFormat.preview(rawText, 24)}"
        YLog.info(msg = msg)
        EventHistoryStore.recordDiagOnly(msg)
        return true
    }

    /** v1.4.5-fix6 护栏 9：旧规则对照实现，仅用于解析期 [CREDIT_RULE_DIFF] 诊断日志 */
    private fun isCreditOrNonVocalLineLegacy(
        rawText: String,
        songTitle: String,
        artist: String,
        withinTrackIntroWindow: Boolean
    ): Boolean {
        val text = rawText.trim()
        if (text.isEmpty()) return true
        if (LEGACY_CREDIT_PREFIX_REGEX.containsMatchIn(text)) return true
        if (withinTrackIntroWindow && text.length <= 32 && INTRO_COLON_CREDIT_REGEX.matches(text)) return true
        if (INSTRUMENTAL_REGEX.containsMatchIn(text)) return true
        val normText = normalizeForMatch(text)
        val normTextCore = normalizeForMatch(stripBrackets(text))
        val normSong = normalizeForMatch(songTitle)
        val normSongCore = normalizeForMatch(stripBrackets(songTitle))
        val normArtist = normalizeForMatch(artist)
        if (normSong.isNotEmpty()) {
            if (normText == normSong || (normSongCore.isNotEmpty() && normTextCore == normSongCore)) return true
            if (normArtist.isNotEmpty()) {
                if (normText == "$normSong$normArtist" || normText == "$normArtist$normSong") return true
                if (normSongCore.isNotEmpty() &&
                    (normTextCore == "$normSongCore$normArtist" || normTextCore == "$normArtist$normSongCore")
                ) return true
            }
            val baseSong = normSongCore.ifEmpty { normSong }
            if (baseSong.length >= 2 && normTextCore.startsWith(baseSong)) {
                val rem = normTextCore.removePrefix(baseSong)
                if (rem.isEmpty() || rem == normArtist || VERSION_SUFFIX_REGEX.matches(rem)) return true
                if (normArtist.isNotEmpty() && rem.startsWith(normArtist)) {
                    val afterArtist = rem.removePrefix(normArtist)
                    if (afterArtist.isEmpty() || VERSION_SUFFIX_REGEX.matches(afterArtist)) return true
                }
            }
            if (withinTrackIntroWindow && baseSong.length >= 2 && normText.contains(baseSong)) {
                if (normText.length <= baseSong.length + normArtist.length + 12) return true
            }
        }
        return false
    }

    /** v1.4.5-fix6 护栏 9：新旧演职员过滤规则双跑差异日志 */
    private fun logCreditRuleDiffIfNeeded(
        parsed: List<LyricLine>,
        newVocalLines: List<LyricLine>,
        newFirstVocalMs: Long,
        songTitle: String,
        artist: String
    ) {
        var oldVocalCount = 0
        var oldFirstVocalMs = -1L
        val newlyFiltered = ArrayList<String>(3)
        val newlyReleased = ArrayList<String>(3)
        for (item in parsed) {
            if (item.isEmptyMarker) continue
            val oldCredit = isCreditOrNonVocalLineLegacy(
                item.text,
                songTitle,
                artist,
                withinTrackIntroWindow = item.startMs <= 15_000L
            )
            if (!oldCredit) {
                if (oldFirstVocalMs < 0L) oldFirstVocalMs = item.startMs
                oldVocalCount++
            }
            if (!oldCredit && item.isCredit && newlyFiltered.size < 3) {
                newlyFiltered.add(LogFormat.preview(item.text, 20))
            } else if (oldCredit && !item.isCredit && newlyReleased.size < 3) {
                newlyReleased.add(LogFormat.preview(item.text, 20))
            }
        }
        if (oldFirstVocalMs != newFirstVocalMs || oldVocalCount != newVocalLines.size) {
            val diffMsg = "[CREDIT_RULE_DIFF] track=${LogFormat.preview(songTitle, 24)} " +
                "oldFirst=$oldFirstVocalMs newFirst=$newFirstVocalMs " +
                "oldVocals=$oldVocalCount newVocals=${newVocalLines.size} " +
                "filtered=$newlyFiltered released=$newlyReleased"
            YLog.info(msg = diffMsg)
            EventHistoryStore.recordDiagOnly(diffMsg)
        }
    }

    /**
     * v1.4.5-fix6 层 3 + 护栏 5：
     * - 仅在 curPosMs >= 0 且处于前奏位置窗内由调用方触发；
     * - 归一化文本全等命中 allLines 中的 isCredit=true 行即拦；
     * - 同文本真歌词豁免（±4s）统一按 (v.startMs + offsetMs) 比较。
     */
    fun isMarkedCreditInTimeline(
        timeline: ParsedTimeline,
        rawText: String,
        curPosMs: Long,
        offsetMs: Long
    ): Boolean {
        if (curPosMs < 0L) return false
        val norm = normalizeForMatch(rawText)
        if (norm.isEmpty()) return false
        val matchesCredit = timeline.allLines.any { line ->
            line.isCredit && normalizeForMatch(line.text) == norm
        }
        if (!matchesCredit) return false
        val nearRealVocal = timeline.vocalLines.any { v ->
            normalizeForMatch(v.text) == norm &&
                kotlin.math.abs((v.startMs + offsetMs) - curPosMs) <= 4_000L
        }
        if (nearRealVocal) return false
        return true
    }

    fun looksLikeKrc(raw: String): Boolean {
        if (raw.isBlank()) return false
        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val m = KRC_LINE_REGEX.matchEntire(line) ?: continue
            if (KRC_WORD_REGEX.containsMatchIn(m.groupValues[3])) {
                return true
            }
        }
        return false
    }

    /** v1.3.0：自动识别酷狗 KRC 逐字或标准 LRC 逐行格式，无逐字自动回退 LRC */
    fun parseAuto(
        raw: String,
        songTitle: String = "",
        artist: String = "",
        flaggedInstrumental: Boolean = false
    ): ParsedTimeline {
        if (flaggedInstrumental) {
            return ParsedTimeline(emptyList(), emptyList(), -1L, isInstrumental = true, hasWordTiming = false)
        }
        if (looksLikeKrc(raw)) {
            val krcParsed = parseKrc(raw, songTitle, artist)
            if (krcParsed.vocalLines.isNotEmpty() || krcParsed.isInstrumental) {
                return krcParsed
            }
        }
        return parseLrc(raw, songTitle, artist, flaggedInstrumental = false)
    }

    fun parseKrc(
        rawKrc: String,
        songTitle: String = "",
        artist: String = ""
    ): ParsedTimeline {
        val parsed = ArrayList<LyricLine>(64)
        for (rawLine in rawKrc.lineSequence()) {
            val line = rawLine.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            val m = KRC_LINE_REGEX.matchEntire(line) ?: continue
            val startMs = m.groupValues[1].toLongOrNull() ?: continue
            val durMs = m.groupValues[2].toLongOrNull() ?: -1L
            val body = m.groupValues[3]

            val words = ArrayList<WordSpan>(16)
            val textSb = StringBuilder(body.length)
            for (wm in KRC_WORD_REGEX.findAll(body)) {
                val wStart = wm.groupValues[1].toLongOrNull() ?: continue
                val wDur = (wm.groupValues[2].toLongOrNull() ?: 0L).coerceAtLeast(0L)
                val wText = wm.groupValues[3]
                if (wText.isEmpty()) continue
                words.add(WordSpan(startOffsetMs = wStart, durationMs = wDur, text = wText))
                textSb.append(wText)
            }
            val textPart = textSb.toString().trim()
            val isEmpty = textPart.isEmpty()
            // v1.4.5-fix9：F1 的 startMs 改写整体废止——startMs/durationMs/words 恒为原始值；
            // 行内静默改由分类后的 computeVocalResumeOffsets 以 vocalResumeOffsetMs 表达（见下方）
            parsed.add(
                LyricLine(
                    startMs = startMs,
                    text = textPart,
                    isCredit = false,
                    isEmptyMarker = isEmpty,
                    durationMs = durMs,
                    words = if (isEmpty) emptyList() else words
                )
            )
        }
        parsed.sortBy { it.startMs }
        // v1.4.5-fix9 P0-1 两遍分类：第一遍禁用歌名规则定出首个人声行与头部块；
        // 第二遍仅当头部块里确有非歌名规则认定的名单行（名单块真实存在）时，才对块内未定行启用歌名规则——
        // 这样副歌里文本=歌名的行不会被吞，「开场第一句即歌名、无名单块」的歌也不会误吞首句
        var seenVocalKrc = false
        for (i in parsed.indices) {
            val item = parsed[i]
            if (item.isEmptyMarker) continue
            val isCredit = isCreditOrNonVocalLine(
                item.text,
                songTitle,
                artist,
                withinTrackIntroWindow = !seenVocalKrc && item.startMs <= 15_000L,
                withinColonCreditWindow = !seenVocalKrc && item.startMs <= 45_000L,
                allowTitleRules = false
            ) || isArtistTitleComboHeader(
                item.text,
                songTitle,
                artist,
                withinWindow = !seenVocalKrc && item.startMs <= 45_000L
            )
            if (isCredit) {
                parsed[i] = item.copy(isCredit = true, words = emptyList())
            } else {
                seenVocalKrc = true
            }
        }
        val firstVocalIdxKrc = parsed.indexOfFirst { !it.isEmptyMarker && !it.isCredit }
        if (firstVocalIdxKrc > 0) {
            val headHasCreditBlock = (0 until firstVocalIdxKrc).any { parsed[it].isCredit }
            if (headHasCreditBlock) {
                for (i in 0 until firstVocalIdxKrc) {
                    val item = parsed[i]
                    if (item.isEmptyMarker || item.isCredit) continue
                    val isCredit = isCreditOrNonVocalLine(
                        item.text,
                        songTitle,
                        artist,
                        withinTrackIntroWindow = item.startMs <= 15_000L,
                        withinColonCreditWindow = item.startMs <= 45_000L,
                        allowTitleRules = true
                    )
                    if (isCredit) {
                        parsed[i] = item.copy(isCredit = true, words = emptyList())
                    }
                }
            }
        }
        val vocalLinesRaw = parsed.filter { !it.isEmptyMarker && !it.isCredit }
        // v1.4.5-fix9 P1：行内静默段 → vocalResumeOffsetMs（startMs 不动，消费端用 effectiveStartMs）
        val vocalLines = computeVocalResumeOffsets(vocalLinesRaw, parsed)
        val firstVocalMs = vocalLines.firstOrNull()?.effectiveStartMs() ?: -1L
        logCreditRuleDiffIfNeeded(parsed, vocalLines, firstVocalMs, songTitle, artist)
        val instrumental = vocalLines.isEmpty() && parsed.any { INSTRUMENTAL_REGEX.containsMatchIn(it.text) }
        val hasWordTiming = vocalLines.any { it.words.isNotEmpty() }
        return ParsedTimeline(parsed, vocalLines, firstVocalMs, instrumental, hasWordTiming = hasWordTiming)
    }

    // v1.4.5-fix9 P1：静默段统一模型。把每行词序列还原为发声/静默段，静默段四型：
    // a 空白 token dur>=2000；b 词间偏移空洞>=2000；c 行首偏移>=500（原 F1 迁入，不再改写 startMs）；
    // d 实词超长（非行尾、dur>=3000 且 >=6×行内其余实词时长中位数；词 index 0 须同文本旁证）。
    // 只对「间奏后首行」（与前一发声结束间隔>=7000）与「全曲首个人声行」计算；首行 d 型同样须旁证。
    private data class SilenceSeg(val start: Long, val end: Long, val rule: String, val wordIndex: Int)

    private fun medianOfOthers(words: List<WordSpan>, excludeIdx: Int): Long {
        val durs = words.filterIndexed { j, w -> j != excludeIdx && w.text.isNotBlank() }
            .map { it.durationMs }.sorted()
        return if (durs.isEmpty()) 0L else durs[durs.size / 2]
    }

    private fun sameTextCorroborated(
        line: LyricLine,
        wordIdx: Int,
        byText: Map<String, List<LyricLine>>
    ): Boolean {
        val key = normalizeForMatch(line.text)
        val selfDur = line.words.getOrNull(wordIdx)?.durationMs ?: return false
        return byText[key]?.any { other ->
            other !== line && other.words.getOrNull(wordIdx)?.let { it.durationMs * 4L < selfDur } == true
        } == true
    }

    private fun silenceSegmentsOf(line: LyricLine): List<SilenceSeg> {
        val words = line.words
        if (words.size < 2) return emptyList()
        val segs = ArrayList<SilenceSeg>(4)
        val firstOff = words[0].startOffsetMs
        if (firstOff >= 500L) segs.add(SilenceSeg(0L, firstOff, "c", 0))
        for (i in words.indices) {
            val w = words[i]
            val wEnd = w.startOffsetMs + w.durationMs
            if (w.text.isBlank()) {
                if (w.durationMs >= 2000L) segs.add(SilenceSeg(w.startOffsetMs, wEnd, "a", i))
            } else if (i < words.size - 1 && w.durationMs >= 3000L) {
                val median = medianOfOthers(words, i)
                if (median > 0L && w.durationMs >= 6L * median) {
                    segs.add(SilenceSeg(w.startOffsetMs, wEnd, if (i == 0) "d0" else "d", i))
                }
            }
            if (i < words.size - 1) {
                val gap = words[i + 1].startOffsetMs - wEnd
                if (gap >= 2000L) segs.add(SilenceSeg(wEnd, words[i + 1].startOffsetMs, "b", i))
            }
        }
        return segs.sortedBy { it.start }
    }

    private fun computeVocalResumeOffsets(
        vocalLines: List<LyricLine>,
        allLines: List<LyricLine>
    ): List<LyricLine> {
        if (vocalLines.isEmpty()) return vocalLines
        val byText = HashMap<String, MutableList<LyricLine>>()
        for (l in vocalLines) {
            byText.getOrPut(normalizeForMatch(l.text)) { ArrayList() }.add(l)
        }
        val timelineForEnd = ParsedTimeline(allLines, vocalLines, -1L, false)
        return vocalLines.mapIndexed { idx, line ->
            val isFirstVocal = idx == 0
            if (line.words.size < 2) return@mapIndexed line
            var segs = silenceSegmentsOf(line)
            if (segs.isEmpty()) return@mapIndexed line
            // d0（首词超长）与首行 d 型：无同文本旁证一律剔除
            segs = segs.filter { seg ->
                when {
                    seg.rule == "d0" -> sameTextCorroborated(line, seg.wordIndex, byText)
                    isFirstVocal && seg.rule == "d" -> sameTextCorroborated(line, seg.wordIndex, byText)
                    else -> true
                }
            }
            if (segs.isEmpty()) return@mapIndexed line
            val first = segs[0]
            // 前提：静默段行内起点 <=1500 且其前发声词 <=2 个
            val soundingBefore = line.words.count { it.text.isNotBlank() && it.startOffsetMs < first.start }
            if (first.start > 1500L || soundingBefore > 2) return@mapIndexed line
            var resume = first.end
            val mergedRules = StringBuilder(first.rule)
            for (k in 1 until segs.size) {
                val s = segs[k]
                if (s.start <= resume + 1L) {
                    if (s.end > resume) resume = s.end
                    mergedRules.append('+').append(s.rule)
                } else break
            }
            // Quick M-3：资格按「有效间隔」判（先算候选 resume 再判）——行起点紧贴上行、间奏全塞行内的行同样命中
            if (!isFirstVocal) {
                val prevEnd = estimateVocalEndMs(timelineForEnd, idx - 1)
                if (prevEnd <= 0L || line.startMs + resume - prevEnd < 7000L) return@mapIndexed line
            }
            // 安全约束：平移 <=15000；复唱点之后必须还有发声词（Quick M-2：防尾部空白把整行吞掉）；
            // 复唱点 <= 行时长 - 剩余发声词数*100（行时长未知时以末词结束时刻代）
            if (resume <= 0L || resume > 15_000L) return@mapIndexed line
            val remaining = line.words.count { it.text.isNotBlank() && it.startOffsetMs >= resume }
            if (remaining == 0) return@mapIndexed line
            val lineDurForGuard = if (line.durationMs > 0L) {
                line.durationMs
            } else {
                line.words.maxOfOrNull { it.startOffsetMs + it.durationMs } ?: 0L
            }
            if (lineDurForGuard > 0L && resume > lineDurForGuard - remaining * 100L) return@mapIndexed line
            val ruleChain = mergedRules.toString()
            val msg = "[KRC_SILENCE] lineStart=${line.startMs} resumeOffset=$resume rule=$ruleChain line=${LogFormat.preview(line.text, 16)}"
            YLog.info(msg = msg)
            EventHistoryStore.record(msg)
            line.copy(vocalResumeOffsetMs = resume, resumeRule = ruleChain)
        }
    }

    // v1.4.5-fix9 P1 显示用词表：以 effectiveStartMs 为 0 原点重排，不动原始 words。
    // Quick M-1 修正：复唱点恒为某词的起点/终点，前缀词没有可压缩的时间窗（压进 [0,400] 必与尾词重叠）——
    // 前缀词是已唱完的部分，一律折叠到 0 点瞬间点亮（dur=1ms 防零除）；尾词按 start-resume 对齐音频、单调不减
    fun compressedDisplayWords(words: List<WordSpan>, resumeOffsetMs: Long): List<WordSpan> {
        if (resumeOffsetMs <= 0L || words.isEmpty()) return words
        return words.map { w ->
            if (w.startOffsetMs < resumeOffsetMs) {
                w.copy(startOffsetMs = 0L, durationMs = 1L)
            } else {
                w.copy(startOffsetMs = w.startOffsetMs - resumeOffsetMs)
            }
        }
    }

    fun parseLrc(
        rawLrc: String,
        songTitle: String = "",
        artist: String = "",
        flaggedInstrumental: Boolean = false
    ): ParsedTimeline {
        if (flaggedInstrumental) {
            return ParsedTimeline(emptyList(), emptyList(), -1L, isInstrumental = true)
        }
        val parsed = ArrayList<LyricLine>(64)
        for (rawLine in rawLrc.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val matches = TIME_TAG_REGEX.findAll(line).toList()
            if (matches.isEmpty()) continue
            val textPart = line.substring(matches.last().range.last + 1).trim()
            val isEmpty = textPart.isEmpty()
            for (m in matches) {
                val min = m.groupValues[1].toLongOrNull() ?: continue
                val sec = m.groupValues[2].toLongOrNull() ?: continue
                val fracStr = m.groupValues[3]
                val fracMs = when (fracStr.length) {
                    0 -> 0L
                    1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                    else -> fracStr.take(3).toLongOrNull() ?: 0L
                }
                val startMs = min * 60_000L + sec * 1000L + fracMs
                parsed.add(LyricLine(startMs, textPart, false, isEmpty))
            }
        }
        parsed.sortBy { it.startMs }
        // v1.4.5-fix9 P0-1：与 parseKrc 同口径两遍分类（歌名规则仅在头部名单块内启用）
        var seenVocalLrc = false
        for (i in parsed.indices) {
            val item = parsed[i]
            if (item.isEmptyMarker) continue
            val isCredit = isCreditOrNonVocalLine(
                item.text,
                songTitle,
                artist,
                withinTrackIntroWindow = !seenVocalLrc && item.startMs <= 15_000L,
                withinColonCreditWindow = !seenVocalLrc && item.startMs <= 45_000L,
                allowTitleRules = false
            ) || isArtistTitleComboHeader(
                // v1.4.5-fix11（Quick M5）：与 parseKrc 同口径补组合行直判，LRC 回退路径同一个洞
                item.text,
                songTitle,
                artist,
                withinWindow = !seenVocalLrc && item.startMs <= 45_000L
            )
            if (isCredit) {
                parsed[i] = item.copy(isCredit = true)
            } else {
                seenVocalLrc = true
            }
        }
        val firstVocalIdxLrc = parsed.indexOfFirst { !it.isEmptyMarker && !it.isCredit }
        if (firstVocalIdxLrc > 0 && (0 until firstVocalIdxLrc).any { parsed[it].isCredit }) {
            for (i in 0 until firstVocalIdxLrc) {
                val item = parsed[i]
                if (item.isEmptyMarker || item.isCredit) continue
                val isCredit = isCreditOrNonVocalLine(
                    item.text,
                    songTitle,
                    artist,
                    withinTrackIntroWindow = item.startMs <= 15_000L,
                    withinColonCreditWindow = item.startMs <= 45_000L,
                    allowTitleRules = true
                )
                if (isCredit) {
                    parsed[i] = item.copy(isCredit = true)
                }
            }
        }
        val vocalLines = parsed.filter { !it.isEmptyMarker && !it.isCredit }
        val firstVocalMs = vocalLines.firstOrNull()?.startMs ?: -1L
        logCreditRuleDiffIfNeeded(parsed, vocalLines, firstVocalMs, songTitle, artist)
        val instrumental = vocalLines.isEmpty() && parsed.any { INSTRUMENTAL_REGEX.containsMatchIn(it.text) }
        return ParsedTimeline(parsed, vocalLines, firstVocalMs, instrumental)
    }

    /** 估算第 vocalIndex 句正歌的结束时刻（v1.3.0 优先取 KRC 尾字精确结束点，其次行时长/空行标记，最后字数公式） */
    fun estimateVocalEndMs(timeline: ParsedTimeline, vocalIndex: Int): Long {
        val current = timeline.vocalLines.getOrNull(vocalIndex) ?: return -1L
        val nextVocal = timeline.vocalLines.getOrNull(vocalIndex + 1)
        val nextStart = nextVocal?.startMs ?: Long.MAX_VALUE

        val longGapToNext = nextVocal != null && (nextStart - current.startMs) >= 12_000L
        if (current.words.isNotEmpty()) {
            var maxWordEndRel = 0L
            // v1.3.10：防止 KRC 间奏前末字异常拖尾（如单字 25~30s）吞掉长间奏倒计时
            val otherDurs = if (longGapToNext && current.words.size >= 2) {
                current.words.dropLast(1).map { it.durationMs.coerceAtLeast(1L) }.sorted()
            } else {
                emptyList()
            }
            val medianOtherDur = if (otherDurs.isNotEmpty()) {
                otherDurs[otherDurs.size / 2]
            } else {
                350L
            }
            val lastIdx = current.words.lastIndex
            for (wi in current.words.indices) {
                val w = current.words[wi]
                val effectiveDur = if (
                    longGapToNext &&
                    wi == lastIdx &&
                    w.durationMs > 6_000L &&
                    w.durationMs > medianOtherDur * 6L
                ) {
                    max(2_500L, medianOtherDur * 3L)
                } else {
                    w.durationMs
                }
                val endRel = w.startOffsetMs + effectiveDur
                if (endRel > maxWordEndRel) maxWordEndRel = endRel
            }
            if (maxWordEndRel > 0L) {
                return min(current.startMs + maxWordEndRel, nextStart)
            }
        }
        if (current.durationMs > 0L) {
            val safeDur = if (longGapToNext && current.durationMs > 12_000L) {
                val charCount = normalizeForMatch(current.text).length
                (charCount * 280L).coerceIn(2200L, 5500L)
            } else {
                current.durationMs
            }
            return min(current.startMs + safeDur, nextStart)
        }

        for (line in timeline.allLines) {
            if (line.startMs > current.startMs + 800L && line.startMs < nextStart) {
                if (line.isEmptyMarker) {
                    return line.startMs
                }
            }
        }

        val charCount = normalizeForMatch(current.text).length
        val estDur = (charCount * 280L).coerceIn(2200L, 5500L)
        return min(current.startMs + estDur, nextStart)
    }

    fun normalizeForMatch(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw.lowercase(Locale.ROOT)) {
            if (ch.isLetterOrDigit() || ch in '\u4e00'..'\u9fff') {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * v1.3.3：当酷狗蓝牙把长 KRC 整句拆成前后半行推送时，按连续字符滑窗（容忍 1 字同音差异如 的/得）
     * 从整句 WordSpan 截取当前半行对应字列表并将 startOffsetMs 归零重算，消除半行节奏慢一倍。
     */
    fun sliceWordSpansForSubLine(line: LyricLine, btLyricText: String): SubLineSlice? {
        if (line.words.isEmpty()) return null
        val btNorm = normalizeForMatch(btLyricText)
        val krcNorm = normalizeForMatch(line.text)
        if (btNorm.isEmpty() || krcNorm.isEmpty()) return null
        if (btNorm.length >= (krcNorm.length * 0.85f).toInt()) return null

        val flatSb = StringBuilder(krcNorm.length)
        val charToWordIdx = IntArray(krcNorm.length + 16)
        var flatLen = 0
        for (wi in line.words.indices) {
            val wn = normalizeForMatch(line.words[wi].text)
            for (ch in wn) {
                if (flatLen < charToWordIdx.size) {
                    flatSb.append(ch)
                    charToWordIdx[flatLen] = wi
                    flatLen++
                }
            }
        }
        val n = btNorm.length
        if (flatLen <= n) return null
        val flatStr = flatSb.toString()

        var bestStart = -1
        var bestSim = 0f
        val exactPos = flatStr.indexOf(btNorm)
        if (exactPos >= 0) {
            bestStart = exactPos
            bestSim = 1.0f
        } else if (n >= 2) {
            val maxStart = flatLen - n
            for (start in 0..maxStart) {
                var hits = 0
                for (j in 0 until n) {
                    if (flatStr[start + j] == btNorm[j]) hits++
                }
                val sim = hits.toFloat() / n.toFloat()
                if (sim > bestSim) {
                    bestSim = sim
                    bestStart = start
                }
            }
        }
        if (bestStart < 0 || bestSim < 0.70f) return null

        val wStart = charToWordIdx[bestStart]
        val wEnd = charToWordIdx[bestStart + n - 1]
        if (wStart > wEnd || (wStart == 0 && wEnd == line.words.lastIndex)) return null

        val baseOffset = line.words[wStart].startOffsetMs.coerceAtLeast(0L)
        val sliced = ArrayList<WordSpan>(wEnd - wStart + 1)
        var maxRelEnd = 0L
        for (wi in wStart..wEnd) {
            val w = line.words[wi]
            val relStart = (w.startOffsetMs - baseOffset).coerceAtLeast(0L)
            val dur = w.durationMs.coerceAtLeast(1L)
            sliced.add(WordSpan(startOffsetMs = relStart, durationMs = dur, text = w.text))
            val relEnd = relStart + dur
            if (relEnd > maxRelEnd) maxRelEnd = relEnd
        }
        if (sliced.isEmpty() || maxRelEnd <= 0L) return null
        val hasMore = wEnd < line.words.lastIndex
        val remText = if (hasMore) {
            val remSb = StringBuilder()
            for (wi in (wEnd + 1) until line.words.size) {
                remSb.append(line.words[wi].text)
            }
            remSb.toString().trim()
        } else {
            ""
        }
        return SubLineSlice(
            words = sliced,
            sliceStartOffsetMs = baseOffset,
            sliceEndOffsetMs = baseOffset + maxRelEnd,
            startWordIndex = wStart,
            endWordIndex = wEnd,
            hasRemainingAfterSlice = hasMore && remText.isNotEmpty(),
            remainderText = remText
        )
    }

    /** 轻量文本模糊匹配相似度 [0f..1f]（支持包含关系、半行滑窗同音字容错与字符重叠率） */
    fun fuzzyMatchScore(a: String, b: String): Float {
        val na = normalizeForMatch(a)
        val nb = normalizeForMatch(b)
        if (na.isEmpty() || nb.isEmpty()) return 0f
        if (na == nb) return 1f
        if (na.contains(nb) || nb.contains(na)) {
            val ratio = min(na.length, nb.length).toFloat() / max(na.length, nb.length).toFloat()
            return max(0.75f, ratio)
        }
        val shorter = if (na.length <= nb.length) na else nb
        val longer = if (na.length <= nb.length) nb else na
        if (shorter.length >= 3 && longer.length > shorter.length) {
            val n = shorter.length
            var bestWinHits = 0
            val maxStart = longer.length - n
            for (start in 0..maxStart) {
                var hits = 0
                for (j in 0 until n) {
                    if (longer[start + j] == shorter[j]) hits++
                }
                if (hits > bestWinHits) bestWinHits = hits
            }
            val bestWinRatio = bestWinHits.toFloat() / n.toFloat()
            if (bestWinRatio >= 0.70f) {
                return max(0.72f, bestWinRatio * 0.85f)
            }
        }
        val setA = HashSet<Char>(na.length)
        for (c in na) setA.add(c)
        var common = 0
        val seenB = HashSet<Char>(nb.length)
        for (c in nb) {
            if (seenB.add(c) && setA.contains(c)) {
                common++
            }
        }
        val union = setA.size + seenB.size - common
        return if (union > 0) common.toFloat() / union.toFloat() else 0f
    }

    fun normalizeCacheKey(title: String, artist: String): String {
        return "${normalizeForMatch(title)}|${normalizeForMatch(artist)}"
    }

    /** v1.4.4：生成时间轴内容指纹（行数 + 首句人声 + 各行 startMs 与文本长度哈希），用于重载身份比对 */
    fun computeTimelineContentFingerprint(timeline: ParsedTimeline): String {
        var h = 1125899906842597L
        for (line in timeline.vocalLines) {
            h = 31L * h + line.startMs
            h = 31L * h + line.text.length
        }
        return "${timeline.vocalLines.size}:${timeline.firstVocalMs}:${java.lang.Long.toHexString(h)}"
    }
}
