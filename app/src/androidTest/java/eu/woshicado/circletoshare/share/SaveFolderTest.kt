package eu.woshicado.circletoshare.share

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.materialswitch.MaterialSwitch
import eu.woshicado.circletoshare.MainActivity
import eu.woshicado.circletoshare.Prefs
import eu.woshicado.circletoshare.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Where Save puts images: Pictures/Screenshots unless a folder is picked,
 *  one folder per app if asked for, and never nowhere. (Picking a folder
 *  takes Android's picker, which a test can't work: that part is tried by
 *  hand.) */
@RunWith(AndroidJUnit4::class)
class SaveFolderTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    private val saved = mutableListOf<Uri>()

    private var dirWas: String? = null
    private var perAppWas = false

    @Before
    fun remember() {
        dirWas = Prefs.getSaveDir(context)
        perAppWas = Prefs.isSavePerApp(context)
    }

    @After
    fun restore() {
        Prefs.setSaveDir(context, dirWas)
        Prefs.setSavePerApp(context, perAppWas)
        saved.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    private fun save(app: String?): Delivered =
        ShareHelper.write(context, bitmap, ShareHelper.bounds(bitmap, null)!!, Delivery.SAVE, app)
            .also { saved += it.uri }

    private fun relativePath(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.RELATIVE_PATH), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    @Test
    fun byDefault_saveGoesToScreenshots_asBefore() {
        Prefs.setSaveDir(context, null)
        Prefs.setSavePerApp(context, false)
        val delivered = save(app = "com.android.settings")
        assertEquals("Screenshots", delivered.folder)
        assertEquals("Pictures/Screenshots/", relativePath(delivered.uri))
    }

    @Test
    fun aFolderPerApp_isNamedAfterTheApp_insideScreenshots() {
        Prefs.setSaveDir(context, null)
        Prefs.setSavePerApp(context, true)
        val delivered = save(app = "com.android.settings")
        assertEquals("Screenshots/Settings", delivered.folder)
        assertEquals("Pictures/Screenshots/Settings/", relativePath(delivered.uri))
    }

    @Test
    fun aFolderPerApp_withNoAppKnown_isTheFolderItself() {
        Prefs.setSaveDir(context, null)
        Prefs.setSavePerApp(context, true)
        val delivered = save(app = null)
        assertEquals("Screenshots", delivered.folder)
        assertEquals("Pictures/Screenshots/", relativePath(delivered.uri))
    }

    @Test
    fun aFolderThatCantBeWrittenTo_isSavedAround_notLostIn() {
        // Never granted: what a folder whose grant is gone looks like.
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ANotGranted"
        Prefs.setSaveDir(context, tree)
        Prefs.setSavePerApp(context, false)
        assertFalse(ShareHelper.isSaveDirUsable(context, Uri.parse(tree)))
        val delivered = save(app = "com.android.settings")
        assertEquals("Screenshots", delivered.folder)
        assertEquals(ContentResolver.SCHEME_CONTENT, delivered.uri.scheme)
        assertEquals("Pictures/Screenshots/", relativePath(delivered.uri))
    }

    @Test
    fun aFolderThatIsNoUriAtAll_isSavedAround_too() {
        Prefs.setSaveDir(context, "not a uri")
        Prefs.setSavePerApp(context, true)
        assertFalse(ShareHelper.isSaveDirUsable(context, Uri.parse("not a uri")))
        val delivered = save(app = "com.android.settings")
        assertEquals("Screenshots/Settings", delivered.folder)
    }

    @Test
    fun aFolderOnTheDevice_isNamedByItsPath() {
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures%2FNotes")
        assertEquals("Pictures/Notes", ShareHelper.describeSaveDir(context, tree))
    }

    @Test
    fun anAppsFolder_isItsName_orElseItsPackage() {
        assertEquals("Settings", ShareHelper.appFolder(context, "com.android.settings"))
        assertEquals("org.example.notinstalled", ShareHelper.appFolder(context, "org.example.notinstalled"))
    }

    @Test
    fun folderNames_keepToWhatAnyStorageTakes() {
        assertEquals("Notes_ work", ShareHelper.folderName("Notes: work"))
        assertEquals("a_b_c", ShareHelper.folderName("a/b\\c"))
        assertEquals("What_", ShareHelper.folderName("What?"))
        assertEquals("Tab here", ShareHelper.folderName("Tab\there"))
        assertEquals("dots", ShareHelper.folderName("..dots.."))
        assertEquals("two words", ShareHelper.folderName("  two   words  "))
        assertNull(ShareHelper.folderName(" . "))
        assertNull(ShareHelper.folderName(""))
        assertEquals(64, ShareHelper.folderName("x".repeat(200))!!.length)
        // An emoji across the cut is dropped whole, not halved.
        assertEquals("x".repeat(63), ShareHelper.folderName("x".repeat(63) + "\uD83D\uDCF7 and more"))
    }

    @Test
    fun theSettings_startAtScreenshots_withNoFolderPerApp() {
        Prefs.setSaveDir(context, null)
        context.getSharedPreferences("cts", Context.MODE_PRIVATE).edit().remove("save_per_app").commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.save_dir_default),
                    activity.findViewById<TextView>(R.id.save_dir_summary).text.toString()
                )
                assertEquals(View.GONE, activity.findViewById<View>(R.id.btn_save_dir_reset).visibility)
                assertFalse(activity.findViewById<MaterialSwitch>(R.id.switch_save_per_app).isChecked)
            }
        }
    }

    @Test
    fun theSettings_sayWhenThePickedFolderCantBeUsed_andOfferTheWayBack() {
        Prefs.setSaveDir(context, "content://com.android.externalstorage.documents/tree/primary%3ANotGranted")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // (Found out in the background: said a moment later.)
            val unusable = context.getString(R.string.save_dir_unusable, "NotGranted")
            var summary = ""
            for (attempt in 1..40) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { summary = it.findViewById<TextView>(R.id.save_dir_summary).text.toString() }
                if (summary == unusable) break
                Thread.sleep(50)
            }
            assertEquals(unusable, summary)
            scenario.onActivity { activity ->
                val reset = activity.findViewById<View>(R.id.btn_save_dir_reset)
                assertEquals(View.VISIBLE, reset.visibility)
                reset.performClick()
                assertNull(Prefs.getSaveDir(activity))
                assertEquals(View.GONE, reset.visibility)
                assertNotNull(activity.findViewById<TextView>(R.id.save_dir_summary).text)
            }
        }
    }
}
