package com.arcu.attnd.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.LayoutScreen
import com.arcu.attnd.domain.LayoutSection
import com.arcu.attnd.domain.ScreenFacts
import com.arcu.attnd.domain.ScreenLayout
import java.time.Instant

/**
 * What the server-driven screens (API-024, iOS `AppState.screenSections`) read beyond the bundle:
 * the layouts to use (null = the built-in ones; always null in screenshot mode) and the facts the
 * `when` conditions ask for. Provided once around the event UI.
 */
@Immutable
data class ScreenEnv(
    val layoutsEnabled: Boolean = false,
    val modelAvailable: Boolean = false,
    val hasSchedule: Boolean = false,
) {
    fun state(now: Instant): ScreenFacts.State =
        ScreenFacts.State(now = now, modelAvailable = modelAvailable, hasPlan = hasSchedule, hasLikes = hasSchedule)
}

val LocalScreenEnv = staticCompositionLocalOf { ScreenEnv() }

/** The sections [screen] draws for a subject, in order (rules in [ScreenLayout.resolve]). */
@Composable
fun rememberSections(
    screen: LayoutScreen,
    bundle: EventBundle,
    now: Instant,
    subject: ScreenLayout.Subject = ScreenLayout.Subject.EMPTY,
    facts: (EventBundle, ScreenFacts.State) -> Set<String>,
): List<LayoutSection> {
    val env = LocalScreenEnv.current
    return remember(screen, bundle, now, subject, env) {
        val layouts = bundle.layout.takeIf { env.layoutsEnabled }
        ScreenLayout.resolve(screen, layouts, facts(bundle, env.state(now)), subject)
    }
}
