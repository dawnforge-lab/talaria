package lab.dawnforge.hermes.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.phonedata.location.FoundPlace
import lab.dawnforge.hermes.core.data.phonedata.location.LocationAccess
import lab.dawnforge.hermes.core.data.phonedata.location.LocationConfig
import lab.dawnforge.hermes.core.data.phonedata.location.LocationHostStatus
import lab.dawnforge.hermes.core.data.phonedata.location.LocationPrecision
import lab.dawnforge.hermes.core.data.phonedata.location.LocationRead
import lab.dawnforge.hermes.core.data.phonedata.location.LocationRule
import lab.dawnforge.hermes.core.data.phonedata.location.LocationSearchFailed
import lab.dawnforge.hermes.core.data.phonedata.location.LocationSettings
import lab.dawnforge.hermes.core.data.phonedata.location.LocationSharing
import lab.dawnforge.hermes.core.data.phonedata.location.LocationSyncState
import lab.dawnforge.hermes.core.data.phonedata.location.RawFix
import lab.dawnforge.hermes.core.data.phonedata.location.SavedPlace
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneDataDestination
import lab.dawnforge.hermes.core.data.session.ProfileRepository
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** What the host says about the location it keeps; null until read, or while it can't be reached. */
data class LocationFromHost(
    val status: LocationHostStatus? = null,
    val reads: List<LocationRead> = emptyList(),
    val rules: List<LocationRule> = emptyList(),
    val unreachable: Boolean = false,
)

data class LocationUi(
    val config: LocationConfig = LocationConfig(),
    val state: LocationSyncState = LocationSyncState(),
    /** Where turning sharing on sends it now: the active dashboard's host and profile. */
    val target: PhoneTarget? = null,
    /** The host sharing goes to, as the user knows it. */
    val destinationHost: String? = null,
    val access: LocationAccess = LocationAccess(whileInUse = false, approximateOnly = false, always = false),
    val host: LocationFromHost = LocationFromHost(),
    val working: Boolean = false,
    /** Rules whose delete is on its way. */
    val deletingRules: Set<String> = emptySet(),
    val failure: ConnectionFailure? = null,
    val nowMs: Long = 0,
) {
    val settings: LocationSettings get() = config.settings
    val paused: Boolean get() = settings.isPaused(nowMs)
}

/**
 * Settings › Location, as iOS's: sharing where the phone is with one Hermes profile. Two layers,
 * each its own choice with its own detail, saved places, pause, how long the host keeps history,
 * every time Hermes read the location, the place rules it set up, and deleting the host's copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LocationViewModel @Inject constructor(
    private val sharing: LocationSharing,
    dashboards: DashboardRepository,
    profiles: ProfileRepository,
) : ViewModel() {
    private val local = MutableStateFlow(LocationUi(access = sharing.access, nowMs = System.currentTimeMillis()))

    private val target = dashboards.activeDashboardId.filterNotNull().flatMapLatest { id ->
        profiles.activeProfile(id).combine(dashboards.dashboards) { profile, list ->
            list.firstOrNull { it.id == id }?.let { PhoneTarget(id.value, it.url.host, profile) }
        }
    }

    val state: StateFlow<LocationUi> = combine(sharing.config, sharing.state, target, local, dashboards.dashboards) {
            config,
            synced,
            target,
            local,
            list,
        ->
        local.copy(
            config = config,
            state = synced,
            target = target,
            destinationHost = config.destination?.let { dest ->
                list.firstOrNull { it.id.value == dest.dashboard }?.url?.host
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), local.value)

    /** Back on the page (from a permission dialog or Android's settings): what's allowed now, and the host's view. */
    fun recheck() {
        local.update { it.copy(access = sharing.access, nowMs = System.currentTimeMillis()) }
        viewModelScope.launch {
            sharing.clearExpiredPause()
            loadFromHost()
        }
    }

    private suspend fun loadFromHost() {
        if (sharing.config.first().destination == null) {
            local.update { it.copy(host = LocationFromHost()) }
            return
        }
        val status = sharing.status()
        val reads = sharing.reads()
        val rules = sharing.rules()
        val unreachable = listOf(status, reads, rules).any { it is NetResult.Failure }
        local.update {
            it.copy(
                host = LocationFromHost(
                    status = (status as? NetResult.Success)?.value,
                    reads = (reads as? NetResult.Success)?.value ?: it.host.reads,
                    rules = (rules as? NetResult.Success)?.value ?: it.host.rules,
                    unreachable = unreachable,
                ),
            )
        }
    }

    /** Turns a layer on for the dashboard and profile in use (consent given, Android's permission asked). */
    fun turnOn(layer: LocationSharing.Layer) {
        val target = state.value.target ?: return
        work { sharing.turnOn(layer, PhoneDataDestination(target.dashboard, target.profile)) }
    }

    fun turnOff(layer: LocationSharing.Layer) = work { sharing.turnOff(layer) }

    fun setPrecision(layer: LocationSharing.Layer, precision: LocationPrecision) = work {
        sharing.setPrecision(layer, precision)
    }

    fun setRetention(days: Int) = work { sharing.setRetention(days) }

    fun pauseForAnHour() = work { sharing.pause(System.currentTimeMillis() + HOUR_MS) }

    /** Until 07:00 tomorrow, local time. */
    fun pauseUntilTomorrow() = work {
        val zone = ZoneId.systemDefault()
        sharing.pause(
            LocalDate.now(
                zone,
            ).plusDays(1).atTime(LocalTime.of(MORNING_HOUR, 0)).atZone(zone).toInstant().toEpochMilli(),
        )
    }

    fun pauseUntilResumed() = work { sharing.pause(LocationSettings.FOREVER) }

    fun resume() = work { sharing.pause(null) }

    fun savePlace(place: SavedPlace) = work { sharing.savePlace(place) }

    fun removePlace(id: String) = work { sharing.removePlace(id) }

    fun deleteRule(id: String) {
        if (id in state.value.deletingRules) return
        local.update { it.copy(deletingRules = it.deletingRules + id) }
        viewModelScope.launch {
            when (val result = sharing.deleteRule(id)) {
                is NetResult.Failure -> local.update { it.copy(failure = result.failure) }

                else -> local.update { ui ->
                    ui.copy(host = ui.host.copy(rules = ui.host.rules.filterNot { it.id == id }))
                }
            }
            local.update { it.copy(deletingRules = it.deletingRules - id) }
        }
    }

    /** Deletes this phone's location data on the host, or everything the profile has there. */
    fun wipe(everything: Boolean) = work {
        when (val result = sharing.wipe(everything)) {
            is NetResult.Failure -> local.update { it.copy(failure = result.failure) }

            is NetResult.Success -> local.update { ui ->
                ui.copy(
                    host = ui.host.copy(reads = emptyList(), rules = if (everything) emptyList() else ui.host.rules),
                )
            }
        }
    }

    /** Where the phone is now, for the place editor. */
    suspend fun here(): RawFix? = sharing.here()

    /** Places matching [query], for the place editor; null when the search couldn't run. */
    suspend fun search(query: String): List<FoundPlace>? = try {
        sharing.search(query)
    } catch (_: LocationSearchFailed) {
        null
    }

    private fun work(block: suspend () -> Unit) {
        local.update { it.copy(working = true, failure = null) }
        viewModelScope.launch {
            try {
                block()
                local.update { it.copy(access = sharing.access, nowMs = System.currentTimeMillis()) }
                loadFromHost()
            } finally {
                local.update { it.copy(working = false) }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val HOUR_MS = 3_600_000L
        const val MORNING_HOUR = 7
    }
}
