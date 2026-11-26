package com.highcapable.yukihookapi.hook.log

import android.util.Log
import com.vivo.islandlyric.Const
import io.github.libxposed.api.XposedInterface

/**
 * 极简 YLog 兼容桥接层：
 * 使所有未改动的歌词核心类与工具类（0 字节改动）直接把日志路由至 libxposed API 102 的
 * XposedInterface.log() 与系统 Logcat，彻底移除 YukiHookAPI 运行时依赖。
 */
object YLog {

    @Volatile
    private var xposed: XposedInterface? = null

    fun attachXposed(iface: XposedInterface) {
        xposed = iface
    }

    fun debug(msg: Any? = "", tag: String = Const.LOG_TAG, e: Throwable? = null) {
        dispatch(Log.DEBUG, tag, msg?.toString().orEmpty(), e)
    }

    fun info(msg: Any? = "", tag: String = Const.LOG_TAG, e: Throwable? = null) {
        dispatch(Log.INFO, tag, msg?.toString().orEmpty(), e)
    }

    fun warn(msg: Any? = "", tag: String = Const.LOG_TAG, e: Throwable? = null) {
        dispatch(Log.WARN, tag, msg?.toString().orEmpty(), e)
    }

    fun error(msg: Any? = "", tag: String = Const.LOG_TAG, e: Throwable? = null) {
        dispatch(Log.ERROR, tag, msg?.toString().orEmpty(), e)
    }

    private fun dispatch(priority: Int, tag: String, text: String, e: Throwable?) {
        val bridge = xposed
        if (bridge != null) {
            runCatching {
                if (e != null) {
                    bridge.log(priority, tag, text, e)
                } else {
                    bridge.log(priority, tag, text)
                }
                return
            }
        }
        if (e != null) {
            Log.println(priority, tag, "$text\n${Log.getStackTraceString(e)}")
        } else {
            Log.println(priority, tag, text)
        }
    }
}
