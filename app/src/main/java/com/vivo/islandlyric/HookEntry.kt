package com.vivo.islandlyric

import com.highcapable.yukihookapi.hook.log.YLog
import com.vivo.islandlyric.hook.IslandLyricHooker
import com.vivo.islandlyric.util.HookPrefs
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 原生 libxposed API 102 模块入口（public 无参构造，由 META-INF/xposed/java_init.list 声明）：
 * - 单进程闭环：仅在 com.vivo.systemuiplugin:island 生效，其他进程在 onModuleLoaded 立即 detach()；
 * - 零 Compose / Backdrop 引用，确保 :island 宿主进程不加载任何 UI 框架代码。
 */
class HookEntry : XposedModule() {

    @Volatile
    private var currentProcessName: String = ""

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        currentProcessName = param.processName
        YLog.attachXposed(this)
        HookPrefs.attachXposed(this)

        if (param.isSystemServer || !currentProcessName.endsWith(Const.PROC_ISLAND_SUFFIX)) {
            detach()
            return
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != Const.PKG_SYSTEMUI_PLUGIN) return
        if (!currentProcessName.endsWith(Const.PROC_ISLAND_SUFFIX)) return
        IslandLyricHooker.onPackageReady(this, param, currentProcessName)
    }
}
