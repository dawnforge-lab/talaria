package lab.dawnforge.hermes.core.voice.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import lab.dawnforge.hermes.core.data.di.ApplicationScope
import lab.dawnforge.hermes.core.data.gateway.ConnectionHolds
import lab.dawnforge.hermes.core.data.settings.VoiceSettings
import lab.dawnforge.hermes.core.data.voice.HermesSpeech
import lab.dawnforge.hermes.core.data.voice.SpeechReadiness
import lab.dawnforge.hermes.core.voice.Dictation
import lab.dawnforge.hermes.core.voice.ReadAloud
import lab.dawnforge.hermes.core.voice.VoiceChecks
import lab.dawnforge.hermes.core.voice.VoiceConversations

@Module
@InstallIn(SingletonComponent::class)
object VoiceModule {
    @Provides
    @Singleton
    fun dictation(@ApplicationContext context: Context, speech: HermesSpeech, settings: VoiceSettings) =
        Dictation(context, speech, settings)

    @Provides
    @Singleton
    fun voiceConversations(
        @ApplicationContext context: Context,
        speech: HermesSpeech,
        readiness: SpeechReadiness,
        settings: VoiceSettings,
        holds: ConnectionHolds,
    ) = VoiceConversations(context, speech, readiness, settings, holds)

    @Provides
    @Singleton
    fun voiceChecks(@ApplicationContext context: Context) = VoiceChecks(context)

    @Provides
    @Singleton
    fun readAloud(
        @ApplicationContext context: Context,
        speech: HermesSpeech,
        readiness: SpeechReadiness,
        settings: VoiceSettings,
        @ApplicationScope scope: CoroutineScope,
    ) = ReadAloud(context, speech, readiness, settings, scope)
}
