# v1.4.3 (versionCode 44)

根治 v1.4.2 首句热升级在 virtual 零点锚下毒化时间轴校验门、误写 7 天负缓存导致双行与逐字同灭的问题，并按「瞬时坏样本 → 长期失效」模板做同类隐患预防。

- 四道锁（修正版）：热升级守卫排除 virtual/none 且仅播放中执行，进入 PLAYING 时补调一次（sampleGate=false，不采样）；virtual→state 清样并归零计次，timeline_anchor→state 漂移 >8s 回 PENDING 重验；校验门多数票加四条约束（相邻不同行、virtual 下重复/近似行不作证据、非 virtual 每样本 ±15s 窗、半句续播不采样）；负缓存桶 v3→v4 自动解封误杀曲目。
- 必补 A–E：首句放行校验本句已采纳；拒表分级（首次 1 小时、14 天内跨会话 ≥2 次才 7 天、PASS 清零计数）；seek 判定扣墙钟漂移、跳变清样；gate 每次尝试落盘 [TIMELINE_GATE_ATTEMPT]；双源均健康无词才写 6 小时负缓存；首句/间奏学习防污染守卫；指纹变化即重拉时间轴。
- P1 修正：seek 扣墙钟防误判、热升级去重采样、跳过标记防提前消费、拒表计数清零时机修正。
- N1 仓侧直修：切歌重置清空 lastObservedRawPosMs；取不到墙钟时不做 seek 判定。
