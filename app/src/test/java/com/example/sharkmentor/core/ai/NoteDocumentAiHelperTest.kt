package com.example.sharkmentor.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteDocumentAiHelperTest {

    @Test
    fun selectPageIndicesForScope_currentPageReturnsSingleClampedPage() {
        assertEquals(emptyList<Int>(), NoteDocumentAiHelper.selectPageIndicesForScope(0, 0, DocumentAiScope.CURRENT_PAGE))
        assertEquals(listOf(0), NoteDocumentAiHelper.selectPageIndicesForScope(-2, 10, DocumentAiScope.CURRENT_PAGE))
        assertEquals(listOf(4), NoteDocumentAiHelper.selectPageIndicesForScope(4, 10, DocumentAiScope.CURRENT_PAGE))
        assertEquals(listOf(9), NoteDocumentAiHelper.selectPageIndicesForScope(99, 10, DocumentAiScope.CURRENT_PAGE))
    }

    @Test
    fun selectPageIndicesForScope_nearbyPagesCentersAndClampsAtBoundaries() {
        assertEquals(listOf(0), NoteDocumentAiHelper.selectPageIndicesForScope(0, 1, DocumentAiScope.NEARBY_PAGES))
        assertEquals(listOf(0, 1), NoteDocumentAiHelper.selectPageIndicesForScope(1, 2, DocumentAiScope.NEARBY_PAGES))
        assertEquals(listOf(0, 1, 2), NoteDocumentAiHelper.selectPageIndicesForScope(0, 50, DocumentAiScope.NEARBY_PAGES))
        assertEquals(listOf(11, 12, 13), NoteDocumentAiHelper.selectPageIndicesForScope(12, 50, DocumentAiScope.NEARBY_PAGES))
        assertEquals(listOf(47, 48, 49), NoteDocumentAiHelper.selectPageIndicesForScope(49, 50, DocumentAiScope.NEARBY_PAGES))
    }

    @Test
    fun parseLassoAiStream_hidesIncompleteMetadataPrefixWhileStreaming() {
        val partial1 = NoteDocumentAiHelper.parseLassoAiStream("[[SHARK_")
        assertTrue(partial1.isMetaPending)
        assertNull(partial1.meta)
        assertEquals("", partial1.displayText)

        val partial2 = NoteDocumentAiHelper.parseLassoAiStream("[[SHARK_META|IS_TEST_QUESTION=true|SUBJECT=Mat")
        assertTrue(partial2.isMetaPending)
        assertNull(partial2.meta)
        assertEquals("", partial2.displayText)
    }

    @Test
    fun parseLassoAiStream_extractsTestQuestionMetadataAndCleanSolutionBody() {
        val raw = """
            [[SHARK_META|IS_TEST_QUESTION=true|SUBJECT=Geometri|TOPIC=Üçgende Alan|ANSWER=C]]

            1. Adım: Taban uzunluğu 8 cm, yükseklik 6 cm verilmiş.
            2. Adım: Alan = (8 x 6) / 2 = 24 cm².

            Doğru Cevap: C
        """.trimIndent()

        val parsed = NoteDocumentAiHelper.parseLassoAiStream(raw)
        assertFalse(parsed.isMetaPending)
        assertNotNull(parsed.meta)
        assertTrue(parsed.meta!!.isTestQuestion)
        assertEquals("Matematik", parsed.meta!!.subject)
        assertEquals("Üçgende Alan", parsed.meta!!.topic)
        assertEquals("C", parsed.meta!!.answer)
        assertFalse(parsed.displayText.contains("SHARK_META"))
        assertTrue(parsed.displayText.startsWith("1. Adım:"))
    }

    @Test
    fun parseLassoAiStream_parsesNonTestContentWithoutAnswer() {
        val raw = "[[SHARK_META|IS_TEST_QUESTION=false|SUBJECT=Tarih|TOPIC=Tanzimat Fermanı|ANSWER=-]]\nTanzimat Fermanı 1839 yılında ilan edilmiştir."
        val parsed = NoteDocumentAiHelper.parseLassoAiStream(raw)
        assertFalse(parsed.isMetaPending)
        assertNotNull(parsed.meta)
        assertFalse(parsed.meta!!.isTestQuestion)
        assertEquals("Tarih", parsed.meta!!.subject)
        assertEquals("Tanzimat Fermanı", parsed.meta!!.topic)
        assertNull(parsed.meta!!.answer)
        assertEquals("Tanzimat Fermanı 1839 yılında ilan edilmiştir.", parsed.displayText)
    }

    @Test
    fun parseLassoAiStream_handlesMissingMetadataHeaderGracefully() {
        val raw = "Bu paragrafta fotosentezin ışığa bağımlı evresi anlatılmaktadır."
        val parsed = NoteDocumentAiHelper.parseLassoAiStream(raw)
        assertFalse(parsed.isMetaPending)
        assertNull(parsed.meta)
        assertEquals(raw, parsed.displayText)
    }

    @Test
    fun normalizeErrorBankSubject_mapsAliasesToCanonicalFolders() {
        assertEquals("Matematik", NoteDocumentAiHelper.normalizeErrorBankSubject("Geometri"))
        assertEquals("Türkçe / Edebiyat", NoteDocumentAiHelper.normalizeErrorBankSubject("Paragraf"))
        assertEquals("Fizik", NoteDocumentAiHelper.normalizeErrorBankSubject("Ders: Fizik"))
        assertEquals("Genel Yetenek / Diğer", NoteDocumentAiHelper.normalizeErrorBankSubject("-"))
    }

    @Test
    fun formatCompactSizeKb_formatsBytesCleanly() {
        assertEquals("1 KB", NoteDocumentAiHelper.formatCompactSizeKb(400))
        assertEquals("84 KB", NoteDocumentAiHelper.formatCompactSizeKb(84 * 1024L))
        assertEquals("139 KB", NoteDocumentAiHelper.formatCompactSizeKb(139 * 1024L + 100L))
    }
}
