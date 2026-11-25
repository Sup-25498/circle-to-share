package eu.woshicado.circletoshare

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast

/**
 * [clip] on the clipboard, and that it is there said: from Android 13 the
 * system says so itself, with a preview of what was copied, and a toast
 * would only say it twice (and from here is mostly dropped anyway: see
 * ScreenshotAccessibilityService.notify); before 13, a toast of [said].
 */
internal fun copyToClipboard(context: Context, clip: ClipData, said: Int) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, said, Toast.LENGTH_SHORT).show()
    }
}
