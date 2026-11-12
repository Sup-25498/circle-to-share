package eu.woshicado.circletoshare

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import eu.woshicado.circletoshare.capture.ScreenshotAccessibilityService

/**
 * What the accessibility service reads and what becomes of it, put to the
 * user before the service is used — a screen of its own, apart from the
 * privacy policy, that only an explicit tap on Agree gets past.
 *
 * RESULT_OK means the user agreed (and the answer is stored); leaving any
 * other way — No thanks, Back — is a no, and stores nothing. No thanks
 * says so in the result ([EXTRA_DECLINED]).
 *
 * With [EXTRA_UPDATE], it is put again to someone who agreed before it said
 * that the text on screen is read too (for "Copy text"): the same text,
 * under a title that says what changed.
 */
class DisclosureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge like the main screen (see MainActivity.onCreate).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_disclosure)
        if (intent.getBooleanExtra(EXTRA_UPDATE, false)) {
            findViewById<TextView>(R.id.disclosure_title).setText(R.string.disclosure_title_update)
            findViewById<TextView>(R.id.disclosure_choice_body).setText(R.string.disclosure_choice_body_update)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.disclosure_root)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<Button>(R.id.btn_disclosure_privacy).setOnClickListener {
            openUrl(R.string.url_privacy)
        }
        findViewById<Button>(R.id.btn_disclosure_decline).setOnClickListener {
            // (A "no" said, as opposed to leaving with Back.)
            setResult(RESULT_CANCELED, android.content.Intent().putExtra(EXTRA_DECLINED, true))
            finish()
        }
        findViewById<Button>(R.id.btn_disclosure_agree).setOnClickListener {
            Prefs.setAccessibilityConsent(this, true)
            Prefs.setTextConsent(this, true)
            // A service that was already switched on has been holding the
            // bubble back for this answer.
            ScreenshotAccessibilityService.instance?.applyBubbleState()
            setResult(RESULT_OK)
            finish()
        }
    }

    companion object {
        const val EXTRA_UPDATE = "update"
        const val EXTRA_DECLINED = "declined"
    }
}
