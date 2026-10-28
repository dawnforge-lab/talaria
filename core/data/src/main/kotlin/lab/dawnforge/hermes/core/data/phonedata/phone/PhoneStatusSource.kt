package lab.dawnforge.hermes.core.data.phonedata.phone

import android.app.NotificationManager
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The phone's state for Hermes, as iOS's `PhoneStatusSource`: battery, power saver, Android Auto,
 * where sound plays and the network. What the user is doing (walking, driving) needs Android's
 * activity recognition, which comes with location sharing; until then it's `unknown`.
 */
class PhoneStatusSource(private val context: Context) {
    fun snapshot(nowMs: Long): JsonObject {
        val battery = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return buildJsonObject {
            put("at", PhoneWire.format(nowMs))
            battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..PERCENT }
                ?.let { put("battery", it / PERCENT.toDouble()) }
            battery?.let { put("charging", it.isCharging) }
            put("low_power", power?.isPowerSaveMode == true)
            // Android Auto puts the phone in car mode.
            put("car_mode", carMode())
            put("audio_route", audioRoute())
            put("network", network())
            put("activity", "unknown")
        }
    }

    private fun carMode(): Boolean =
        context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_CAR

    private fun audioRoute(): String {
        val audio = context.getSystemService(AudioManager::class.java) ?: return "other"
        val types = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSet()
        return when {
            types.any { it in BLUETOOTH } -> "bluetooth"
            types.any { it in HEADPHONES } -> "headphones"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER in types -> "speaker"
            else -> "other"
        }
    }

    private fun network(): String {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return "none"
        val capabilities = runCatching { connectivity.getNetworkCapabilities(connectivity.activeNetwork) }.getOrNull()
            ?: return "none"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"

            else -> "wifi"
        }
    }

    /**
     * Do Not Disturb, as iOS's Focus: on or off, its name, and whether calls are held. Android
     * says only which filter is on; a named mode needs notification-policy access the app doesn't ask for.
     */
    fun focus(nowMs: Long): JsonObject {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val filter = notifications?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        val active = filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        val holdsCalls = when (filter) {
            NotificationManager.INTERRUPTION_FILTER_NONE, NotificationManager.INTERRUPTION_FILTER_ALARMS -> true

            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> runCatching {
                notifications?.notificationPolicy?.let {
                    it.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS == 0
                }
            }.getOrNull() ?: true

            else -> false
        }
        return buildJsonObject {
            put("at", PhoneWire.format(nowMs))
            put("active", active)
            if (active) put("label", "Do Not Disturb")
            put("hold_calls", active && holdsCalls)
        }
    }

    private companion object {
        const val PERCENT = 100
        val BLUETOOTH = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
        val HEADPHONES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
