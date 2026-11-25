package eu.woshicado.circletoshare

import android.content.Context
import android.app.LocaleManager
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.text.Collator
import java.util.Locale

/**
 * The app's language, when one is picked over the system's. From Android 13
 * Android keeps it (also in its own App languages settings); before that
 * AppCompat does, for its activities. [wrap] applies it where neither
 * Android nor AppCompat does.
 */
object Language {

    /** What can be picked, in the order offered: null for the system's
     *  language, then English, the app's own (values/), then the
     *  translations (values-xx, listed by the build) by their names. */
    fun options(): List<String?> {
        val collator = Collator.getInstance()
        return listOf(null, "en") + BuildConfig.TRANSLATIONS.filter { it != "en" }
            .sortedWith { a, b -> collator.compare(name(a), name(b)) }
    }

    /** A language by its own name ("Deutsch"), as it is looked for. */
    fun name(tag: String): String =
        Locale.forLanguageTag(tag).let { it.getDisplayName(it).replaceFirstChar { c -> c.titlecase(it) } }

    /** The language picked, as one of [options] where it is one: Android's
     *  own settings may have added a region ("en-US" for "en"). Null when
     *  it follows the system. (Read after an AppCompat activity is created.) */
    fun picked(): String? {
        val locale = AppCompatDelegate.getApplicationLocales()[0] ?: return null
        val tags = options().filterNotNull()
        return tags.find { it == locale.toLanguageTag() }
            ?: tags.find { Locale.forLanguageTag(it).language == locale.language }
            ?: locale.toLanguageTag()
    }

    fun pick(context: Context, tag: String?) {
        if (Build.VERSION.SDK_INT < 33) Prefs.setLanguage(context, tag)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag.orEmpty()))
        refresh(context)
    }

    // From 13: the languages the app is to be in, as last read (see
    // refresh): those picked for it, else the system's. Android applies a
    // pick to the app's activities, but to the rest of its process only
    // where an activity started it (not where the accessibility service
    // did), and never to an assistant session's window.
    @Volatile
    private var inUse: LocaleList? = null

    /** Reads again which languages Android keeps for the app and the system
     *  (from 13; before, the pref is read each time): where a crop tool is
     *  built, so that a pick made meanwhile in Android's own settings, or a
     *  new system language, shows in it. */
    fun refresh(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val locales = context.getSystemService(LocaleManager::class.java) ?: return
        inUse = locales.applicationLocales.takeUnless { it.isEmpty } ?: locales.systemLocales
    }

    /**
     * [base] in the language picked, where Android doesn't see to that
     * itself: before Android 13 it does for AppCompat's activities alone,
     * from 13 for the activities and, not always, the rest (see [inUse]).
     *
     * It follows a new pick and a new configuration (a rotation, dark mode)
     * as they come: the services it is for outlive both.
     */
    fun wrap(base: Context): Context = object : ContextWrapper(base) {
        // The languages, the configuration they were applied to, and what
        // came of it.
        @Volatile
        private var made: Triple<LocaleList, Configuration, Resources>? = null

        override fun getResources(): Resources {
            val own = baseContext.resources
            val wanted = wanted(baseContext) ?: return own
            if (own.configuration.locales == wanted) return own
            made?.let { (locales, config, resources) ->
                if (locales == wanted && config == own.configuration) return resources
            }
            val config = Configuration(own.configuration)
            val applied = Configuration(config).apply { setLocales(wanted) }
            return baseContext.createConfigurationContext(applied).resources.also { made = Triple(wanted, config, it) }
        }
    }

    /** The languages [context] should be in; null to leave it as it is
     *  (before 13 where nothing is picked, from 13 before the first read). */
    private fun wanted(context: Context): LocaleList? =
        if (Build.VERSION.SDK_INT >= 33) inUse
        else Prefs.getLanguage(context)?.let { LocaleList.forLanguageTags(it) }
}
