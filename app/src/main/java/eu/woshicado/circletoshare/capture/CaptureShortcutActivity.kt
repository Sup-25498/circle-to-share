package eu.woshicado.circletoshare.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import eu.woshicado.circletoshare.Language
import eu.woshicado.circletoshare.R

/**
 * Makes a "Capture" shortcut for whoever asks with ACTION_CREATE_SHORTCUT:
 * the shortcut pickers of automation apps (MacroDroid's "Launch Shortcut"),
 * which list only apps that answer it, not app shortcuts. Answers the way
 * they read: the shortcut's intent, name and icon in the result (not
 * ShortcutManager's pin request, which is for launchers — those have the
 * app shortcut). Shows nothing; hands back the shortcut and is gone.
 */
class CaptureShortcutActivity : Activity() {

    // (In the language picked, before Android 13 too: see Language.)
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Language.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(Intent.EXTRA_SHORTCUT_INTENT, CaptureTriggerActivity.intent(this))
                .putExtra(Intent.EXTRA_SHORTCUT_NAME, getString(R.string.shortcut_capture_long))
                .putExtra(
                    Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                    Intent.ShortcutIconResource.fromContext(this, R.mipmap.ic_launcher)
                )
        )
        // (Theme.NoDisplay: finished before it resumes, or it throws.)
        finish()
    }
}
