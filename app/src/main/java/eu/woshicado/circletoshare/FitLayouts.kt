package eu.woshicado.circletoshare

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.max

/**
 * Buttons side by side, as many as fit on a line and the rest on the next:
 * where a narrow screen, a large font or display size or a long translation
 * leaves no room for them all, rather than squeezing the last into a sliver
 * that breaks its label a letter at a time.
 */
class WrapRow(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {

    private val across = dp(GAP_ACROSS_DP)
    private val down = dp(GAP_DOWN_DP)

    // Where each child goes, by its index, from the start and the top:
    // worked out as it is measured, so that it is laid out on the lines it
    // was measured on.
    private var xs = IntArray(0)
    private var ys = IntArray(0)

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        if (xs.size < childCount) {
            xs = IntArray(childCount)
            ys = IntArray(childCount)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val room = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
        else MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var line = 0
        var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            if (x > 0 && x + across + child.measuredWidth > room) {
                y += line + down
                x = 0
                line = 0
            }
            if (x > 0) x += across
            xs[i] = x
            ys[i] = y
            x += child.measuredWidth
            line = max(line, child.measuredHeight)
            widest = max(widest, x)
        }
        setMeasuredDimension(
            resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(y + line + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val left = if (rtl) r - l - paddingRight - xs[i] - child.measuredWidth else paddingLeft + xs[i]
            val top = paddingTop + ys[i]
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    private companion object {
        const val GAP_ACROSS_DP = 12
        const val GAP_DOWN_DP = 8
    }
}

/**
 * Sliders with their labels, as a table: each label beside its slider,
 * the longest label setting where all the sliders start, so they are one
 * length. Where that leaves a slider too short to set (a narrow screen, a
 * large font or display size), each label goes above its slider instead,
 * and the slider takes the whole width.
 *
 * Its rows are the LinearLayout children, each a label and its slider; any
 * other child (a note under a row) is left as it is.
 */
class SliderTable(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    init {
        orientation = VERTICAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val rows = (0 until childCount).map(::getChildAt).filterIsInstance<LinearLayout>()
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            // The longest label, on one line.
            val label = rows.maxOfOrNull { row ->
                row.getChildAt(0).let { it.measure(unspecified, unspecified); it.measuredWidth }
            } ?: 0
            val room = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val beside = room - label >= dp(MIN_SLIDER_DP)
            for (row in rows) arrange(row, beside, label)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun arrange(row: LinearLayout, beside: Boolean, labelWidth: Int) {
        row.orientation = if (beside) HORIZONTAL else VERTICAL
        (row.getChildAt(0).layoutParams as LayoutParams).apply {
            width = if (beside) labelWidth else LayoutParams.MATCH_PARENT
            gravity = Gravity.CENTER_VERTICAL
        }
        (row.getChildAt(1).layoutParams as LayoutParams).apply {
            width = if (beside) 0 else LayoutParams.MATCH_PARENT
            weight = if (beside) 1f else 0f
        }
    }

    private companion object {
        // Short of this, a slider's steps are too close together to pick.
        const val MIN_SLIDER_DP = 160
    }
}
