package lab.dawnforge.hermes.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import lab.dawnforge.hermes.MainActivity
import lab.dawnforge.hermes.core.data.settings.AppSettings
import lab.dawnforge.hermes.core.designsystem.theme.HermesTheme

/**
 * Another app's share (text, links, files), or a Direct Share to one chat. The sheet picks where it
 * goes; the app then opens that chat with it in the composer, as iOS's share extension does.
 */
@AndroidEntryPoint
class ShareActivity : ComponentActivity() {
    @Inject
    lateinit var appSettings: AppSettings

    private val viewModel: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        viewModel.load(intent)
        setContent {
            val preferences by appSettings.preferences.collectAsStateWithLifecycle(initialValue = null)
            preferences?.let {
                HermesTheme(theme = it.theme, accent = it.accent) {
                    ShareScreen(
                        viewModel = viewModel,
                        onCancel = ::finish,
                        onAdd = {
                            viewModel.add()?.let(::open)
                            finish()
                        },
                    )
                }
            }
        }
    }

    /** Brings the app forward on the chosen chat, with the share waiting in its composer. */
    private fun open(shared: SharedOpen) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_OPEN_SHARED)
                .putExtra(EXTRA_DASHBOARD, shared.dashboard)
                .putExtra(EXTRA_PROFILE, shared.profile)
                .putExtra(EXTRA_SESSION, shared.sessionId)
                .putExtra(EXTRA_TITLE, shared.title)
                .putExtra(EXTRA_DRAFT, shared.draftId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    companion object {
        const val ACTION_OPEN_SHARED = "lab.dawnforge.hermes.OPEN_SHARED"
        private const val EXTRA_DASHBOARD = "lab.dawnforge.hermes.share.DASHBOARD"
        private const val EXTRA_PROFILE = "lab.dawnforge.hermes.share.PROFILE"
        private const val EXTRA_SESSION = "lab.dawnforge.hermes.share.SESSION"
        private const val EXTRA_TITLE = "lab.dawnforge.hermes.share.TITLE"
        private const val EXTRA_DRAFT = "lab.dawnforge.hermes.share.DRAFT"

        /** The share the app was asked to open, from [intent]. */
        fun opened(intent: Intent): SharedOpen? {
            if (intent.action != ACTION_OPEN_SHARED) return null
            return SharedOpen(
                dashboard = intent.getStringExtra(EXTRA_DASHBOARD) ?: return null,
                profile = intent.getStringExtra(EXTRA_PROFILE) ?: return null,
                sessionId = intent.getStringExtra(EXTRA_SESSION),
                title = intent.getStringExtra(EXTRA_TITLE),
                draftId = intent.getStringExtra(EXTRA_DRAFT) ?: return null,
            )
        }
    }
}
