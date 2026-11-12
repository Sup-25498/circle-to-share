package eu.woshicado.circletoshare.text

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.FloatBuffer
import java.util.zip.GZIPInputStream

/**
 * The Kotlin OCR pipeline ([PaddleOcr]) against PaddleOCR's own (RapidOCR's
 * lines in the fixtures), with the original ONNX models on desktop ONNX
 * Runtime. Needs the models and color crops in the directory named by the
 * system property / env COPYTEXT_MODELS; skipped without them.
 */
class PaddleOcrPipelineTest {

    private val dir = (System.getProperty("COPYTEXT_MODELS") ?: System.getenv("COPYTEXT_MODELS"))?.let(::File)

    private class OnnxRunner(path: File) : ModelRunner {
        private val env = OrtEnvironment.getEnvironment()
        private val session = env.createSession(path.path, OrtSession.SessionOptions())
        override fun run(input: FloatArray, shape: IntArray): Pair<FloatArray, IntArray> {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape.map { it.toLong() }.toLongArray()).use { t ->
                session.run(mapOf(session.inputNames.first() to t)).use { r ->
                    val out = r[0] as OnnxTensor
                    val buf = out.floatBuffer
                    val arr = FloatArray(buf.remaining()).also { buf.get(it) }
                    return arr to out.info.shape.map { it.toInt() }.toIntArray()
                }
            }
        }
    }

    private fun rgb(file: File): Rgb {
        val bytes = GZIPInputStream(file.inputStream()).use { it.readBytes() }
        var pos = 0
        val fields = ArrayList<String>()
        while (fields.size < 4) {
            var end = pos
            while (bytes[end] != '\n'.code.toByte()) end++
            fields += String(bytes, pos, end - pos).split(' ')
            pos = end + 1
        }
        val w = fields[1].toInt()
        val h = fields[2].toInt()
        return Rgb(w, h, IntArray(w * h) {
            val o = pos + 3 * it
            ((bytes[o].toInt() and 0xFF) shl 16) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o + 2].toInt() and 0xFF)
        })
    }

    @Test
    fun readsLikePaddleOcr() = check(fixedSizes = false)

    /** As the app reads: detection at the fixed sizes its models have. */
    @Test
    fun readsLikePaddleOcr_atTheAppsDetectionSizes() = check(fixedSizes = true)

    private fun check(fixedSizes: Boolean) {
        assumeTrue(dir?.resolve("latin_keys.txt")?.exists() == true)
        val detector = OnnxRunner(dir!!.resolve("ch_PP-OCRv5_det_mobile.onnx"))
        val ocr = PaddleOcr(
            if (fixedSizes) listOf(Detector(960, 960, detector), Detector(1920, 960, detector))
            else listOf(Detector(0, 0, detector)),
            OnnxRunner(dir.resolve("latin_PP-OCRv5_rec_mobile.onnx")).let { r -> { _: Int -> mapOf(640 to r, 1280 to r) } },
            PaddleOcr.dictionary(dir.resolve("latin_keys.txt").readLines()),
        )
        var total = 0
        var same = 0
        for (name in Fixtures.NAMES) {
            val expected = Fixtures.ocr(name).filter { it.score >= 0.5f }
            val t0 = System.nanoTime()
            val actual = ocr.read(rgb(dir.resolve("$name.ppm.gz")))
            val ms = (System.nanoTime() - t0) / 1_000_000
            val texts = actual.map { it.text }.toMutableList()
            val hit = expected.count { e -> texts.remove(e.text) }
            println("$name: $hit/${expected.size} lines the same, ${actual.size} read, $ms ms")
            expected.filter { e -> actual.none { it.text == e.text } }.take(3).forEach { e ->
                val near = actual.minByOrNull { kotlin.math.abs(it.top - e.top) + kotlin.math.abs(it.left - e.left) }
                println("   want: ${e.text}\n   got:  ${near?.text}")
            }
            total += expected.size
            same += hit
        }
        println("overall${if (fixedSizes) " (fixed sizes)" else ""}: $same/$total")
        assert(same >= 0.8 * total) { "$same of $total lines read the same" }
    }
}
