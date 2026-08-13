package com.mediacenter.app.ui.image

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import com.mediacenter.app.MediaCenterApp
import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.data.model.MediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ImageViewerViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repository = (application as MediaCenterApp).repository

    val images: List<MediaItem>
    val startIndex: Int

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    init {
        val uri = savedStateHandle.get<String>(EXTRA_URI)?.let(Uri::parse)
        val name = savedStateHandle.get<String>(EXTRA_TITLE).orEmpty()
        val opened = repository.lastOpenedImages
        images = if (opened.isNotEmpty()) {
            opened
        } else if (uri != null) {
            listOf(
                MediaItem(
                    id = uri.toString(),
                    uri = uri,
                    name = name.ifEmpty { uri.lastPathSegment.orEmpty() },
                    mimeType = "image/*",
                    size = 0L,
                    dateModified = 0L,
                    type = MediaType.IMAGE,
                ),
            )
        } else {
            emptyList()
        }
        startIndex = images.indexOfFirst { it.uri == uri }.coerceAtLeast(0)
        _title.value = images.getOrNull(startIndex)?.name.orEmpty()
    }

    fun onPageChanged(index: Int) {
        _title.value = images.getOrNull(index)?.name.orEmpty()
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
    }
}
