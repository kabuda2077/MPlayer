package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.scoped.CustomNavigationBarItem
import com.theveloper.pixelplay.ui.theme.PixelPlayTheme

@Preview(
    name = "Bottom Bars - Home selected",
    widthDp = 393,
    heightDp = 180,
    showBackground = true,
    backgroundColor = 0xFF05070B
)
@Composable
private fun BottomBarsHomeSelectedPreview() {
    PixelPlayTheme(darkTheme = true) {
        BottomBarPreviewHarness(selectedIndex = 0)
    }
}

@Preview(
    name = "Bottom Bars - Search selected",
    widthDp = 393,
    heightDp = 180,
    showBackground = true,
    backgroundColor = 0xFF05070B
)
@Composable
private fun BottomBarsSearchSelectedPreview() {
    PixelPlayTheme(darkTheme = true) {
        BottomBarPreviewHarness(selectedIndex = 1)
    }
}

@Composable
private fun BottomBarPreviewHarness(selectedIndex: Int) {
    val miniPlayerScheme = MaterialTheme.colorScheme.copy(
        primary = Color(0xFF8ADDD4),
        onPrimary = Color(0xFF003B36),
        primaryContainer = Color(0xFF00675D),
        onPrimaryContainer = Color(0xFFE0FFFA)
    )

    Column(
        modifier = Modifier
            .background(Color(0xFF05070B))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        CompositionLocalProvider(LocalMaterialTheme provides miniPlayerScheme) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MiniPlayerHeight),
                shape = RoundedCornerShape(32.dp),
                color = miniPlayerScheme.primaryContainer
            ) {
                MiniPlayerContentInternal(
                    song = previewSong,
                    isPlaying = true,
                    isCastConnecting = false,
                    isPreparingPlayback = false,
                    onPlayPause = {},
                    onPrevious = {},
                    onNext = {},
                    canScroll = false
                )
            }
        }

        Spacer(modifier = Modifier.height(MiniPlayerBottomSpacer + 4.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(NavBarContentHeight),
            shape = RoundedCornerShape(36.dp),
            color = NavigationBarDefaults.containerColor
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                previewNavItems.forEachIndexed { index, item ->
                    val selected = selectedIndex == index
                    CustomNavigationBarItem(
                        modifier = Modifier.weight(1f),
                        selected = selected,
                        onClick = {},
                        icon = {
                            Icon(
                                painter = painterResource(id = item.iconRes),
                                contentDescription = item.label,
                                modifier = Modifier.size(30.dp)
                            )
                        },
                        selectedIcon = {
                            Icon(
                                painter = painterResource(id = item.selectedIconRes),
                                contentDescription = item.label,
                                modifier = Modifier.size(30.dp)
                            )
                        },
                        label = { Text(item.label) },
                        contentDescription = item.label,
                        alwaysShowLabel = true,
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                }
            }
        }
    }
}

private data class PreviewNavItem(
    val label: String,
    val iconRes: Int,
    val selectedIconRes: Int
)

private val previewNavItems = listOf(
    PreviewNavItem("首页", R.drawable.rounded_home_24, R.drawable.home_24_rounded_filled),
    PreviewNavItem("搜索", R.drawable.rounded_search_24, R.drawable.rounded_search_24),
    PreviewNavItem("音乐库", R.drawable.rounded_library_music_24, R.drawable.round_library_music_24)
)

private val previewSong = Song(
    id = "preview",
    title = "故湘，风",
    artist = "GAI周延",
    artistId = 0L,
    album = "Preview",
    albumId = 0L,
    path = "",
    contentUriString = "netease://preview",
    albumArtUriString = null,
    duration = 240_000L,
    mimeType = "audio/flac",
    bitrate = 320_000,
    sampleRate = 44_100
)
