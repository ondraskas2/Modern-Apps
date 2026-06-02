package com.vayunmathur.library.widgets

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.GlanceStateDefinition
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

class GenericWidgetWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val className = inputData.getString(ARG_WIDGET_CLASS) ?: return Result.failure()

        try {
            // Use reflection to get the GlanceAppWidget instance
            val kClass = Class.forName(className).kotlin
            val widget = kClass.java.getDeclaredConstructor().newInstance() as GlanceAppWidget

            widget.updateAll(context)
            return Result.success()
        } catch (e: Exception) {
            return Result.failure()
        }
    }

    companion object {
        const val ARG_WIDGET_CLASS = "widget_class_name"
    }
}

fun <T : GlanceAppWidget> Context.scheduleHourlyUpdate(widgetClass: KClass<T>) {
    val className = widgetClass.qualifiedName ?: return

    val inputData = workDataOf(GenericWidgetWorker.ARG_WIDGET_CLASS to className)

    val request = PeriodicWorkRequestBuilder<GenericWidgetWorker>(1, TimeUnit.HOURS)
        .setInputData(inputData)
        .build()

    WorkManager.getInstance(this).enqueueUniquePeriodicWork(
        className, // Unique name per widget class
        ExistingPeriodicWorkPolicy.KEEP,
        request
    )
}

/**
 * Schedule an immediate widget update via WorkManager.
 * Uses REPLACE policy to debounce rapid updates - if multiple updates are requested
 * quickly, only the last one will be executed. This prevents Android's widget update
 * throttling from dropping updates.
 */
fun <T : GlanceAppWidget> Context.updateWidget(widgetClass: KClass<T>) {
    val className = widgetClass.qualifiedName ?: return

    val inputData = workDataOf(GenericWidgetWorker.ARG_WIDGET_CLASS to className)

    val request = OneTimeWorkRequestBuilder<GenericWidgetWorker>()
        .setInputData(inputData)
        .build()

    WorkManager.getInstance(this).enqueueUniqueWork(
        "${className}_immediate",
        ExistingWorkPolicy.REPLACE,
        request
    )
}

/**
 * GlanceStateDefinition that stores data as JSON in Preferences DataStore.
 * Use this for widgets that need to store complex state objects.
 * 
 * This uses Glance's built-in PreferencesGlanceStateDefinition which is the
 * standard way to store Preferences state in Glance widgets.
 */
typealias JsonPreferencesStateDefinition = androidx.glance.state.PreferencesGlanceStateDefinition

/**
 * Helper to update a Glance widget's state with a serializable object.
 * The state is stored as JSON in Preferences. This triggers automatic widget
 * recomposition without needing updateAll().
 *
 * @param context Android context
 * @param widgetClass The GlanceAppWidget class to update
 * @param key The preferences key to store the data under
 * @param value The value to store (must be serializable)
 * @param serializer The KSerializer for the value type
 */
suspend fun <T : GlanceAppWidget, V> updateWidgetState(
    context: Context,
    widgetClass: KClass<T>,
    key: String,
    value: V,
    serializer: KSerializer<V>
) {
    val glanceIds = GlanceAppWidgetManager(context).getGlanceIds(widgetClass.java)
    if (glanceIds.isEmpty()) return

    val json = Json.encodeToString(serializer, value)
    val prefsKey = stringPreferencesKey(key)

    glanceIds.forEach { glanceId ->
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[prefsKey] = json
        }
    }
}

/**
 * Helper to read a widget's state. Returns null if state is not available.
 *
 * @param prefs The Preferences object from currentState()
 * @param key The preferences key
 * @param serializer The KSerializer for the value type
 * @return The deserialized value, or null if not found or parsing fails
 */
inline fun <reified V> readWidgetState(
    prefs: Preferences,
    key: String,
    serializer: KSerializer<V>
): V? {
    val prefsKey = stringPreferencesKey(key)
    val json = prefs[prefsKey] ?: return null
    return try {
        Json.decodeFromString(serializer, json)
    } catch (e: Exception) {
        null
    }
}
