package com.vivo.islandlyric

object Const {
    const val LOG_TAG = "VivoIslandLyric"

    const val MODULE_PACKAGE_NAME = "com.vivo.islandlyric"
    const val PREF_FILE_NAME = "vivo_island_lyric_prefs"

    const val PKG_KUGOU = "com.kugou.android"
    const val PKG_SYSTEMUI_PLUGIN = "com.vivo.systemuiplugin"
    const val PROC_ISLAND_SUFFIX = ":island"

    /** 在线歌词时间轴 ContentProvider 契约 */
    const val LYRIC_PROVIDER_AUTHORITY = "com.vivo.islandlyric.lyricprovider"
    const val LYRIC_PROVIDER_URI = "content://com.vivo.islandlyric.lyricprovider"
    const val METHOD_FETCH_TIMELINE = "fetch_timeline"
    const val METHOD_REJECT_TIMELINE = "reject_timeline"
    const val METHOD_APPEND_DIAG_LOG = "append_diag_log"
    // v1.4.5-fix16（Quick 补件审 H-M2）：诊断批量写——一行一次跨进程 call 改为攒批一次 call
    const val METHOD_APPEND_DIAG_LOG_BATCH = "append_diag_log_batch"
    const val EXTRA_DIAG_LINES = "diag_lines"
    const val EXTRA_TITLE = "title"
    const val EXTRA_ARTIST = "artist"
    const val EXTRA_DURATION_MS = "duration_ms"
    const val EXTRA_STATUS = "status"
    const val EXTRA_SOURCE = "src"
    const val EXTRA_LRC = "lrc"
    const val EXTRA_INSTRUMENTAL = "instrumental"
    const val EXTRA_ELAPSED_MS = "elapsed_ms"
    const val EXTRA_ERROR_DETAIL = "err_detail"
    const val EXTRA_DIAG_LINE = "diag_line"
    const val EXTRA_KUGOU_ID = "kugou_id"
    const val EXTRA_EXCLUDE_KUGOU_IDS = "exclude_kugou_ids"
    const val EXTRA_FORCE_LRCLIB_ONLY = "force_lrclib_only"
    const val EXTRA_NETWORK_ERROR = "net_err"
    const val EXTRA_REJECT_SCOPE = "reject_scope"
    const val REJECT_SCOPE_CANDIDATE = "candidate"
    const val REJECT_SCOPE_TRACK = "track"

    /** 模块自有诊断日志（512KB 自动滚动上限） */
    const val DIAG_LOG_FILE_NAME = "island_diag.log"
    const val DIAG_LOG_MAX_BYTES = 512 * 1024

    /** 在线查询与分级缓存策略（v1.2.8） */
    const val TIMELINE_SINGLE_SOURCE_TIMEOUT_MS = 3000
    const val TIMELINE_TOTAL_TIMEOUT_MS = 5000L
    const val TIMELINE_DURATION_TOLERANCE_MS = 5000L
    const val TIMELINE_KUGOU_MAX_DURATION_DIFF_MS = 12_000L
    const val TIMELINE_POSITIVE_CACHE_MAX = 500
    const val TIMELINE_NO_LYRIC_NEG_TTL_MS = 6L * 3600L * 1000L
    const val TIMELINE_NEGATIVE_CACHE_TTL_MS = 7L * 24L * 3600L * 1000L
    const val TIMELINE_GATE_MAX_OFFSET_ERROR_MS = 4000L
    const val TIMELINE_OFFSET_LOG_DELTA_MS = 300L
    const val TIMELINE_INTERLUDE_MIN_GAP_MS = 7000L
    /** v1.4.5 G3/G6 统一前奏保护阈值（1500ms） */
    const val TIMELINE_INTRO_GUARD_MS = 1500L
    /** v1.4.5-fix2 Y5：校验门 preVocal miss 保护阈值（与展示用 1500ms 独立，避免误统一） */
    const val GATE_PREVOCAL_GUARD_MS = 2000L

    /** 持久事件环形历史（宿主私有 prefs，200 条落盘 + 最近 40 条 5s 节流回放） */
    const val EVENT_HISTORY_PREF_NAME = "vivo_island_lyric_event_history"
    const val EVENT_HISTORY_PREF_KEY = "ring_v1"
    const val EVENT_HISTORY_MAX_ENTRIES = 200
    const val EVENT_HISTORY_REPLAY_LIMIT = 40
    const val EVENT_HISTORY_REPLAY_THROTTLE_MS = 5000L

    /**
     * 已通过实机验证的酷狗目标版本：20.9.0 (versionCode = 20909)，运行于 com.kugou.android.support 进程。
     */
    const val TARGET_KUGOU_VERSION_CODE = 20909L
    const val STRICT_VERSION_GATE = false

    /** 单钩子/单回调连续异常达到此次数后自动熔断停用 */
    const val CIRCUIT_BREAKER_THRESHOLD = 3

    /** 运行时 16 项配置键（热生效） */
    const val PREF_MASTER_SWITCH = "master_switch_enabled"
    const val PREF_GLOW_ENABLED = "island_glow_enabled"
    const val PREF_GLOW_LEVEL = "island_glow_level"
    // v1.4.6-a：独立呼吸光圈配置键（旧 PREF_GLOW_ENABLED/LEVEL 仅保留只读兼容，不再驱动光圈）
    const val PREF_HALO_ENABLED = "island_halo_enabled"
    const val PREF_GLOW_STYLE = "island_glow_style"
    const val PREF_GLOW_INTENSITY = "island_glow_intensity"
    const val PREF_GLOW_PERIOD_MS = "island_glow_period_ms"
    const val PREF_GLOW_OUTER_ENABLED = "island_glow_outer_enabled"
    const val PREF_HALO_ORBIT_ENABLED = "halo_orbit_enabled"
    const val PREF_HALO_OUTER_LEVEL = "halo_outer_level"
    const val PREF_LYRIC_TEXT_SIZE_SP = "island_lyric_text_size_sp"
    const val PREF_SCROLL_AUTO = "island_scroll_auto"
    const val PREF_SCROLL_SPEED_FACTOR = "island_scroll_speed_factor"
    const val PREF_KARAOKE_ENABLED = "island_karaoke_enabled"
    const val PREF_LINE_DISSOLVE_ENABLED = "island_line_dissolve_enabled"
    const val PREF_LINE_TRANSITION_MODE = "island_line_transition_mode"
    const val PREF_LANDSCAPE_MODE = "island_landscape_mode"
    const val PREF_LYRIC_STYLE = "island_lyric_style"
    const val PREF_INTERLUDE_DOTS_ENABLED = "island_interlude_dots_enabled"
    const val PREF_DUAL_LINE_LYRIC_ENABLED = "island_dual_line_lyric_enabled"
    const val PREF_PERSISTENT_WAVE_ENABLED = "island_persistent_wave_enabled"
    const val PREF_SHIMMER_LEVEL = "island_shimmer_level"
    const val PREF_SHIMMER_STYLE = "island_shimmer_style"
    // v1.4.5-fix13 C：流光两枚独立开关（老大 19:47 定）——倒计时流光与整岛流光各管各的，样式/强度共用
    const val PREF_SHIMMER_COUNTDOWN_ENABLED = "island_shimmer_countdown_enabled"
    const val PREF_ISLAND_SHIMMER_ENABLED = "island_island_shimmer_enabled"
    // v1.4.5-fix16：诊断级别（false＝精简：EDGE_CHECK/SCROLL_DIAG 默认关；true＝详细全开）
    const val PREF_DIAG_DETAILED = "island_diag_detailed"
    // v1.4.5-fix17（老大 2026-11-25 提议）：诊断日志总开关（默认开精简档）＋清空时间戳（岛进程据此清内存环与队列）
    const val PREF_DIAG_LOG_ENABLED = "island_diag_log_enabled"
    const val PREF_DIAG_CLEAR_AT = "island_diag_clear_at"
    const val PREF_DUAL_LINE_SUB_TEXT_SIZE_SP = "island_dual_line_sub_text_size_sp"
    const val PREF_LYRIC_CENTER_ALIGN = "island_lyric_center_align"
    const val PREF_WIDE_LYRIC_WIDTH_DP = "island_wide_lyric_width_dp"
    const val PREF_LEFT_CENTER_ALIGN = "island_left_center_align"
    const val PREF_ONLINE_TIMELINE_ENABLED = "island_online_timeline_enabled"
    const val PREF_LYRIC_LEFT_OFFSET_DP = "island_lyric_left_offset_dp"
    const val PREF_ALBUM_LEFT_PADDING_PX = "island_album_left_padding_px"
    const val PREF_LEFT_INFO_OFFSET_DP = "island_left_info_offset_dp"
    const val PREF_LEFT_INFO_MARGIN_START_DP = "island_left_info_margin_start_dp"
    const val PREF_LEFT_INFO_RIGHT_INSET_DP = "island_left_info_right_inset_dp"
    const val PREF_RIGHT_LYRIC_RIGHT_INSET_DP = "island_right_lyric_right_inset_dp"

    /** v1.2.13：配置初始化标记与宿主 com.vivo.systemuiplugin 自动镜像契约 */
    /** v1.3.2 / v1.3.3：横屏/游戏顶部纯文字悬浮歌词（默认关闭，未唱部分 84% 亮白；支持上下/左右位置与独立字号热调） */
    const val PREF_LANDSCAPE_LYRIC_ENABLED = "island_landscape_lyric_enabled"
    const val PREF_LANDSCAPE_LYRIC_TOP_MARGIN_DP = "island_landscape_lyric_top_margin_dp"
    const val PREF_LANDSCAPE_LYRIC_OFFSET_X_DP = "island_landscape_lyric_offset_x_dp"
    const val PREF_LANDSCAPE_LYRIC_TEXT_SIZE_SP = "island_landscape_lyric_text_size_sp"
    const val LANDSCAPE_LYRIC_ENABLED_DEFAULT = false
    const val LANDSCAPE_KARAOKE_UNPLAYED_ALPHA = 215 // ~84% bright white
    const val LANDSCAPE_LYRIC_TOP_MARGIN_DP = 6f
    const val LANDSCAPE_LYRIC_TOP_MARGIN_MIN_DP = 0f
    const val LANDSCAPE_LYRIC_TOP_MARGIN_MAX_DP = 120f
    const val LANDSCAPE_LYRIC_TOP_MARGIN_DEFAULT_DP = 6f
    const val LANDSCAPE_LYRIC_OFFSET_X_MIN_DP = -160f
    const val LANDSCAPE_LYRIC_OFFSET_X_MAX_DP = 160f
    const val LANDSCAPE_LYRIC_OFFSET_X_DEFAULT_DP = 0f
    const val LANDSCAPE_LYRIC_TEXT_SIZE_MIN_SP = 9.0f
    const val LANDSCAPE_LYRIC_TEXT_SIZE_MAX_SP = 24.0f
    const val LANDSCAPE_LYRIC_TEXT_SIZE_DEFAULT_SP = 13.0f

    const val PREF_CONFIG_INITIALIZED = "island_config_initialized_v1"
    const val HOST_PREFS_MIRROR_FILE = "vivo_island_lyric_prefs_mirror"
    const val HOST_PREFS_MIRROR_KEY = "mirror_json_v1"
    const val ACTION_PUSH_PREFS_MIRROR = "com.vivo.islandlyric.ACTION_PUSH_PREFS_MIRROR"
    const val ACTION_REQUEST_PREFS_MIRROR = "com.vivo.islandlyric.ACTION_REQUEST_PREFS_MIRROR"
    const val ACTION_REPLY_PREFS_MIRROR = "com.vivo.islandlyric.ACTION_REPLY_PREFS_MIRROR"
    const val EXTRA_MIRROR_JSON = "mirror_json"
    const val EXTRA_CALLER_PKG = "caller_pkg"

    const val ONLINE_TIMELINE_ENABLED_DEFAULT = true
    const val LYRIC_LEFT_OFFSET_MIN_DP = -35.0f
    const val LYRIC_LEFT_OFFSET_MAX_DP = 30.0f
    const val LYRIC_LEFT_OFFSET_DEFAULT_DP = 1.2f
    const val ALBUM_LEFT_PADDING_MIN_PX = 0
    const val ALBUM_LEFT_PADDING_MAX_PX = 40
    const val ALBUM_LEFT_PADDING_DEFAULT_PX = 14
    const val LEFT_INFO_OFFSET_MIN_DP = -20.0f
    const val LEFT_INFO_OFFSET_MAX_DP = 20.0f
    const val LEFT_INFO_OFFSET_DEFAULT_DP = 0.0f
    const val LEFT_INFO_MARGIN_START_MIN_DP = -6.0f
    const val LEFT_INFO_MARGIN_START_MAX_DP = 20.0f
    const val LEFT_INFO_MARGIN_START_DEFAULT_DP = 4.0f
    const val LEFT_INFO_RIGHT_INSET_MIN_DP = -20.0f
    const val LEFT_INFO_RIGHT_INSET_MAX_DP = 24.0f
    const val LEFT_INFO_RIGHT_INSET_DEFAULT_DP = 4.0f
    const val RIGHT_LYRIC_RIGHT_INSET_MIN_DP = 0.0f
    const val RIGHT_LYRIC_RIGHT_INSET_MAX_DP = 20.0f
    const val RIGHT_LYRIC_RIGHT_INSET_DEFAULT_DP = 2.3f

    /** v1.2.9：展开态收起时间窗、光晕缩放门控与 PreDraw 闭环锁门限 */
    const val EXPANDED_COLLAPSE_WINDOW_MS = 1800L
    const val GLOW_AND_LOCK_MIN_SCALE = 0.92f
    const val PRE_DRAW_LOCK_MIN_DELTA_PX = 0.5f

    /** 曲目指纹 v2 去抖确认窗口（毫秒） */
    const val TRACK_DEBOUNCE_MS = 1200L

    /** 播放进度回跳佐证阈值：从 >10s 跳回 <3s 视为切歌/单曲循环重播信号 */
    const val POS_REWIND_FROM_MS = 10_000L
    const val POS_REWIND_TO_MS = 5_000L
    const val OUTRO_CLEAR_GRACE_MS = 2_500L
    const val POS_REWIND_VALID_WINDOW_MS = 3_000L

    /** 展开态判定高度阈值（px）：X100s 收起胶囊 168px，展开卡片 1400px */
    const val ISLAND_EXPANDED_HEIGHT_THRESHOLD_PX = 260

    /** 宽/窄形态正测量判宽阈值（冻结不变） */
    const val WIDE_UNION_ENTER_DP = 120
    const val WIDE_UNION_EXIT_DP = 105
    const val CAP_ANIM_SCALE_FREEZE_THRESHOLD = 0.95f

    /** 窄形态 v1.0.0 胶囊文字槽样式（冻结不变） */
    const val LYRIC_TEXT_SIZE_SP = 10.5f
    const val LYRIC_MAX_WIDTH_DP = 64
    const val LYRIC_FADING_EDGE_DP = 6

    /** 宽形态左侧两行（歌名 + 歌手）样式（冻结不变） */
    const val LEFT_INFO_MARGIN_START_DP = 6
    const val LEFT_TITLE_TEXT_SIZE_SP = 11.5f
    const val LEFT_ARTIST_TEXT_SIZE_SP = 10f
    const val LEFT_TITLE_COLOR = 0xFFFFFFFF.toInt()
    const val LEFT_ARTIST_COLOR = 0xB3FFFFFF.toInt()
    const val LEFT_CENTER_ALIGN_DEFAULT = true
    const val LEFT_MARQUEE_SPEED_DP_PER_SEC = 26f
    const val LEFT_MARQUEE_GAP_DP = 28f
    const val LEFT_MARQUEE_START_HOLD_MS = 900L
    const val LEFT_MARQUEE_FADE_EDGE_DP = 4f

    /** 宽度与贴边基线常量 */
    const val WIDE_CONTENT_EXPLICIT_WIDTH_PX = 228
    const val WIDE_TEMPLATE_EXPLICIT_WIDTH_PX = 333
    const val LEFT_TEMPLATE_EDGE_PADDING_PX = 14
    const val LEFT_TEMPLATE_FACTORY_PADDING_PX = 35
    const val RIGHT_TEMPLATE_EDGE_PADDING_PX = 8
    const val RIGHT_LYRIC_FADING_EDGE_PX = 8f
    const val WIDE_LYRIC_WIDTH_DP_DEFAULT = 65
    const val WIDE_LYRIC_WIDTH_DP_MIN = 52
    const val WIDE_LYRIC_WIDTH_DP_MAX = 95

    /** 宽形态右侧整行歌词样式（字号支持设置页 10~16sp 热调节） */
    const val RIGHT_LYRIC_TEXT_SIZE_SP = 12f
    const val RIGHT_LYRIC_TEXT_SIZE_MIN_SP = 10f
    const val RIGHT_LYRIC_TEXT_SIZE_MAX_SP = 16f
    const val RIGHT_LYRIC_COLOR = 0xFFFFFFFF.toInt()

    /** 双色卡拉 OK 参数（冻结不变） */
    const val KARAOKE_ENABLED_DEFAULT = true
    /** v1.4.0：换行溶解过渡 / 双行歌词预览 / 歌词常驻声波 默认全开且独立可控 */
    const val LINE_DISSOLVE_ENABLED_DEFAULT = true
    const val LINE_TRANSITION_MODE_DEFAULT = 0
    const val LINE_TRANSITION_MODE_MIN = 0
    const val LINE_TRANSITION_MODE_MAX = 4
    /** vc55：横屏岛行为 0=摘岛（连原厂一起藏） 1=默认岛（还原＋让位） 2=歌词岛（现状），默认摘岛（老大 2026-11-26 拍板） */
    const val LANDSCAPE_MODE_DEFAULT = 0
    const val LANDSCAPE_MODE_MIN = 0
    const val LANDSCAPE_MODE_MAX = 2
    const val LANDSCAPE_MODE_OFF = 0
    const val LANDSCAPE_MODE_NATIVE = 1
    const val LANDSCAPE_MODE_LYRIC = 2
    /** vc55：换行过渡模式编号 */
    const val LINE_TRANSITION_DISSOLVE = 0
    const val LINE_TRANSITION_SOFT_FOCUS = 1
    const val LINE_TRANSITION_SCATTER = 2
    const val LINE_TRANSITION_FOG = 3
    const val LINE_TRANSITION_BREATHE = 4
    const val LYRIC_STYLE_DEFAULT = 0
    const val LYRIC_STYLE_MIN = 0
    const val LYRIC_STYLE_MAX = 6
    const val INTERLUDE_DOTS_ENABLED_DEFAULT = false
    const val LYRIC_DYNAMIC_MAX_FPS = 30
    const val LYRIC_ROUTE_A_PAD_V_PX = 4
    const val LYRIC_MAX_VERTICAL_SHIFT_PX = 3.5f
    // v1.4.6-b1（F2/F3/F4/F5/F6/F7）：歌词专用色板亮度下限、极光退化固定三色、各样式位移钳数组与双行位移上限
    const val LYRIC_PALETTE_MIN_L = 0.55f
    val LYRIC_AURORA_FALLBACK_PALETTE = intArrayOf(
        0xFF35E0D0.toInt(),
        0xFF4E9AF1.toInt(),
        0xFF9B6BF3.toInt()
    )
    val LYRIC_STYLE_SHIFT_CAP_PX = intArrayOf(0, 0, 8, 5, 3, 9, 0)
    const val DUAL_LINE_SHIFT_CAP_PX = 3.5f
    // vc54：⑥波浪双行专用位移上限（用户真机反馈双行波得不够强，3.5→5.0；其余样式双行仍 3.5）
    const val DUAL_LINE_WAVE_SHIFT_CAP_PX = 5.0f
    // PROTOTYPE: 待真机截图锁数（⑤聚光灯 / ⑦水墨晕染）
    const val LYRIC_SPOTLIGHT_RADIUS_FACTOR = 1.6f
    const val LYRIC_SPOTLIGHT_CORE_ALPHA = 220
    const val LYRIC_SPOTLIGHT_MID_ALPHA = 0xB0
    const val LYRIC_SPOTLIGHT_DIM_FACTOR = 0.78f
    const val LYRIC_SPOTLIGHT_UNPLAYED_ALPHA = 100
    const val LYRIC_INK_BLOTCH_ALPHA = 160
    const val LYRIC_INK_STROKE_MIN_PX = 1.5f
    const val LYRIC_INK_STROKE_MAX_PX = 3.0f
    const val LYRIC_INK_STROKE_ALPHA = 160
    const val LYRIC_INK_GHOST_MAX_ALPHA = 160
    const val INTERLUDE_DOT_PHASE_DIVISOR_MS = 420f
    const val INTERLUDE_DOT_PHASE_STEP_RAD = 0.7f
    const val DUAL_LINE_LYRIC_ENABLED_DEFAULT = true
    const val PERSISTENT_WAVE_ENABLED_DEFAULT = true
    const val LINE_DISSOLVE_DURATION_MS = 300L
    const val DUAL_LINE_MAIN_MAX_SP = 12.5f
    const val DUAL_LINE_SUB_SCALE = 0.72f
    const val DUAL_LINE_SUB_MIN_SP = 6.5f
    const val DUAL_LINE_SUB_MAX_SP = 11.5f
    const val DUAL_LINE_SUB_TEXT_SIZE_DEFAULT_SP = 7.8f
    const val LYRIC_CENTER_ALIGN_DEFAULT = false
    const val DUAL_LINE_SUB_ALPHA = 145
    const val KARAOKE_UNPLAYED_ALPHA = 140 // ~55% alpha
    const val KARAOKE_AMBER_FALLBACK = 0xFFFFC46B.toInt()
    const val KARAOKE_MIN_VALUE = 0.88f

    /** 前奏/间奏倒计时与宿主私有落盘学习参数 */
    const val INTRO_EQ_BAR_COUNT = 5
    const val INTRO_EQ_BAR_WIDTH_DP = 3f
    const val INTRO_EQ_BAR_GAP_DP = 2f
    const val INTRO_EQ_BAR_MAX_HEIGHT_DP = 14f
    const val INTRO_RIGHT_EQ_BAR_COUNT = 9
    const val INTRO_RIGHT_EQ_BAR_WIDTH_DP = 3f
    const val INTRO_RIGHT_EQ_BAR_GAP_DP = 3f
    const val INTRO_RIGHT_EQ_BAR_MAX_HEIGHT_DP = 18f
    const val INTRO_EQ_PERIOD_MS = 920L
    const val INTRO_COUNTDOWN_TEXT_SIZE_SP = 11f
    const val INTRO_RIGHT_COUNTDOWN_TEXT_SIZE_SP = 16f
    const val INTRO_COUNTDOWN_MAX_REMAINING_MS = 15_000L
    /** v1.3.13：前奏波浪段按总长分档（>=20s 前 8s、10-20s 前 6s、<=10s 前 4s） */
    const val INTRO_WAVE_LEAD_LONG_THRESHOLD_MS = 20_000L
    const val INTRO_WAVE_LEAD_MEDIUM_THRESHOLD_MS = 10_000L
    const val INTRO_WAVE_LEAD_LONG_MS = 8_000L
    const val INTRO_WAVE_LEAD_MEDIUM_MS = 6_000L
    const val INTRO_WAVE_LEAD_SHORT_MS = 4_000L
    /** v1.3.13：胶囊入场缩放动画落稳单次补刷延时（零轮询） */
    const val HOLD_SETTLE_DELAY_MS = 220L
    const val INTRO_MIN_VALID_START_MS = 5000L
    const val INTRO_SAFETY_TIMEOUT_MS = 60_000L
    const val INTRO_REENTER_DEBOUNCE_MS = 3_000L
    const val INTRO_LEARN_FIX_THRESHOLD_MS = 800L
    const val INTRO_LEARN_PREF_NAME = "vivo_island_lyric_intro_cache"
    const val INTRO_LEARN_PREF_KEY = "first_line_map_v2"
    const val INTRO_LEARN_INTERLUDES_KEY = "interlude_spans_v2"
    const val INTRO_LEARN_CACHE_MAX = 200

    /** C1 / C3 / C4 滚动自适应与间奏检测参数（冻结不变） */
    const val SCROLL_AUTO_DEFAULT = true
    const val SCROLL_SPEED_FACTOR_DEFAULT = 1.0f
    const val SCROLL_SPEED_FACTOR_MIN = 0.5f
    const val SCROLL_SPEED_FACTOR_MAX = 2.0f
    const val SCROLL_SAMPLE_CAPACITY = 6
    const val SCROLL_MIN_VALID_SAMPLE_MS = 800L
    const val SCROLL_MIN_WINDOW_MS = 2500L
    const val SCROLL_MAX_WINDOW_MS = 12_000L
    const val SCROLL_DEFAULT_WINDOW_MS = 5000L

    const val SCROLL_START_HOLD_MS = 300L
    const val SCROLL_END_HOLD_MS = 500L
    const val SCROLL_FADE_BACK_MS = 250L
    const val SCROLL_EASE_COMPRESS_THRESHOLD_MS = 900L

    const val INTERLUDE_MIN_THRESHOLD_MS = 8000L
    const val INTERLUDE_MULTIPLIER = 1.8
    const val INTRO_DOTS_THRESHOLD_MS = 3000L

    /** 自建注入视图防重复 Tag */
    const val TAG_LEFT_INFO_CONTAINER = "vivo_island_lyric_left_info_v11"
    const val TAG_LEFT_TITLE_TV = "vivo_island_lyric_left_title_v122"
    const val TAG_LEFT_ARTIST_TV = "vivo_island_lyric_left_artist_v122"
    const val TAG_RIGHT_INTRO_COUNTDOWN = "vivo_island_lyric_right_intro_cd_v126"
    const val TAG_RIGHT_INTERLUDE_COUNTDOWN = "vivo_island_lyric_right_interlude_cd_v125"
    const val TAG_RIGHT_LYRIC_VIEW = "vivo_island_lyric_right_scroll_v12"

    /** 原子岛真实类与资源 ID */
    const val CLS_ISLAND_WINDOW_VIEW = "com.vivo.island.view.IslandWindowView"
    const val CLS_DYNAMIC_CAPSULE_CONTAINER = "com.vivo.island.view.DynamicCapsuleContainer"
    const val CLS_DYNAMIC_CAPSULE_VIEW = "com.vivo.island.view.DynamicCapsuleView"
    const val CLS_LEFT_TEMPLATE = "com.vivo.island.template.AtomMainIslandLeftTemplate"
    const val CLS_RIGHT_TEMPLATE = "com.vivo.island.template.AtomMainIslandRightTemplate"
    const val CLS_TEMPLATE_FRAME_LAYOUT = "com.vivo.island.template.IslandTemplateFrameLayout"
    const val CLS_CAPSULE_CONTENT_VIEW = "com.vivo.island.view.CapsuleContentView"
    const val CLS_TEXT_SWITCHER = "com.vivo.island.template.VivoTextSwitcher"
    const val CLS_MUSIC_WAVE_VIEW = "com.vivo.island.widget.music.IslandMusicWaveView"

    const val ID_ICON_IMAGE_LEFT = "vivo_atom_main_island_icon_image_left"
    const val ID_TEXT_SWITCHER_LEFT = "vivo_atom_main_island_text_switcher_left"
    const val ID_TEXT_LEFT1 = "vivo_atom_main_island_icon_text_left1"
    const val ID_TEXT_LEFT2 = "vivo_atom_main_island_icon_text_left2"
    const val ID_CAP_LEFT = "cap_left"
    const val ID_CAP_RIGHT = "cap_right"
    const val ID_CAP_EXPANDED = "cap_expanded"
    const val ID_REAL_CAP_CONTAINER = "real_cap_container"
    const val ID_NEW_DYNAMIC_CAPS = "new_dynamic_caps"
    const val ID_MUSIC_WAVE = "vivo_atom_main_island_icon_music"

    /** 宽态视图树转储参数与真宽态门控（并集 >= 700px） */
    const val WIDE_TREE_DUMP_NODE_LIMIT = 120
    const val WIDE_TREE_DUMP_FALLBACK_MS = 1500L
    const val WIDE_TREE_MIN_UNION_PX = 700f

    /** 单字段日志预览上限 */
    const val LOG_PREVIEW_LIMIT = 80

    /** 边缘炫光与岛内流光（InnerAurora）参数（冻结不变） */
    const val GLOW_ENABLED_DEFAULT = true
    const val GLOW_LEVEL_DEFAULT = 100
    const val GLOW_LEVEL_MIN = 20
    const val GLOW_LEVEL_MAX = 200
    const val GLOW_LEVEL_MIN_SCALE = 0.2f
    const val GLOW_LEVEL_MAX_SCALE = 2.0f

    /** v1.4.6-a 正式双层呼吸光圈默认与边界参数（独立开关默认关、强度默认淡、周期默认 4s、外圈默认开） */
    const val HALO_ENABLED_DEFAULT = false
    const val GLOW_STYLE_DEFAULT = 0
    const val GLOW_STYLE_MIN = 0
    const val GLOW_STYLE_MAX = 5
    const val GLOW_INTENSITY_DEFAULT = 0
    const val GLOW_INTENSITY_MIN = 0
    const val GLOW_INTENSITY_MAX = 2
    const val GLOW_PERIOD_MS_DEFAULT = 4000
    const val GLOW_PERIOD_MS_FAST = 2500
    const val GLOW_PERIOD_MS_SLOW = 6000
    const val GLOW_OUTER_ENABLED_DEFAULT = true
    const val HALO_ORBIT_ENABLED_DEFAULT = true
    const val HALO_OUTER_LEVEL_DEFAULT = 2
    const val HALO_OUTER_LEVEL_MIN = 0
    const val HALO_OUTER_LEVEL_MAX = 4
    const val HALO_OUTER_MAX_PX = 14f
    const val HALO_OUTER_HARD_CAP_PX = 16f
    const val HALO_SPOT_OUTWARD_OFFSET_PX = 8f
    const val HALO_SPOT_MAX_RADIUS_PX = 10f
    const val HALO_SPOT_HARD_CAP_PX = 18f
    const val HALO_INNER_RIM_MAX_PX = 3.0f
    const val HALO_INNER_DIFFUSE_MAX_PX = 16f
    const val HALO_COLOR_FADE_MS = 400L
    const val HALO_MIN_HSL_LIGHTNESS = 0.65f
    const val HALO_FALLBACK_CUTOUT_HALF_W_PX = 49f

    /** 前奏倒计时流光强度档位（v1.4.5-fix12，老大定：多加几档自选）：0=淡（fix11 原强度）/ 1=中 / 2=亮 */
    const val SHIMMER_LEVEL_DEFAULT = 0
    const val SHIMMER_LEVEL_MIN = 0
    const val SHIMMER_LEVEL_MAX = 2

    /** 前奏倒计时流光样式（v1.4.5-fix12，老大 2026-11-24 圈定）：0=横扫 / 1=呼吸 / 2=极光 / 3=封面色 / 4=星点 / 5=斜扫 / 6=文字（光只在倒计时数字身上流动） */
    const val SHIMMER_STYLE_DEFAULT = 0
    const val SHIMMER_STYLE_MIN = 0
    const val SHIMMER_STYLE_MAX = 6

    /** v1.4.5-fix13 C：两枚流光开关默认全开（倒计时流光为 fix11 以来的现行行为；整岛流光为老大点名新功能，装上即可见） */
    const val SHIMMER_COUNTDOWN_ENABLED_DEFAULT = true
    const val ISLAND_SHIMMER_ENABLED_DEFAULT = true
    const val DIAG_DETAILED_DEFAULT = false
    const val DIAG_LOG_ENABLED_DEFAULT = true

    const val GLOW_INSET_PX = 0f
    const val GLOW_GEOM_EPSILON_PX = 0.05f
    const val GLOW_AURORA_CLIP_INSET_PX = 2f
    const val GLOW_AURORA_PERIOD_1_MS = 6000L
    const val GLOW_AURORA_PERIOD_2_MS = 7800L
    const val GLOW_AURORA_ALPHA_1 = 26
    const val GLOW_AURORA_ALPHA_2 = 17
    const val GLOW_STEADY_FRAME_DELAY_MS = 33L

    val GLOW_LAYER_WIDTHS_DP = floatArrayOf(10f, 7f, 5f, 3f, 1.6f)
    val GLOW_LAYER_ALPHAS = intArrayOf(10, 16, 30, 64, 150)

    const val GLOW_UNION_GAP_DP = 8
    const val GLOW_GRADIENT_EXTEND_DP = 24
    const val TAG_GLOW_VIEW = "vivo_island_lyric_glow_v11"

    const val GLOW_BREATH_PERIOD_MS = 2400L
    const val GLOW_BREATH_MIN_INTENSITY = 0.78f
    const val GLOW_BREATH_MAX_INTENSITY = 1.0f
    const val GLOW_PAUSED_INTENSITY = 0.35f
    const val GLOW_COLOR_FADE_MS = 400L

    const val GLOW_SAMPLE_MAX_DIM = 32
    const val GLOW_GRAYSCALE_SAT_THRESHOLD = 0.12f
    const val GLOW_PALETTE_CACHE_MAX = 32
    val GLOW_SILVER_PALETTE = intArrayOf(
        0xFFB4BCC8.toInt(),
        0xFFD4DCE6.toInt(),
        0xFFFFFFFF.toInt(),
        0xFFCFD7E2.toInt(),
        0xFFAAB3C0.toInt()
    )
}
