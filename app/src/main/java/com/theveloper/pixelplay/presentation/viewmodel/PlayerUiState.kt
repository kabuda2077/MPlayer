package com.theveloper.pixelplay.presentation.viewmodel

import androidx.compose.runtime.Immutable
import com.theveloper.pixelplay.data.model.Album
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.data.model.FolderSource
import com.theveloper.pixelplay.data.model.MusicFolder
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.OnlineSearchPlatform
import com.theveloper.pixelplay.data.model.OnlineSearchResultItem
import com.theveloper.pixelplay.data.model.SearchResultItem
import com.theveloper.pixelplay.data.model.SortOption
import com.theveloper.pixelplay.data.model.SongListViewMode
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class PlayerUiState(
    // val allSongs: ImmutableList<Song> = persistentListOf(), // REMOVED
    val currentPlaybackQueue: ImmutableList<Song> = persistentListOf(),
    val currentQueueSourceName: String = "All Songs",
    // val albums: ImmutableList<Album> = persistentListOf(), // REMOVED
    // val artists: ImmutableList<Artist> = persistentListOf(), // REMOVED
    val onlineSearchResults: ImmutableList<OnlineSearchResultItem> = persistentListOf(),
    val onlineSearchPlatform: OnlineSearchPlatform = OnlineSearchPlatform.NETEASE,
    val onlineSearchLoading: Boolean = false,
    val onlineSearchError: String? = null,
    val librarySearchResults: ImmutableList<SearchResultItem> = persistentListOf(),
    val librarySearchLoading: Boolean = false,
    val librarySearchError: String? = null,
    val popularSearchTerms: ImmutableList<String> = persistentListOf(),
    val musicFolders: ImmutableList<MusicFolder> = persistentListOf(),
    val sortOption: SortOption = SortOption.SongDefaultOrder,
    val isLoadingInitialSongs: Boolean = true,
    val isLoadingLibrary: Boolean = true,
    val filteredSongs: ImmutableList<Song> = persistentListOf(), // For search filtering within lists
    val isFiltering: Boolean = false,
    val showDismissUndoBar: Boolean = false,
    val dismissedSong: Song? = null,
    val dismissedQueue: ImmutableList<Song> = persistentListOf(),
    val dismissedQueueName: String = "",
    val dismissedPosition: Long = 0L,
    val currentFolder: MusicFolder? = null,
    val currentFolderPath: String? = null,
    val folderSource: FolderSource = FolderSource.INTERNAL,
    val folderSourceRootPath: String = "",
    val isSdCardAvailable: Boolean = false,
    val lavaLampColors: ImmutableList<androidx.compose.ui.graphics.Color> = persistentListOf(),
    val undoBarVisibleDuration: Long = 4000L,
    val isFolderFilterActive: Boolean = false,
    val isFoldersPlaylistView: Boolean = false,
    val preparingSongId: String? = null,
    val isLoadingLibraryCategories: Boolean = true,
    val isAlbumsListView: Boolean = false,
    val songListViewMode: SongListViewMode = SongListViewMode.LIST,
    val favoriteSongListViewMode: SongListViewMode = SongListViewMode.LIST,
    val currentFavoriteSortOption: SortOption = SortOption.LikedSongTitleAZ,
    val currentAlbumSortOption: SortOption = SortOption.AlbumTitleAZ,
    val currentArtistSortOption: SortOption = SortOption.ArtistNameAZ,
    val currentFolderSortOption: SortOption = SortOption.FolderNameAZ,
    val folderBackGestureNavigationEnabled: Boolean = true,
    val currentSongSortOption: SortOption = SortOption.SongTitleAZ,
    // val songCount: Int = 0, // REMOVED
    val isGeneratingAiMetadata: Boolean = false,
    val searchQuery: String = "",
    val isSyncingLibrary: Boolean = false,
    val currentStorageFilter: com.theveloper.pixelplay.data.model.StorageFilter = com.theveloper.pixelplay.data.model.StorageFilter.ALL,
    val hideLocalMedia: Boolean = false,
    val showQueueItemUndoBar: Boolean = false,
    val lastRemovedQueueSong: Song? = null,
    val lastRemovedQueueIndex: Int = -1
)
