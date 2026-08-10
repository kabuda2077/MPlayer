package com.theveloper.pixelplay.presentation.components

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.theveloper.pixelplay.BottomNavItem
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateToTopLevelSafely
import com.theveloper.pixelplay.ui.theme.AppShadows
import com.theveloper.pixelplay.ui.theme.appShadow
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop

internal val FloatingNavBarContentHeight = 66.dp
internal val FloatingNavBarCompactContentHeight = 56.dp
internal val FloatingNavBarBottomSpacing = 16.dp
private val FloatingNavBarInset = 4.dp
private val FloatingNavBarScreenMargin = 14.dp

@Composable
fun FloatingGlassNavigationBar(
    navController: NavHostController,
    navItems: ImmutableList<BottomNavItem>,
    currentRoute: String?,
    backdrop: Backdrop,
    blurEnabled: Boolean,
    compactMode: Boolean,
    horizontalMargin: Dp = FloatingNavBarScreenMargin,
    modifier: Modifier = Modifier,
    onSearchIconDoubleTap: () -> Unit = {}
) {
    val itemCount = navItems.size.coerceAtLeast(1)
    val barHeight = if (compactMode) FloatingNavBarCompactContentHeight else FloatingNavBarContentHeight
    val selectedIndex = navItems.indexOfFirst { it.screen.route == currentRoute }
    val latestCurrentRoute by rememberUpdatedState(currentRoute)
    val latestOnSearchIconDoubleTap by rememberUpdatedState(onSearchIconDoubleTap)
    val latestNavigationEnabled by rememberUpdatedState(currentRoute != null)
    val scope = rememberCoroutineScope()
    var lastSearchTapTimestamp by remember { mutableStateOf(0L) }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val availableWidth = (maxWidth - horizontalMargin * 2).coerceAtLeast(0.dp)
        val barWidth = availableWidth
        val itemWidth = ((barWidth - FloatingNavBarInset * 2) / itemCount).coerceAtLeast(0.dp)
        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val indicatorIndex = selectedIndex.coerceIn(0, itemCount - 1)
        val indicatorOffsetTarget = if (selectedIndex >= 0) {
            val visualIndex = if (isLtr) indicatorIndex else itemCount - 1 - indicatorIndex
            itemWidth * visualIndex
        } else {
            0.dp
        }
        val indicatorOffset by animateDpAsState(
            targetValue = indicatorOffsetTarget,
            animationSpec = tween(durationMillis = 220),
            label = "FloatingNavIndicatorOffset"
        )
        val barShape = CircleShape
        val surfaceColor = MaterialTheme.colorScheme.surface
        val containerColor = surfaceColor.copy(alpha = if (blurEnabled) 0.66f else 0.94f)
        val indicatorColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (blurEnabled) 0.72f else 0.9f)
        val density = LocalDensity.current
        val blurRadiusPx = remember(density) { with(density) { 22.dp.toPx() } }

        Box(
            modifier = Modifier
                .width(barWidth)
                .height(barHeight)
                .appShadow(AppShadows.BottomFloating, barShape, clip = false)
                .clip(barShape)
                .then(
                    if (blurEnabled) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { barShape },
                            effects = { blur(blurRadiusPx, blurRadiusPx) },
                            onDrawSurface = { drawRect(containerColor) }
                        )
                    } else {
                        Modifier.background(containerColor, barShape)
                    }
                )
                .padding(FloatingNavBarInset)
        ) {
            if (selectedIndex >= 0) {
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .width(itemWidth)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(indicatorColor)
                )
            }

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                navItems.forEach { item ->
                    val itemRoute = item.screen.route
                    val isSelected = currentRoute == itemRoute
                    val label = stringResource(id = item.labelResId)
                    val iconResId = if (isSelected && item.selectedIconResId != null && item.selectedIconResId != 0) {
                        item.selectedIconResId
                    } else {
                        item.iconResId
                    }
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(durationMillis = 150),
                        label = "FloatingNavItemColor"
                    )
                    val onClick = remember(itemRoute, navController, scope) {
                        click@{
                            if (!latestNavigationEnabled) {
                                lastSearchTapTimestamp = 0L
                                return@click
                            }

                            val isSearchTab = itemRoute == Screen.Search.route
                            val isAlreadySelected = latestCurrentRoute == itemRoute

                            if (isSearchTab) {
                                val now = SystemClock.elapsedRealtime()
                                val isDoubleTap = now - lastSearchTapTimestamp <= 350L
                                lastSearchTapTimestamp = now

                                if (!isAlreadySelected) {
                                    if (!navController.navigateToTopLevelSafely(itemRoute)) {
                                        lastSearchTapTimestamp = 0L
                                        return@click
                                    }
                                }

                                if (isDoubleTap) {
                                    lastSearchTapTimestamp = 0L
                                    if (isAlreadySelected) {
                                        latestOnSearchIconDoubleTap()
                                    } else {
                                        scope.launch {
                                            delay(160L)
                                            latestOnSearchIconDoubleTap()
                                        }
                                    }
                                }
                            } else if (!isAlreadySelected) {
                                lastSearchTapTimestamp = 0L
                                navController.navigateToTopLevelSafely(itemRoute)
                            } else {
                                lastSearchTapTimestamp = 0L
                            }
                        }
                    }

                    FloatingGlassNavigationItem(
                        label = label,
                        iconResId = iconResId,
                        contentColor = contentColor,
                        selected = isSelected,
                        compactMode = compactMode,
                        width = itemWidth,
                        enabled = currentRoute != null,
                        onClick = onClick
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingGlassNavigationItem(
    label: String,
    iconResId: Int,
    contentColor: Color,
    selected: Boolean,
    compactMode: Boolean,
    width: Dp,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick
            )
            .semantics {
                contentDescription = label
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(id = iconResId),
            contentDescription = null,
            modifier = Modifier.size(if (compactMode) 26.dp else 28.dp),
            tint = contentColor
        )
        if (!compactMode) {
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = label,
                color = contentColor,
                fontSize = 11.5.sp,
                lineHeight = 14.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
