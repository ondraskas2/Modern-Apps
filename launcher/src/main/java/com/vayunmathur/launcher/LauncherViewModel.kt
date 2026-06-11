package com.vayunmathur.launcher

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.launcher.search.GroupedResults
import com.vayunmathur.launcher.search.SearchManager
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable
)

@OptIn(FlowPreview::class)
class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val searchManager = SearchManager(application)

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchResults = MutableStateFlow(GroupedResults())
    val searchResults: StateFlow<GroupedResults> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    init {
        viewModelScope.launch {
            searchManager.initialize()
            loadApps()
            indexData()
        }

        viewModelScope.launch {
            _query.debounce(200).collect { q ->
                if (q.isNotBlank()) {
                    _searchResults.value = searchManager.search(q)
                } else {
                    _searchResults.value = GroupedResults()
                }
            }
        }
    }

    private fun loadApps() {
        val pm = getApplication<Application>().packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos: List<ResolveInfo> = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        _apps.value = resolveInfos
            .mapNotNull { info ->
                val label = info.loadLabel(pm)?.toString() ?: return@mapNotNull null
                AppInfo(
                    name = label,
                    packageName = info.activityInfo.packageName,
                    icon = info.loadIcon(pm)
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    private suspend fun indexData() {
        searchManager.indexApps()
        searchManager.indexContacts()
        searchManager.indexCalendarEvents()
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    fun setSearching(active: Boolean) {
        _isSearching.value = active
        if (!active) {
            _query.value = ""
            _searchResults.value = GroupedResults()
        }
    }

    override fun onCleared() {
        super.onCleared()
        searchManager.close()
    }
}
