package com.refayatul.fityah.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.StringReader
import java.io.StringWriter

class KeywordFileCodecTest {

    @Test
    fun importTrimsAndDeduplicatesKeywordsInFileOrder() {
        val input = " existing \n\nnew\n new \nanother\n"

        val result = KeywordFileCodec.readNewKeywords(
            BufferedReader(StringReader(input)),
            setOf("existing")
        )

        assertEquals(listOf("new", "another"), result)
    }

    @Test
    fun exportWritesOneKeywordPerLineWithoutTrailingNewline() {
        val output = StringWriter()

        BufferedWriter(output).use { writer ->
            KeywordFileCodec.writeKeywords(writer, listOf("first", "second"))
        }

        assertEquals("first${System.lineSeparator()}second", output.toString())
    }

    @Test
    fun handlesFiveMegabyteKeywordList() {
        val input = StringBuilder(5 * 1024 * 1024)
        var keywordCount = 0
        while (input.length < 5 * 1024 * 1024) {
            input.append("keyword_").append(keywordCount++).append('\n')
        }

        val keywords = KeywordFileCodec.readNewKeywords(
            BufferedReader(StringReader(input.toString())),
            emptySet()
        )
        val output = StringWriter(input.length)
        BufferedWriter(output).use { writer ->
            KeywordFileCodec.writeKeywords(writer, keywords)
        }

        assertEquals(keywordCount, keywords.size)
        assertTrue(output.buffer.length >= 5 * 1024 * 1024 - 1)
    }
}
