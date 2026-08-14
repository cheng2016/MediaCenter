package com.mediacenter.app.ui.video

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import com.mediacenter.app.MediaCenterApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VideoPlayerViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val progressStore = (application as MediaCenterApp).progressStore

    val uri: Uri? = savedStateHandle.get<String>(EXTRA_URI)?.let(Uri::parse)
    val title: String = savedStateHandle.get<String>(EXTRA_TITLE).orEmpty()
    val filePath: String? = savedStateHandle.get<String>(EXTRA_FILE_PATH)
    val progressKey: String = progressStore.key(uri, filePath)

    private val _position = MutableStateFlow(progressStore.videoPositionMs(progressKey))
    val position: StateFlow<Long> = _position.asStateFlow()

    fun savePosition(positionMs: Long, durationMs: Long = 0L) {
        val value = positionMs.coerceAtLeast(0L)
        _position.value = value
        progressStore.saveVideo(progressKey, value, durationMs)
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FILE_PATH = "file_path"
    }
}
