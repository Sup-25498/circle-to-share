package eu.woshicado.circletoshare.text

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The text on screen while it is still being read: the accessibility tree
 * of a large page takes seconds to walk (Firefox answers a node at a time,
 * ~6 ms each), so the walk runs in the background from the moment of
 * capture, and "Copy text" waits for it only if tapped before it is done.
 *
 * Whoever holds one [cancel]s it when the capture is done with — the crop
 * tool closed by anything but Copy text, or showing another picture — and
 * the walk stops at its next node.
 */
class PendingScreenText {
    private val done = CountDownLatch(1)

    @Volatile
    private var result: ScreenText? = null

    @Volatile
    var cancelled = false
        private set

    fun cancel() {
        cancelled = true
    }

    /** The walk's end: what it read (null for nothing, or cancelled). */
    fun complete(text: ScreenText?) {
        result = if (cancelled) null else text
        done.countDown()
    }

    val isDone get() = done.count == 0L

    /** Waits until the walk is done; the text, or null. The walk has a time
     *  budget of its own, so this returns within it. */
    fun await(): ScreenText? {
        done.await()
        return result
    }

    /** Waits at most [ms] for the walk to be done. */
    fun awaitFor(ms: Long) {
        done.await(ms, TimeUnit.MILLISECONDS)
    }

    companion object {
        /** Text already read (tests, or none at all). */
        fun of(text: ScreenText?) = PendingScreenText().also { it.complete(text) }
    }
}
