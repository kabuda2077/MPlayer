package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

val LibraryHeaderHorizontalPadding = 22.dp
val LibraryHeaderTabOuterPadding = PaddingValues(
    start = 0.dp,
    top = 5.dp,
    end = 10.dp,
    bottom = 5.dp
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryHeaderScaffold(
    gradientBrush: Brush,
    headerContainerColor: Color,
    modifier: Modifier = Modifier,
    searchBarVisible: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    onSearch: (String) -> Unit = {},
    onClearSearch: () -> Unit = {},
    searchInputModifier: Modifier = Modifier,
    searchBarPadding: PaddingValues = PaddingValues(
        start = LibraryHeaderHorizontalPadding,
        top = 2.dp,
        end = LibraryHeaderHorizontalPadding,
        bottom = 2.dp
    ),
    contentTopSpacing: Dp = 6.dp,
    topBar: @Composable ColumnScope.() -> Unit,
    afterSearchBar: @Composable ColumnScope.() -> Unit = {},
    bottomOverlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .background(color = MaterialTheme.colorScheme.surface)
            .fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .background(color = headerContainerColor)
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                topBar()
                AnimatedVisibility(visible = searchBarVisible) {
                    LibraryHeaderSearchBar(
                        query = searchQuery,
                        onQueryChange = onSearchQueryChange,
                        onSearch = onSearch,
                        onClear = onClearSearch,
                        containerColor = lerp(
                            headerContainerColor,
                            MaterialTheme.colorScheme.surface,
                            0.32f
                        ),
                        inputModifier = searchInputModifier,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(searchBarPadding)
                    )
                }
                afterSearchBar()
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = contentTopSpacing)
                    .weight(1f),
                color = MaterialTheme.colorScheme.surface,
                shape = AbsoluteSmoothCornerShape(
                    cornerRadiusTL = 34.dp,
                    smoothnessAsPercentBL = 60,
                    cornerRadiusBL = 0.dp,
                    smoothnessAsPercentBR = 60,
                    cornerRadiusBR = 0.dp,
                    smoothnessAsPercentTR = 60,
                    cornerRadiusTR = 34.dp,
                    smoothnessAsPercentTL = 60
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    content = content
                )
            }
        }

        bottomOverlay()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryHeaderSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color,
    inputModifier: Modifier = Modifier
) {
    val inputFieldColors = SearchBarDefaults.inputFieldColors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        cursorColor = MaterialTheme.colorScheme.primary
    )

    DockedSearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                modifier = inputModifier,
                query = query,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                expanded = false,
                onExpandedChange = {},
                placeholder = {
                    Text(
                        text = stringResource(R.string.search_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.search_cd_search_icon),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(
                            onClick = onClear,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.search_cd_clear_search_query),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                colors = inputFieldColors
            )
        },
        expanded = false,
        onExpandedChange = {},
        modifier = modifier
            .clip(RoundedCornerShape(28.dp)),
        colors = SearchBarDefaults.colors(
            containerColor = containerColor,
            dividerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
            inputFieldColors = inputFieldColors
        ),
        content = {}
    )
}
