package com.mediacenter.app.ui.text

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mediacenter.app.MediaCenterApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TextViewerUiState(
    val title: String = "",
    val content: String = "",
    val truncated: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
)

class TextViewerViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repository = (application as MediaCenterApp).repository
    private val uri = savedStateHandle.get<String>(EXTRA_URI)?.let(Uri::parse)
    private val title = savedStateHandle.get<String>(EXTRA_TITLE).orEmpty()

    private val _uiState = MutableStateFlow(TextViewerUiState(title = title))
    val uiState: StateFlow<TextViewerUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        val target = uri
        if (target == null) {
            _uiState.update { it.copy(loading = false, error = "无效文件") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            val result = repository.readText(target)
            _uiState.update {
                it.copy(
                    content = result.content,
                    truncated = result.truncated,
                    loading = false,
                    error = result.error,
                )
            }
        }
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
    }
}
