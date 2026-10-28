package lab.dawnforge.hermes.core.data.phonedata.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lab.dawnforge.hermes.core.data.phonedata.alarms.AlarmBook

/** Hermes' changes on this phone: events through Android's calendar, alarms and timers in this app. */
class DevicePhoneChanges(private val writer: CalendarWriter, private val alarms: AlarmBook) : PhoneChangeApplier {
    override suspend fun apply(change: PhoneChange): String? = when (change.kind) {
        PhoneChangeKind.AddEvent -> io { writer.add(change) }

        PhoneChangeKind.UpdateEvent -> io { writer.update(change) }

        PhoneChangeKind.DeleteEvent -> io { writer.delete(change) }

        PhoneChangeKind.SetAlarm -> alarms.setAlarm(change)

        PhoneChangeKind.SetTimer -> alarms.setTimer(change)

        PhoneChangeKind.CancelAlarm -> {
            val id = change.alarmId ?: throw PhoneChangeError(AlarmBook.NOT_FOUND)
            alarms.cancel(id)
            id
        }
    }

    override suspend fun undo(kind: PhoneChangeKind, itemId: String) {
        when (kind) {
            PhoneChangeKind.AddEvent -> io { writer.removeAdded(itemId) }
            PhoneChangeKind.SetAlarm, PhoneChangeKind.SetTimer -> alarms.cancel(itemId)
            else -> Unit
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
