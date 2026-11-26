# VivoIslandKugouLyric v1.4.1（versionCode 42）发布说明

- **版本**：`versionName = "1.4.1"` / `versionCode = 42`
- **基底**：`v1.4.0`（`versionCode 41`）
- **改动文件**（严格 9 个文件，全部通过语法配平与护栏校验）：
  - `app/build.gradle.kts`
  - `app/src/main/java/com/vivo/islandlyric/Const.kt`
  - `app/src/main/java/com/vivo/islandlyric/util/HookPrefs.kt`
  - `app/src/main/java/com/vivo/islandlyric/util/PrefsBackupCodec.kt`
  - `app/src/main/java/com/vivo/islandlyric/ui/LiquidGlassComponents.kt`
  - `app/src/main/java/com/vivo/islandlyric/ui/SettingsActivity.kt`
  - `app/src/main/java/com/vivo/islandlyric/hook/LyricScrollView.kt`
  - `app/src/main/java/com/vivo/islandlyric/hook/IslandMediaSessionSource.kt`
  - `app/src/main/java/com/vivo/islandlyric/hook/CapsuleLyricRenderer.kt`

## 本版七项修复与体验升级

1. **波浪宽度账重算 + 视觉安全区锁定（`CapsuleLyricRenderer.kt`）**
   - `computeRightLyricWidthForModePx` 改为按 `musicWave` 实际占位（含水平 margin）+ `2dp` 紧贴间距计算歌词宽度，并增加硬约束 `leftMargin + lyricW + gap + waveOccupied <= templateW - padRight - 4dp`。
   - `applyPreDrawVisualLock` 同步把常驻声波视觉右缘稳在胶囊内侧安全区（`capR.right - 6dp`），并在 `resetVisualLockTranslations()` 中还原，波浪完整留在胶囊内且歌词紧贴波浪无大段留白。
2. **次行字号滑杆 + 右侧歌词居中对齐开关（`Const.kt` / `HookPrefs.kt` / `PrefsBackupCodec.kt` / `SettingsActivity.kt` / `LyricScrollView.kt` / `CapsuleLyricRenderer.kt`）**
   - 新增 **次行预览字号滑杆**（`island_dual_line_sub_text_size_sp`，`6.5sp–11.5sp`，默认 `7.8sp`）。
   - 新增 **右侧歌词居中对齐开关**（`island_lyric_center_align`，默认关闭=靠左，开启=单行与双行主/次行短文本均水平居中）。
   - 两项均支持实时热生效及配置备份/还原。
3. **液态玻璃开关点按修复（`LiquidGlassComponents.kt`）**
   - 将 `LiquidToggle` 的手势与语义命中区上移至整个 `60dp × 28dp` 轨道，拖动阈值提高至 `8dp`（TouchSlop）：位移 `< 8dp` 立即按单击切换开关，`>= 8dp` 完整保留液态玻璃阻尼拖动。
4. **首句放行 KRC 逐字与次行预览（`IslandMediaSessionSource.kt`）**
   - 当 `timelineGateState == PENDING` 且首句高置信度（`score >= 0.72`）命中 `matchedIdx`、偏移在安全窗内时，提前放行该行的 KRC `words`、真实行时长与 `nextLyric`；第 2 句二次门控校验保持不变。
5. **间奏前末句与全曲末句逐字保留（`IslandMediaSessionSource.kt`）**
   - 当 `nextLine == null`（全曲末句）或 `gapMs` 达间奏级 / `rawLineSpan > 25_000L`（间奏前末句）时不再 `return null`，改用 `(vocalEndMs - lineStart).coerceIn(1000L, 25_000L)` 作为有效唱段时长，完整保留 KRC 逐字。
6. **切歌缓冲保宽 + 暂停宽限调优（`IslandMediaSessionSource.kt` / `CapsuleLyricRenderer.kt`）**
   - 将 `STATE_BUFFERING(6)` / `STATE_CONNECTING(8)` / `STATE_SKIPPING_TO_NEXT(10)` / `STATE_SKIPPING_TO_PREVIOUS(9)` 视为切歌过渡态，仅停行时钟与定时器、不触发拆宽；`PAUSE_TEARDOWN_GRACE_MS` 从 `600ms` 调整为 `1000ms`。
7. **曲末/尾奏陈旧词拦截（`IslandMediaSessionSource.kt`）**
   - 新增 `outroClearedInTrack` 状态；在尾奏已清词或非播放态且进度位于曲末 `2.5s` 内时，直接丢弃播放器补发的陈旧歌词，杜绝切歌/单曲循环首帧旧词闪现。

## 实机验收要点

1. 单行/双行 × 靠左/居中四组合下，常驻波浪完整留在胶囊内且歌词紧贴波浪。
2. 次行默认 `7.8sp` 更精致，拖动次行字号滑杆与切换居中开关即时热生效。
3. 设置页各开关关闭状态下直接轻点 5 次全部灵敏响应。
4. 切歌与单曲循环：首句即带次行预览且逐字速度准确、间奏前末句与全曲末句逐字正常、切歌不缩窄再弹宽、循环衔接无旧词闪现。
5. 插电、展开收起与多任务切换与 `v1.3.20` 保持一致零回归。
