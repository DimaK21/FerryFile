package ru.kryu.ferryfile.data

import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class PreferencesRepositoryTest {
    private val prefs: SharedPreferences = mock()
    private val editor: SharedPreferences.Editor = mock()
    private lateinit var repo: PreferencesRepository

    @Before fun setUp() {
        whenever(prefs.edit()).thenReturn(editor)
        whenever(editor.putString(any(), any())).thenReturn(editor)
        whenever(editor.putInt(any(), any())).thenReturn(editor)
        whenever(editor.putBoolean(any(), any())).thenReturn(editor)
        repo = PreferencesRepository(prefs)
    }

    @Test fun `safUris returns empty list when nothing stored`() {
        whenever(prefs.getString("saf_uris", "[]")).thenReturn("[]")
        assertTrue(repo.safUris.isEmpty())
    }

    @Test fun `safUris deserializes stored JSON`() {
        val uris = listOf("content://foo/tree/bar", "content://foo/tree/baz")
        whenever(prefs.getString("saf_uris", "[]")).thenReturn(Json.encodeToString(uris))
        assertEquals(uris, repo.safUris)
    }

    @Test fun `port returns 8080 by default`() {
        whenever(prefs.getInt("server_port", 8080)).thenReturn(8080)
        assertEquals(8080, repo.port)
    }

    @Test fun `safUris returns empty list when stored JSON is corrupted`() {
        whenever(prefs.getString("saf_uris", "[]")).thenReturn("{not valid json}")
        assertTrue(repo.safUris.isEmpty())
    }
}
