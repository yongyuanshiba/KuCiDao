/*
 * Copyright 2025 Kyant
 *
 * Adapted from Kyant0/AndroidLiquidGlass (app catalog utils:
 * DampedDragAnimation.kt & InteractiveHighlight.kt).
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

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * 阻尼弹簧拖拽与点按弹出控制器（仅在手势或点按切换期间驱动有限短程弹簧，静止零重绘、零无限循环）。
 */
internal class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedFloatingPointRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val enabled: () -> Boolean = { true },
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit = {},
    val onDragStopped: DampedDragAnimation.() -> Unit = {},
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit = { _, _ -> },
    val onDragCancelled: DampedDragAnimation.() -> Unit = {}
) {
    private val valueAnimationSpec = spring(
        dampingRatio = 1f,
        stiffness = 1000f,
        visibilityThreshold = visibilityThreshold
    )
    private val tapGlideValueSpec = spring(
        dampingRatio = 0.84f,
        stiffness = 460f,
        visibilityThreshold = visibilityThreshold
    )
    private val fastTapGlideValueSpec = spring(
        dampingRatio = 0.90f,
        stiffness = 620f,
        visibilityThreshold = visibilityThreshold
    )
    private val velocityAnimationSpec = spring(
        dampingRatio = 0.65f,
        stiffness = 340f,
        visibilityThreshold = visibilityThreshold * 10f
    )
    private val pressProgressAnimationSpec = spring(
        dampingRatio = 1f,
        stiffness = 950f,
        visibilityThreshold = 0.001f
    )
    private val scaleXAnimationSpec = spring(
        dampingRatio = 0.72f,
        stiffness = 480f,
        visibilityThreshold = 0.001f
    )
    private val scaleYAnimationSpec = spring(
        dampingRatio = 0.72f,
        stiffness = 480f,
        visibilityThreshold = 0.001f
    )

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()
    private var tapPopJob: Job? = null
    private var lastTapUptimeMs: Long = 0L

    val value: Float
        get() = valueAnimation.value

    var targetValue: Float = initialValue
        private set

    val velocity: Float
        get() = velocityAnimation.value

    val pressProgress: Float
        get() = pressProgressAnimation.value

    val scaleX: Float
        get() = scaleXAnimation.value

    val scaleY: Float
        get() = scaleYAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!enabled()) return@awaitEachGesture

            tapPopJob?.cancel()
            tapPopJob = null
            velocityTracker.resetTracking()
            velocityTracker.addPosition(down.uptimeMillis, down.position)
            press()

            var lastPosition = down.position
            var totalDx = 0f
            var totalDy = 0f
            var horizontalLocked = false
            var dragStartedDispatched = false
            var cancelledByVerticalScroll = false
            val touchSlopPx = viewConfiguration.touchSlop
            val completed = drag(down.id) { change ->
                val delta = change.positionChange()
                totalDx += delta.x
                totalDy += delta.y
                if (!horizontalLocked) {
                    val absDx = abs(totalDx)
                    val absDy = abs(totalDy)
                    if (absDy > touchSlopPx && absDy > absDx * 1.15f) {
                        cancelledByVerticalScroll = true
                        return@drag
                    }
                    if (absDx > touchSlopPx && absDx >= absDy) {
                        horizontalLocked = true
                        if (!dragStartedDispatched) {
                            dragStartedDispatched = true
                            onDragStarted(down.position)
                        }
                    }
                }
                if (cancelledByVerticalScroll) return@drag
                if (horizontalLocked && delta != Offset.Zero) {
                    change.consume()
                }
                lastPosition = change.position
                velocityTracker.addPosition(change.uptimeMillis, lastPosition)
                if (horizontalLocked) {
                    onDrag(size, delta)
                    val vx = runCatching { velocityTracker.calculateVelocity().x }.getOrDefault(0f)
                    animationScope.launch {
                        velocityAnimation.snapTo((vx / 120f).coerceIn(-40f, 40f))
                    }
                }
            }

            release()
            if (completed && !cancelledByVerticalScroll) {
                if (!dragStartedDispatched) {
                    dragStartedDispatched = true
                    onDragStarted(down.position)
                }
                onDragStopped()
            } else {
                onDragCancelled()
            }
        }
    }

    fun press() {
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
            launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val clamped = value.coerceIn(valueRange.start, valueRange.endInclusive)
        targetValue = clamped
        animationScope.launch {
            mutatorMutex.mutate {
                valueAnimation.snapTo(clamped)
            }
        }
    }

    fun animateToValue(value: Float) {
        val clamped = value.coerceIn(valueRange.start, valueRange.endInclusive)
        targetValue = clamped
        animationScope.launch {
            mutatorMutex.mutate {
                valueAnimation.animateTo(clamped, valueAnimationSpec)
            }
        }
    }

    /**
     * 点按切页专用的「水滴弹出→受控拉伸滑移→落位回弹」有限短程动效（v1.3.9 收敛优化）：
     * - 收紧横向膨胀幅度（scaleX <= 1.10x）并保留清晰纵向弹出（scaleY 1.18x），不跨界覆盖相邻标签文字；
     * - 快速连点时立即收束上一次缩放与速度脉冲，切入更利落的短程滑移，避免双字重叠重影。
     */
    fun animateTapPopToValue(value: Float) {
        val clamped = value.coerceIn(valueRange.start, valueRange.endInclusive)
        val delta = clamped - valueAnimation.value
        targetValue = clamped
        if (abs(delta) < 0.01f) return

        val now = SystemClock.uptimeMillis()
        val isRapidTap = tapPopJob?.isActive == true || (now - lastTapUptimeMs) < 280L
        lastTapUptimeMs = now

        tapPopJob?.cancel()
        tapPopJob = animationScope.launch {
            val dir = delta.sign
            val distFactor = abs(delta).coerceIn(1f, 3f)

            if (isRapidTap) {
                // 快速连点：立即收束上一次残留膨胀与速度，避免透镜停在交界处产生双字重影
                scaleXAnimation.snapTo(initialScale)
                scaleYAnimation.snapTo(initialScale)
                velocityAnimation.snapTo(0f)
                if (pressProgressAnimation.value > 0.35f) {
                    pressProgressAnimation.snapTo(0.35f)
                }
            }

            val popPressTarget = if (isRapidTap) 0.45f else 0.72f
            val popScaleX = if (isRapidTap) 1.05f else 1.10f
            val popScaleY = if (isRapidTap) 1.09f else 1.18f
            val velocityPulse = if (isRapidTap) {
                dir * 0.45f
            } else {
                dir * (0.85f + 0.25f * distFactor)
            }

            launch { pressProgressAnimation.animateTo(popPressTarget, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(popScaleX, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(popScaleY, scaleYAnimationSpec) }
            launch { velocityAnimation.snapTo(velocityPulse) }

            val glideSpec = if (isRapidTap) fastTapGlideValueSpec else tapGlideValueSpec
            val moveJob = launch {
                mutatorMutex.mutate {
                    valueAnimation.animateTo(clamped, glideSpec)
                }
            }

            val holdMs = if (isRapidTap) {
                65L
            } else {
                (120L + (distFactor * 22f).toLong()).coerceAtMost(185L)
            }
            delay(holdMs)
            release()
            moveJob.join()
        }
    }

    /**
     * 耗电护栏：Activity onPause/onStop 时立即收束一切弹簧动效，确保后台零帧提交。
     */
    fun snapToRest() {
        tapPopJob?.cancel()
        tapPopJob = null
        animationScope.launch {
            mutatorMutex.mutate {
                valueAnimation.snapTo(targetValue.coerceIn(valueRange.start, valueRange.endInclusive))
            }
            velocityAnimation.snapTo(0f)
            pressProgressAnimation.snapTo(0f)
            scaleXAnimation.snapTo(initialScale)
            scaleYAnimation.snapTo(initialScale)
        }
    }
}

/**
 * 跟随手指按压位置的轻量镜面高光层（静止零绘制开销）。
 */
internal class InteractiveHighlight(
    private val animationScope: CoroutineScope,
    private val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset }
) {
    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private var startPosition: Offset = Offset.Zero
    private var currentPosition: Offset = Offset.Zero

    val modifier: Modifier = Modifier.drawWithContent {
        drawContent()
        val progress = pressProgressAnimation.value
        if (progress > 0.001f && size.width > 0f && size.height > 0f) {
            val center = position(size, currentPosition)
            val radius = (size.maxDimension * 0.55f).coerceAtLeast(1f)
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.20f * progress),
                        Color.White.copy(alpha = 0.06f * progress),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius
                ),
                blendMode = BlendMode.Plus
            )
        }
    }

    val gestureModifier: Modifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            startPosition = down.position
            currentPosition = down.position
            animationScope.launch {
                pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec)
            }
            drag(down.id) { change ->
                currentPosition = change.position
            }
            animationScope.launch {
                pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec)
            }
        }
    }

    fun snapToRest() {
        animationScope.launch {
            pressProgressAnimation.snapTo(0f)
        }
    }
}

internal fun lerpScale(initial: Float, pressed: Float, progress: Float): Float {
    return lerp(initial, pressed, progress.coerceIn(0f, 1f))
}
