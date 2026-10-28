package lab.dawnforge.hermes.core.gateway

/**
 * Where the gateway reports what it is doing. Messages name methods, states and ids only; they
 * never carry tickets, params, results or message text.
 */
interface GatewayLog {
    fun info(message: String)

    fun warn(message: String, error: Throwable? = null)

    object None : GatewayLog {
        override fun info(message: String) = Unit

        override fun warn(message: String, error: Throwable?) = Unit
    }
}
