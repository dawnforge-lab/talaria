package lab.dawnforge.hermes.core.data.profiles

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A profile's glow, as iOS's `ProfileGlowColors`: the colour picked for it, else the main colour of
 * its photo. Two profiles whose photos share a main colour would glow alike, so the later one in the
 * profile order falls back to its photo's second, then third colour. Colours are `0xRRGGBB`.
 */
object GlowColors {
    /** Colours closer than this in hue count as one. */
    const val DISTINCT_HUE_DEGREES = 30.0

    /** The photo is scaled to this many pixels a side before counting. */
    const val SAMPLE_SIDE = 32

    private const val MAX_COLORS = 3
    private const val BINS = 24
    private const val MIN_SATURATION = 0.2
    private const val MIN_BRIGHTNESS = 0.2
    private const val MIN_SHARE = 0.04
    private const val GREY = 0.15
    private const val GREY_BRIGHTNESS = 0.2
    private const val GLOW_SATURATION = 0.45
    private const val GLOW_BRIGHTNESS = 0.75
    private const val FULL_CIRCLE = 360.0
    private const val SECTOR = 60.0
    private const val CHANNEL = 255.0
    private const val OPAQUE_ENOUGH = 0.5

    data class Hsb(val hue: Double, val saturation: Double, val brightness: Double)

    fun hsb(rgb: Int): Hsb {
        val r = channel(rgb, 16)
        val g = channel(rgb, 8)
        val b = channel(rgb, 0)
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        val delta = high - low
        if (delta <= 0.0) return Hsb(0.0, 0.0, high)
        var hue = when (high) {
            r -> (g - b) / delta
            g -> (b - r) / delta + 2
            else -> (r - g) / delta + 4
        } * SECTOR
        if (hue < 0) hue += FULL_CIRCLE
        return Hsb(hue, delta / high, high)
    }

    fun fromHsb(hue: Double, saturation: Double, brightness: Double): Int {
        val h = ((hue % FULL_CIRCLE) + FULL_CIRCLE) % FULL_CIRCLE / SECTOR
        val chroma = brightness * saturation
        val x = chroma * (1 - abs(h % 2 - 1))
        val (r, g, b) = when {
            h < 1 -> Triple(chroma, x, 0.0)
            h < 2 -> Triple(x, chroma, 0.0)
            h < 3 -> Triple(0.0, chroma, x)
            h < 4 -> Triple(0.0, x, chroma)
            h < 5 -> Triple(x, 0.0, chroma)
            else -> Triple(chroma, 0.0, x)
        }
        val m = brightness - chroma
        return rgb(r + m, g + m, b + m)
    }

    /** Whether two glows read as the same colour: hues closer than 30°, or two greys of about the same lightness. */
    fun looksLike(a: Int, b: Int): Boolean {
        val x = hsb(a)
        val y = hsb(b)
        val xGrey = x.saturation < GREY
        val yGrey = y.saturation < GREY
        if (xGrey || yGrey) return xGrey && yGrey && abs(x.brightness - y.brightness) < GREY_BRIGHTNESS
        val distance = abs(x.hue - y.hue)
        return min(distance, FULL_CIRCLE - distance) < DISTINCT_HUE_DEGREES
    }

    /**
     * A photo's main colours, most prominent first, from its pixels scaled to [SAMPLE_SIDE] a side
     * (`0xAARRGGBB`, not premultiplied). Pixels that are grey, dark or faint don't count; a colour
     * must cover 4% of the colourful ones.
     */
    fun palette(pixels: IntArray): List<Int> {
        val weights = DoubleArray(BINS)
        val sums = Array(BINS) { DoubleArray(3) }
        var total = 0.0
        for (pixel in pixels) {
            if ((pixel ushr 24) / CHANNEL < OPAQUE_ENOUGH) continue
            val color = pixel and 0xFFFFFF
            val hsb = hsb(color)
            if (hsb.saturation < MIN_SATURATION || hsb.brightness < MIN_BRIGHTNESS) continue
            // Vivid pixels count for more than washed-out ones of the same hue.
            val weight = hsb.saturation * hsb.brightness
            val bin = min((hsb.hue / (FULL_CIRCLE / BINS)).toInt(), BINS - 1)
            weights[bin] += weight
            sums[bin][0] += channel(color, 16) * weight
            sums[bin][1] += channel(color, 8) * weight
            sums[bin][2] += channel(color, 0) * weight
            total += weight
        }
        if (total <= 0.0) return emptyList()
        val picked = mutableListOf<Int>()
        for (bin in weights.indices.sortedByDescending { weights[it] }) {
            if (picked.size >= MAX_COLORS || weights[bin] / total < MIN_SHARE) break
            val weight = weights[bin]
            val glow = glowable(rgb(sums[bin][0] / weight, sums[bin][1] / weight, sums[bin][2] / weight))
            if (picked.none { looksLike(it, glow) }) picked += glow
        }
        return picked
    }

    /** A photo's colours are often dark or muted: keep the hue, lift it to a glow's strength. */
    fun glowable(rgb: Int): Int {
        val hsb = hsb(rgb)
        return fromHsb(hsb.hue, max(hsb.saturation, GLOW_SATURATION), max(hsb.brightness, GLOW_BRIGHTNESS))
    }

    /**
     * Each profile's glow. Picked colours are claimed first; then, in profile [order], each photo's
     * main colour unless an earlier profile already glows like it, then its second and third. When
     * all are taken the main colour is used anyway. Profiles with neither are left out, as are
     * picks for profiles no longer listed.
     */
    fun assign(order: List<String>, palettes: Map<String, List<Int>>, picked: Map<String, Int>): Map<String, Int> {
        val result = picked.filterKeys { it in order }.toMutableMap()
        val taken = order.mapNotNull { result[it] }.toMutableList()
        for (profile in order) {
            if (profile in result) continue
            val palette = palettes[profile] ?: continue
            val main = palette.firstOrNull() ?: continue
            val choice = palette.firstOrNull { candidate -> taken.none { looksLike(it, candidate) } } ?: main
            result[profile] = choice
            taken += choice
        }
        return result
    }

    fun hex(rgb: Int): String = "#%06x".format(rgb and 0xFFFFFF)

    fun parseHex(value: String?): Int? = value?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16)

    private fun channel(rgb: Int, shift: Int): Double = ((rgb shr shift) and 0xFF) / CHANNEL

    private fun rgb(r: Double, g: Double, b: Double): Int {
        fun byte(v: Double) = (v.coerceIn(0.0, 1.0) * CHANNEL).roundToInt()
        return (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }
}
