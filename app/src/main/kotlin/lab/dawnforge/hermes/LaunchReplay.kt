package lab.dawnforge.hermes

import android.content.Intent

/**
 * A start Android replays: reopening the app from Recents after its screen was closed relaunches
 * it with the intent that first started it, marked [Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY].
 * What that intent carried (a sign-in link, a chat link, a tapped notification, a share, an
 * answered call) was handled the first time, so a replay must not act on it again.
 */
internal object LaunchReplay {
    fun isReplay(flags: Int): Boolean = flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
}
