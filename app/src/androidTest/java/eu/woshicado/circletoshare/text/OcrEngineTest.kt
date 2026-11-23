package eu.woshicado.circletoshare.text

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The OCR as shipped — the converted models on LiteRT, NHWC and all —
 * against PaddleOCR's own reading of the same crops (from the Copy text
 * fixtures): a settings screen, and a typewritten letter as a picture.
 */
@RunWith(AndroidJUnit4::class)
class OcrEngineTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun check(name: String, share: Double) {
        val assets = instrumentation.context.assets
        val bitmap = assets.open("copytext/$name.png").use { BitmapFactory.decodeStream(it) }
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val image = Rgb(bitmap.width, bitmap.height, IntArray(px.size) { px[it] and 0xFFFFFF })
        val expected = JSONObject(assets.open("copytext/$name.ocr.json").bufferedReader().readText())
            .getJSONArray("lines").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("text") } }

        val read = OcrEngine.get(instrumentation.targetContext).read(image).map { it.text }.toMutableList()
        val same = expected.count { read.remove(it) }
        assertTrue("$name: $same of ${expected.size} lines read as PaddleOCR does", same >= share * expected.size)
    }

    @Test
    fun aSettingsScreen_readsAsPaddleOcrDoes() = check("settings_display", 0.9)

    @Test
    fun aTypewrittenLetter_readsMostlyAsPaddleOcrDoes() = check("chrome_letter", 0.5)
}
