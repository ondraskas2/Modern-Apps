package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.youpipe.MAIN_BOTTOM_BAR_ITEMS
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionCategory
import com.vayunmathur.youpipe.data.SubscriptionVideo
import com.vayunmathur.youpipe.util.setupHourlyTask

@Composable
fun SubscriptionVideosPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, category: String?) {
    val videos by viewModel.data<SubscriptionVideo>().collectAsState()
    val subscriptions by viewModel.data<Subscription>().collectAsState()
    val pairs by viewModel.data<SubscriptionCategory>().collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        setupHourlyTask(context)
    }

    val subsInCategory = pairs.filter { it.category == category }.map { pair ->
        subscriptions.first { it.id == pair.subscriptionID }
    }

    val videosInSubs = if(category == null) videos else subsInCategory.flatMap { sub ->
        videos.filter { it.channelID == sub.id }
    }

    val workInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkLiveData("subscription_fetch_immediate")
        .observeAsState()
    val currentWorkInfo = workInfos?.firstOrNull { it.state == WorkInfo.State.RUNNING }
    val fetchProgress = currentWorkInfo?.progress?.getFloat("progress", -1f) ?: -1f

    Scaffold(bottomBar = { BottomNavBar(backStack, MAIN_BOTTOM_BAR_ITEMS, Route.SubscriptionsPage) }) { paddingValues ->
        LazyColumn(Modifier.padding(paddingValues)) {
            if (fetchProgress in 0f..1f) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator({fetchProgress})
                    }
                }
            }
            items(videosInSubs.map {
                VideoInfo(it.name, it.id, it.duration, it.views, it.uploadDate, it.thumbnailURL, it.author)
            }.sortedByDescending { it.uploadDate }) {
                VideoItem(backStack, viewModel, it, true)
            }
        }
    }
}