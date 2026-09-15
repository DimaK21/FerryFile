package ru.kryu.ferryfile.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import ru.kryu.ferryfile.data.files.SafPermissionManager
import ru.kryu.ferryfile.domain.model.Port

class SettingsRepositoryImplTest {

    private val prefs: SharedPreferences = mock()
    private val editor: SharedPreferences.Editor = mock()
    private val permissions: SafPermissionManager = mock()

    @Before fun setUp() {
        whenever(prefs.edit()).thenReturn(editor)
        whenever(editor.putString(any(), any())).thenReturn(editor)
        whenever(editor.putInt(any(), any())).thenReturn(editor)
        whenever(editor.putBoolean(any(), any())).thenReturn(editor)
        whenever(prefs.getString(eq("saf_uris"), any())).thenReturn("[]")
        whenever(prefs.getInt(eq("server_port"), any())).thenReturn(8080)
        whenever(prefs.getBoolean(eq("dark_theme"), any())).thenReturn(true)
        whenever(prefs.getBoolean(eq("use_https"), any())).thenReturn(false)
        whenever(permissions.displayName(any())).thenAnswer { it.arguments[0].toString().substringAfterLast('/') }
        whenever(permissions.takePersistable(any())).thenReturn(true)
    }

    private fun repo() = SettingsRepositoryImpl(prefs, permissions)

    @Test fun `port falls back to default when stored value is out of range`() {
        whenever(prefs.getInt(eq("server_port"), any())).thenReturn(80)
        assertEquals(Port.DEFAULT, repo().port.value)
    }

    @Test fun `stored port is exposed`() {
        whenever(prefs.getInt(eq("server_port"), any())).thenReturn(9000)
        assertEquals(9000, repo().port.value.value)
    }

    @Test fun `setPort persists and updates the flow`() = runTest {
        val repo = repo()
        repo.setPort(Port.parse(9100)!!)
        verify(editor).putInt("server_port", 9100)
        assertEquals(9100, repo.port.value.value)
    }

    @Test fun `shared folders are decoded from stored json`() {
        whenever(prefs.getString(eq("saf_uris"), any()))
            .thenReturn(Json.encodeToString(listOf("content://tree/photos", "content://tree/docs")))
        val folders = repo().sharedFolders.value
        assertEquals(listOf("content://tree/photos", "content://tree/docs"), folders.map { it.uri })
        assertEquals(listOf("photos", "docs"), folders.map { it.displayName })
    }

    @Test fun `corrupted json yields no shared folders`() {
        whenever(prefs.getString(eq("saf_uris"), any())).thenReturn("{not valid json}")
        assertTrue(repo().sharedFolders.value.isEmpty())
    }

    @Test fun `addSharedFolder persists uri and updates the flow`() = runTest {
        val repo = repo()
        assertTrue(repo.addSharedFolder("content://tree/photos"))
        verify(editor).putString("saf_uris", Json.encodeToString(listOf("content://tree/photos")))
        assertEquals(listOf("content://tree/photos"), repo.sharedFolders.value.map { it.uri })
    }

    @Test fun `addSharedFolder returns false and stores nothing when permission is denied`() = runTest {
        whenever(permissions.takePersistable(any())).thenReturn(false)
        val repo = repo()
        assertFalse(repo.addSharedFolder("content://tree/photos"))
        verify(editor, never()).putString(eq("saf_uris"), any())
        assertTrue(repo.sharedFolders.value.isEmpty())
    }

    @Test fun `addSharedFolder does not duplicate an existing uri`() = runTest {
        whenever(prefs.getString(eq("saf_uris"), any()))
            .thenReturn(Json.encodeToString(listOf("content://tree/photos")))
        val repo = repo()
        assertTrue(repo.addSharedFolder("content://tree/photos"))
        assertEquals(1, repo.sharedFolders.value.size)
    }

    @Test fun `removeSharedFolder releases permission and updates the flow`() = runTest {
        whenever(prefs.getString(eq("saf_uris"), any()))
            .thenReturn(Json.encodeToString(listOf("content://tree/photos", "content://tree/docs")))
        val repo = repo()
        repo.removeSharedFolder("content://tree/photos")
        verify(permissions).release("content://tree/photos")
        assertEquals(listOf("content://tree/docs"), repo.sharedFolders.value.map { it.uri })
    }

    @Test fun `setDarkTheme persists and updates the flow`() = runTest {
        val repo = repo()
        repo.setDarkTheme(false)
        verify(editor).putBoolean("dark_theme", false)
        assertFalse(repo.darkTheme.value)
    }

    @Test fun `HTTPS is disabled by default`() {
        assertFalse(repo().useHttps.value)
    }

    @Test fun `setUseHttps persists and updates the flow`() = runTest {
        val repo = repo()
        repo.setUseHttps(true)
        verify(editor).putBoolean("use_https", true)
        assertTrue(repo.useHttps.value)
    }
}
