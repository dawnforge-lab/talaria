package lab.dawnforge.hermes.core.push.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import lab.dawnforge.hermes.core.data.dashboard.DashboardDataOwner
import lab.dawnforge.hermes.core.data.di.ApplicationScope
import lab.dawnforge.hermes.core.data.gateway.AppForeground
import lab.dawnforge.hermes.core.data.gateway.GatewayManager
import lab.dawnforge.hermes.core.data.live.LiveTurnStarter
import lab.dawnforge.hermes.core.data.plugin.HermesCallsRepository
import lab.dawnforge.hermes.core.data.secrets.SecretCipher
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.data.settings.AppSettings
import lab.dawnforge.hermes.core.push.DashboardPushPluginApi
import lab.dawnforge.hermes.core.push.FirebasePushTokens
import lab.dawnforge.hermes.core.push.GatewayCall
import lab.dawnforge.hermes.core.push.PushAnswers
import lab.dawnforge.hermes.core.push.PushConfig
import lab.dawnforge.hermes.core.push.PushNotifier
import lab.dawnforge.hermes.core.push.PushSetup
import lab.dawnforge.hermes.core.push.PushStore
import lab.dawnforge.hermes.core.push.PushTokens
import lab.dawnforge.hermes.core.push.TokenChallengeInbox
import lab.dawnforge.hermes.core.push.calls.HermesCallRinger
import lab.dawnforge.hermes.core.push.live.LiveTurnNotifier
import lab.dawnforge.hermes.core.push.live.LiveTurns
import lab.dawnforge.hermes.core.push.relay.RelayClient
import okhttp3.OkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
private annotation class PushPreferences

/** Push needs one thing from the app: its [PushConfig]. */
@Module
@InstallIn(SingletonComponent::class)
internal object PushModule {
    @Provides
    @Singleton
    @PushPreferences
    fun pushPreferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("push") }

    @Provides
    @Singleton
    fun pushStore(@PushPreferences store: DataStore<Preferences>, cipher: SecretCipher) = PushStore(store, cipher)

    @Provides
    @Singleton
    fun relayClient(base: OkHttpClient) = RelayClient(base.newBuilder().followRedirects(false).build())

    @Provides
    @Singleton
    fun pushTokens(@ApplicationContext context: Context, config: PushConfig): PushTokens =
        FirebasePushTokens(context, config.firebase)

    @Provides
    @Singleton
    fun tokenChallenges() = TokenChallengeInbox()

    @Provides
    @Singleton
    fun pushNotifier(@ApplicationContext context: Context) = PushNotifier(context)

    @Provides
    @Singleton
    fun callRinger(
        @ApplicationContext context: Context,
        notifier: PushNotifier,
        calls: HermesCallsRepository,
        settings: AppSettings,
        @ApplicationScope scope: CoroutineScope,
    ) = HermesCallRinger(context, notifier, calls, settings, scope)

    @Provides
    @Singleton
    fun pushSetup(
        store: PushStore,
        relay: RelayClient,
        tokens: PushTokens,
        challenges: TokenChallengeInbox,
        requests: DashboardRequests,
        config: PushConfig,
    ) = PushSetup(store, relay, tokens, challenges, DashboardPushPluginApi(requests), config)

    /** Answers from a notification: approvals on the dashboard's gateway, questions through the relay. */
    @Provides
    @Singleton
    fun pushAnswers(store: PushStore, relay: RelayClient, gateway: GatewayManager, setup: PushSetup) = PushAnswers(
        store = store,
        relay = relay,
        gateway = GatewayCall { id, profile, method, params -> gateway.callOnce(id, profile, method, params) },
        relayUrl = { setup.relayUrl()?.let(RelayClient::relayUrl) },
    )

    @Provides
    @Singleton
    fun liveTurns(
        @ApplicationContext context: Context,
        store: PushStore,
        setup: PushSetup,
        relay: RelayClient,
        foreground: AppForeground,
        gateway: GatewayManager,
        @ApplicationScope scope: CoroutineScope,
    ) = LiveTurns(
        context,
        store,
        setup,
        relay,
        LiveTurnNotifier(context),
        foreground,
        scope,
        gateway.events,
        gateway.pendingRequests,
    )

    /** A turn sent in a chat is followed by its live notification. */
    @Provides
    fun liveTurnStarter(turns: LiveTurns): LiveTurnStarter = turns

    /** Signing out of a dashboard, or removing it, ends its notifications on this phone. */
    @Provides
    @IntoSet
    fun forgetPairings(setup: Lazy<PushSetup>) = DashboardDataOwner { id -> setup.get().forget(id) }
}
