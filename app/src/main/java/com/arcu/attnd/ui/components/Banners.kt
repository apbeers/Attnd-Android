package com.arcu.attnd.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.InAppBanner
import com.arcu.attnd.domain.StatusBadge
import com.arcu.attnd.domain.SwipeDismissRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Reduce Motion on Android: animations switched off (animator duration scale 0) in the developer / accessibility settings. */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * Top pills and banners follow the finger (iOS `SwipeUpToDismiss`, rule [SwipeDismissRule]): dragged
 * up they move with it, pulled down they rubber-band (≤ 24 dp); released past 30 % of their height or
 * flung up they slide off the top and then [onDismiss] runs; otherwise they spring back. Reduce Motion:
 * a shorter slide and no spring. TalkBack gets a "Dismiss" action.
 */
@Composable
fun SwipeUpToDismiss(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val reduceMotion = rememberReduceMotion()
    val dismiss by rememberUpdatedState(onDismiss)
    val offset = remember { Animatable(0f) }
    var height by remember { mutableIntStateOf(1) }
    var translation by remember { mutableFloatStateOf(0f) }
    var leaving by remember { mutableStateOf(false) }
    fun toDp(px: Float): Double = (px / density.density).toDouble()
    fun toPx(dp: Double): Float = (dp * density.density).toFloat()
    val drag = rememberDraggableState { delta ->
        if (leaving) return@rememberDraggableState
        translation += delta
        scope.launch { offset.snapTo(toPx(SwipeDismissRule.offset(toDp(translation)))) }
    }
    Box(
        modifier
            .onSizeChanged { height = maxOf(it.height, 1) }
            .graphicsLayer {
                translationY = offset.value
                alpha = if (leaving) 0f else 1f - (maxOf(0f, -offset.value) / (height * 2f)).coerceIn(0f, 1f)
            }
            .draggable(
                state = drag,
                orientation = Orientation.Vertical,
                onDragStarted = { translation = 0f },
                onDragStopped = { velocity ->
                    if (leaving) return@draggable
                    val heightDp = toDp(height.toFloat())
                    val moved = toDp(translation)
                    if (SwipeDismissRule.dismisses(moved, SwipeDismissRule.predicted(moved, toDp(velocity)), heightDp)) {
                        leaving = true
                        val travel = if (reduceMotion) -height * 0.5f else -(height + toPx(80.0))
                        scope.launch {
                            offset.animateTo(travel, tween(if (reduceMotion) 120 else 220, easing = FastOutLinearInEasing))
                            delay(10)
                            dismiss()
                            leaving = false
                            offset.snapTo(0f)
                        }
                    } else {
                        scope.launch {
                            if (reduceMotion) offset.animateTo(0f, tween(150))
                            else offset.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow))
                        }
                    }
                    translation = 0f
                },
            )
            .semantics { customActions = listOf(CustomAccessibilityAction("Dismiss") { dismiss(); true }) },
    ) {
        content()
    }
}

/**
 * The floating status pills (iOS `eventStatusBanners`): "Event ended", "Offline · Updated 2:14 PM ·
 * everything still works" / "Can't update · …" and (phase 9) "Instant updates on", top to bottom,
 * overlaid top-centre of the tab content (no layout space), clear of the status bar and cutouts. Each
 * swipes away for the session ([onDismiss]); Options → Status keeps the same information.
 */
@Composable
fun StatusPills(badges: List<StatusBadge>, onDismiss: (StatusBadge.Kind) -> Unit, modifier: Modifier = Modifier) {
    if (badges.isEmpty()) return
    Column(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        badges.forEach { badge ->
            SwipeUpToDismiss(onDismiss = { onDismiss(badge.kind) }) {
                StatusPill(badge)
            }
        }
    }
}

/** Pill colours and icon per kind; [StatusBadge.Kind.INSTANT] is green like iOS. */
@Composable
fun StatusPill(badge: StatusBadge) {
    val scheme = MaterialTheme.colorScheme
    val (icon: ImageVector, colors: Pair<Color, Color>) = when (badge.kind) {
        StatusBadge.Kind.ENDED -> Icons.Filled.EventBusy to (scheme.secondaryContainer to scheme.onSecondaryContainer)
        StatusBadge.Kind.REFRESH -> Icons.Filled.CloudOff to (scheme.errorContainer to scheme.onErrorContainer)
        StatusBadge.Kind.INSTANT -> {
            val dark = scheme.surface.luminance() < 0.5f
            Icons.Filled.Bolt to (if (dark) InstantGreenDark to InstantGreenLight else InstantGreenLight to InstantGreenDark)
        }
    }
    Surface(
        shape = CircleShape,
        color = colors.first,
        contentColor = colors.second,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = badge.description },
    ) {
        Row(
            modifier = Modifier
                .height(28.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(badge.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The "Instant updates on" capsule's greens (≥ 7:1 against each other; phase 9's colours). */
private val InstantGreenLight = Color(0xFFC8E6C9)
private val InstantGreenDark = Color(0xFF1B5E20)

/**
 * The top banner for an organizer update received while the app is open (iOS `InAppBannerOverlay`):
 * tap opens it in Updates, a swipe up dismisses it (it stays in Updates and the shade); the caller
 * drops it after [InAppBanner.durationMs].
 */
@Composable
fun InAppBannerCard(banner: InAppBanner, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    SwipeUpToDismiss(
        onDismiss = onDismiss,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .widthIn(max = 520.dp)
            .fillMaxWidth(),
    ) {
        Surface(
            onClick = onOpen,
            shape = RoundedCornerShape(22.dp),
            color = if (banner.urgent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (banner.urgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    if (banner.urgent) Icons.Filled.Warning else Icons.Filled.NotificationsActive,
                    contentDescription = if (banner.urgent) "Urgent" else null,
                    tint = if (banner.urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    if (banner.title.isNotBlank()) {
                        Text(banner.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (banner.body.isNotBlank()) {
                        Text(
                            banner.body,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
