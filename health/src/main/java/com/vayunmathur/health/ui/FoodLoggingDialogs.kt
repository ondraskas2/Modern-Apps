package com.vayunmathur.health.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.data.Ingredient
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.data.Recipe
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.util.HealthAPI
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.launch

enum class HydrationUnit(val displayName: String, val toLiters: Double) {
    Liters("Liters", 1.0),
    Milliliters("Milliliters", 0.001),
    Ounces("Ounces", 0.0295735),
    Cups("Cups", 0.236588)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogHydrationDialog(initialTime: Instant? = null, onDismiss: () -> Unit) {
    var quantityStr by remember { mutableStateOf("") }
    var selectedUnit by remember { mutableStateOf(HydrationUnit.Liters) }
    var unitExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val quantity = quantityStr.toDoubleOrNull()
    val isValid = quantity != null && quantity > 0

    AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Log Hydration") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                            value = quantityStr,
                            onValueChange = { quantityStr = it },
                            label = { Text("Quantity") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                ExposedDropdownMenuBox(
                                        expanded = unitExpanded,
                                        onExpandedChange = { unitExpanded = !unitExpanded }
                                ) {
                                    Row(
                                            modifier =
                                                    Modifier.menuAnchor()
                                                            .clickable { unitExpanded = true }
                                                            .padding(end = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                                selectedUnit.displayName,
                                                style = MaterialTheme.typography.bodyMedium
                                        )
                                        ExposedDropdownMenuDefaults.TrailingIcon(
                                                expanded = unitExpanded
                                        )
                                    }
                                    ExposedDropdownMenu(
                                            expanded = unitExpanded,
                                            onDismissRequest = { unitExpanded = false }
                                    ) {
                                        HydrationUnit.entries.forEach { unit ->
                                            DropdownMenuItem(
                                                    text = { Text(unit.displayName) },
                                                    onClick = {
                                                        selectedUnit = unit
                                                        unitExpanded = false
                                                    }
                                            )
                                        }
                                    }
                                }
                            }
                    )
                }
            },
            confirmButton = {
                Button(
                        enabled = isValid,
                        onClick = {
                            if (quantity != null) {
                                scope.launch {
                                    val time = initialTime ?: Instant.now()
                                    val record =
                                            Record(
                                                    id = UUID.randomUUID().toString(),
                                                    index = 0,
                                                    type = RecordType.Hydration,
                                                    startTime = time,
                                                    endTime = time,
                                                    value =
                                                            quantity *
                                                                    selectedUnit
                                                                            .toLiters, // Convert to
                                                    // Liters
                                                    metadata = "Hydration"
                                            )
                                    HealthAPI.db.healthDao().upsert(listOf(record))
                                    HealthAPI.writeHealthRecord(record)
                                    onDismiss()
                                }
                            }
                        }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

sealed class Loggable {
    abstract val id: String
    abstract val name: String

    data class RecipeWrapper(val recipe: Recipe) : Loggable() {
        override val id = recipe.id
        override val name = recipe.name
    }

    data class IngredientWrapper(val ingredient: Ingredient) : Loggable() {
        override val id = ingredient.id
        override val name = ingredient.displayName
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogMealDialog(initialTime: Instant? = null, onDismiss: () -> Unit) {
    val recipes by HealthAPI.db.healthDao().getAllRecipesFlow().collectAsState(emptyList())
    val ingredientRecipes by
            HealthAPI.db.healthDao().getIngredientsAsRecipesFlow().collectAsState(emptyList())

    val allLoggables =
            remember(recipes, ingredientRecipes) {
                recipes.map { Loggable.RecipeWrapper(it) } +
                        ingredientRecipes.map { Loggable.IngredientWrapper(it) }
            }

    var selectedLoggable by remember { mutableStateOf<Loggable?>(null) }
    var quantityStr by remember { mutableStateOf("1") }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val quantity = quantityStr.toDoubleOrNull()
    val isValid = selectedLoggable != null && quantity != null && quantity > 0

    AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Log Meal") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                                value = selectedLoggable?.name ?: "Select Recipe",
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                                },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                        ) {
                            allLoggables.sortedBy { it.name }.forEach { loggable ->
                                DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(loggable.name)
                                                if (loggable is Loggable.IngredientWrapper) {
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(
                                                            "(Ingredient)",
                                                            style =
                                                                    MaterialTheme.typography
                                                                            .bodySmall,
                                                            color =
                                                                    MaterialTheme.colorScheme
                                                                            .outline
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            selectedLoggable = loggable
                                            expanded = false
                                        }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                            value = quantityStr,
                            onValueChange = { quantityStr = it },
                            label = { Text("Servings") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                        enabled = isValid,
                        onClick = {
                            if (quantity != null && selectedLoggable != null) {
                                scope.launch {
                                    val time = initialTime ?: Instant.now()
                                    val nutrition: NutritionData =
                                            when (val loggable = selectedLoggable!!) {
                                                is Loggable.RecipeWrapper -> {
                                                    val ingredients =
                                                            HealthAPI.db
                                                                    .healthDao()
                                                                    .getIngredientsForRecipe(
                                                                            loggable.id
                                                                    )
                                                    var protein = 0.0
                                                    var carbs = 0.0
                                                    var fat = 0.0
                                                    var fiber = 0.0
                                                    var sugar = 0.0
                                                    var sodium = 0.0
                                                    var kcal = 0.0

                                                    ingredients.forEach { ri ->
                                                        val ing =
                                                                HealthAPI.db
                                                                        .healthDao()
                                                                        .getIngredient(
                                                                                ri.ingredientId
                                                                        )
                                                        if (ing != null) {
                                                            val units =
                                                                    HealthAPI.db
                                                                            .healthDao()
                                                                            .getUnitsForIngredient(
                                                                                    ing.id
                                                                            )
                                                            val unit =
                                                                    units.find {
                                                                        it.id == ri.unitId
                                                                    }
                                                            val grams = unit?.grams ?: 1.0
                                                            val totalGrams =
                                                                    ri.quantity * grams * quantity

                                                            protein +=
                                                                    (ing.nutritionData.protein /
                                                                            100.0) * totalGrams
                                                            carbs +=
                                                                    (ing.nutritionData
                                                                            .carbohydrates /
                                                                            100.0) * totalGrams
                                                            fat +=
                                                                    (ing.nutritionData.fat /
                                                                            100.0) * totalGrams
                                                            fiber +=
                                                                    (ing.nutritionData.fiber /
                                                                            100.0) * totalGrams
                                                            sugar +=
                                                                    (ing.nutritionData.sugar /
                                                                            100.0) * totalGrams
                                                            sodium +=
                                                                    (ing.nutritionData.sodium /
                                                                            100.0) * totalGrams
                                                            kcal +=
                                                                    (ing.nutritionData.calories /
                                                                            100.0) * totalGrams
                                                        }
                                                    }
                                                    NutritionData(
                                                            protein,
                                                            carbs,
                                                            fat,
                                                            fiber,
                                                            sugar,
                                                            sodium,
                                                            calories = kcal
                                                    )
                                                }
                                                is Loggable.IngredientWrapper -> {
                                                    // For single ingredients marked as recipes,
                                                    // 1 serving is treated as 100g
                                                    val ing = loggable.ingredient
                                                    NutritionData(
                                                            protein =
                                                                    ing.nutritionData.protein *
                                                                            quantity,
                                                            carbohydrates =
                                                                    ing.nutritionData
                                                                            .carbohydrates *
                                                                            quantity,
                                                            fat = ing.nutritionData.fat * quantity,
                                                            fiber =
                                                                    ing.nutritionData.fiber *
                                                                            quantity,
                                                            sugar =
                                                                    ing.nutritionData.sugar *
                                                                            quantity,
                                                            sodium =
                                                                    ing.nutritionData.sodium *
                                                                            quantity,
                                                            calories =
                                                                    ing.nutritionData.calories *
                                                                            quantity
                                                    )
                                                }
                                            }

                                    val record =
                                            Record(
                                                    id = UUID.randomUUID().toString(),
                                                    index = 0,
                                                    type = RecordType.Nutrition,
                                                    startTime = time,
                                                    endTime = time,
                                                    value = nutrition.calories,
                                                    nutritionData = nutrition,
                                                    metadata = selectedLoggable?.name
                                            )
                                    HealthAPI.db.healthDao().upsert(listOf(record))
                                    HealthAPI.writeHealthRecord(record)
                                    onDismiss()
                                }
                            }
                        }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
