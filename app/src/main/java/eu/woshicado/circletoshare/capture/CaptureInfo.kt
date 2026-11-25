package eu.woshicado.circletoshare.capture

import android.graphics.Rect
import eu.woshicado.circletoshare.crop.SnapBounds
import eu.woshicado.circletoshare.text.PendingScreenText

/**
 * What was read off the screen with a picture, at the moment it was taken
 * — while the tool about to open hides the app from the service, it can't
 * be read later — kept with the picture and handed on with it, as one.
 * Each part is null or empty where it wasn't read.
 *
 * [snap]: the elements' bounds, for snapping (see collectSnapBounds).
 * [areas]: what scrolls sideways, for the "↔" button (see sidewaysAreasNow),
 * in display pixels. [text]: the text on screen as it is being read, for
 * Copy text (see collectScreenText); whoever holds the info last cancels it.
 * [app]: the package of the app the picture is of, for a save into a folder
 * per app (see saveAppNow).
 */
data class CaptureInfo(
    val snap: SnapBounds? = null,
    val areas: List<Rect> = emptyList(),
    val text: PendingScreenText? = null,
    val app: String? = null,
)
