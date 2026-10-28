package lab.dawnforge.hermes.di

import com.google.firebase.FirebaseOptions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import lab.dawnforge.hermes.BuildConfig
import lab.dawnforge.hermes.core.push.PushConfig

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /**
     * This build's push settings, read from google-services.json and local.properties at build time;
     * an empty value is one the build didn't have.
     */
    @Provides
    @Singleton
    fun pushConfig(): PushConfig {
        val values = listOf(
            BuildConfig.FIREBASE_APP_ID,
            BuildConfig.FIREBASE_API_KEY,
            BuildConfig.FIREBASE_PROJECT_ID,
            BuildConfig.FIREBASE_SENDER_ID,
        )
        val firebase = values.takeIf { it.all(String::isNotEmpty) }?.let { (appId, apiKey, projectId, senderId) ->
            FirebaseOptions.Builder()
                .setApplicationId(appId)
                .setApiKey(apiKey)
                .setProjectId(projectId)
                .setGcmSenderId(senderId)
                .build()
        }
        val relay = BuildConfig.DEFAULT_RELAY_URL.takeIf(String::isNotEmpty)
        return PushConfig(BuildConfig.APPLICATION_ID, relay, firebase)
    }
}
