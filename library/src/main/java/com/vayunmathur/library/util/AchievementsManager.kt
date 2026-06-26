package com.vayunmathur.library.util

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val achievementsJson = Json { ignoreUnknownKeys = true }

abstract class AchievementsManager(val context: Context, jsonContent: String) {
    protected val ds = DataStoreUtils.getInstance(context)
    val achievements: List<Achievement> =
        achievementsJson.decodeFromString(ListSerializer(Achievement.serializer()), jsonContent)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _newAchievement = MutableStateFlow<Achievement?>(null)
    val newAchievement = _newAchievement.asStateFlow()

    abstract fun checkExistingAchievements()

    fun onAchievementUnlocked(id: String) {
        val achievement = achievements.find { it.id == id } ?: return
        scope.launch {
            if (ds.addStringToSetIfAbsent("achievements_unlocked", id)) {
                _newAchievement.value = achievement
            }
        }
    }

    fun onProgressUpdated(id: String, progress: Int) {
        val achievement = achievements.find { it.id == id } ?: return
        scope.launch {
            if (ds.setLongIfGreater("achievement_progress_$id", progress.toLong()) &&
                progress >= achievement.targetProgress
            ) {
                onAchievementUnlocked(id)
            }
        }
    }

    fun getAchievementStatuses(): Flow<List<AchievementStatus>> {
        val unlockedFlow = ds.stringSetFlow("achievements_unlocked")
        val progressFlows = achievements.map { ds.longFlow("achievement_progress_${it.id}", 0L) }
        return combine(unlockedFlow, combine(progressFlows) { it }) { unlocked, progresses ->
            achievements.mapIndexed { index, achievement ->
                AchievementStatus(
                    achievement = achievement,
                    progress = progresses[index].toInt(),
                    isUnlocked = unlocked.contains(achievement.id)
                )
            }
        }
    }

    fun dismissNotification() {
        _newAchievement.value = null
    }
}
