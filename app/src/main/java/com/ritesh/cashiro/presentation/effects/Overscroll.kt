package com.ritesh.cashiro.presentation.effects

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Taken from : https://github.com/Yos-X/FlamingoSank/blob/master/overscroll_core/src/main/java/com/cormor/overscroll/core/OverScroll.kt
 * A parabolic rolling easing curve.
 *
 * When rolling in the same direction, the farther away from 0, the greater the "resistance"; the closer to 0, the smaller the "resistance";
 *
 * No drag effect is applied when the scrolling direction is opposite to the currently existing overscroll offset
 *
 * Note: when [p] = 50f, its performance should be consistent with iOS
 * @param currentOffset Offset value currently out of bounds
 * @param newOffset The offset of the new scroll
 * @param p Key parameters for parabolic curve calculation
 * @param density Without this param, the unit of offset is pixels,
 * so we need this variable to have the same expectations on different devices.
 */
@Stable
fun parabolaScrollEasing(
    currentOffset: Float,
    newOffset: Float,
    p: Float = 50f,
    density: Float = 4f
): Float {
    val realP = p * density
    val ratio =
        (realP / (sqrt(realP * abs(currentOffset + newOffset / 2).coerceAtLeast(Float.MIN_VALUE)))).coerceIn(
            Float.MIN_VALUE,
            1f
        )
    return if (sign(currentOffset) == sign(newOffset)) {
        currentOffset + newOffset * ratio
    } else {
        currentOffset + newOffset
    }
}

/**
 * Linear, you probably wouldn't think of using it.
 */
val LinearScrollEasing: (currentOffset: Float, newOffset: Float) -> Float =
    { currentOffset, newOffset -> currentOffset + newOffset }

internal val DefaultParabolaScrollEasing: (currentOffset: Float, newOffset: Float) -> Float
    @Composable
        get() {
        val density = LocalDensity.current.density
        return { currentOffset, newOffset ->
            parabolaScrollEasing(currentOffset, newOffset, density = density)
        }
    }

internal const val OutBoundSpringStiff = 150f
internal const val OutBoundSpringDamp = 0.86f

/**
 * @see overScrollOutOfBound
 */
fun Modifier.overScrollVertical(
    scrollEasing: ((currentOffset: Float, newOffset: Float) -> Float)? = null,
    springStiff: Float = OutBoundSpringStiff,
    springDamp: Float = OutBoundSpringDamp,
): Modifier = overScrollOutOfBound(
    isVertical = true,
    scrollEasing,
    springStiff,
    springDamp
)

/**
 * @see overScrollOutOfBound
 */
fun Modifier.overScrollHorizontal(
    scrollEasing: ((currentOffset: Float, newOffset: Float) -> Float)? = null,
    springStiff: Float = OutBoundSpringStiff,
    springDamp: Float = OutBoundSpringDamp,
): Modifier = overScrollOutOfBound(
    isVertical = false,
    scrollEasing,
    springStiff,
    springDamp
)

/**
 * OverScroll effect for scrollable Composable .
 *
 * - You should call it before Modifiers with similar semantics such as [Modifier.scrollable], so that nested scrolling can work normally
 * - You should use it with [rememberOverscrollFlingBehavior]
 * @Author: cormor
 * @Email: cangtiansuo@gmail.com
 * @param isVertical is vertical, or horizontal ?
 * @param scrollEasing U can refer to [DefaultParabolaScrollEasing], The incoming values are the currently existing overscroll Offset
 * and the new offset from the gesture.
 * modify it to cooperate with [springStiff] to customize the sliding damping effect.
 * The current default easing comes from iOS, you don't need to modify it!
 * @param springStiff springStiff for overscroll effect，For better user experience, the new value is not recommended to be higher than[Spring.StiffnessMediumLow]
 * @param springDamp springDamp for overscroll effect，generally do not need to set
 */
@Suppress("NAME_SHADOWING")
fun Modifier.overScrollOutOfBound(
    isVertical: Boolean = true,
    scrollEasing: ((currentOffset: Float, newOffset: Float) -> Float)? = null,
    springStiff: Float = OutBoundSpringStiff,
    springDamp: Float = OutBoundSpringDamp,
): Modifier = composed {
    val scrollEasing by rememberUpdatedState(scrollEasing ?: DefaultParabolaScrollEasing)
    val springStiff by rememberUpdatedState(springStiff)
    val springDamp by rememberUpdatedState(springDamp)
    val isVertical by rememberUpdatedState(isVertical)
    val dispatcher = remember { NestedScrollDispatcher() }
    var offset by remember { mutableFloatStateOf(0f) }

    val nestedConnection = remember {
        object : NestedScrollConnection {
            private val visibilityThreshold = 0.5f
            private var rebound: Job? = null

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                rebound?.cancel()
                val delta = if (isVertical) available.y else available.x
                if (abs(offset) <= visibilityThreshold || sign(delta) == sign(offset)) return Offset.Zero

                val previousOffset = offset
                val nextOffset = scrollEasing(previousOffset, delta)
                val consumed = if (sign(previousOffset) != sign(nextOffset)) -previousOffset else delta
                offset = if (sign(previousOffset) != sign(nextOffset)) 0f else nextOffset
                return if (isVertical) Offset(0f, consumed) else Offset(consumed, 0f)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val delta = if (isVertical) available.y else available.x
                if (source != NestedScrollSource.UserInput || delta == 0f) return Offset.Zero
                offset = scrollEasing(offset, delta)
                return if (isVertical) Offset(0f, delta) else Offset(delta, 0f)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val velocity = if (isVertical) available.y else available.x
                if (sign(velocity) != sign(offset)) startRebound(0f)
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val velocity = if (isVertical) available.y else available.x
                startRebound(velocity)
                return Velocity.Zero
            }

            private fun startRebound(velocity: Float) {
                if (rebound?.isActive == true) return
                if (abs(offset) < visibilityThreshold) {
                    offset = 0f
                    return
                }
                rebound = dispatcher.coroutineScope.launch {
                    Animatable(offset).animateTo(
                        0f,
                        spring(springDamp, springStiff, visibilityThreshold),
                        initialVelocity = velocity
                    ) {
                        offset = value
                    }
                    offset = 0f
                }
            }
        }
    }

    this
        .clipToBounds()
        .nestedScroll(nestedConnection, dispatcher)
        .graphicsLayer {
            if (isVertical) translationY = offset else translationX = offset
        }
}

/**
 * You should use it with [overScrollVertical]
 * @param decaySpec You can use instead [rememberSplineBasedDecay]
 * @param getScrollState Pass in your [ScrollableState], for [LazyColumn]/[LazyRow] , it's [LazyListState]
 */
@Composable
fun rememberOverscrollFlingBehavior(
    decaySpec: DecayAnimationSpec<Float> = exponentialDecay(),
    getScrollState: () -> ScrollableState,
): FlingBehavior {
    val currentScrollState by rememberUpdatedState(getScrollState)
    return remember(decaySpec) {
        object : FlingBehavior {
            /**
             * - We should check it every frame of fling
             * - Should stop fling when returning true and return the remaining speed immediately.
             * - Without this detection, scrollBy() will continue to consume velocity,
             * which will cause a velocity error in nestedScroll.
             */
            private val Float.canNotBeConsumed: Boolean // this is Velocity
                get() {
                    val state = currentScrollState()
                    return !(this < 0 && state.canScrollBackward || this > 0 && state.canScrollForward)
                }

            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                if (initialVelocity.canNotBeConsumed) {
                    return initialVelocity
                }
                return if (abs(initialVelocity) > 1f) {
                    var velocityLeft = initialVelocity
                    var lastValue = 0f
                    AnimationState(
                        initialValue = 0f,
                        initialVelocity = initialVelocity,
                    ).animateDecay(decaySpec) {
                        val delta = value - lastValue
                        val consumed = scrollBy(delta)
                        lastValue = value
                        velocityLeft = this.velocity
                        // avoid rounding errors and stop if anything is unconsumed
                        if (abs(delta - consumed) > 0.5f || velocityLeft.canNotBeConsumed) {
                            cancelAnimation()
                        }
                    }
                    velocityLeft
                } else {
                    initialVelocity
                }
            }
        }
    }
}
