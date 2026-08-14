package com.refayatul.fityah.utils

import java.io.BufferedReader
import java.io.BufferedWriter

internal object KeywordFileCodec {

    fun readNewKeywords(
        reader: BufferedReader,
        existingKeywords: Collection<String>
    ): List<String> {
        val seenKeywords = HashSet(existingKeywords)
        val newKeywords = mutableListOf<String>()

        while (true) {
            val keyword = reader.readLine()?.trim() ?: break
            if (keyword.isNotEmpty() && seenKeywords.add(keyword)) {
                newKeywords.add(keyword)
            }
        }

        return newKeywords
    }

    fun writeKeywords(writer: BufferedWriter, keywords: Collection<String>) {
        keywords.forEachIndexed { index, keyword ->
            if (index > 0) writer.newLine()
            writer.write(keyword)
        }
    }
}
