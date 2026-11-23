package eu.woshicado.circletoshare.capture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import eu.woshicado.circletoshare.MainActivity
import eu.woshicado.circletoshare.Prefs
import eu.woshicado.circletoshare.R
import eu.woshicado.circletoshare.capture.ScreenshotAccessibilityService.Refusal

/**
 * An activity that shows nothing, asks the accessibility service for a
 * capture ([request]) and is gone: what a tap on the tile starts
 * (TileCaptureActivity) and what other apps start (CaptureTriggerActivity).
 * Where the service is off or not agreed to, the app opens and says why.
 * (Theme.NoDisplay, a task of its own: see the manifest.)
 */
abstract class CaptureStarterActivity : Activity() {

    /** Asks [service] for the capture. */
    protected abstract fun request(service: ScreenshotAccessibilityService): Refusal

    /** The device is locked: says so. */
    protected open fun onLocked() = ScreenshotAccessibilityService.notify(this, R.string.error_tile_locked)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = ScreenshotAccessibilityService.instance
        when {
            service == null -> openApp(ScreenshotAccessibilityService.whyNotRunning(this))
            !Prefs.hasAccessibilityConsent(this) -> openApp(R.string.error_needs_consent)
            else -> when (request(service)) {
                // (Busy: a tool is already up, or on its way.)
                Refusal.NONE, Refusal.BUSY -> Unit
                Refusal.LOCKED -> onLocked()
                Refusal.NO_CONSENT -> openApp(R.string.error_needs_consent)
            }
        }
        // (Theme.NoDisplay: finished before it resumes, or it throws.)
        finish()
    }

    private fun openApp(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }
}
