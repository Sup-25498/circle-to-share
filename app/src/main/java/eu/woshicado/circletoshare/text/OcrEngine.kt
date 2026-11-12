package eu.woshicado.circletoshare.text

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * The OCR models (assets/ocr, see its README) on LiteRT, loaded when a crop
 * tool opens with Copy text on (see [TextCopy.prepare]) and let go of when
 * Copy text has not been used for a while ([release]): the service's
 * process lives on, and their memory with it. Used from TextCopy's worker,
 * one copy at a time.
 */
object OcrEngine {

    @Volatile
    private var ocr: PaddleOcr? = null

    // The recognizers of each worker reading lines side by side.
    private val recognizers = java.util.concurrent.CopyOnWriteArrayList<Map<Int, ModelRunner>>()

    fun get(context: Context): PaddleOcr = ocr ?: synchronized(this) {
        ocr ?: load(context.applicationContext).also { ocr = it }
    }


    fun release() = synchronized(this) {
        ocr?.close()
        ocr = null
        recognizers.forEach { set -> set.values.forEach { it.close() } }
        recognizers.clear()
    }

    private fun load(context: Context): PaddleOcr {
        fun model(name: String): ByteBuffer = context.assets.openFd("ocr/$name").use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use {
                it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
        val keys = context.assets.open("ocr/latin_keys.txt").bufferedReader().readLines()
        return PaddleOcr(
            det = listOf(
                Detector(960, 960, TfliteRunner(model("det960x960.tflite"))),
                Detector(1920, 960, TfliteRunner(model("det1920x960.tflite"))),
                Detector(960, 1920, TfliteRunner(model("det960x1920.tflite"))),
            ),
            // The first worker's recognizers now; the others' when a page first
            // needs many lines read (a picture of text), as most don't.
            rec = { w ->
                recognizers.getOrNull(w) ?: synchronized(recognizers) {
                    while (recognizers.size <= w) {
                        val threads = if (recognizers.isEmpty()) 4 else 2
                        recognizers += mapOf(
                            640 to TfliteRunner(model("rec640.tflite"), threads, keep = true),
                            1280 to TfliteRunner(model("rec1280.tflite"), threads, keep = true),
                        )
                    }
                    recognizers[w]
                }
            },
            workers = 3,
            dict = PaddleOcr.dictionary(keys),
        )
    }

    /** One model on a LiteRT interpreter. The models take NHWC where
     *  [PaddleOcr] hands NCHW (as PaddleOCR's own): transposed here. Each
     *  runner is used by one thread. [keep]: its buffers are kept for the
     *  next run — a recognizer's, run line after line; not a detector's, run
     *  once a copy on a picture tens of megabytes big. */
    private class TfliteRunner(model: ByteBuffer, threads: Int = 4, private val keep: Boolean = false) : ModelRunner {
        private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(threads))
        private var inputShape: IntArray? = null
        private var input: ByteBuffer? = null
        private var output: ByteBuffer? = null

        override fun run(input: FloatArray, shape: IntArray): Pair<FloatArray, IntArray> {
            val (n, c, h, w) = shape.toList()
            val nhwc = intArrayOf(n, h, w, c)
            if (!nhwc.contentEquals(inputShape)) {
                interpreter.resizeInput(0, nhwc)
                interpreter.allocateTensors()
                inputShape = nhwc
            }
            val bytes = 4 * input.size
            val inBuf = this.input?.takeIf { it.capacity() >= bytes }
                ?: ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).also { this.input = it }
            // (The models are of a fixed size: their output's is known now.)
            val outBytes = interpreter.getOutputTensor(0).numBytes()
            val outBuf = output?.takeIf { it.capacity() >= outBytes }
                ?: ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder()).also { output = it }
            inBuf.clear()
            val floats = inBuf.asFloatBuffer()
            val plane = h * w
            for (b in 0 until n) for (i in 0 until plane) for (ch in 0 until c) {
                floats.put(input[b * c * plane + ch * plane + i])
            }
            inBuf.limit(bytes)
            outBuf.clear()
            interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inBuf), mapOf<Int, Any>(0 to outBuf))
            val outShape = interpreter.getOutputTensor(0).shape()
            val values = FloatArray(outShape.fold(1) { a, b -> a * b })
            outBuf.rewind()
            outBuf.asFloatBuffer().get(values)
            if (!keep) {
                this.input = null
                output = null
            }
            // Detection's map comes as (1, H, W, 1): the same numbers as (1, 1, H, W).
            val nchw = if (outShape.size == 4) intArrayOf(outShape[0], outShape[3], outShape[1], outShape[2]) else outShape
            return values to nchw
        }

        override fun close() = interpreter.close()
    }
}
