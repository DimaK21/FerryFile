package ru.kryu.ferryfile.server.routes

import org.junit.Assert.*
import org.junit.Test

class AttachmentHeaderTest {

    @Test fun `ascii name is used verbatim in both parameters`() {
        assertEquals(
            "attachment; filename=\"report.pdf\"; filename*=UTF-8''report.pdf",
            attachmentHeader("report.pdf")
        )
    }

    @Test fun `non ascii name keeps an ascii fallback and a percent encoded form`() {
        val header = attachmentHeader("Отчёт.pdf")
        assertTrue(header.startsWith("attachment; filename=\"_____.pdf\""))
        assertTrue(header.contains("filename*=UTF-8''%D0%9E"))
    }

    @Test fun `quotes and backslashes cannot break out of the header`() {
        val header = attachmentHeader("a\"b\\c.txt")
        assertTrue(header.startsWith("attachment; filename=\"a_b_c.txt\""))
    }

    @Test fun `spaces are encoded as percent twenty, not plus`() {
        assertTrue(attachmentHeader("my report.pdf").contains("filename*=UTF-8''my%20report.pdf"))
    }
}
