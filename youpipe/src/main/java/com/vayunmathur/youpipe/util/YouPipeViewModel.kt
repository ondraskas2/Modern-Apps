package com.vayunmathur.youpipe.util

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.youpipe.data.CachedRelatedVideo
import com.vayunmathur.youpipe.data.CachedRelatedVideoDao
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.data.DownloadedVideoDao
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.data.HistoryVideoDao
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionCategory
import com.vayunmathur.youpipe.data.SubscriptionCategoryDao
import com.vayunmathur.youpipe.data.SubscriptionDao
import com.vayunmathur.youpipe.data.SubscriptionVideo
import com.vayunmathur.youpipe.data.SubscriptionVideoDao
import com.vayunmathur.youpipe.ui.AudioStream
import com.vayunmathur.youpipe.ui.ChannelInfo
import com.vayunmathur.youpipe.ui.Comment
import com.vayunmathur.youpipe.ui.ItemInfo
import com.vayunmathur.youpipe.ui.VideoChapter
import com.vayunmathur.youpipe.ui.VideoData
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.VideoStream
import com.vayunmathur.youpipe.ui.fromHTML
import com.vayunmathur.youpipe.ui.getVideoCodecName
import com.vayunmathur.youpipe.ui.getAudioCodecName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.zip.ZipInputStream
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * Single ViewModel for the YouPipe app.
 *
 * Owns:
 *  - Search query state, suggestions, and result list (via NewPipe Extractor).
 *  - Per-video data load (streams, comments, related, segments, sponsor segments)
 *    triggered by [loadVideo].
 *  - Per-channel data load triggered by [loadChannel].
 *  - YouTube/NewPipe/Youpipe import/export pipelines and their progress state.
 *  - WorkManager subscription-fetch progress mirror.
 *  - One-time hourly subscription-fetch task setup.
 *  - All Room CRUD through directly-injected DAOs.
 */
class YouPipeViewModel(
    application: Application,
    private val subscriptionDao: SubscriptionDao,
    private val subscriptionCategoryDao: SubscriptionCategoryDao,
    private val subscriptionVideoDao: SubscriptionVideoDao,
    private val historyVideoDao: HistoryVideoDao,
    private val downloadedVideoDao: DownloadedVideoDao,
    private val cachedRelatedVideoDao: CachedRelatedVideoDao,
) : AndroidViewModel(application) {

    // ===================== Data StateFlows =====================

    val subscriptions: StateFlow<List<Subscription>> = subscriptionDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val subscriptionCategories: StateFlow<List<SubscriptionCategory>> = subscriptionCategoryDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val subscriptionVideos: StateFlow<List<SubscriptionVideo>> = subscriptionVideoDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val historyVideos: StateFlow<List<HistoryVideo>> = historyVideoDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloadedVideos: StateFlow<List<DownloadedVideo>> = downloadedVideoDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ===================== Derived, ready-to-render state =====================

    /** History sorted most-recent-first, as shown by the History screen. */
    val historyVideosByRecency: StateFlow<List<HistoryVideo>> = historyVideoDao.getAllFlow()
        .map { list -> list.sortedByDescending { it.timestamp } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Downloaded videos sorted most-recent-first, as shown by the Downloads screen. */
    val downloadedVideosByRecency: StateFlow<List<DownloadedVideo>> = downloadedVideoDao.getAllFlow()
        .map { list -> list.sortedByDescending { it.timestamp } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Distinct subscription-category names, as listed by the Subscriptions screen. */
    val categoryNames: StateFlow<List<String>> = subscriptionCategoryDao.getAllFlow()
        .map { cats -> cats.map { it.category }.distinct() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The videos to display on the subscription-videos screen for [category]
     * (all subscriptions when null), already filtered, mapped to [VideoInfo],
     * and sorted newest-first. Subscriptions referenced by a dangling category
     * row are skipped rather than crashing.
     */
    fun subscriptionVideosFor(category: String?): Flow<List<VideoInfo>> =
        combine(subscriptionVideos, subscriptions, subscriptionCategories) { videos, subs, cats ->
            val visible = if (category == null) {
                videos
            } else {
                val subIds = cats.filter { it.category == category }
                    .mapNotNull { pair -> subs.firstOrNull { it.id == pair.subscriptionID }?.id }
                    .toSet()
                videos.filter { it.channelID in subIds }
            }
            visible
                .map { VideoInfo(it.name, it.id, it.duration, it.views, it.uploadDate, it.thumbnailURL, it.author) }
                .sortedByDescending { it.uploadDate }
        }

    /**
     * The subscriptions currently assigned to [category]. A category row that
     * references a missing subscription is skipped rather than crashing.
     */
    fun subscriptionsInCategory(category: String): Flow<List<Subscription>> =
        combine(subscriptionCategories, subscriptions) { cats, subs ->
            cats.filter { it.category == category }
                .mapNotNull { pair -> subs.firstOrNull { it.id == pair.subscriptionID } }
        }

    // ===================== By-id flows =====================

    fun historyById(id: Long): Flow<HistoryVideo?> = historyVideoDao.getByIdFlow(id)
    fun downloadedById(id: Long): Flow<DownloadedVideo?> = downloadedVideoDao.getByIdFlow(id)

    // ===================== Mutations =====================

    fun upsertSubscription(item: Subscription) {
        viewModelScope.launch(Dispatchers.IO) { subscriptionDao.upsert(item) }
    }

    fun deleteSubscription(item: Subscription) {
        viewModelScope.launch(Dispatchers.IO) { subscriptionDao.delete(item) }
    }

    fun upsertHistoryVideo(item: HistoryVideo) {
        viewModelScope.launch(Dispatchers.IO) { historyVideoDao.upsert(item) }
    }

    fun deleteHistoryVideos(ids: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) { historyVideoDao.deleteByIds(ids) }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) { historyVideoDao.clearAll() }
    }

    fun deleteDownloadedVideo(item: DownloadedVideo) {
        viewModelScope.launch(Dispatchers.IO) { downloadedVideoDao.delete(item) }
    }

    suspend fun replaceCategory(originalCategoryName: String?, categoryName: String, ids: List<Long>) {
        withContext(Dispatchers.IO) {
            subscriptionCategoryDao.replaceCategory(originalCategoryName, categoryName, ids)
        }
    }

    // ===================== Recommendations =====================

    private val _recommendations = MutableStateFlow<List<VideoInfo>>(emptyList())
    val recommendations: StateFlow<List<VideoInfo>> = _recommendations.asStateFlow()

    private val _recommendationsLoading = MutableStateFlow(false)
    val recommendationsLoading: StateFlow<Boolean> = _recommendationsLoading.asStateFlow()

    fun loadRecommendations() {
        viewModelScope.launch(Dispatchers.IO) {
            _recommendationsLoading.value = true
            try {
                val history = historyVideos.value
                val subNames = subscriptions.value.map { it.name.lowercase() }.toSet()
                val now = Clock.System.now()

                val profile = buildInterestProfile(history, now)
                val candidates = gatherCandidates(profile)
                val ranked = rankRecommendations(candidates, history, subNames, now)

                _recommendations.value = ranked
                fetchDeArrowForVideos(ranked.map { it.videoID })
            } catch (e: Exception) {
                Log.e(TAG, "Recommendation error", e)
            }
            _recommendationsLoading.value = false
        }
    }

    /**
     * Pulls candidates from all sources in parallel and merges them. Each source
     * is isolated in its own try/catch so one failing network call never blanks
     * the feed.
     */
    private suspend fun gatherCandidates(profile: InterestProfile): List<Candidate> = coroutineScope {
        val history = historyVideos.value
        val subs = subscriptions.value
        val sourceTimestamps = history.associate { it.id to it.timestamp }

        val relatedDeferred = async(Dispatchers.IO) {
            try {
                cachedRelatedVideoDao.getAll().map { item ->
                    Candidate(item.videoItem, RecSource.RELATED, sourceTimestamps[item.sourceVideoID])
                }
            } catch (e: Exception) {
                Log.e(TAG, "Related candidates error", e); emptyList()
            }
        }

        val trendingDeferred = async(Dispatchers.IO) {
            try {
                getTrendingVideos().map { Candidate(it, RecSource.TRENDING) }
            } catch (e: Exception) {
                Log.e(TAG, "Trending candidates error", e); emptyList()
            }
        }

        val subscriptionCandidates = subscriptionVideos.value.map {
            Candidate(
                VideoInfo(it.name, it.id, it.duration, it.views, it.uploadDate, it.thumbnailURL, it.author),
                RecSource.SUBSCRIPTION,
            )
        }

        val topChannelDeferred = async(Dispatchers.IO) {
            try {
                profile.authorWeights.entries
                    .sortedByDescending { it.value }
                    .take(MAX_TOP_CHANNELS)
                    .flatMap { (author, _) ->
                        val channelId = resolveChannelId(author, subs) ?: return@flatMap emptyList()
                        getChannelVideos(channelId)
                            .take(TOP_CHANNEL_VIDEOS)
                            .map { Candidate(it, RecSource.TOP_CHANNEL) }
                            .toList()
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Top-channel candidates error", e); emptyList()
            }
        }

        val searchDeferred = async(Dispatchers.IO) {
            try {
                profile.keywordWeights.entries
                    .sortedByDescending { it.value }
                    .take(MAX_SEARCH_KEYWORDS)
                    .flatMap { (keyword, _) ->
                        searchVideos(keyword).map { Candidate(it, RecSource.SEARCH) }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Search candidates error", e); emptyList()
            }
        }

        relatedDeferred.await() + trendingDeferred.await() + subscriptionCandidates +
            topChannelDeferred.await() + searchDeferred.await()
    }

    /**
     * Resolves an author name to a YouTube channel id, preferring a matching
     * subscription and falling back to a bounded channel search.
     */
    private suspend fun resolveChannelId(authorName: String, subs: List<Subscription>): String? {
        subs.firstOrNull { it.name.equals(authorName, ignoreCase = true) }?.let { return it.channelID }
        return try {
            val ex = ServiceList.YouTube.getSearchExtractor(authorName)
            ex.fetchPage()
            ex.initialPage.items.filterIsInstance<ChannelInfoItem>()
                .firstOrNull()?.let { channelURLtoID(it.url) }
        } catch (e: Exception) {
            null
        }
    }

    // ===================== Search =====================

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val _searchResults = MutableStateFlow<List<ItemInfo>>(emptyList())
    val searchResults: StateFlow<List<ItemInfo>> = _searchResults.asStateFlow()

    private var suggestionJob: Job? = null
    private var searchJob: Job? = null

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        suggestionJob?.cancel()
        suggestionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _suggestions.value = if (query.isNotBlank()) {
                    ServiceList.YouTube.suggestionExtractor
                        .suggestionList(query)
                        .map { it.decodeHtml() }
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Suggestion error", e)
            }
        }
    }

    /** Returns the resolved videoID if the query is a watch URL, else null. */
    fun resolveWatchUrl(): Long? {
        val q = _searchQuery.value
        return if (q.contains("/watch?v=")) videoURLtoID(q) else null
    }

    fun performSearch() {
        val q = _searchQuery.value
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val ex = ServiceList.YouTube.getSearchExtractor(q)
                ex.fetchPage()
                val results = ex.initialPage.items.mapNotNull { item ->
                    when (item) {
                        is StreamInfoItem -> item.toVideoInfo()
                        is ChannelInfoItem -> ChannelInfo(
                            item.name.decodeHtml(),
                            channelURLtoID(item.url),
                            item.subscriberCount,
                            0,
                            item.thumbnails.first().url,
                        )
                        else -> null
                    }
                }
                _searchResults.value = results
                fetchDeArrowForVideos(results.filterIsInstance<VideoInfo>().map { it.videoID })
                _suggestions.value = emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Search error", e)
            }
        }
    }

    // ===================== Channel =====================

    data class ChannelState(
        val info: ChannelInfo? = null,
        val videos: List<VideoInfo> = emptyList(),
    )

    private val _channelState = MutableStateFlow(ChannelState())
    val channelState: StateFlow<ChannelState> = _channelState.asStateFlow()
    private var channelJob: Job? = null

    fun loadChannel(channelID: String) {
        channelJob?.cancel()
        _channelState.value = ChannelState()
        channelJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = getChannelInfo(channelID)
                _channelState.update { it.copy(info = info) }
                val channelVideos = mutableListOf<VideoInfo>()
                getChannelVideos(info.channelID).forEach { video ->
                    channelVideos.add(video)
                    _channelState.update { it.copy(videos = it.videos + video) }
                }
                fetchDeArrowForVideos(channelVideos.map { it.videoID })
            } catch (e: Exception) {
                Log.e(TAG, "Channel load error", e)
            }
        }
    }

    // ===================== Video =====================

    data class VideoState(
        val data: VideoData? = null,
        val videoStreams: List<VideoStream> = emptyList(),
        val audioStreams: List<AudioStream> = emptyList(),
        val segments: List<VideoChapter> = emptyList(),
        val comments: List<Comment> = emptyList(),
        val relatedVideos: List<VideoInfo> = emptyList(),
        val sponsorSegments: List<SponsorSegment> = emptyList(),
        val error: Boolean = false,
    )

    private val _videoState = MutableStateFlow(VideoState())
    val videoState: StateFlow<VideoState> = _videoState.asStateFlow()
    private var videoJob: Job? = null
    private var sponsorJob: Job? = null

    fun loadVideo(videoID: Long, downloadedVideo: DownloadedVideo?) {
        videoJob?.cancel()
        sponsorJob?.cancel()
        _videoState.value = VideoState()

        // Sponsor segments load in parallel.
        sponsorJob = viewModelScope.launch(Dispatchers.IO) {
            val segs = getSponsorSegments(videoID)
            _videoState.update { it.copy(sponsorSegments = segs) }
        }

        fetchDeArrowForVideos(listOf(videoID))

        videoJob = viewModelScope.launch {
            val url = videoIDtoURL(videoID)
            val youtubeService = ServiceList.YouTube
            try {
                withContext(Dispatchers.IO) {
                    val ex = youtubeService.getStreamExtractor(url)
                    ex.fetchPage()

                    val videoStreams: List<VideoStream>
                    val audioStreams: List<AudioStream>
                    val segments: List<VideoChapter>

                    if (downloadedVideo == null) {
                        segments = ex.streamSegments.map {
                            VideoChapter(it.startTimeSeconds * 1000, it.title, it.previewUrl)
                        }
                        val rawVideoOnly = ex.videoOnlyStreams
                        val rawAudio = ex.audioStreams

                        if (rawVideoOnly.isNotEmpty() && rawAudio.isNotEmpty()) {
                            videoStreams = rawVideoOnly.map { it.toDomain() }.sortedWith(
                                compareByDescending<VideoStream> { it.height }
                                    .thenByDescending { codecPriority(it.codec) }
                            )
                            audioStreams = rawAudio.map { it.toDomain() }.sortedWith(
                                compareByDescending<AudioStream> { it.bitrate }
                                    .thenByDescending { audioCodecPriority(it.codec) }
                            )
                        } else {
                            videoStreams = ex.videoStreams.map { it.toDomain() }
                                .sortedWith(compareByDescending { it.height })
                            audioStreams = emptyList()
                        }
                    } else {
                        val (vs, as_) = downloadedStreams(downloadedVideo)
                        videoStreams = vs
                        audioStreams = as_
                        segments = emptyList()
                    }

                    val data = VideoData(
                        ex.name.decodeHtml(),
                        ex.viewCount,
                        ex.length,
                        ex.uploadDate!!.instant.toKotlinInstant(),
                        ex.thumbnails.first().url,
                        ex.uploaderName.decodeHtml(),
                        channelURLtoID(ex.uploaderUrl),
                        ex.uploaderAvatars.first().url,
                        ex.description.content.fromHTML()
                    )
                    val related = ex.relatedItems?.items?.filterIsInstance<StreamInfoItem>()
                        ?.mapNotNull { it.toVideoInfo() } ?: emptyList()

                    _videoState.update {
                        it.copy(
                            data = data,
                            videoStreams = videoStreams,
                            audioStreams = audioStreams,
                            segments = segments,
                            relatedVideos = related,
                        )
                    }

                    fetchDeArrowForVideos(related.map { it.videoID })

                    if (related.isNotEmpty()) {
                        cachedRelatedVideoDao.upsertAll(related.map {
                            CachedRelatedVideo(
                                sourceVideoID = videoID,
                                videoItem = it,
                                cachedAt = Clock.System.now()
                            )
                        })
                    }
                }
                withContext(Dispatchers.IO) {
                    val cex = youtubeService.getCommentsExtractor(url)
                    cex.fetchPage()
                    val comments = cex.initialPage.items.map { c ->
                        val content = if (c.commentText.type == Description.HTML) {
                            c.commentText.content.fromHTML()
                        } else {
                            c.commentText.content.decodeHtml()
                        }
                        Comment(content, c.uploaderName.decodeHtml(), c.likeCount, 0)
                    }
                    _videoState.update { it.copy(comments = comments) }
                }
            } catch (e: Exception) {
                if (downloadedVideo != null) {
                    val video = downloadedVideo
                    val data = VideoData(
                        video.videoItem.name,
                        video.videoItem.views,
                        video.videoItem.duration,
                        video.videoItem.uploadDate,
                        video.videoItem.thumbnailURL,
                        video.videoItem.author,
                        "",
                        "",
                        ""
                    )
                    val (vs, as_) = downloadedStreams(video)
                    _videoState.update { it.copy(data = data, videoStreams = vs, audioStreams = as_) }
                } else {
                    _videoState.update { it.copy(error = true) }
                    Log.e(TAG, "Video load error", e)
                }
            }
        }
    }

    /**
     * When a download completes while the user is on the video page, swap the
     * playable streams over to the on-disk copy (mirrors the original
     * `LaunchedEffect(downloadedVideo)` in VideoPage).
     */
    fun applyDownloadedStreams(downloadedVideo: DownloadedVideo) {
        val (vs, as_) = downloadedStreams(downloadedVideo)
        _videoState.update { it.copy(videoStreams = vs, audioStreams = as_, segments = emptyList()) }
    }

    fun clearVideoError() {
        _videoState.update { it.copy(error = false) }
    }

    private fun downloadedStreams(dv: DownloadedVideo): Pair<List<VideoStream>, List<AudioStream>> {
        val video = listOf(VideoStream(dv.filePath, 1920, 1080, 0, 30, "Downloaded", "avc", 0L))
        val audio = if (dv.audioPath != null) listOf(AudioStream(dv.audioPath, 0, "Default", "aac", 0L)) else emptyList()
        return video to audio
    }

    // ===================== Subscription fetch progress (WorkManager) =====================

    /**
     * Mirrors the currently-running "subscription_fetch_immediate" WorkInfo's
     * progress as a float in [0f, 1f], or -1f if no fetch is running.
     */
    val fetchProgress: StateFlow<Float> = WorkManager.getInstance(application)
        .getWorkInfosForUniqueWorkFlow("subscription_fetch_immediate")
        .map { infos ->
            infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                ?.progress?.getFloat("progress", -1f) ?: -1f
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1f)

    // ===================== DeArrow cache =====================

    data class DeArrowData(val title: String?, val thumbnailUrl: String?)

    private val _deArrowCache = MutableStateFlow<Map<Long, DeArrowData>>(emptyMap())
    val deArrowCache: StateFlow<Map<Long, DeArrowData>> = _deArrowCache.asStateFlow()

    private val deArrowSemaphore = Semaphore(5)

    fun fetchDeArrowForVideos(videoIds: List<Long>) {
        if (!_deArrowEnabled.value) return
        val idsToFetch = videoIds.filter { it !in _deArrowCache.value }
        if (idsToFetch.isEmpty()) return
        idsToFetch.forEach { id ->
            viewModelScope.launch(Dispatchers.IO) {
                deArrowSemaphore.withPermit {
                    val branding = getDeArrowBranding(id)
                    val data = DeArrowData(
                        branding?.trustedTitle(),
                        branding?.trustedThumbnailUrl(id)
                    )
                    _deArrowCache.update { it + (id to data) }
                }
            }
        }
    }

    // ===================== Settings: imports/exports =====================

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importProgress = MutableStateFlow(0f)
    val importProgress: StateFlow<Float> = _importProgress.asStateFlow()

    val sponsorBlockEnabled: StateFlow<Boolean> = DataStoreUtils
        .getInstance(application)
        .stringSetFlow("sponsorblock_categories")
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _deArrowEnabled: StateFlow<Boolean> = DataStoreUtils
        .getInstance(application)
        .booleanFlow("dearrow_enabled")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val deArrowEnabled: StateFlow<Boolean> = _deArrowEnabled

    private val _sponsorBlockCategories: StateFlow<Set<String>> = DataStoreUtils
        .getInstance(application)
        .stringSetFlow("sponsorblock_categories")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_SPONSOR_CATEGORIES)
    val sponsorBlockCategories: StateFlow<Set<String>> = _sponsorBlockCategories

    fun setDeArrowEnabled(enabled: Boolean) {
        viewModelScope.launch {
            DataStoreUtils.getInstance(getApplication()).setBoolean("dearrow_enabled", enabled)
        }
    }

    fun setSponsorBlockCategories(categories: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val ds = DataStoreUtils.getInstance(getApplication())
            // Clear and re-add all categories
            for (cat in ALL_SPONSOR_CATEGORIES) {
                ds.removeStringFromSet("sponsorblock_categories", cat)
            }
            for (cat in categories) {
                ds.addStringToSet("sponsorblock_categories", cat)
            }
        }
    }

    fun toggleSponsorBlockCategory(category: String) {
        val current = _sponsorBlockCategories.value
        val updated = if (category in current) current - category else current + category
        setSponsorBlockCategories(updated)
    }

    fun importYouTubeTakeout(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            _importProgress.value = 0f
            try {
                ZipInputStream(ctx.contentResolver.openInputStream(uri)).use { zipInputStream ->
                var entry = zipInputStream.nextEntry
                val subs = mutableListOf<Subscription>()
                val history = mutableListOf<HistoryVideo>()

                while (entry != null) {
                    when {
                        entry.name.endsWith("subscriptions/subscriptions.csv") -> {
                            val content = zipInputStream.readBytes().decodeToString()
                            val lines = content.lines().drop(1)
                            val total = lines.size
                            lines.forEachIndexed { index, line ->
                                if (line.isNotBlank()) {
                                    val parts = line.split(",")
                                    if (parts.size >= 2) {
                                        val url = parts[1]
                                        try {
                                            val channelInfo = getChannelInfoFromURL(url)
                                            subs.add(channelInfo.toSubscription())
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error fetching channel info for $url", e)
                                        }
                                    }
                                }
                                _importProgress.value = (index + 1).toFloat() / total
                            }
                        }
                        entry.name.endsWith("history/watch-history.json") -> {
                            val jsonString = zipInputStream.readBytes().decodeToString()
                            val jsonArray = Json.parseToJsonElement(jsonString).jsonArray
                            jsonArray.forEach { element ->
                                try {
                                    val title = element.jsonObject["title"]?.jsonPrimitive?.content?.removePrefix("Watched ") ?: ""
                                    val url = element.jsonObject["titleUrl"]?.jsonPrimitive?.content ?: ""
                                    val time = element.jsonObject["time"]?.jsonPrimitive?.content?.let { Instant.parse(it) } ?: Clock.System.now()
                                    val author = element.jsonObject["subtitles"]?.jsonArray?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.content ?: ""

                                    if (url.contains("watch?v=")) {
                                        val videoID = videoURLtoID(url)
                                        history.add(
                                            HistoryVideo(
                                                id = videoID,
                                                progress = 0,
                                                videoItem = VideoInfo(
                                                    title.decodeHtml(),
                                                    videoID,
                                                    0,
                                                    0,
                                                    time,
                                                    "",
                                                    author.decodeHtml()
                                                ),
                                                timestamp = time
                                            )
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing history item", e)
                                }
                            }
                        }
                        entry.name.endsWith("history/watch-history.html") -> {
                            val html = zipInputStream.readBytes().decodeToString()
                            val regex = Regex(
                                "<div class=\"content-cell mdl-cell mdl-cell--6-col mdl-typography--body-1\">Watched&nbsp;<a href=\"(.*?)\">(.*?)</a><br><a href=\"(.*?)\">(.*?)</a><br>(.*?)</div>",
                                RegexOption.DOT_MATCHES_ALL
                            )
                            val matches = regex.findAll(html)
                            matches.forEach { match ->
                                try {
                                    val url = match.groupValues[1]
                                    val title = match.groupValues[2]
                                    val author = match.groupValues[4]

                                    if (url.contains("watch?v=")) {
                                        val videoID = videoURLtoID(url)
                                        history.add(
                                            HistoryVideo(
                                                id = videoID,
                                                progress = 0,
                                                videoItem = VideoInfo(
                                                    title.decodeHtml(),
                                                    videoID,
                                                    0,
                                                    0,
                                                    Clock.System.now(),
                                                    "",
                                                    author.decodeHtml()
                                                ),
                                                timestamp = Clock.System.now()
                                            )
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing HTML history item", e)
                                }
                            }
                        }
                    }
                    entry = zipInputStream.nextEntry
                }

                if (subs.isNotEmpty()) subscriptionDao.upsertAll(subs)
                if (history.isNotEmpty()) historyVideoDao.upsertAll(history)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error importing YouTube Takeout", e)
            }
            _isImporting.value = false
            setupHourlyTask(ctx)
        }
    }

    fun exportSubscriptions(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val subs = subscriptionDao.getAll()
                val json = Json.encodeToString(subs)
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            } catch (e: Exception) {
                Log.e(TAG, "Error exporting subscriptions", e)
            }
        }
    }

    fun restoreSubscriptions(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            try {
                val json = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
                val subs = Json.decodeFromString<List<Subscription>>(json)
                subscriptionDao.clearAll()
                subscriptionDao.upsertAll(subs)
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring subscriptions", e)
            }
            _isImporting.value = false
            setupHourlyTask(ctx)
        }
    }

    fun importNewPipe(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            _importProgress.value = 0f
            try {
                val jsonString = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
                val json = Json.parseToJsonElement(jsonString).jsonObject
                val subsArray = json["subscriptions"]?.jsonArray
                if (subsArray != null) {
                    val total = subsArray.size
                    val subs = mutableListOf<Subscription>()
                    subsArray.forEachIndexed { index, element ->
                        try {
                            var url = element.jsonObject["url"]?.jsonPrimitive?.content ?: ""
                            if (!url.startsWith("http")) url = "https://$url"
                            val channelInfo = getChannelInfoFromURL(url)
                            subs.add(channelInfo.toSubscription())
                        } catch (e: Exception) {
                            Log.e(TAG, "Error importing channel", e)
                        }
                        _importProgress.value = (index + 1).toFloat() / total
                    }
                    subscriptionDao.clearAll()
                    subscriptionDao.upsertAll(subs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error importing NewPipe subscriptions", e)
            }
            _isImporting.value = false
            setupHourlyTask(ctx)
        }
    }

    // ===================== Hourly fetch task =====================

    init {
        setupHourlyTask(application)
        viewModelScope.launch(Dispatchers.IO) {
            val cutoff = Clock.System.now() - 30.days
            cachedRelatedVideoDao.deleteOlderThan(cutoff)
        }
    }

    companion object {
        private const val TAG = "YouPipeViewModel"

        private const val MAX_TOP_CHANNELS = 5
        private const val TOP_CHANNEL_VIDEOS = 5
        private const val MAX_SEARCH_KEYWORDS = 2

        val ALL_SPONSOR_CATEGORIES = setOf(
            "sponsor", "selfpromo", "interaction", "intro", "outro",
            "preview", "music_offtopic", "filler"
        )
        val DEFAULT_SPONSOR_CATEGORIES = ALL_SPONSOR_CATEGORIES

        val SPONSOR_CATEGORY_LABELS = mapOf(
            "sponsor" to "Block Sponsors",
            "selfpromo" to "Block Self Promotion",
            "interaction" to "Block Interaction Reminders",
            "intro" to "Block Intros",
            "outro" to "Block Outros",
            "preview" to "Block Previews",
            "music_offtopic" to "Block Non-Music",
            "filler" to "Block Filler",
        )
    }
}

private fun org.schabi.newpipe.extractor.stream.VideoStream.toDomain() = VideoStream(
    content, width, height, bitrate, fps, "${height}p",
    getVideoCodecName(codec ?: ""), itagItem?.contentLength ?: 0L
)

private fun org.schabi.newpipe.extractor.stream.AudioStream.toDomain() = AudioStream(
    content, bitrate, audioLocale?.language ?: "Default",
    getAudioCodecName(codec ?: ""), itagItem?.contentLength ?: 0L
)

private fun codecPriority(codec: String): Int = when (codec) {
    "av1" -> 3; "vp9" -> 2; "avc" -> 1; else -> 0
}

private fun audioCodecPriority(codec: String): Int = when (codec) {
    "opus" -> 2; "aac" -> 1; else -> 0
}

/** Factory for constructing [YouPipeViewModel] with the DAOs. */
class YouPipeViewModelFactory(
    private val application: Application,
    private val subscriptionDao: SubscriptionDao,
    private val subscriptionCategoryDao: SubscriptionCategoryDao,
    private val subscriptionVideoDao: SubscriptionVideoDao,
    private val historyVideoDao: HistoryVideoDao,
    private val downloadedVideoDao: DownloadedVideoDao,
    private val cachedRelatedVideoDao: CachedRelatedVideoDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(YouPipeViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return YouPipeViewModel(
            application,
            subscriptionDao,
            subscriptionCategoryDao,
            subscriptionVideoDao,
            historyVideoDao,
            downloadedVideoDao,
            cachedRelatedVideoDao,
        ) as T
    }
}
