package com.vayunmathur.education.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.education.Route
import com.vayunmathur.education.content.Band
import com.vayunmathur.education.content.Grades
import com.vayunmathur.education.content.ModuleType
import com.vayunmathur.education.util.EducationViewModel
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentPage(backStack: NavBackStack<Route>, viewModel: EducationViewModel) {
    val learner by viewModel.learner.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val content = viewModel.content
    val l = learner ?: return

    // Which unit (if any) is currently having its deadline picked.
    var pendingDeadlineUnitId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Parent settings") },
                navigationIcon = { IconNavigation(backStack) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // --- Learner ---
            SectionHeader("Learner")
            var name by remember { mutableStateOf(l.name) }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; viewModel.setName(it) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            GradeDropdown(current = l.gradeLevel, onSelect = viewModel::setGrade)
            BandOverrideDropdown(current = l.bandOverride, onSelect = viewModel::setBandOverride)
            Text(
                "Active band: ${bandLabel(viewModel.bandOf(l))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            DailyGoalRow(goal = l.dailyGoal, onChange = viewModel::setDailyGoal)

            HorizontalDivider()

            // --- Progress ---
            SectionHeader("Progress")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StreakChip(l.streakCount)
                StarsChip(l.totalStars)
            }
            val practiced = progress.values.count { it.stars > 0 }
            Text(
                "$practiced skill${if (practiced == 1) "" else "s"} with stars earned",
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider()

            // --- Deadlines ---
            SectionHeader("Module deadlines")
            Text(
                "Set a target date for a unit. It shows in your child's app as a gentle reminder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content.courses.forEach { course ->
                Text(
                    course.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                course.units.forEach { unit ->
                    val deadline = viewModel.deadlineFor(ModuleType.UNIT, unit.id)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(unit.title, style = MaterialTheme.typography.bodyLarge)
                            deadline?.let {
                                Text(
                                    "Due ${formatEpochDay(it.dueEpochDay)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (deadline != null) {
                            IconButton(onClick = { viewModel.removeDeadline(deadline) }) { IconDelete() }
                        }
                        OutlinedButton(onClick = { pendingDeadlineUnitId = unit.id }) {
                            Text(if (deadline == null) "Set date" else "Change")
                        }
                    }
                }
            }

            HorizontalDivider()

            // --- Security ---
            SectionHeader("Security")
            ChangePinRow(onSetPin = viewModel::setPin)
        }
    }

    pendingDeadlineUnitId?.let { unitId ->
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { pendingDeadlineUnitId = null },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        state.selectedDateMillis?.let { ms ->
                            val day = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                            viewModel.setDeadline(ModuleType.UNIT, unitId, day)
                        }
                        pendingDeadlineUnitId = null
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeadlineUnitId = null }) { Text("Cancel") }
            },
        ) {
            DatePicker(state)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradeDropdown(current: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = Grades.label(current),
            onValueChange = {},
            readOnly = true,
            label = { Text("Grade") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Grades.all.forEach { g ->
                DropdownMenuItem(
                    text = { Text(Grades.label(g)) },
                    onClick = { onSelect(g); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BandOverrideDropdown(current: String?, onSelect: (Band?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = current?.let { runCatching { Band.valueOf(it) }.getOrNull() }
        ?.let { bandLabel(it) } ?: "Automatic (by grade)"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Band override") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Automatic (by grade)") },
                onClick = { onSelect(null); expanded = false },
            )
            Band.entries.forEach { band ->
                DropdownMenuItem(
                    text = { Text(bandLabel(band)) },
                    onClick = { onSelect(band); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun DailyGoalRow(goal: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Daily goal: $goal activit${if (goal == 1) "y" else "ies"}", Modifier.weight(1f))
        OutlinedButton(onClick = { onChange((goal - 1).coerceAtLeast(1)) }) { Text("-") }
        Text("  $goal  ", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { onChange(goal + 1) }) { Text("+") }
    }
}

@Composable
private fun ChangePinRow(onSetPin: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) { pin = it; saved = false } },
            label = { Text("New PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { onSetPin(pin); pin = ""; saved = true },
            enabled = pin.length >= 4,
            modifier = Modifier.padding(start = 8.dp),
        ) { Text(if (saved) "Saved" else "Save") }
    }
}
