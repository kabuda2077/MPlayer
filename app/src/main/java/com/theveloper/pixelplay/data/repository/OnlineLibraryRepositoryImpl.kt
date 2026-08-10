package com.theveloper.pixelplay.data.repository

import com.theveloper.pixelplay.data.database.AlbumEntity
import com.theveloper.pixelplay.data.database.ArtistEntity
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.SongArtistCrossRef
import com.theveloper.pixelplay.data.database.SongEntity
import com.theveloper.pixelplay.data.database.SourceType
import com.theveloper.pixelplay.data.database.toSong
import com.theveloper.pixelplay.data.model.LibrarySong
import com.theveloper.pixelplay.data.model.OnlineBrowsePlatform
import com.theveloper.pixelplay.data.model.Playlist
import com.theveloper.pixelplay.data.model.RemotePlaylistDetail
import com.theveloper.pixelplay.data.model.TransientOnlineSong
import com.theveloper.pixelplay.data.plugin.PluginSource
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnlineLibraryRepositoryImpl @Inject constructor(
    private val musicDao: MusicDao,
    private val playlistRepository: PlaylistPreferencesRepository
) : OnlineLibraryRepository {

    override suspend fun saveSong(song: TransientOnlineSong): Result<LibrarySong> =
        withContext(Dispatchers.IO) {
            runCatching { saveSongInternal(song) }
        }

    override suspend fun savePlaylist(playlist: RemotePlaylistDetail): Result<Playlist> =
        withContext(Dispatchers.IO) {
            runCatching {
                val savedSongs = playlist.songs.map { saveSongInternal(it) }
                val source = when (playlist.reference.platform) {
                    OnlineBrowsePlatform.NETEASE -> SAVED_NETEASE_SOURCE
                    OnlineBrowsePlatform.QQ -> SAVED_QQ_SOURCE
                }
                val stablePlaylistId = buildString {
                    append(SAVED_PLAYLIST_PREFIX)
                    append(playlist.reference.platform.name.lowercase())
                    append(':')
                    append(playlist.reference.remoteId)
                }
                val existing = playlistRepository.getPlaylistsOnce()
                    .firstOrNull { it.id == stablePlaylistId }
                val songIds = savedSongs.map { it.libraryId.toString() }
                if (existing == null) {
                    playlistRepository.createPlaylist(
                        name = playlist.reference.title,
                        songIds = songIds,
                        coverImageUri = playlist.reference.artworkUri,
                        customId = stablePlaylistId,
                        source = source
                    )
                } else {
                    existing.copy(
                        name = playlist.reference.title,
                        songIds = songIds,
                        coverImageUri = playlist.reference.artworkUri,
                        source = source
                    ).also { playlistRepository.updatePlaylist(it) }
                }
            }
        }

    private suspend fun saveSongInternal(value: TransientOnlineSong): LibrarySong {
        require(value.canSaveToLibrary) {
            "${value.source} online references must be downloaded before adding them to the library"
        }

        musicDao.getSongByContentUriOnce(value.song.contentUriString)?.let { existing ->
            return LibrarySong.require(existing.toSong())
        }

        val song = value.song
        val artistNames = song.artists
            .sortedByDescending { it.isPrimary }
            .map { it.name.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty {
                song.displayArtist
                    .split('/', '&', '、', ',', '，')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
            }
            .ifEmpty { listOf(UNKNOWN_ARTIST) }
            .distinct()
        val primaryArtistName = artistNames.first()
        val songId = when (value.source) {
            PluginSource.NETEASE -> OnlineLibraryIds.neteaseSong(
                value.sourceSongId.toLongOrNull()
                    ?: error("Invalid Netease song ID: ${value.sourceSongId}")
            )
            PluginSource.QQ_MUSIC -> OnlineLibraryIds.qqSong(value.sourceSongId)
            else -> error("Unsupported online library source: ${value.source}")
        }
        val artistId: (String) -> Long = when (value.source) {
            PluginSource.NETEASE -> OnlineLibraryIds::neteaseArtist
            PluginSource.QQ_MUSIC -> OnlineLibraryIds::qqArtist
        }
        val primaryArtistId = artistId(primaryArtistName)
        val albumName = song.album.ifBlank { UNKNOWN_ALBUM }
        val albumId = when (value.source) {
            PluginSource.NETEASE -> OnlineLibraryIds.neteaseAlbum(song.albumId, albumName)
            PluginSource.QQ_MUSIC -> OnlineLibraryIds.qqAlbum(song.albumId.toString(), albumName)
        }
        val now = System.currentTimeMillis()
        val artists = artistNames.map { name ->
            ArtistEntity(id = artistId(name), name = name, trackCount = 0)
        }
        val album = AlbumEntity(
            id = albumId,
            title = albumName,
            artistName = primaryArtistName,
            artistId = primaryArtistId,
            albumArtUriString = song.albumArtUriString,
            songCount = 1,
            dateAdded = song.dateAdded.takeIf { it > 0 } ?: now,
            year = song.year,
            albumArtist = song.albumArtist
        )
        val entity = SongEntity(
            id = songId,
            title = song.title,
            artistName = song.displayArtist.ifBlank { primaryArtistName },
            artistId = primaryArtistId,
            albumArtist = song.albumArtist,
            albumName = albumName,
            albumId = albumId,
            contentUriString = song.contentUriString,
            albumArtUriString = song.albumArtUriString,
            duration = song.duration,
            genre = song.genre,
            filePath = "",
            parentDirectoryPath = when (value.source) {
                PluginSource.NETEASE -> NETEASE_PARENT
                else -> QQ_PARENT
            },
            isFavorite = false,
            lyrics = song.lyrics,
            trackNumber = song.trackNumber,
            discNumber = song.discNumber,
            year = song.year,
            dateAdded = song.dateAdded.takeIf { it > 0 } ?: now,
            mimeType = song.mimeType,
            bitrate = song.bitrate,
            sampleRate = song.sampleRate,
            sourceType = when (value.source) {
                PluginSource.NETEASE -> SourceType.NETEASE
                else -> SourceType.QQMUSIC
            }
        )
        val crossRefs = artistNames.mapIndexed { index, name ->
            SongArtistCrossRef(songId = songId, artistId = artistId(name), isPrimary = index == 0)
        }

        musicDao.insertMusicDataWithCrossRefs(
            songs = listOf(entity),
            albums = listOf(album),
            artists = artists,
            crossRefs = crossRefs
        )
        val persisted = requireNotNull(musicDao.getSongByContentUriOnce(song.contentUriString)) {
            "Online song was not persisted: ${song.contentUriString}"
        }
        return LibrarySong.require(persisted.toSong())
    }

    private companion object {
        const val UNKNOWN_ARTIST = "Unknown Artist"
        const val UNKNOWN_ALBUM = "Unknown Album"
        const val NETEASE_PARENT = "Netease Music"
        const val QQ_PARENT = "QQ Music"
        const val SAVED_PLAYLIST_PREFIX = "saved-online:"
        const val SAVED_NETEASE_SOURCE = "SAVED_NETEASE"
        const val SAVED_QQ_SOURCE = "SAVED_QQMUSIC"
    }
}
