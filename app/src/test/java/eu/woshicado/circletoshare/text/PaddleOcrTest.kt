package eu.woshicado.circletoshare.text

import org.junit.Assert.assertEquals
import org.junit.Test

/** [PaddleOcr]'s choice of detector and scale, with a detector that only
 *  looks at what it is given. */
class PaddleOcrTest {

    /** Remembers the picture's extent in its input: white is 1 there, the rest 0. */
    private class Probe(val height: Int, val width: Int) : ModelRunner {
        var used = 0
        var w = 0
        var h = 0
        override fun run(input: FloatArray, shape: IntArray): Pair<FloatArray, IntArray> {
            used++
            w = (0 until width).count { input[it] == 1f }
            h = (0 until height).count { input[it * width] == 1f }
            return FloatArray(height * width) to intArrayOf(1, 1, height, width)
        }
    }

    private fun white(w: Int, h: Int) = Rgb(w, h, IntArray(w * h) { 0xFFFFFF })

    private fun ocr(vararg probes: Probe) =
        PaddleOcr(probes.map { Detector(it.height, it.width, it) }, { emptyMap() }, PaddleOcr.dictionary(emptyList()))

    @Test
    fun aSmallSelectionIsNotScaledUp() {
        // Scaled up 1.5× to fill the wide detector, its lines came apart into
        // words whose boxes overlapped: "device: f from", "provides i it".
        val square = Probe(960, 960)
        val wide = Probe(960, 1920)
        val tall = Probe(1920, 960)
        ocr(square, wide, tall).detect(white(1270, 354))
        assertEquals(0, square.used)
        assertEquals(1, wide.used)
        assertEquals(1270, wide.w)
        assertEquals(354, wide.h)
    }

    @Test
    fun aSelectionThatFitsGoesToTheSmallestDetector() {
        val square = Probe(960, 960)
        val wide = Probe(960, 1920)
        ocr(square, wide).detect(white(516, 174))
        assertEquals(1, square.used)
        assertEquals(0, wide.used)
        assertEquals(516, square.w)
        assertEquals(174, square.h)
    }
}
