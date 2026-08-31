package com.theveloper.pixelplay.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.database.LyricsDao
import com.theveloper.pixelplay.data.database.LyricsEntity
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsDocument
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.SyncedWord
import com.theveloper.pixelplay.data.netease.NeteaseRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.qqmusic.QqLyricsFetcher
import com.theveloper.pixelplay.utils.LyricsUtils
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test

class LyricsRepositoryImplTest {

    @Test
    fun parseBestEmbeddedLyricsField_prefersSyncedLyricsWhenLyricsFieldIsPlain() {
        val result = parseBestEmbeddedLyricsField(
            mapOf(
                "LYRICS" to arrayOf("plain lyrics only"),
                "SYNCEDLYRICS" to arrayOf("[00:01.00]Synced lyrics")
            )
        )

        assertThat(result).isNotNull()
        assertThat(result!!.synced).hasSize(1)
        assertThat(result.synced!!.first().line).isEqualTo("Synced lyrics")
        assertThat(result.areFromRemote).isFalse()
    }

    @Test
    fun getLyrics_importsLegacySongColumnThroughDaoAfterStorageMiss() = runTest {
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val musicDao = mockk<MusicDao>(relaxed = true)
        coEvery { musicDao.getLegacySongLyrics(12L) } returns "[00:01.00]Hello again"
        val repository = createRepository(lyricsDao = lyricsDao, musicDao = musicDao)
        val song = testSong(
            id = "12",
            title = "Track",
            artist = "Artist",
            lyrics = "[00:02.00]This in-memory field must not bypass Room migration",
            duration = 180_000L
        )

        val lyrics = repository.getLyrics(song)

        assertThat(lyrics).isNotNull()
        assertThat(lyrics!!.areFromRemote).isFalse()
        assertThat(lyrics.synced).isNotEmpty()
        assertThat(lyrics.synced!!.first().line).isEqualTo("Hello again")
        coVerify(exactly = 1) { lyricsDao.getLyrics("library:12") }
        coVerify(exactly = 1) {
            lyricsDao.insert(match { it.songId == 12L && it.source == "song_field" })
        }
        coVerify(exactly = 1) { musicDao.clearLegacySongLyrics(12L) }
    }

    @Test
    fun getLyrics_migratesLegacyJsonForExplicitLibraryIdentityAndDeletesIt() = runTest {
        val filesDir = Files.createTempDirectory("pixelplay-legacy-json").toFile()
        val legacyFile = File(filesDir, "lyrics/12.json").apply {
            parentFile?.mkdirs()
            writeText("""{"plainLyrics":null,"syncedLyrics":"[00:01.00]Legacy JSON"}""")
        }
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val repository = createRepository(
            lyricsDao = lyricsDao,
            context = testContext(filesDir)
        )
        val song = testSong(
            id = "12",
            libraryId = 12L,
            title = "Track",
            artist = "Artist",
            contentUriString = "content://media/external/audio/media/12",
            duration = 180_000L
        )

        val lyrics = repository.getLyrics(song)

        assertThat(lyrics?.synced?.firstOrNull()?.line).isEqualTo("Legacy JSON")
        assertThat(legacyFile.exists()).isFalse()
        coVerify { lyricsDao.insert(match { it.source == "json_cache" && it.songId == 12L }) }
    }

    @Test
    fun getLyrics_doesNotImportAmbiguousLegacyJsonForTransientOnlineSong() = runTest {
        val filesDir = Files.createTempDirectory("pixelplay-transient-json").toFile()
        val legacyFile = File(filesDir, "lyrics/12.json").apply {
            parentFile?.mkdirs()
            writeText("""{"plainLyrics":null,"syncedLyrics":"[00:01.00]Wrong source"}""")
        }
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val repository = createRepository(
            lyricsDao = lyricsDao,
            context = testContext(filesDir),
            enableOnlineLyrics = false
        )
        val song = testSong(
            id = "12",
            libraryId = null,
            title = "Online Track",
            artist = "Artist",
            contentUriString = "qqmusic://12",
            duration = 180_000L
        )

        val lyrics = repository.getLyrics(song)

        assertThat(lyrics).isNull()
        assertThat(legacyFile.exists()).isTrue()
        coVerify(exactly = 0) { lyricsDao.insert(match { it.source == "json_cache" }) }
    }

    @Test
    fun getLyrics_doesNotUseInMemorySongLyricsAsPersistentCache() = runTest {
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val repository = createRepository(
            lyricsDao = lyricsDao,
            enableOnlineLyrics = false
        )
        val song = testSong(
            id = "12",
            libraryId = null,
            title = "Online Track",
            artist = "Artist",
            contentUriString = "qqmusic://12",
            lyrics = "[00:01.00]Stale in-memory lyrics",
            duration = 180_000L
        )

        val lyrics = repository.getLyrics(song)

        assertThat(lyrics).isNull()
        coVerify(exactly = 0) { lyricsDao.insert(match { it.source == "song_field" }) }
    }

    @Test
    fun fetchFromRemote_skipsStoredLyricsAndFetchesRemoteSource() = runTest {
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val qqLyricsFetcher = createQqLyricsFetcherMock()
        val neteaseRepository = mockk<NeteaseRepository>(relaxed = true)
        coEvery { lyricsDao.getLyrics("uri:netease://77") } returns LyricsEntity(
            mediaKey = "uri:netease://77",
            songId = 77L,
            content = "[00:01.00]Stored line",
            isSynced = true,
            source = "manual"
        )
        coEvery { neteaseRepository.getLyricsPayload(77L) } returns Result.success(
            neteasePayload(lrc = "[00:01.00]Remote line")
        )
        val repository = createRepository(
            lyricsDao = lyricsDao,
            qqLyricsFetcher = qqLyricsFetcher,
            neteaseRepository = neteaseRepository
        )
        val song = testSong(
            id = "77",
            title = "Stored Track",
            artist = "Artist",
            contentUriString = "netease://77",
            neteaseId = 77L,
            duration = 180_000L
        )

        val result = repository.fetchFromRemote(song)

        assertThat(result.isSuccess).isTrue()
        val (lyrics, rawLyrics) = result.getOrThrow()
        assertThat(rawLyrics).contains("Remote line")
        assertThat(lyrics.synced).isNotEmpty()
        assertThat(lyrics.synced!!.first().line).isEqualTo("Remote line")
        assertThat(lyrics.areFromRemote).isTrue()
        coVerify(exactly = 1) { qqLyricsFetcher.fetch(any()) }
        coVerify(exactly = 0) { lyricsDao.getLyrics("uri:netease://77") }
        coVerify(exactly = 1) { neteaseRepository.getLyricsPayload(77L) }
    }

    @Test
    fun getStoredLyrics_returnsDatabaseLyricsContent() = runTest {
        val rawPayload = "[00:01.00]Hello"
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        coEvery { lyricsDao.getLyrics("library:88") } returns LyricsEntity(
            mediaKey = "library:88",
            songId = 88L,
            content = rawPayload,
            isSynced = true,
            source = "qq_music"
        )
        val repository = createRepository(
            lyricsDao = lyricsDao,
            qqLyricsFetcher = createQqLyricsFetcherMock()
        )
        val song = testSong(
            id = "88",
            title = "Hello",
            artist = "Singer",
            duration = 180_000L
        )

        val stored = repository.getStoredLyrics(song)

        assertThat(stored).isNotNull()
        assertThat(stored!!.second).isEqualTo(rawPayload)
        assertThat(stored.first.areFromRemote).isFalse()
        assertThat(stored.first.synced).isNotEmpty()
        assertThat(stored.first.synced!!.first().line).isEqualTo("Hello")
        coVerify(exactly = 1) {
            lyricsDao.insert(match { it.songId == 88L && it.schemaVersion == LyricsDocument.CURRENT_SCHEMA_VERSION })
        }
    }

    @Test
    fun getStoredLyrics_readsStructuredPayloadWithoutLegacyReparse() = runTest {
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val expected = Lyrics(
            synced = listOf(
                SyncedLine(
                    time = 1_000,
                    line = "Structured line",
                    words = listOf(SyncedWord(time = 1_000, word = "Structured")),
                    translation = "Translated"
                )
            ),
            areFromRemote = true
        )
        val document = LyricsDocument(
            lyrics = expected,
            rawContent = "structured-raw",
            sourceTag = "qq_music"
        )
        coEvery { lyricsDao.getLyrics("library:188") } returns LyricsEntity(
            mediaKey = "library:188",
            songId = 188L,
            content = "legacy-fallback",
            isSynced = true,
            source = "qq_music",
            schemaVersion = LyricsDocument.CURRENT_SCHEMA_VERSION,
            payloadJson = Gson().toJson(document),
            updatedAt = 123L
        )
        val repository = createRepository(
            lyricsDao = lyricsDao,
            qqLyricsFetcher = createQqLyricsFetcherMock()
        )

        val stored = repository.getStoredLyrics(
            testSong(id = "188", title = "Structured", artist = "Singer", duration = 180_000L)
        )

        assertThat(stored).isNotNull()
        assertThat(stored!!.second).isEqualTo("structured-raw")
        assertThat(stored.first.synced!!.first().words).hasSize(1)
        assertThat(stored.first.synced!!.first().translation).isEqualTo("Translated")
        coVerify(exactly = 0) { lyricsDao.insert(any()) }
    }

    @Test
    fun getStoredLyrics_importsLegacyJsonIntoRoomThenDeletesFile() = runTest {
        val filesDir = Files.createTempDirectory("pixelplay-legacy-lyrics").toFile()
        val lyricsDir = File(filesDir, "lyrics").apply { mkdirs() }
        val legacyFile = File(lyricsDir, "288.json").apply {
            writeText(
                """{"plainLyrics":null,"syncedLyrics":"[00:01.00]Imported line", "wordByWordLyrics":null}"""
            )
        }
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        coEvery { lyricsDao.getLyrics("library:288") } returns null
        val repository = createRepository(
            lyricsDao = lyricsDao,
            context = testContext(filesDir)
        )

        val stored = repository.getStoredLyrics(
            testSong(id = "288", title = "Imported", artist = "Singer", duration = 180_000L)
        )

        assertThat(stored).isNotNull()
        assertThat(stored!!.first.synced!!.first().line).isEqualTo("Imported line")
        assertThat(legacyFile.exists()).isFalse()
        coVerify(exactly = 1) {
            lyricsDao.insert(match { it.songId == 288L && it.source == "json_cache" })
        }
    }

    @Test
    fun getStoredLyrics_parsesNeteaseOfficialPayloadFromDatabase() = runTest {
        val rawPayload = neteasePayload(
            lrc = """
                {"t":0,"c":[{"tx":"作词: "},{"tx":"Jason Mraz"}]}
                {"t":1000,"c":[{"tx":"制作人: "},{"tx":"Martin Terefe"}]}
                [00:12.48]Official line
            """.trimIndent(),
            tlyric = "[00:12.48]官方翻译"
        )
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        coEvery { lyricsDao.getLyrics("uri:netease://89") } returns LyricsEntity(
            mediaKey = "uri:netease://89",
            songId = 89L,
            content = rawPayload,
            isSynced = true,
            source = "netease_official"
        )
        val repository = createRepository(
            lyricsDao = lyricsDao,
            qqLyricsFetcher = createQqLyricsFetcherMock()
        )
        val song = testSong(
            id = "89",
            title = "Cloud Song",
            artist = "Singer",
            contentUriString = "netease://89",
            neteaseId = 89L,
            duration = 180_000L
        )

        val stored = repository.getStoredLyrics(song)

        assertThat(stored).isNotNull()
        val synced = stored!!.first.synced.orEmpty()
        assertThat(synced.map { it.line }).containsExactly("Official line")
        assertThat(synced.first().translation).isEqualTo("官方翻译")
    }

    @Test
    fun fetchFromRemote_usesNeteaseOfficialLyricsWhenTtmlDisabled() = runTest {
        val neteaseRepository = mockk<NeteaseRepository>(relaxed = true)
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        coEvery { lyricsDao.getLyrics("uri:netease://123") } returns null
        coEvery { neteaseRepository.getLyricsPayload(123L) } returns Result.success(
            neteasePayload(
                lrc = "[00:01.00]Official line\n"
            )
        )
        val repository = createRepository(
            neteaseRepository = neteaseRepository,
            lyricsDao = lyricsDao,
            enableOnlineTtmlLyrics = false
        )
        val song = testSong(
            id = "123",
            title = "Cloud Song",
            artist = "Singer",
            contentUriString = "netease://123",
            neteaseId = 123L,
            duration = 200_000L
        )

        val result = repository.fetchFromRemote(song)

        assertThat(result.exceptionOrNull()).isNull()
        assertThat(result.isSuccess).isTrue()
        val (lyrics, rawLyrics) = result.getOrThrow()
        assertThat(rawLyrics).contains("Official line")
        assertThat(lyrics.synced!!.first().line).isEqualTo("Official line")
        assertThat(lyrics.areFromRemote).isTrue()
        coVerify(exactly = 1) { lyricsDao.insert(any()) }
    }

    @Test
    fun searchRemoteByQuery_returnsQqResultsWhenEnabled() = runTest {
        val qqLyricsFetcher = createQqLyricsFetcherMock()
        val expected = listOf(
            LyricsSearchResult(
                record = LyricsSearchRecord(
                    id = "qq-1",
                    name = "Song",
                    artistName = "Artist",
                    albumName = "Album",
                    sourceName = "QQ Music",
                    sourceUrl = "https://y.qq.com/"
                ),
                lyrics = Lyrics(plain = listOf("line"), areFromRemote = true),
                rawLyrics = "[00:01.00]line"
            )
        )
        coEvery { qqLyricsFetcher.searchByQuery("Song", "Artist") } returns expected
        val repository = createRepository(qqLyricsFetcher = qqLyricsFetcher)

        val result = repository.searchRemoteByQuery("Song", "Artist")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().first).isEqualTo("Song Artist")
        assertThat(result.getOrThrow().second).hasSize(1)
    }

    @Test
    fun fetchFromRemote_doesNotUseQqForLocalSongsWhenOnlineLyricsDisabled() = runTest {
        val qqLyricsFetcher = createQqLyricsFetcherMock()
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        coEvery { lyricsDao.getLyrics("library:201") } returns null
        val repository = createRepository(
            qqLyricsFetcher = qqLyricsFetcher,
            lyricsDao = lyricsDao,
            enableOnlineLyrics = false
        )
        val song = testSong(
            id = "201",
            title = "Local Song",
            artist = "Artist",
            path = "/music/local.mp3",
            contentUriString = "file:///music/local.mp3",
            duration = 190_000L
        )

        val result = repository.fetchFromRemote(song)

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { qqLyricsFetcher.fetch(any()) }
    }

    @Test
    fun parseNeteaseLyricsPayload_parsesOfficialLrcPayload() = runTest {
        val repository = createRepository()
        val payload = neteasePayload(
            lrc = "[00:01.00]Official line\n"
        )
        assertThat(payload).contains("Official line")
        assertThat(LyricsUtils.parseLyrics("[00:01.00]Official line\n").synced).isNotEmpty()

        val lyrics = repository.parseNeteaseLyricsPayload(payload)

        assertThat(lyrics).isNotNull()
        assertThat(lyrics!!.synced).isNotEmpty()
        assertThat(lyrics.synced!!.first().line).isEqualTo("Official line")
    }

    @Test
    fun parseNeteaseLyricsPayload_prefersCompleteYrcWithoutMergingLrcLines() = runTest {
        val lyrics = createRepository().parseNeteaseLyricsPayload(
            neteasePayload(
                lrc = "[00:01.00]Line-level text\n",
                yrc = "[1000,1000]Word(0,500) level(500,500)",
                ytlrc = "[00:01.00]逐字翻译\n"
            )
        )

        val firstLine = requireNotNull(lyrics).synced.orEmpty().first()
        assertThat(firstLine.line).isEqualTo("Word level")
        assertThat(firstLine.words.orEmpty().map { it.word }).containsExactly("Word", "level").inOrder()
        assertThat(firstLine.translation).isEqualTo("逐字翻译")
    }

    @Test
    fun fetchFromRemote_usesNeteaseCandidateWithoutCrossSourceQqWordLevelMerge() = runTest {
        val neteaseRepository = mockk<NeteaseRepository>(relaxed = true)
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val qqLyricsFetcher = createQqLyricsFetcherMock()
        coEvery { lyricsDao.getLyrics("uri:netease://124") } returns null
        coEvery { neteaseRepository.getLyricsPayload(124L) } returns Result.success(
            neteasePayload(
                lrc = "[00:01.00]Official line\n",
                tlyric = "[00:01.00]官方翻译\n"
            )
        )
        coEvery { qqLyricsFetcher.fetch(any()) } returns QqLyricsFetcher.FetchResult(
            lyrics = Lyrics(
                synced = listOf(
                    com.theveloper.pixelplay.data.model.SyncedLine(
                        time = 1_000,
                        line = "QQ line",
                        words = listOf(
                            com.theveloper.pixelplay.data.model.SyncedWord(
                                time = 1_000,
                                word = "QQ",
                                startsNewWord = true
                            ),
                            com.theveloper.pixelplay.data.model.SyncedWord(
                                time = 1_200,
                                word = "line",
                                startsNewWord = true
                            )
                        )
                    )
                ),
                plain = listOf("QQ line"),
                areFromRemote = true
            ),
            rawLyrics = "qqlyrics://mock",
            metadata = QqLyricsFetcher.FetchMetadata(
                sourceTag = "qq_qrc",
                hasWordLevel = true,
                hasTranslation = false,
                hasRomanization = false,
                matchType = "fuzzy",
                durationDiffMs = 0L,
                exactPlatformMatch = false
            )
        )
        val repository = createRepository(
            neteaseRepository = neteaseRepository,
            qqLyricsFetcher = qqLyricsFetcher,
            lyricsDao = lyricsDao,
            enableOnlineTtmlLyrics = false
        )
        val song = testSong(
            id = "124",
            title = "Cloud Song",
            artist = "Singer",
            contentUriString = "netease://124",
            neteaseId = 124L,
            duration = 200_000L
        )

        val result = repository.fetchFromRemote(song)

        assertThat(result.isSuccess).isTrue()
        val lyrics = result.getOrThrow().first
        assertThat(lyrics.synced).isNotEmpty()
        val firstLine = lyrics.synced.orEmpty().first()
        assertThat(firstLine.line).isEqualTo("Official line")
        assertThat(firstLine.translation).isEqualTo("官方翻译")
        assertThat(firstLine.words).isNull()
    }

    @Test
    fun getLyrics_usesEmbeddedLyricsWithoutAutomaticQqWordLevelFallbackForLocalSongs() = runTest {
        val qqLyricsFetcher = createQqLyricsFetcherMock()
        val musicDao = mockk<MusicDao>(relaxed = true)
        coEvery { musicDao.getLegacySongLyrics(301L) } returns "[00:01.00]Embedded line"
        coEvery { qqLyricsFetcher.fetch(any()) } returns QqLyricsFetcher.FetchResult(
            lyrics = Lyrics(
                synced = listOf(
                    SyncedLine(
                        time = 1_000,
                        line = "QQ line",
                        words = listOf(
                            SyncedWord(time = 1_000, word = "QQ", startsNewWord = true),
                            SyncedWord(time = 1_200, word = "line", startsNewWord = true)
                        )
                    )
                ),
                plain = listOf("QQ line"),
                areFromRemote = true
            ),
            rawLyrics = "qqlyrics://mock",
            metadata = QqLyricsFetcher.FetchMetadata(
                sourceTag = "qq_qrc",
                hasWordLevel = true,
                hasTranslation = false,
                hasRomanization = false,
                matchType = "fuzzy",
                durationDiffMs = 0L,
                exactPlatformMatch = false
            )
        )
        val repository = spyk(
            createRepository(
                qqLyricsFetcher = qqLyricsFetcher,
                musicDao = musicDao
            )
        )
        val song = testSong(
            id = "301",
            title = "Embedded Song",
            artist = "Singer",
            path = "/music/embedded.mp3",
            contentUriString = "file:///music/embedded.mp3",
            lyrics = "[00:01.00]Embedded line",
            duration = 180_000L
        )

        val result = repository.getLyrics(song)

        assertThat(result).isNotNull()
        val firstLine = result!!.synced.orEmpty().first()
        assertThat(firstLine.line).isEqualTo("Embedded line")
        assertThat(firstLine.words).isNull()
        coVerify(exactly = 0) { qqLyricsFetcher.fetch(any()) }
    }

    @Test
    fun getLyrics_usesQqFallbackWhenLocalSongHasNoEmbeddedOrLocalLyrics() = runTest {
        val qqLyricsFetcher = createQqLyricsFetcherMock()
        coEvery { qqLyricsFetcher.fetch(any()) } returns QqLyricsFetcher.FetchResult(
            lyrics = Lyrics(
                synced = listOf(
                    SyncedLine(
                        time = 1_000,
                        line = "QQ fallback line",
                        words = listOf(
                            SyncedWord(time = 1_000, word = "QQ", startsNewWord = true),
                            SyncedWord(time = 1_200, word = "fallback", startsNewWord = true),
                            SyncedWord(time = 1_500, word = "line", startsNewWord = true)
                        )
                    )
                ),
                plain = listOf("QQ fallback line"),
                areFromRemote = true
            ),
            rawLyrics = "qqlyrics://mock",
            metadata = QqLyricsFetcher.FetchMetadata(
                sourceTag = "qq_qrc",
                hasWordLevel = true,
                hasTranslation = false,
                hasRomanization = false,
                matchType = "fuzzy",
                durationDiffMs = 0L,
                exactPlatformMatch = false
            )
        )
        val repository = createRepository(qqLyricsFetcher = qqLyricsFetcher)
        val song = testSong(
            id = "302",
            title = "No Local Lyrics",
            artist = "Singer",
            path = "/music/no-local-lyrics.mp3",
            contentUriString = "file:///music/no-local-lyrics.mp3",
            duration = 180_000L
        )

        val result = repository.getLyrics(song)

        assertThat(result).isNotNull()
        assertThat(result!!.areFromRemote).isTrue()
        val firstLine = result.synced.orEmpty().first()
        assertThat(firstLine.line).isEqualTo("QQ fallback line")
        assertThat(firstLine.words.orEmpty().map { it.word }).containsExactly("QQ", "fallback", "line").inOrder()
        coVerify(exactly = 1) { qqLyricsFetcher.fetch(any()) }
    }

    @Test
    fun transientPluginLyrics_surviveRepositoryRecreationUsingSourceAwareKey() = runTest {
        val sources = listOf(
            "qq-search" to "qqmusic://mid-1",
            "kugou-search" to "kugou://hash-1",
            "kuwo-search" to "kuwo://music-1"
        )

        for ((id, sourceUri) in sources) {
            val firstDao = mockk<LyricsDao>(relaxed = true)
            val persisted = slot<LyricsEntity>()
            coEvery { firstDao.insert(capture(persisted)) } returns Unit
            val firstFetcher = createQqLyricsFetcherMock()
            coEvery { firstFetcher.fetch(any()) } returns QqLyricsFetcher.FetchResult(
                lyrics = Lyrics(
                    synced = listOf(SyncedLine(time = 1_000, line = "Persisted remote line")),
                    plain = listOf("Persisted remote line"),
                    areFromRemote = true
                ),
                rawLyrics = "[00:01.00]Persisted remote line",
                metadata = QqLyricsFetcher.FetchMetadata(
                    sourceTag = "qq_lrc",
                    hasWordLevel = false,
                    hasTranslation = false,
                    hasRomanization = false,
                    matchType = "strict_metadata",
                    durationDiffMs = 0L,
                    exactPlatformMatch = sourceUri.startsWith("qqmusic:")
                )
            )
            val song = testSong(
                id = id,
                title = "Remote Track",
                artist = "Remote Artist",
                contentUriString = sourceUri,
                duration = 180_000L
            )

            val firstResult = createRepository(
                lyricsDao = firstDao,
                qqLyricsFetcher = firstFetcher,
                enableOnlineTtmlLyrics = false
            ).getLyrics(song)

            assertThat(firstResult).isNotNull()
            assertThat(persisted.captured.mediaKey).isEqualTo("uri:$sourceUri")
            assertThat(persisted.captured.songId).isNull()

            val secondDao = mockk<LyricsDao>(relaxed = true)
            coEvery { secondDao.getLyrics("uri:$sourceUri") } returns persisted.captured
            val secondFetcher = createQqLyricsFetcherMock()
            val restored = createRepository(
                lyricsDao = secondDao,
                qqLyricsFetcher = secondFetcher,
                enableOnlineTtmlLyrics = false
            ).getLyrics(song)

            assertThat(restored).isNotNull()
            assertThat(restored!!.synced.orEmpty().first().line).isEqualTo("Persisted remote line")
            coVerify(exactly = 0) { secondFetcher.fetch(any()) }
        }
    }

    @Test
    fun manualUpdateAndReset_useTransientSourceIdentity() = runTest {
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val repository = createRepository(lyricsDao = lyricsDao)
        val song = testSong(
            id = "search-result",
            title = "Remote Track",
            artist = "Artist",
            contentUriString = "qqmusic://mid-manual",
            duration = 180_000L
        )

        repository.updateLyrics(song, "[00:01.00]Manual line")
        repository.resetLyrics(song)

        coVerify(exactly = 1) {
            lyricsDao.insert(match {
                it.mediaKey == "uri:qqmusic://mid-manual" && it.songId == null && it.source == "manual"
            })
        }
        coVerify(exactly = 1) { lyricsDao.deleteLyrics("uri:qqmusic://mid-manual") }
    }

    @Test
    fun parseLyrics_parsesSimpleLrc() {
        val lyrics = LyricsUtils.parseLyrics("[00:01.00]Official line\n")

        assertThat(lyrics.synced).isNotEmpty()
        assertThat(lyrics.synced!!.first().line).isEqualTo("Official line")
    }

    private fun createRepository(
        lyricsDao: LyricsDao = mockk(relaxed = true),
        qqLyricsFetcher: QqLyricsFetcher = createQqLyricsFetcherMock(),
        neteaseRepository: NeteaseRepository = mockk(relaxed = true),
        enableOnlineTtmlLyrics: Boolean = true,
        enableOnlineLyrics: Boolean = true,
        context: Context = testContext(),
        musicDao: MusicDao = mockk(relaxed = true)
    ): LyricsRepositoryImpl {
        val userPreferencesRepository = mockk<UserPreferencesRepository>(relaxed = true).also { preferences ->
            every { preferences.enableOnlineTtmlLyricsFlow } returns flowOf(enableOnlineTtmlLyrics)
            every { preferences.enableOnlineLyricsFlow } returns flowOf(enableOnlineLyrics)
        }

        return LyricsRepositoryImpl(
            context = context,
            neteaseRepository = neteaseRepository,
            qqLyricsFetcher = qqLyricsFetcher,
            userPreferencesRepository = userPreferencesRepository,
            lyricsDao = lyricsDao,
            musicDao = musicDao,
            okHttpClient = mockk<OkHttpClient>(relaxed = true),
            lyricsCoordinator = LyricsCoordinator()
        )
    }

    private fun createQqLyricsFetcherMock(): QqLyricsFetcher {
        val parser = QqLyricsFetcher(
            qqMusicApiService = mockk(relaxed = true),
            qqMusicDao = mockk(relaxed = true)
        )
        return mockk {
            every { parsePersistedRaw(any()) } answers { parser.parsePersistedRaw(firstArg<String>()) }
            coEvery { fetch(any()) } returns null
            coEvery { search(any()) } returns emptyList()
            coEvery { searchByQuery(any(), any(), any()) } returns emptyList()
        }
    }

    private fun testContext(filesDir: File = Files.createTempDirectory("pixelplay-lyrics-test").toFile()): Context {
        return mockk<Context>(relaxed = true) {
            every { this@mockk.filesDir } returns filesDir
        }
    }

    private fun testSong(
        id: String,
        libraryId: Long? = id.toLongOrNull(),
        title: String,
        artist: String,
        path: String = "",
        contentUriString: String = "",
        lyrics: String? = null,
        neteaseId: Long? = null,
        duration: Long
    ): Song {
        return Song(
            id = id,
            libraryId = libraryId,
            title = title,
            artist = artist,
            artistId = 5L,
            album = "Album",
            albumId = 8L,
            path = path,
            contentUriString = contentUriString,
            albumArtUriString = null,
            duration = duration,
            lyrics = lyrics,
            mimeType = "audio/mpeg",
            bitrate = 320_000,
            sampleRate = 44_100
            ,
            neteaseId = neteaseId
        )
    }

    private fun neteasePayload(
        lrc: String = "",
        tlyric: String = "",
        romalrc: String = "",
        yrc: String = "",
        ytlrc: String = "",
        yromalrc: String = ""
    ): String {
        fun escape(value: String): String = buildString {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }

        return """
            {"code":200,"lrc":{"lyric":"${escape(lrc)}"},"tlyric":{"lyric":"${escape(tlyric)}"},"romalrc":{"lyric":"${escape(romalrc)}"},"yrc":{"lyric":"${escape(yrc)}"},"ytlrc":{"lyric":"${escape(ytlrc)}"},"yromalrc":{"lyric":"${escape(yromalrc)}"}}
        """.trimIndent()
    }
}
