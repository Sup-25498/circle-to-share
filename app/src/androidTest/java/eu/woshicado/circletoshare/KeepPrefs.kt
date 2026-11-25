package eu.woshicado.circletoshare

import android.content.Context
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * The app's settings as they were before each test, put back after it: a
 * test may change any of them. Written to disk at once (commit): the test
 * process is killed when the run ends, and a write still on its way
 * (apply) would be lost with it, leaving the test's settings on the device.
 */
class KeepPrefs : ExternalResource() {

    /** The store itself, for a test that reads or writes a key as it is. */
    val store: SharedPreferences = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    private var was: Map<String, Any?> = emptyMap()

    override fun before() {
        // (A copy of each set: the store's own may change under it.)
        was = store.all.mapValues { (_, value) -> if (value is Set<*>) HashSet(value) else value }
    }

    override fun after() {
        val editor = store.edit().clear()
        for ((key, value) in was) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.commit()
    }
}
