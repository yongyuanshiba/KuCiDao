package com.vivo.islandlyric.util

import com.highcapable.yukihookapi.hook.log.YLog
import com.vivo.islandlyric.Const
import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程内异常熔断器 + fail-open 执行包装器。
 * 连续抛出异常达到阈值（默认 3 次）后自动熔断，绝不影响宿主原有逻辑。
 */
class HookCircuitBreaker(
    private val hookName: String,
    private val threshold: Int = Const.CIRCUIT_BREAKER_THRESHOLD
) {
    private val consecutiveFailures = AtomicInteger(0)

    @Volatile
    private var tripped: Boolean = false

    fun isTripped(): Boolean = tripped

    fun execute(block: () -> Unit) {
        if (tripped) return
        try {
            block()
            consecutiveFailures.set(0)
        } catch (t: Throwable) {
            val count = consecutiveFailures.incrementAndGet()
            YLog.error(msg = "[GUARD][$hookName] 执行异常 ($count/$threshold)", e = t)
            if (count >= threshold) {
                tripped = true
                YLog.error(msg = "[CIRCUIT_OPEN][$hookName] 连续异常达 $threshold 次，已自动熔断停用")
            }
        }
    }
}

object LogFormat {
    fun preview(raw: String?, maxLen: Int = Const.LOG_PREVIEW_LIMIT): String {
        if (raw == null) return "<null>"
        val normalized = raw
            .replace("\r\n", "↵")
            .replace("\n", "↵")
            .replace("\r", "↵")
        return if (normalized.length <= maxLen) {
            "\"$normalized\""
        } else {
            "\"${normalized.take(maxLen)}...\"(截断)"
        }
    }
}
