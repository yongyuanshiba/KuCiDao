# VivoIslandKugouLyric v1.3.9（versionCode 29）变更说明

基线版本：`v1.3.8`（含 `io.github.libxposed:service:102.0.0` 与 `IslandLyricHooker` 三处渲染器分离回调修复）。本次改动严格限定在 `ui/` 设置页、模块简介文案与版本号配置，歌词核心逻辑、挂钩底座与耗电护栏均未改动。

## 变更摘要

1. **4 个标签页常驻保活（切页零重建）**
   - `SettingsRootScreen` 改为首帧挂载当前页、首帧后常驻保活全部 4 个标签页，并各自维护独立 `ScrollState`，不再在切页时销毁重建 `LiquidToggle` / `LiquidSlider` 及其独立 `LayerBackdrop`。
   - 非当前且不在过渡中的标签页在 `layout` 阶段不执行 `placeWithLayer`，静止状态下零绘制、零触控拦截、零后台重绘。
2. **补齐同频短程页面过渡动画**
   - 切页时驱动短程横向微滑（`22dp`）与淡入淡出（`spring(dampingRatio = 0.95f, stiffness = 580f)`），完全运行在布局/绘制层（零重组）。
   - 快速连点可立即打断上一次过渡动画；`onPause` / `onStop` 触发 `RegisterPauseSnap` 立即收束到静止帧。
3. **消除底栏连点文字重影**
   - 收紧点按水滴的横向膨胀（`scaleX 1.10x`，保留纵向 `scaleY 1.18x` 弹出感）与速度拉伸上限（`±0.08`），不再跨界吞掉相邻标签文字。
   - 检测到 `280ms` 内快速连点或动画未结束时，立即收束上一次残留缩放与速度脉冲，切入更利落的短程滑移（`holdMs 65ms`）。
   - 对齐底栏 `Layer A` 与 `Layer B` 的高度、内边距与缩放系数，将水滴内文字放大比例收敛至 `1.07x`，并移除点击切页时的 `onTabSelected` 重复回环。
4. **LSPosed 模块简介文案更新**
   - `xposed_desc` 精简为「原子岛歌词显示」。

## 修改文件清单

| 文件路径 | 改动内容 |
|---|---|
| `app/src/main/java/com/vivo/islandlyric/ui/SettingsActivity.kt` | 4 页常驻保活 + 独立滚动位置 + 短程横向微滑与淡入淡出过渡 + `onPause` 停帧收束 |
| `app/src/main/java/com/vivo/islandlyric/ui/LiquidGlassMotion.kt` | 收紧 `animateTapPopToValue` 横向膨胀与速度脉冲，新增快速连点即时收束逻辑 |
| `app/src/main/java/com/vivo/islandlyric/ui/LiquidGlassComponents.kt` | 收紧拖拽与速度拉伸上限，对齐 `Layer A`/`Layer B` 尺寸与缩放，水滴文字放大收敛至 `1.07x`，移除冗余回环 |
| `app/src/main/res/values/strings.xml` | `xposed_desc` 改为「原子岛歌词显示」 |
| `app/build.gradle.kts` | `versionCode` `28 -> 29`，`versionName` `"1.3.8" -> "1.3.9"` |
| `.github/workflows/build.yml` | 工作流名称与构建产物名更新为 `v1.3.9` |
| `CHANGELOG-v1.3.9.md` | 新增本版本变更说明 |
