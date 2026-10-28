package lab.dawnforge.hermes.core.push.calls

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.telecom.DisconnectCause
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.plugin.AnsweredCall
import lab.dawnforge.hermes.core.push.HermesNotification
import lab.dawnforge.hermes.core.push.PushNotifier

/**
 * A call from Hermes ringing, as the phone's own calls ring: a self-managed Telecom call (so the
 * phone's call handling, a headset or a car see it) with its incoming-call notification, in a
 * phone-call foreground service. It ends answered (the app opens the chat and talks), turned down,
 * or missed after 45 seconds; the last two are reported to Hermes and leave a missed-call
 * notification. One call rings at a time.
 */
@AndroidEntryPoint
class HermesCallService : Service() {
    @Inject
    lateinit var notifier: PushNotifier

    @Inject
    lateinit var ringer: HermesCallRinger

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ringing: Ring? = null

    private class Ring(val request: RingRequest) {
        var control: CallControlScope? = null
        var timeout: Job? = null
        var telecom: Job? = null
        var ended = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> pending?.let { request ->
                pending = null
                ring(request)
            }

            ACTION_ANSWERED -> end(Ending.Answered)

            ACTION_DECLINE -> end(Ending.Declined)
        }
        if (ringing == null) stopSelf()
        return START_NOT_STICKY
    }

    private enum class Ending { Answered, Declined, Missed, Elsewhere }

    private fun ring(request: RingRequest) {
        if (ringing != null) {
            // One call rings at a time: the next one waits as a notification.
            ringer.talkInstead(request)
            return
        }
        val decline = PendingIntent.getService(
            this,
            DECLINE_REQUEST,
            Intent(this, HermesCallService::class.java).setAction(ACTION_DECLINE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val fullScreen = PendingIntent.getActivity(
            this,
            SCREEN_REQUEST,
            Intent(ACTION_INCOMING)
                .setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_CALLER, request.caller)
                .putExtra(EXTRA_SUMMARY, request.summary)
                .putExtra(EXTRA_ANSWER, request.answer)
                .putExtra(EXTRA_DECLINE, decline),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val shown = notifier.ringing(request.caller, request.summary, request.answer, decline, fullScreen)
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, shown, foregroundType())
        }
        if (started.isFailure) {
            // Android refused the call service (no permission, or not allowed from here): talk instead.
            ringer.talkInstead(request)
            return
        }
        val ring = Ring(request)
        ringing = ring
        _ringingNow.value = true
        ring.timeout = scope.launch {
            delay(RING_MS)
            end(Ending.Missed)
        }
        ring.telecom = scope.launch { addToTelecom(ring) }
    }

    /** Telecom knows the call, so a headset, a car or a watch can answer it; where it can't, it rings anyway. */
    @SuppressLint("MissingPermission")
    private suspend fun addToTelecom(ring: Ring) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val calls = CallsManager(this)
            calls.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
            calls.addCall(
                CallAttributesCompat(
                    displayName = ring.request.caller,
                    address = Uri.parse("hermes:${ring.request.notification.target?.profile.orEmpty()}"),
                    direction = CallAttributesCompat.DIRECTION_INCOMING,
                    callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                ),
                // Answered on a headset, in a car or on a watch: as if Answer was tapped.
                onAnswer = { scope.launch { answerElsewhere(ring) } },
                onDisconnect = { scope.launch { if (ringing === ring) end(Ending.Elsewhere) } },
                onSetActive = {},
                onSetInactive = {},
            ) {
                ring.control = this
                if (ring.ended) launch { disconnect(DisconnectCause(DisconnectCause.LOCAL)) }
            }
        }
    }

    private fun answerElsewhere(ring: Ring) {
        if (ringing !== ring) return
        runCatching { ring.request.answer.send() }
        end(Ending.Answered)
    }

    private fun end(how: Ending) {
        val ring = ringing ?: return
        ringing = null
        _ringingNow.value = false
        ring.ended = true
        ring.timeout?.cancel()
        ring.control?.let { control ->
            val cause = when (how) {
                Ending.Answered, Ending.Elsewhere -> DisconnectCause.LOCAL
                Ending.Declined -> DisconnectCause.REJECTED
                Ending.Missed -> DisconnectCause.MISSED
            }
            control.launch { runCatching { control.disconnect(DisconnectCause(cause)) } }
        }
        when (how) {
            Ending.Declined -> ringer.unanswered(ring.request, declined = true)
            Ending.Missed -> ringer.unanswered(ring.request, declined = false)
            Ending.Answered, Ending.Elsewhere -> Unit
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ringing?.let { end(Ending.Missed) }
        scope.cancel()
        super.onDestroy()
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0

    /** A call to ring: what the notification and the call screen show, and where Answer leads. */
    class RingRequest(
        val notification: HermesNotification,
        val call: HermesCall,
        val caller: String,
        val summary: String,
        val answered: AnsweredCall,
        val answer: PendingIntent,
    )

    companion object {
        /** The full-screen call screen's action, answered by the app's own activity. */
        const val ACTION_INCOMING = "lab.dawnforge.hermes.call.INCOMING"
        const val EXTRA_CALLER = "lab.dawnforge.hermes.call.CALLER"
        const val EXTRA_SUMMARY = "lab.dawnforge.hermes.call.SUMMARY"
        const val EXTRA_ANSWER = "lab.dawnforge.hermes.call.ANSWER"
        const val EXTRA_DECLINE = "lab.dawnforge.hermes.call.DECLINE"
        private const val ACTION_RING = "lab.dawnforge.hermes.call.RING"
        private const val ACTION_ANSWERED = "lab.dawnforge.hermes.call.ANSWERED"
        private const val ACTION_DECLINE = "lab.dawnforge.hermes.call.DECLINE_CALL"
        private const val NOTIFICATION_ID = 7401
        private const val DECLINE_REQUEST = 7402
        private const val SCREEN_REQUEST = 7403
        private const val RING_MS = 45_000L

        private val _ringingNow = MutableStateFlow(false)

        /** Whether a call rings now; the call screen closes when it stops. */
        val ringingNow: StateFlow<Boolean> = _ringingNow.asStateFlow()

        /** The call waiting for the service to start, handed over in this process. */
        @Volatile
        private var pending: RingRequest? = null

        fun ring(context: Context, request: RingRequest) {
            pending = request
            ContextCompat.startForegroundService(
                context,
                Intent(context, HermesCallService::class.java).setAction(ACTION_RING),
            )
        }

        /** The user answered (Answer, or Talk on its call screen): the ringing stops. */
        fun answered(context: Context) {
            runCatching {
                context.startService(Intent(context, HermesCallService::class.java).setAction(ACTION_ANSWERED))
            }
        }
    }
}
