package lab.dawnforge.hermes.core.data.profiles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import lab.dawnforge.hermes.core.data.bots.BotColor
import lab.dawnforge.hermes.core.data.bots.BotsRepository
import lab.dawnforge.hermes.core.data.bots.BotsState
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.Profile

/**
 * The profiles' glow colours on a dashboard, as iOS works them out ([GlowColors]): the colours
 * picked on this phone, else each photo's own, in the profile order. A profile without either
 * glows in its bot's own colour, and otherwise in the app's accent.
 */
class ProfileGlows(private val repository: DashboardRepository, private val bots: BotsRepository) {
    /** Each photo's palette, worked out once per picture. */
    private val palettes = ConcurrentHashMap<String, Pair<ByteArray, List<Int>>>()

    /** The colour picked for each of [id]'s profiles on this phone. */
    fun picks(id: DashboardId): Flow<Map<String, Int>> = repository.profileGlows(id)

    suspend fun pick(id: DashboardId, profile: String, rgb: Int?) = repository.setProfileGlow(id, profile, rgb)

    /** Every listed profile's glow from a pick or its photo; profiles with neither are left out. */
    fun glows(id: DashboardId): Flow<Map<String, Int>> = combine(
        repository.profileGlows(id),
        repository.profileOrder(id),
        bots.state,
        bots.avatars,
    ) { picks, order, state, avatars ->
        val roster = (state as? BotsState.Available)?.roster?.bots?.map { it.name }
        // Without the roster (not connected yet) every pick still counts.
        val names = ordered(roster ?: (order + picks.keys).distinct(), order)
        val photos = avatars.filterKeys { it in names }.mapValues { (name, bytes) -> palette(name, bytes) }
        GlowColors.assign(names, photos, picks)
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    /** [profile]'s glow, else its bot's own colour; null for the app's accent. */
    fun tint(id: DashboardId, profile: String): Flow<Int?> = glows(id).combine(bots.state) { glows, state ->
        glows[profile] ?: (state as? BotsState.Available)?.roster?.find(profile)?.color?.let(BotColor::rgb)
    }.distinctUntilChanged()

    /** The saved order first, then the main profile, then the rest by name, as the profile list shows them. */
    private fun ordered(names: List<String>, order: List<String>): List<String> {
        val rank = order.withIndex().associate { (index, name) -> name to index }
        return names.sortedWith(
            compareBy<String> { rank[it] ?: Int.MAX_VALUE }.thenBy { it != Profile.DEFAULT }.thenBy { it.lowercase() },
        )
    }

    private fun palette(name: String, bytes: ByteArray): List<Int> {
        palettes[name]?.takeIf { it.first === bytes }?.let { return it.second }
        val colors = runCatching { decode(bytes) }.getOrNull().orEmpty()
        palettes[name] = bytes to colors
        return colors
    }

    private fun decode(bytes: ByteArray): List<Int> {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return emptyList()
        val side = GlowColors.SAMPLE_SIDE
        val small = Bitmap.createScaledBitmap(source, side, side, true)
        val pixels = IntArray(side * side)
        small.getPixels(pixels, 0, side, 0, 0, side, side)
        return GlowColors.palette(pixels)
    }
}
