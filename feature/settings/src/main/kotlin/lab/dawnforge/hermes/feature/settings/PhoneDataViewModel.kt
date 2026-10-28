package lab.dawnforge.hermes.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.phonedata.alarms.AlarmBook
import lab.dawnforge.hermes.core.data.phonedata.phone.CalendarAccess
import lab.dawnforge.hermes.core.data.phonedata.phone.CalendarSource
import lab.dawnforge.hermes.core.data.phonedata.phone.PeopleBook
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneDataDestination
import lab.dawnforge.hermes.core.data.phonedata.phone.PhonePerson
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneSharing
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneSyncEngine
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneSyncOutcome
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneSyncState
import lab.dawnforge.hermes.core.data.session.ProfileRepository
import lab.dawnforge.hermes.core.net.ConnectionFailure

/** Where the data would go now: the active dashboard's host and profile. */
data class PhoneTarget(val dashboard: String, val host: String, val profile: String)

data class PhoneDataUi(
    val sharing: PhoneSharing = PhoneSharing(),
    val state: PhoneSyncState = PhoneSyncState(),
    val target: PhoneTarget? = null,
    val calendars: List<CalendarSource.Calendar> = emptyList(),
    val canRead: Boolean = false,
    val canWrite: Boolean = false,
    val syncing: Boolean = false,
    val failure: ConnectionFailure? = null,
    /** The dashboard's host it goes to, as the user knows it. */
    val destinationHost: String? = null,
    /** Android lets this app ring alarms at exact times. */
    val canRingAlarms: Boolean = false,
    val people: List<PhonePerson> = emptyList(),
)

/**
 * Settings › Calendar & phone, as iOS's: what of the calendar and phone goes to Hermes, each its
 * own choice, kept on the phone; sealed straight to one profile's host, never through the relay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PhoneDataViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val engine: PhoneSyncEngine,
    private val dashboards: DashboardRepository,
    private val profiles: ProfileRepository,
    private val alarmBook: AlarmBook,
    private val peopleBook: PeopleBook,
) : ViewModel() {
    private val calendarSource = CalendarSource(context)
    private val local = MutableStateFlow(PhoneDataUi())

    private val target = dashboards.activeDashboardId.filterNotNull().flatMapLatest { id ->
        profiles.activeProfile(id).combine(dashboards.dashboards) { profile, list ->
            list.firstOrNull { it.id == id }?.let { PhoneTarget(id.value, it.url.host, profile) }
        }
    }

    private val withPeople = combine(local, peopleBook.people) { local, people -> local.copy(people = people) }

    val state: StateFlow<PhoneDataUi> = combine(
        engine.sharing,
        engine.state,
        target,
        withPeople,
        dashboards.dashboards,
    ) {
            sharing,
            synced,
            target,
            local,
            list,
        ->
        local.copy(
            sharing = sharing,
            state = synced,
            target = target,
            destinationHost = sharing.destination?.let { dest ->
                list.firstOrNull { it.id.value == dest.dashboard }?.url?.host
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PhoneDataUi())

    init {
        recheck()
    }

    /** Back on the page (from the permission dialog or system settings): what the phone allows now. */
    fun recheck() = local.update {
        it.copy(
            canRead = calendarSource.canRead,
            canWrite = calendarSource.canWrite,
            calendars = calendarSource.calendars(),
            canRingAlarms = alarmBook.canSchedule,
        )
    }

    fun setAlarms(on: Boolean) = change { it.copy(alarms = on) }

    fun setContacts(on: Boolean) = change { it.copy(contacts = on) }

    /** The people picked in a contact picker join the people Hermes knows. */
    fun addPeople(picked: android.net.Uri) {
        viewModelScope.launch {
            peopleBook.add(picked)
            if (engine.sharing.first().isOn) syncNow()
        }
    }

    fun setRelation(id: String, relation: String) {
        viewModelScope.launch {
            peopleBook.setRelation(id, relation)
            if (engine.sharing.first().isOn) syncNow()
        }
    }

    fun removePerson(id: String) {
        viewModelScope.launch {
            peopleBook.remove(id)
            if (engine.sharing.first().isOn) syncNow()
        }
    }

    /** Sharing turned on, to the active dashboard's profile, with nothing chosen yet. */
    fun turnOn() {
        val target = state.value.target ?: return
        change { it.copy(destination = PhoneDataDestination(target.dashboard, target.profile)) }
    }

    fun setCalendar(access: CalendarAccess) = change { it.copy(calendar = access) }

    fun setDetails(on: Boolean) = change { it.copy(details = on, attendeeEmails = it.attendeeEmails && on) }

    fun setAttendeeEmails(on: Boolean) = change { it.copy(attendeeEmails = on) }

    fun setFutureDays(days: Int) = change { it.copy(futureDays = days) }

    fun setStatus(on: Boolean) = change { it.copy(status = on) }

    fun setFocus(on: Boolean) = change { it.copy(focus = on) }

    fun setAutoAdd(on: Boolean) = change { it.copy(autoAdd = on) }

    fun setCalendarShown(providerId: Long, shown: Boolean) = change {
        val id = providerId.toString()
        it.copy(hiddenCalendars = if (shown) it.hiddenCalendars - id else it.hiddenCalendars + id)
    }

    private fun change(edit: (PhoneSharing) -> PhoneSharing) {
        viewModelScope.launch {
            engine.setSharing(edit)
            if (engine.sharing.first().isOn) syncNow()
        }
    }

    fun syncNow() {
        if (local.value.syncing) return
        local.update { it.copy(syncing = true, failure = null) }
        viewModelScope.launch {
            val outcome = engine.sync()
            local.update { it.copy(syncing = false, failure = (outcome as? PhoneSyncOutcome.Failed)?.failure) }
        }
    }

    /** Stops sharing; with [delete], what this phone sent goes from the host too. */
    fun turnOff(delete: Boolean) {
        viewModelScope.launch {
            local.update { it.copy(syncing = true) }
            engine.turnOff(deleteFromHost = delete)
            local.update { it.copy(syncing = false, failure = null) }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
