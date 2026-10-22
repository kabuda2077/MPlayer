package com.theveloper.pixelplay.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PixelPlayDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PixelPlayDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @After
    fun tearDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (version in 25..50) {
            context.deleteDatabase(databaseNameFor(version))
        }
        context.deleteDatabase(DB_NAME_33_TO_34)
        context.deleteDatabase(DB_NAME_23_TO_24_DRIFTED)
        context.deleteDatabase(DB_NAME_35_TO_36)
        context.deleteDatabase(DB_NAME_39_TO_40)
        context.deleteDatabase(DB_NAME_46_TO_47)
        context.deleteDatabase(DB_NAME_47_TO_48)
        context.deleteDatabase(DB_NAME_48_TO_49)
        context.deleteDatabase(DB_NAME_49_TO_50)
        context.deleteDatabase(DB_NAME_50_TO_51)
    }

    @Test
    fun migrateEveryExportedSchemaToLatest() {
        for (startVersion in 25..50) {
            helper.createDatabase(databaseNameFor(startVersion), startVersion).close()

            helper.runMigrationsAndValidate(
                databaseNameFor(startVersion),
                PixelPlayDatabaseVersion.LATEST,
                true,
                *ALL_MIGRATIONS
            ).close()
        }
    }

    @Test
    fun migration33To34AddsArtistsJsonColumnToSongs() {
        helper.createDatabase(DB_NAME_33_TO_34, 33).close()

        helper.runMigrationsAndValidate(
            DB_NAME_33_TO_34,
            34,
            true,
            PixelPlayDatabase.MIGRATION_33_34
        ).let { db ->
            val cursor = db.query("PRAGMA table_info(`songs`)")
            try {
                val nameIndex = cursor.getColumnIndex("name")
                val defaultValueIndex = cursor.getColumnIndex("dflt_value")
                var foundArtistsJson = false
                var defaultValue: String? = null

                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == "artists_json") {
                        foundArtistsJson = true
                        defaultValue = cursor.getString(defaultValueIndex)
                        break
                    }
                }

                assertTrue(foundArtistsJson)
                assertEquals("NULL", defaultValue)
            } finally {
                cursor.close()
                db.close()
            }
        }
    }

    @Test
    fun migration23To24RepairsSongsWithoutDateAddedBeforeCreatingIndexes() {
        val openHelper = createDriftedVersion23Database(DB_NAME_23_TO_24_DRIFTED)
        val db = openHelper.writableDatabase

        try {
            PixelPlayDatabase.MIGRATION_23_24.migrate(db)

            val columns = db.tableColumns("songs")
            assertTrue("date_added" in columns)

            db.query("SELECT date_added FROM songs WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }

            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_songs_date_added'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("index_songs_date_added", cursor.getString(0))
            }
        } finally {
            db.close()
            openHelper.close()
        }
    }

    @Test
    fun migration35To36AddsSongsFtsTable() {
        helper.createDatabase(DB_NAME_35_TO_36, 35).close()

        helper.runMigrationsAndValidate(
            DB_NAME_35_TO_36,
            36,
            true,
            PixelPlayDatabase.MIGRATION_35_36
        ).let { db ->
            val cursor = db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'songs_fts'")
            try {
                assertTrue(cursor.moveToFirst())
                assertEquals("songs_fts", cursor.getString(0))
            } finally {
                cursor.close()
                db.close()
            }
        }
    }

    @Test
    fun migration39To40AddsCompositeSongIndexes() {
        helper.createDatabase(DB_NAME_39_TO_40, 39).close()

        helper.runMigrationsAndValidate(
            DB_NAME_39_TO_40,
            40,
            true,
            PixelPlayDatabase.MIGRATION_39_40
        ).let { db ->
            try {
                val indexes = db.tableIndexes("songs")
                assertTrue("index_songs_parent_directory_path_source_type_album_id" in indexes)
                assertTrue("index_songs_parent_directory_path_source_type_id" in indexes)
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun migration50To51PreservesPluginsAndAddsOptionalSourceUrl() {
        helper.createDatabase(DB_NAME_50_TO_51, 50).use { db ->
            db.execSQL("""
                INSERT INTO audio_source_plugins
                (id, name, description, author, version, homepage, enabled, script, created_at, updated_at)
                VALUES ('existing', 'Existing plugin', '', '', '1', '', 1, 'script', 1, 2)
            """.trimIndent())
        }
        helper.runMigrationsAndValidate(DB_NAME_50_TO_51, 51, true, PixelPlayDatabase.MIGRATION_50_51).use { db ->
            db.query("SELECT name, enabled, script, source_url FROM audio_source_plugins WHERE id = 'existing'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Existing plugin", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals("script", cursor.getString(2))
                assertTrue(cursor.isNull(3))
            }
        }
    }

    @Test
    fun migration49To50KeepsSupportedSongsAndDropsRemovedProviders() {
        helper.createDatabase(DB_NAME_49_TO_50, 49).use { db ->
            db.execSQL("INSERT INTO artists(id, name, track_count) VALUES (1, 'Artist', 7)")
            db.execSQL(
                "INSERT INTO albums(id, title, artist_name, artist_id, song_count, date_added, year) " +
                    "VALUES (1, 'Album', 'Artist', 1, 7, 0, 2026)"
            )
            val rows = listOf(
                Triple(100L, "content://media/song/100", 0),
                Triple(102L, "netease://102", 2),
                Triple(104L, "qqmusic://MID104", 4),
                Triple(101L, "telegram://101", 1),
                Triple(103L, "gdrive://103", 3),
                Triple(105L, "navidrome://105", 5),
                Triple(106L, "jellyfin://106", 6)
            )
            rows.forEach { (id, uri, sourceType) ->
                db.execSQL(
                    """
                    INSERT INTO songs(
                        id, title, artist_name, artist_id, album_name, album_id,
                        content_uri_string, duration, file_path, parent_directory_path, source_type
                    ) VALUES (?, ?, 'Artist', 1, 'Album', 1, ?, 180000, ?, '/music', ?)
                    """.trimIndent(),
                    arrayOf<Any?>(id, "Song $id", uri, "/music/$id.mp3", sourceType)
                )
                db.execSQL(
                    "INSERT INTO song_artist_cross_ref(song_id, artist_id, is_primary) VALUES (?, 1, 1)",
                    arrayOf(id)
                )
            }
            db.execSQL(
                """
                INSERT INTO downloads(
                    content_uri_string, title, artist, album, duration, source, platform_song_id,
                    requested_quality, status, progress, size_bytes, storage_mode, created_at, updated_at
                ) VALUES ('telegram://101', 'Removed', 'Artist', 'Album', 1, 'telegram', '101',
                    '320k', 0, 0, 0, 'PUBLIC_MEDIASTORE', 0, 0)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO downloads(
                    content_uri_string, title, artist, album, duration, source, platform_song_id,
                    requested_quality, status, progress, size_bytes, storage_mode, created_at, updated_at
                ) VALUES ('qqmusic://MID104', 'Retained', 'Artist', 'Album', 1, 'tx', 'MID104',
                    '320k', 0, 0, 0, 'PUBLIC_MEDIASTORE', 0, 0)
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(
            DB_NAME_49_TO_50,
            50,
            true,
            PixelPlayDatabase.MIGRATION_49_50
        ).use { db ->
            db.query("SELECT id, source_type FROM songs ORDER BY id").use { cursor ->
                val retained = mutableListOf<Pair<Long, Int>>()
                while (cursor.moveToNext()) retained += cursor.getLong(0) to cursor.getInt(1)
                assertEquals(listOf(100L to 0, 102L to 2, 104L to 4), retained)
            }
            assertTrue("telegram_chat_id" !in db.tableColumns("songs"))
            assertTrue("telegram_file_id" !in db.tableColumns("songs"))

            db.query("SELECT COUNT(*) FROM song_artist_cross_ref").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3, cursor.getInt(0))
            }
            db.query("SELECT source FROM downloads ORDER BY source").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("tx", cursor.getString(0))
                assertTrue(!cursor.moveToNext())
            }

            listOf(
                "telegram_songs", "telegram_channels", "telegram_topics",
                "gdrive_songs", "gdrive_folders", "navidrome_songs", "navidrome_playlists",
                "jellyfin_songs", "jellyfin_playlists"
            ).forEach { table ->
                db.query(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                    arrayOf(table)
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Expected $table to be removed", 0, cursor.getInt(0))
                }
            }
        }
    }

    @Test
    fun migration46To47AddsStructuredLyricsColumnsWithoutChangingContent() {
        helper.createDatabase(DB_NAME_46_TO_47, 46).use { db ->
            db.execSQL(
                "INSERT INTO lyrics (songId, content, isSynced, source) " +
                    "VALUES (7, '[00:01.00]Legacy line', 1, 'legacy')"
            )
        }

        helper.runMigrationsAndValidate(
            DB_NAME_46_TO_47,
            47,
            true,
            PixelPlayDatabase.MIGRATION_46_47
        ).use { db ->
            val columns = db.tableColumns("lyrics")
            assertTrue("schemaVersion" in columns)
            assertTrue("payloadJson" in columns)
            assertTrue("updatedAt" in columns)
            db.query(
                "SELECT content, schemaVersion, payloadJson, updatedAt FROM lyrics WHERE songId = 7"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("[00:01.00]Legacy line", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertTrue(cursor.isNull(2))
                assertEquals(0L, cursor.getLong(3))
            }
        }
    }

    @Test
    fun migration47To48UsesSourceAwareLyricsKeysAndPreservesPayload() {
        helper.createDatabase(DB_NAME_47_TO_48, 47).use { db ->
            db.execSQL("PRAGMA foreign_keys=OFF")
            db.execSQL(
                """
                INSERT INTO songs (
                    id, title, artist_name, artist_id, album_name, album_id,
                    content_uri_string, duration, file_path, parent_directory_path
                ) VALUES (
                    -9, 'Cloud song', 'Artist', 1, 'Album', 1,
                    'netease://12345', 180000, '', ''
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO lyrics (
                    songId, content, isSynced, source, schemaVersion, payloadJson, updatedAt
                ) VALUES (
                    -9, '[00:01.00]Cloud line', 1, 'netease_official', 1, '{"version":1}', 456
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO lyrics (
                    songId, content, isSynced, source, schemaVersion, payloadJson, updatedAt
                ) VALUES (
                    12, '[00:02.00]Local line', 1, 'local_file', 0, NULL, 123
                )
                """.trimIndent()
            )
            db.execSQL("PRAGMA foreign_keys=ON")
        }

        helper.runMigrationsAndValidate(
            DB_NAME_47_TO_48,
            48,
            true,
            PixelPlayDatabase.MIGRATION_47_48
        ).use { db ->
            db.query(
                "SELECT mediaKey, songId, content, source, schemaVersion, payloadJson, updatedAt FROM lyrics ORDER BY mediaKey"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("library:12", cursor.getString(0))
                assertEquals(12L, cursor.getLong(1))
                assertEquals("[00:02.00]Local line", cursor.getString(2))
                assertTrue(cursor.moveToNext())
                assertEquals("uri:netease://12345", cursor.getString(0))
                assertEquals(-9L, cursor.getLong(1))
                assertEquals("[00:01.00]Cloud line", cursor.getString(2))
                assertEquals("netease_official", cursor.getString(3))
                assertEquals(1, cursor.getInt(4))
                assertEquals("{\"version\":1}", cursor.getString(5))
                assertEquals(456L, cursor.getLong(6))
            }
        }
    }

    @Test
    fun migration48To49ReplacesTransientDownloadSentinelWithNull() {
        helper.createDatabase(DB_NAME_48_TO_49, 48).use { db ->
            fun insertDownload(contentUri: String, songId: Long, title: String) {
                db.execSQL(
                    """
                    INSERT INTO downloads (
                        content_uri_string, song_id, title, artist, album,
                        album_art_uri_string, duration, source, platform_song_id,
                        requested_quality, actual_quality, status, progress, size_bytes,
                        mime_type, file_path, public_uri, relative_path, display_name,
                        storage_mode, local_song_id, error_message, created_at, updated_at
                    ) VALUES (
                        '$contentUri', $songId, '$title', 'Artist', 'Album',
                        NULL, 1000, 'source', 'platform-id', 'preferred', NULL, 0, 0, 0,
                        NULL, NULL, NULL, NULL, NULL, 'PUBLIC_MEDIASTORE', NULL, NULL, 1, 1
                    )
                    """.trimIndent()
                )
            }
            insertDownload("qqmusic://mid", 0L, "Transient")
            insertDownload("netease://7", 7L, "Library")
        }

        helper.runMigrationsAndValidate(
            DB_NAME_48_TO_49,
            49,
            true,
            PixelPlayDatabase.MIGRATION_48_49
        ).use { db ->
            db.query("SELECT content_uri_string, song_id FROM downloads ORDER BY content_uri_string").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("netease://7", cursor.getString(0))
                assertEquals(7L, cursor.getLong(1))
                assertTrue(cursor.moveToNext())
                assertEquals("qqmusic://mid", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
        }
    }

    private fun databaseNameFor(startVersion: Int): String = "migration-test-$startVersion"

    private fun createDriftedVersion23Database(
        databaseName: String
    ): SupportSQLiteOpenHelper {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)

        val callback = object : SupportSQLiteOpenHelper.Callback(23) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                        CREATE TABLE IF NOT EXISTS songs (
                            id INTEGER NOT NULL PRIMARY KEY,
                            title TEXT NOT NULL,
                            artist_name TEXT NOT NULL,
                            artist_id INTEGER NOT NULL,
                            album_artist TEXT,
                            album_name TEXT NOT NULL,
                            album_id INTEGER NOT NULL,
                            content_uri_string TEXT NOT NULL,
                            album_art_uri_string TEXT,
                            duration INTEGER NOT NULL,
                            genre TEXT,
                            file_path TEXT NOT NULL,
                            parent_directory_path TEXT NOT NULL,
                            is_favorite INTEGER NOT NULL DEFAULT 0,
                            lyrics TEXT DEFAULT null,
                            track_number INTEGER NOT NULL DEFAULT 0,
                            year INTEGER NOT NULL DEFAULT 0,
                            mime_type TEXT,
                            bitrate INTEGER,
                            sample_rate INTEGER,
                            telegram_chat_id INTEGER,
                            telegram_file_id INTEGER
                        )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                        INSERT INTO songs (
                            id,
                            title,
                            artist_name,
                            artist_id,
                            album_artist,
                            album_name,
                            album_id,
                            content_uri_string,
                            album_art_uri_string,
                            duration,
                            genre,
                            file_path,
                            parent_directory_path,
                            is_favorite,
                            lyrics,
                            track_number,
                            year,
                            mime_type,
                            bitrate,
                            sample_rate,
                            telegram_chat_id,
                            telegram_file_id
                        ) VALUES (
                            1,
                            'Song',
                            'Artist',
                            10,
                            NULL,
                            'Album',
                            20,
                            'content://song/1',
                            NULL,
                            180000,
                            NULL,
                            '/music/song.mp3',
                            '/music',
                            0,
                            NULL,
                            1,
                            2024,
                            'audio/mpeg',
                            320000,
                            44100,
                            NULL,
                            NULL
                        )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                        CREATE TABLE IF NOT EXISTS favorites (
                            songId INTEGER NOT NULL PRIMARY KEY,
                            isFavorite INTEGER NOT NULL,
                            timestamp INTEGER NOT NULL
                        )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                        CREATE TABLE IF NOT EXISTS song_engagements (
                            song_id TEXT NOT NULL PRIMARY KEY,
                            play_count INTEGER NOT NULL DEFAULT 0,
                            total_play_duration_ms INTEGER NOT NULL DEFAULT 0,
                            last_played_timestamp INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent()
                )
            }

            override fun onUpgrade(
                db: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int
            ) = Unit
        }

        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(callback)
                .build()
        )
    }

    private fun SupportSQLiteDatabase.tableColumns(tableName: String): Set<String> {
        val columns = mutableSetOf<String>()
        query("PRAGMA table_info(`$tableName`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
        }
        return columns
    }

    private fun SupportSQLiteDatabase.tableIndexes(tableName: String): Set<String> {
        val indexes = mutableSetOf<String>()
        query("PRAGMA index_list(`$tableName`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                indexes += cursor.getString(nameIndex)
            }
        }
        return indexes
    }

    private object PixelPlayDatabaseVersion {
        const val LATEST = 51
    }

    companion object {
        private const val DB_NAME_23_TO_24_DRIFTED = "migration-test-23-to-24-drifted"
        private const val DB_NAME_33_TO_34 = "migration-test-33-to-34"
        private const val DB_NAME_35_TO_36 = "migration-test-35-to-36"
        private const val DB_NAME_39_TO_40 = "migration-test-39-to-40"
        private const val DB_NAME_46_TO_47 = "migration-test-46-to-47"
        private const val DB_NAME_47_TO_48 = "migration-test-47-to-48"
        private const val DB_NAME_48_TO_49 = "migration-test-48-to-49"
        private const val DB_NAME_49_TO_50 = "migration-test-49-to-50"
        private const val DB_NAME_50_TO_51 = "migration-test-50-to-51"

        private val ALL_MIGRATIONS = arrayOf(
            PixelPlayDatabase.MIGRATION_25_26,
            PixelPlayDatabase.MIGRATION_26_27,
            PixelPlayDatabase.MIGRATION_27_28,
            PixelPlayDatabase.MIGRATION_28_29,
            PixelPlayDatabase.MIGRATION_29_30,
            PixelPlayDatabase.MIGRATION_30_31,
            PixelPlayDatabase.MIGRATION_31_32,
            PixelPlayDatabase.MIGRATION_32_33,
            PixelPlayDatabase.MIGRATION_33_34,
            PixelPlayDatabase.MIGRATION_34_35,
            PixelPlayDatabase.MIGRATION_35_36,
            PixelPlayDatabase.MIGRATION_36_37,
            PixelPlayDatabase.MIGRATION_37_38,
            PixelPlayDatabase.MIGRATION_38_39,
            PixelPlayDatabase.MIGRATION_39_40,
            PixelPlayDatabase.MIGRATION_40_41,
            PixelPlayDatabase.MIGRATION_41_42,
            PixelPlayDatabase.MIGRATION_42_43,
            PixelPlayDatabase.MIGRATION_43_44,
            PixelPlayDatabase.MIGRATION_44_45,
            PixelPlayDatabase.MIGRATION_45_46,
            PixelPlayDatabase.MIGRATION_46_47,
            PixelPlayDatabase.MIGRATION_47_48,
            PixelPlayDatabase.MIGRATION_48_49,
            PixelPlayDatabase.MIGRATION_49_50,
            PixelPlayDatabase.MIGRATION_50_51
        )
    }
}
