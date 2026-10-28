package lab.dawnforge.hermes.core.push

import android.content.Intent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import lab.dawnforge.hermes.core.data.plugin.AnsweredCall
import lab.dawnforge.hermes.core.model.DashboardId

/** The chat a tapped notification opens, carried in the launch intent's extras. */
object PushLink {
    /** A tapped notification's own action, so Android delivers it to the activity. */
    const val ACTION_OPEN = "lab.dawnforge.hermes.push.OPEN"

    /** A call from Hermes answered (or Talk tapped): the chat opens and talks. */
    const val ACTION_TALK = "lab.dawnforge.hermes.push.TALK"
    private const val EXTRA_CALL_INTRO = "lab.dawnforge.hermes.push.CALL_INTRO"
    private const val EXTRA_CALL_WAITS = "lab.dawnforge.hermes.push.CALL_WAITS"
    private const val EXTRA_DASHBOARD = "lab.dawnforge.hermes.push.DASHBOARD"
    private const val EXTRA_PROFILE = "lab.dawnforge.hermes.push.PROFILE"
    private const val EXTRA_SESSION = "lab.dawnforge.hermes.push.SESSION"

    fun put(intent: Intent, target: HermesNotification.Target): Intent = intent
        .putExtra(EXTRA_DASHBOARD, target.dashboardId.value)
        .putExtra(EXTRA_PROFILE, target.profile)
        .putExtra(EXTRA_SESSION, target.sessionId)

    /** What the call says first, for a Talk intent. */
    fun putCall(intent: Intent, call: AnsweredCall): Intent = intent
        .putExtra(EXTRA_CALL_INTRO, call.intro)
        .putExtra(EXTRA_CALL_WAITS, call.waitsOnUser)

    /** The answered call in [intent], removed so a recreated activity doesn't answer it again. */
    fun takeCall(intent: Intent): AnsweredCall? {
        if (intent.action != ACTION_TALK) return null
        val intro = intent.getStringExtra(EXTRA_CALL_INTRO) ?: return null
        val call = AnsweredCall(intro, intent.getBooleanExtra(EXTRA_CALL_WAITS, false))
        intent.removeExtra(EXTRA_CALL_INTRO)
        intent.removeExtra(EXTRA_CALL_WAITS)
        return call
    }

    /** The target in [intent], removing it so a recreated activity does not open it again. */
    fun take(intent: Intent): HermesNotification.Target? {
        val dashboard = intent.getStringExtra(EXTRA_DASHBOARD) ?: return null
        val profile = intent.getStringExtra(EXTRA_PROFILE) ?: return null
        val target = HermesNotification.Target(DashboardId(dashboard), profile, intent.getStringExtra(EXTRA_SESSION))
        intent.removeExtra(EXTRA_DASHBOARD)
        intent.removeExtra(EXTRA_PROFILE)
        intent.removeExtra(EXTRA_SESSION)
        return target
    }
}

/** Hands a tapped notification's target from the activity to the navigation. */
@Singleton
class PushLinkInbox @Inject constructor() {
    private val pending = MutableStateFlow<HermesNotification.Target?>(null)

    val target: StateFlow<HermesNotification.Target?> = pending.asStateFlow()

    fun offer(target: HermesNotification.Target) {
        pending.value = target
    }

    fun take(): HermesNotification.Target? = pending.getAndUpdate { null }
}
