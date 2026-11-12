package eu.woshicado.circletoshare.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingScreenTextTest {

    private val text = ScreenText(10, 10, emptyList())

    @Test
    fun aWalkStillUnderWay_isWaitedFor() {
        val pending = PendingScreenText()
        assertFalse(pending.isDone)
        Thread { Thread.sleep(50); pending.complete(text) }.start()
        assertSame(text, pending.await())
        assertTrue(pending.isDone)
    }

    @Test
    fun aCancelledWalk_handsOverNothing() {
        val pending = PendingScreenText()
        pending.cancel()
        pending.complete(text)
        assertNull(pending.await())
    }

    @Test
    fun aBoundedWait_returnsWhileTheWalkGoesOn() {
        val pending = PendingScreenText()
        pending.awaitFor(20)
        assertFalse(pending.isDone)
    }
}
