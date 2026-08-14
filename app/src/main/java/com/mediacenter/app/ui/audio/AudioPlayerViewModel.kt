package com.mediacenter.app.ui.audio

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import com.mediacenter.app.MediaCenterApp
import com.mediacenter.app.data.model.MediaItem

class AudioPlayerViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val progressStore = (application as MediaCenterApp).progressStore
    private val repository = (application as MediaCenterApp).repository

    val playlist: List<MediaItem> = repository.lastOpenedAudio.ifEmpty {
        val uri = savedStateHandle.get<String>(EXTRA_URI)?.let(Uri::parse)
        val title = savedStateHandle.get<String>(EXTRA_TITLE).orEmpty()
        val path = savedStateHandle.get<String>(EXTRA_FILE_PATH)
        if (uri == null) emptyList()
        else listOf(
            MediaItem(
                id = uri.toString(),
                uri = uri,
                name = title,
                mimeType = null,
                size = 0L,
                dateModified = 0L,
                type = com.mediacenter.app.data.model.MediaType.AUDIO,
                filePath = path,
            ),
        )
    }

    var index: Int = playlist.indexOfFirst {
        it.uri.toString() == savedStateHandle.get<String>(EXTRA_URI)
    }.coerceAtLeast(0)
        private set

    val current: MediaItem? get() = playlist.getOrNull(index)

    fun progressKey(item: MediaItem? = current): String {
        val target = item ?: return ""
        return progressStore.key(target.uri, target.filePath)
    }

    fun resumePositionMs(item: MediaItem? = current): Long {
        val target = item ?: return 0L
        return progressStore.videoPositionMs(progressKey(target))
    }

    fun savePosition(positionMs: Long, durationMs: Long = 0L) {
        val item = current ?: return
        progressStore.saveVideo(progressKey(item), positionMs.coerceAtLeast(0L), durationMs)
    }

    fun moveBy(delta: Int): MediaItem? {
        if (playlist.isEmpty()) return null
        index = (index + delta).mod(playlist.size)
        return current
    }

    fun setIndex(value: Int) {
        if (playlist.isEmpty()) return
        index = value.coerceIn(0, playlist.lastIndex)
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FILE_PATH = "file_path"
    }
}
