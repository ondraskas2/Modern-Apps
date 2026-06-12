package com.vayunmathur.launcher.ui

import android.appwidget.AppWidgetHost
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import android.view.HapticFeedbackConstants
import com.vayunmathur.launcher.AppInfo
import com.vayunmathur.launcher.DragSource
import com.vayunmathur.launcher.data.HomeScreenItem
import com.vayunmathur.launcher.data.WidgetItem
import com.vayunmathur.launcher.widget.HostedWidget

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomePages(
    pagerState: PagerState,
    pageCount: Int,
    gridColumns: Int,
    gridRows: Int,
    getPageItems: (Int) -> List<HomeScreenItem>,
    getPageWidgets: (Int) -> List<WidgetItem>,
    getAppInfo: (String) -> AppInfo?,
    widgetHost: AppWidgetHost?,
    onAppClick: (HomeScreenItem) -> Unit,
    onAppLongClick: (HomeScreenItem) -> Unit,
    onEmptyLongClick: () -> Unit,
    onDragStart: (DragSource, Offset) -> Unit = { _, _ -> },
    onDrag: (Offset) -> Unit = {},
    onDragEnd: (Int, Int, Int) -> Unit = { _, _, _ -> },
    isDragging: Boolean = false,
    dragSource: DragSource? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            userScrollEnabled = !isDragging
        ) { page ->
            HomePageGrid(
                page = page,
                items = getPageItems(page),
                widgets = getPageWidgets(page),
                gridColumns = gridColumns,
                gridRows = gridRows,
                getAppInfo = getAppInfo,
                widgetHost = widgetHost,
                onAppClick = onAppClick,
                onAppLongClick = onAppLongClick,
                onEmptyLongClick = onEmptyLongClick,
                onDragStart = onDragStart,
                onDrag = onDrag,
                onDragEnd = onDragEnd,
                isDragging = isDragging,
                dragSource = dragSource
            )
        }

        PageIndicator(
            pageCount = pageCount,
            currentPage = pagerState.currentPage,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomePageGrid(
    page: Int,
    items: List<HomeScreenItem>,
    widgets: List<WidgetItem>,
    gridColumns: Int,
    gridRows: Int,
    getAppInfo: (String) -> AppInfo?,
    widgetHost: AppWidgetHost?,
    onAppClick: (HomeScreenItem) -> Unit,
    onAppLongClick: (HomeScreenItem) -> Unit,
    onEmptyLongClick: () -> Unit,
    onDragStart: (DragSource, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (Int, Int, Int) -> Unit,
    isDragging: Boolean,
    dragSource: DragSource?
) {
    val view = LocalView.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .combinedClickable(
                onClick = {},
                onLongClick = onEmptyLongClick
            )
            .padding(horizontal = 11.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            for (row in 0 until gridRows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    for (col in 0 until gridColumns) {
                        val item = items.find { it.row == row && it.col == col }
                        val widget = widgets.find { it.row == row && it.col == col }
                        val isBeingDragged = isDragging && dragSource is DragSource.Home && dragSource.item.id == item?.id

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .then(
                                    if (item != null) {
                                        Modifier.pointerInput(item) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { offset ->
                                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                    onDragStart(DragSource.Home(item), offset)
                                                },
                                                onDrag = { change, _ ->
                                                    change.consume()
                                                    onDrag(change.position)
                                                },
                                                onDragEnd = { onDragEnd(page, row, col) },
                                                onDragCancel = { onDragEnd(page, row, col) }
                                            )
                                        }
                                    } else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                isBeingDragged -> {
                                    // Hide the original while dragging
                                }
                                item != null -> {
                                    val appInfo = getAppInfo(item.packageName)
                                    if (appInfo != null) {
                                        AppIcon(
                                            name = appInfo.name,
                                            icon = appInfo.icon,
                                            onClick = { onAppClick(item) },
                                            onLongClick = { onAppLongClick(item) }
                                        )
                                    }
                                }
                                widget != null && widgetHost != null -> {
                                    HostedWidget(
                                        appWidgetId = widget.appWidgetId,
                                        widgetHost = widgetHost,
                                        cellWidth = 80.dp,
                                        cellHeight = 80.dp,
                                        spanX = widget.spanX,
                                        spanY = widget.spanY
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier
) {
    val activeColor = Color(0xFF0B57D0)
    val inactiveColor = Color(0xFF0B57D0).copy(alpha = 0.5f)

    Row(
        modifier = modifier.height(24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val isActive = index == currentPage
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (isActive) activeColor else inactiveColor)
            )
        }
    }
}
