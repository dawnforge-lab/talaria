package lab.dawnforge.hermes.core.push.calls

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.plugin.AnsweredCall
import lab.dawnforge.hermes.core.data.plugin.CallOutcome
import lab.dawnforge.hermes.core.data.plugin.HermesCallsRepository
import lab.dawnforge.hermes.core.data.plugin.PendingCallOutcome
import lab.dawnforge.hermes.core.data.settings.AppSettings
import lab.dawnforge.hermes.core.push.HermesNotification
import lab.dawnforge.hermes.core.push.PushNotifier
import lab.dawnforge.hermes.core.voice.VoiceSessions

/**
 * What a call from Hermes does when it reaches the phone, as on iOS:
 * - it rings, when it's fresh and ringing is on;
 * - it comes as a "Hermes wants to talk" notification, with Talk, while a voice conversation runs
 *   or when ringing is off;
 * - it's a missed call when it arrives more than 90 seconds after Hermes placed it.
 *
 * A call the user turns down or misses is reported to Hermes, whose next turn in the chat hears it.
 */
class HermesCallRinger(
    private val context: Context,
    private val notifier: PushNotifier,
    private val calls: HermesCallsRepository,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val copy = HermesCallCopy(context)

    suspend fun incoming(notification: HermesNotification) {
        val call = notification.call
        val target = notification.target
        if (call == null || target == null) {
            notifier.show(notification)
            return
        }
        val answered = AnsweredCall(copy.intro(call), call.kind?.waitsOnUser == true)
        val summary = copy.summary(call)
        val late = clock() / MILLIS - call.issuedAt > LATE_SECONDS
        val answer = notifier.talkIntent(target, answered, notification.thread)
        when {
            late -> {
                notifier.showMissed(notification, copy.missedTitle(target.profile), summary, answered)
                record(notification, call, CallOutcome.Missed)
            }

            answer == null || !settings.ringsLikeCall.first() || VoiceSessions.current != null ->
                notifier.showTalk(notification, summary, answered)

            else -> HermesCallService.ring(
                context,
                HermesCallService.RingRequest(
                    notification,
                    call,
                    copy.callerName(target.profile, call.title),
                    summary,
                    answered,
                    answer,
                ),
            )
        }
    }

    /** The call couldn't ring: it waits as a notification instead. */
    fun talkInstead(request: HermesCallService.RingRequest) {
        notifier.showTalk(request.notification, request.summary, request.answered)
    }

    /** Turned down or rang out: a missed call, and Hermes hears of it. */
    fun unanswered(request: HermesCallService.RingRequest, declined: Boolean) {
        val target = request.notification.target ?: return
        notifier.showMissed(request.notification, copy.missedTitle(target.profile), request.summary, request.answered)
        record(request.notification, request.call, if (declined) CallOutcome.Declined else CallOutcome.Missed)
    }

    private fun record(notification: HermesNotification, call: HermesCall, outcome: CallOutcome) {
        val target = notification.target ?: return
        if (call.id.isEmpty() || call.sessionIds.isEmpty()) return
        scope.launch {
            calls.record(
                PendingCallOutcome(
                    dashboard = target.dashboardId.value,
                    profile = target.profile,
                    callId = call.id,
                    sessionIds = call.sessionIds,
                    outcome = outcome.key,
                    kind = call.kind?.key,
                    title = call.title,
                    reason = call.reason,
                    at = clock(),
                ),
            )
        }
    }

    private companion object {
        const val MILLIS = 1000L

        /** Older than this, a call doesn't ring: it's a missed call, as on iOS. */
        const val LATE_SECONDS = 90L
    }
}
