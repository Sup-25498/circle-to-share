package eu.woshicado.circletoshare.capture

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors
import eu.woshicado.circletoshare.text.Box
import eu.woshicado.circletoshare.text.PendingScreenText
import eu.woshicado.circletoshare.text.ScreenText
import eu.woshicado.circletoshare.text.StyleRange
import eu.woshicado.circletoshare.text.TextNode

/**
 * Copies the text on screen out of the app windows' accessibility trees,
 * for "Copy text": a [ScreenText] that outlives the nodes. The windows'
 * roots are taken at the moment of capture, like the snap bounds; the walk
 * down from them runs in the background ([PendingScreenText]), as it can
 * take seconds.
 *
 * Only what reaches the screen is copied, as far as the bounds tell: a
 * leaf off it is skipped (each child fetched is Binder IPC) — a branch is
 * not, Firefox leaving a section's bounds where it was before it expanded
 * — and a password field's text is never read. (Nor does "visible to
 * user" tell: Chrome and Firefox say text on screen isn't, a whole table
 * at a time. What is actually visible is for OCR to tell, see TextFusion.)
 * Budgeted by nodes and time; a page too big for the budget yields what
 * was read until then.
 *
 * Measured on a Pixel 10 Pro (2026-11-12): a Wikipedia page in Chrome
 * takes 35–145 ms (~470 nodes); the Python docs in Firefox 1.2–5 s for its
 * ~750 nodes. Under the bubble's or tile's overlay the walk can take that
 * long: the app's nodes stay readable. Under the assistant's window they
 * don't — once it shows, the walk gets nothing more — so a show waits for
 * the walk a while first (AssistSession), and a long Firefox page is read
 * in part there (~430 nodes). Asking for descendants to be prefetched
 * (getRoot/getChild with FLAG_PREFETCH_DESCENDANTS_HYBRID or _DEPTH_FIRST,
 * Android 13+) made no difference in either browser.
 */
internal object ScreenTextReader {

    private const val MAX_NODES = 6000
    // In the background, so no one waits for it unless they tap Copy text:
    // room for a long page in Firefox (~4.5 s for a docs page on a Pixel 10).
    private const val MAX_MS = 8000L
    private const val MAX_DEPTH = 80
    // Text sizes are asked of a native text one by one (more IPC); a screen
    // of settings has a few dozen.
    private const val MAX_SIZE_QUERIES = 80

    private const val CHROME_ROLE = "AccessibilityNodeInfo.chromeRole"
    private const val GECKO_ROLE = "AccessibilityNodeInfo.geckoRole"
    private const val ROLE_DESCRIPTION = "AccessibilityNodeInfo.roleDescription"

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "screen-text") }

    /**
     * Starts reading [windows] (the app windows among them) in the background;
     * this app's own ([ownPackage]: its settings) right away, on this thread.
     * Those answer the service on the main thread, the one a show of the
     * assistant's tool waits on (AssistSession): in the background their walk
     * would wait out that wait, and then find them hidden (7 of ~40 nodes).
     * Asked from that very thread, they answer at once (a few ms).
     */
    fun start(windows: List<AccessibilityWindowInfo>, display: Rect, ownPackage: String): PendingScreenText {
        val pending = PendingScreenText()
        // The roots now, while the app's windows are what is on screen.
        val roots = windows.filter {
            // Application windows only, as for the snap bounds: not our own
            // overlays, the system bars or the keyboard.
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.displayId == Display.DEFAULT_DISPLAY
        }.mapNotNull { it.root }
        if (roots.isEmpty()) return pending.also { it.complete(null) }
        val own = roots.map { it.packageName?.toString() == ownPackage }
        val nodes = arrayOfNulls<TextNode>(roots.size)
        fun walk(which: Boolean): Boolean = try {
            read(roots.indices.filter { own[it] == which }.map { it to roots[it] }, display, pending, nodes)
            true
        } catch (t: Throwable) {
            android.util.Log.w("CircleToShare", "screen text failed", t)
            false
        }
        if (own.any { it } && !walk(true)) return pending.also { it.complete(null) }
        if (own.all { it }) return pending.also { it.complete(screen(nodes, display, pending)) }
        worker.execute {
            pending.complete(if (walk(false)) screen(nodes, display, pending) else null)
        }
        return pending
    }

    /** Reads [roots] (by their place among all) into [into]. */
    private fun read(
        roots: List<Pair<Int, AccessibilityNodeInfo>>, display: Rect, pending: PendingScreenText, into: Array<TextNode?>,
    ) {
        val walk = Walk(display, pending)
        for ((i, root) in roots) {
            into[i] = walk.node(root, 0)
            if (walk.spent()) break
        }
        android.util.Log.d(
            "CircleToShare",
            "screen text: ${roots.size} windows, ${walk.nodes} nodes, " +
                "${SystemClock.uptimeMillis() - walk.started} ms${if (pending.cancelled) ", cancelled" else ""}"
        )
    }

    private fun screen(read: Array<TextNode?>, display: Rect, pending: PendingScreenText): ScreenText? {
        val windows = read.filterNotNull()
        return if (windows.isEmpty() || pending.cancelled) null else ScreenText(display.width(), display.height(), windows)
    }

    private class Walk(val display: Rect, val pending: PendingScreenText) {
        val started = SystemClock.uptimeMillis()
        var nodes = 0
        var sizeQueries = 0

        fun spent() = nodes >= MAX_NODES || SystemClock.uptimeMillis() - started > MAX_MS || pending.cancelled

        fun node(info: AccessibilityNodeInfo, depth: Int): TextNode? {
            nodes++
            val r = Rect()
            info.getBoundsInScreen(r)
            val box = Box(r.left, r.top, r.right, r.bottom)
            val children = ArrayList<TextNode>()
            if (depth < MAX_DEPTH) {
                val count = info.childCount
                val sorted = count >= NodeWalk.SEARCH_MIN && NodeWalk.runsDownThePage(info, count)
                var i = if (sorted) NodeWalk.firstChildReachingScreen(info, count, display) else 0
                var seenOnScreen = false
                val bounds = Rect()
                while (i < count && !spent()) {
                    val child = info.getChild(i++) ?: continue
                    child.getBoundsInScreen(bounds)
                    if (sorted) {
                        if (!bounds.isEmpty && bounds.top >= display.bottom) {
                            if (seenOnScreen) break
                        } else if (Rect.intersects(bounds, display)) {
                            seenOnScreen = true
                        }
                    }
                    // A leaf off the screen can't be in a crop. (Empty bounds pass:
                    // line breaks of a code block are empty, and can sit right of
                    // the screen.)
                    if (!bounds.isEmpty && !Rect.intersects(bounds, display) && child.childCount == 0) continue
                    node(child, depth + 1)?.let { children += it }
                }
            }
            val text = if (info.isPassword) null else info.text
            val extras: Bundle? = info.extras
            val chromeRole = extras?.getCharSequence(CHROME_ROLE)?.toString()
            val geckoRole = extras?.getCharSequence(GECKO_ROLE)?.toString()
            val web = extras != null && (extras.containsKey(CHROME_ROLE) || extras.containsKey(GECKO_ROLE))
            // Nothing to keep: no text, no children with any, no role worth knowing.
            if (text.isNullOrEmpty() && children.isEmpty() && !info.isHeading && chromeRole == null && geckoRole == null) {
                return null
            }
            val styles = (text as? Spanned)?.let { spanned ->
                spanned.getSpans(0, spanned.length, StyleSpan::class.java).map {
                    StyleRange(spanned.getSpanStart(it), spanned.getSpanEnd(it), it.style)
                }
            }.orEmpty()
            val item = info.collectionItemInfo?.let {
                intArrayOf(it.rowIndex, it.columnIndex, it.rowSpan, it.columnSpan)
            }
            return TextNode(
                cls = info.className?.toString()?.substringAfterLast('.') ?: "",
                bounds = box,
                text = text?.toString(),
                heading = info.isHeading,
                web = web,
                role = geckoRole?.takeIf { it.isNotEmpty() } ?: chromeRole ?: "",
                roleDescription = extras?.getCharSequence(ROLE_DESCRIPTION)?.toString() ?: "",
                item = item,
                styles = styles,
                textPx = if (!web && !text.isNullOrBlank()) textSize(info) else 0f,
                children = children,
            )
        }

        /** The size a native text is drawn at, where the app tells. */
        private fun textSize(info: AccessibilityNodeInfo): Float {
            if (sizeQueries >= MAX_SIZE_QUERIES || spent()) return 0f
            val key = AccessibilityNodeInfo.EXTRA_DATA_RENDERING_INFO_KEY
            if (info.availableExtraData?.contains(key) != true) return 0f
            sizeQueries++
            return runCatching {
                info.refreshWithExtraData(key, Bundle())
                info.extraRenderingInfo?.textSizeInPx ?: 0f
            }.getOrDefault(0f)
        }
    }
}
