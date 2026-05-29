package com.vayunmathur.library.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.R
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.library.util.ReorderableDatabaseItem
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
inline fun <reified T : DatabaseItem, Route : NavKey, reified EditPage : Route> ListPage(
    backStack: NavBackStack<Route>,
    data: List<T>,
    title: String,
    crossinline headlineContent: @Composable (T) -> Unit,
    crossinline supportingContent: @Composable (T) -> Unit,
    crossinline viewPage: suspend (id: Long) -> Route,
    noinline editPage: (() -> Route)? = null,
    settingsPage: Route? = null,
    crossinline otherActions: @Composable () -> Unit = {},
    crossinline leadingContent: @Composable (T) -> Unit = {},
    crossinline trailingContent: @Composable (T) -> Unit = {},
    noinline itemModifier: @Composable (T) -> Modifier = { Modifier },
    noinline itemColors: @Composable (T) -> ListItemColors = { ListItemDefaults.colors() },
    searchEnabled: Boolean = false,
    sortOrder: Comparator<T>? = null,
    crossinline searchString: (T) -> String = { it.toString() },
    noinline bottomBar: @Composable () -> Unit = {},
    noinline fab: (@Composable () -> Unit)? = null,
) {
    var searchQuery by remember { mutableStateOf("") }
    val displayed by remember(data, searchQuery, sortOrder) {
        derivedStateOf {
            val filtered = if (searchQuery.isBlank()) data
            else data.filter { searchString(it).contains(searchQuery, true) }
            if (sortOrder != null) filtered.sortedWith(sortOrder) else filtered
        }
    }

    BackHandler(enabled = searchEnabled && searchQuery.isNotEmpty()) {
        searchQuery = ""
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchEnabled) {
                        CommonSearchBar(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = title,
                            padding = PaddingValues(0.dp)
                        )
                    } else {
                        Text(title)
                    }
                },
                actions = {
                    otherActions()
                    settingsPage?.let { settingsPage ->
                        IconButton(onClick = { backStack.add(settingsPage) }) {
                            IconSettings()
                        }
                    }
                }
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = {
            Column {
                fab?.invoke()
                if (editPage != null && backStack.last() !is EditPage) {
                    FloatingActionButton(onClick = { backStack.add(editPage()) }) {
                        IconAdd()
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = paddingValues
        ) {
            items(displayed, key = { it.id }) { item ->
                val modifier = itemModifier(item)
                ListItem({ headlineContent(item) }, modifier.clickable {
                    coroutineScope.launch {
                        backStack.add(viewPage(item.id))
                    }
                }, {}, { supportingContent(item) }, { leadingContent(item) }, {
                    Row {
                        trailingContent(item)
                    }
                }, itemColors(item))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
inline fun <reified T : ReorderableDatabaseItem<T>, Route : NavKey, reified EditPage : Route> ListPageR(
    backStack: NavBackStack<Route>,
    data: List<T>,
    noinline onReorder: suspend (List<T>) -> Unit,
    title: String,
    crossinline headlineContent: @Composable (T) -> Unit,
    crossinline supportingContent: @Composable (T) -> Unit,
    crossinline viewPage: (id: Long) -> Route,
    crossinline editPage: () -> Route,
    settingsPage: Route? = null,
    crossinline otherActions: @Composable () -> Unit = {},
    noinline selectionActions: @Composable (selectedItems: List<T>, clearSelection: () -> Unit) -> Unit = { _, _ -> },
    crossinline leadingContent: @Composable (T) -> Unit = {},
    crossinline trailingContent: @Composable (T) -> Unit = {},
    searchEnabled: Boolean = false,
    crossinline searchString: (T) -> String = { it.toString() },
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered by remember(data, searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) data
            else data.filter { searchString(it).contains(searchQuery, true) }
        }
    }

    BackHandler(enabled = searchEnabled && searchQuery.isNotEmpty()) {
        searchQuery = ""
    }

    val hapticFeedback = LocalHapticFeedback.current
    val selectedIds = remember { mutableStateListOf<Long>() }
    val isSelectionMode by remember { derivedStateOf { selectedIds.isNotEmpty() } }

    val listState = rememberLazyListState()
    var localData by remember { mutableStateOf(filtered) }

    val selectedIndices by remember {
        derivedStateOf {
            localData.mapIndexedNotNull { index, item ->
                if (item.id in selectedIds) index else null
            }
        }
    }
    val isContiguous by remember {
        derivedStateOf {
            selectedIndices.isEmpty() || selectedIndices.size == 1 ||
                (selectedIndices.last() - selectedIndices.first() == selectedIndices.size - 1)
        }
    }

    val state = rememberReorderableLazyListState(listState, onMove = { from, to ->
        val fromIdx = from.index
        val toIdx = to.index
        if (fromIdx in localData.indices && toIdx in localData.indices) {
            val mutableList = localData.toMutableList()
            val draggedItem = localData[fromIdx]
            if (isSelectionMode && isContiguous && draggedItem.id in selectedIds) {
                val selectedInOrder = selectedIndices.map { localData[it] }
                mutableList.removeAll { it.id in selectedIds }
                val targetItem = localData[toIdx]
                var insertIdx = mutableList.indexOfFirst { it.id == targetItem.id }
                if (insertIdx == -1) insertIdx = 0
                if (toIdx > fromIdx) insertIdx++
                val prevPos = mutableList.getOrNull(insertIdx - 1)?.position
                val nextPos = mutableList.getOrNull(insertIdx)?.position
                val startPos = prevPos ?: ((nextPos ?: 0.0) - 100.0)
                val endPos = nextPos ?: (startPos + 100.0)
                val step = (endPos - startPos) / (selectedInOrder.size + 1)
                val movedItems = selectedInOrder.mapIndexed { index, item ->
                    item.withPosition(startPos + step * (index + 1))
                }
                mutableList.addAll(insertIdx, movedItems)
                localData = mutableList
            } else if (!isSelectionMode) {
                val prevIdx = if (toIdx > fromIdx) toIdx else toIdx - 1
                val nextIdx = if (toIdx > fromIdx) toIdx + 1 else toIdx
                val prevPos = localData.getOrNull(prevIdx)?.position
                val nextPos = localData.getOrNull(nextIdx)?.position
                val resultItemPosition = when {
                    prevPos == null -> (nextPos ?: 0.0) - 50.0
                    nextPos == null -> prevPos + 50.0
                    else -> (prevPos + nextPos) / 2.0
                }
                val movedItem = mutableList.removeAt(fromIdx).withPosition(resultItemPosition)
                mutableList.add(toIdx, movedItem)
                localData = mutableList
            }
            hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    })

    LaunchedEffect(filtered) {
        if (!state.isAnyItemDragging) {
            localData = filtered.sortedBy { it.position }
        }
    }

    val isDragging = state.isAnyItemDragging
    LaunchedEffect(isDragging) {
        if (!isDragging && localData != filtered) {
            onReorder(localData)
        }
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text(selectedIds.size.toString()) },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds.clear() }) {
                            IconClose()
                        }
                    },
                    actions = {
                        selectionActions(localData.filter { it.id in selectedIds }) {
                            selectedIds.clear()
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        if (searchEnabled) {
                            CommonSearchBar(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = title,
                                padding = PaddingValues(0.dp)
                            )
                        } else {
                            Text(title)
                        }
                    },
                    actions = {
                        otherActions()
                        settingsPage?.let { settingsPage ->
                            IconButton(onClick = { backStack.add(settingsPage) }) {
                                IconSettings()
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (backStack.last() !is EditPage && !isSelectionMode) {
                FloatingActionButton(onClick = { backStack.add(editPage()) }) {
                    IconAdd()
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = paddingValues
        ) {
            items(localData, key = { it.id }) { item ->
                ReorderableItem(state, key = item.id) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)
                    val isSelected = item.id in selectedIds
                    Surface(Modifier.animateItem(), shadowElevation = elevation) {
                        ListItem(
                            headlineContent = { headlineContent(item) },
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (isSelectionMode) {
                                        if (isSelected) selectedIds.remove(item.id)
                                        else selectedIds.add(item.id)
                                    } else {
                                        backStack.add(viewPage(item.id))
                                    }
                                },
                                onLongClick = {
                                    if (!isSelectionMode) {
                                        selectedIds.add(item.id)
                                    }
                                }
                            ),
                            supportingContent = { supportingContent(item) },
                            leadingContent = { leadingContent(item) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    trailingContent(item)
                                    if (localData.size > 1 && selectedIds.size < localData.size && (!isSelectionMode || isContiguous)) {
                                        IconButton(
                                            modifier = Modifier.draggableHandle(
                                                onDragStarted = {
                                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                                },
                                                onDragStopped = {
                                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                                },
                                            ),
                                            onClick = {},
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.drag_handle_24px),
                                                contentDescription = "Reorder"
                                            )
                                        }
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                            ),
                            tonalElevation = elevation,
                            shadowElevation = elevation
                        )
                    }
                }
            }
        }
    }
}
