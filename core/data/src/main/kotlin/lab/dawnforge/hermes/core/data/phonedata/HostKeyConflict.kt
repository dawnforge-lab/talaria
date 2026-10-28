package lab.dawnforge.hermes.core.data.phonedata

/**
 * Whether a 409 from the plugin's phone-data routes says the host lost or replaced this phone's key,
 * as iOS's `HostKeyConflict`. The host sends its message as the detail, so the messages are
 * recognised as well as the codes; its other 409s (a layer that's off, a schema it refused) aren't
 * key loss, and setting up a new key wouldn't help them.
 */
object HostKeyConflict {
    private val markers = listOf(
        // The codes, should a host send them.
        "unknown_key",
        "bad_seal",
        "unknown key",
        // unknown_key: "This device's location (health) key isn't on this profile; set it up again"
        "key isn't on this profile",
        // bad_seal: "The body didn't open with this device's key; set up … again"
        "didn't open with this device's key",
    )

    fun isKeyLoss(detail: String?): Boolean {
        val normalized = detail?.lowercase()?.replace('’', '\'') ?: return false
        return markers.any { it in normalized }
    }
}
