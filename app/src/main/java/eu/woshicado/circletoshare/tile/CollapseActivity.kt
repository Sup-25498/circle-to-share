package eu.woshicado.circletoshare.tile

import android.app.Activity
import android.os.Bundle

/**
 * Shows nothing and is gone as soon as it starts. The tile starts it only for
 * the collapse of its panel that comes with [android.service.quicksettings.TileService.startActivityAndCollapse],
 * where SystemUI doesn't offer the accessibility action to close the shade
 * (see ScreenshotAccessibilityService.triggerCaptureFromShade). As
 * ScreenshotTile's NoDisplayActivity; a task of its own (taskAffinity ""),
 * or starting it would bring this app's task to the front.
 */
class CollapseActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // (Theme.NoDisplay: finished before it resumes, or it throws.)
        finish()
    }
}
