package com.vayunmathur.games.wordmaker.util
import android.content.Context

/**
 * Loads `dictionary.csv` from assets and answers word lookups.
 *
 * Loading and reading run on different threads (VM init on Dispatchers.IO,
 * lookups from the UI), so the populated collections are built locally and
 * then assigned atomically via @Volatile. Until init completes, lookups
 * harmlessly see the empty defaults.
 */
class Dictionary {
    data class Word(val word: String, val position: String, val definition: String)

    @Volatile private var words: List<Word> = emptyList()
    @Volatile private var definitions: Map<String, List<String>> = emptyMap()
    @Volatile private var wordSet: Set<String> = emptySet()

    fun init(context: Context) {
        val newWords = mutableListOf<Word>()
        val newDefs = mutableMapOf<String, MutableList<String>>()
        context.assets.open("dictionary.csv").bufferedReader().lines().forEach { line ->
            val parts = line.split(",", limit = 4)
            if (parts.size < 4) return@forEach
            val w = parts[0].lowercase()
            val def = parts[3].trim { it == '"' }
            newWords.add(Word(w, parts[2], def))
            newDefs.getOrPut(w) { mutableListOf() }.add(def)
        }
        words = newWords
        definitions = newDefs.mapValues { it.value.toList() }
        // Set lookup is O(1); previous `words.any { ... }` was O(n) per call.
        wordSet = newWords.mapTo(HashSet(newWords.size)) { it.word }
    }

    operator fun contains(word: String): Boolean = word in wordSet

    fun getDefinition(word: String): List<String> = definitions[word.lowercase()] ?: emptyList()
}
