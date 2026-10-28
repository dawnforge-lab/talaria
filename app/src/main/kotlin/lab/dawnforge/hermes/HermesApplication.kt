package lab.dawnforge.hermes

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.di.ApplicationScope
import lab.dawnforge.hermes.core.data.gateway.AppForeground
import lab.dawnforge.hermes.core.data.gateway.FinishWork
import lab.dawnforge.hermes.core.data.gateway.FinishWorkService
import lab.dawnforge.hermes.core.data.gateway.GatewayManager
import lab.dawnforge.hermes.core.data.gateway.NetworkAvailability
import lab.dawnforge.hermes.core.data.gateway.WorkOnItsWay
import lab.dawnforge.hermes.core.data.phonedata.health.HealthSync
import lab.dawnforge.hermes.core.data.phonedata.location.LocationSharing
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneSync
import lab.dawnforge.hermes.core.data.plugin.HermesCallsRepository
import lab.dawnforge.hermes.core.push.PushNotifier
import lab.dawnforge.hermes.core.push.PushSetup
import lab.dawnforge.hermes.core.push.live.LiveTurns

@HiltAndroidApp
class HermesApplication : Application() {
    @Inject
    lateinit var foreground: AppForeground

    @Inject
    lateinit var network: NetworkAvailability

    @Inject
    lateinit var gateway: GatewayManager

    @Inject
    lateinit var onItsWay: WorkOnItsWay

    // Injecting it starts Firebase, before FCM hands this process a message.
    @Inject
    lateinit var push: PushSetup

    @Inject
    lateinit var notifier: PushNotifier

    @Inject
    lateinit var calls: HermesCallsRepository

    @Inject
    lateinit var liveTurns: LiveTurns

    @Inject
    lateinit var phoneSync: PhoneSync

    @Inject
    lateinit var location: LocationSharing

    @Inject
    lateinit var health: HealthSync

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        foreground.register()
        network.register()
        gateway.start()
        // A send or a reply still on its way when the app leaves the screen is finished out of sight.
        FinishWork(scope, foreground.isForeground, onItsWay.held) { FinishWorkService.start(this) }.follow()
        // The calendar and phone go to Hermes while the user shares them.
        phoneSync.start()
        // Location's background signals are set up again, and it goes when the app opens.
        location.start()
        // Health data goes to Hermes when the app opens and every hour while it's shared.
        health.start()
        notifier.createChannels()
        // A token change the relay missed (the app was killed mid-update) goes out now.
        scope.launch { push.syncToken() }
        // Calls from Hermes declined or missed while the host couldn't hear it go out when the app is back.
        scope.launch {
            foreground.isForeground.collect {
                if (it) {
                    calls.deliver()
                    // Back in the app: the chat says what a live turn's notification would.
                    liveTurns.onForeground()
                }
            }
        }
    }
}
