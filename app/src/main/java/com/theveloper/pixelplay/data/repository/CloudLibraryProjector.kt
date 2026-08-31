package com.theveloper.pixelplay.data.repository

import com.theveloper.pixelplay.data.database.AlbumEntity
import com.theveloper.pixelplay.data.database.ArtistEntity
import com.theveloper.pixelplay.data.database.FavoritesDao
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.NeteaseDao
import com.theveloper.pixelplay.data.database.QqMusicDao
import com.theveloper.pixelplay.data.database.SongArtistCrossRef
import com.theveloper.pixelplay.data.database.SongEntity
import com.theveloper.pixelplay.data.database.SourceType
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.stream.CloudMusicUtils
import javax.inject.Inject
import javax.inject.Singleton

/** The single projection boundary from account caches into the unified library. */
@Singleton
class CloudLibraryProjector @Inject constructor(
    private val musicDao: MusicDao,
    private val neteaseDao: NeteaseDao,
    private val qqMusicDao: QqMusicDao,
    private val favoritesDao: FavoritesDao,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository
) {
    suspend fun hasCachedSongs(): Boolean =
        neteaseDao.getNeteaseCount() > 0 || qqMusicDao.getAllQqMusicSongsList().isNotEmpty()

    suspend fun projectAll() {
        projectNetease()
        projectQqMusic()
    }

    suspend fun projectNetease() {
        val cachedSongs = neteaseDao.getAllNeteaseSongsList()
        val existingIds = musicDao.getAllNeteaseSongIds()
        if (cachedSongs.isEmpty()) {
            deleteUnretained(existingIds, NETEASE_SOURCE)
            return
        }

        val songs = ArrayList<SongEntity>(cachedSongs.size)
        val artists = LinkedHashMap<Long, ArtistEntity>()
        val albums = LinkedHashMap<Long, AlbumEntity>()
        val crossRefs = mutableListOf<SongArtistCrossRef>()

        cachedSongs.forEach { cached ->
            val songId = OnlineLibraryIds.neteaseSong(cached.neteaseId)
            val artistNames = CloudMusicUtils.parseArtistNames(cached.artist)
            val primaryArtistName = artistNames.firstOrNull() ?: UNKNOWN_ARTIST
            val primaryArtistId = OnlineLibraryIds.neteaseArtist(primaryArtistName)

            artistNames.forEachIndexed { index, name ->
                val artistId = OnlineLibraryIds.neteaseArtist(name)
                artists.putIfAbsent(artistId, ArtistEntity(artistId, name, 0, null))
                crossRefs += SongArtistCrossRef(songId, artistId, index == 0)
            }

            val albumName = cached.album.ifBlank { UNKNOWN_ALBUM }
            val albumId = OnlineLibraryIds.neteaseAlbum(cached.albumId, albumName)
            albums.putIfAbsent(
                albumId,
                AlbumEntity(
                    id = albumId,
                    title = albumName,
                    artistName = primaryArtistName,
                    artistId = primaryArtistId,
                    songCount = 0,
                    dateAdded = cached.dateAdded,
                    year = 0,
                    albumArtUriString = cached.albumArtUrl
                )
            )
            songs += SongEntity(
                id = songId,
                title = cached.title,
                artistName = cached.artist.ifBlank { primaryArtistName },
                artistId = primaryArtistId,
                albumArtist = null,
                albumName = albumName,
                albumId = albumId,
                contentUriString = "netease://${cached.neteaseId}",
                albumArtUriString = cached.albumArtUrl,
                duration = cached.duration,
                genre = NETEASE_GENRE,
                filePath = "",
                parentDirectoryPath = NETEASE_PARENT_DIRECTORY,
                isFavorite = false,
                lyrics = null,
                trackNumber = 0,
                year = 0,
                dateAdded = cached.dateAdded.takeIf { it > 0 } ?: System.currentTimeMillis(),
                mimeType = cached.mimeType,
                bitrate = cached.bitrate,
                sampleRate = null,
                sourceType = SourceType.NETEASE
            )
        }

        val currentIds = songs.mapTo(HashSet()) { it.id }
        val protectedIds = protectedIds(NETEASE_SOURCE)
        sync(
            songs = songs,
            albums = albums,
            artists = artists,
            crossRefs = crossRefs,
            deletedIds = existingIds.filterNot { it in currentIds || it in protectedIds }
        )
    }

    suspend fun projectQqMusic() {
        val cachedSongs = qqMusicDao.getAllQqMusicSongsList()
        val existingIds = musicDao.getAllQqMusicSongIds()
        if (cachedSongs.isEmpty()) {
            deleteUnretained(existingIds, QQ_SOURCE)
            return
        }

        val songs = ArrayList<SongEntity>(cachedSongs.size)
        val artists = LinkedHashMap<Long, ArtistEntity>()
        val albums = LinkedHashMap<Long, AlbumEntity>()
        val crossRefs = mutableListOf<SongArtistCrossRef>()

        cachedSongs.forEach { cached ->
            val songId = OnlineLibraryIds.qqSong(cached.songMid)
            val artistNames = CloudMusicUtils.parseArtistNames(cached.artist)
            val primaryArtistName = artistNames.firstOrNull() ?: UNKNOWN_ARTIST
            val primaryArtistId = OnlineLibraryIds.qqArtist(primaryArtistName)

            artistNames.forEachIndexed { index, name ->
                val artistId = OnlineLibraryIds.qqArtist(name)
                artists.putIfAbsent(artistId, ArtistEntity(artistId, name, 0, null))
                crossRefs += SongArtistCrossRef(songId, artistId, index == 0)
            }

            val albumName = cached.album.ifBlank { UNKNOWN_ALBUM }
            val albumId = OnlineLibraryIds.qqAlbum(cached.albumMid, albumName)
            albums.putIfAbsent(
                albumId,
                AlbumEntity(
                    id = albumId,
                    title = albumName,
                    artistName = primaryArtistName,
                    artistId = primaryArtistId,
                    songCount = 0,
                    dateAdded = cached.dateAdded,
                    year = 0,
                    albumArtUriString = cached.albumArtUrl
                )
            )
            songs += SongEntity(
                id = songId,
                title = cached.title,
                artistName = cached.artist.ifBlank { primaryArtistName },
                artistId = primaryArtistId,
                albumArtist = null,
                albumName = albumName,
                albumId = albumId,
                contentUriString = "qqmusic://${cached.songMid}",
                albumArtUriString = cached.albumArtUrl,
                duration = cached.duration,
                genre = QQ_GENRE,
                filePath = "",
                parentDirectoryPath = QQ_PARENT_DIRECTORY,
                isFavorite = false,
                lyrics = null,
                trackNumber = 0,
                year = 0,
                dateAdded = cached.dateAdded.takeIf { it > 0 } ?: System.currentTimeMillis(),
                mimeType = cached.mimeType,
                bitrate = cached.bitrate,
                sampleRate = null,
                sourceType = SourceType.QQMUSIC
            )
        }

        val currentIds = songs.mapTo(HashSet()) { it.id }
        val protectedIds = protectedIds(QQ_SOURCE)
        sync(songs, albums, artists, crossRefs, existingIds.filterNot { it in currentIds || it in protectedIds })
    }

    suspend fun removeUnretainedNeteaseSongs() =
        deleteUnretained(musicDao.getAllNeteaseSongIds(), NETEASE_SOURCE)

    suspend fun removeUnretainedQqMusicSongs() =
        deleteUnretained(musicDao.getAllQqMusicSongIds(), QQ_SOURCE)

    private suspend fun sync(
        songs: List<SongEntity>,
        albums: Map<Long, AlbumEntity>,
        artists: Map<Long, ArtistEntity>,
        crossRefs: List<SongArtistCrossRef>,
        deletedIds: List<Long>
    ) {
        val albumCounts = songs.groupingBy(SongEntity::albumId).eachCount()
        musicDao.incrementalSyncMusicData(
            songs = songs,
            albums = albums.values.map { it.copy(songCount = albumCounts[it.id] ?: 0) },
            artists = artists.values.toList(),
            crossRefs = crossRefs,
            deletedSongIds = deletedIds
        )
    }

    private suspend fun deleteUnretained(existingIds: List<Long>, platform: String) {
        musicDao.deleteSongsAndRelatedData(existingIds.filterNot { it in protectedIds(platform) })
    }

    private suspend fun protectedIds(platform: String): Set<Long> {
        val favorites = favoritesDao.getFavoriteSongIdsOnce().toSet()
        val userPlaylists = playlistPreferencesRepository.getPlaylistsOnce()
            .asSequence()
            .filterNot { it.source.equals(platform, ignoreCase = true) }
            .flatMap { it.songIds.asSequence() }
            .mapNotNull(String::toLongOrNull)
            .toSet()
        return favorites + userPlaylists
    }

    private companion object {
        const val NETEASE_SOURCE = "NETEASE"
        const val QQ_SOURCE = "QQMUSIC"
        const val NETEASE_PARENT_DIRECTORY = "/Cloud/Netease"
        const val QQ_PARENT_DIRECTORY = "/Cloud/QQMusic"
        const val NETEASE_GENRE = "Netease Cloud"
        const val QQ_GENRE = "QQ Music"
        const val UNKNOWN_ARTIST = "Unknown Artist"
        const val UNKNOWN_ALBUM = "Unknown Album"
    }
}
