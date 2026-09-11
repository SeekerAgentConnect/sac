package io.github.brrenat.seekervault.connections

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.awaitCancellation

/**
 * The back camera's preview, with each frame checked for a QR code while the screen is visible.
 * [onText] gets every decoded text on the main thread. [onUnavailable] is called if the camera
 * can't be opened. The caller holds the CAMERA permission.
 */
@Composable
fun QrScanner(onText: (String) -> Unit, onUnavailable: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnText by rememberUpdatedState(onText)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    LaunchedEffect(lifecycleOwner) {
        val frames = Executors.newSingleThreadExecutor()
        val mainThread = ContextCompat.getMainExecutor(context)
        val decoder = QrDecoder()
        val preview = Preview.Builder().build().apply { setSurfaceProvider { surfaceRequest = it } }
        val analysis =
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply {
                    setAnalyzer(frames) { image ->
                        val text = image.use { decoder.decode(it) }
                        if (text != null) mainThread.execute { currentOnText(text) }
                    }
                }
        val provider =
            try {
                ProcessCameraProvider.awaitInstance(context).also {
                    it.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            } catch (e: CancellationException) {
                frames.shutdown()
                throw e
            } catch (e: Exception) {
                // No back camera, or the camera is in use or disabled.
                frames.shutdown()
                currentOnUnavailable()
                return@LaunchedEffect
            }
        try {
            awaitCancellation()
        } finally {
            provider.unbind(preview, analysis)
            frames.shutdown()
        }
    }
    surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it, modifier = modifier) }
}

private fun QrDecoder.decode(image: ImageProxy): String? {
    val plane = image.planes[0] // Y: one byte per pixel
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    return decode(bytes, image.width, image.height, plane.rowStride)
}
