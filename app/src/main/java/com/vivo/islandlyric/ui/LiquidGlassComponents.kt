/*
 * Copyright 2025 Kyant
 *
 * Adapted from Kyant0/AndroidLiquidGlass catalog components
 * (LiquidBottomTabs.kt, LiquidToggle.kt, LiquidSlider.kt, LiquidButton.kt),
 * powered by io.github.kyant0:backdrop.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.vivo.islandlyric.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

internal val LocalLiquidBottomTabScale = compositionLocalOf<() -> Float> { { 1f } }

/**
 * 注册页级停帧回调：当 Activity 触发 onPause / onStop 时立即收束所有组件弹簧动画。
 */
internal val LocalPauseRegistry = compositionLocalOf<MutableList<() -> Unit>?> { null }

@Composable
internal fun RegisterPauseSnap(onPause: () -> Unit) {
    val registry = LocalPauseRegistry.current
    DisposableEffect(registry, onPause) {
        registry?.add(onPause)
        onDispose { registry?.remove(onPause) }
    }
}

/**
 * 官方结构 LiquidBottomTabs（v1.3.7 极透水晶＋点按水滴弹出滑移调校）：
 * - 底层极透水晶胶囊：极低表面遮罩（浅色 8% / 深色 14%）＋极轻模糊（1.2dp）＋边缘凸透镜色散折射（22/26dp）；
 * - 顶层液态水滴指示器：无论手指按住拖拽，还是直接点按其他标签，都会触发水滴突出膨胀＋色散折射＋果冻滑移落位。
 */
@Composable
internal fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor = if (isLightTheme) Color(0xFF007AFF) else Color(0xFF0A84FF)
    // 极透水晶底罩：显著降低发白/发灰遮罩浓度，让下方文字与绿色开关清晰折射穿透
    val containerColor = if (isLightTheme) Color(0xFFFFFFFF).copy(0.04f) else Color(0xFF1C1C1E).copy(0.09f)

    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            ((constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount).coerceAtLeast(1f)
        }

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val width = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val fraction = (offsetAnimation.value / width).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember(selectedTabIndex) {
            mutableIntStateOf(selectedTabIndex())
        }
        var lastChangeFromDrag by remember { mutableStateOf(false) }
        val currentOnTabSelected by rememberUpdatedState(onTabSelected)

        val dampedDragAnimation = remember(animationScope, tabWidth, tabsCount) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.24f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    lastChangeFromDrag = true
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    currentOnTabSelected(targetIndex)
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }

        LaunchedEffect(selectedTabIndex) {
            snapshotFlow { selectedTabIndex() }
                .collectLatest { index ->
                    val changed = index != currentIndex
                    currentIndex = index
                    if (lastChangeFromDrag) {
                        lastChangeFromDrag = false
                    } else if (changed) {
                        // 点按切页：触发受控水滴弹出 + 微色散折射 + 弹簧滑移（连点即时收束防重影）
                        dampedDragAnimation.animateTapPopToValue(index.toFloat())
                    }
                }
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        RegisterPauseSnap {
            dampedDragAnimation.snapToRest()
            interactiveHighlight.snapToRest()
            animationScope.launch { offsetAnimation.snapTo(0f) }
        }

        // Layer A：极透水晶底栏主体
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        vibrancy()
                        blur(1.0f.dp.toPx())
                        lens(
                            18f.dp.toPx(),
                            22f.dp.toPx()
                        )
                    },
                    highlight = { Highlight.Default },
                    shadow = {
                        Shadow(
                            radius = 12f.dp,
                            color = Color.Black.copy(alpha = if (isLightTheme) 0.08f else 0.34f)
                        )
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 8f.dp.toPx() / size.width.coerceAtLeast(1f), progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                .height(64f.dp)
                .fillMaxWidth()
                .padding(4f.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )

        // Layer B：隐藏高亮染色采样源（与 Layer A 等高同缩放对齐，轻度微放大 1.07x，避免水滴滑过时双字错位重影）
        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.07f, dampedDragAnimation.pressProgress)
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { CircleShape },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(1.0f.dp.toPx())
                            lens(18f.dp.toPx() * progress, 22f.dp.toPx() * progress)
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        layerBlock = {
                            val progress = dampedDragAnimation.pressProgress
                            val scale = lerp(1f, 1f + 8f.dp.toPx() / size.width.coerceAtLeast(1f), progress)
                            scaleX = scale
                            scaleY = scale
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(64f.dp)
                    .fillMaxWidth()
                    .padding(4f.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        // Layer C：可拖拽/点按弹出的液态水滴透镜指示器（带边缘色散 chromaticAberration）
        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX = if (isLtr) {
                        dampedDragAnimation.value * tabWidth + panelOffset
                    } else {
                        size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                    }
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { CircleShape },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        lens(
                            4f.dp.toPx() + 5f.dp.toPx() * progress,
                            6f.dp.toPx() + 7f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Default.copy(alpha = progress)
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        Shadow(alpha = progress)
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 8f.dp * progress,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 14f
                        scaleX /= 1f - (velocity * 0.55f).fastCoerceIn(-0.08f, 0.08f)
                        scaleY *= 1f - (velocity * 0.22f).fastCoerceIn(-0.06f, 0.06f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(
                            if (isLightTheme) Color.Black.copy(0.055f) else Color.White.copy(0.09f),
                            alpha = 1f - progress
                        )
                        drawRect(Color.Black.copy(alpha = 0.02f * progress))
                    }
                )
                .height(56f.dp)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}

@Composable
internal fun RowScope.LiquidBottomTab(
    label: String,
    selected: Boolean,
    iconType: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLightTheme = !isSystemInDarkTheme()
    val scale = LocalLiquidBottomTabScale.current
    val activeColor = if (isLightTheme) Color(0xFF007AFF) else Color(0xFF0A84FF)
    val idleColor = if (isLightTheme) Color(0xFF1C1C1E) else Color(0xFFE5E5EA)
    val tint = if (selected) activeColor else idleColor

    Column(
        modifier = modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .weight(1f)
            .height(56.dp)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
    ) {
        TabVectorIcon(iconType = iconType, color = tint, modifier = Modifier.size(21.dp))
        BasicText(
            text = label,
            style = TextStyle(
                color = tint,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            maxLines = 1
        )
    }
}

/**
 * 官方结构 LiquidToggle：
 * 轨道作为同级 layerBackdrop 采样源（绝不形成祖先循环），滑块按压/拖动时化为 1.45x 透明色散水滴。
 */
@Composable
internal fun LiquidToggle(
    selected: () -> Boolean,
    onSelect: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor = if (isLightTheme) Color(0xFF34C759) else Color(0xFF30D158)
    val trackColor = if (isLightTheme) Color(0xFF787880).copy(0.22f) else Color(0xFF787880).copy(0.38f)

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(density) { 20f.dp.toPx() }
    val touchSlopPx = with(density) { 8f.dp.toPx() }
    val animationScope = rememberCoroutineScope()
    var didDrag by remember { mutableStateOf(false) }
    var totalDragX by remember { mutableFloatStateOf(0f) }
    var fraction by remember { mutableFloatStateOf(if (selected()) 1f else 0f) }
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)

    val dampedDragAnimation = remember(animationScope, enabled) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.45f,
            enabled = { enabled },
            onDragStarted = {
                didDrag = false
                totalDragX = 0f
            },
            onDragStopped = {
                val next = if (didDrag) {
                    targetValue >= 0.5f
                } else {
                    !currentSelected()
                }
                fraction = if (next) 1f else 0f
                currentOnSelect(next)
                didDrag = false
                totalDragX = 0f
            },
            onDrag = { _, dragAmount ->
                totalDragX += dragAmount.x
                if (!didDrag && abs(totalDragX) >= touchSlopPx) {
                    didDrag = true
                }
                if (didDrag) {
                    val delta = dragAmount.x / dragWidth
                    fraction = if (isLtr) (fraction + delta).fastCoerceIn(0f, 1f)
                    else (fraction - delta).fastCoerceIn(0f, 1f)
                }
            },
            onDragCancelled = {
                val restored = if (currentSelected()) 1f else 0f
                fraction = restored
                animateToValue(restored)
                didDrag = false
                totalDragX = 0f
            }
        )
    }

    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }
            .collectLatest { value ->
                dampedDragAnimation.updateValue(value)
            }
    }
    LaunchedEffect(selected) {
        snapshotFlow { selected() }
            .collectLatest { isSelected ->
                val target = if (isSelected) 1f else 0f
                if (target != fraction) {
                    fraction = target
                    dampedDragAnimation.animateToValue(target)
                }
            }
    }

    RegisterPauseSnap { dampedDragAnimation.snapToRest() }

    val trackBackdrop = rememberLayerBackdrop()

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .semantics { role = Role.Switch }
            .then(dampedDragAnimation.modifier),
        contentAlignment = Alignment.CenterStart
    ) {
        // 同级轨道采样层
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(CircleShape)
                .drawBehind {
                    drawRect(lerp(trackColor, accentColor, dampedDragAnimation.value))
                }
                .size(60f.dp, 28f.dp)
        )

        // 同级液态玻璃滑块
        Box(
            Modifier
                .graphicsLayer {
                    val value = dampedDragAnimation.value
                    val padding = 2f.dp.toPx()
                    translationX = if (isLtr) lerp(padding, padding + dragWidth, value)
                    else lerp(-padding, -(padding + dragWidth), value)
                }
                .drawBackdrop(
                    backdrop = rememberBackdrop(trackBackdrop) { drawBackdrop ->
                        val progress = dampedDragAnimation.pressProgress
                        val sx = lerp(0.72f, 0.82f, progress)
                        val sy = lerp(0.55f, 0.82f, progress)
                        scale(sx, sy) { drawBackdrop() }
                    },
                    shape = { CircleShape },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(4f.dp.toPx() * (1f - progress))
                        lens(
                            5f.dp.toPx() * progress,
                            10f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4f.dp,
                            color = Color.Black.copy(alpha = 0.08f)
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 4f.dp * progress,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 50f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress * 0.88f))
                    }
                )
                .size(36f.dp, 24f.dp)
        )
    }
}

/**
 * 官方风格 LiquidSlider：
 * 轨道作为同级 layerBackdrop 采样源，按压/拖动时拇指膨胀为带色散折射的水滴胶囊。
 */
@Composable
internal fun LiquidSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor = if (isLightTheme) Color(0xFF007AFF) else Color(0xFF0A84FF)
    val trackBg = if (isLightTheme) Color(0xFF787880).copy(0.20f) else Color(0xFF787880).copy(0.36f)
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)

    val animationScope = rememberCoroutineScope()
    val trackBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val thumbWidthDp = 36f.dp
        val thumbWidthPx = with(density) { thumbWidthDp.toPx() }
        val usableWidth = (constraints.maxWidth.toFloat() - thumbWidthPx).coerceAtLeast(1f)

        fun toFraction(v: Float): Float = ((v - valueRange.start) / span).fastCoerceIn(0f, 1f)
        fun fromFraction(f: Float): Float =
            (valueRange.start + f.fastCoerceIn(0f, 1f) * span).fastCoerceIn(valueRange.start, valueRange.endInclusive)

        val currentValue by rememberUpdatedState(value)
        val currentOnValueChange by rememberUpdatedState(onValueChange)

        val dampedDragAnimation = remember(animationScope, usableWidth, enabled, valueRange) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = toFraction(currentValue()),
                valueRange = 0f..1f,
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.42f,
                enabled = { enabled },
                onDragStarted = {},
                onDragStopped = {
                    currentOnValueChange(fromFraction(targetValue))
                },
                onDrag = { _, dragAmount ->
                    val delta = (dragAmount.x / usableWidth) * if (isLtr) 1f else -1f
                    val next = (targetValue + delta).fastCoerceIn(0f, 1f)
                    updateValue(next)
                    currentOnValueChange(fromFraction(next))
                },
                onDragCancelled = {
                    animateToValue(toFraction(currentValue()))
                }
            )
        }

        LaunchedEffect(value, valueRange) {
            snapshotFlow { value() }
                .collectLatest { external ->
                    val target = toFraction(external)
                    if (abs(target - dampedDragAnimation.targetValue) > 0.0015f) {
                        dampedDragAnimation.animateToValue(target)
                    }
                }
        }

        RegisterPauseSnap { dampedDragAnimation.snapToRest() }

        // 同级轨道采样层
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .layerBackdrop(trackBackdrop)
                .clip(CircleShape)
                .drawBehind {
                    drawRect(trackBg)
                    val activeW = size.width * dampedDragAnimation.value.fastCoerceIn(0f, 1f)
                    drawRect(
                        color = accentColor,
                        topLeft = Offset(if (isLtr) 0f else size.width - activeW, 0f),
                        size = Size(activeW, size.height)
                    )
                }
        )

        // 同级液态玻璃水滴拇指
        Box(
            Modifier
                .graphicsLayer {
                    val f = dampedDragAnimation.value.fastCoerceIn(0f, 1f)
                    translationX = if (isLtr) f * usableWidth else (1f - f) * usableWidth
                }
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberBackdrop(trackBackdrop) { drawBackdrop ->
                        drawBackdrop()
                    },
                    shape = { CircleShape },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(3f.dp.toPx() * (1f - progress))
                        lens(
                            5f.dp.toPx() * progress,
                            10f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.4f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.4f,
                            alpha = progress
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 5f.dp,
                            color = Color.Black.copy(alpha = if (isLightTheme) 0.14f else 0.36f)
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 4f.dp * progress,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 55f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.18f, 0.18f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.18f, 0.18f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress * 0.86f))
                    }
                )
                .size(thumbWidthDp, 22f.dp)
        )
    }
}

@Composable
internal fun StepPillButton(
    symbol: String,
    enabled: Boolean,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val bg = if (isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)
    val fg = if (isDark) Color(0xFFF5F5F7) else Color(0xFF1C1C1E)
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(bg)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = symbol,
            style = TextStyle(
                color = fg,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

@Composable
internal fun GlassActionButton(
    label: String,
    danger: Boolean,
    enabled: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = when {
        danger && isDark -> Color(0xFF3A1C1C)
        danger -> Color(0xFFFFEFEF)
        isDark -> Color(0xFF2C2C2E)
        else -> Color(0xFFF2F2F7)
    }
    val border = when {
        danger && isDark -> Color(0x55FF453A)
        danger -> Color(0x44FF3B30)
        isDark -> Color(0x28FFFFFF)
        else -> Color(0x18000000)
    }
    val fg = when {
        danger && isDark -> Color(0xFFFF453A)
        danger -> Color(0xFFFF3B30)
        isDark -> Color(0xFFF5F5F7)
        else -> Color(0xFF1C1C1E)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(0.8.dp, border, RoundedCornerShape(14.dp))
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = fg,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

/**
 * 轻量自绘底栏矢量图标（零 material-icons-extended 体积膨胀）：
 * 0 = 常规（播放/圆环）, 1 = 竖屏（手机）, 2 = 横屏（横屏游戏）, 3 = 备份（下载/归档）。
 */
@Composable
internal fun TabVectorIcon(
    iconType: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = (w * 0.09f).coerceAtLeast(1.6f)
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (iconType) {
            0 -> {
                drawCircle(
                    color = color,
                    radius = w * 0.41f,
                    center = Offset(w * 0.5f, h * 0.5f),
                    style = stroke
                )
                val tri = Path().apply {
                    moveTo(w * 0.42f, h * 0.34f)
                    lineTo(w * 0.66f, h * 0.50f)
                    lineTo(w * 0.42f, h * 0.66f)
                    close()
                }
                drawPath(tri, color = color)
            }
            1 -> {
                val padX = w * 0.25f
                val padY = h * 0.12f
                drawRoundRect(
                    color = color,
                    topLeft = Offset(padX, padY),
                    size = Size(w - padX * 2f, h - padY * 2f),
                    cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
                    style = stroke
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.42f, h * 0.26f),
                    end = Offset(w * 0.58f, h * 0.26f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round
                )
            }
            2 -> {
                val padX = w * 0.10f
                val padY = h * 0.24f
                drawRoundRect(
                    color = color,
                    topLeft = Offset(padX, padY),
                    size = Size(w - padX * 2f, h - padY * 2f),
                    cornerRadius = CornerRadius(w * 0.16f, w * 0.16f),
                    style = stroke
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.32f, h * 0.46f),
                    end = Offset(w * 0.68f, h * 0.46f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round
                )
            }
            else -> {
                drawLine(
                    color = color,
                    start = Offset(w * 0.5f, h * 0.16f),
                    end = Offset(w * 0.5f, h * 0.64f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round
                )
                val arrow = Path().apply {
                    moveTo(w * 0.33f, h * 0.48f)
                    lineTo(w * 0.50f, h * 0.65f)
                    lineTo(w * 0.67f, h * 0.48f)
                }
                drawPath(arrow, color = color, style = stroke)
                val tray = Path().apply {
                    moveTo(w * 0.20f, h * 0.64f)
                    lineTo(w * 0.20f, h * 0.82f)
                    lineTo(w * 0.80f, h * 0.82f)
                    lineTo(w * 0.80f, h * 0.64f)
                }
                drawPath(tray, color = color, style = stroke)
            }
        }
    }
}
