# VivoIslandKugouLyric v1.4.2 发布说明（versionCode 43）

基于 `v1.4.1`（`versionCode 42`）迭代，共改动 7 个文件，完成合并评审稿全部 5 项修复与同源闭包加固，`v1.3.15–v1.3.20` 与 `v1.4.1` 全部护栏零回退。

## 核心改动

1. **波浪位移复位与保护间距**（`CapsuleLyricRenderer.kt`）
   - `resetVisualLockTranslations()` 补清 `musicWave.translationX = 0f`，消除展开大卡与退回原厂时的声波残余偏移。
   - `applyPreDrawVisualLock()` 在前奏/间奏倒计时态或关闭常驻声波时立即复位 `waveView.translationX = 0f`，彻底解决间奏倒计时（如 `19s`）与波浪粘连重叠。
   - 歌词态常驻声波拉回安全区时，与 `rightLyricView` 右缘保持至少 `2dp` 保护间距，并在空间富余时平滑回退负位移。

2. **设置页开关修复与手势取消防误触**（`LiquidGlassComponents.kt`、`LiquidGlassMotion.kt`）
   - `LiquidToggle`、`LiquidSlider`、`LiquidBottomTabs` 统一改用 `rememberUpdatedState` 读取最新状态与回调，彻底解决初始开只能关、初始关只能开的单向点击问题。
   - `DampedDragAnimation` 校验 `drag(down.id)` 完成状态，新增 `onDragCancelled` 回调；列表垂直滚动取消手势时仅恢复原位、不触发切换。

3. **歌词对齐一致性（A 案）**（`LyricScrollView.kt`）
   - 居中开关关闭时，短句主行、长句主行、溶解淡出行与次行预览统一真靠左（`paddingLeft`），消除短长句左右跳变；开启时居中保持不变。
   - 同句原地热升级切换单/双行时同步重测主行宽与逐字像素区间，防止字号变化导致着色偏移。

4. **首句缓存/在线 KRC 原地热升级**（`IslandMediaSessionSource.kt`、`CapsuleLyricRenderer.kt`）
   - `onTimelineLoaded` 就绪后，若当前正在显示无 KRC 的 `estimate` 首句，自动调用 `tryUpgradeCurrentEstimateLineWithTimeline()` 保持同 `lineSeq` 原地热升级逐字表、真实时长与 `nextLyric`，按已播进度无缝续跑、不重头闪跳。

5. **单字句 KRC 逐字放行**（`LrcTimelineParser.kt`、`IslandMediaSessionSource.kt`）
   - `sliceWordSpansForSubLine` 放行单字精确子串切片；在线时间轴通过校验后，对单字句（如「呜」）支持 ±1500ms 时序邻近匹配并输出有效单字 `WordSpan`，不再回落 `estimate (words=0)`。

## 改动文件清单（较 v1.4.1 共 7 处）

- `app/build.gradle.kts`（`versionCode = 43`、`versionName = "1.4.2"`）
- `app/src/main/java/com/vivo/islandlyric/hook/CapsuleLyricRenderer.kt`
- `app/src/main/java/com/vivo/islandlyric/hook/IslandMediaSessionSource.kt`
- `app/src/main/java/com/vivo/islandlyric/hook/LyricScrollView.kt`
- `app/src/main/java/com/vivo/islandlyric/util/LrcTimelineParser.kt`
- `app/src/main/java/com/vivo/islandlyric/ui/LiquidGlassComponents.kt`
- `app/src/main/java/com/vivo/islandlyric/ui/LiquidGlassMotion.kt`

## 验收检查清单

- **间奏倒计时与波浪**：开启常驻声波播放《六层楼》等含间奏歌曲，倒计时数字与波浪之间间距清晰、零重叠；展开大卡/退回原厂波浪无偏左。
- **设置页开关与滑动**：初始开与初始关各选 2 个开关连续单击 5 次以上全部即时切换；上下滑动设置页不误触开关。
- **对齐与逐字**：关闭居中时短长句统一靠左不跳变；中途进歌/续播首句不再 5 秒盲跑；单字句（如「呜」）日志显示 `src=krc` 且 `words>=1`。
- **护栏回归**：插电、展开收起、切歌保宽、单曲循环、多任务快恢复与 `v1.4.1` 一致零回归。
