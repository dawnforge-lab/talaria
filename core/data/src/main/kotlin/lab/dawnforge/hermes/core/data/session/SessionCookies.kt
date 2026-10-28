package lab.dawnforge.hermes.core.data.session

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.BrowserCookieStore
import lab.dawnforge.hermes.core.net.DashboardCookieJar
import lab.dawnforge.hermes.core.net.NativeTokens
import lab.dawnforge.hermes.core.net.StoredCookie

/**
 * One cookie jar per dashboard, shared by every request, persisted on every change. A dashboard
 * signed in through the browser (Hermes' native flow) has a bearer token instead of cookies; its
 * jar carries that ([DashboardCookieJar.bearerToken]), loaded with the jar and set by [useTokens].
 *
 * Hermes keeps the access cookie short-lived and, on any request after it lapses, refreshes the
 * session and rotates both cookies in the response. A second copy of the jar would keep sending
 * the old refresh cookie, which the identity provider may treat as reuse and revoke, so all
 * callers share this one. Writes go through one ordered queue, so an older snapshot never
 * overwrites a newer one.
 */
class SessionCookies(
    private val repository: DashboardRepository,
    scope: CoroutineScope,
    /** The app browser's per-dashboard cookie stores; none in JVM tests. */
    private val browserStores: BrowserCookieStores? = null,
) {
    private val jars = ConcurrentHashMap<DashboardId, DashboardCookieJar>()
    private val loading = Mutex()
    private val writes = Channel<Write>(Channel.UNLIMITED)

    private sealed interface Write {
        data class Save(val id: DashboardId, val cookies: List<StoredCookie>) : Write

        data class Flush(val done: CompletableDeferred<Unit>) : Write
    }

    init {
        scope.launch {
            for (write in writes) {
                when (write) {
                    is Write.Save -> repository.saveCookies(write.id, write.cookies)
                    is Write.Flush -> write.done.complete(Unit)
                }
            }
        }
    }

    /** The dashboard's jar, loaded from storage the first time. */
    suspend fun jar(id: DashboardId): DashboardCookieJar = jars[id] ?: loading.withLock {
        jars[id] ?: DashboardCookieJar(
            initial = repository.cookies(id),
            onChange = { snapshot -> writes.trySend(Write.Save(id, snapshot)) },
        ).also { jar ->
            jar.bearerToken = repository.nativeTokens(id)?.accessToken
            if (browserStores != null && repository.isBrowserSession(id)) jar.browser = browserStores.store(id)
            jars[id] = jar
        }
    }

    /** Whether [id]'s session is a sign-in through its own page, kept by the app's browser. */
    suspend fun isBrowserSession(id: DashboardId): Boolean = repository.isBrowserSession(id)

    /** The app browser's cookie store for [id]; its sign-in page and the app's requests share it. */
    suspend fun browserStore(id: DashboardId): BrowserCookieStore? = browserStores?.store(id)

    /**
     * Makes the browser's store [id]'s session, after a sign-in through its own page: the jar's
     * own cookies and any native session go, so the sessions never mix.
     */
    suspend fun useBrowser(id: DashboardId) {
        val store = browserStores?.store(id) ?: return
        repository.saveNativeTokens(id, null)
        repository.setBrowserSession(id, true)
        val jar = jar(id)
        jar.bearerToken = null
        jar.reset(emptyList())
        jar.browser = store
        flush()
    }

    /** Ends a browser session in the jar (not in the browser): another kind of sign-in replaces it. */
    private suspend fun leaveBrowser(id: DashboardId, jar: DashboardCookieJar) {
        if (jar.browser == null) return
        jar.browser = null
        repository.setBrowserSession(id, false)
    }

    /**
     * Installs the session of a native sign-in (or its renewal): stored first, then used by every
     * request. A cookie session the dashboard had is dropped, so the two never mix.
     */
    suspend fun useTokens(id: DashboardId, tokens: NativeTokens) {
        repository.saveNativeTokens(id, tokens)
        val jar = jar(id)
        leaveBrowser(id, jar)
        if (!jar.snapshot().isEmpty()) jar.reset(emptyList())
        jar.bearerToken = tokens.accessToken
        flush()
    }

    /** Installs the cookies of a fresh sign-in and waits until they are stored; a native session goes. */
    suspend fun replace(id: DashboardId, cookies: List<StoredCookie>) {
        val jar = jar(id)
        leaveBrowser(id, jar)
        if (jar.bearerToken != null) {
            jar.bearerToken = null
            repository.saveNativeTokens(id, null)
        }
        jar.reset(cookies)
        flush()
    }

    /** Forgets the session (sign out) and waits until storage agrees. */
    suspend fun clear(id: DashboardId) {
        val jar = jar(id)
        jar.reset(emptyList())
        jar.bearerToken = null
        // Signing out signs the browser out too, as iOS clears the dashboard's WebKit store.
        if (jar.browser != null) browserStores?.clear(id)
        leaveBrowser(id, jar)
        repository.saveNativeTokens(id, null)
        flush()
    }

    /** Drops the in-memory jar of a removed dashboard (its storage is removed with it). */
    suspend fun forget(id: DashboardId) {
        flush()
        browserStores?.delete(id)
        jars.remove(id)
    }

    /** Forgets what a sign-in page given up on left in the browser for [id], never saved, at [url]. */
    suspend fun discardBrowser(id: DashboardId, url: String) {
        browserStores?.discard(id, url)
    }

    /** Forgets the browser data of every dashboard but [kept], the saved ones. */
    suspend fun sweepBrowsers(kept: Set<DashboardId>) {
        browserStores?.sweep(kept)
    }

    /** Waits until every change so far is stored. */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        writes.send(Write.Flush(done))
        done.await()
    }
}
