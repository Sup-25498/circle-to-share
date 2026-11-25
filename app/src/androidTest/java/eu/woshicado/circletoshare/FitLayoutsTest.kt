package eu.woshicado.circletoshare

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.slider.Slider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Rows that give way where there is no room: buttons that wrap, sliders
 *  that go under their labels. */
@RunWith(AndroidJUnit4::class)
class FitLayoutsTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_CircleToShare)
    private val density = context.resources.displayMetrics.density

    private fun dp(value: Int) = (value * density).toInt()

    private fun layOut(view: View, width: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, width, view.measuredHeight)
    }

    @Test
    fun buttons_sitSideBySide_whereTheyFit_andWrapWhereTheyDont() = instrumentation.runOnMainSync {
        val row = WrapRow(context)
        val first = Button(context).apply { text = "Privacy policy" }
        val second = Button(context).apply { text = "Imprint" }
        row.addView(first)
        row.addView(second)

        layOut(row, dp(600))
        assertEquals("one line", first.top, second.top)
        assertTrue("after the first", second.left >= first.right)

        val wanted = listOf(first.width, second.width)

        // Room for either, not for both.
        layOut(row, first.width + second.width / 2)
        assertTrue("the second on a line of its own", second.top >= first.bottom)
        assertEquals("at the start", first.left, second.left)
        // Neither squeezed: each as wide as it wants.
        assertEquals(wanted, listOf(first.width, second.width))
    }

    @Test
    fun sliders_stayBesideTheirLabels_whereTheyAreLongEnough_andGoUnderThemWhereNot() =
        instrumentation.runOnMainSync {
            val table = SliderTable(context)
            fun row(label: String) = LinearLayout(context).apply {
                addView(TextView(context).apply { text = label })
                addView(Slider(context))
            }
            val short = row("Step")
            val long = row("Sideways per step")
            table.addView(short)
            table.addView(TextView(context).apply { text = "A note under the first" })
            table.addView(long)

            layOut(table, dp(400))
            for (r in listOf(short, long)) {
                assertEquals(LinearLayout.HORIZONTAL, r.orientation)
                assertTrue("long enough", r.getChildAt(1).width >= dp(160))
            }
            // The longest label sets where both sliders start: one length.
            assertEquals(short.getChildAt(1).left, long.getChildAt(1).left)
            assertEquals(short.getChildAt(1).width, long.getChildAt(1).width)

            // Room for the label, not for a slider beside it.
            val label = long.getChildAt(0).let {
                it.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                it.measuredWidth
            }
            layOut(table, label + dp(100))
            for (r in listOf(short, long)) {
                assertEquals(LinearLayout.VERTICAL, r.orientation)
                assertEquals("the whole width", r.width, r.getChildAt(1).width)
                assertTrue("under its label", r.getChildAt(1).top >= r.getChildAt(0).bottom)
            }

            // And back, with the room back.
            layOut(table, dp(400))
            assertEquals(LinearLayout.HORIZONTAL, long.orientation)
        }
}
