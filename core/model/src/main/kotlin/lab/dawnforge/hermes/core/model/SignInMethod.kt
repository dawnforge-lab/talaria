package lab.dawnforge.hermes.core.model

/** How a dashboard wants the user to sign in, worked out from what it advertises. */
sealed interface SignInMethod {
    /** Username and password through Hermes' password provider. */
    data class Password(
        val providerName: String,
        /**
         * The browser sign-in offered beside the password, as iOS's: Hermes' native flow for its
         * other providers (one picked by name, several left to Hermes' chooser). Null when the
         * password is the only provider, as the browser would only show the same form.
         */
        val browser: NativeOAuth? = null,
    ) : SignInMethod {
        val browserAlsoAvailable: Boolean get() = browser != null
    }

    /** Hermes' native OAuth flow (PKCE with a loopback redirect). */
    data class NativeOAuth(
        /** The provider to pre-select, or null to let Hermes show its own chooser. */
        val providerName: String?,
    ) : SignInMethod

    /**
     * Sign-in that only works in a browser: an authentication proxy in front of Hermes, or an
     * older Hermes whose OAuth provider has no native flow.
     */
    data object Browser : SignInMethod
}
