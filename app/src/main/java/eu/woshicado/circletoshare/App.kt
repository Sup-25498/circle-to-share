package eu.woshicado.circletoshare

import android.app.Application
import android.content.Context

/** The app as a whole: in the language picked (see [Language]), for what
 *  is said through it once the screen or service that asked has moved on. */
class App : Application() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Language.wrap(base))

    override fun onCreate() {
        super.onCreate()
        Language.refresh(this)
    }
}
