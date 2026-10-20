package com.secure.reader.core.designsystem.component

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * The app's entire motion vocabulary, in one place.
 *
 * One thesis: a document reader should feel like paper being set down — things
 * settle, they never bounce around. So the vocabulary is deceleration-only
 * ([Motion.EaseOut]), feedback is faster than state change, and exactly one
 * entrance is authored (the library → reader hand-off). Everything else is
 * confirmation of a state change, never a show.
 */
internal object Motion {
    /** A finger landed on something. */
    const val TAP_MS = 110
    /** Routine state change: pill appears, skeleton crossfades. */
    const val ROUTINE_MS = 190
    /** Layout change with consequence: list settles after a delete. */
    const val STATE_MS = 250
    /** The one authored entrance (library → reader). */
    const val ENTRANCE_MS = 420

    /** Exponential ease-out. Confident arrival, no overshoot. */
    val EaseOut: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** Exponential ease-in-out for things that travel both ways. */
    val EaseInOut: Easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
}

/**
 * Press feedback: fast, tight, no wobble. A list row should feel like a physical
 * surface, not a spring toy — the bounce is reserved for the one primary action.
 */
internal val PressSpring: AnimationSpec<Float> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessHigh,
)

/** The primary action's slightly livelier press. */
internal val PressSpringBouncy: AnimationSpec<Float> = spring(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * False when the user has turned animations off (developer option or an
 * accessibility flow). Apps that ignore this feel broken to those users, so
 * every authored duration in the app reads from here before it runs.
 */
val LocalMotionEnabled = compositionLocalOf { true }

/**
 * Observes the system animator duration scale, including changes made while the
 * app is alive. Reads the setting rather than `ValueAnimator.areAnimatorsEnabled`
 * because the latter needs API 26 and is not observable.
 */
@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(readAnimatorScale(context.contentResolver) > 0f) }

    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = readAnimatorScale(resolver) > 0f
            }
        }
        runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer,
            )
        }
        onDispose {
            runCatching { resolver.unregisterContentObserver(observer) }
        }
    }

    return enabled
}

private fun readAnimatorScale(resolver: android.content.ContentResolver): Float =
    runCatching {
        Settings.Global.getFloat(
            resolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }.getOrDefault(1f)
