package eu.woshicado.circletoshare.text

import android.content.ClipData
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * "Copy text": the text inside a selection, as [Block]s, read off the
 * thread that draws the crop tool. The picture is read by OCR; where the
 * capture came with the accessibility tree's text ([ScreenText]), that
 * supplies the exact characters and the structure, and OCR what is visible
 * ([TextFusion]).
 */
object TextCopy {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "copy-text") }

    /** A wait for the tree's text longer than this is said. */
    private const val SLOW_WAIT_MS = 1000L

    /** How long the models stay loaded after their last use. */
    private const val IDLE_MS = 2 * 60_000L
    private val release = Runnable { worker.execute { OcrEngine.release() } }

    /** Load the OCR models in the background now — the crop tool is open
     *  with Copy text on, which is a tap away with a selection or without —
     *  so that the tap doesn't wait for them (~0.24 s of work per opening
     *  while they are released, nothing while loaded; only with Copy text
     *  on, see CropScreen.showSelectionActions). (Not a first run of the
     *  detectors too, which would make the first copy ~0.2 s faster: it
     *  costs ~1 s of work on every opening, Copy text or not.) */
    fun prepare(context: Context) {
        val app = context.applicationContext
        worker.execute { runCatching { OcrEngine.get(app) } }
        releaseLater()
    }

    private fun releaseLater() {
        main.removeCallbacks(release)
        main.postDelayed(release, IDLE_MS)
    }

    /**
     * Reads the text of [selection] (in [bitmap]'s pixels) and hands the
     * blocks — empty when there is none — to [done] on the main thread,
     * unless the [Job] is cancelled first. [slow], on the main thread, if
     * the wait for the tree's text is a long one (a long page can take
     * seconds to read): for the caller to say so.
     */
    fun start(
        context: Context, bitmap: Bitmap, selection: Rect, screen: PendingScreenText?,
        slow: () -> Unit, done: (List<Block>) -> Unit,
    ): Job {
        val job = Job()
        val app = context.applicationContext
        val sayingSlow = Runnable { if (!job.cancelled) slow() }
        releaseLater()
        worker.execute {
            val blocks = try {
                read(app, bitmap, selection, screen, sayingSlow) { job.cancelled }
            } catch (_: java.util.concurrent.CancellationException) {
                emptyList() // the tool closed: no one waits for it
            } catch (t: Throwable) {
                // Like the QR scan: garnish on the crop tool, never its downfall.
                android.util.Log.w("CircleToShare", "copy text failed", t)
                emptyList()
            }
            if (!job.cancelled) main.post { if (!job.cancelled) done(blocks) }
        }
        return job
    }

    private fun read(
        context: Context, bitmap: Bitmap, selection: Rect, pending: PendingScreenText?, slow: Runnable,
        cancelled: () -> Boolean,
    ): List<Block> {
        val begun = android.os.SystemClock.uptimeMillis()
        val area = Rect(selection).apply { intersect(0, 0, bitmap.width, bitmap.height) }
        if (area.isEmpty) return emptyList()
        val soft = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val px = IntArray(area.width() * area.height())
        soft.getPixels(px, 0, area.width(), area.left, area.top, area.width(), area.height())
        val gray = Gray(area.width(), area.height(), IntArray(px.size) {
            val p = px[it]
            // (Rec. 601 luma, as the layout rules were tuned with.)
            (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        })
        for (i in px.indices) px[i] = px[i] and 0xFFFFFF
        val rgb = Rgb(area.width(), area.height(), px)
        val started = android.os.SystemClock.uptimeMillis()
        val ocr: PaddleOcr
        val page = try {
            ocr = OcrEngine.get(context)
            ocr.detect(rgb, cancelled)
        } catch (t: Throwable) {
            // Without OCR the tree's text alone is still worth copying.
            android.util.Log.w("CircleToShare", "OCR unavailable", t)
            val screen = pending?.await() ?: return emptyList()
            return TreeMarkdown(toScreen(area, bitmap, screen)).read(screen)
        }
        // The tree's text, if its reading isn't done yet (while OCR looked).
        val detected = android.os.SystemClock.uptimeMillis()
        if (pending != null && !pending.isDone) main.postDelayed(slow, SLOW_WAIT_MS)
        val screen = pending?.await()
        main.removeCallbacks(slow)
        val read = android.os.SystemClock.uptimeMillis()
        val blocks = if (screen == null) LayoutMarkdown.read(page.all(), gray) else {
            val crop = toScreen(area, bitmap, screen)
            TextFusion.read(
                screen, crop, page, gray, crop.left, crop.top,
                screen.width.toFloat() / bitmap.width, screen.height.toFloat() / bitmap.height,
            )
        }
        android.util.Log.d(
            "CircleToShare",
            "copy text: ${area.width()}x${area.height()}, tree ${if (screen != null) "yes" else "no"}: " +
                "pixels ${started - begun} ms, load+detect ${detected - started} ms (detect ${ocr.detMs}), " +
                "waited for the tree ${read - detected} ms, " +
                "then reading ${ocr.recLines} of ${page.boxes.size} lines ${ocr.recMs} ms, all ${android.os.SystemClock.uptimeMillis() - begun} ms"
        )
        return blocks
    }

    /** [selection] in the picture's pixels, in the screen's (which the tree's
     *  bounds are in): the picture may be scaled. */
    fun toScreen(selection: Rect, bitmap: Bitmap, screen: ScreenText): Box {
        val sx = screen.width.toDouble() / bitmap.width
        val sy = screen.height.toDouble() / bitmap.height
        return Box(
            (selection.left * sx).toInt(), (selection.top * sy).toInt(),
            (selection.right * sx).toInt(), (selection.bottom * sy).toInt()
        )
    }

    /** [blocks] for the clipboard: Markdown for plain-text fields, HTML for
     *  rich ones (mail, documents); or, with the formatting switched off,
     *  plain text. */
    fun clip(blocks: List<Block>, markdown: Boolean): ClipData =
        if (markdown) ClipData.newHtmlText("text", blocks.toMarkdown(), blocks.toHtml())
        else ClipData.newPlainText("text", blocks.toPlainText())

    class Job internal constructor() {
        @Volatile
        var cancelled = false
            private set

        fun cancel() {
            cancelled = true
        }
    }
}
