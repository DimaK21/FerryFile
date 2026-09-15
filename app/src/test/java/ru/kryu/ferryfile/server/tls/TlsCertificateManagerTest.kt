package ru.kryu.ferryfile.server.tls

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TlsCertificateManagerTest {

    @Test
    fun `certificate is persisted for the same host`() {
        val directory = Files.createTempDirectory("ferryfile-tls").toFile()
        val values = mutableMapOf<String, String?>()
        val context = mock<Context> {
            on { filesDir } doReturn directory
        }
        val editor = mock<SharedPreferences.Editor>()
        val preferences = mock<SharedPreferences> {
            on { getString(any(), anyOrNull()) } doAnswer { values[it.getArgument(0)] }
            on { edit() } doReturn editor
        }
        whenever(editor.putString(any(), anyOrNull())).thenAnswer {
            values[it.getArgument<String>(0)] = it.getArgument(1)
            editor
        }

        val first = TlsCertificateManager(context, preferences).prepare("192.168.1.5")
        val second = TlsCertificateManager(context, preferences).prepare("192.168.1.5")

        assertEquals(first.fingerprint, second.fingerprint)
        assertTrue(File(directory, "ferryfile-server.p12").isFile)
        assertTrue(first.fingerprint.matches(Regex("(?:[0-9A-F]{2}:){31}[0-9A-F]{2}")))
    }

    @Test
    fun `certificate is replaced when the advertised host changes`() {
        val directory = Files.createTempDirectory("ferryfile-tls").toFile()
        val values = mutableMapOf<String, String?>()
        val context = mock<Context> {
            on { filesDir } doReturn directory
        }
        val editor = mock<SharedPreferences.Editor>()
        val preferences = mock<SharedPreferences> {
            on { getString(any(), anyOrNull()) } doAnswer { values[it.getArgument(0)] }
            on { edit() } doReturn editor
        }
        whenever(editor.putString(any(), anyOrNull())).thenAnswer {
            values[it.getArgument<String>(0)] = it.getArgument(1)
            editor
        }

        val first = TlsCertificateManager(context, preferences).prepare("192.168.1.5")
        val second = TlsCertificateManager(context, preferences).prepare("192.168.1.6")

        assertNotEquals(first.fingerprint, second.fingerprint)
        assertEquals("192.168.1.6", values["tls_certificate_host"])
    }
}
