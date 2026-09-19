package io.github.brrenat.seekervault.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.brrenat.seekervault.designsystem.gallery.ComponentGallery

/** Debug-build-only host for the live design-system preview fixtures. */
class ComponentGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ComponentGallery(onClose = ::finish) }
    }
}
