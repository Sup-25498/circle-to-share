package eu.woshicado.circletoshare

import android.content.Context
import android.view.View

/** [value] density-independent pixels in pixels, rounded down, as sizes and
 *  paddings are given. */
internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/** [value] density-independent pixels in pixels, unrounded, for drawing. */
internal fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

internal fun View.dp(value: Int): Int = context.dp(value)

internal fun View.dp(value: Float): Float = context.dp(value)
