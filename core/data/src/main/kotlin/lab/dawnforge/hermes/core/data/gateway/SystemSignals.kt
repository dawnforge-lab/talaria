package lab.dawnforge.hermes.core.data.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether any of the app's screens is visible, from the process lifecycle. Main thread only. */
class AppForeground {
    private val state = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = state.asStateFlow()

    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    state.value = true
                }

                override fun onStop(owner: LifecycleOwner) {
                    state.value = false
                }
            },
        )
    }
}

/**
 * Whether the default network can reach the internet; a return to true means "retry now".
 *
 * [onNetworkChanged] runs whenever the default network is replaced or lost. Pooled connections
 * belong to the old network and can hang silently (a dead HTTP/2 connection keeps taking new
 * calls until each times out), so the caller drops them there.
 */
class NetworkAvailability(private val context: Context, private val onNetworkChanged: () -> Unit) {
    private val state = MutableStateFlow(true)
    val isAvailable: StateFlow<Boolean> = state.asStateFlow()

    @Volatile
    private var current: Network? = null

    fun register() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (current != null && current != network) onNetworkChanged()
                    current = network
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    state.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }

                override fun onLost(network: Network) {
                    onNetworkChanged()
                    state.value = false
                }
            },
        )
    }
}
