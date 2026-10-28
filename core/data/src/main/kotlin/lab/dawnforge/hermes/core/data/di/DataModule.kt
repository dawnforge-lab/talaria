package lab.dawnforge.hermes.core.data.di

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import lab.dawnforge.hermes.core.data.bots.BotsRepository
import lab.dawnforge.hermes.core.data.cache.ChatCache
import lab.dawnforge.hermes.core.data.chat.AttachmentReader
import lab.dawnforge.hermes.core.data.chat.ChatMemory
import lab.dawnforge.hermes.core.data.chat.ChatRepository
import lab.dawnforge.hermes.core.data.chat.MediaRepository
import lab.dawnforge.hermes.core.data.cron.CronRepository
import lab.dawnforge.hermes.core.data.dashboard.AppUnlock
import lab.dawnforge.hermes.core.data.dashboard.DashboardDataOwner
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.dashboard.ProxyHeadersStore
import lab.dawnforge.hermes.core.data.gateway.AppForeground
import lab.dawnforge.hermes.core.data.gateway.ConnectionHolds
import lab.dawnforge.hermes.core.data.gateway.GatewayManager
import lab.dawnforge.hermes.core.data.gateway.NetworkAvailability
import lab.dawnforge.hermes.core.data.gateway.WorkOnItsWay
import lab.dawnforge.hermes.core.data.groups.GroupsRepository
import lab.dawnforge.hermes.core.data.phonedata.PhoneDataUploader
import lab.dawnforge.hermes.core.data.phonedata.alarms.AlarmBook
import lab.dawnforge.hermes.core.data.phonedata.health.HealthAccess
import lab.dawnforge.hermes.core.data.phonedata.health.HealthConnectSource
import lab.dawnforge.hermes.core.data.phonedata.health.HealthSync
import lab.dawnforge.hermes.core.data.phonedata.health.HealthSyncEngine
import lab.dawnforge.hermes.core.data.phonedata.location.DeviceConditions
import lab.dawnforge.hermes.core.data.phonedata.location.LocationFixes
import lab.dawnforge.hermes.core.data.phonedata.location.LocationSharing
import lab.dawnforge.hermes.core.data.phonedata.location.LocationSignals
import lab.dawnforge.hermes.core.data.phonedata.location.LocationSyncEngine
import lab.dawnforge.hermes.core.data.phonedata.phone.CalendarSource
import lab.dawnforge.hermes.core.data.phonedata.phone.CalendarWriter
import lab.dawnforge.hermes.core.data.phonedata.phone.DevicePhoneChanges
import lab.dawnforge.hermes.core.data.phonedata.phone.DevicePhoneSources
import lab.dawnforge.hermes.core.data.phonedata.phone.PeopleBook
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneStatusSource
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneSync
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneSyncEngine
import lab.dawnforge.hermes.core.data.plugin.AnsweredCalls
import lab.dawnforge.hermes.core.data.plugin.AppDevice
import lab.dawnforge.hermes.core.data.plugin.HermesCallsRepository
import lab.dawnforge.hermes.core.data.plugin.HermesChanges
import lab.dawnforge.hermes.core.data.plugin.MapsNavigator
import lab.dawnforge.hermes.core.data.plugin.PresentedConfirms
import lab.dawnforge.hermes.core.data.plugin.PushPluginRepository
import lab.dawnforge.hermes.core.data.profiles.ProfileGlows
import lab.dawnforge.hermes.core.data.secrets.SecretCipher
import lab.dawnforge.hermes.core.data.secrets.TinkSecretCipher
import lab.dawnforge.hermes.core.data.session.ChatListSettings
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.data.session.ProfileRepository
import lab.dawnforge.hermes.core.data.session.ProjectsRepository
import lab.dawnforge.hermes.core.data.session.SessionCookies
import lab.dawnforge.hermes.core.data.session.SessionRepository
import lab.dawnforge.hermes.core.data.session.WebViewCookieStores
import lab.dawnforge.hermes.core.data.settings.AppSettings
import lab.dawnforge.hermes.core.data.settings.CapabilitiesRepository
import lab.dawnforge.hermes.core.data.settings.ConnectorsRepository
import lab.dawnforge.hermes.core.data.settings.GatewayStatusRepository
import lab.dawnforge.hermes.core.data.settings.HermesSettingsRepository
import lab.dawnforge.hermes.core.data.settings.VoiceSettings
import lab.dawnforge.hermes.core.data.share.ChatShortcuts
import lab.dawnforge.hermes.core.data.signin.BrowserSignInKeepAlive
import lab.dawnforge.hermes.core.data.signin.ConnectionTest
import lab.dawnforge.hermes.core.data.signin.SignInService
import lab.dawnforge.hermes.core.data.tasks.KanbanEvents
import lab.dawnforge.hermes.core.data.tasks.KanbanRepository
import lab.dawnforge.hermes.core.data.voice.HermesSpeech
import lab.dawnforge.hermes.core.data.voice.SpeechReadiness
import lab.dawnforge.hermes.core.data.workspace.WorkspaceRepository
import lab.dawnforge.hermes.core.database.HermesDatabase
import lab.dawnforge.hermes.core.net.DashboardApi
import lab.dawnforge.hermes.core.net.DashboardAuthClient
import lab.dawnforge.hermes.core.net.DashboardClientFactory
import okhttp3.OkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
private annotation class SettingsStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
private annotation class SecretsStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
private annotation class PhoneDataStore

/** Work that outlives any screen, such as the gateway connection. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Declares the sets other modules may add to, so they can also stay empty. */
@Module
@InstallIn(SingletonComponent::class)
internal interface DataBindings {
    @Multibinds
    fun dashboardDataOwners(): Set<DashboardDataOwner>
}

@Module
@InstallIn(SingletonComponent::class)
internal object DataModule {
    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun appForeground() = AppForeground()

    @Provides
    @Singleton
    fun connectionHolds() = ConnectionHolds()

    @Provides
    @Singleton
    fun workOnItsWay() = WorkOnItsWay()

    @Provides
    @Singleton
    fun networkAvailability(@ApplicationContext context: Context, base: OkHttpClient) =
        NetworkAvailability(context) { base.connectionPool.evictAll() }

    @Provides
    @Singleton
    fun gatewayManager(
        @ApplicationScope scope: CoroutineScope,
        repository: DashboardRepository,
        signIn: SignInService,
        clients: DashboardClientFactory,
        foreground: AppForeground,
        holds: ConnectionHolds,
        network: NetworkAvailability,
    ) = GatewayManager(scope, repository, signIn, clients, foreground.isForeground, holds.held, network.isAvailable)

    /** One base client so every dashboard shares the connection pool and thread pool. */
    @Provides
    @Singleton
    fun baseHttpClient(): OkHttpClient = DashboardClientFactory.defaultBaseClient()

    /** Extra headers a reverse proxy asks for, by server origin; every dashboard client sends them. */
    @Provides
    @Singleton
    fun proxyHeaders(@SecretsStore secrets: DataStore<Preferences>, cipher: SecretCipher) =
        ProxyHeadersStore(secrets, cipher)

    @Provides
    @Singleton
    fun dashboardClientFactory(base: OkHttpClient, headers: ProxyHeadersStore) = DashboardClientFactory(base, headers)

    @Provides
    @Singleton
    fun dashboardAuthClient(factory: DashboardClientFactory) = DashboardAuthClient(factory)

    @Provides
    @Singleton
    @SettingsStore
    fun settingsStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("dashboards") }

    @Provides
    @Singleton
    @SecretsStore
    fun secretsStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("dashboard_secrets") }

    @Provides
    @Singleton
    fun secretCipher(@ApplicationContext context: Context, @IoDispatcher io: CoroutineDispatcher): SecretCipher =
        TinkSecretCipher(context, io)

    @Provides
    @Singleton
    fun dashboardRepository(
        @SettingsStore settings: DataStore<Preferences>,
        @SecretsStore secrets: DataStore<Preferences>,
        cipher: SecretCipher,
    ) = DashboardRepository(settings, secrets, cipher)

    /** The phone's unlock before a locked dashboard shows, once per start. */
    @Provides
    @Singleton
    fun appUnlock(repository: DashboardRepository) = AppUnlock(repository)

    @Provides
    @Singleton
    fun sessionCookies(
        repository: DashboardRepository,
        @ApplicationScope scope: CoroutineScope,
        browserStores: WebViewCookieStores,
    ) = SessionCookies(repository, scope, browserStores)

    @Provides
    @Singleton
    fun browserCookieStores(repository: DashboardRepository) =
        WebViewCookieStores { id -> repository.dashboard(id)?.url?.toString() }

    @Provides
    @Singleton
    fun signInService(
        auth: DashboardAuthClient,
        repository: DashboardRepository,
        cookies: SessionCookies,
        headers: ProxyHeadersStore,
        cache: ChatCache,
        owners: Set<@JvmSuppressWildcards DashboardDataOwner>,
        @ApplicationContext context: Context,
    ) = SignInService(
        auth,
        repository,
        cookies,
        forgetLocalData = { id ->
            cache.forget(id)
            owners.forEach { it.forget(id) }
        },
        keepAlive = BrowserSignInKeepAlive(context),
        forgetProxyHeaders = headers::forget,
    )

    @Provides
    fun connectionTest(signIn: SignInService) = ConnectionTest(signIn)

    @Provides
    @Singleton
    fun dashboardApi(factory: DashboardClientFactory) = DashboardApi(factory)

    @Provides
    @Singleton
    fun dashboardRequests(
        api: DashboardApi,
        repository: DashboardRepository,
        cookies: SessionCookies,
        signIn: SignInService,
    ) = DashboardRequests(api, repository, cookies, signIn)

    @Provides
    @Singleton
    fun profileRepository(requests: DashboardRequests, repository: DashboardRepository) =
        ProfileRepository(requests, repository)

    @Provides
    @Singleton
    fun sessionRepository(requests: DashboardRequests, gateway: GatewayManager) =
        SessionRepository(requests) { gateway.client.value }

    @Provides
    @Singleton
    fun projectsRepository(requests: DashboardRequests, gateway: GatewayManager): ProjectsRepository =
        ProjectsRepository(requests) { gateway.client.value }

    @Provides
    @Singleton
    fun chatListSettings(@SettingsStore settings: DataStore<Preferences>) = ChatListSettings(settings)

    @Provides
    @Singleton
    fun phoneDataUploader(
        requests: DashboardRequests,
        @SecretsStore secrets: DataStore<Preferences>,
        cipher: SecretCipher,
    ) = PhoneDataUploader(requests, secrets, cipher, deviceName())

    /** What the host shows this phone as: its maker and model ("Google Pixel 8"). */
    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.titlecase() }
        val model = Build.MODEL
        return (if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model").take(DEVICE_NAME_CHARS)
    }

    @Provides
    @Singleton
    @PhoneDataStore
    fun phoneDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("phone_data") }

    @Provides
    @Singleton
    fun alarmBook(@ApplicationContext context: Context, @PhoneDataStore store: DataStore<Preferences>) =
        AlarmBook(context, store)

    @Provides
    @Singleton
    fun peopleBook(@ApplicationContext context: Context, @PhoneDataStore store: DataStore<Preferences>) =
        PeopleBook(context, store)

    @Provides
    @Singleton
    fun phoneSyncEngine(
        uploader: PhoneDataUploader,
        @ApplicationContext context: Context,
        @PhoneDataStore store: DataStore<Preferences>,
        alarms: AlarmBook,
        people: PeopleBook,
    ): PhoneSyncEngine {
        val calendars = CalendarSource(context)
        return PhoneSyncEngine(
            uploader,
            DevicePhoneSources(calendars, PhoneStatusSource(context)),
            store,
            alarms = { alarms.wire() },
            contacts = { people.wire() },
            applier = DevicePhoneChanges(CalendarWriter(context, calendars), alarms),
        )
    }

    @Provides
    @Singleton
    fun phoneSync(
        @ApplicationContext context: Context,
        engine: PhoneSyncEngine,
        foreground: AppForeground,
        @ApplicationScope scope: CoroutineScope,
    ) = PhoneSync(context, engine, foreground.isForeground, scope)

    /** Removing the dashboard the phone's data goes to stops sending it. */
    @Provides
    @IntoSet
    fun forgetPhoneData(engine: dagger.Lazy<PhoneSyncEngine>): DashboardDataOwner =
        DashboardDataOwner { id -> engine.get().forget(id) }

    @Provides
    @Singleton
    fun healthAccess(@ApplicationContext context: Context) = HealthAccess(context)

    @Provides
    @Singleton
    fun healthSyncEngine(
        uploader: PhoneDataUploader,
        @ApplicationContext context: Context,
        @PhoneDataStore store: DataStore<Preferences>,
    ) = HealthSyncEngine(uploader, HealthConnectSource(context), store, deviceName = deviceName())

    @Provides
    @Singleton
    fun healthSync(
        @ApplicationContext context: Context,
        engine: HealthSyncEngine,
        access: HealthAccess,
        foreground: AppForeground,
        @ApplicationScope scope: CoroutineScope,
    ) = HealthSync(context, engine, access, foreground.isForeground, scope, conditions = { deviceConditions(context) })

    /** Removing the dashboard health data goes to stops sharing it there. */
    @Provides
    @IntoSet
    fun forgetHealth(engine: dagger.Lazy<HealthSyncEngine>): DashboardDataOwner =
        DashboardDataOwner { id -> engine.get().forget(id) }

    @Provides
    @Singleton
    fun locationSharing(
        uploader: PhoneDataUploader,
        @ApplicationContext context: Context,
        @PhoneDataStore store: DataStore<Preferences>,
        foreground: AppForeground,
        @ApplicationScope scope: CoroutineScope,
    ): LocationSharing {
        val fixes = LocationFixes(context)
        return LocationSharing(
            LocationSyncEngine(uploader, store),
            fixes,
            LocationSignals(context, fixes),
            foreground.isForeground,
            scope,
            conditions = { deviceConditions(context) },
        )
    }

    /** Removing the dashboard location goes to stops sharing it there. */
    @Provides
    @IntoSet
    fun forgetLocation(sharing: dagger.Lazy<LocationSharing>): DashboardDataOwner =
        DashboardDataOwner { id -> sharing.get().forget(id) }

    /** The power saver and the data saver, which make routine sends wait longer. */
    private fun deviceConditions(context: Context): DeviceConditions {
        val power = context.getSystemService(PowerManager::class.java)
        val network = context.getSystemService(ConnectivityManager::class.java)
        return DeviceConditions(
            powerSaver = power?.isPowerSaveMode == true,
            dataSaver = network?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
        )
    }

    /** Signing out of a dashboard, or removing it, forgets its starred chats. */
    @Provides
    @IntoSet
    fun forgetChatListSettings(settings: ChatListSettings): DashboardDataOwner = settings

    @Provides
    @Singleton
    fun appSettings(@SettingsStore settings: DataStore<Preferences>) = AppSettings(settings)

    /** Signing out of a dashboard, or removing it, forgets what its model picker hides. */
    @Provides
    @IntoSet
    fun forgetAppSettings(settings: AppSettings): DashboardDataOwner = settings

    @Provides
    @Singleton
    fun voiceSettings(@SettingsStore settings: DataStore<Preferences>) = VoiceSettings(settings)

    /** Signing out of a dashboard, or removing it, forgets its profiles' voice settings. */
    @Provides
    @IntoSet
    fun forgetVoiceSettings(settings: VoiceSettings): DashboardDataOwner = settings

    @Provides
    @Singleton
    fun hermesSpeech(
        dashboards: DashboardRepository,
        clients: DashboardClientFactory,
        signIn: SignInService,
        requests: DashboardRequests,
    ) = HermesSpeech(dashboards, clients, signIn, requests)

    @Provides
    @Singleton
    fun speechReadiness(capabilities: CapabilitiesRepository, settings: HermesSettingsRepository) =
        SpeechReadiness(capabilities, settings)

    @Provides
    @Singleton
    fun cronRepository(requests: DashboardRequests) = CronRepository(requests)

    @Provides
    @Singleton
    fun chatRepository(requests: DashboardRequests, gateway: GatewayManager) =
        ChatRepository(requests) { gateway.client.value }

    @Provides
    @Singleton
    fun chatMemory() = ChatMemory()

    @Provides
    @Singleton
    fun mediaRepository(requests: DashboardRequests) = MediaRepository(requests)

    @Provides
    @Singleton
    fun hermesSettingsRepository(requests: DashboardRequests, gateway: GatewayManager) =
        HermesSettingsRepository(requests) { gateway.client.value }

    @Provides
    @Singleton
    fun gatewayStatusRepository(requests: DashboardRequests) = GatewayStatusRepository(requests)

    @Provides
    @Singleton
    fun capabilitiesRepository(requests: DashboardRequests) = CapabilitiesRepository(requests)

    @Provides
    @Singleton
    fun connectorsRepository(requests: DashboardRequests) = ConnectorsRepository(requests)

    @Provides
    @Singleton
    fun kanbanRepository(requests: DashboardRequests) = KanbanRepository(requests)

    @Provides
    @Singleton
    fun workspaceRepository(requests: DashboardRequests): WorkspaceRepository = WorkspaceRepository(requests)

    @Provides
    @Singleton
    fun pushPluginRepository(
        requests: DashboardRequests,
        connectors: ConnectorsRepository,
        base: OkHttpClient,
    ): PushPluginRepository = PushPluginRepository(requests, connectors, base)

    /** This phone as a host's plugin sees it: its name in Android's settings, and the app's version. */
    @Provides
    @Singleton
    fun appDevice(@ApplicationContext context: Context): AppDevice {
        val name = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf(String::isNotBlank)
            ?: Build.MODEL
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        return AppDevice(name, version)
    }

    @Provides
    @Singleton
    fun presentedConfirms(
        dashboards: DashboardRepository,
        plugin: PushPluginRepository,
        settings: AppSettings,
        device: AppDevice,
        foreground: AppForeground,
        @ApplicationScope scope: CoroutineScope,
        @ApplicationContext context: Context,
        location: dagger.Lazy<LocationSharing>,
    ): PresentedConfirms {
        val navigator = MapsNavigator(context, foreground.isForeground) { location.get().config.first().places }
        return PresentedConfirms(dashboards, plugin, settings, device, foreground.isForeground, scope, navigator)
    }

    @Provides
    @Singleton
    fun hermesChanges(
        dashboards: DashboardRepository,
        plugin: PushPluginRepository,
        app: AppSettings,
        voice: VoiceSettings,
        device: AppDevice,
        foreground: AppForeground,
        @ApplicationScope scope: CoroutineScope,
    ) = HermesChanges(dashboards, plugin, app, voice, device, foreground.isForeground, scope)

    @Provides
    @Singleton
    fun hermesCalls(@SettingsStore settings: DataStore<Preferences>, requests: dagger.Lazy<DashboardRequests>) =
        HermesCallsRepository({ requests.get() }, settings)

    @Provides
    @Singleton
    fun answeredCalls() = AnsweredCalls()

    /** Signing out of a dashboard, or removing it, forgets its calls the host hasn't heard were missed. */
    @Provides
    @IntoSet
    fun forgetHermesCalls(calls: HermesCallsRepository): DashboardDataOwner = calls

    @Provides
    @Singleton
    fun bots(requests: DashboardRequests, gateway: GatewayManager) = BotsRepository(requests) { gateway.client.value }

    @Provides
    @Singleton
    fun profileGlows(repository: DashboardRepository, bots: BotsRepository) = ProfileGlows(repository, bots)

    @Provides
    @Singleton
    fun groups(requests: DashboardRequests, gateway: GatewayManager) =
        GroupsRepository(requests) { gateway.client.value }

    @Provides
    @Singleton
    fun kanbanEvents(dashboards: DashboardRepository, clients: DashboardClientFactory, signIn: SignInService) =
        KanbanEvents(dashboards, clients, signIn)

    @Provides
    @Singleton
    fun attachmentReader(@ApplicationContext context: Context) = AttachmentReader(context.contentResolver)

    @Provides
    @Singleton
    fun chatShortcuts(@ApplicationContext context: Context) = ChatShortcuts(context)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context) = HermesDatabase.create(context)

    @Provides
    @Singleton
    fun chatCache(database: HermesDatabase) = ChatCache(database.cache())
}

private const val DEVICE_NAME_CHARS = 60
