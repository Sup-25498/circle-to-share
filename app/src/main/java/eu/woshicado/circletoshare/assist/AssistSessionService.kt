package eu.woshicado.circletoshare.assist

import android.content.Context
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import eu.woshicado.circletoshare.Language

class AssistSessionService : VoiceInteractionSessionService() {

    // (Its sessions, the crop tool, in the language picked, before
    // Android 13 too: see Language.)
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Language.wrap(base))

    override fun onNewSession(args: android.os.Bundle?): VoiceInteractionSession {
        return AssistSession(this)
    }
}
