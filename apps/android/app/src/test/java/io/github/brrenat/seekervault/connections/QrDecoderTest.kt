package io.github.brrenat.seekervault.connections

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** QR decoding of camera frames, with codes drawn by ZXing's own encoder. */
class QrDecoderTest {
    private val code =
        "seekervault://pair?v=1&url=https%3A%2F%2Fmac.tailnet.ts.net" +
            "&server=9fda5035-f3b4-4ec3-a68a-5e6caa02397a" +
            "&token=Lq3v7Yk2Qm9XwTzR4bN8cJ1dH6fG0sA5eP-uV_iKoLw"

    /** A luminance (Y) plane showing [text] as a QR code: dark modules 0, light ones 255. */
    private fun frame(text: String, size: Int, rowStride: Int = size): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val plane = ByteArray(rowStride * size) { LIGHT }
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (matrix[x, y]) plane[y * rowStride + x] = DARK
            }
        }
        return plane
    }

    @Test
    fun readsAPairingCodeFromAFrame() {
        assertEquals(code, QrDecoder().decode(frame(code, 480), 480, 480))
    }

    @Test
    fun readsAFrameWhoseRowsArePadded() {
        // Camera buffers often have more bytes per row than pixels.
        assertEquals(code, QrDecoder().decode(frame(code, 480, rowStride = 512), 480, 480, 512))
    }

    @Test
    fun findsNothingInAFrameWithoutACode() {
        assertNull(QrDecoder().decode(ByteArray(480 * 480) { GRAY }, 480, 480))
    }

    @Test
    fun keepsDecodingAfterAFrameWithoutACode() {
        val decoder = QrDecoder()
        assertNull(decoder.decode(ByteArray(480 * 480) { GRAY }, 480, 480))
        assertEquals(code, decoder.decode(frame(code, 480), 480, 480))
    }

    private companion object {
        const val DARK: Byte = 0
        const val LIGHT: Byte = -1 // 0xFF
        const val GRAY: Byte = 0x7F
    }
}
