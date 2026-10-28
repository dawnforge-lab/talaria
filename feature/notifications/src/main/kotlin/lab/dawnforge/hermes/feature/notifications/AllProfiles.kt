package lab.dawnforge.hermes.feature.notifications

import lab.dawnforge.hermes.core.data.plugin.ReplySources
import lab.dawnforge.hermes.core.push.PushStore.NotifyPreferences

/**
 * Notifications as one switch for every profile, with one set of choices: what the screen shows
 * and changes, read from each profile's own pairing and preferences.
 *
 * A profile that notifies another phone keeps its own "Move to this phone" button: the switch
 * never takes one over, and doesn't count it.
 */
object AllProfiles {
    /** Every profile that can notify this phone does, and there's at least one. */
    fun allOn(rows: List<ProfileRow>): Boolean {
        val here = rows.filter { it.status != ProfileNotifications.OnElsewhere }
        return here.isNotEmpty() && here.all { it.status == ProfileNotifications.On }
    }

    /** Some profile hasn't answered yet, so the switch would act on a guess. */
    fun checking(rows: List<ProfileRow>): Boolean = rows.any { it.status == ProfileNotifications.Checking }

    /** The profiles turning everything on turns on, in the list's order. */
    fun toTurnOn(rows: List<ProfileRow>): List<String> =
        rows.filter { it.status == ProfileNotifications.Off }.map { it.name }

    /** The profiles turning everything off turns off. */
    fun toTurnOff(rows: List<ProfileRow>): List<String> =
        rows.filter { it.status == ProfileNotifications.On }.map { it.name }

    /** The choices every profile shares: the first one's on this phone, else the defaults. */
    fun preferences(rows: List<ProfileRow>): NotifyPreferences =
        rows.firstOrNull { it.status == ProfileNotifications.On }?.preferences ?: NotifyPreferences()

    /** Which senders' replies notify: the first profile on this phone whose host reports them, else null. */
    fun sources(rows: List<ProfileRow>): ReplySources? =
        rows.firstOrNull { it.status == ProfileNotifications.On && it.replySources != null }?.replySources

    /** Profiles on this phone whose choices differ from [shared], to bring in line. */
    fun outOfLine(rows: List<ProfileRow>, shared: NotifyPreferences): List<String> =
        rows.filter { it.status == ProfileNotifications.On && it.preferences != shared }.map { it.name }
}
