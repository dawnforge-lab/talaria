package lab.dawnforge.hermes.feature.connect.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/**
 * Finds a QR code in a camera frame's luminance (the Y plane). ZXing runs on any device, with no
 * Google Play services, so the scanner works on de-Googled phones too.
 */
internal class QrDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    /** [luminance] holds [height] rows of [rowStride] bytes, of which the first [width] are pixels. */
    fun decode(luminance: ByteArray, width: Int, height: Int, rowStride: Int): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return decode(BinaryBitmap(HybridBinarizer(source)))
            // Some apps show light-on-dark codes.
            ?: decode(BinaryBitmap(HybridBinarizer(source.invert())))
    }

    private fun decode(bitmap: BinaryBitmap): String? = try {
        reader.decodeWithState(bitmap).text
    } catch (_: NotFoundException) {
        null
    } catch (_: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}
