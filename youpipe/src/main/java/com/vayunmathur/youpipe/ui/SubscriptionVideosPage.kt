package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.youpipe.MAIN_BOTTOM_BAR_ITEMS
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.util.YouPipeViewModel

@Composable
fun SubscriptionVideosPage(
    backStack: NavBackStack<Route>,
    youPipeViewModel: YouPipeViewModel,
    category: String?,
) {
    val videos by youPipeViewModel.subscriptionVideos.collectAsState()
    val subscriptions by youPipeViewModel.subscriptions.collectAsState()
    val pairs by youPipeViewModel.subscriptionCategories.collectAsState()
    val fetchProgress by youPipeViewModel.fetchProgress.collectAsState()

    val subsInCategory = pairs.filter { it.category == category }.map { pair ->
        subscriptions.first { it.id == pair.subscriptionID }
    }

    val videosInSubs = if (category == null) videos else subsInCategory.flatMap { sub ->
        videos.filter { it.channelID == sub.id }
    }

    Scaffold(bottomBar = { BottomNavBar(backStack, MAIN_BOTTOM_BAR_ITEMS, Route.SubscriptionsPage) }) { paddingValues ->
        LazyColumn(Modifier.padding(paddingValues)) {
            if (fetchProgress in 0f..1f) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator({ fetchProgress })
                    }
                }
            }
            items(videosInSubs.map {
                VideoInfo(it.name, it.id, it.duration, it.views, it.uploadDate, it.thumbnailURL, it.author)
            }.sortedByDescending { it.uploadDate }) {
                VideoItem(backStack, youPipeViewModel, it, true)
            }
        }
    }
}
