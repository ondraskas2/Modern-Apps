package com.vayunmathur.games.wordmaker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class LevelDataStore(context: Context) {

    private val appContext = context.applicationContext
    companion object {
        private val LEVEL_KEY = intPreferencesKey("current_level")
        private val FOUND_WORDS_KEY = stringSetPreferencesKey("found_words")
        private val BONUS_WORDS_KEY = stringSetPreferencesKey("bonus_words")
        private val TOTAL_BONUS_WORDS_KEY = intPreferencesKey("total_bonus_words")
        private val TAP_TO_SPELL_KEY = booleanPreferencesKey("tap_to_spell")
        private val REVEALED_HINTS_KEY = stringSetPreferencesKey("revealed_hints")
    }

    val currentLevel: Flow<Int> = appContext.dataStore.data
        .map { preferences ->
            preferences[LEVEL_KEY] ?: 1
        }

    val foundWords: Flow<Set<String>> = appContext.dataStore.data.map { it[FOUND_WORDS_KEY] ?: emptySet() }
    val bonusWords: Flow<Set<String>> = appContext.dataStore.data.map { it[BONUS_WORDS_KEY] ?: emptySet() }
    val totalBonusWords: Flow<Int> = appContext.dataStore.data.map { it[TOTAL_BONUS_WORDS_KEY] ?: 0 }

    val tapToSpell: Flow<Boolean> = appContext.dataStore.data.map { it[TAP_TO_SPELL_KEY] ?: false }

    val revealedHints: Flow<Set<Pair<Int, Int>>> = appContext.dataStore.data.map { prefs ->
        prefs[REVEALED_HINTS_KEY]?.mapNotNull { s ->
            val parts = s.split(",")
            if (parts.size == 2) parts[0].toIntOrNull()?.let { r -> parts[1].toIntOrNull()?.let { c -> r to c } }
            else null
        }?.toSet() ?: emptySet()
    }

    suspend fun setTapToSpell(enabled: Boolean) {
        appContext.dataStore.edit { it[TAP_TO_SPELL_KEY] = enabled }
    }

    suspend fun addRevealedHint(row: Int, col: Int) {
        appContext.dataStore.edit { settings ->
            val current = settings[REVEALED_HINTS_KEY] ?: emptySet()
            settings[REVEALED_HINTS_KEY] = current + "$row,$col"
        }
    }

    suspend fun addBonusWord(word: String): Int {
        var newTotal = 0
        appContext.dataStore.edit { settings ->
            val currentBonusWords = settings[BONUS_WORDS_KEY] ?: emptySet()
            if (word !in currentBonusWords) {
                settings[BONUS_WORDS_KEY] = currentBonusWords + word
                val currentTotal = settings[TOTAL_BONUS_WORDS_KEY] ?: 0
                newTotal = currentTotal + 1
                settings[TOTAL_BONUS_WORDS_KEY] = newTotal
            } else {
                newTotal = settings[TOTAL_BONUS_WORDS_KEY] ?: 0
            }
        }
        return newTotal
    }

    suspend fun addFoundWord(word: String) {
        appContext.dataStore.edit { settings ->
            val currentFoundWords = settings[FOUND_WORDS_KEY] ?: emptySet()
            settings[FOUND_WORDS_KEY] = currentFoundWords + word
        }
    }

    suspend fun saveLevel(level: Int) {
        appContext.dataStore.edit { settings ->
            settings[LEVEL_KEY] = level
            settings[FOUND_WORDS_KEY] = emptySet()
            settings[BONUS_WORDS_KEY] = emptySet()
            settings[REVEALED_HINTS_KEY] = emptySet()
        }
    }
}
