package com.mediacenter.app.ui.video

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VideoPlayerViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    val uri: Uri? = savedStateHandle.get<String>(EXTRA_URI)?.let(Uri::parse)
    val title: String = savedStateHandle.get<String>(EXTRA_TITLE).orEmpty()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    fun savePosition(positionMs: Long) {
        _position.value = positionMs
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
    }
}
