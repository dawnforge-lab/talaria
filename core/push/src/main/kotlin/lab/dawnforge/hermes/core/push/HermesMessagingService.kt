package lab.dawnforge.hermes.core.push

import android.annotation.SuppressLint
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import lab.dawnforge.hermes.core.data.di.ApplicationScope
import lab.dawnforge.hermes.core.data.phonedata.location.LocateService
import lab.dawnforge.hermes.core.data.phonedata.location.LocationSharing
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneSync
import lab.dawnforge.hermes.core.data.plugin.PresentedConfirms
import lab.dawnforge.hermes.core.push.calls.HermesCallRinger
import lab.dawnforge.hermes.core.push.e2e.E2EException
import lab.dawnforge.hermes.core.push.e2e.NotificationE2E
import lab.dawnforge.hermes.core.push.live.LiveTurnUpdate
import lab.dawnforge.hermes.core.push.live.LiveTurns

/** Data messages from the Talaria relay, and changes to this phone's Firebase registration. */
// The rule predates FCM's installation-id registration: onRegistered() replaces the deprecated onNewToken().
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
@AndroidEntryPoint
class HermesMessagingService : FirebaseMessagingService() {
    @Inject
    lateinit var store: PushStore

    @Inject
    lateinit var setup: PushSetup

    @Inject
    lateinit var challenges: TokenChallengeInbox

    @Inject
    lateinit var notifier: PushNotifier

    @Inject
    lateinit var confirms: PresentedConfirms

    @Inject
    lateinit var calls: HermesCallRinger

    @Inject
    lateinit var liveTurns: LiveTurns

    @Inject
    lateinit var phoneSync: PhoneSync

    @Inject
    lateinit var location: LocationSharing

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    /** The phone registered, or its installation id changed (an app data reset, say). */
    override fun onRegistered(fid: String) {
        // The relay needs a fresh proof, which arrives as a message to this same service: it can't
        // be awaited here, where messages are delivered one at a time.
        scope.launch { setup.onNewToken(fid) }
    }

    // FCM calls this on its own background thread and gives it a few seconds, so blocking is fine.
    override fun onMessageReceived(message: RemoteMessage) = runBlocking {
        // A running turn's progress: coarse and unsealed, it updates the live notification.
        if (message.data["category"] == LiveTurnUpdate.CATEGORY) {
            liveTurns.onPush(message.data)
            return@runBlocking
        }
        // Hermes' calendar and alarm changes come with the phone's next pass, whether the push shows
        // (one that waits on the user) or is silent (one that goes in without asking).
        if (routingType(message.data) == CONFIRM_TYPE) phoneSync.now()
        // Hermes asking where the phone is now: answered in the background, nothing shown.
        if (routingType(message.data) == LOCATE_TYPE) {
            IncomingPushReader.readLocate(
                data = message.data,
                installationId = store.current().installationId,
                nowSeconds = System.currentTimeMillis() / 1000,
            ) { gatewayId ->
                keysFor(gatewayId)
            }?.takeIf { location.answersLocate(it.dashboardId, it.profile) }?.let { request ->
                // A fresh position needs a location foreground service from the background; should
                // Android refuse one, the answer is tried here.
                if (!LocateService.start(
                        this@HermesMessagingService,
                        request.dashboardId,
                        request.profile,
                        request.locateId,
                    )
                ) {
                    location.answerLocate(request.dashboardId, request.profile, request.locateId)
                }
            }
            return@runBlocking
        }
        val incoming = IncomingPushReader.read(
            data = message.data,
            installationId = store.current().installationId,
            nowSeconds = System.currentTimeMillis() / 1000,
        ) { gatewayId -> keysFor(gatewayId) }
        when (incoming) {
            is IncomingPush.Challenge -> challenges.offer(incoming.nonce)

            is IncomingPush.Show -> {
                val id = incoming.messageId
                val first = id == null || store.firstSighting(id)
                when {
                    !first -> Unit

                    // A call from Hermes rings, or comes as a notification to talk.
                    incoming.notification.kind == NotificationKind.Call -> calls.incoming(incoming.notification)

                    else -> notifier.show(incoming.notification)
                }
                // A confirmation: on screen now if the app shows its dashboard and profile.
                if (incoming.notification.type == CONFIRM_TYPE) {
                    val target = incoming.notification.target
                    confirms.woken(target?.dashboardId, target?.profile)
                }
            }

            IncomingPush.Ignore -> Unit
        }
    }

    private fun routingType(data: Map<String, String>): String? = data["talaria"]?.let { text ->
        runCatching {
            (Json.parseToJsonElement(text) as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private companion object {
        const val CONFIRM_TYPE = "phone.changes"
        const val LOCATE_TYPE = "location.requested"
    }

    private suspend fun keysFor(gatewayId: String): PairingKeys? {
        val pairing = store.pairing(gatewayId) ?: return null
        val secret = store.secret(gatewayId) ?: return null
        val keys = try {
            NotificationE2E.deriveKeys(pairing.kid, secret)
        } catch (_: E2EException) {
            return null
        }
        return PairingKeys(pairing.dashboardId, pairing.profile, keys, pairing.preferences)
    }
}
