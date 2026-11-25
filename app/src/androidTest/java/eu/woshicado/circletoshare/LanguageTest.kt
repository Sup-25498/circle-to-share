package eu.woshicado.circletoshare

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.text.Collator

/** The language picker's list, and the language picked reaching what is
 *  not an AppCompat activity before Android 13, and from 13 what Android
 *  leaves in another language (an assistant session's window, a process an
 *  accessibility service started). */
@RunWith(AndroidJUnit4::class)
class LanguageTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule
    val kept = KeepPrefs()

    @Test
    fun theSystemsLanguageComesFirst_thenEnglish_thenTheTranslationsByName() {
        val options = Language.options()
        assertEquals(listOf(null, "en"), options.take(2))
        val translations = options.drop(2).map { it!! }
        assertEquals(BuildConfig.TRANSLATIONS.toSet(), translations.toSet())
        assertEquals(translations.sortedWith(compareBy(Collator.getInstance(), Language::name)), translations)
    }

    @Test
    fun eachLanguage_isNamedInItself() {
        assertEquals("English", Language.name("en"))
        assertEquals("Deutsch", Language.name("de"))
    }

    @Test
    fun beforeAndroid13_aWrappedContext_followsThePick() {
        assumeTrue(Build.VERSION.SDK_INT < 33)
        val wrapped = Language.wrap(context)
        Prefs.setLanguage(context, "de")
        assertEquals("Sprache", wrapped.getString(R.string.language))
        Prefs.setLanguage(context, "en")
        assertEquals("Language", wrapped.getString(R.string.language))
    }

    @Test
    fun fromAndroid13_aContext_isInTheLanguageAndroidKeepsForTheApp_whateverItWasIn() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val locales = context.getSystemService(LocaleManager::class.java)
        val was = locales.applicationLocales
        val system = locales.systemLocales
        // A language that isn't the system's, and a context in a third
        // (as the window context Android gives an assistant session, or a
        // process an accessibility service started).
        val pickedTag = if (system[0].language == "de") "en" else "de"
        val elsewhere = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("fr")) }
        )
        try {
            locales.applicationLocales = LocaleList.forLanguageTags(pickedTag)
            Language.refresh(context)
            assertEquals(pickedTag, Language.wrap(elsewhere).resources.configuration.locales[0].language)
            // Nothing picked: the system's.
            locales.applicationLocales = LocaleList.getEmptyLocaleList()
            Language.refresh(context)
            assertEquals(system, Language.wrap(elsewhere).resources.configuration.locales)
        } finally {
            locales.applicationLocales = was
            Language.refresh(context)
        }
    }
}
