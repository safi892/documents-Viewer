package com.secure.reader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.secure.reader.core.designsystem.theme.ReaderTheme
import com.secure.reader.features.library.ui.LibraryViewModel
import com.secure.reader.features.reader.ui.ReaderViewModel

class MainActivity : ComponentActivity() {

    private val libraryViewModel: LibraryViewModel by viewModels {
        (application as ReaderApplication).container.libraryViewModelFactory
    }

    private val readerViewModel: ReaderViewModel by viewModels {
        (application as ReaderApplication).container.readerViewModelFactory
    }

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let(readerViewModel::open)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bind the compositor to the display's fastest mode so Compose animations and
        // scroll physics run at the panel's native rate (120 Hz on flagships, 90 Hz
        // on many mid-range panels) instead of being time-scaled into 60 Hz.
        preferMaxRefreshRate()
        enableEdgeToEdge()
        setContent {
            ReaderTheme {
                App(
                    libraryViewModel = libraryViewModel,
                    readerViewModel = readerViewModel,
                    onOpenFile = { openDocument.launch(arrayOf("*/*")) },
                )
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data?.takeIf { it.scheme == CONTENT || it.scheme == FILE } ?: return
        readerViewModel.open(uri)
    }

    companion object {
        private const val CONTENT = "content"
        private const val FILE = "file"
    }
}

/**
 * Asks the display for its highest refresh-rate mode (120 Hz on most flagship
 * panels, 90 Hz on several mid-range ones).
 *
 * On R+ the window pins an explicit [android.view.Display.Mode]; below that the
 * older [android.view.WindowManager.LayoutParams.preferredRefreshRate] hint is
 * the best a minSdk-24 app can do — the compositor still honours it on most
 * devices of that era.
 */
private fun Activity.preferMaxRefreshRate() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val fastest = display?.supportedModes?.maxByOrNull { it.refreshRate } ?: return
        if (fastest.modeId == window.attributes.preferredDisplayModeId) return
        window.attributes = window.attributes.apply { preferredDisplayModeId = fastest.modeId }
    } else {
        @Suppress("DEPRECATION")
        window.attributes = window.attributes.apply { preferredRefreshRate = 120f }
    }
}
