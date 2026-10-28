package lab.dawnforge.hermes.core.push

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * This phone's push address at FCM: its Firebase installation id (FID), registered for messaging.
 * The relay sends to it in place of the registration token the older SDKs used. An interface so the
 * setup can be tested without Firebase.
 */
interface PushTokens {
    /** False when this build has no Firebase project (no `google-services.json` at build time). */
    val available: Boolean

    /** Registers the phone for messaging and returns its FID; changes arrive in `onRegistered`. */
    suspend fun token(): String

    /** Unregisters the phone, so Google stops delivering to it. */
    suspend fun delete()
}

/**
 * Firebase, started from code with this build's options instead of resources the Google Services
 * Gradle plugin would generate. Messaging only: no analytics.
 */
class FirebasePushTokens(private val context: Context, options: FirebaseOptions?) : PushTokens {
    init {
        if (options != null && FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context, options)
    }

    override val available: Boolean get() = FirebaseApp.getApps(context).isNotEmpty()

    override suspend fun token(): String {
        check(available) { "This build has no Firebase project" }
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = true
        messaging.register().await()
        return FirebaseInstallations.getInstance().id.await()
    }

    override suspend fun delete() {
        if (!available) return
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = false
        messaging.unregister().await()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            task.isCanceled -> continuation.cancel()
            else -> continuation.resume(task.result)
        }
    }
}
