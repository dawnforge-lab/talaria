package lab.dawnforge.hermes.feature.notifications

/**
 * Whether to tell the user about Android's background limits, and how. Android cuts a backgrounded
 * app's network within seconds unless the app is allowed unrestricted battery use (it is then on
 * the battery allow-list); a short service finishes what was on its way, and replies come as
 * notifications, so the advice is a note, not a request: Android's guidance doesn't let a chat app
 * that uses push ask for the exemption directly. Xiaomi's phones (Redmi and POCO included) also
 * keep a closed app from being woken for its notifications unless it is allowed Autostart.
 */
internal object BackgroundLimits {
    enum class Advice {
        /** Allow unrestricted battery use. */
        Battery,

        /** That, and Autostart. */
        BatteryAndAutostart,
    }

    private val AUTOSTART_MAKERS = setOf("xiaomi", "redmi", "poco")

    /**
     * The advice for a phone made by [manufacturer], sold as [brand]; null when the app already
     * may use the battery unrestricted ([unrestricted]).
     */
    fun advice(unrestricted: Boolean, manufacturer: String?, brand: String?): Advice? = when {
        unrestricted -> null

        listOfNotNull(manufacturer, brand).any {
            it.trim().lowercase() in AUTOSTART_MAKERS
        } -> Advice.BatteryAndAutostart

        else -> Advice.Battery
    }
}
