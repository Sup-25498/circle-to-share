package eu.woshicado.circletoshare.capture

import android.content.Context
import android.content.Intent

/**
 * Opens the crop tool for other apps: automation apps (Tasker's and
 * MacroDroid's "Send Intent" to an activity, action [ACTION]) and the
 * shortcuts: the app shortcut on the launcher icon, and the one made through
 * CaptureShortcutActivity. Exported, as ScreenshotTile's trigger is:
 * whoever starts it only gets the tool shown to the person at the phone,
 * never the picture.
 */
class CaptureTriggerActivity : CaptureStarterActivity() {

    override fun request(service: ScreenshotAccessibilityService) = service.triggerCaptureFromApp()

    companion object {
        /** The action other apps start it with. */
        const val ACTION = "eu.woshicado.circletoshare.CAPTURE"

        /** What starts it, for a shortcut (see CaptureShortcutActivity, and
         *  res/xml/shortcuts.xml for the app shortcut). */
        fun intent(context: Context): Intent = Intent(ACTION).setClass(context, CaptureTriggerActivity::class.java)
    }
}
