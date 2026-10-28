package lab.dawnforge.hermes.feature.connect.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.CustomHeader
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import lab.dawnforge.hermes.core.ui.failureText
import lab.dawnforge.hermes.feature.connect.ConnectEvent
import lab.dawnforge.hermes.feature.connect.ConnectViewModel
import lab.dawnforge.hermes.feature.connect.ErrorText
import lab.dawnforge.hermes.feature.connect.EventEffect
import lab.dawnforge.hermes.feature.connect.R
import lab.dawnforge.hermes.feature.connect.WebSignIn

/**
 * The dashboard's own sign-in page in the app, for a sign-in service in front of Hermes, as iOS's
 * `AuthWebView`: titled Sign in, with Cancel, without an address bar. It loads in the dashboard's
 * own WebView profile, whose cookies the app's requests then use. Whenever a dashboard page that
 * isn't a sign-in page has loaded, the app checks whether the session opens the live connection;
 * once it does, the dashboard is saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebSignInScreen(viewModel: ConnectViewModel, onSignedIn: () -> Unit, onCancel: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val web = state.webSignIn
    EventEffect(viewModel.events) { event ->
        if (event == ConnectEvent.SignedIn) onSignedIn()
    }
    val cancel by rememberUpdatedState {
        viewModel.cancelWebSignIn()
        onCancel()
    }
    BackHandler { cancel() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_connect_web_title)) },
                actions = {
                    TextButton(onClick = { cancel() }) { Text(stringResource(R.string.feature_connect_cancel)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.completingWeb) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.webError?.let { failure ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { ErrorText(failureText(failure)) }
            }
            if (web != null) SignInPage(web, onPageFinished = viewModel::onWebPage)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled") // Sign-in services' pages are scripts.
@Composable
private fun SignInPage(web: WebSignIn, onPageFinished: (String) -> Unit) {
    val finished by rememberUpdatedState(onPageFinished)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                // The dashboard's own profile, before anything loads: its cookies are its session.
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    WebViewCompat.setProfile(this, web.profile)
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.CUSTOM_REQUEST_HEADERS)) {
                        // The proxy's headers on every request to the dashboard, not just the first.
                        val profile = ProfileStore.getInstance().getOrCreateProfile(web.profile)
                        profile.clearAllCustomHeaders()
                        web.headers.forEach { (name, value) ->
                            profile.addCustomHeader(CustomHeader(name, value, setOf(web.origin)))
                        }
                    }
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportMultipleWindows(false)
                webViewClient = object : WebViewClient() {
                    private var pinned = false

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame) return false
                        return !WebSignInPolicy.allowsNavigation(request.url.toString(), web.url, pinned)
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        // A redirect the policy above never saw (a server's) is stopped here too.
                        if (!WebSignInPolicy.allowsNavigation(url, web.url, pinned)) view.stopLoading()
                    }

                    override fun onPageFinished(view: WebView, url: String) = check(url)

                    // Hermes' dashboard changes pages in place, without loading a new one.
                    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = check(url)

                    private fun check(url: String) {
                        if (WebSignInPolicy.isSignedInDashboardPage(url, web.url)) {
                            // Signed in, or Hermes' own page: from now on only the dashboard's origin.
                            pinned = true
                            finished(url)
                        }
                    }
                }
                loadUrl(web.startUrl, web.headers)
            }
        },
        onRelease = WebView::destroy,
    )
}
