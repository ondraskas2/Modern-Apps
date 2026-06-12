package com.vayunmathur.launcher.ui

import android.appwidget.AppWidgetHost
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.vayunmathur.launcher.LauncherViewModel
import com.vayunmathur.launcher.MainActivity
import com.vayunmathur.launcher.util.NotificationListener
import com.vayunmathur.launcher.widget.WidgetPicker

@Composable
fun LauncherScreen(
    viewModel: LauncherViewModel,
    widgetHost: AppWidgetHost?,
    activity: MainActivity
) {
    val apps by viewModel.apps.collectAsState()
    val query by viewModel.query.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val isDrawerOpen by viewModel.isDrawerOpen.collectAsState()
    val focusSearch by viewModel.focusSearch.collectAsState()
    val dockItems by viewModel.dockItems.collectAsState()
    val pageCount by viewModel.pageCount.collectAsState()
    val gridColumns by viewModel.gridColumns.collectAsState()
    val gridRows by viewModel.gridRows.collectAsState()
    val homeContextMenuVisible by viewModel.homeContextMenuVisible.collectAsState()
    val showWidgetPicker by viewModel.showWidgetPicker.collectAsState()
    val contextMenuApp by viewModel.contextMenuApp.collectAsState()
    val contextMenuDockApp by viewModel.contextMenuDockApp.collectAsState()
    val openFolder by viewModel.openFolder.collectAsState()
    val isDragging by viewModel.isDragging.collectAsState()
    val dragSource by viewModel.dragSource.collectAsState()
    val dragOffset by viewModel.dragOffset.collectAsState()
    val badgeCounts by NotificationListener.badgeCounts.collectAsState()

    val context = LocalContext.current
    val view = LocalView.current
    val pagerState = rememberPagerState(pageCount = { pageCount })

    Box(modifier = Modifier.fillMaxSize()) {
        WallpaperBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .launcherGestures(
                    onSwipeUp = { viewModel.openDrawer() },
                    onSwipeDown = {
                        try {
                            @Suppress("DEPRECATION")
                            val sbService = context.getSystemService("statusbar")
                            sbService?.javaClass
                                ?.getMethod("expandNotificationsPanel")
                                ?.invoke(sbService)
                        } catch (_: Exception) {}
                    }
                )
        ) {
            AtAGlanceWidget()

            HomePages(
                pagerState = pagerState,
                pageCount = pageCount,
                gridColumns = gridColumns,
                gridRows = gridRows,
                getPageItems = { viewModel.getPageItems(it) },
                getPageWidgets = { viewModel.getPageWidgets(it) },
                getAppInfo = { viewModel.getAppInfo(it) },
                widgetHost = widgetHost,
                onAppClick = { item ->
                    context.packageManager.getLaunchIntentForPackage(item.packageName)?.let {
                        context.startActivity(it)
                    }
                },
                onAppLongClick = { item ->
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    viewModel.showAppContextMenu(item)
                },
                onEmptyLongClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    viewModel.showHomeContextMenu()
                },
                onDragStart = { source, offset ->
                    viewModel.startDrag(source, offset)
                },
                onDrag = { offset ->
                    viewModel.updateDragPosition(offset)
                },
                onDragEnd = { page, row, col ->
                    viewModel.endDrag()
                },
                isDragging = isDragging,
                dragSource = dragSource,
                modifier = Modifier.weight(1f)
            )

            SearchPill(onClick = { viewModel.openDrawer(focusSearch = true) })

            DockBar(
                dockItems = dockItems,
                getIcon = { viewModel.getIcon(it) },
                onAppClick = { item ->
                    context.packageManager.getLaunchIntentForPackage(item.packageName)?.let {
                        context.startActivity(it)
                    }
                },
                onAppLongClick = { item ->
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    viewModel.showDockAppContextMenu(item)
                }
            )
        }

        // Drag overlay
        if (isDragging && dragSource != null) {
            val dragIcon = when (val src = dragSource) {
                is com.vayunmathur.launcher.DragSource.Home ->
                    viewModel.getIcon(src.item.packageName)
                is com.vayunmathur.launcher.DragSource.Dock ->
                    viewModel.getIcon(src.item.packageName)
                is com.vayunmathur.launcher.DragSource.Drawer ->
                    src.appInfo.icon
                else -> null
            }
            if (dragIcon != null) {
                DragOverlay(icon = dragIcon, offset = dragOffset)
            }
        }

        if (isDrawerOpen) {
            AppDrawer(
                apps = apps,
                query = query,
                searchResults = searchResults,
                isSearchActive = isSearching,
                onQueryChange = viewModel::setQuery,
                onSearchActiveChange = viewModel::setSearching,
                onDismiss = { viewModel.closeDrawer() },
                onAppClick = { app ->
                    context.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                        context.startActivity(it)
                    }
                },
                onAppLongClick = { app ->
                    viewModel.addToHomeFromDrawer(app, pagerState.currentPage, 0, 0)
                },
                focusSearch = focusSearch
            )
        }

        HomeContextMenu(
            expanded = homeContextMenuVisible,
            onDismiss = { viewModel.hideHomeContextMenu() },
            onWidgets = { viewModel.showWidgetPicker() }
        )

        contextMenuApp?.let { item ->
            AppContextMenu(
                expanded = true,
                packageName = item.packageName,
                onDismiss = { viewModel.hideAppContextMenu() },
                onRemove = {
                    viewModel.removeFromPage(item)
                    viewModel.hideAppContextMenu()
                }
            )
        }

        contextMenuDockApp?.let { item ->
            AppContextMenu(
                expanded = true,
                packageName = item.packageName,
                onDismiss = { viewModel.hideDockAppContextMenu() },
                onRemove = {
                    viewModel.removeFromDock(item)
                    viewModel.hideDockAppContextMenu()
                }
            )
        }

        if (showWidgetPicker) {
            WidgetPicker(
                onDismiss = { viewModel.hideWidgetPicker() },
                onWidgetSelected = { providerInfo ->
                    viewModel.hideWidgetPicker()
                    if (widgetHost != null) {
                        val widgetId = widgetHost.allocateAppWidgetId()
                        activity.requestBindWidget(widgetId, providerInfo) { boundId, info ->
                            viewModel.addWidget(
                                appWidgetId = boundId,
                                page = pagerState.currentPage,
                                row = 0, col = 0,
                                spanX = (info.minWidth / 80).coerceAtLeast(1),
                                spanY = (info.minHeight / 80).coerceAtLeast(1)
                            )
                        }
                    }
                }
            )
        }

        openFolder?.let { folder ->
            val folderItems = viewModel.getFolderItems(folder.id)
            FolderDialog(
                folder = folder,
                folderApps = folderItems,
                getAppInfo = { viewModel.getAppInfo(it) },
                onAppClick = { item ->
                    context.packageManager.getLaunchIntentForPackage(item.packageName)?.let {
                        context.startActivity(it)
                    }
                    viewModel.closeFolder()
                },
                onRename = { viewModel.renameFolder(folder, it) },
                onDismiss = { viewModel.closeFolder() }
            )
        }
    }
}
