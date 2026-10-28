package lab.dawnforge.hermes.core.chat

/**
 * A Settings page Hermes links to from a reply (`talaria://settings/<page>`), as iOS's
 * app links: "turn it on in [Location settings](talaria://settings/location)".
 * Opened only from inside the app, like a link to another chat; another app can't open one.
 */
enum class SettingsLink(val path: String) {
    /** Calls from Hermes. */
    Calls("calls"),

    /** Voice, where "Let Hermes change settings" lives. */
    Voice("voice"),

    /** The push plugin on the dashboard's host. */
    Plugin("plugin"),

    /** Health, where sharing health data is turned on. */
    Health("health"),

    /** Location, where sharing where the phone is is set up. */
    Location("location"),

    /** Calendar & phone. */
    Phone("phone"),
    ;

    companion object {
        private const val PREFIX = "talaria://settings/"

        /** The page [uri] links to, or null when it isn't a settings link this app knows. */
        fun parse(uri: String): SettingsLink? {
            val text = uri.trim()
            if (!text.startsWith(PREFIX, ignoreCase = true)) return null
            val page = text.substring(PREFIX.length).substringBefore('?').substringBefore('#').trim('/')
            if (page.isEmpty() || '/' in page) return null
            return entries.firstOrNull { it.path.equals(page, ignoreCase = true) }
        }
    }
}
