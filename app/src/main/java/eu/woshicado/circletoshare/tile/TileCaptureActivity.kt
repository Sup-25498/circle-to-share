package eu.woshicado.circletoshare.tile

import android.os.Handler
import android.os.Looper
import eu.woshicado.circletoshare.R
import eu.woshicado.circletoshare.capture.CaptureStarterActivity
import eu.woshicado.circletoshare.capture.ScreenshotAccessibilityService
import eu.woshicado.circletoshare.capture.ScreenshotAccessibilityService.Refusal

/**
 * What a tap on the tile starts from Android 15 on: SystemUI starts it
 * itself (Tile.setActivityLaunchForClick, see CaptureTileService), closing
 * the shade and asking for an unlock first where one is needed. The tile's
 * service needn't be bound for that: SystemUI binds only three apps' tiles
 * at a time, and a tap on one not bound yet — just after the shade opened,
 * on a page of tiles further on — waited seconds for its turn.
 */
class TileCaptureActivity : CaptureStarterActivity() {

    override fun request(service: ScreenshotAccessibilityService) = service.triggerCaptureFromTileLaunch()

    /** SystemUI has had the device unlocked first, but the keyguard can still
     *  be on its way out: once more after a moment, as the tile does after
     *  its own unlock. */
    override fun onLocked() {
        val app = applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            if (ScreenshotAccessibilityService.instance?.triggerCaptureFromTileLaunch() == Refusal.LOCKED) {
                ScreenshotAccessibilityService.notify(app, R.string.error_tile_locked)
            }
        }, CaptureTileService.keyguardExitGraceMs(app))
    }
}
