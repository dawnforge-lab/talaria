package lab.dawnforge.hermes.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.phonedata.health.HealthAccess
import lab.dawnforge.hermes.core.data.phonedata.health.HealthAvailability
import lab.dawnforge.hermes.core.data.phonedata.health.HealthCategory
import lab.dawnforge.hermes.core.data.phonedata.health.HealthHistory
import lab.dawnforge.hermes.core.data.phonedata.health.HealthPreferences
import lab.dawnforge.hermes.core.data.phonedata.health.HealthSync
import lab.dawnforge.hermes.core.data.phonedata.health.HealthSyncEngine
import lab.dawnforge.hermes.core.data.phonedata.health.HealthSyncState
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneDataDestination
import lab.dawnforge.hermes.core.data.session.ProfileRepository

data class HealthUi(
    val preferences: HealthPreferences = HealthPreferences(),
    val state: HealthSyncState = HealthSyncState(),
    /** Where turning sharing on sends it now: the active dashboard's host and profile. */
    val target: PhoneTarget? = null,
    val destinationHost: String? = null,
    val availability: HealthAvailability = HealthAvailability.Unavailable,
    val granted: Set<String> = emptySet(),
    /** What the current choices ask Health Connect for. */
    val wanted: Set<String> = emptySet(),
    val backgroundAvailable: Boolean = false,
    val working: Boolean = false,
    /** Turning off asked the host to delete and it couldn't. */
    val deleteFailed: Boolean = false,
) {
    val missing: Set<String> get() = wanted - granted
}

/**
 * Settings › Health, as iOS's: health data shared with one Hermes profile, a switch per category with
 * its detailed readings, how far back history reaches, Sync now, and turning it off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HealthViewModel @Inject constructor(
    private val engine: HealthSyncEngine,
    private val sync: HealthSync,
    private val access: HealthAccess,
    dashboards: DashboardRepository,
    profiles: ProfileRepository,
) : ViewModel() {
    private val local = MutableStateFlow(HealthUi())

    private val target = dashboards.activeDashboardId.filterNotNull().flatMapLatest { id ->
        profiles.activeProfile(id).combine(dashboards.dashboards) { profile, list ->
            list.firstOrNull { it.id == id }?.let { PhoneTarget(id.value, it.url.host, profile) }
        }
    }

    val state: StateFlow<HealthUi> = combine(engine.preferences, engine.state, target, local, dashboards.dashboards) {
            preferences,
            synced,
            target,
            local,
            list,
        ->
        local.copy(
            preferences = preferences,
            state = synced,
            target = target,
            destinationHost = preferences.destination?.let { dest ->
                list.firstOrNull { it.id.value == dest.dashboard }?.url?.host
            },
            wanted = if (local.availability == HealthAvailability.Available) {
                access.permissionsFor(if (preferences.isOn) preferences else preferences.withDefaults())
            } else {
                emptySet()
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), local.value)

    /** Back on the page (from Health Connect or Android's settings): what's allowed now. */
    fun recheck() {
        viewModelScope.launch {
            val availability = access.availability
            local.update {
                it.copy(
                    availability = availability,
                    granted = access.granted(),
                    backgroundAvailable = availability == HealthAvailability.Available && access.backgroundAvailable,
                )
            }
        }
    }

    /** What turning sharing on asks Health Connect for, with the default categories when none are chosen. */
    fun permissionsToTurnOn(): Set<String> = access.permissionsFor(state.value.preferences.withDefaults())

    /** Turns sharing on for the dashboard and profile in use (consent given, Health Connect asked). */
    fun turnOn() {
        val target = state.value.target ?: return
        viewModelScope.launch {
            engine.setPreferences {
                it.withDefaults().copy(destination = PhoneDataDestination(target.dashboard, target.profile))
            }
            recheck()
            sync.now()
        }
    }

    fun setCategory(category: HealthCategory, on: Boolean) = change {
        val categories = if (on) it.categories + category else it.categories - category
        it.copy(categories = categories, detailed = it.detailed.intersect(categories))
    }

    fun setDetailed(category: HealthCategory, on: Boolean) = change {
        it.copy(detailed = if (on) it.detailed + category else it.detailed - category)
    }

    fun setHistory(history: HealthHistory) = change { it.copy(history = history) }

    fun syncNow() = sync.now()

    /** Stops sharing; with [delete], what this phone sent is deleted from the host first. */
    fun turnOff(delete: Boolean) {
        local.update { it.copy(working = true, deleteFailed = false) }
        viewModelScope.launch {
            val deleted = engine.turnOff(deleteFromHost = delete)
            local.update { it.copy(working = false, deleteFailed = delete && !deleted) }
        }
    }

    private fun change(edit: (HealthPreferences) -> HealthPreferences) {
        viewModelScope.launch {
            if (engine.setPreferences(edit).isOn) sync.now()
        }
    }

    private fun HealthPreferences.withDefaults(): HealthPreferences =
        if (categories.isEmpty()) copy(categories = HealthPreferences.DEFAULT_CATEGORIES) else this

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
