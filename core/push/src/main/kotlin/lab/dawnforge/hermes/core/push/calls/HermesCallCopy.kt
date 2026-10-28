package lab.dawnforge.hermes.core.push.calls

import android.content.Context
import lab.dawnforge.hermes.core.model.Profile
import lab.dawnforge.hermes.core.push.R

/** What a call from Hermes says on the phone: who's calling, what it's about, and its first words. */
class HermesCallCopy(private val context: Context) {
    /** The calling profile ("Hermes" for the default one), with the job's title when there is one. */
    fun callerName(profile: String, title: String?): String {
        val name = name(profile)
        return if (title == null) name else context.getString(R.string.core_push_call_caller_title, name, title)
    }

    fun missedTitle(profile: String): String = context.getString(R.string.core_push_call_missed_title, name(profile))

    private fun name(profile: String) =
        if (profile == Profile.DEFAULT) context.getString(R.string.core_push_call_hermes) else profile

    /** How the job ended, as one sentence: "“Check the server” finished." */
    private fun outcome(kind: CallKind?, title: String?): String {
        when (kind) {
            CallKind.Approval -> return context.getString(R.string.core_push_call_needs_ok)
            CallKind.Question -> return context.getString(R.string.core_push_call_has_question)
            else -> Unit
        }
        return if (title == null) {
            context.getString(
                when (kind) {
                    CallKind.Done -> R.string.core_push_call_job_finished
                    CallKind.Failed -> R.string.core_push_call_job_failed
                    CallKind.Stopped -> R.string.core_push_call_job_stopped
                    else -> R.string.core_push_call_job_ended
                },
            )
        } else {
            context.getString(
                when (kind) {
                    CallKind.Done -> R.string.core_push_call_titled_finished
                    CallKind.Failed -> R.string.core_push_call_titled_failed
                    CallKind.Stopped -> R.string.core_push_call_titled_stopped
                    else -> R.string.core_push_call_titled_ended
                },
                title,
            )
        }
    }

    /** What the call is about: Hermes' own reason when it gave one, else how the job ended. */
    fun summary(call: HermesCall): String {
        val reason = call.reason ?: return outcome(call.kind, call.title)
        return if (call.kind?.waitsOnUser == true) outcome(call.kind, null) + " " + reason else reason
    }

    /** What the voice conversation says first when the call is answered. */
    fun intro(call: HermesCall): String = context.getString(R.string.core_push_call_intro) + " " + summary(call)
}
