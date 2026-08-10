package com.theveloper.pixelplay.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineLibraryIdsTest {
    @Test
    fun `song ids are stable and separated by platform`() {
        assertEquals(OnlineLibraryIds.neteaseSong(123L), OnlineLibraryIds.neteaseSong(123L))
        assertEquals(OnlineLibraryIds.qqSong("mid-1"), OnlineLibraryIds.qqSong("mid-1"))
        assertTrue(OnlineLibraryIds.neteaseSong(123L) < 0L)
        assertTrue(OnlineLibraryIds.qqSong("mid-1") < 0L)
        assertTrue(OnlineLibraryIds.neteaseSong(123L) != OnlineLibraryIds.qqSong("123"))
    }

    @Test
    fun `album and artist ids stay in their reserved ranges`() {
        assertTrue(OnlineLibraryIds.neteaseAlbum(1L, "album") <= -4_000_000_000_001L)
        assertTrue(OnlineLibraryIds.neteaseArtist("artist") <= -5_000_000_000_001L)
        assertTrue(OnlineLibraryIds.qqAlbum("mid", "album") <= -7_000_000_000_001L)
        assertTrue(OnlineLibraryIds.qqArtist("artist") <= -8_000_000_000_001L)
    }
}
