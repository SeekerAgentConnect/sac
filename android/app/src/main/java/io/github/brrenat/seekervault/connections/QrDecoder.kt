package io.github.brrenat.seekervault.connections

import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Finds a QR code in a camera frame's luminance (Y) plane, with ZXing. Use one per thread. */
class QrDecoder {
    private val reader = QRCodeReader()

    /**
     * The QR code's text, or null if the frame has none. [rowStride] is the number of bytes per row
     * in [luminance], at least [width].
     */
    fun decode(luminance: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        val source =
            PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source))).text
        } catch (e: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}
