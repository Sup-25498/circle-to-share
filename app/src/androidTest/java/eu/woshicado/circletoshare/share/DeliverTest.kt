package eu.woshicado.circletoshare.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.woshicado.circletoshare.KeepPrefs
import eu.woshicado.circletoshare.Prefs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A delivery is written in the background and answered on the main
 *  thread; what can't be delivered is told before anything is written. */
@RunWith(AndroidJUnit4::class)
class DeliverTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)

    private var saved: Delivered? = null

    @get:Rule
    val kept = KeepPrefs()

    @Before
    fun setUp() {
        Prefs.setSaveDir(context, null)
        Prefs.setSavePerApp(context, false)
    }

    @After
    fun tearDown() {
        saved?.let { runCatching { context.contentResolver.delete(it.uri, null, null) } }
    }

    @Test
    fun bounds_areTheWholePicture_withoutASelection() {
        assertEquals(Rect(0, 0, 64, 48), ShareHelper.bounds(bitmap, null))
    }

    @Test
    fun bounds_areClampedToThePicture() {
        assertEquals(Rect(10, 10, 64, 48), ShareHelper.bounds(bitmap, Rect(10, 10, 200, 200)))
    }

    @Test
    fun bounds_ofNothingUsable_areNone() {
        assertNull(ShareHelper.bounds(bitmap, Rect(100, 100, 200, 200))) // outside
        assertNull(ShareHelper.bounds(bitmap, Rect(0, 0, 3, 40))) // too narrow
        assertNull(ShareHelper.bounds(bitmap, Rect(0, 0, 40, 2))) // too flat
    }

    private fun deliver(action: Delivery): Pair<Result<Delivered>, Boolean> {
        val latch = CountDownLatch(1)
        var answer: Result<Delivered>? = null
        var onMain = false
        instrumentation.runOnMainSync {
            ShareHelper.deliver(context, bitmap, Rect(0, 0, 32, 32), action, null) {
                answer = it
                onMain = Looper.myLooper() == Looper.getMainLooper()
                latch.countDown()
            }
        }
        assertTrue("answered", latch.await(10, TimeUnit.SECONDS))
        return answer!! to onMain
    }

    @Test
    fun aSave_isAnsweredOnTheMainThread_withWhereItWent() {
        val (result, onMain) = deliver(Delivery.SAVE)
        assertTrue(onMain)
        saved = result.getOrThrow()
        assertEquals("Screenshots", saved!!.folder)
    }

    @Test
    fun aShare_isWrittenToTheCache_forTheCallerToHandOver() {
        val (result, onMain) = deliver(Delivery.SHARE)
        assertTrue(onMain)
        val delivered = result.getOrThrow()
        assertNull(delivered.folder)
        assertEquals("eu.woshicado.circletoshare.fileprovider", delivered.uri.authority)
        // The image is all there: it decodes, at the crop's size.
        val decoded = context.contentResolver.openInputStream(delivered.uri)!!.use {
            android.graphics.BitmapFactory.decodeStream(it)
        }
        assertNotNull(decoded)
        assertEquals(32, decoded.width)
        assertEquals(32, decoded.height)
    }
}
