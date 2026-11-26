package com.vivo.islandlyric.hook

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.View
import android.view.ViewGroup
import com.highcapable.yukihookapi.hook.log.YLog
import com.vivo.islandlyric.Const
import com.vivo.islandlyric.util.HookCircuitBreaker
import com.vivo.islandlyric.util.HookPrefs
import com.vivo.islandlyric.util.LrcTimelineParser
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 正式版 v1.3.8 单进程主入口（原生 libxposed API 102，仅运行在 com.vivo.systemuiplugin:island）：
 * - 零全局 View Hook：仅定点钩住原子岛自有类构造器与自有声明方法（onMeasure 候选 7 个自有类，严禁钩父类）；
 * - 全部 7 处定点 Hook 均开启 ExceptionMode.PROTECTIVE 保护模式，防止异常影响宿主进程。
 */
object IslandLyricHooker {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val initialized = AtomicBoolean(false)
    private val hooksInstalled = AtomicBoolean(false)

    private val leftTemplateCtorBreaker = HookCircuitBreaker("Island.LeftTemplate.ctor")
    private val rightTemplateCtorBreaker = HookCircuitBreaker("Island.RightTemplate.ctor")
    private val waveViewCtorBreaker = HookCircuitBreaker("Island.MusicWave.ctor")
    private val windowViewCtorBreaker = HookCircuitBreaker("Island.WindowView.ctor")
    private val switcherVisBreaker = HookCircuitBreaker("Island.Switcher.setVisibility")
    private val waveVisBreaker = HookCircuitBreaker("Island.Wave.setVisibility")
    private val measureBreaker = HookCircuitBreaker("Island.Template.onMeasure")

    private val onMeasureHookStatus = LinkedHashMap<String, String>(8)

    private lateinit var landscapeOverlay: LandscapeLyricOverlay

    private val renderer: CapsuleLyricRenderer = CapsuleLyricRenderer(
        mainHandler = mainHandler,
        readPrefs = { HookPrefs.read() },
        positionProvider = { mediaSource.estimateCurrentPositionMs() },
        onNativeVisibilityChanged = {
            if (::landscapeOverlay.isInitialized) {
                landscapeOverlay.onNativeIslandStateChanged()
            }
        }
    )

    private val mediaSource: IslandMediaSessionSource = IslandMediaSessionSource(
        mainHandler = mainHandler,
        readPrefs = { HookPrefs.read() },
        introStateQuery = { renderer.queryIntroVisualState() },
        listener = object : IslandMediaSessionSource.Listener {
            override fun onTrackInfo(displayTitle: String, artist: String) {
                renderer.updateTrackInfo(displayTitle, artist)
            }

            override fun onCoverPalette(colors: IntArray) {
                renderer.updateGlowPalette(colors)
                if (::landscapeOverlay.isInitialized) landscapeOverlay.updateGlowPalette(colors)
            }

            override fun onPlaybackPlayingChanged(isPlaying: Boolean) {
                renderer.updatePlaybackPlaying(isPlaying)
                if (::landscapeOverlay.isInitialized) landscapeOverlay.updatePlaybackPlaying(isPlaying)
            }

            override fun onLyricLine(
                lyric: String,
                isPlaying: Boolean,
                estimatedWindowMs: Long,
                karaokeDurationMs: Long,
                wordSpans: List<LrcTimelineParser.WordSpan>,
                lineSeq: Long,
                nextLyric: String
            ) {
                renderer.updateLyric(lyric, isPlaying, estimatedWindowMs, karaokeDurationMs, wordSpans, lineSeq, nextLyric)
                if (::landscapeOverlay.isInitialized) {
                    landscapeOverlay.updateLyric(lyric, isPlaying, estimatedWindowMs, karaokeDurationMs, wordSpans, lineSeq)
                }
            }

            override fun onInterludeChanged(inInterlude: Boolean, targetNextLineMs: Long, startMs: Long, skipWaveLead: Boolean) {
                renderer.updateInterlude(inInterlude, targetNextLineMs, startMs, skipWaveLead)
                if (::landscapeOverlay.isInitialized) landscapeOverlay.updateInterlude(inInterlude, targetNextLineMs)
            }

            override fun onIntroChanged(
                inIntro: Boolean,
                learnedFirstLineMs: Long,
                skipWaveLead: Boolean,
                forceWaveOnly: Boolean
            ) {
                renderer.updateIntro(inIntro, learnedFirstLineMs, skipWaveLead, forceWaveOnly)
                if (::landscapeOverlay.isInitialized) landscapeOverlay.updateIntro(inIntro, learnedFirstLineMs, skipWaveLead, forceWaveOnly)
            }

            override fun onTrackSwitched(newInitialTitle: String, isPlaying: Boolean, estimatedWindowMs: Long) {
                renderer.clearForTrackSwitch(newInitialTitle, isPlaying, estimatedWindowMs)
                if (::landscapeOverlay.isInitialized) {
                    landscapeOverlay.clearForTrackSwitch(newInitialTitle, isPlaying, estimatedWindowMs)
                }
            }

            override fun onSessionEnded(reason: String) {
                renderer.clearAndRestore(reason)
                if (::landscapeOverlay.isInitialized) landscapeOverlay.clearAndRestore(reason)
            }

            override fun onTrackConfirmedForDiag() {
                logOnMeasureHookStatus()
            }
        }
    )

    fun onPackageReady(
        xposed: XposedInterface,
        param: XposedModuleInterface.PackageReadyParam,
        processName: String
    ) {
        val packageName = param.packageName
        YLog.info(
            msg = "[ISLAND_V1380_LOAD] pkg=$packageName | proc=$processName | pid=${Process.myPid()} | uid=${Process.myUid()} | api=${xposed.apiVersion}"
        )

        if (packageName != Const.PKG_SYSTEMUI_PLUGIN || !processName.endsWith(Const.PROC_ISLAND_SUFFIX)) {
            YLog.info(msg = "[ISLAND_V1380_SKIP] 非 :island 进程 ($processName)，跳过装钩")
            return
        }
        if (!hooksInstalled.compareAndSet(false, true)) return

        val cl = param.classLoader
        installApplicationCreateHook(xposed)
        resolveCurrentApplication()?.let { startOnce(it) }

        installLeftTemplateHook(xposed, cl)
        installRightTemplateHook(xposed, cl)
        installMusicWaveHook(xposed, cl)
        installWindowViewHook(xposed, cl)

        installSwitcherVisibilityGuardIfDeclared(xposed, cl)
        installWaveVisibilityGuardIfDeclared(xposed, cl)

        val candidates = arrayOf(
            Const.CLS_LEFT_TEMPLATE,
            Const.CLS_RIGHT_TEMPLATE,
            Const.CLS_TEMPLATE_FRAME_LAYOUT,
            Const.CLS_CAPSULE_CONTENT_VIEW,
            Const.CLS_DYNAMIC_CAPSULE_CONTAINER,
            Const.CLS_DYNAMIC_CAPSULE_VIEW,
            Const.CLS_ISLAND_WINDOW_VIEW
        )
        for (clsName in candidates) {
            installDeclaredOnMeasureHook(xposed, cl, clsName)
        }
        logOnMeasureHookStatus()
    }

    private fun installApplicationCreateHook(xposed: XposedInterface) {
        runCatching {
            val onCreate = Application::class.java.getDeclaredMethod("onCreate")
            xposed.hook(onCreate)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val res = chain.proceed()
                    val app = chain.thisObject as? Application
                    if (app != null) {
                        startOnce(app)
                    }
                    res
                }
        }.onFailure { err ->
            YLog.warn(msg = "[HOOK_WARN] Application.onCreate 装钩失败: ${err.message}")
        }
    }

    private fun resolveCurrentApplication(): Application? {
        return runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val m = at.getDeclaredMethod("currentApplication").apply { isAccessible = true }
            m.invoke(null) as? Application
        }.getOrNull()
    }

    private fun loadClassOrNull(cl: ClassLoader, name: String): Class<*>? {
        return runCatching { Class.forName(name, false, cl) }.getOrNull()
    }

    private fun startOnce(ctx: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val appCtx = ctx.applicationContext ?: ctx
        HookPrefs.attachHostContext(appCtx)
        landscapeOverlay = LandscapeLyricOverlay(
            mainHandler = mainHandler,
            readPrefs = { HookPrefs.read() },
            positionProvider = { mediaSource.estimateCurrentPositionMs() },
            isNativeIslandShowing = { renderer.isNativeIslandShowing() },
            nativeWindowTypeProvider = { renderer.nativeWindowType() }
        ).apply {
            attachHostContext(appCtx)
        }
        runCatching {
            appCtx.registerComponentCallbacks(object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    landscapeOverlay.onConfigurationChanged()
                    renderer.onConfigurationChanged()
                }
                override fun onLowMemory() {}
            })
        }
        registerPrefsMirrorReceiver(appCtx)
        mediaSource.start(appCtx)

        mainHandler.postDelayed({
            runCatching {
                if (!HookPrefs.read(forceReload = true).masterEnabled) return@runCatching
                scanExistingIslandWindows()
            }
        }, 2000L)
    }

    private fun registerPrefsMirrorReceiver(appCtx: Context) {
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Const.ACTION_PUSH_PREFS_MIRROR)
                addAction(Const.ACTION_REQUEST_PREFS_MIRROR)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val act = intent?.action ?: return
                    if (intent.getStringExtra(Const.EXTRA_CALLER_PKG) != Const.MODULE_PACKAGE_NAME) return
                    val mirrorSp = appCtx.getSharedPreferences(Const.HOST_PREFS_MIRROR_FILE, Context.MODE_PRIVATE)
                    when (act) {
                        Const.ACTION_PUSH_PREFS_MIRROR -> {
                            val json = intent.getStringExtra(Const.EXTRA_MIRROR_JSON).orEmpty()
                            if (json.isNotBlank() && json.trimStart().startsWith("{")) {
                                mirrorSp.edit().putString(Const.HOST_PREFS_MIRROR_KEY, json).apply()
                                YLog.info(msg = "[PREFS_MIRROR] 宿主已保存配置镜像 (${json.length}B)")
                            }
                            HookPrefs.read(forceReload = true)
                            if (::landscapeOverlay.isInitialized) {
                                landscapeOverlay.onPrefsChanged()
                            }
                            // vc55：偏好热下发也戳一次渲染器 reconcile（横屏中改「横屏时」立即生效）；runCatching 防御（门检 C1）
                            runCatching { renderer.onConfigurationChanged() }
                        }
                        Const.ACTION_REQUEST_PREFS_MIRROR -> {
                            val saved = mirrorSp.getString(Const.HOST_PREFS_MIRROR_KEY, null)
                            if (!saved.isNullOrBlank()) {
                                val reply = Intent(Const.ACTION_REPLY_PREFS_MIRROR).apply {
                                    setPackage(Const.MODULE_PACKAGE_NAME)
                                    putExtra(Const.EXTRA_MIRROR_JSON, saved)
                                }
                                appCtx.sendBroadcast(reply)
                                YLog.info(msg = "[PREFS_MIRROR] 已向模块回传宿主配置镜像 (${saved.length}B)")
                            }
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                appCtx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appCtx.registerReceiver(receiver, filter)
            }
        }.onFailure { err ->
            YLog.warn(msg = "[PREFS_MIRROR] 注册宿主配置镜像接收器失败: ${err.message}")
        }
    }

    private fun logOnMeasureHookStatus() {
        val summary = synchronized(onMeasureHookStatus) {
            onMeasureHookStatus.entries.joinToString(" | ") { "${it.key}=${it.value}" }
        }
        YLog.info(msg = "[HOOK_STATUS] onMeasure: $summary")
    }

    private fun installDeclaredOnMeasureHook(xposed: XposedInterface, cl: ClassLoader, className: String) {
        val simpleName = className.substringAfterLast('.')
        val cls = loadClassOrNull(cl, className)
        if (cls == null) {
            synchronized(onMeasureHookStatus) { onMeasureHookStatus[simpleName] = "SKIP(noClass)" }
            YLog.info(msg = "[HOOK_SKIP] onMeasure 目标类不存在: $className")
            return
        }
        val declaredOnMeasure = runCatching {
            cls.getDeclaredMethod("onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }.getOrNull()
        if (declaredOnMeasure == null) {
            synchronized(onMeasureHookStatus) { onMeasureHookStatus[simpleName] = "SKIP(notDeclared)" }
            YLog.info(msg = "[HOOK_SKIP] $className 未覆写 onMeasure，跳过（绝不钩父类）")
            return
        }

        xposed.hook(declaredOnMeasure)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                var forcedSpec: Int? = null
                measureBreaker.execute {
                    val v = chain.thisObject as? View ?: return@execute
                    forcedSpec = renderer.resolveForcedWideMeasureSpec(v)
                }
                val spec = forcedSpec
                if (spec != null) {
                    chain.proceed(arrayOf(spec, chain.getArg(1)))
                } else {
                    chain.proceed()
                }
            }
        synchronized(onMeasureHookStatus) { onMeasureHookStatus[simpleName] = "OK" }
        YLog.info(msg = "[HOOK_OK] 已定点安装 $className.onMeasure 测量期定宽钩")
    }

    private fun installLeftTemplateHook(xposed: XposedInterface, cl: ClassLoader) {
        val cls = loadClassOrNull(cl, Const.CLS_LEFT_TEMPLATE)
        if (cls == null) {
            YLog.warn(msg = "[HOOK_MISS] 未找到 ${Const.CLS_LEFT_TEMPLATE}")
            return
        }
        for (ctor in cls.declaredConstructors) {
            xposed.hook(ctor)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val res = chain.proceed()
                    leftTemplateCtorBreaker.execute {
                        if (!HookPrefs.read().masterEnabled) return@execute
                        val template = chain.thisObject as? ViewGroup ?: return@execute
                        template.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) {
                                renderer.bindLeftTemplate(template)
                            }
                            override fun onViewDetachedFromWindow(v: View) {
                                renderer.unbindLeftTemplate(template)
                            }
                        })
                    }
                    res
                }
        }
        YLog.info(msg = "[HOOK_OK] 已定点安装 AtomMainIslandLeftTemplate 构造器钩")
    }

    private fun installRightTemplateHook(xposed: XposedInterface, cl: ClassLoader) {
        val cls = loadClassOrNull(cl, Const.CLS_RIGHT_TEMPLATE)
        if (cls == null) {
            YLog.warn(msg = "[HOOK_MISS] 未找到 ${Const.CLS_RIGHT_TEMPLATE}")
            return
        }
        for (ctor in cls.declaredConstructors) {
            xposed.hook(ctor)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val res = chain.proceed()
                    rightTemplateCtorBreaker.execute {
                        if (!HookPrefs.read().masterEnabled) return@execute
                        val template = chain.thisObject as? ViewGroup ?: return@execute
                        template.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) {
                                renderer.bindRightTemplate(template)
                            }
                            override fun onViewDetachedFromWindow(v: View) {
                                renderer.unbindRightTemplate(template)
                            }
                        })
                    }
                    res
                }
        }
        YLog.info(msg = "[HOOK_OK] 已定点安装 AtomMainIslandRightTemplate 构造器钩")
    }

    private fun installMusicWaveHook(xposed: XposedInterface, cl: ClassLoader) {
        val cls = loadClassOrNull(cl, Const.CLS_MUSIC_WAVE_VIEW)
        if (cls == null) {
            YLog.warn(msg = "[HOOK_MISS] 未找到 ${Const.CLS_MUSIC_WAVE_VIEW}")
            return
        }
        for (ctor in cls.declaredConstructors) {
            xposed.hook(ctor)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val res = chain.proceed()
                    waveViewCtorBreaker.execute {
                        if (!HookPrefs.read().masterEnabled) return@execute
                        val waveView = chain.thisObject as? View ?: return@execute
                        waveView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) {
                                renderer.onMusicWaveAttachChanged(v, attached = true)
                            }
                            override fun onViewDetachedFromWindow(v: View) {
                                renderer.onMusicWaveAttachChanged(v, attached = false)
                            }
                        })
                    }
                    res
                }
        }
        YLog.info(msg = "[HOOK_OK] 已定点安装 IslandMusicWaveView 构造器钩")
    }

    private fun installWindowViewHook(xposed: XposedInterface, cl: ClassLoader) {
        val cls = loadClassOrNull(cl, Const.CLS_ISLAND_WINDOW_VIEW)
        if (cls == null) {
            YLog.warn(msg = "[HOOK_MISS] 未找到 ${Const.CLS_ISLAND_WINDOW_VIEW}")
            return
        }
        for (ctor in cls.declaredConstructors) {
            xposed.hook(ctor)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val res = chain.proceed()
                    windowViewCtorBreaker.execute {
                        if (!HookPrefs.read().masterEnabled) return@execute
                        val root = chain.thisObject as? View ?: return@execute
                        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) {
                                renderer.onRootWindowAttached(v)
                            }
                            override fun onViewDetachedFromWindow(v: View) {
                                renderer.onRootWindowDetached("island_window_detached")
                            }
                        })
                    }
                    res
                }
        }
        YLog.info(msg = "[HOOK_OK] 已定点安装 IslandWindowView 构造器钩")
    }

    private fun installSwitcherVisibilityGuardIfDeclared(xposed: XposedInterface, cl: ClassLoader) {
        val cls = loadClassOrNull(cl, Const.CLS_TEXT_SWITCHER) ?: return
        val declaredSetVis = runCatching {
            cls.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType)
        }.getOrNull() ?: return

        xposed.hook(declaredSetVis)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                var overrideVis: Int? = null
                switcherVisBreaker.execute {
                    val v = chain.thisObject as? View ?: return@execute
                    val vis = (chain.getArg(0) as? Int) ?: return@execute
                    if (vis != View.VISIBLE && renderer.shouldPreventSwitcherHide(v)) {
                        overrideVis = View.VISIBLE
                    }
                }
                val next = overrideVis
                if (next != null) {
                    chain.proceed(arrayOf(next))
                } else {
                    chain.proceed()
                }
            }
        YLog.info(msg = "[HOOK_OK] 已定点安装 VivoTextSwitcher.setVisibility 防重置钩")
    }

    private fun installWaveVisibilityGuardIfDeclared(xposed: XposedInterface, cl: ClassLoader) {
        val cls = loadClassOrNull(cl, Const.CLS_MUSIC_WAVE_VIEW) ?: return
        val declaredSetVis = runCatching {
            cls.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType)
        }.getOrNull() ?: return

        xposed.hook(declaredSetVis)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                var overrideVis: Int? = null
                var requestedVis: Int = -1
                waveVisBreaker.execute {
                    val v = chain.thisObject as? View ?: return@execute
                    val vis = (chain.getArg(0) as? Int) ?: return@execute
                    requestedVis = vis
                    if (vis != View.GONE && renderer.shouldPreventWaveShow(v)) {
                        overrideVis = View.GONE
                    }
                }
                val next = overrideVis
                val res = if (next != null) {
                    chain.proceed(arrayOf(next))
                } else {
                    chain.proceed()
                }
                if (next == null) {
                    if (requestedVis == View.VISIBLE) {
                        waveVisBreaker.execute {
                            val v = chain.thisObject as? View ?: return@execute
                            renderer.onMusicSurfaceBecameVisible(v)
                        }
                    } else if (requestedVis == View.GONE || requestedVis == View.INVISIBLE) {
                        waveVisBreaker.execute {
                            val v = chain.thisObject as? View ?: return@execute
                            renderer.onMusicWaveHiddenBySystem(v)
                        }
                    }
                }
                res
            }
        YLog.info(msg = "[HOOK_OK] 已定点安装 IslandMusicWaveView.setVisibility 防重置钩")
    }

    private fun scanExistingIslandWindows() {
        val wmgClass = Class.forName("android.view.WindowManagerGlobal")
        val getInstance = wmgClass.getDeclaredMethod("getInstance").apply { isAccessible = true }
        val wmg = getInstance.invoke(null) ?: return
        val mViewsField = wmgClass.getDeclaredField("mViews").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val views = mViewsField.get(wmg) as? List<View> ?: return
        for (v in views) {
            if (v.javaClass.name == Const.CLS_ISLAND_WINDOW_VIEW) {
                renderer.onRootWindowAttached(v)
            }
        }
    }
}
