package com.vayunmathur.games.alchemist.util

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.alchemist.data.Alchemist
import com.vayunmathur.games.alchemist.data.AlchemyItem
import com.vayunmathur.games.alchemist.data.AlchemyRecipe
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlacedItem(val id: Long, val offset: Offset, val key: Long = System.nanoTime())

class AlchemistViewModel(application: Application) : AndroidViewModel(application) {

    private val ds = DataStoreUtils.getInstance(application)

    private val _allItems = MutableStateFlow<List<AlchemyItem>>(emptyList())
    val allItems: StateFlow<List<AlchemyItem>> = _allItems.asStateFlow()

    private val _recipes = MutableStateFlow<List<AlchemyRecipe>>(emptyList())
    val recipes: StateFlow<List<AlchemyRecipe>> = _recipes.asStateFlow()

    val itemIds: StateFlow<Set<Long>> = ds.stringSetFlow("available_items")
        .map { set -> set.map { it.toLong() }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val usedRecipes: StateFlow<Set<String>> = ds.stringSetFlow("used_recipes")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val availableItems: StateFlow<List<AlchemyItem>> =
        combine(_allItems, itemIds) { items, ids ->
            items.filter { it.id in ids }.sortedBy { it.name }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _placedElements = MutableStateFlow<List<PlacedItem>>(emptyList())
    val placedElements: StateFlow<List<PlacedItem>> = _placedElements.asStateFlow()

    private val _newUnlocksEvent = MutableSharedFlow<List<AlchemyItem>>(extraBufferCapacity = 5)
    val newUnlocksEvent: SharedFlow<List<AlchemyItem>> = _newUnlocksEvent.asSharedFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            Alchemist.init(application)
            _allItems.value = Alchemist.items
            _recipes.value = Alchemist.recipes
            seedInitialItemsIfEmpty()
            backfillLegacyRecipes()
        }
    }

    private suspend fun seedInitialItemsIfEmpty() {
        val initial = ds.stringSetFlow("available_items").first()
        if (initial.isEmpty()) {
            (1L..4L).forEach { ds.addStringToSet("available_items", it.toString()) }
        }
    }

    /**
     * One-time migration for users who unlocked items before recipe logging existed.
     * For every unlocked element that has no logged recipe creating it, add every recipe
     * that creates it whose inputs are themselves only such pre-existing elements
     * (unlocked with no logged recipe creating them).
     */
    private suspend fun backfillLegacyRecipes() {
        if (ds.getBoolean("recipes_backfilled", false)) return

        val unlocked = ds.stringSetFlow("available_items").first()
            .mapNotNull { it.toLongOrNull() }.toSet()
        val usedKeys = ds.stringSetFlow("used_recipes").first()
        val allRecipes = _recipes.value

        // Elements that already have a logged recipe creating them.
        val createdByLog = allRecipes
            .filter { recipeKey(it) in usedKeys }
            .flatMap { it.outputs }
            .toSet()

        // "Pre-existing" elements: unlocked but not created by any logged recipe.
        val legacy = unlocked - createdByLog

        allRecipes.forEach { recipe ->
            if (recipe.outputs.any { it in legacy } && recipe.inputs.all { it in legacy }) {
                ds.addStringToSet("used_recipes", recipeKey(recipe))
            }
        }

        ds.setBoolean("recipes_backfilled", true)
    }

    fun unlockItem(id: Long) {
        if (id !in itemIds.value) {
            ds.addStringToSet("available_items", id.toString())
        }
    }

    fun placeElement(id: Long, offset: Offset): PlacedItem {
        val newItem = PlacedItem(id, offset)
        _placedElements.update { it + newItem }
        tryCombine(newItem.key, newItem.offset)
        return newItem
    }

    fun updateElementPosition(key: Long, offset: Offset) {
        _placedElements.update { list ->
            list.map { if (it.key == key) it.copy(offset = offset) else it }
        }
    }

    fun removeElement(key: Long) {
        _placedElements.update { list -> list.filterNot { it.key == key } }
    }

    fun duplicateElement(key: Long) {
        val current = _placedElements.value
        val elementToDuplicate = current.find { it.key == key } ?: return
        val duplicatedItem = PlacedItem(
            id = elementToDuplicate.id,
            offset = elementToDuplicate.offset + Offset(25f, 25f)
        )
        _placedElements.update { it + duplicatedItem }
    }

    fun clearElements() {
        _placedElements.update { emptyList() }
    }

    fun tryCombine(movedKey: Long, movedOffset: Offset) {
        val current = _placedElements.value
        val movedItem = current.find { it.key == movedKey } ?: return
        val target = current
            .filter { it.key != movedKey }
            .find { (it.offset - movedOffset).getDistance() < 100f }
            ?: return

        val combined = listOf(movedItem.id, target.id).sorted()
        val recipe = _recipes.value.find { it.inputs.sorted() == combined } ?: return

        // Record this recipe as used
        ds.addStringToSet("used_recipes", recipeKey(recipe))

        val toRemoveKeys = setOf(movedItem.key, target.key)
        val toAdd = recipe.outputs.map { PlacedItem(it, target.offset) }
        _placedElements.update { list ->
            list.filterNot { it.key in toRemoveKeys } + toAdd
        }

        // Emit discovery toast for new outputs
        val knownIds = itemIds.value
        val discovered = recipe.outputs
            .filter { it !in knownIds }
            .distinct()
            .mapNotNull { id -> _allItems.value.find { it.id == id } }
        if (discovered.isNotEmpty()) {
            _newUnlocksEvent.tryEmit(discovered)
        }
        toAdd.forEach { unlockItem(it.id) }
    }

    // --- Achievements ---
    private var achievementsBound = false
    fun bindAchievements(achievementsManager: AchievementsManager) {
        if (achievementsBound) return
        achievementsBound = true
        viewModelScope.launch {
            availableItems.collect { items ->
                if (items.isEmpty()) return@collect
                if (items.size > 4) achievementsManager.onAchievementUnlocked("first_creation")
                achievementsManager.onProgressUpdated("collector_50", items.size)
                achievementsManager.onProgressUpdated("collector_100", items.size)
                achievementsManager.onProgressUpdated("all_discovered", items.size)

                val all = _allItems.value
                if (items.size >= all.size && all.isNotEmpty()) {
                    achievementsManager.onAchievementUnlocked("all_discovered")
                }
                if (items.any { it.final }) {
                    achievementsManager.onAchievementUnlocked("final_item")
                }
                if (items.any { it.id == 44L }) {
                    achievementsManager.onAchievementUnlocked("created_life")
                }

                // Auto-unlock Time once enough items have been discovered. This reacts to
                // both app open (initial emission) and newly created elements.
                if (items.size >= TIME_UNLOCK_THRESHOLD && TIME_ID !in itemIds.value) {
                    _allItems.value.find { it.id == TIME_ID }?.let { timeItem ->
                        unlockItem(TIME_ID)
                        _newUnlocksEvent.tryEmit(listOf(timeItem))
                    }
                }
                if (items.any { it.id == TIME_ID }) {
                    achievementsManager.onAchievementUnlocked("unlocked_time")
                }
            }
        }
    }

    companion object {
        private const val TIME_ID = 41L
        private const val TIME_UNLOCK_THRESHOLD = 100
    }
}

fun recipeKey(recipe: AlchemyRecipe): String =
    "${recipe.inputs.sorted().joinToString(",")}->${recipe.outputs.sorted().joinToString(",")}"
