package eu.woshicado.circletoshare

import android.content.Context
import android.provider.Settings

/**
 * How long to wait for a system animation to finish before capturing behind it.
 *
 * Several capture paths have to outlast an animation they didn't start — the
 * assistant's zoom-out, the Quick Settings shade collapsing — and each has its
 * own measured [baseMs] at 1x. All of them must respect the user's animation
 * scale settings, hence one shared helper.
 *
 * Returns [baseMs] scaled up for slower-than-normal animations, or [offMs]
 * when the user has animations switched off entirely (nothing to wait for, but
 * a frame or two is still needed for the teardown to land). Neither has a
 * default: each caller waits out a different animation and has to justify its
 * own numbers.
 *
 * Reading `Settings.Global` needs no permission. Cheap enough for a click path
 * (three cached provider reads, and the result is immediately spent on a
 * `postDelayed`), but don't call it per-frame or in a loop.
 */
fun animationSettleMs(context: Context, baseMs: Float, offMs: Long): Long {
    val r = context.contentResolver
    val scale = maxOf(
        Settings.Global.getFloat(r, Settings.Global.WINDOW_ANIMATION_SCALE, 1f),
        Settings.Global.getFloat(r, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f),
        Settings.Global.getFloat(r, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    )
    return if (scale <= 0f) offMs else (baseMs * scale.coerceAtLeast(1f)).toLong()
}

/**
 * How long to give the lock screen to be gone after an unlock (the tile's
 * prompt) or after a show that found it going (the assistant's): long
 * enough for the keyguard-exit transition to flip isKeyguardLocked(), short
 * enough not to feel like a hang. Scaled like every other wait here: the
 * exit is an animation, and at 2x a fixed 400 ms would look again while
 * still locked and drop the capture the user just unlocked for.
 */
fun keyguardExitGraceMs(context: Context): Long =
    animationSettleMs(context, baseMs = KEYGUARD_EXIT_GRACE_MS, offMs = KEYGUARD_EXIT_GRACE_OFF_MS)
        .coerceAtMost(KEYGUARD_EXIT_GRACE_MAX_MS)

private const val KEYGUARD_EXIT_GRACE_MS = 400f
private const val KEYGUARD_EXIT_GRACE_OFF_MS = 100L
private const val KEYGUARD_EXIT_GRACE_MAX_MS = 1500L
