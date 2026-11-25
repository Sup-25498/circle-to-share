package eu.woshicado.circletoshare

import android.app.Instrumentation
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.materialswitch.MaterialSwitch
import eu.woshicado.circletoshare.capture.ScreenshotAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The main screen's sections: the setup steps that fold away, the rows
 *  that come and go with their switch, and the way to the scrollshot page. */
@RunWith(AndroidJUnit4::class)
class MainScreenSectionsTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @get:Rule
    val kept = KeepPrefs()

    private fun prefs() = kept.store

    private fun MainActivity.shows(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    @Test
    fun theSetupSteps_showWhileThereIsSomethingToDo_andFoldAwayOnceDone() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // (A test cannot make this app the assistant: the screen is
                // told what it would find.)
                activity.renderSetup(isAssistant = false, fallbackEnabled = false)
                assertEquals(true, activity.shows(R.id.setup_content))
                activity.renderSetup(isAssistant = true, fallbackEnabled = false)
                assertEquals(true, activity.shows(R.id.setup_content))
                assertEquals(
                    "✓ Assistant · ✗ Fallback",
                    activity.findViewById<TextView>(R.id.setup_summary).text.toString()
                )
                activity.renderSetup(isAssistant = true, fallbackEnabled = true)
                assertEquals(false, activity.shows(R.id.setup_content))
                assertEquals(
                    "✓ Assistant · ✓ Fallback",
                    activity.findViewById<TextView>(R.id.setup_summary).text.toString()
                )
                // The header is still there, to open them with.
                assertEquals(true, activity.shows(R.id.setup_header))
            }
        }
    }

    @Test
    fun aTapOnTheSetupHeader_opensAndClosesTheSteps_untilWhatIsDoneChanges() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val header = activity.findViewById<View>(R.id.setup_header)
                activity.renderSetup(isAssistant = true, fallbackEnabled = true)
                header.performClick()
                assertEquals(true, activity.shows(R.id.setup_content))
                // Looked at again (every return to the screen does): as left.
                activity.renderSetup(isAssistant = true, fallbackEnabled = true)
                assertEquals(true, activity.shows(R.id.setup_content))
                header.performClick()
                assertEquals(false, activity.shows(R.id.setup_content))

                // Folded away by hand while there was something to do, and
                // then all done and undone again: they are back.
                activity.renderSetup(isAssistant = true, fallbackEnabled = false)
                assertEquals(true, activity.shows(R.id.setup_content))
                header.performClick()
                assertEquals(false, activity.shows(R.id.setup_content))
                activity.renderSetup(isAssistant = true, fallbackEnabled = false)
                assertEquals(false, activity.shows(R.id.setup_content))
                activity.renderSetup(isAssistant = true, fallbackEnabled = true)
                activity.renderSetup(isAssistant = false, fallbackEnabled = true)
                assertEquals(true, activity.shows(R.id.setup_content))
            }
        }
    }

    @Test
    fun theStepsOpenedByHand_areStillOpenAfterARotation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var openAsFound = false
            scenario.onActivity { activity ->
                openAsFound = activity.shows(R.id.setup_content)
                activity.findViewById<View>(R.id.setup_header).performClick()
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(!openAsFound, activity.shows(R.id.setup_content))
            }
        }
    }

    @Test
    fun theHighlightColor_isOnlyThereWhileCodesAreLookedFor() {
        Prefs.setQrDetectEnabled(context, true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val switch = activity.findViewById<MaterialSwitch>(R.id.switch_qr)
                assertEquals(true, activity.shows(R.id.qr_color_row))
                switch.performClick()
                assertEquals(false, activity.shows(R.id.qr_color_row))
                assertEquals(false, Prefs.isQrDetectEnabled(context))
                switch.performClick()
                assertEquals(true, activity.shows(R.id.qr_color_row))
            }
        }
        Prefs.setQrDetectEnabled(context, false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertEquals(false, it.shows(R.id.qr_color_row)) }
        }
    }

    @Test
    fun theScrollshotRow_isAButtonToItsPage_andASwitchBesideIt() {
        Prefs.setCaptureMoreEnabled(context, true)
        val monitor = Instrumentation.ActivityMonitor(
            ScrollshotSettingsActivity::class.java.name, null, false
        )
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // The switch switches, and goes nowhere.
                scenario.onActivity {
                    it.findViewById<MaterialSwitch>(R.id.switch_capture_more).performClick()
                }
                assertNull(monitor.waitForActivityWithTimeout(500))
                assertEquals(false, Prefs.isCaptureMoreEnabled(context))

                // The text beside it opens the page, and switches nothing.
                scenario.onActivity {
                    it.findViewById<View>(R.id.btn_scrollshot_settings).performClick()
                }
                val page = monitor.waitForActivityWithTimeout(5000)
                assertNotNull(page)
                assertEquals(false, Prefs.isCaptureMoreEnabled(context))
                instrumentation.runOnMainSync { page.finish() }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun howToOpenIt_foldsOnATap_andIsAsLeftTheNextTimeTheAppIsOpened() {
        Prefs.setSectionExpanded(context, "triggers", true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(true, activity.shows(R.id.triggers_content))
                activity.findViewById<View>(R.id.triggers_header).performClick()
                assertEquals(false, activity.shows(R.id.triggers_content))
                // Its header stays, and so does what comes after it.
                assertEquals(true, activity.shows(R.id.triggers_header))
                assertEquals(true, activity.shows(R.id.switch_freeform))
            }
        }
        assertEquals(false, Prefs.isSectionExpanded(context, "triggers"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(false, activity.shows(R.id.triggers_content))
                activity.findViewById<View>(R.id.triggers_header).performClick()
                assertEquals(true, activity.shows(R.id.triggers_content))
            }
        }
        assertEquals(true, Prefs.isSectionExpanded(context, "triggers"))
    }

    @Test
    fun foldedAway_howToOpenIt_saysWhetherTheFloatingButtonIsOn() {
        Prefs.setSectionExpanded(context, "triggers", true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val summary = activity.findViewById<TextView>(R.id.triggers_summary)
                fun says(text: Int) = assertEquals(activity.getString(text), summary.text.toString())
                // Open, the switch itself is there to see.
                assertEquals(View.INVISIBLE, summary.visibility)
                activity.findViewById<View>(R.id.triggers_header).performClick()
                assertEquals(View.VISIBLE, summary.visibility)
                // What the switch under it says, in words...
                val switchedOn = activity.findViewById<MaterialSwitch>(R.id.switch_bubble).isChecked
                says(
                    if (switchedOn) R.string.triggers_summary_bubble_on
                    else R.string.triggers_summary_bubble_off
                )
                // ...either way. (Told which: the setting itself is the
                // device's, with a button on its screen that goes by it.)
                activity.renderTriggersSummary(bubbleOn = true)
                says(R.string.triggers_summary_bubble_on)
                activity.renderTriggersSummary(bubbleOn = false)
                says(R.string.triggers_summary_bubble_off)
                activity.findViewById<View>(R.id.triggers_header).performClick()
                assertEquals(View.INVISIBLE, summary.visibility)
            }
        }
    }

    @Test
    fun everyOtherSection_foldsOnATap_andIsAsLeftTheNextTimeTheAppIsOpened() {
        val sections = listOf(
            Triple("crop", R.id.crop_header, R.id.crop_content),
            Triple("extras", R.id.extras_header, R.id.extras_content),
            Triple("experimental", R.id.experimental_header, R.id.experimental_content),
            Triple("saving", R.id.saving_header, R.id.saving_content),
            Triple("about", R.id.about_header, R.id.about_content),
        )
        val were = sections.map { prefs().takeIf { p -> p.contains(it.first + "_expanded") }?.getBoolean(it.first + "_expanded", true) }
        try {
            // Open where nothing is stored.
            prefs().edit().apply { sections.forEach { remove(it.first + "_expanded") } }.commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    for ((_, header, content) in sections) {
                        assertEquals(true, activity.shows(content))
                        activity.findViewById<View>(header).performClick()
                        assertEquals(false, activity.shows(content))
                        assertEquals(true, activity.shows(header))
                    }
                }
            }
            sections.forEach { assertEquals(false, Prefs.isSectionExpanded(context, it.first)) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    for ((_, header, content) in sections) {
                        assertEquals(false, activity.shows(content))
                        activity.findViewById<View>(header).performClick()
                        assertEquals(true, activity.shows(content))
                    }
                }
            }
            sections.forEach { assertEquals(true, Prefs.isSectionExpanded(context, it.first)) }
        } finally {
            prefs().edit().apply {
                sections.zip(were).forEach { (section, was) ->
                    was?.let { putBoolean(section.first + "_expanded", it) } ?: remove(section.first + "_expanded")
                }
            }.commit()
        }
    }

    @Test
    fun theSections_comeInTheirOrder_savingAfterExperimental_beforeAbout() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val headers = listOf(
                    R.id.setup_header, R.id.triggers_header, R.id.crop_header, R.id.extras_header,
                    R.id.experimental_header, R.id.saving_header, R.id.about_header,
                ).map { activity.findViewById<View>(it) }
                val parent = headers.first().parent as android.view.ViewGroup
                val at = headers.map { parent.indexOfChild(it) }
                assertEquals(at.sorted(), at)
            }
        }
    }

    @Test
    fun howToOpenIt_isOpen_whereNothingIsStored() {
        prefs().edit().remove("triggers_expanded").commit()
        assertEquals(true, Prefs.isSectionExpanded(context, "triggers"))
    }

    @Test
    fun scrollshotButtons_areOff_whereNothingIsStored() {
        prefs().edit().remove("capture_more").commit()
        assertEquals(false, Prefs.isCaptureMoreEnabled(context))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertEquals(false, it.findViewById<MaterialSwitch>(R.id.switch_capture_more).isChecked)
            }
        }
    }

    private fun ActivityScenario<MainActivity>.notice(): ExperimentalNoticeDialog? {
        var notice: ExperimentalNoticeDialog? = null
        instrumentation.waitForIdleSync()
        onActivity {
            it.supportFragmentManager.executePendingTransactions()
            notice = it.supportFragmentManager.fragments
                .filterIsInstance<ExperimentalNoticeDialog>().firstOrNull()
        }
        return notice
    }

    @Test
    fun switchingAnExperimentalFeatureOn_saysWhatThatMeans_andWhereToReport() {
        Prefs.setCopyTextEnabled(context, false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertNull(scenario.notice())
            scenario.onActivity {
                val switch = it.findViewById<MaterialSwitch>(R.id.switch_copy_text)
                switch.performClick()
                assertEquals(true, switch.isChecked)
                // Off and on again in the same breath finds the first notice.
                switch.performClick()
                switch.performClick()
            }
            // On straight away: the notice asks nothing.
            assertEquals(true, Prefs.isCopyTextEnabled(context))
            val notice = scenario.notice()
            assertNotNull(notice)
            scenario.onActivity { activity ->
                val message = notice!!.dialog!!.findViewById<TextView>(android.R.id.message)
                val text = message.text as android.text.Spanned
                assertEquals(true, text.contains("\"" + activity.getString(R.string.switch_copy_text) + "\""))
                // (Paragraphs, and a list: not one run of text.)
                assertEquals(true, text.contains("\n\nSo that") && text.contains("\n• "))
                assertEquals(true, text.contains("Android " + android.os.Build.VERSION.RELEASE))
                // Two links, on the address and on the page, that a tap opens.
                val links = text.getSpans(0, text.length, android.text.style.ClickableSpan::class.java)
                assertEquals(
                    listOf(
                        activity.getString(R.string.feedback_email),
                        "github.com/woshicado/circle-to-share/issues"
                    ),
                    links.map { text.substring(text.getSpanStart(it), text.getSpanEnd(it)) }
                )
                assertEquals(true, message.movementMethod is android.text.method.LinkMovementMethod)
            }

            // Still there after a rotation, and only once.
            scenario.recreate()
            assertNotNull(scenario.notice())
            scenario.onActivity { activity ->
                assertEquals(1, activity.supportFragmentManager.fragments.count { it is ExperimentalNoticeDialog })
                activity.supportFragmentManager.fragments
                    .filterIsInstance<ExperimentalNoticeDialog>().single().dismiss()
            }
        }
    }

    @Test
    fun theNotice_isOnlyForSwitchingOn_notForBeingOn_norForSwitchingOff() {
        Prefs.setCopyTextEnabled(context, true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertNull(scenario.notice())
            // (A rotation sets the switch as well, to where it was.)
            scenario.recreate()
            assertNull(scenario.notice())
            scenario.onActivity {
                it.findViewById<MaterialSwitch>(R.id.switch_copy_text).performClick()
            }
            assertEquals(false, Prefs.isCopyTextEnabled(context))
            assertNull(scenario.notice())
        }
    }

    @Test
    fun withoutTheAccessibilityService_theButtonsCannotBeSwitchedOn_andNothingIsSaidOfReports() {
        Prefs.setCaptureMoreEnabled(context, false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.fallbackCheck = { false }
                val switch = activity.findViewById<MaterialSwitch>(R.id.switch_capture_more)
                switch.performClick()
                assertEquals(false, switch.isChecked)
            }
            assertEquals(false, Prefs.isCaptureMoreEnabled(context))
            assertNull(scenario.notice())
        }
    }

    @Test
    fun aTapOnHowToOpenIt_goesByWhatIsShown_notByWhatIsStored() {
        Prefs.setSectionExpanded(context, "triggers", true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Stored otherwise behind the screen's back (a second window).
                Prefs.setSectionExpanded(context, "triggers", false)
                activity.findViewById<View>(R.id.triggers_header).performClick()
                assertEquals(false, activity.shows(R.id.triggers_content))
                assertEquals(false, Prefs.isSectionExpanded(context, "triggers"))
            }
        }
    }

    @Test
    fun aServiceSetButNotRunning_isShownAsSuch_withTheStepsOpen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                fun status(id: Int) = activity.findViewById<TextView>(id).text.toString()
                activity.showSetupStatus(
                    isAssistant = true, assistantStale = true, fallbackEnabled = true, fallbackStale = true
                )
                assertEquals(activity.getString(R.string.status_assistant_stale), status(R.id.status_assistant))
                assertEquals(activity.getString(R.string.status_fallback_stale), status(R.id.status_accessibility))
                // Not done, then: the steps are open, and the header says so.
                assertEquals(true, activity.shows(R.id.setup_content))
                assertEquals(
                    "✗ Assistant · ✗ Fallback",
                    activity.findViewById<TextView>(R.id.setup_summary).text.toString()
                )
                // Running again: done.
                activity.showSetupStatus(
                    isAssistant = true, assistantStale = false, fallbackEnabled = true, fallbackStale = false
                )
                assertEquals(activity.getString(R.string.status_assistant_on), status(R.id.status_assistant))
                assertEquals(activity.getString(R.string.status_fallback_on), status(R.id.status_accessibility))
            }
        }
    }

    @Test
    fun anAssistantWithoutVoiceInput_isNotDone_andIsShownTheWayToTheLanguageSettings() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                fun status(id: Int) = activity.findViewById<TextView>(id).text.toString()
                activity.showSetupStatus(
                    isAssistant = true, assistantStale = false, fallbackEnabled = true, fallbackStale = false,
                    voiceInputMissing = true
                )
                assertEquals(
                    activity.getString(R.string.status_assistant_no_voice_input), status(R.id.status_assistant)
                )
                assertEquals(true, activity.shows(R.id.voice_input_group))
                assertEquals(true, activity.shows(R.id.setup_content))
                assertEquals(
                    "✗ Assistant · ✓ Fallback",
                    activity.findViewById<TextView>(R.id.setup_summary).text.toString()
                )
                // Chosen: done, and the hint goes.
                activity.showSetupStatus(
                    isAssistant = true, assistantStale = false, fallbackEnabled = true, fallbackStale = false,
                    voiceInputMissing = false
                )
                assertEquals(activity.getString(R.string.status_assistant_on), status(R.id.status_assistant))
                assertEquals(false, activity.shows(R.id.voice_input_group))
                // Set and not running, and no voice input: the stale status
                // says what to do now, the hint what keeps it.
                activity.showSetupStatus(
                    isAssistant = true, assistantStale = true, fallbackEnabled = true, fallbackStale = false,
                    voiceInputMissing = true
                )
                assertEquals(activity.getString(R.string.status_assistant_stale), status(R.id.status_assistant))
                assertEquals(true, activity.shows(R.id.voice_input_group))
            }
        }
    }

    @Test
    fun snapToScreenBorders_isAPlainSwitch_onByDefault() {
        val prefs = prefs()
        val was = prefs.takeIf { it.contains("snap_to_edges") }?.getBoolean("snap_to_edges", true)
        try {
            prefs.edit().remove("snap_to_edges").commit()
            assertEquals(true, Prefs.isSnapToEdgesEnabled(context))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val switch = activity.findViewById<MaterialSwitch>(R.id.switch_snap_edges)
                    assertEquals(true, switch.isChecked)
                    // Right above "Snap to screen content".
                    val snap = activity.findViewById<View>(R.id.switch_snap)
                    assertEquals(true, switch.bottom <= snap.top)
                    switch.performClick()
                }
            }
            assertEquals(false, Prefs.isSnapToEdgesEnabled(context))
        } finally {
            prefs.edit().apply { was?.let { putBoolean("snap_to_edges", it) } ?: remove("snap_to_edges") }.commit()
        }
    }
}
