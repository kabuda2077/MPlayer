package com.theveloper.pixelplay.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class AppShadowStyle(
    val elevation: Dp,
    val radius: Dp = elevation,
    val color: Color = Color.Black,
    val alpha: Float = 1f,
    val ambientColor: Color = Color.Black,
    val spotColor: Color = Color.Black
)

object AppElevation {
    val None: Dp = 0.dp
    val Subtle: Dp = 1.dp
    val FloatingLow: Dp = 2.dp
    val Floating: Dp = 3.dp
    val Raised: Dp = 6.dp
    val Prominent: Dp = 8.dp
    val Overlay: Dp = 12.dp
}

object AppShadows {
    val None = AppShadowStyle(
        elevation = AppElevation.None,
        radius = 0.dp,
        color = Color.Transparent,
        alpha = 0f,
        ambientColor = Color.Transparent,
        spotColor = Color.Transparent
    )
    val BottomFloating = AppShadowStyle(
        elevation = 4.dp,
        ambientColor = Color.Black.copy(alpha = 0.18f),
        spotColor = Color.Black.copy(alpha = 0.26f)
    )
    val FloatingAction = AppShadowStyle(elevation = AppElevation.Raised)
    val BottomSheet = AppShadowStyle(elevation = AppElevation.Prominent)
    val Dialog = AppShadowStyle(elevation = AppElevation.Overlay)
}

fun Modifier.appShadow(
    style: AppShadowStyle,
    shape: Shape,
    clip: Boolean = false
): Modifier {
    val shadowModifier = shadow(
        elevation = style.elevation,
        shape = shape,
        clip = false,
        ambientColor = style.ambientColor,
        spotColor = style.spotColor
    )
    return if (clip) shadowModifier.clip(shape) else shadowModifier
}
