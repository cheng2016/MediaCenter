package com.mediacenter.app.ui.web

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mediacenter.app.MediaCenterApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WebViewerUiState(
    val title: String = "",
    val html: String = "",
    val filePath: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class WebViewerViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repository = (application as MediaCenterApp).repository

    val uri: Uri? = savedStateHandle.get<String>(EXTRA_URI)?.let(Uri::parse)
    val title: String = savedStateHandle.get<String>(EXTRA_TITLE).orEmpty()
    val parentUri: Uri? = savedStateHandle.get<String>(EXTRA_PARENT_URI)?.let(Uri::parse)
    val extraFilePath: String? = savedStateHandle.get<String>(EXTRA_FILE_PATH)

    private val _uiState = MutableStateFlow(WebViewerUiState(title = title))
    val uiState: StateFlow<WebViewerUiState> = _uiState.asStateFlow()

    init {
        val target = uri
        if (target == null && extraFilePath.isNullOrBlank()) {
            _uiState.value = WebViewerUiState(title = title, loading = false, error = "无效文件")
        } else {
            viewModelScope.launch {
                val path = withContext(Dispatchers.IO) {
                    repository.resolveLocalPath(target, extraFilePath)
                }
                if (path != null) {
                    _uiState.value = WebViewerUiState(
                        title = title,
                        filePath = path,
                        loading = false,
                    )
                    return@launch
                }
                val html = withContext(Dispatchers.IO) {
                    if (target != null) repository.readHtml(target, extraFilePath) else ""
                }
                _uiState.value = WebViewerUiState(
                    title = title,
                    html = html,
                    loading = false,
                    error = if (html.isEmpty()) "无法读取网页" else null,
                )
            }
        }
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PARENT_URI = "parent_uri"
        const val EXTRA_FILE_PATH = "file_path"
    }
}
