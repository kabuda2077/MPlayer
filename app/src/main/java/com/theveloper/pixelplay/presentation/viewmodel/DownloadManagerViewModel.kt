package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.download.DownloadEntity
import com.theveloper.pixelplay.data.download.DownloadRepository
import com.theveloper.pixelplay.data.download.DownloadStats
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DownloadManagerViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository
) : ViewModel() {
    val downloads: StateFlow<List<DownloadEntity>> = downloadRepository.observeDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stats: StateFlow<DownloadStats> = downloadRepository.observeStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DownloadStats(0, 0L))

    fun cancel(entity: DownloadEntity) {
        viewModelScope.launch { downloadRepository.cancel(entity.contentUriString) }
    }

    fun cancelAllActive() {
        viewModelScope.launch { downloadRepository.cancelAllActive() }
    }

    fun retry(entity: DownloadEntity) {
        viewModelScope.launch { downloadRepository.retry(entity) }
    }

    fun retryFailedAndCanceled() {
        viewModelScope.launch { downloadRepository.retryFailedAndCanceled() }
    }

    fun delete(entity: DownloadEntity) {
        viewModelScope.launch { downloadRepository.delete(entity) }
    }

    fun clearCompletedAndFailed() {
        viewModelScope.launch { downloadRepository.clearCompletedAndFailed() }
    }

    fun clearFailedAndCanceled() {
        viewModelScope.launch { downloadRepository.clearFailedAndCanceled() }
    }

    fun deleteCompletedDownloads() {
        viewModelScope.launch { downloadRepository.deleteCompletedDownloads() }
    }

    fun clearAll() {
        viewModelScope.launch { downloadRepository.clearAll() }
    }
}
