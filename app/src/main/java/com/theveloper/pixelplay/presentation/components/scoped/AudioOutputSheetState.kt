package com.theveloper.pixelplay.presentation.components.scoped

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

internal data class AudioOutputSheetState(
    val visible: Boolean,
    val openFraction: Float,
    val open: () -> Unit,
    val dismiss: () -> Unit,
)

@Composable
internal fun rememberAudioOutputSheetState(): AudioOutputSheetState {
    var visible by remember { mutableStateOf(false) }
    val openFraction by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "audioOutputSheetOpenFraction",
    )
    return AudioOutputSheetState(
        visible = visible,
        openFraction = openFraction,
        open = { visible = true },
        dismiss = { visible = false },
    )
}
