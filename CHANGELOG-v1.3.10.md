# VivoIslandKugouLyric v1.3.10（versionCode 30）变更说明

基线版本：`v1.3.9`（保留 `compileSdk = 37`＋`android.suppressUnsupportedCompileSdk=37`、原生 `libxposed API 102` `api:102.0.0` / `service:102.0.0` 布局与 `ui/` 设置页零改动）。本次严格按 Gemini × Jasmine 合并审定方案落盘，全部改动聚焦在歌词时间轴与会话状态机，零轮询、零新增线程。

## 变更摘要

1. **P0｜单曲循环跨状态重播记账＋立即清屏＋长尾奏自动退场**
   - `IslandMediaSessionSource.handlePlaybackState` 新增跨状态标志 `pendingSameTrackRewind`，无论处于 `PAUSED`、`BUFFERING` 还是 `PLAYING`，只要同曲进度从 `> 10000ms` 跳回 `0..5000ms`（`POS_REWIND_TO_MS` 由 `3000ms` 放宽至 `5000ms`）立即记账，进入 `PLAYING` 时必定消费，彻底防止中间态 `rawPos=0` 吃掉重播判定。
   - 重播消费分支立即调用 `listener.onTrackSwitched("", true, currentEstimatedWindowMs())` 同步清空胶囊与横屏旧词，并复位 `lastMatchedVocalIndex = -1`、`inIntro = false`、`activeIntroTargetMs = -1L`，杜绝上一遍末句残留到下一遍前奏或误武装为间奏。
   - `scheduleTimelineLyricFallback` 对全曲最后一句（`nextLine == null`）新增尾奏清词点 `outroClearPosMs = vocalEndMs + trackTimeOffsetMs + 2500L`：已过清词点时立即清词不再重推（手动拖进尾奏段也不再复活末句）；尚在演唱时挂单次 `postDelayed` 到点自动清词。
2. **P1｜KRC 异常尾字拖尾裁剪（释放长间奏倒计时）**
   - `LrcTimelineParser.estimateVocalEndMs` 在存在下一句且行距 `>= 12000ms` 时，对异常拉长的末字时长（`> 6000ms` 且超过本行其余字中位时长的 `6` 倍）按 `max(2500ms, median * 3)` 截断估算唱完点，同时对无逐字时间轴但行时长 `> 12000ms` 的异常行回退字数估算，避免吞掉长间奏倒计时。
3. **P1｜扩充 Live 演职员表过滤**
   - `LrcTimelineParser` 扩充 `CREDIT_PREFIX_REGEX` 覆盖 Live 常见乐器与幕后岗位（`音乐总监`、`乐器技师`、`调音`、`大提琴`、`小提琴`、`音响`、`和音`、`伴唱`、`PGM` 等），并在曲首 `15s` 窗内过滤「职务名 + 冒号 + 人名」短行，避免演职员表进歌词区或拉低 `firstVocalMs` 废掉前奏倒计时。
4. **P1｜消除前奏切首句 1ms 间隙闪回原厂胶囊**
   - `scheduleTimelineLyricFallback` 接管首句时改为先推送 `listener.onLyricLine(...)` 再调用 `exitIntroIfActive("timeline_first_vocal")`，避免空词间隙触发 `not_collapsed_music_capsule` 缩回又重扩。
5. **P2｜`resetAndEnd` 空闲幂等早退**
   - 酷狗未运行且会话已完全空闲时直接早退，消除其他媒体会话触发的数百次冗余日志与无效写盘。
6. **B4 / B6 / B7（Jasmine 独立审计合并项）**
   - **B4**：`isSeekJump` 保持原判定逻辑不盲改，新增 `[SEEK_DIAG]` 日志记录 `rawPos`、`prevRawPos`、`deltaPos`、`deltaWallMs`、`driftMs` 供实机观测。
   - **B6**：将 `lastDeliveredLyric = ""` 与 `lastDeliveredWordSpans = emptyList()` 收进 `resetTrackTimingState` 本体加固契约。
   - **B7**：删除 `scheduleTimelineLyricFallback` 中未使用的死代码 `lineEndMs`。

## 修改文件清单

| 文件路径 | 改动内容 |
|---|---|
| `app/src/main/java/com/vivo/islandlyric/Const.kt` | `POS_REWIND_TO_MS` `3000L -> 5000L`，新增 `OUTRO_CLEAR_GRACE_MS = 2500L` |
| `app/src/main/java/com/vivo/islandlyric/util/LrcTimelineParser.kt` | 扩充 Live 演职员表正则与曲首冒号行过滤；`estimateVocalEndMs` 增加长间奏前异常尾字拖尾裁剪 |
| `app/src/main/java/com/vivo/islandlyric/hook/IslandMediaSessionSource.kt` | 跨状态重播记账、重播清屏与索引复位、尾奏单次定时清词、首句先推后退前奏、空闲 `resetAndEnd` 早退、`[SEEK_DIAG]` 观测日志、`resetTrackTimingState` 清词收口、删 `lineEndMs` |
| `app/build.gradle.kts` | `versionCode` `29 -> 30`，`versionName` `"1.3.9" -> "1.3.10"`（`compileSdk = 37` 与 `libxposed` 102.0.0 坐标原样保留） |
| `.github/workflows/build.yml` | 工作流名称与构建产物名更新为 `v1.3.10` |
| `CHANGELOG-v1.3.10.md` | 新增本版本变更说明 |
