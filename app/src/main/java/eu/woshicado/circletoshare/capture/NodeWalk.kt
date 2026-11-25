package eu.woshicado.circletoshare.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Shortcuts for walks over a window's nodes (snap bounds, screen text): a
 * long page scrolled far down — a web page lists all of itself — would
 * spend a walk's whole budget on what lies above the screen. Where a node
 * has many children and they run down the page in order, a walk starts at
 * the first that reaches the screen and stops after the last that does.
 */
internal object NodeWalk {

    /** Fewer children than this are simply all looked at. */
    const val SEARCH_MIN = 12

    /** Whether [node]'s [count] children are laid out one below the other
     *  in the order they are listed in — judged by three of them. */
    fun runsDownThePage(node: AccessibilityNodeInfo, count: Int): Boolean {
        var last = Int.MIN_VALUE
        val r = Rect()
        for (i in intArrayOf(0, count / 2, count - 1)) {
            val child = node.getChild(i) ?: return false
            child.getBoundsInScreen(r)
            if (r.isEmpty || r.top < last) return false
            last = r.top
        }
        return true
    }

    /** The first of [node]'s children (which run down the page) to end
     *  below the top of [display]: every one before it is above the screen. */
    fun firstChildReachingScreen(node: AccessibilityNodeInfo, count: Int, display: Rect): Int {
        var lo = 0
        var hi = count
        val r = Rect()
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val child = node.getChild(mid)
            child?.getBoundsInScreen(r)
            // (One that can't be placed is taken to be on screen: better a
            // few looks too many than a start too far down.)
            if (child != null && !r.isEmpty && r.bottom <= display.top) lo = mid + 1 else hi = mid
        }
        // A step back: neighbours can overlap by a margin.
        return (lo - 1).coerceAtLeast(0)
    }

    /**
     * [node]'s children that can be on [display], in order, to [visit], for
     * as long as [more]: where they are many and run down the page, from
     * the first that reaches the screen to the last that does; else all.
     */
    inline fun childrenOnScreen(
        node: AccessibilityNodeInfo, display: Rect, more: () -> Boolean, visit: (AccessibilityNodeInfo) -> Unit,
    ) {
        val count = node.childCount
        val sorted = count >= SEARCH_MIN && runsDownThePage(node, count)
        var i = if (sorted) firstChildReachingScreen(node, count, display) else 0
        var seenOnScreen = false
        val bounds = Rect()
        while (i < count && more()) {
            val child = node.getChild(i++) ?: continue
            if (sorted) {
                child.getBoundsInScreen(bounds)
                if (!bounds.isEmpty && bounds.top >= display.bottom) {
                    if (seenOnScreen) break
                } else if (Rect.intersects(bounds, display)) {
                    seenOnScreen = true
                }
            }
            visit(child)
        }
    }
}
