package com.vayunmathur.email

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailSyncWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class EmailViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = EmailDatabase.getInstance(application).emailDao()
    
    val folders = dao.getFoldersFlow()
    
    private val _selectedFolder = MutableStateFlow("INBOX")
    val selectedFolder: StateFlow<String> = _selectedFolder

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val messages: Flow<List<EmailMessage>> = _selectedFolder.flatMapLatest { folder ->
        _searchQuery.flatMapLatest { query ->
            if (query.isEmpty()) {
                dao.getMessagesFlow(folder)
            } else {
                dao.searchMessagesFlow(folder, query)
            }
        }
    }

    fun selectFolder(folderName: String) {
        _selectedFolder.value = folderName
        _searchQuery.value = ""
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun refresh(context: android.content.Context) {
        EmailSyncWorker.runOneOffSync(context)
    }

    suspend fun getMessage(folderName: String, uid: Long): EmailMessage? {
        return dao.getMessage(folderName, uid)
    }
}
