package eu.woshicado.circletoshare.text

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Runs one model: [input] in NCHW, its [shape]; returns the output and its
 * shape. Behind it, whichever runtime the app ships (see [TfliteRunner]).
 */
interface ModelRunner {
    fun run(input: FloatArray, shape: IntArray): Pair<FloatArray, IntArray>

    fun close() {}
}

/** A text detector taking pictures of [height]×[width] (0×0: any size). */
class Detector(val height: Int, val width: Int, val runner: ModelRunner)

/** A picture in color, 0xRRGGBB per pixel, row by row. */
class Rgb(val width: Int, val height: Int, val px: IntArray)

/**
 * PaddleOCR (PP-OCRv5 mobile, Latin recognition) — the two steps of it:
 * [detect] finds where lines of text are (the DB text detector), and
 * recognition reads each (CTC decoding over [dict]). Latin: English, German
 * (ä, ö, ü, ß) and the other languages written in Latin letters; not CJK. The same pre- and
 * post-processing as PaddleOCR's own pipeline (via RapidOCR), minus what a
 * screenshot doesn't need: text on screen is level, so boxes are axis
 * aligned and no angle classifier runs.
 */
/**
 * What OCR found in a picture: the boxes (left, top, right, bottom, in the
 * picture's pixels) of its lines of text, each read only when asked for —
 * reading is most of the time OCR takes, and where the accessibility tree
 * has the text, few lines need it ([TextFusion]).
 */
interface OcrPage {
    val boxes: List<FloatArray>

    /** The lines at [indices], read (once each). */
    fun lines(indices: Collection<Int>): List<OcrLine>

    fun line(i: Int): OcrLine = lines(listOf(i))[0]

    /** Every line with text. */
    fun all(): List<OcrLine> = lines(boxes.indices.toList()).filter { it.text.isNotBlank() }
}

class PaddleOcr(
    /** Detectors: of a fixed input size each, or one of any size (0×0). */
    private val det: List<Detector>,
    /** Recognizers by the width (in pixels at height 48) of the lines they
     *  take. Worker w reading lines side by side uses `rec(w)` (made on first use). */
    private val rec: (Int) -> Map<Int, ModelRunner>,
    private val dict: List<String>,
    /** How many workers read lines side by side when there are many. */
    private val workers: Int = 1,
) {
    private val pool = if (workers > 1) Executors.newFixedThreadPool(workers - 1) { Thread(it, "ocr-rec") } else null

    /** Time spent on the last picture, for the log: detecting, and reading how many lines in how long. */
    @Volatile
    var detMs = 0L
        private set
    private val recNanos = AtomicLong()
    private val recCount = AtomicInteger()
    val recMs get() = recNanos.get() / 1_000_000
    val recLines get() = recCount.get()

    /** Let go of the models' memory; this one is not to be used after. */
    fun close() {
        pool?.shutdown()
        for (d in det) d.runner.close()
    }

    /** Every line of text in [image]. */
    fun read(image: Rgb): List<OcrLine> = detect(image).all()

    /**
     * Where the lines of text in [image] are, to read on demand. A long
     * picture (a scrollshot) is looked at in overlapping tiles, each line
     * kept from the tile it sits best in, so its text isn't shrunk to fit
     * the limit below.
     */
    fun detect(image: Rgb, cancelled: () -> Boolean = { false }): OcrPage {
        val started = System.nanoTime()
        recNanos.set(0)
        recCount.set(0)
        val parts = ArrayList<Part>()
        val tall = image.height > MAX_SIDE && image.height > 2 * image.width
        val wide = image.width > MAX_SIDE && image.width > 2 * image.height
        if (!tall && !wide) {
            parts += part(image, 0, 0)
        } else {
            // Tiles along the long side (a scrollshot down, or sideways).
            val length = if (tall) image.height else image.width
            val tile = max(MAX_SIDE, if (tall) image.width else image.height)
            var from = 0
            while (true) {
                val to = min(length, from + tile)
                val first = from == 0
                val last = to == length
                val p = if (tall) part(crop(image, 0, from, image.width, to), 0, from)
                else part(crop(image, from, 0, to, image.height), from, 0)
                // Lines in the overlap belong to the tile whose middle they are nearer.
                parts += p.keeping { b ->
                    val mid = (if (tall) (b[1] + b[3]) else (b[0] + b[2])) / 2 - from
                    (first || mid >= TILE_OVERLAP / 2) && (last || mid < tile - TILE_OVERLAP / 2)
                }
                if (last) break
                from = to - TILE_OVERLAP
            }
        }
        detMs = (System.nanoTime() - started) / 1_000_000
        return Page(parts, cancelled)
    }

    /**
     * One picture (or tile [dx], [dy] along a long one) as PaddleOCR (RapidOCR)
     * looks at it: scaled down if longer than [MAX_SIDE], padded above and
     * below if very flat (a single line), so the detector has room.
     */
    private fun part(image: Rgb, dx: Int, dy: Int): Part {
        val longest = max(image.width, image.height)
        val scaled = if (longest > MAX_SIDE) {
            val r = MAX_SIDE.toFloat() / longest
            resize(image, (image.width * r).toInt(), (image.height * r).toInt())
        } else image
        val flat = scaled.height <= MIN_HEIGHT || scaled.width.toFloat() / scaled.height > FLAT_RATIO
        val pad = if (flat) {
            val newH = max((scaled.width / FLAT_RATIO).toInt(), MIN_HEIGHT) * 2
            kotlin.math.abs(newH - scaled.height) / 2
        } else 0
        val padded = if (pad > 0) padVertically(scaled, pad) else scaled
        return Part(
            padded, boxesIn(padded), image.width.toFloat() / scaled.width, image.height.toFloat() / scaled.height, pad, dx, dy
        )
    }

    /** A picture the detector looked at, its boxes in its own pixels, and how
     *  to bring them to the original's. */
    private class Part(
        val image: Rgb, val boxes: List<FloatArray>, val sx: Float, val sy: Float, val pad: Int, val dx: Int, val dy: Int,
    ) {
        fun toOriginal(b: FloatArray) =
            floatArrayOf(b[0] * sx + dx, (b[1] - pad) * sy + dy, b[2] * sx + dx, (b[3] - pad) * sy + dy)

        fun keeping(keep: (FloatArray) -> Boolean) = Part(image, boxes.filter { keep(toOriginal(it)) }, sx, sy, pad, dx, dy)
    }

    private inner class Page(parts: List<Part>, private val cancelled: () -> Boolean) : OcrPage {
        private val entries = parts.flatMap { p -> p.boxes.map { p to it } }
        override val boxes: List<FloatArray> = entries.map { (p, b) -> p.toOriginal(b) }
        private val read = arrayOfNulls<OcrLine>(entries.size)

        override fun lines(indices: Collection<Int>): List<OcrLine> {
            val missing = indices.distinct().filter { read[it] == null }
            if (missing.isNotEmpty()) {
                val started = System.nanoTime()
                // Many lines (a picture of text) are read side by side: a long line
                // takes the recognizer ~65 ms on one core of a Pixel 10, ~40 ms a
                // line with three at it. A few (alongside the tree) one by one.
                val n = if (pool == null || missing.size < 8) 1 else workers
                val tasks = (1 until n).map { w ->
                    pool!!.submit { for (k in w until missing.size step n) readOne(missing[k], rec(w)) }
                }
                for (k in 0 until missing.size step n) readOne(missing[k], rec(0))
                // (A worker's cancellation, or failure, as if it were this
                // thread's: Future.get wraps whatever a task threw.)
                tasks.forEach { task ->
                    try {
                        task.get()
                    } catch (e: java.util.concurrent.ExecutionException) {
                        throw e.cause ?: e
                    }
                }
                recNanos.addAndGet(System.nanoTime() - started)
                recCount.addAndGet(missing.size)
            }
            return indices.map { read[it]!! }
        }

        private fun readOne(i: Int, runners: Map<Int, ModelRunner>) {
            // A copy no one waits for any more (the tool closed, another capture)
            // stops between lines.
            if (cancelled()) throw java.util.concurrent.CancellationException()
            val (part, b) = entries[i]
            val (text, score) = recognize(part.image, b, runners)
            val o = boxes[i]
            read[i] = OcrLine(o[0], o[1], o[2], o[3], text, score)
        }
    }

    private fun crop(img: Rgb, x0: Int, y0: Int, x1: Int, y1: Int): Rgb {
        val w = x1 - x0
        val px = IntArray(w * (y1 - y0))
        for (y in y0 until y1) System.arraycopy(img.px, y * img.width + x0, px, (y - y0) * w, w)
        return Rgb(w, y1 - y0, px)
    }

    /** [img] with [pad] black rows above and below. */
    private fun padVertically(img: Rgb, pad: Int): Rgb {
        val px = IntArray(img.width * (img.height + 2 * pad))
        System.arraycopy(img.px, 0, px, pad * img.width, img.px.size)
        return Rgb(img.width, img.height + 2 * pad, px)
    }

    /** Bilinear resize, as OpenCV's default. */
    private fun resize(img: Rgb, w: Int, h: Int): Rgb {
        val px = IntArray(w * h)
        val sx = img.width.toFloat() / w
        val sy = img.height.toFloat() / h
        for (y in 0 until h) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (img.height - 1).toFloat())
            val iy = fy.toInt()
            val iy1 = min(iy + 1, img.height - 1)
            val dy = fy - iy
            for (x in 0 until w) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (img.width - 1).toFloat())
                val ix = fx.toInt()
                val ix1 = min(ix + 1, img.width - 1)
                val dx = fx - ix
                var v = 0
                for (shift in intArrayOf(0, 8, 16)) {
                    val p00 = (img.px[iy * img.width + ix] shr shift) and 0xFF
                    val p01 = (img.px[iy * img.width + ix1] shr shift) and 0xFF
                    val p10 = (img.px[iy1 * img.width + ix] shr shift) and 0xFF
                    val p11 = (img.px[iy1 * img.width + ix1] shr shift) and 0xFF
                    val top = p00 + (p01 - p00) * dx
                    val c = (top + (p10 + (p11 - p10) * dx - top) * dy).roundToInt().coerceIn(0, 255)
                    v = v or (c shl shift)
                }
                px[y * w + x] = v
            }
        }
        return Rgb(w, h, px)
    }

    // ------------------------------------------------------------ detection

    /** Boxes (left, top, right, bottom) of the lines of text in [image]. */
    private fun boxesIn(image: Rgb): List<FloatArray> {
        val (h, w) = image.height to image.width
        val any = det.firstOrNull { it.width == 0 }
        if (any != null) {
            // Small pictures are scaled up until their short side is 736, as PaddleOCR does.
            val ratio = if (min(h, w) < LIMIT_SIDE) LIMIT_SIDE.toFloat() / min(h, w) else 1f
            val rh = max(32, (((h * ratio).toInt() / 32f).roundToInt() * 32))
            val rw = max(32, (((w * ratio).toInt() / 32f).roundToInt() * 32))
            val (out, shape) = any.runner.run(normalized(image, 0, 0, w, h, rw, rh, rw, rh), intArrayOf(1, 3, rh, rw))
            return boxesFrom(out, shape[3], shape[3], shape[2], w, h)
        }
        // A detector of fixed size: the picture scaled to fit the one that keeps
        // its text largest (the smaller one on a tie), the rest left blank. Never
        // scaled up: shown a small selection larger, the detector splits its
        // lines into words, and boxes grown into their neighbours read the
        // letters where two meet twice ("device: f from").
        fun scale(d: Detector) = min(1f, min(d.width.toFloat() / w, d.height.toFloat() / h))
        val model = det.maxWith(compareBy<Detector> { scale(it) }.thenByDescending { it.width * it.height })
        val scale = scale(model)
        val rw = max(1, (w * scale).roundToInt()).coerceAtMost(model.width)
        val rh = max(1, (h * scale).roundToInt()).coerceAtMost(model.height)
        val (out, shape) = model.runner.run(
            normalized(image, 0, 0, w, h, rw, rh, model.width, model.height), intArrayOf(1, 3, model.height, model.width)
        )
        return boxesFrom(out, shape[3], rw, rh, w, h)
    }

    /**
     * The picture's region (x, y, w, h) resized to [rw]×[rh] (bilinear),
     * normalized to -1…1, channels in PaddleOCR's B, G, R order, laid out
     * as CHW planes of [rows] rows [stride] wide (the rest zero).
     */
    private fun normalized(img: Rgb, x0: Int, y0: Int, w: Int, h: Int, rw: Int, rh: Int, stride: Int, rows: Int = rh): FloatArray {
        val plane = rows * stride
        val out = FloatArray(3 * plane)
        val sx = w.toFloat() / rw
        val sy = h.toFloat() / rh
        for (y in 0 until rh) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val iy = fy.toInt()
            val iy1 = min(iy + 1, h - 1)
            val dy = fy - iy
            for (x in 0 until rw) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val ix = fx.toInt()
                val ix1 = min(ix + 1, w - 1)
                val dx = fx - ix
                val p00 = img.px[(y0 + iy) * img.width + x0 + ix]
                val p01 = img.px[(y0 + iy) * img.width + x0 + ix1]
                val p10 = img.px[(y0 + iy1) * img.width + x0 + ix]
                val p11 = img.px[(y0 + iy1) * img.width + x0 + ix1]
                val o = y * stride + x
                // B, G, R
                for (c in 0 until 3) {
                    val shift = c * 8 // B is the low byte of 0xRRGGBB
                    val v00 = (p00 shr shift) and 0xFF
                    val v01 = (p01 shr shift) and 0xFF
                    val v10 = (p10 shr shift) and 0xFF
                    val v11 = (p11 shr shift) and 0xFF
                    val top = v00 + (v01 - v00) * dx
                    val bottom = v10 + (v11 - v10) * dx
                    val v = top + (bottom - top) * dy
                    out[c * plane + o] = (v / 255f - 0.5f) / 0.5f
                }
            }
        }
        return out
    }

    /** The DB post-processing: threshold, dilate, one box per connected region
     *  scoring well enough, grown back out ("unclipped") to the whole line.
     *  [prob] is [stride] wide; its top-left [pw]×[ph] is the picture's. */
    private fun boxesFrom(prob: FloatArray, stride: Int, pw: Int, ph: Int, srcW: Int, srcH: Int): List<FloatArray> {
        val bin = BooleanArray(pw * ph) { prob[(it / pw) * stride + it % pw] > THRESH }
        // 2×2 dilation, anchored like OpenCV's: a pixel is set if it or its
        // left, upper or upper-left neighbour is.
        val dil = BooleanArray(pw * ph)
        for (y in 0 until ph) for (x in 0 until pw) {
            val i = y * pw + x
            dil[i] = bin[i] || (x > 0 && bin[i - 1]) || (y > 0 && bin[i - pw]) || (x > 0 && y > 0 && bin[i - pw - 1])
        }
        val seen = BooleanArray(pw * ph)
        val stack = IntArray(pw * ph)
        val boxes = ArrayList<FloatArray>()
        for (start in 0 until pw * ph) {
            if (!dil[start] || seen[start]) continue
            if (boxes.size >= MAX_CANDIDATES) break
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % pw
                val y = i / pw
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= pw || ny >= ph) continue
                    val j = ny * pw + nx
                    if (dil[j] && !seen[j]) {
                        seen[j] = true
                        stack[sp++] = j
                    }
                }
            }
            // The region's rectangle, as OpenCV's minAreaRect of its outline gives
            // for level text: from the first to the last pixel.
            var bw = (maxX - minX).toFloat()
            var bh = (maxY - minY).toFloat()
            if (min(bw, bh) < MIN_SIZE) continue
            var score = 0.0
            for (y in minY..maxY) for (x in minX..maxX) score += prob[y * stride + x]
            score /= (maxX - minX + 1) * (maxY - minY + 1)
            if (score < BOX_THRESH) continue
            // Unclip: grow by area × ratio / perimeter on every side.
            val d = bw * bh * UNCLIP_RATIO / (2 * (bw + bh))
            val l = minX - d
            val t = minY - d
            bw += 2 * d
            bh += 2 * d
            if (min(bw, bh) < MIN_SIZE + 2) continue
            val left = (l / pw * srcW).roundToInt().coerceIn(0, srcW).toFloat()
            val right = ((l + bw) / pw * srcW).roundToInt().coerceIn(0, srcW).toFloat()
            val top = (t / ph * srcH).roundToInt().coerceIn(0, srcH).toFloat()
            val bottom = ((t + bh) / ph * srcH).roundToInt().coerceIn(0, srcH).toFloat()
            if (right - left <= 3 || bottom - top <= 3) continue
            boxes += floatArrayOf(left, top, right, bottom)
        }
        // Reading order: rows (tops within 10 px), then left to right.
        val byTop = boxes.sortedBy { it[1] }
        val line = IntArray(byTop.size)
        for (i in 1 until byTop.size) line[i] = line[i - 1] + if (byTop[i][1] - byTop[i - 1][1] >= 10) 1 else 0
        return byTop.indices.sortedWith(compareBy({ line[it] }, { byTop[it][0] })).map { byTop[it] }
    }

    // ------------------------------------------------------------ recognition

    /** One line read by the narrowest recognizer it fits — a short label
     *  needn't pay for the room a full line takes. A line longer than the
     *  widest is squeezed into it, as PaddleOCR does. */
    private fun recognize(image: Rgb, b: FloatArray, runners: Map<Int, ModelRunner>): Pair<String, Float> {
        val x0 = b[0].toInt()
        val y0 = b[1].toInt()
        val w = max(1, b[2].toInt() - x0)
        val h = max(1, b[3].toInt() - y0)
        val needed = ceil(REC_H * w.toFloat() / h).toInt()
        val width = runners.keys.sorted().firstOrNull { it >= needed } ?: runners.keys.max()
        val input = normalized(image, x0, y0, w, h, min(width, needed), REC_H, width)
        val (probs, shape) = runners.getValue(width).run(input, intArrayOf(1, 3, REC_H, width))
        return ctc(probs, 0, shape[1], shape[2])
    }

    /** Greedy CTC: the likeliest class per step, repeats merged, blanks dropped. */
    private fun ctc(probs: FloatArray, offset: Int, steps: Int, classes: Int): Pair<String, Float> {
        val sb = StringBuilder()
        var sum = 0f
        var count = 0
        var prev = -1
        for (t in 0 until steps) {
            var best = 0
            var bestP = -1f
            val base = offset + t * classes
            for (c in 0 until classes) {
                val p = probs[base + c]
                if (p > bestP) { bestP = p; best = c }
            }
            if (best != 0 && best != prev && best < dict.size) {
                sb.append(dict[best])
                sum += bestP
                count++
            }
            prev = best
        }
        return sb.toString() to if (count == 0) 0f else sum / count
    }

    companion object {
        private const val LIMIT_SIDE = 736
        private const val MAX_SIDE = 2000
        private const val MIN_HEIGHT = 30
        private const val FLAT_RATIO = 8f
        private const val TILE_OVERLAP = 200
        private const val THRESH = 0.3f
        private const val BOX_THRESH = 0.5
        private const val UNCLIP_RATIO = 1.6f
        private const val MIN_SIZE = 3f
        private const val MAX_CANDIDATES = 1000
        private const val REC_H = 48

        /** The model's characters, as PaddleOCR lays them out for CTC: blank
         *  first, then the dictionary, then a space. */
        fun dictionary(keys: List<String>): List<String> = listOf("") + keys + " "
    }
}
