package dev.qcom.bandmenu.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import dev.qcom.bandmenu.ui.component.animation.DampedDragAnimation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.sign

val LocalFloatingBottomBarTabScale = staticCompositionLocalOf { { 1f } }

private val BAR_HEIGHT = 64.dp
private val BarCorner = RoundedCornerShape(50)

@Composable
fun RowScope.FloatingBottomBarItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalFloatingBottomBarTabScale.current
    val hapticFeedback = LocalHapticFeedback.current
    Column(
        modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                    onClick()
                }
            )
            .fillMaxHeight()
            .weight(1f)
            .padding(horizontal = 12.dp)
            .graphicsLayer {
                val scale = scale()
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

@Composable
fun FloatingBottomBar(
    modifier: Modifier = Modifier,
    selectedIndex: () -> Int,
    onSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    isBackdropBlurEnabled: Boolean = true,
    isLiquidGlassEnabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val isInLightTheme = !isSystemInDarkTheme()
    val accentColor = MiuixTheme.colorScheme.primary
    val containerColor = if (isBackdropBlurEnabled) {
        MiuixTheme.colorScheme.surfaceContainer.copy(0.4f)
    } else {
        MiuixTheme.colorScheme.surfaceContainer
    }

    val tabsBackdrop = rememberLayerBackdrop(onDraw = { drawContent() })
    val density = LocalDensity.current
    val barHeightDp = BAR_HEIGHT
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }

    val offsetAnimation = remember { Animatable(0f) }
    val panelOffset by remember(density) {
        derivedStateOf {
            if (totalWidthPx == 0f) 0f else {
                val fraction = (offsetAnimation.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                with(density) { 4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
            }
        }
    }

    // Incoming selection (the pager) only moves the indicator. onSelected is
    // reserved for the user's own indicator drag, so a pager update is never
    // echoed back as a navigation request.
    var currentIndex by remember { mutableIntStateOf(selectedIndex()) }
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentOnSelected by rememberUpdatedState(onSelected)

    class DampedDragAnimationHolder { var instance: DampedDragAnimation? = null }
    val holder = remember { DampedDragAnimationHolder() }

    val dampedDragAnimation = remember(animationScope, tabsCount, density, isLtr) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex().toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            canDrag = { offset ->
                val anim = holder.instance ?: return@DampedDragAnimation true
                if (tabWidthPx == 0f) return@DampedDragAnimation false
                val currentValue = anim.value
                val indicatorX = currentValue * tabWidthPx
                val padding = with(density) { 4.dp.toPx() }
                val globalTouchX = if (isLtr) {
                    val touchX = indicatorX + offset.x; padding + touchX
                } else {
                    val touchX = totalWidthPx - padding - tabWidthPx - indicatorX + offset.x; touchX
                }
                globalTouchX in 0f..totalWidthPx
            },
            onDragStarted = { },
            onDragStopped = {
                val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                currentIndex = targetIndex
                animateToValue(targetIndex.toFloat())
                currentOnSelected(targetIndex)
                animationScope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
            },
            onDrag = { _, dragAmount ->
                if (tabWidthPx > 0) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch { offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x) }
                }
            }
        ).also { holder.instance = it }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { currentSelectedIndex() }.collectLatest { currentIndex = it }
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { currentIndex }.drop(1).collectLatest { index ->
            dampedDragAnimation.animateToValue(index.toFloat())
        }
    }

    Box(
        modifier = modifier.fillMaxWidth(0.85f),
        contentAlignment = Alignment.CenterStart
    ) {
        // ── Layer 1: outer glass bar (visible, draws the background blur + highlight) ──
        Row(
            Modifier
                .onGloballyPositioned { coords ->
                    totalWidthPx = coords.size.width.toFloat()
                    val contentWidthPx = totalWidthPx - with(density) { 8.dp.toPx() }
                    tabWidthPx = contentWidthPx / tabsCount
                }
                .graphicsLayer { translationX = panelOffset }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { BarCorner },
                    effects = {
                        if (isBackdropBlurEnabled) blur(8f.dp.toPx())
                        if (isLiquidGlassEnabled) {
                            vibrancy()
                            lens(24f.dp.toPx(), 24f.dp.toPx())
                        }
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = if (isLiquidGlassEnabled) 1f else 0f)
                    },
                    shadow = {
                        Shadow.Default.copy(
                            color = Color.Black.copy(if (isInLightTheme) 0.1f else 0.2f),
                        )
                    },
                    layerBlock = {
                        if (isLiquidGlassEnabled) {
                            val progress = dampedDragAnimation.pressProgress
                            val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                            scaleX = scale
                            scaleY = scale
                        }
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .height(barHeightDp)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )

        // ── Layer 2: invisible tab-scale layer (feeds tabsBackdrop for the indicator) ──
        CompositionLocalProvider(
            LocalFloatingBottomBarTabScale provides {
                if (isLiquidGlassEnabled) lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
                else 1f
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
                        shape = { BarCorner },
                        effects = {
                            if (isBackdropBlurEnabled) blur(8f.dp.toPx())
                            if (isLiquidGlassEnabled) {
                                val progress = dampedDragAnimation.pressProgress
                                vibrancy()
                            }
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = if (isLiquidGlassEnabled) dampedDragAnimation.pressProgress else 0f)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .height(barHeightDp - 8.dp)
                    .padding(4.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        // ── Layer 3: selected-tab indicator pill (drag handle) ──
        if (tabWidthPx > 0f) {
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .graphicsLayer {
                        val contentWidth = totalWidthPx - with(density) { 8.dp.toPx() }
                        val singleTabWidth = contentWidth / tabsCount
                        val progressOffset = dampedDragAnimation.value * singleTabWidth
                        translationX = if (isLtr) {
                            progressOffset + panelOffset
                        } else {
                            -progressOffset + panelOffset
                        }
                    }
                    .then(dampedDragAnimation.modifier)
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                        shape = { BarCorner },
                        effects = {
                            if (isLiquidGlassEnabled) {
                                val progress = dampedDragAnimation.pressProgress
                                lens(10f.dp.toPx() * progress, 14f.dp.toPx() * progress, true, true)
                            }
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = if (isLiquidGlassEnabled) dampedDragAnimation.pressProgress else 0f)
                        },
                        shadow = { Shadow(alpha = if (isLiquidGlassEnabled) dampedDragAnimation.pressProgress else 0f) },
                        innerShadow = {
                            InnerShadow(
                                radius = 8f.dp * dampedDragAnimation.pressProgress,
                                alpha = if (isLiquidGlassEnabled) dampedDragAnimation.pressProgress else 0f
                            )
                        },
                        layerBlock = {
                            if (isLiquidGlassEnabled) {
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                                val velocity = dampedDragAnimation.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            }
                        },
                        onDrawSurface = {
                            val progress = if (isLiquidGlassEnabled) dampedDragAnimation.pressProgress else 0f
                            drawRect(
                                color = if (isInLightTheme) {
                                    Color.Black.copy(0.1f)
                                } else {
                                    Color.White.copy(0.1f)
                                },
                                alpha = 1f - progress
                            )
                            drawRect(Color.Black.copy(alpha = 0.03f * progress))
                        }
                    )
                    .height(barHeightDp - 8.dp)
                    .width(with(density) { ((totalWidthPx - 8.dp.toPx()) / tabsCount).toDp() })
            )
        }
    }
}
