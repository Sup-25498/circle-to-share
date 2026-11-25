package eu.woshicado.circletoshare.crop

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.woshicado.circletoshare.KeepPrefs
import eu.woshicado.circletoshare.Prefs
import eu.woshicado.circletoshare.R
import eu.woshicado.circletoshare.qr.QrCode
import eu.woshicado.circletoshare.share.Delivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The crop overlay as a whole: the chrome around [CropOverlayView] must not
 *  get between the finger and the crop. */
@RunWith(AndroidJUnit4::class)
class CropScreenTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var screen: CropScreen

    // (Among them the remembered crop: a test that saves or starts a
    // sideways run remembers its selection.)
    @get:Rule
    val kept = KeepPrefs()

    @Before
    fun setUp() = onMain {
        // Off for these tests: setBitmap would otherwise start a real scan of
        // the blank bitmap on a worker thread, whose (empty) result arrives
        // whenever it arrives and replaces any codes a test has put in.
        Prefs.setQrDetectEnabled(context, false)
        // On for these tests (off by default): the bar with Copy text is the
        // fuller one, and the tests of its fit and its button expect it.
        Prefs.setCopyTextEnabled(context, true)
        // At the font and display size the device came with: a phone's
        // width, as these tests take it. (The fit at others is tried with
        // widths of its own: see buttonsWhoseLabelsDoNotFit_….)
        val standard = Configuration(context.resources.configuration).apply {
            fontScale = 1f
            densityDpi = DisplayMetrics.DENSITY_DEVICE_STABLE
        }
        screen = CropScreen(context.createConfigurationContext(standard))
        // A size no real capture has, so no remembered crop can match it and
        // the hint is the plain, non-interactive one.
        screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))
        layOut()
    }

    /** Measure and lay the screen out. It isn't attached to a window here, so
     *  nothing does this by itself when a child appears or changes size —
     *  call it (on the main thread) after anything that should move views. */
    private fun layOut(width: Int = WIDTH) {
        screen.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        screen.layout(0, 0, width, HEIGHT)
    }

    @Test
    fun plainHintIsNotClickable() {
        val hint = findText(screen, context.getString(R.string.crop_hint_freeform))
            ?: findText(screen, context.getString(R.string.crop_hint))
        assertNotNull("the hint should be showing", hint)
        assertFalse("a hint with nothing to do must let touches through", hint!!.isClickable)
    }

    @Test
    fun aCropCanStartOnTheHint() {
        val hint = findText(screen, context.getString(R.string.crop_hint_freeform))
            ?: findText(screen, context.getString(R.string.crop_hint))
        assertNotNull(hint)
        val startX = (hint!!.left + hint.right) / 2f
        val startY = (hint.top + hint.bottom) / 2f
        val share = findText(screen, context.getString(R.string.action_share))
        assertNotNull("with nothing selected the button is there (it shares the full screen)", share)

        // A stroke that begins on the hint and loops through the screen.
        onMain {
            val downTime = SystemClock.uptimeMillis()
            var time = downTime
            fun send(action: Int, x: Float, y: Float) {
                val event = MotionEvent.obtain(downTime, time, action, x, y, 0)
                screen.dispatchTouchEvent(event)
                event.recycle()
                time += 10
            }
            send(MotionEvent.ACTION_DOWN, startX, startY)
            send(MotionEvent.ACTION_MOVE, startX - 200f, startY + 300f)
            send(MotionEvent.ACTION_MOVE, startX, startY + 700f)
            send(MotionEvent.ACTION_MOVE, startX + 200f, startY + 300f)
            send(MotionEvent.ACTION_UP, startX + 200f, startY + 300f)
        }

        // The crop view got the stroke and made a selection: the button now
        // shares the selection, and the hint has stepped aside.
        assertEquals(context.getString(R.string.action_share), share!!.text.toString())
        assertEquals(View.GONE, hint.visibility)
    }

    @Test
    fun saveSaves_withASelectionOrWithout_andCloseIsInTheCorner() {
        val delivered = mutableListOf<Pair<Delivery, Rect?>>()
        var cancelled = 0
        onMain {
            screen.callbacks = object : CropScreen.Callbacks {
                override fun onDeliver(action: Delivery, rect: Rect?) {
                    delivered += action to rect
                }
                override fun onCancel() {
                    cancelled++
                }
            }
        }
        val (save, _, share) = barButtons()
        val close = findByDescription(screen, context.getString(R.string.action_close))
        assertNotNull("a close button", close)
        assertNotEquals("not in the bar", share.parent, close!!.parent)
        assertTrue("in the top corner", close.top < HEIGHT / 4 && close.right > WIDTH * 3 / 4)

        // Nothing selected: the whole picture.
        onMain { save.performClick() }
        assertEquals(listOf<Pair<Delivery, Rect?>>(Delivery.SAVE to null), delivered)

        onMain { findCropView(screen)!!.applyCropRect(Rect(100, 200, 600, 900)) }
        assertEquals("the same button, the same words", context.getString(R.string.action_save), save.text.toString())
        // (Delivering remembers the crop for this capture size: put back
        // after the test, see KeepPrefs.)
        onMain { save.performClick() }
        assertEquals(Delivery.SAVE to Rect(100, 200, 600, 900), delivered.last())
        assertEquals("saving doesn't close", 0, cancelled)

        // With a selection, ✕ still closes.
        onMain { close.performClick() }
        assertEquals(1, cancelled)
        assertEquals(2, delivered.size)
    }

    @Test
    fun copyTextSitsBesideCopy_withOrWithoutASelection_inTheRoomCopysLabelTook() {
        val (_, copy, _) = barButtons()
        val bar = copy.parent as ViewGroup
        val text = (0 until bar.childCount).map { bar.getChildAt(it) as TextView }
            .first { it.contentDescription == context.getString(R.string.action_copy_text) }
        // No selection: both there already (the whole screen's text).
        assertEquals(View.VISIBLE, text.visibility)
        assertEquals("Copy gives up its label", "", copy.text.toString())
        assertEquals(context.getString(R.string.action_copy_image), copy.contentDescription)
        // The room the labeled Copy takes, as with Copy text switched off.
        val wasOn = Prefs.isCopyTextEnabled(context)
        Prefs.setCopyTextEnabled(context, false)
        val labeledWidth = try {
            onMain { screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)); layOut() }
            copy.measuredWidth
        } finally {
            Prefs.setCopyTextEnabled(context, wasOn)
        }
        onMain {
            screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))
            findCropView(screen)!!.applyCropRect(Rect(100, 200, 600, 900))
            layOut()
        }
        assertEquals(View.VISIBLE, text.visibility)
        val gap = (text.layoutParams as ViewGroup.MarginLayoutParams).marginStart +
            (copy.layoutParams as ViewGroup.MarginLayoutParams).marginEnd
        assertTrue(
            "the two icons (${copy.measuredWidth} + ${text.measuredWidth} + $gap) fit where Copy was ($labeledWidth)",
            copy.measuredWidth + text.measuredWidth + gap <= labeledWidth + gap
        )
    }

    @Test
    fun withCopyTextSwitchedOff_aSelectionKeepsTheLabeledCopy_andNoCopyText() {
        val was = Prefs.isCopyTextEnabled(context)
        Prefs.setCopyTextEnabled(context, false)
        try {
            val (_, copy, _) = barButtons()
            val bar = copy.parent as ViewGroup
            onMain {
                findCropView(screen)!!.applyCropRect(Rect(100, 200, 600, 900))
                layOut()
            }
            assertEquals(context.getString(R.string.action_copy), copy.text.toString())
            val copyText = (0 until bar.childCount).map { bar.getChildAt(it) as TextView }
                .first { it.contentDescription == context.getString(R.string.action_copy_text) }
            assertEquals(View.GONE, copyText.visibility)
        } finally {
            Prefs.setCopyTextEnabled(context, was)
        }
    }

    @Test
    fun anOpenThatFails_saysSoInThePanelAndKeepsTheToolOpen() {
        var cancelled = 0
        onMain {
            screen.callbacks = object : CropScreen.Callbacks {
                override fun onDeliver(action: Delivery, rect: Rect?) = Unit
                override fun onCancel() {
                    cancelled++
                }
            }
        }
        // A link in a scheme nothing on the device handles.
        val code = QrCode("circletoshare-test-nohandler://x", Rect(300, 600, 700, 1000))
        val cropView = findCropView(screen)!!
        onMain { cropView.setQrCodes(listOf(code)) }

        val open = findText(screen, context.getString(R.string.qr_open))!!
        val error = findText(screen, context.getString(R.string.qr_open_failed))!!
        // The panel is what the error line sits in; a line that is "visible"
        // inside a hidden panel shows nothing.
        val panel = error.parent as View
        assertEquals("the panel is closed until a code is tapped", View.GONE, panel.visibility)

        tapOn(500f, 800f)
        onMain { layOut() }

        assertEquals("tapping the code opens its panel", View.VISIBLE, panel.visibility)
        assertEquals("Open is offered for a link", View.VISIBLE, open.visibility)
        assertEquals("no error before trying", View.GONE, error.visibility)

        onMain {
            open.performClick()
            layOut()
        }

        assertEquals("the panel stays open", View.VISIBLE, panel.visibility)
        assertEquals("the failure must be visible in it", View.VISIBLE, error.visibility)
        assertEquals("Open would only fail again", View.GONE, open.visibility)
        assertEquals("a failed open must not close the tool", 0, cancelled)

        // A touch elsewhere closes the panel; tapping the code again starts
        // afresh. ("Elsewhere" is the strip beside the panel: it keeps a
        // margin from the screen's edge however wide the text makes it.)
        val beside = Rect().also { panel.getHitRect(it) }
        assertTrue("the panel should be laid out", beside.width() > 0 && beside.height() > 0)
        assertTrue("the panel should leave a margin at the edge", beside.left > 2)
        // A touch on the panel itself must not close it…
        tapOn(beside.exactCenterX(), beside.top + 4f)
        assertEquals(View.VISIBLE, panel.visibility)
        // …one beside it does.
        tapOn(beside.left / 2f, beside.exactCenterY())
        assertEquals(View.GONE, panel.visibility)
        tapOn(500f, 800f)
        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(View.GONE, error.visibility)
        assertEquals(View.VISIBLE, open.visibility)
    }

    @Test
    fun backReachesTheHostEvenWithFocusOnAButton() {
        var backs = 0
        onMain { screen.onBackKey = { backs++ } }
        val save = findText(screen, context.getString(R.string.action_save))
        assertNotNull(save)
        onMain {
            // Keyboard focus on a pill: from here a key listener on the
            // screen itself would never be consulted.
            save!!.isFocusableInTouchMode = true
            save.requestFocus()
            screen.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            screen.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }

        assertEquals("one Back press, one callback", 1, backs)
    }

    @Test
    fun withoutABackHandler_backIsLeftToTheWindow() {
        var consumed = true
        onMain {
            consumed = screen.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)) or
                screen.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }

        // The assistant session's dialog handles Back itself; the screen
        // must not swallow it.
        assertFalse(consumed)
    }

    @Test
    fun buttonsThatFitSideBySide_areARowWithTheMainActionLast() {
        val (save, copy, share) = barButtons()

        assertEquals("one row", save.top, copy.top)
        assertEquals("one row", copy.top, share.top)
        assertTrue(save.right <= copy.left && copy.right <= share.left)
        for (button in listOf(save, copy, share)) assertEquals(1, button.lineCount)
    }

    @Test
    fun buttonsWhoseLabelsDoNotFit_showTheirIconsAlone_whereThoseFit() {
        val (save, _, share) = barButtons()
        val bar = share.parent as ViewGroup
        val shown = (0 until bar.childCount).count { bar.getChildAt(it).visibility != View.GONE }
        // Room for every icon alone (18 dp, 14 dp either side, 6 dp margins),
        // not for the labels.
        val density = screen.resources.displayMetrics.density
        val width = ((shown * (18 + 2 * 14 + 2 * 6) + 8) * density).toInt()
        assertTrue(width < bar.width)

        onMain { layOut(width) }
        assertEquals("one row", save.top, share.top)
        assertEquals("", save.text.toString())
        assertEquals("", share.text.toString())
        // Their labels said to screen readers instead.
        assertEquals(context.getString(R.string.action_save), save.contentDescription?.toString())
        assertEquals(context.getString(R.string.action_share), share.contentDescription?.toString())
        assertTrue("within the screen", bar.left >= 0 && bar.right <= width)

        // A label given meanwhile waits for the room...
        onMain {
            share.text = "Send"
            layOut(width)
        }
        assertEquals("", share.text.toString())
        // ...and with the room back, the labels are back.
        onMain { layOut() }
        assertEquals(context.getString(R.string.action_save), save.text.toString())
        assertEquals("Send", share.text.toString())
        assertNull(save.contentDescription)
    }

    @Test
    fun aBarShortOfRoom_givesUpCopysLabelFirst_thenAllLabels_andStaysSo() {
        // (Labeled Copy: with Copy text beside it, the bar is judged by a
        // label it doesn't show.)
        val was = Prefs.isCopyTextEnabled(context)
        Prefs.setCopyTextEnabled(context, false)
        try {
            onMain { screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)) }
            val (save, copy, share) = barButtons()
            val bar = share.parent as ViewGroup
            val density = screen.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            onMain { layOut(3 * WIDTH) }
            assertEquals(context.getString(R.string.action_save), save.text.toString())
            val labeled = bar.measuredWidth
            val copyLabeled = copy.measuredWidth
            fun measure(width: Int) = onMain {
                bar.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
            }
            // A little short of what the labels take, as a long translation
            // can leave it: Copy its icon alone, Save and Share their labels.
            // And so at every measure after: a pill padded for its icon alone
            // was once taken for room to label it, and the row flipped.
            repeat(3) {
                measure(labeled - dp(6))
                assertEquals("", copy.text.toString())
                assertEquals(context.getString(R.string.action_copy), copy.contentDescription?.toString())
                assertEquals(context.getString(R.string.action_save), save.text.toString())
                assertEquals(context.getString(R.string.action_share), share.text.toString())
            }
            // Short of even that (what Copy's label took beyond its icon
            // alone, and more): all their icons alone.
            val copySpares = copyLabeled - copy.measuredWidth
            repeat(3) {
                measure(labeled - copySpares - dp(6))
                assertEquals("", save.text.toString())
                assertEquals("", copy.text.toString())
                assertEquals("", share.text.toString())
            }
            // And back, with the room.
            measure(labeled)
            assertEquals(context.getString(R.string.action_copy), copy.text.toString())
            assertEquals(context.getString(R.string.action_save), save.text.toString())
        } finally {
            Prefs.setCopyTextEnabled(context, was)
        }
    }

    @Test
    fun withCopyText_aBarWithJustTheRoomItTakes_keepsSaveAndShareLabeled() {
        // Copy and Copy text, icons both, take more or less than Copy's label
        // would: with only as much room as the bar shows them in, Save and
        // Share keep their labels either way.
        val (save, copy, share) = barButtons()
        val bar = share.parent as ViewGroup
        val copyText = (0 until bar.childCount).map { bar.getChildAt(it) as TextView }
            .first { it.contentDescription == context.getString(R.string.action_copy_text) }
        onMain { layOut(3 * WIDTH) }
        val shown = bar.measuredWidth + (screen.resources.displayMetrics.density).toInt()
        onMain {
            bar.measure(
                View.MeasureSpec.makeMeasureSpec(shown, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
        }
        assertEquals(View.VISIBLE, copyText.visibility)
        assertEquals("", copy.text.toString())
        assertEquals(context.getString(R.string.action_save), save.text.toString())
        assertEquals(context.getString(R.string.action_share), share.text.toString())
    }

    @Test
    fun aSelectionInABarOfIcons_leavesCopyAnIcon_asWideAsTheOthers() {
        val was = Prefs.isCopyTextEnabled(context)
        Prefs.setCopyTextEnabled(context, false)
        try {
            onMain { screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)) }
            val (save, copy, share) = barButtons()
            val density = screen.resources.displayMetrics.density
            // Room for the icons alone (as in buttonsWhoseLabelsDoNotFit_…).
            val width = ((3 * (18 + 2 * 14 + 2 * 6) + 8) * density).toInt()
            onMain { layOut(width) }
            assertEquals("", copy.text.toString())
            // A tap draws a selection; Copy is set to its label again, which
            // the row has no room for.
            onMain {
                findCropView(screen)!!.applyCropRect(Rect(100, 200, 600, 900))
                layOut(width)
            }
            assertEquals("", copy.text.toString())
            assertEquals("padded as an icon", save.measuredWidth, copy.measuredWidth)
            assertEquals(share.measuredWidth, copy.measuredWidth)
            assertEquals(context.getString(R.string.action_copy), copy.contentDescription?.toString())
        } finally {
            Prefs.setCopyTextEnabled(context, was)
        }
    }

    @Test
    fun aRowGoneToItsIcons_staysSo_measuredAgainAndAgain() {
        // More left and More right, for a selection: labels too long for
        // the room, icons that fit.
        val left = moreButton(R.string.action_capture_more_left)
        val right = moreButton(R.string.action_capture_more_right)
        val density = screen.resources.displayMetrics.density
        val width = (2 * (18 + 2 * 14 + 2 * 6) * density + 2 * 8 * density).toInt()
        onMain {
            screen.onCaptureMore = {}
            screen.onSidewaysRun = { _, _ -> }
            findCropView(screen)!!.applyCropRect(Rect(100, 200, 600, 900))
        }
        // (A label it no longer shows is no room to show it in: once was
        // enough for the row to flip back, and then again.)
        repeat(3) {
            onMain { layOut(width) }
            assertEquals("", left.text.toString())
            assertEquals("", right.text.toString())
            assertEquals(context.getString(R.string.action_capture_more_right), right.contentDescription?.toString())
        }
        val row = left.parent as View
        assertTrue("within the screen", row.left >= 0 && row.right <= width)
        assertEquals("one line, one height", left.height, right.height)
    }

    @Test
    fun buttonsThatDoNotFitSideBySide_stackWithTheMainActionFirst() {
        val (save, copy, share) = barButtons()
        // Too narrow for the three in a row, wide enough for the widest alone
        // — what a small screen at a large font size comes to.
        val bar = share.parent as View
        val narrow = share.width + (save.width + copy.width) / 2
        assertTrue(narrow < bar.width)

        onMain { layOut(narrow) }

        assertTrue("main action on top", share.bottom <= copy.top)
        assertTrue(copy.bottom <= save.top)
        for (button in listOf(save, copy, share)) {
            assertEquals("no label is broken across lines", 1, button.lineCount)
            assertEquals("equally wide", share.width, button.width)
            assertTrue("within the screen", bar.left + button.left >= 0 && bar.left + button.right <= narrow)
        }

        // Its label getting shorter changes nothing…
        onMain {
            share.text = "Send"
            layOut(narrow)
        }
        assertTrue("still stacked", share.bottom <= copy.top)

        // …and with room again it is a row again, in its usual order.
        onMain { layOut() }
        assertEquals(save.top, share.top)
        assertTrue(save.right <= copy.left && copy.right <= share.left)
    }

    @Test
    fun theQrPanelSitsAboveTheButtons_howeverTallTheyAre() {
        val (save, _, share) = barButtons()
        val bar = share.parent as View
        val panel = findText(screen, context.getString(R.string.qr_open_failed))!!.parent as View
        val cropView = findCropView(screen)!!
        onMain { cropView.setQrCodes(listOf(QrCode("https://example.com/", Rect(300, 600, 700, 1000)))) }

        tapOn(500f, 800f)
        onMain { layOut() }
        assertEquals(View.VISIBLE, panel.visibility)
        assertTrue("above the row", panel.bottom < bar.top)

        val narrow = share.width + save.width / 2
        onMain { layOut(narrow) }
        assertTrue("the buttons are stacked", share.bottom <= save.top)
        assertTrue("above the stack", panel.bottom < bar.top)
        assertTrue("and on screen", panel.top >= 0)
    }

    @Test
    fun aMessageInPlaceOfTheTool_closesWhenClicked() {
        var cancelled = 0
        onMain {
            screen.callbacks = object : CropScreen.Callbacks {
                override fun onDeliver(action: Delivery, rect: Rect?) = Unit
                override fun onCancel() {
                    cancelled++
                }
            }
            screen.showMessage("Nothing to crop")
            layOut()
        }

        val message = findText(screen, "Nothing to crop")
        assertNotNull(message)
        assertEquals(View.VISIBLE, message!!.visibility)
        assertEquals("no tool behind it", View.GONE, findCropView(screen)!!.visibility)
        // (setUp had put a picture up, on its black backing.)
        assertNull("nor a black screen where the picture was", screen.background)
        assertEquals(View.GONE, (barButtons().first().parent as View).visibility)

        // (The click itself, not a touch: a view outside any window never
        // gets to run the click it posts when a finger comes up.)
        assertTrue(screen.isClickable)
        onMain { screen.performClick() }
        assertEquals(1, cancelled)

        // A capture arriving turns it back into the tool: no click to take.
        onMain {
            screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))
            layOut()
        }
        assertFalse(screen.isClickable)
        assertFalse(screen.hasOnClickListeners())
        assertEquals(View.VISIBLE, findCropView(screen)!!.visibility)
    }

    private fun moreButton(label: Int): TextView = findText(screen, context.getString(label))!!

    /** Whether [button] is on screen: itself and the row it is in. */
    private fun shows(button: View): Boolean =
        button.visibility == View.VISIBLE && (button.parent as View).visibility == View.VISIBLE

    @Test
    fun captureMoreIsOnlyThereForAHostThatCanDoIt() {
        val above = moreButton(R.string.action_capture_more_above)
        val below = moreButton(R.string.action_capture_more_below)
        assertFalse(shows(above))
        assertFalse(shows(below))

        val asked = ArrayList<CropScreen.More>()
        onMain {
            screen.onCaptureMore = { asked += it }
            layOut()
        }
        assertTrue(shows(above))
        assertTrue(shows(below))
        // A row of their own above the bar, clear of the bar's buttons.
        val bar = barButtons().first().parent as View
        val row = above.parent as View
        assertTrue("the buttons overlap the bar", row.bottom <= bar.top)
        onMain {
            above.performClick()
            below.performClick()
        }
        assertEquals(listOf(CropScreen.More.ABOVE, CropScreen.More.BELOW), asked)

        onMain {
            screen.onCaptureMore = null
            layOut()
        }
        assertFalse(shows(above))
        assertFalse(shows(below))
    }

    @Test
    fun onlyTheWaysThereAreToGoAreOffered() {
        val above = moreButton(R.string.action_capture_more_above)
        val below = moreButton(R.string.action_capture_more_below)
        onMain {
            screen.onCaptureMore = {}
            layOut()
        }
        val bothLeft = below.left
        onMain {
            screen.captureMoreChoices = setOf(CropScreen.More.BELOW)
            layOut()
        }
        assertFalse(shows(above))
        assertTrue(shows(below))
        // The one left stays where it was: it is tapped again and again.
        assertEquals(bothLeft, below.left)
        onMain {
            screen.captureMoreChoices = setOf(CropScreen.More.ABOVE)
            layOut()
        }
        assertTrue(shows(above))
        assertFalse(shows(below))
        onMain {
            screen.captureMoreChoices = emptySet()
            layOut()
        }
        assertFalse(shows(above))
        assertFalse(shows(below))
    }

    /** The "↔" button, found by what it says of itself (it has no label). */
    private fun sidewaysButton(): View = findByDescription(screen, context.getString(R.string.action_capture_sideways))!!

    private fun findByDescription(root: View, description: String): View? {
        if (root.contentDescription?.toString() == description) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) findByDescription(root.getChildAt(i), description)?.let { return it }
        }
        return null
    }

    @Test
    fun saveAndShareAreEquallyWide_andTheBarIsSymmetric() {
        val (save, _, share) = barButtons()
        val bar = share.parent as View
        assertEquals(share.width, save.width)
        // As far from the bar's start as Share is from its end: what lies
        // between them is in the middle.
        assertEquals(save.left, bar.width - share.right)
        assertEquals(WIDTH / 2f, (bar.left + bar.right) / 2f, 1f)
    }

    @Test
    fun theMoreButtonsAreEquallyWide_withSidewaysInTheMiddleOfTheScreen() {
        val above = moreButton(R.string.action_capture_more_above)
        val below = moreButton(R.string.action_capture_more_below)
        val sideways = sidewaysButton()
        onMain {
            screen.onCaptureMore = {}
            screen.onCaptureSideways = {}
            layOut()
        }
        assertEquals(below.width, above.width)
        val row = sideways.parent as View
        assertEquals(WIDTH / 2f, row.left + (sideways.left + sideways.right) / 2f, 1f)
    }

    @Test
    fun sidewaysIsOffered_betweenTheTwo_whileNoRunHasBegun() {
        val above = moreButton(R.string.action_capture_more_above)
        val below = moreButton(R.string.action_capture_more_below)
        val sideways = sidewaysButton()
        onMain {
            screen.onCaptureMore = {}
            layOut()
        }
        // Not without a host for it.
        assertFalse(shows(sideways))
        var asked = 0
        onMain {
            screen.onCaptureSideways = { asked++ }
            layOut()
        }
        assertTrue(shows(sideways))
        assertTrue("between the two", above.right <= sideways.left && sideways.right <= below.left)
        onMain { sideways.performClick() }
        assertEquals(1, asked)
        // Gone once the picture has grown one way...
        onMain {
            screen.captureMoreChoices = setOf(CropScreen.More.BELOW)
            layOut()
        }
        assertFalse(shows(sideways))
        // ...and in a sideways run, which has buttons of its own.
        onMain {
            screen.captureMoreChoices = setOf(CropScreen.More.LEFT, CropScreen.More.RIGHT)
            layOut()
        }
        assertFalse(shows(sideways))
        assertFalse(shows(above))
        assertTrue(shows(moreButton(R.string.action_capture_more_left)))
        assertTrue(shows(moreButton(R.string.action_capture_more_right)))
        // Equally wide, whatever their labels.
        assertEquals(
            moreButton(R.string.action_capture_more_right).width,
            moreButton(R.string.action_capture_more_left).width
        )
    }

    @Test
    fun aCropOfAScrollshot_isNotRemembered() {
        // The crop of the screen before stays the one to restore.
        Prefs.saveLastCrop(context, Rect(10, 20, 300, 400), WIDTH, HEIGHT)
        val (dismiss, _, _) = barButtons()
        onMain {
            screen.callbacks = object : CropScreen.Callbacks {
                override fun onDeliver(action: Delivery, rect: Rect?) = Unit
                override fun onCancel() = Unit
            }
            screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT * 3, Bitmap.Config.ARGB_8888), scrollshot = true)
            findCropView(screen)!!.applyCropRect(Rect(100, 2000, 600, 4000))
            layOut()
            dismiss.performClick() // Save
        }
        assertEquals(Rect(10, 20, 300, 400), Prefs.getLastCropFor(context, WIDTH, HEIGHT))
        assertNull(Prefs.getLastCropFor(context, WIDTH, HEIGHT * 3))
    }

    @Test
    fun nothingScrollingSideways_stillOffersTheLastCrop() {
        Prefs.saveLastCrop(context, Rect(10, 20, 300, 400), WIDTH, HEIGHT)
        val crop = findCropView(screen)!!
        onMain {
            screen.onCaptureMore = {}
            screen.onCaptureSideways = {}
            screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))
            layOut()
        }
        assertNotNull(findText(screen, context.getString(R.string.crop_hint_restore)))
        onMain {
            screen.pickArea(emptyList())
            layOut()
        }
        val hint = findText(screen, context.getString(R.string.capture_sideways_none_restore))
        assertNotNull("the hint says both: no row, and the crop to restore", hint)
        assertTrue(hint!!.isClickable)
        onMain { hint.performClick() }
        assertEquals(Rect(10, 20, 300, 400), crop.getCropRect())
    }

    @Test
    fun nothingScrollingSideways_saysSo_andOnlyTakesTheSidewaysButtonAway() {
        val above = moreButton(R.string.action_capture_more_above)
        val below = moreButton(R.string.action_capture_more_below)
        val sideways = sidewaysButton()
        val left = moreButton(R.string.action_capture_more_left)
        val crop = findCropView(screen)!!
        onMain {
            screen.onCaptureMore = {}
            screen.onCaptureSideways = {}
            screen.onSidewaysRun = { _, _ -> }
            layOut()
        }
        assertTrue(shows(sideways))
        onMain {
            screen.pickArea(emptyList())
            layOut()
        }
        // The same view, told: the vertical buttons stay, the "↔" goes.
        assertFalse(screen.pickingArea)
        assertTrue(shows(above))
        assertTrue(shows(below))
        assertFalse(shows(sideways))
        assertEquals(
            context.getString(R.string.capture_sideways_none),
            findText(screen, context.getString(R.string.capture_sideways_none))?.text?.toString()
        )
        // A box drawn by hand offers the sideways run as ever...
        onMain {
            crop.applyCropRect(Rect(100, 800, 900, 1100))
            layOut()
        }
        assertTrue(shows(left))
        // ...and cleared, the word stays until the next capture.
        onMain {
            crop.clear()
            layOut()
        }
        assertFalse(shows(sideways))
        assertNotNull(findText(screen, context.getString(R.string.capture_sideways_none)))
        onMain {
            screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))
            layOut()
        }
        assertTrue(shows(sideways))
        assertNull(findText(screen, context.getString(R.string.capture_sideways_none)))
    }

    @Test
    fun choosingARow_byTapOrByBox_startsASidewaysRunFromTheSelection() {
        val above = moreButton(R.string.action_capture_more_above)
        val left = moreButton(R.string.action_capture_more_left)
        val right = moreButton(R.string.action_capture_more_right)
        val crop = findCropView(screen)!!
        var started: Pair<Rect, CropScreen.More>? = null
        onMain {
            screen.onCaptureMore = {}
            screen.onCaptureSideways = {}
            screen.onSidewaysRun = { area, more -> started = area to more }
            layOut()
        }
        val areas = listOf(Rect(0, 200, WIDTH, 500), Rect(0, 1200, WIDTH, 1500))
        onMain {
            screen.pickArea(areas)
            layOut()
        }
        // Choosing: the vertical buttons are away, and so are the sideways
        // ones until something is selected; the hint says what to do.
        assertTrue(screen.pickingArea)
        assertFalse(shows(above))
        assertFalse(shows(right))
        assertEquals(
            context.getString(R.string.capture_sideways_pick),
            findText(screen, context.getString(R.string.capture_sideways_pick))?.text?.toString()
        )
        // A tap on the second outline makes it the selection...
        val where = crop.mapToView(areas[1])
        onMain {
            val picker = findByDescriptionLess(screen, "AreaPickerView")!!
            val x = where.centerX()
            val y = where.centerY()
            picker.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, x, y, 0))
            picker.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 10, android.view.MotionEvent.ACTION_UP, x, y, 0))
            layOut()
        }
        assertEquals(areas[1], crop.getCropRect())
        assertTrue(shows(left))
        assertTrue(shows(right))
        assertEquals(
            context.getString(R.string.capture_sideways_go),
            findText(screen, context.getString(R.string.capture_sideways_go))?.text?.toString()
        )
        // ...and "More right" starts the run of it.
        onMain { right.performClick() }
        assertEquals(areas[1] to CropScreen.More.RIGHT, started)
        assertFalse(screen.pickingArea)

        // A box of the user's own does as well — with no outlines asked for:
        // every selection offers the two, while no run has begun.
        onMain {
            crop.applyCropRect(Rect(100, 800, 900, 1100))
            layOut()
        }
        assertFalse(screen.pickingArea)
        assertTrue(shows(left))
        assertFalse(shows(above))
        onMain { left.performClick() }
        assertEquals(Rect(100, 800, 900, 1100) to CropScreen.More.LEFT, started)
        // And is remembered, as a shared crop is, for the next capture of
        // the same screen.
        assertEquals(Rect(100, 800, 900, 1100), Prefs.getLastCropFor(context, WIDTH, HEIGHT))
        // Not in a sideways run, whose buttons are its steps.
        onMain {
            screen.captureMoreChoices = setOf(CropScreen.More.RIGHT)
            crop.applyCropRect(Rect(100, 800, 900, 1100))
            layOut()
        }
        assertFalse(shows(left))
        assertFalse(shows(right))
        onMain {
            screen.captureMoreChoices = setOf(CropScreen.More.ABOVE, CropScreen.More.BELOW)
            crop.clear()
        }

        // Back while choosing only leaves the choosing; a new capture too.
        var backs = 0
        onMain {
            screen.onBackKey = { backs++ }
            screen.pickArea(areas)
            screen.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            screen.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        assertEquals(0, backs)
        assertFalse(screen.pickingArea)
        onMain {
            screen.pickArea(areas)
            screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))
            layOut()
        }
        assertFalse(screen.pickingArea)
        assertTrue(shows(above))
    }

    private fun findByDescriptionLess(root: View, className: String): View? {
        if (root.javaClass.simpleName == className) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) findByDescriptionLess(root.getChildAt(i), className)?.let { return it }
        }
        return null
    }

    @Test
    fun captureMoreStepsAsideWhileSomethingIsSelected() {
        val above = moreButton(R.string.action_capture_more_above)
        val below = moreButton(R.string.action_capture_more_below)
        val crop = findCropView(screen)!!
        onMain {
            screen.onCaptureMore = {}
            layOut()
        }
        assertTrue(shows(above))
        assertTrue(shows(below))

        onMain { crop.applyCropRect(Rect(200, 600, 800, 1400)) }
        assertFalse(shows(above))
        assertFalse(shows(below))

        // A new capture starts with nothing selected.
        onMain {
            screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))
            layOut()
        }
        assertTrue(shows(above))
        assertTrue(shows(below))
    }

    @Test
    fun captureMoreComesAndGoesWithTheTool() {
        val below = moreButton(R.string.action_capture_more_below)
        onMain {
            screen.onCaptureMore = {}
            screen.showMessage("nothing to crop")
            layOut()
        }
        assertFalse(shows(below))
        onMain {
            screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))
            layOut()
        }
        assertTrue(shows(below))
        onMain {
            screen.clear()
            layOut()
        }
        assertFalse(shows(below))
    }

    @Test
    fun whileDelivering_theButtonsTakeNoTaps() {
        val delivered = ArrayList<Delivery>()
        val asked = ArrayList<CropScreen.More>()
        onMain {
            screen.callbacks = object : CropScreen.Callbacks {
                override fun onDeliver(action: Delivery, rect: Rect?) {
                    delivered += action
                }
                override fun onCancel() {}
            }
            screen.onCaptureMore = { asked += it }
            layOut()
        }
        val bar = barButtons().last().parent as ViewGroup
        // Save, Copy, Copy text, Share; and a More button.
        val buttons = (0 until bar.childCount).map { bar.getChildAt(it) } +
            moreButton(R.string.action_capture_more_above)

        onMain { screen.setDelivering(true) }
        // (Disabled, as screen readers say: neither a tap nor a screen
        // reader's click does anything.)
        assertEquals(emptyList<View>(), buttons.filter { it.isEnabled })
        onMain { buttons.forEach { it.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null) } }
        assertEquals(emptyList<Delivery>(), delivered)
        assertEquals(emptyList<CropScreen.More>(), asked)

        // Over (it failed, say): they do again. (Nothing selected: no crop
        // remembered.)
        onMain { screen.setDelivering(false) }
        onMain { barButtons().first().performClick() }
        assertEquals(listOf(Delivery.SAVE), delivered)
    }

    @Test
    fun aNoticeOnTwoLines_centersThem_andIsNoWiderThanTheLongest() {
        val notice = "A notice up here long enough to take more than the one line"
        lateinit var hint: TextView
        onMain {
            screen.showMessage(notice)
            // Narrow, as a large font makes it.
            layOut(WIDTH / 2)
            hint = findText(screen, notice)!!
        }
        val lines = hint.layout
        assertTrue("on more than one line", lines.lineCount > 1)
        val room = hint.width - hint.compoundPaddingLeft - hint.compoundPaddingRight
        val longest = (0 until lines.lineCount).maxOf { lines.getLineWidth(it) }
        assertTrue("no wider than its longest line ($room for $longest)", room <= longest + 1)
        for (i in 0 until lines.lineCount) {
            val before = lines.getLineLeft(i)
            val after = room - lines.getLineRight(i)
            assertEquals("line $i centered", before, after, 1.5f)
        }
        assertEquals("centered on the screen", (WIDTH / 2).toDouble(), (hint.left + hint.right).toDouble(), 1.0)
    }

    /** Cancel, Copy and the share button of the main bar. */
    private fun barButtons(): List<TextView> {
        val share = findText(screen, context.getString(R.string.action_share))!!
        val bar = share.parent as ViewGroup
        fun inBar(label: Int, description: Int = label): TextView =
            (0 until bar.childCount).map { bar.getChildAt(it) as TextView }
                .first { it.text.toString() == context.getString(label) || it.contentDescription == context.getString(description) }
        // (Copy shows its icon alone beside Copy text, when that is on.)
        return listOf(inBar(R.string.action_save), inBar(R.string.action_copy, R.string.action_copy_image), share)
    }

    private fun tapOn(x: Float, y: Float) = onMain {
        val downTime = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to downTime, MotionEvent.ACTION_UP to downTime + 50)) {
            val event = MotionEvent.obtain(downTime, time, action, x, y, 0)
            screen.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun findCropView(root: View): CropOverlayView? {
        if (root is CropOverlayView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findCropView(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun findText(root: View, text: String): TextView? {
        if (root is TextView && root.text.toString() == text) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findText(root.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private companion object {
        const val WIDTH = 1081
        const val HEIGHT = 2399
    }
}
