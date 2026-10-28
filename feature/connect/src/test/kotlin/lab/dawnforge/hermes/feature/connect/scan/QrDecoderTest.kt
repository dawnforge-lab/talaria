package lab.dawnforge.hermes.feature.connect.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QrDecoderTest {
    private val link = "talaria://connect?url=https%3A%2F%2Fbox.tail1234.ts.net&username=me&password=p%40ss"

    /** Renders [text] as a QR code into a luminance buffer with [padding] extra bytes per row. */
    private fun frame(
        text: String,
        size: Int = 400,
        padding: Int = 0,
        inverted: Boolean = false,
    ): Triple<ByteArray, Int, Int> {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val stride = size + padding
        val bytes = ByteArray(stride * size) { 0x7F }
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dark = matrix[x, y] != inverted
                bytes[y * stride + x] = if (dark) 0 else 0xFF.toByte()
            }
        }
        return Triple(bytes, size, stride)
    }

    @Test
    fun `decodes a code from a frame`() {
        val (bytes, size, stride) = frame(link)
        assertEquals(link, QrDecoder().decode(bytes, size, size, stride))
    }

    @Test
    fun `honours the row stride`() {
        val (bytes, size, stride) = frame(link, padding = 64)
        assertEquals(link, QrDecoder().decode(bytes, size, size, stride))
    }

    @Test
    fun `decodes light-on-dark codes`() {
        val (bytes, size, stride) = frame(link, inverted = true)
        assertEquals(link, QrDecoder().decode(bytes, size, size, stride))
    }

    @Test
    fun `blank frame has no code`() {
        assertNull(QrDecoder().decode(ByteArray(200 * 200) { 0x40 }, 200, 200, 200))
    }
}
