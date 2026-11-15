package com.arcu.attnd.domain

import java.text.Normalizer
import java.util.Locale

/**
 * Text normalisation for the in-app search (shared spec with iOS): lower-case, diacritics stripped
 * (é → e), punctuation, `&`, `-` and other symbols treated as spaces, spaces collapsed.
 *
 * [mapped] also keeps, for every normalised character, the index of the character it came from,
 * so matches can be highlighted in the original text.
 */
object SearchText {

    fun normalize(value: String): String = mapped(value).text

    /** Lower-case and strip diacritics only (punctuation kept), for parsing clock times like `3:30`. */
    fun fold(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) out.append(ch)
        }
        return out.toString().lowercase(Locale.ROOT)
    }

    fun mapped(value: String): NormalizedText {
        val out = StringBuilder(value.length)
        val source = ArrayList<Int>(value.length)
        var pendingSpace = false
        for (index in value.indices) {
            val decomposed = Normalizer.normalize(value[index].toString(), Normalizer.Form.NFD)
            for (raw in decomposed) {
                if (Character.getType(raw) == Character.NON_SPACING_MARK.toInt()) continue
                val ch = raw.lowercaseChar()
                if (ch.isLetterOrDigit()) {
                    if (pendingSpace && out.isNotEmpty()) {
                        out.append(' ')
                        source.add(index)
                    }
                    pendingSpace = false
                    out.append(ch)
                    source.add(index)
                } else {
                    pendingSpace = true
                }
            }
        }
        return NormalizedText(out.toString(), source.toIntArray())
    }

    /** Normalised words of [value] (no empty strings). */
    fun words(value: String): List<String> = normalize(value).split(' ').filter { it.isNotEmpty() }
}

/** A normalised string and, per character, the index of the original character it came from. */
class NormalizedText(val text: String, private val sourceIndex: IntArray) {

    /** Words with their start offset in [text]. */
    val words: List<Pair<Int, String>> by lazy {
        val result = mutableListOf<Pair<Int, String>>()
        var start = 0
        for (part in text.split(' ')) {
            if (part.isNotEmpty()) result += start to part
            start += part.length + 1
        }
        result
    }

    /** The range in the original string covered by normalised characters [start] until [endExclusive]. */
    fun sourceRange(start: Int, endExclusive: Int): IntRange? {
        if (start < 0 || endExclusive > sourceIndex.size || start >= endExclusive) return null
        return sourceIndex[start]..sourceIndex[endExclusive - 1]
    }
}
