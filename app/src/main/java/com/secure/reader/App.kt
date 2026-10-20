package com.secure.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.secure.reader.features.library.ui.LibraryScreen
import com.secure.reader.features.library.ui.LibraryViewModel
import com.secure.reader.features.reader.ui.ReaderScreen
import com.secure.reader.features.reader.ui.ReaderUiState
import com.secure.reader.features.reader.ui.ReaderViewModel

@Composable
fun App(
    libraryViewModel: LibraryViewModel,
    readerViewModel: ReaderViewModel,
    onOpenFile: () -> Unit,
) {
    val libraryState by libraryViewModel.state.collectAsState()
    val readerState by readerViewModel.state.collectAsState()
    val haptics = LocalHapticFeedback.current

    var showingReader by rememberSaveable { mutableStateOf(false) }

    // A completed open (picker or "Open with" intent) pushes the reader — including
    // failures, so an unreadable file shows the error with retry instead of leaving
    // the user on the library with no feedback at all.
    LaunchedEffect(readerState.phase) {
        when (readerState.phase) {
            ReaderUiState.Phase.READY -> {
                // The one moment worth confirming physically: the file is open.
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                showingReader = true
            }
            ReaderUiState.Phase.FAILED -> showingReader = true
            ReaderUiState.Phase.LOADING -> Unit
        }
    }

    BackHandler(enabled = showingReader) {
        showingReader = false
        readerViewModel.close()
    }

    // The theme owns every surface colour: without this the window background
    // (from the XML theme) shows through and text falls back to hard black.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        // The tapped row's filename flies into the reader's title bar (and back
        // on return) — the app's one piece of showmanship, and the clearest
        // possible signal of "this row became that document".
        val boundsTransform = remember {
            BoundsTransform { _, _ ->
                spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
            }
        }
        SharedTransitionLayout {
            AnimatedContent(
                targetState = showingReader,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically { it / 16 })
                        .togetherWith(fadeOut(tween(180)))
                },
                label = "library-reader",
            ) { inReader ->
                // `this` is the AnimatedContentScope, which is the
                // AnimatedVisibilityScope the shared element pairs against.
                val titleHero = Modifier.sharedElement(
                    sharedContentState = rememberSharedContentState(TITLE_HERO_KEY),
                    animatedVisibilityScope = this,
                    boundsTransform = boundsTransform,
                )
                if (inReader) {
                    ReaderScreen(
                        state = readerState,
                        onBack = {
                            showingReader = false
                            readerViewModel.close()
                        },
                        onRetry = readerViewModel::retry,
                        titleModifier = titleHero,
                    )
                } else {
                    // Only the tapped row joins the hero; every other row keeps
                    // its plain title.
                    val openedId = readerState.document?.id
                    LibraryScreen(
                        state = libraryState,
                        onOpenFile = onOpenFile,
                        onOpenDocument = { document ->
                            readerViewModel.reopen(document.id)
                        },
                        onDeleteDocument = { document ->
                            libraryViewModel.delete(document.id)
                        },
                        onRetry = libraryViewModel::retry,
                        titleModifier = { document ->
                            if (document.id == openedId) titleHero else Modifier
                        },
                    )
                }
            }
        }
    }
}

private const val TITLE_HERO_KEY = "document-title"
