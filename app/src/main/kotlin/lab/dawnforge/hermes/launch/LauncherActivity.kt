package lab.dawnforge.hermes.launch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import lab.dawnforge.hermes.MainActivity

/**
 * The one way the widget, the launcher shortcuts and the Quick Settings tile reach the app. It isn't
 * exported, so no other app can start a chat or a voice conversation through it; it hands the action
 * to the app and brings it forward.
 */
@AndroidEntryPoint
class LauncherActivity : ComponentActivity() {
    @Inject
    lateinit var launches: LaunchInbox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LaunchAction.parse(intent.data)?.let(launches::offer)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    companion object {
        /** Starts [action] (for a widget's or tile's pending intent, or a shortcut). */
        fun intent(context: Context, action: LaunchAction): Intent = Intent(context, LauncherActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(action.uri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
