package com.vivo.islandlyric.util

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import com.highcapable.yukihookapi.hook.log.YLog
import com.vivo.islandlyric.Const
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 宿主私有 SharedPreferences 持久事件环形历史 + 模块自有诊断文件跨进程落盘（v1.2.9）：
 * - 环形缓冲存储最近 200 条关键事件，每曲确认 / resetAndEnd / 非播放态 hide 回放最近 40 条（5s 节流）；
 * - 同步通过后台单线程经 LyricTimelineProvider(METHOD_APPEND_DIAG_LOG) 追加写入模块私有 island_diag.log（512KB 自动滚动），
 *   彻底摆脱 logcat 尾段窗口限制；EDGE_CHECK 摘要支持去重防刷盘。
 */
object EventHistoryStore {

    private val ring = ArrayDeque<String>(Const.EVENT_HISTORY_MAX_ENTRIES + 4)
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val diagExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "IslandDiagWriter").apply { isDaemon = true }
    }

    // fix16 性能：record() 只写内存环，SharedPreferences 持久化改为 5s 防抖 + trim/暂停补存
    @Volatile
    private var persistFuture: ScheduledFuture<*>? = null

    @Volatile
    private var persistDirty: Boolean = false
    val perfRecordCalls = AtomicInteger(0)
    val perfRecordSerializeMs = AtomicLong(0)
    val perfDiagQueueMax = AtomicInteger(0)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    private var lastThrottledReplayElapsedMs: Long = 0L

    // v1.4.5-fix16（Quick 补件审 H-M3）：去重键按 tag 分开记，A/B 两 tag 交替时不再互相顶掉
    private val lastDiagDedupKeys = HashMap<String, String>()

    // v1.4.5-fix16（Quick 补件审 H-M2＋老大定数）：诊断批量写队列——攒 5s 或满 100 行才跨进程喊一次，
    // 队列空时零调用；上限 500 行、超限丢最旧并在下一批补一行 [DIAG] dropped=N
    private val diagBatchQueue = ArrayDeque<String>()
    private var diagBatchDropped: Int = 0
    private var diagBatchFuture: ScheduledFuture<*>? = null

    /** v1.4.5-fix16：诊断级别——false（精简，默认）时 [EDGE_CHECK]/[SCROLL_DIAG] 不落盘；Renderer 按 prefs 同步 */
    @Volatile
    var detailedDiag: Boolean = false

    /** v1.4.5-fix17（老大提议）：诊断日志总开关——关时 record/recordDiagOnly 全部短路、队列丢弃；Renderer 按 prefs 同步 */
    @Volatile
    var logEnabled: Boolean = true

    // v1.4.5-fix17 实施审 M3/M4（Quick）：存储代际——clearAll 递增后，已拍快照的落盘与已取队列的
    // 批量写在写入前比对代际、不一致即弃，杜绝「清空后旧日志被在途任务写回」
    @Volatile
    private var storeGen: Int = 0

    /** v1.4.5-fix17：清空全部诊断（内存环＋批队列＋落盘快照）；文件本体由设置页同进程截断 */
    fun clearAll() {
        storeGen++
        val genAtClear = storeGen
        synchronized(this) {
            ring.clear()
            lastDiagDedupKeys.clear()
        }
        synchronized(diagBatchQueue) {
            diagBatchQueue.clear()
            diagBatchDropped = 0
        }
        persistDirty = false
        runCatching { persistFuture?.cancel(false) }
        val ctx = appContext ?: return
        // 写空与防抖落盘走同一单线程执行器串行，顺序有保证；在途落盘另有代际比对兜底（flushPersist）
        runCatching {
            diagExecutor.execute {
                if (storeGen != genAtClear) return@execute
                runCatching {
                    ctx.getSharedPreferences(Const.EVENT_HISTORY_PREF_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(Const.EVENT_HISTORY_PREF_KEY, "")
                        .apply()
                }
            }
        }
    }

    fun init(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        appContext = app
        synchronized(this) {
            if (initialized) return
            initialized = true
            runCatching {
                val sp = app.getSharedPreferences(Const.EVENT_HISTORY_PREF_NAME, Context.MODE_PRIVATE)
                val raw = sp.getString(Const.EVENT_HISTORY_PREF_KEY, null)
                if (!raw.isNullOrBlank()) {
                    for (line in raw.split('\n')) {
                        if (line.isNotBlank()) {
                            appendLocked(line)
                        }
                    }
                }
            }
        }
        // fix16：系统内存紧张时补存一次，避免进程被杀后历史全丢
        runCatching {
            app.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) flushPersist("trim_memory")
                }

                override fun onConfigurationChanged(newConfig: Configuration) {}
                override fun onLowMemory() {}
            })
        }
        replayToLog(trigger = "module_start", maxEntries = Const.EVENT_HISTORY_REPLAY_LIMIT)
    }

    fun record(eventLine: String) {
        if (!logEnabled) return
        perfRecordCalls.incrementAndGet()
        PerfStats.diagLines.incrementAndGet()
        val ts = synchronized(timeFmt) { timeFmt.format(Date()) }
        val entry = "$ts $eventLine"
        synchronized(this) { appendLocked(entry) }
        persistDirty = true
        schedulePersistDebounced()
        val ctx = appContext ?: return
        enqueueDiagWrite(ctx, entry)
    }

    /** fix16：5s 防抖持久化——最后一次 record 后 5s 才真正序列化落盘一次 */
    private fun schedulePersistDebounced() {
        runCatching {
            persistFuture?.cancel(false)
            persistFuture = diagExecutor.schedule({
                flushPersist("debounce")
            }, 5, TimeUnit.SECONDS)
        }
    }

    /** 立即把内存环序列化落盘（防抖到期 / trim / 进入暂停时调用）；最多丢失未落盘的最近数秒记录 */
    fun flushPersist(reason: String) {
        // 暂停/trim 关口顺带把诊断批队列也清一次，暂停后不再有跨进程调用把模块进程反复拉起
        runCatching { diagExecutor.execute { flushDiagQueue() } }
        if (!persistDirty) return
        val ctx = appContext ?: return
        // 快照在锁内拍、序列化在锁外做，不堵调用线程；拍快照同时记代际（M3），写入前比对防旧快照回写
        val genAtSnap = storeGen
        val snapshot = synchronized(this) { ArrayList(ring) }
        val t0 = SystemClock.uptimeMillis()
        val serialized = snapshot.joinToString("\n")
        perfRecordSerializeMs.addAndGet(SystemClock.uptimeMillis() - t0)
        if (genAtSnap != storeGen) return
        persistDirty = false
        runCatching {
            ctx.getSharedPreferences(Const.EVENT_HISTORY_PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(Const.EVENT_HISTORY_PREF_KEY, serialized)
                .apply()
        }
    }

    /** 仅写入模块自有诊断文件（不挤占 200 条核心事件环形历史），支持去重防刷盘（如 EDGE_CHECK 摘要） */
    fun recordDiagOnly(eventLine: String, dedupKey: String? = null) {
        if (!logEnabled) return
        // v1.4.5-fix16：精简级别下高频几何/滚动诊断直接丢弃（字符串已在调用方拼好，此处只省落盘与队列）
        if (!detailedDiag && (eventLine.startsWith("[EDGE_CHECK]") || eventLine.startsWith("[SCROLL_DIAG]"))) {
            return
        }
        if (dedupKey != null) {
            val tag = eventLine.substringBefore(' ')
            synchronized(this) {
                if (lastDiagDedupKeys[tag] == dedupKey) return
                if (lastDiagDedupKeys.size > 64) lastDiagDedupKeys.clear()
                lastDiagDedupKeys[tag] = dedupKey
            }
        }
        PerfStats.diagLines.incrementAndGet()
        val ctx = appContext ?: return
        val ts = synchronized(timeFmt) { timeFmt.format(Date()) }
        enqueueDiagWrite(ctx, "$ts $eventLine")
    }

    /** 只入内存队列：攒满 100 行立即刷、否则第一行入队后 5s 刷；队列空时不产生任何跨进程调用 */
    private fun enqueueDiagWrite(ctx: Context, formattedLine: String) {
        var flushNow = false
        synchronized(diagBatchQueue) {
            if (diagBatchQueue.size >= 500) {
                diagBatchQueue.removeFirst()
                diagBatchDropped++
            }
            diagBatchQueue.addLast(formattedLine)
            val depth = diagBatchQueue.size
            if (depth > perfDiagQueueMax.get()) perfDiagQueueMax.set(depth)
            if (depth >= 100) {
                flushNow = true
            } else if (diagBatchFuture == null || diagBatchFuture?.isDone == true) {
                diagBatchFuture = runCatching {
                    diagExecutor.schedule({ flushDiagQueue() }, 5, TimeUnit.SECONDS)
                }.getOrNull()
            }
        }
        if (flushNow) {
            runCatching { diagExecutor.execute { flushDiagQueue() } }
        }
    }

    /** 把攒着的诊断行一次跨进程批量落盘（Provider 的 BATCH 方法）；队列空直接返回、零调用 */
    private fun flushDiagQueue() {
        val batch: List<String>
        val dropped: Int
        val genAtTake: Int
        synchronized(diagBatchQueue) {
            if (!logEnabled) {
                // 总开关关：攒着的行直接丢弃，不产生跨进程调用
                diagBatchQueue.clear()
                diagBatchDropped = 0
                return
            }
            if (diagBatchQueue.isEmpty()) return
            batch = ArrayList(diagBatchQueue)
            diagBatchQueue.clear()
            dropped = diagBatchDropped
            diagBatchDropped = 0
            genAtTake = storeGen
        }
        // M4：取队列后若发生过 clearAll，这批是旧日志——发 IPC 前比对代际，不一致整批丢弃
        if (genAtTake != storeGen) return
        val ctx = appContext ?: return
        val lines = if (dropped > 0) {
            val ts = synchronized(timeFmt) { timeFmt.format(Date()) }
            listOf("$ts [DIAG] dropped=$dropped") + batch
        } else {
            batch
        }
        runCatching {
            val extras = Bundle().apply {
                putStringArrayList(Const.EXTRA_DIAG_LINES, ArrayList(lines))
            }
            ctx.contentResolver.call(
                Uri.parse(Const.LYRIC_PROVIDER_URI),
                Const.METHOD_APPEND_DIAG_LOG_BATCH,
                null,
                extras
            )
        }
    }

    /** 每曲确认、resetAndEnd 及非播放态 state_changed_hide 时调用：回放最近 40 条，5s 节流防刷屏 */
    fun replayRecentThrottled(trigger: String, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && (now - lastThrottledReplayElapsedMs) < Const.EVENT_HISTORY_REPLAY_THROTTLE_MS) {
            return
        }
        lastThrottledReplayElapsedMs = now
        replayToLog(trigger = trigger, maxEntries = Const.EVENT_HISTORY_REPLAY_LIMIT)
    }

    fun replayToLog(trigger: String, maxEntries: Int = Const.EVENT_HISTORY_REPLAY_LIMIT) {
        val snapshot = synchronized(this) {
            if (ring.size <= maxEntries) ring.toList() else ring.takeLast(maxEntries)
        }
        if (snapshot.isEmpty()) {
            YLog.info(msg = "[HISTORY] ($trigger) 暂无历史记录")
            return
        }
        YLog.info(msg = "=== [HISTORY] BEGIN ($trigger, showing=${snapshot.size}) ===")
        for (line in snapshot) {
            YLog.info(msg = "[HISTORY] $line")
        }
        YLog.info(msg = "=== [HISTORY] END ($trigger) ===")
    }

    private fun appendLocked(entry: String) {
        while (ring.size >= Const.EVENT_HISTORY_MAX_ENTRIES) {
            ring.removeFirst()
        }
        ring.addLast(entry)
    }
}
