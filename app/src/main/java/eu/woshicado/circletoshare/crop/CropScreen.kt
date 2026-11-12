package eu.woshicado.circletoshare.crop

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.doOnLayout
import eu.woshicado.circletoshare.R
import eu.woshicado.circletoshare.qr.QrCode
import eu.woshicado.circletoshare.qr.QrDetector
import eu.woshicado.circletoshare.qr.qrOpenUri
import eu.woshicado.circletoshare.share.Delivery
import eu.woshicado.circletoshare.text.PendingScreenText
import eu.woshicado.circletoshare.text.TextCopy
import kotlin.math.ceil

/**
 * The full crop overlay UI — frozen screenshot, draggable crop rectangle, and
 * the Cancel-or-Save/Copy/Share pill bar. Shared by both the assistant session and the
 * floating-bubble path so the two behave identically.
 */
class CropScreen(context: Context) : FrameLayout(context) {

    interface Callbacks {
        /** [rect] is in bitmap coordinates, or null to share the whole screen. */
        fun onDeliver(action: Delivery, rect: Rect?)
        fun onCancel()
    }

    var callbacks: Callbacks? = null

    /**
     * What Back does, for a host whose window gets no Back handling of its
     * own (a bare overlay window; the assistant session's dialog has its own
     * and leaves this unset).
     */
    var onBackKey: (() -> Unit)? = null

    /** The two ways to capture more: what is further up the list on screen,
     *  or further down. */
    enum class More { ABOVE, BELOW, LEFT, RIGHT }

    /**
     * What the "Capture more" buttons do, for a host that can
     * scroll the app underneath and come back with a longer picture; they
     * are only there while this is set.
     */
    var onCaptureMore: ((More) -> Unit)? = null
        set(value) {
            field = value
            updateMoreButton()
        }

    /** Which of the buttons there is anything behind: the up-and-down pair,
     *  or (a sideways run) the left-and-right pair. */
    var captureMoreChoices: Set<More> = setOf(More.ABOVE, More.BELOW)
        set(value) {
            field = value
            updateMoreButton()
        }

    /**
     * What the "↔" button does, for a host that can find the
     * parts of the screen that scroll sideways and grow a picture of one:
     * the button is there, between "More above" and "More below", while
     * this is set and no run has begun.
     */
    var onCaptureSideways: (() -> Unit)? = null
        set(value) {
            field = value
            updateMoreButton()
        }

    private val cropView = CropOverlayView(context)
    private val hintView: TextView
    // A word about Copy text (no text found), above the buttons for a while.
    private val noticeView: TextView
    private val buttonBar: ActionRow
    // Closes the tool: in the top corner, out of the bar, so that every
    // button there does the same with a selection or without (on it, or on
    // the whole picture).
    private val closeButton: TextView
    private val saveButton: TextView
    // Copy, and beside it with a selection "Copy text": both icons only then,
    // in the room Copy's label took.
    private val copyButton: TextView
    private val copyTextButton: TextView
    private val shareButton: TextView
    private val moreRow: ActionRow
    private val moreAboveButton: TextView
    private val moreSidewaysButton: TextView
    private val moreBelowButton: TextView
    private val sideRow: ActionRow
    private val moreLeftButton: TextView
    private val moreRightButton: TextView
    private val areaPicker: AreaPickerView
    private val qrPanel: LinearLayout
    private val qrPanelText: TextView
    private val qrPanelError: TextView
    private val qrOpenButton: TextView

    // Saved crop offered by the tappable hint, or null when none is available
    // for the current capture.
    private var pendingRestore: Rect? = null
    private var scrollshot = false

    // The capture currently on screen — identity-checked when an async QR scan
    // finishes, so a result for a dismissed or replaced capture is dropped.
    private var currentBitmap: android.graphics.Bitmap? = null

    // The tapped code whose actions the panel is showing.
    private var activeQr: QrCode? = null

    private var hasSelection = false

    // The QR scan of [currentBitmap], while it runs.
    private var qrScan: QrDetector.Scan? = null

    // The text on screen when [currentBitmap] was taken, for "Copy text", as
    // it is being read (null when the capture came without it); stopped when
    // the tool closes or shows another picture. And that copy while it runs.
    private var screenText: PendingScreenText? = null
    private var textCopy: TextCopy.Job? = null
    private val noticeGone = Runnable { noticeView.visibility = View.GONE }

    init {
        cropView.visibility = View.GONE
        cropView.onZoomChanged = { areaPicker.invalidate() }
        cropView.listener = object : CropOverlayView.Listener {
            override fun onSelectionChanged(hasSelection: Boolean) {
                showSelectionActions(hasSelection)
                showPicking()
            }

            override fun onDoubleTapInside() = deliver(Delivery.SHARE)
            override fun onLongPressInside() = deliver(Delivery.COPY)
            override fun onDoubleTapEmpty() {
                callbacks?.onCancel()
            }

            override fun onQrTapped(code: QrCode) = showQrPanel(code)
        }
        addView(
            cropView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        hintView = TextView(context).apply {
            text = context.getString(R.string.crop_hint)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = pill(Color.argb(200, 30, 30, 30), 24, outlined = true)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            compoundDrawablePadding = dp(ICON_PADDING_DP)
            compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        addView(
            hintView,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(HINT_TOP_DP)
                // Keep a long text (or a large font) off the screen's edges,
                // and the close button's corner: the same on both sides, to
                // stay centered.
                leftMargin = dp(16 + 48 + 8)
                rightMargin = dp(16 + 48 + 8)
            }
        )

        closeButton = pillButton("", R.drawable.ic_close, filled = false) { callbacks?.onCancel() }.apply {
            contentDescription = context.getString(R.string.action_close)
            tooltipText = contentDescription
            compoundDrawablePadding = 0
            // A round button, as tall as the bar's.
            setPaddingRelative(dp(15), dp(15), dp(15), dp(15))
            minWidth = dp(48)
            visibility = View.GONE
        }
        addView(
            closeButton,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                // Level with the hint, clear of the status bar.
                topMargin = dp(HINT_TOP_DP - 4)
                marginEnd = dp(16)
            }
        )

        buttonBar = ActionRow(context).apply { visibility = View.GONE }
        saveButton = pillButton(
            context.getString(R.string.action_save), R.drawable.ic_save, filled = false
        ) {
            deliver(Delivery.SAVE)
        }
        copyButton = pillButton(
            context.getString(R.string.action_copy), R.drawable.ic_copy, filled = false
        ) {
            deliver(Delivery.COPY)
        }
        copyTextButton = pillButton("", R.drawable.ic_copy_text, filled = false) { copyText() }
        setIconOnly(copyTextButton, R.string.action_copy_text)
        copyTextButton.visibility = View.GONE
        shareButton = pillButton(
            context.getString(R.string.action_share), R.drawable.ic_share, filled = true
        ) {
            deliver(Delivery.SHARE)
        }
        buttonBar.addAction(saveButton)
        // (Its label, for the fit, also while it shows its icon alone.)
        buttonBar.addAction(copyButton, context.getString(R.string.action_copy))
        // (Not counted for the fit: with it, Copy loses its label.)
        buttonBar.addAction(copyTextButton, measured = false)
        buttonBar.addAction(shareButton)
        // Save and Share alike, the copy buttons between them in the middle.
        buttonBar.matchWidths(saveButton, shareButton)
        addView(
            buttonBar,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(BAR_BOTTOM_MARGIN_DP) }
        )

        // A row of their own above the bar, not more buttons in it: the
        // bar's three are a tight fit already.
        moreAboveButton = pillButton(
            context.getString(R.string.action_capture_more_above), R.drawable.ic_arrow_upward,
            filled = false
        ) {
            onCaptureMore?.invoke(More.ABOVE)
        }
        moreBelowButton = pillButton(
            context.getString(R.string.action_capture_more_below), R.drawable.ic_arrow_downward,
            filled = false
        ) {
            onCaptureMore?.invoke(More.BELOW)
        }
        // Between the two, the icon alone: what it does is a mode, not a
        // step, and a word for it would make the row too wide for one line.
        moreSidewaysButton = pillButton("", R.drawable.ic_swap_horiz, filled = false) {
            onCaptureSideways?.invoke()
        }.apply {
            contentDescription = context.getString(R.string.action_capture_sideways)
            compoundDrawablePadding = 0
            setPaddingRelative(dp(14), dp(13), dp(14), dp(13))
        }
        moreRow = ActionRow(context).apply {
            visibility = View.GONE
            addAction(moreAboveButton)
            addAction(moreSidewaysButton)
            addAction(moreBelowButton)
            // "↔" in the middle.
            matchWidths(moreAboveButton, moreBelowButton)
        }
        val moreRowParams = {
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(BAR_BOTTOM_MARGIN_DP + 48 + MORE_GAP_DP) }
        }
        addView(moreRow, moreRowParams())

        // A sideways run has a row of its own in the same place.
        moreLeftButton = pillButton(
            context.getString(R.string.action_capture_more_left), R.drawable.ic_arrow_back,
            filled = false
        ) {
            sideways(More.LEFT)
        }
        moreRightButton = pillButton(
            context.getString(R.string.action_capture_more_right), R.drawable.ic_arrow_forward,
            filled = false
        ) {
            sideways(More.RIGHT)
        }
        sideRow = ActionRow(context).apply {
            visibility = View.GONE
            addAction(moreLeftButton)
            addAction(moreRightButton)
            matchWidths(moreLeftButton, moreRightButton)
        }
        addView(sideRow, moreRowParams())

        // Over the picture while a row is being chosen (see pickArea).
        areaPicker = AreaPickerView(context).apply { visibility = View.GONE }
        addView(areaPicker, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Actions for a tapped QR code: the payload plus Copy/Open pills.
        // Sits above the main button bar; dismissed by tapping anywhere else.
        qrPanelText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }
        // Why a tapped code could not be opened — shown here rather than in a
        // toast, which is drawn beneath this tool's window where nobody sees
        // it.
        qrPanelError = TextView(context).apply {
            text = context.getString(R.string.qr_open_failed)
            // Material's error tone for dark surfaces.
            setTextColor(Color.rgb(255, 180, 171))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            visibility = View.GONE
            // Spoken by a screen reader when it appears.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        qrOpenButton = pillButton(
            context.getString(R.string.qr_open), R.drawable.ic_open_in_new, filled = true
        ) {
            activeQr?.let { openQr(it.text) }
        }
        val qrCopyButton = pillButton(
            context.getString(R.string.action_copy), R.drawable.ic_copy, filled = false
        ) {
            activeQr?.let { copyQr(it.text) }
        }
        qrPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(Color.argb(235, 30, 30, 30), 20, outlined = true)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            visibility = View.GONE
            // Swallow every touch in the panel's area — without this, a drag
            // starting on the panel body falls through to the crop view and
            // draws a selection behind the (still open) panel.
            isClickable = true
            addView(
                qrPanelText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                qrPanelError,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            )
            addView(
                ActionRow(context).apply {
                    addAction(qrCopyButton)
                    addAction(qrOpenButton)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10); gravity = Gravity.CENTER_HORIZONTAL }
            )
        }
        addView(
            qrPanel,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(BAR_BOTTOM_MARGIN_DP + 48 + QR_PANEL_GAP_DP)
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
        )

        // In this window, like the QR panel's error: a toast lay over the
        // buttons, and the system drops one after a few in a row from the
        // same app, leaving no word at all.
        noticeView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = pill(Color.argb(235, 30, 30, 30), 24, outlined = true)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(
            noticeView,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(BAR_BOTTOM_MARGIN_DP + 48 + MORE_GAP_DP)
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // The QR panel sits a fixed gap above the button bar — however tall
        // the bar has turned out (a large font size, or stacked buttons).
        // "Capture more" likewise, and a notice above whichever of them shows.
        if (buttonBar.visibility == View.GONE) return
        val params = qrPanel.layoutParams as LayoutParams
        val above = dp(BAR_BOTTOM_MARGIN_DP + QR_PANEL_GAP_DP) + buttonBar.measuredHeight
        val moreParams = moreRow.layoutParams as LayoutParams
        val sideParams = sideRow.layoutParams as LayoutParams
        val moreAbove = dp(BAR_BOTTOM_MARGIN_DP + MORE_GAP_DP) + buttonBar.measuredHeight
        val noticeParams = noticeView.layoutParams as LayoutParams
        val row = listOf(moreRow, sideRow).firstOrNull { it.visibility == View.VISIBLE }
        val noticeAbove = moreAbove + (row?.let { it.measuredHeight + dp(MORE_GAP_DP) } ?: 0)
        if (params.bottomMargin != above || moreParams.bottomMargin != moreAbove ||
            sideParams.bottomMargin != moreAbove || noticeParams.bottomMargin != noticeAbove
        ) {
            params.bottomMargin = above
            moreParams.bottomMargin = moreAbove
            sideParams.bottomMargin = moreAbove
            noticeParams.bottomMargin = noticeAbove
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    /**
     * [scrollshot]: the picture is a "Capture more" picture — longer than
     * the screen, or a sideways strip — and a crop of it is not remembered
     * (see [deliver]): the memory keeps the crop of the screen before.
     */
    fun setBitmap(
        bitmap: android.graphics.Bitmap, snap: SnapBounds? = null, scrollshot: Boolean = false,
        text: PendingScreenText? = null
    ) {
        this.scrollshot = scrollshot
        dropText()
        screenText = text
        // Apply the current crop-gesture preference (freeform circle vs. box).
        val freeform = eu.woshicado.circletoshare.Prefs.isFreeformCrop(context)
        cropView.freeform = freeform
        cropView.snapToEdges = eu.woshicado.circletoshare.Prefs.isSnapToEdgesEnabled(context)

        setBackgroundColor(Color.BLACK)
        setOnClickListener(null)
        isClickable = false
        currentBitmap = bitmap
        qrScan?.cancel()
        qrScan = null
        hideQrPanel()
        // A new capture starts afresh: not in the middle of choosing a row
        // for the one before (this screen is kept from one capture to the
        // next by the assistant session), and with the "↔" button back.
        sidewaysUnavailable = false
        if (pickingArea) leavePicking()
        cropView.setBitmap(bitmap)
        cropView.setSnapBounds(snap)
        // Scan the capture for QR codes off the main thread; the identity
        // check drops the result if the tool was dismissed (or reloaded with
        // a new capture) while the scan ran. Both trigger paths — assistant
        // session and bubble — funnel through here, so this covers them all.
        cropView.setQrHighlightColor(eu.woshicado.circletoshare.Prefs.getQrColor(context))
        if (eu.woshicado.circletoshare.Prefs.isQrDetectEnabled(context)) {
            qrScan = QrDetector.detect(bitmap) { codes ->
                if (currentBitmap === bitmap) cropView.setQrCodes(codes)
            }
        }
        cropView.visibility = View.VISIBLE
        hintView.visibility = View.VISIBLE
        buttonBar.visibility = View.VISIBLE
        closeButton.visibility = View.VISIBLE
        updateMoreButton()
        showSelectionActions(false)

        // Offer to restore the last crop only when it fits this exact capture.
        pendingRestore = eu.woshicado.circletoshare.Prefs
            .getLastCropFor(context, bitmap.width, bitmap.height)
        showHint()
    }

    /**
     * Show [message] in place of the tool — for a host that has nothing to
     * crop and nowhere else to say why. A touch anywhere asks the host to
     * close ([Callbacks.onCancel]); with [onTap], a touch on the message
     * itself runs that instead. Undone by [setBitmap] and [clear].
     */
    fun showMessage(message: CharSequence, onTap: (() -> Unit)? = null) {
        // Nothing behind the message — not the black the picture stood on,
        // if there was one: the screen underneath shows through instead.
        background = null
        currentBitmap = null
        qrScan?.cancel()
        qrScan = null
        dropText()
        hideQrPanel()
        cropView.visibility = View.GONE
        buttonBar.visibility = View.GONE
        closeButton.visibility = View.GONE
        updateMoreButton()
        hintView.text = message
        hintView.setCompoundDrawablesRelative(null, null, null, null)
        // (In this order — see setBitmap.)
        hintView.setOnClickListener(onTap?.let { tap -> View.OnClickListener { tap() } })
        hintView.isClickable = onTap != null
        hintView.visibility = View.VISIBLE
        setOnClickListener { callbacks?.onCancel() }
    }

    /** Drop the frozen screenshot and hide the UI — the reverse of [setBitmap].
     *  Called when the overlay is dismissed so a reused instance can't flash
     *  the previous session's image. */
    fun clear() {
        sidewaysUnavailable = false
        if (pickingArea) leavePicking()
        background = null
        currentBitmap = null
        qrScan?.cancel()
        qrScan = null
        dropText()
        setOnClickListener(null)
        isClickable = false
        hideQrPanel()
        cropView.clear()
        // Drop the snap lines too — a reused instance must not snap to a
        // previous session's layout.
        cropView.setSnapBounds(null)
        cropView.visibility = View.GONE
        hintView.visibility = View.GONE
        buttonBar.visibility = View.GONE
        closeButton.visibility = View.GONE
        updateMoreButton()
    }

    /** The "Capture more" buttons show with the button bar, where there is
     *  something for them to do, and only while nothing is selected: a
     *  longer picture replaces the one the box was drawn on, and the box
     *  with it. They also make way for the QR panel, which opens in the same
     *  place. */
    private fun updateMoreButton() {
        val choices = captureMoreChoices
        val sideways = More.LEFT in choices || More.RIGHT in choices
        // The one there is nothing behind keeps its place, unseen: the other
        // gets tapped again and again, and must not move from under the
        // finger between two taps.
        moreAboveButton.visibility = if (More.ABOVE in choices) View.VISIBLE else View.INVISIBLE
        moreBelowButton.visibility = if (More.BELOW in choices) View.VISIBLE else View.INVISIBLE
        // The way sideways is open while the picture has not grown yet; its
        // place is kept after, like the other's (see above).
        moreSidewaysButton.visibility = when {
            onCaptureSideways == null || sidewaysUnavailable -> View.GONE
            More.ABOVE in choices && More.BELOW in choices -> View.VISIBLE
            else -> View.INVISIBLE
        }
        moreLeftButton.visibility = if (More.LEFT in choices) View.VISIBLE else View.INVISIBLE
        moreRightButton.visibility = if (More.RIGHT in choices) View.VISIBLE else View.INVISIBLE
        val shown = onCaptureMore != null && choices.isNotEmpty() &&
            buttonBar.visibility == View.VISIBLE &&
            !hasSelection && qrPanel.visibility != View.VISIBLE
        // A selection, where no run has begun and a host can grow a strip of
        // it: "More left" and "More right" start one — the row chosen by a
        // tap on an outline (see pickArea) or any box the user drew.
        if (hasSelection && !sideways && onSidewaysRun != null && onCaptureMore != null) {
            moreLeftButton.visibility = View.VISIBLE
            moreRightButton.visibility = View.VISIBLE
            moreRow.visibility = View.GONE
            sideRow.visibility =
                if (buttonBar.visibility == View.VISIBLE && qrPanel.visibility != View.VISIBLE) View.VISIBLE
                else View.GONE
            return
        }
        moreRow.visibility = if (shown && !sideways && !pickingArea) View.VISIBLE else View.GONE
        sideRow.visibility = if (shown && sideways) View.VISIBLE else View.GONE
    }

    /** "More left" or "More right": in a sideways run a step of it; over a
     *  selection, the start of one from it. */
    private fun sideways(more: More) {
        val sidewaysRun = More.LEFT in captureMoreChoices || More.RIGHT in captureMoreChoices
        if (sidewaysRun) return onCaptureMore?.invoke(more) ?: Unit
        val area = cropView.getCropRect() ?: return
        // Remembered like a crop that was shared: the hint offers it back
        // the next time the same screen is captured.
        if (!scrollshot) cropView.getBitmapSize()?.let { (w, h) ->
            eu.woshicado.circletoshare.Prefs.saveLastCrop(context, area, w, h)
        }
        if (pickingArea) leavePicking()
        onSidewaysRun?.invoke(area, more)
    }

    /**
     * Outline [areas] — the parts of the picture that scroll
     * sideways, in the picture's pixels — for a tap on one to make it the
     * selection. (A box drawn by hand does as well, with or without this.)
     * With a selection, "More left" and "More right" are offered, and one
     * of them hands the selection and the way to [onSidewaysRun]. Back
     * leaves the outlines.
     */
    fun pickArea(areas: List<Rect>) {
        if (areas.isEmpty()) {
            // Nothing to outline: the tool stays as it is, says so, and
            // keeps the "↔" button away — pressing it again would find no
            // more. A box drawn by hand still has "More left" and "More
            // right".
            sidewaysUnavailable = true
            showPicking()
            return
        }
        pickingArea = true
        areaPicker.show(areas, cropView::mapToView) { cropView.applyCropRect(it) }
        showPicking()
    }

    // The "↔" button found nothing on this capture (see pickArea).
    private var sidewaysUnavailable = false

    private fun leavePicking() {
        pickingArea = false
        areaPicker.hide()
        showPicking()
    }

    /** The hint and the buttons as the choosing has them (see [pickArea]);
     *  or back as they were. */
    private fun showPicking() {
        if (pickingArea) {
            hintView.setText(if (hasSelection) R.string.capture_sideways_go else R.string.capture_sideways_pick)
            // (Not the restore offer's icon and tap, if that was showing.)
            hintView.setCompoundDrawablesRelative(null, null, null, null)
            hintView.setOnClickListener(null)
            hintView.isClickable = false
            hintView.visibility = View.VISIBLE
            // The outlines are for choosing; chosen, the box shows what is.
            areaPicker.visibility = if (hasSelection) View.INVISIBLE else View.VISIBLE
        } else {
            hintView.visibility = if (hasSelection) View.GONE else View.VISIBLE
            showHint()
        }
        updateMoreButton()
    }

    /** The hint over the picture: the saved crop to restore, if one fits,
     *  else how to crop — and that no row scrolls sideways, once the "↔"
     *  button has found none (see [pickArea]). */
    private fun showHint() {
        if (pendingRestore != null) {
            hintView.setText(
                if (sidewaysUnavailable) R.string.capture_sideways_none_restore else R.string.crop_hint_restore
            )
            hintView.setCompoundDrawablesRelative(icon(R.drawable.ic_restore), null, null, null)
            hintView.isClickable = true
            hintView.setOnClickListener { pendingRestore?.let { cropView.applyCropRect(it) } }
        } else {
            hintView.setText(
                when {
                    sidewaysUnavailable -> R.string.capture_sideways_none
                    cropView.freeform -> R.string.crop_hint_freeform
                    else -> R.string.crop_hint
                }
            )
            hintView.setCompoundDrawablesRelative(null, null, null, null)
            // In this order: setOnClickListener makes a view clickable, even
            // when handed null. A clickable hint swallows every touch that
            // starts on it — no crop could begin there, and a QR code
            // underneath couldn't be tapped.
            hintView.setOnClickListener(null)
            hintView.isClickable = false
        }
    }

    /** Whether a row to capture sideways is being chosen (see [pickArea]). */
    var pickingArea: Boolean = false
        private set

    /**
     * What "More left" and "More right" do over a selection
     * while no run has begun: the selection, in the picture's pixels, and
     * the way to grow a picture of it. Set, every selection offers them.
     */
    var onSidewaysRun: ((Rect, More) -> Unit)? = null
        set(value) {
            field = value
            updateMoreButton()
        }

    override fun onDetachedFromWindow() {
        // Taken off the screen for good (an overlay window is not reused):
        // nobody is left to show what the scan finds — nor to have a copy of
        // text still under way overwrite the clipboard, or close whatever
        // tool is open by the time it is done. (The screen's text being read
        // is the host's to stop: it may be handed on to the assistant.)
        qrScan?.cancel()
        qrScan = null
        textCopy?.cancel()
        textCopy = null
        super.onDetachedFromWindow()
    }

    /** Make [rect], in the picture's pixels, the selection (see
     *  [CropOverlayView.applyCropRect]): a selection given back after a
     *  sideways run that got nowhere, for the user to adjust and try again. */
    fun applyCropRect(rect: Rect) {
        // (Where the picture lies is only known once this is laid out; a
        // screen just made waits for that — and for the layout to be over:
        // the buttons a selection brings out must not come out in the
        // middle of one, where they would be left unmeasured.)
        if (cropView.isLaidOut) cropView.applyCropRect(rect)
        else cropView.doOnLayout { cropView.post { cropView.applyCropRect(rect) } }
    }

    /** See [CropOverlayView.setDisplayFrame]. */
    fun setDisplayFrame(frame: RectF?) = cropView.setDisplayFrame(frame)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val onBack = onBackKey
        if (onBack != null && event.keyCode == KeyEvent.KEYCODE_BACK) {
            // Taken here, ahead of the children: a ViewGroup hands a key to
            // its focused child only, so once keyboard focus sits on one of
            // the buttons, a key listener on this view would never see Back.
            // (Escape arrives here as Back too, as the keyboard's fallback.)
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                // Picking an area, Back only leaves the picking.
                if (pickingArea) leavePicking() else onBack()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // --- QR actions ---------------------------------------------------------

    /** A touch anywhere outside the QR panel dismisses it; the touch still
     *  falls through so it can start a crop gesture (or open another code). */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (qrPanel.visibility == View.VISIBLE &&
            ev.actionMasked == MotionEvent.ACTION_DOWN
        ) {
            val hit = Rect()
            qrPanel.getHitRect(hit)
            if (!hit.contains(ev.x.toInt(), ev.y.toInt())) hideQrPanel()
        }
        return super.onInterceptTouchEvent(ev)
    }

    private fun showQrPanel(code: QrCode) {
        activeQr = code
        qrPanelText.text = code.text
        qrPanelError.visibility = View.GONE
        qrOpenButton.visibility = if (qrOpenUri(code.text) != null) View.VISIBLE else View.GONE
        qrPanel.visibility = View.VISIBLE
        updateMoreButton()
    }

    private fun hideQrPanel() {
        activeQr = null
        qrPanel.visibility = View.GONE
        updateMoreButton()
    }

    private fun copyQr(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("qr", text))
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
        callbacks?.onCancel()
    }

    private fun openQr(text: String) {
        val uri = qrOpenUri(text) ?: return
        // Intent filters match schemes case-sensitively, and QR codes often
        // spell theirs in capitals ("HTTPS://…" packs denser) — normalize, or
        // perfectly ordinary links find no app.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri).normalizeScheme())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // The payload is untrusted input and this runs in the process
            // hosting the assistant and accessibility services, so nothing it
            // provokes may escape: no handler (ActivityNotFoundException), a
            // handler we aren't allowed to start (SecurityException), a URI
            // the platform refuses to send (FileUriExposedException)…
            // Said in the panel (see qrPanelError), and Open withdrawn: it
            // would only fail again. Copy remains.
            qrPanelError.visibility = View.VISIBLE
            qrOpenButton.visibility = View.GONE
            return
        }
        callbacks?.onCancel()
    }

    /** The bar's labels (and icons) for a selection present or not. */
    private fun showSelectionActions(selected: Boolean) {
        hasSelection = selected
        updateMoreButton()
        // Copy text beside Copy, with a selection or without (the whole screen's
        // text then), OCR reading what the tree can't; unless switched off.
        val withText = eu.woshicado.circletoshare.Prefs.isCopyTextEnabled(context)
        if (withText) TextCopy.prepare(context)
        if (withText) setIconOnly(copyButton, R.string.action_copy_image)
        else setLabeled(copyButton, R.string.action_copy)
        copyTextButton.visibility = if (withText) View.VISIBLE else View.GONE
        copyTextButton.isEnabled = textCopy == null
    }

    private fun deliver(action: Delivery) {
        val rect = cropView.getCropRect()
        rememberCrop(rect)
        callbacks?.onDeliver(action, rect)
    }

    /** Remember an actual selection so it can be restored next time; a
     *  full-screen share (null rect) leaves the previous memory untouched,
     *  and so does a crop of a scrollshot (its picture is no screen's). */
    private fun rememberCrop(rect: Rect?) {
        if (rect != null && !scrollshot) {
            cropView.getBitmapSize()?.let { (w, h) ->
                eu.woshicado.circletoshare.Prefs.saveLastCrop(context, rect, w, h)
            }
        }
    }

    /** "Copy text": the selection's text to the clipboard, formatted — the
     *  whole screen's without one — and the tool closes; or, where there is
     *  no text, it says so and stays. */
    private fun copyText() {
        val bitmap = currentBitmap ?: return
        val selection = cropView.getCropRect()
        val rect = selection ?: Rect(0, 0, bitmap.width, bitmap.height)
        if (textCopy != null) return
        copyTextButton.isEnabled = false
        copyTextButton.alpha = 0.5f
        textCopy = TextCopy.start(context, bitmap, rect, screenText) { blocks ->
            textCopy = null
            copyTextButton.isEnabled = true
            copyTextButton.alpha = 1f
            if (blocks.isEmpty()) {
                sayNoText()
                return@start
            }
            rememberCrop(selection)
            TextCopy.toClipboard(context, blocks, eu.woshicado.circletoshare.Prefs.isCopyTextMarkdown(context))
            Toast.makeText(context, R.string.text_copied, Toast.LENGTH_SHORT).show()
            callbacks?.onCancel()
        }
    }

    /** That there was no text (in the selection, or on the whole screen),
     *  above the buttons for a while: the tool stays open. */
    private fun sayNoText() {
        removeCallbacks(noticeGone)
        noticeView.setText(R.string.text_none)
        noticeView.visibility = View.VISIBLE
        postDelayed(noticeGone, NO_TEXT_MS)
    }

    private fun dropText() {
        removeCallbacks(noticeGone)
        noticeView.visibility = View.GONE
        textCopy?.cancel()
        textCopy = null
        screenText?.cancel()
        screenText = null
        copyTextButton.isEnabled = true
        copyTextButton.alpha = 1f
    }

    /** [button] as an icon alone, [description] saying what it does to
     *  screen readers and on a long press. */
    private fun setIconOnly(button: TextView, description: Int) {
        button.text = ""
        button.contentDescription = context.getString(description)
        button.tooltipText = context.getString(description)
        (button as PillButton).setIconAlone(true)
    }

    private fun setLabeled(button: TextView, label: Int) {
        button.text = context.getString(label)
        button.contentDescription = null
        button.tooltipText = null
        (button as PillButton).setIconAlone(false)
    }

    /** [res] at the size the pills' icons share, ready for a compound
     *  drawable (tinted by the view it goes on). */
    private fun icon(res: Int) = context.getDrawable(res)!!.mutate().apply {
        setBounds(0, 0, dp(ICON_SIZE_DP), dp(ICON_SIZE_DP))
    }

    private fun pillButton(label: String, iconRes: Int, filled: Boolean, onClick: () -> Unit): TextView =
        PillButton(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            val content = if (filled) Color.BLACK else Color.WHITE
            setTextColor(content)
            setCompoundDrawablesRelative(icon(iconRes), null, null, null)
            compoundDrawableTintList = ColorStateList.valueOf(content)
            // Pressed feedback, clipped to the pill's own shape (no mask
            // layer: the ripple is masked by the content beneath it).
            background = RippleDrawable(
                ColorStateList.valueOf(
                    if (filled) Color.argb(48, 0, 0, 0) else Color.argb(64, 255, 255, 255)
                ),
                pill(
                    if (filled) Color.WHITE else Color.argb(230, 45, 45, 45), 26,
                    outlined = !filled
                ),
                null
            )
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setIconAlone(false)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    /** A rounded surface. [outlined] adds the hairline light edge every dark
     *  surface of the tool carries (the bubble has the same one): without it
     *  dark chrome dissolves into a dark screenshot. */
    private fun pill(color: Int, radiusDp: Int, outlined: Boolean = false) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (outlined) setStroke(dp(1), Color.argb(90, 255, 255, 255))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val BAR_BOTTOM_MARGIN_DP = 48
        const val HINT_TOP_DP = 64
        const val QR_PANEL_GAP_DP = 24
        const val MORE_GAP_DP = 12
        const val ICON_SIZE_DP = 18
        // How long "no text" shows.
        const val NO_TEXT_MS = 3000L
    }
}

// Between a pill's icon and its label.
private const val ICON_PADDING_DP = 8
// Each side of an icon-only pill: two of them take about the width of one
// with a short label.
private const val ICON_ONLY_PADDING_DP = 14

/**
 * A pill whose leading icon stays beside its label, the two centered as one.
 * A plain TextView pins a compound drawable to its edge and centers only the
 * text — fine while the pill wraps its content, but a stacked [ActionRow]
 * stretches it, which would strand the icon at the far edge.
 *
 * A plain TextView, like the crop screen's other views: it is built in
 * service contexts (assistant session, accessibility overlay) that carry no
 * AppCompat theme, and needs nothing AppCompat would add.
 */
@SuppressLint("AppCompatCustomView")
private class PillButton(context: Context) : TextView(context) {

    // What it says, also while it shows its icon alone (see compact).
    private var label: CharSequence = ""

    /**
     * Its icon alone, its label said to screen readers and on a long press
     * instead: where the row it is in has no room for the labels (see
     * ActionRow). For a button with a label; one without is left as it is.
     * A label given meanwhile is kept for when there is room again.
     */
    var compact = false
        set(value) {
            // Set from the row's measure pass: only a change may touch the
            // text and padding, each of which asks for a new layout.
            if (field == value) return
            field = value
            if (label.isEmpty()) return
            val shown = if (value) "" else label
            if (text.toString() != shown.toString()) super.setText(shown, BufferType.NORMAL)
            contentDescription = if (value) label else null
            tooltipText = if (value) label else null
            setIconAlone(value)
        }

    override fun setText(text: CharSequence?, type: BufferType?) {
        label = text ?: ""
        super.setText(if (compact && label.isNotEmpty()) "" else text, type)
    }

    /** Padded for the icon alone, or for a label beside it (less on the
     *  icon's side, as Material's buttons with an icon). */
    fun setIconAlone(iconAlone: Boolean) {
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        compoundDrawablePadding = if (iconAlone) 0 else dp(ICON_PADDING_DP)
        val start = if (iconAlone) dp(ICON_ONLY_PADDING_DP) else dp(18)
        val end = if (iconAlone) dp(ICON_ONLY_PADDING_DP) else dp(24)
        // (Padding as it is already is left alone: setting it lays out again.)
        if (paddingStart != start || paddingEnd != end || paddingTop != dp(13)) {
            setPaddingRelative(start, dp(13), end, dp(13))
        }
    }

    // Placed at each draw rather than layout: a new label in a pill of fixed
    // width (stacked) is only redrawn, not laid out again.
    override fun onDraw(canvas: Canvas) {
        placeIcon()
        super.onDraw(canvas)
    }

    private fun placeIcon() {
        val icon = compoundDrawablesRelative[0] ?: return
        // The room beside the label, split evenly: the label is centered in
        // the space after the icon, so the icon moves along by half of it.
        // (Bounds, not padding: moving the drawing needs no new layout.)
        val textArea = width - compoundPaddingLeft - compoundPaddingRight
        val textWidth = ceil(paint.measureText(text.toString())).toInt().coerceAtMost(textArea)
        val shift = (textArea - textWidth) / 2
        val offset = if (layoutDirection == LAYOUT_DIRECTION_RTL) -shift else shift
        val size = icon.bounds.width()
        if (icon.bounds.left != offset) icon.setBounds(offset, 0, offset + size, icon.bounds.height())
    }
}

/**
 * The parts of the picture that scroll sideways, outlined over it while a
 * row is being chosen (see CropScreen.pickArea): a tap on one makes it the
 * selection. Every other touch passes through to the crop view, where the
 * user draws and resizes a box as usual. Placed by the crop view's mapping
 * of the picture, so that the outlines lie where the picture is drawn.
 */
private class AreaPickerView(context: Context) : View(context) {

    private var areas: List<Rect> = emptyList()
    private var toView: ((Rect) -> RectF)? = null
    private var onTapped: ((Rect) -> Unit)? = null
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(6f)), 0f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(56, 255, 255, 255) }
    private val glyph = context.getDrawable(R.drawable.ic_swap_horiz)!!.mutate().apply {
        setTint(Color.WHITE)
    }
    private val glyphBack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 30, 30, 30) }

    // The outline a touch began on, if any: a tap on it picks it, a drag
    // from it is let go of (the crop view did not see its start).
    private var pressed: Rect? = null
    private var downX = 0f
    private var downY = 0f

    fun show(areas: List<Rect>, toView: (Rect) -> RectF, onTapped: (Rect) -> Unit) {
        this.areas = areas
        this.toView = toView
        this.onTapped = onTapped
        visibility = View.VISIBLE
        invalidate()
    }

    fun hide() {
        visibility = View.GONE
        pressed = null
    }

    override fun onDraw(canvas: Canvas) {
        val map = toView ?: return
        val radius = dp(10f)
        val glyphSize = dp(28f)
        for (area in areas) {
            val r = map(area)
            canvas.drawRoundRect(r, radius, radius, fill)
            canvas.drawRoundRect(r, radius, radius, stroke)
            // The glyph in the middle, on a disc of its own, where the
            // picture does not show through it.
            canvas.drawCircle(r.centerX(), r.centerY(), glyphSize, glyphBack)
            glyph.setBounds(
                (r.centerX() - glyphSize / 2).toInt(), (r.centerY() - glyphSize / 2).toInt(),
                (r.centerX() + glyphSize / 2).toInt(), (r.centerY() + glyphSize / 2).toInt()
            )
            glyph.draw(canvas)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val map = toView ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pressed = areas.firstOrNull { map(it).contains(event.x, event.y) }
                // Not on an outline: the crop view's touch, from the start.
                return pressed != null
            }
            MotionEvent.ACTION_UP -> {
                val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                val tapped = pressed?.takeIf {
                    kotlin.math.abs(event.x - downX) <= slop && kotlin.math.abs(event.y - downY) <= slop
                }
                pressed = null
                if (tapped != null) onTapped?.invoke(tapped)
            }
            MotionEvent.ACTION_CANCEL -> pressed = null
        }
        return pressed != null || event.actionMasked == MotionEvent.ACTION_UP
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

/**
 * A row of pill buttons, main action last. Where the buttons don't fit side
 * by side with their labels on one line — a narrow screen at a large font or
 * display size — they show their icons alone (see PillButton.compact); where
 * even those don't fit, it becomes a stack of equally wide buttons with their
 * labels, main action first, rather than squeeze the last one into a sliver
 * that breaks its label a letter at a time.
 */
private class ActionRow(context: Context) : LinearLayout(context) {

    // Every label a button may come to show, to judge the fit by the widest:
    // side by side or stacked must not flip when a label changes.
    private val labels = HashMap<TextView, List<String>>()
    // Buttons left out of judging the fit (see addAction).
    private val unmeasured = HashSet<TextView>()
    // Buttons as wide as the widest of them (see matchWidths).
    private val matched = HashSet<TextView>()
    private var stacked = false

    init {
        orientation = HORIZONTAL
    }

    /** [measured] false: [button] is left out of judging the fit — it only
     *  ever shows while another gives up its label for the room. */
    fun addAction(button: TextView, vararg labels: String, measured: Boolean = true) {
        this.labels[button] = labels.toList()
        if (!measured) unmeasured += button
        addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        applyItemLayout()
    }

    /**
     * [buttons], side by side, all as wide as the widest of those showing:
     * the outer two of a row, so that what sits between them sits in the
     * middle of the row — and of the screen — whatever their labels.
     * (Stacked, every button is as wide as the widest anyway.)
     */
    fun matchWidths(vararg buttons: TextView) {
        matched += buttons
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            // With their labels if they fit; else their icons alone, if those
            // do; else stacked, with their labels.
            val room = MeasureSpec.getSize(widthMeasureSpec)
            val labels = widthSideBySide() <= room
            val icons = labels || widthIconsAlone() <= room
            for (i in 0 until childCount) (getChildAt(i) as? PillButton)?.compact = !labels && icons
            setStacked(!icons)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (stacked) return
        // Widen the narrower of the matched to the widest, and the row by as
        // much. (Measured again rather than given a minimum width, which would
        // outlast a shorter label. The icon stays beside the label: see
        // PillButton.)
        val shown = matched.filter { it.visibility != View.GONE }
        if (shown.size < 2) return
        val widest = shown.maxOf { it.measuredWidth }
        var grown = 0
        for (button in shown) {
            if (button.measuredWidth == widest) continue
            grown += widest - button.measuredWidth
            button.measure(
                MeasureSpec.makeMeasureSpec(widest, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(button.measuredHeight, MeasureSpec.EXACTLY)
            )
        }
        if (grown > 0) setMeasuredDimension(measuredWidth + grown, measuredHeight)
    }

    /** The width the row needs with every label on a single line (the
     *  matched buttons as wide as the widest of them). */
    private fun widthSideBySide(): Int {
        var width = paddingLeft + paddingRight
        // (Compound padding: the padding plus the icon beside the label.)
        fun needed(button: TextView): Int {
            val widest = (labels[button].orEmpty() + button.text.toString())
                .maxOf { button.paint.measureText(it) }
            return button.compoundPaddingLeft + button.compoundPaddingRight + ceil(widest).toInt()
        }
        val matchedWidth = matched.filter { it.visibility != View.GONE && it !in unmeasured }
            .maxOfOrNull { needed(it) } ?: 0
        for (i in 0 until childCount) {
            val button = getChildAt(i) as TextView
            if (button in unmeasured) continue
            val own = if (button in matched && button.visibility != View.GONE) matchedWidth else needed(button)
            width += own + 2 * dp(ITEM_MARGIN_DP)
        }
        return width
    }

    /** The width the row needs with every button showing its icon alone. */
    private fun widthIconsAlone(): Int {
        var width = paddingLeft + paddingRight
        for (i in 0 until childCount) {
            val button = getChildAt(i) as TextView
            if (button.visibility == View.GONE) continue
            val icon = button.compoundDrawablesRelative[0]?.bounds?.width() ?: 0
            width += icon + 2 * dp(ICON_ONLY_PADDING_DP) + 2 * dp(ITEM_MARGIN_DP)
        }
        return width
    }

    private fun setStacked(stack: Boolean) {
        if (stacked == stack) return
        stacked = stack
        orientation = if (stack) VERTICAL else HORIZONTAL
        // Reverse the order: the main action is last in a row, first in a stack.
        for (i in childCount - 2 downTo 0) bringChildToFront(getChildAt(i))
        applyItemLayout()
    }

    private fun applyItemLayout() {
        for (i in 0 until childCount) {
            val params = getChildAt(i).layoutParams as LayoutParams
            // Stacked, each button is as wide as the widest of them.
            params.width = if (stacked) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
            params.marginStart = dp(ITEM_MARGIN_DP)
            params.marginEnd = dp(ITEM_MARGIN_DP)
            params.topMargin = if (stacked && i > 0) dp(STACK_GAP_DP) else 0
        }
        requestLayout()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val ITEM_MARGIN_DP = 6
        const val STACK_GAP_DP = 8
    }
}
