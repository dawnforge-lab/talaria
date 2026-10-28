package lab.dawnforge.hermes

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import lab.dawnforge.hermes.core.data.plugin.AnsweredCalls
import lab.dawnforge.hermes.core.data.settings.AppSettings
import lab.dawnforge.hermes.core.designsystem.theme.HermesTheme
import lab.dawnforge.hermes.core.model.ThemeChoice
import lab.dawnforge.hermes.core.push.PushLink
import lab.dawnforge.hermes.core.push.PushLinkInbox
import lab.dawnforge.hermes.core.push.calls.HermesCallService
import lab.dawnforge.hermes.feature.chat.LocalMapsAvailable
import lab.dawnforge.hermes.feature.connect.ConnectLinkInbox
import lab.dawnforge.hermes.share.ShareActivity
import lab.dawnforge.hermes.ui.HermesApp
import lab.dawnforge.hermes.ui.SessionLinkInbox
import lab.dawnforge.hermes.ui.SharedOpenInbox

// A FragmentActivity: the phone's unlock prompt (androidx.biometric) needs one.
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject
    lateinit var linkInbox: ConnectLinkInbox

    @Inject
    lateinit var pushInbox: PushLinkInbox

    @Inject
    lateinit var sessionLinks: SessionLinkInbox

    @Inject
    lateinit var sharedOpens: SharedOpenInbox

    @Inject
    lateinit var appSettings: AppSettings

    @Inject
    lateinit var answeredCalls: AnsweredCalls

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity has already handled its launch intent.
        if (savedInstanceState == null) handleLink(intent)
        setContent {
            // Nothing until the settings are read, so a chosen theme never flashes the default.
            val preferences by appSettings.preferences.collectAsStateWithLifecycle(initialValue = null)
            preferences?.let {
                val dark = when (it.theme) {
                    ThemeChoice.System -> isSystemInDarkTheme()
                    ThemeChoice.Light -> false
                    ThemeChoice.Dark -> true
                }
                // The status and navigation bar icons follow the app's theme, not the system's.
                DisposableEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                        navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                    )
                    onDispose {}
                }
                HermesTheme(theme = it.theme, accent = it.accent) {
                    CompositionLocalProvider(LocalMapsAvailable provides BuildConfig.MAPS_ENABLED) {
                        HermesApp()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /**
     * A tapped notification's chat, a link to a chat (`talaria://session/…`), or a
     * `talaria://connect?…` setup link (the format Hermes generates for phones).
     */
    private fun handleLink(intent: Intent) {
        // Reopened from Recents: the start that carried this was handled when it happened.
        if (LaunchReplay.isReplay(intent.flags)) return
        // A call from Hermes answered (or Talk tapped): the ringing stops and the chat talks.
        val call = PushLink.takeCall(intent)
        val target = PushLink.take(intent)
        if (call != null) {
            HermesCallService.answered(this)
            target?.sessionId?.let { answeredCalls.offer(it, call) }
        }
        target?.let(pushInbox::offer)
        ShareActivity.opened(intent)?.let(sharedOpens::offer)
        if (intent.action != Intent.ACTION_VIEW) return
        val data = intent.dataString ?: return
        if (!sessionLinks.offer(data)) linkInbox.offer(data)
        // Keep the password out of the intent the system may hold on to.
        intent.data = null
    }
}

/** The scrims Android draws behind three-button navigation, as `enableEdgeToEdge` uses by default. */
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
