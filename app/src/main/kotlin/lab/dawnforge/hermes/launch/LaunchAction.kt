package lab.dawnforge.hermes.launch

import android.net.Uri

/**
 * What the widget, a launcher shortcut or the Quick Settings tile asks of the app, written as
 * iOS's widget links: `talaria://widget/v1/{open|resume|newChat|talk}?dashboard=&profile=&session=`.
 * They only ever reach the app through [LauncherActivity], which other apps can't start.
 */
sealed interface LaunchAction {
    /** Just the app. */
    data object Open : LaunchAction

    data class Resume(val dashboard: String, val profile: String, val sessionId: String) : LaunchAction

    /** A new chat on [dashboard] and [profile], else on the ones the app shows. */
    data class NewChat(val dashboard: String?, val profile: String?) : LaunchAction

    /** A new chat whose voice conversation starts at once. */
    data class Talk(val dashboard: String?, val profile: String?) : LaunchAction

    fun uri(): Uri {
        val builder = Uri.Builder().scheme("talaria").authority("widget").appendPath("v1")
        when (this) {
            Open -> builder.appendPath("open")

            is Resume -> builder.appendPath("resume")
                .appendQueryParameter("dashboard", dashboard)
                .appendQueryParameter("profile", profile)
                .appendQueryParameter("session", sessionId)

            is NewChat -> builder.appendPath("newChat").workspace(dashboard, profile)

            is Talk -> builder.appendPath("talk").workspace(dashboard, profile)
        }
        return builder.build()
    }

    companion object {
        private val VALUE = Regex("^[A-Za-z0-9_.:-]{1,128}$")

        /** The action [uri] asks for, or null when it isn't one (an unknown or malformed link does nothing). */
        fun parse(uri: Uri?): LaunchAction? {
            if (uri == null || uri.scheme != "talaria" || uri.authority != "widget") return null
            val path = uri.pathSegments
            if (path.size != 2 || path[0] != "v1") return null
            val names = uri.queryParameterNames
            if (names.any { it !in KEYS } || names.any { uri.getQueryParameters(it).size > 1 }) return null
            fun value(key: String) = uri.getQueryParameter(key)?.takeIf(String::isNotEmpty)
            if (KEYS.mapNotNull(::value).any { !VALUE.matches(it) }) return null
            val dashboard = value("dashboard")
            val profile = value("profile")
            return when (path[1]) {
                "open" -> Open
                "resume" -> Resume(dashboard ?: return null, profile ?: return null, value("session") ?: return null)
                "newChat" -> if (value("session") == null) NewChat(dashboard, profile) else null
                "talk" -> if (value("session") == null) Talk(dashboard, profile) else null
                else -> null
            }
        }

        private val KEYS = setOf("dashboard", "profile", "session")

        private fun Uri.Builder.workspace(dashboard: String?, profile: String?) = apply {
            dashboard?.let { appendQueryParameter("dashboard", it) }
            profile?.let { appendQueryParameter("profile", it) }
        }
    }
}
