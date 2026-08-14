package com.mediacenter.app.ui.text

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mediacenter.app.MediaCenterApp
import com.mediacenter.app.data.MediaRepository
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
    val canEdit: Boolean = false,
    val editing: Boolean = false,
    val error: String? = null,
    val saveMessage: String? = null,
)

class TextViewerViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repository = (application as MediaCenterApp).repository
    private val uri = savedStateHandle.get<String>(EXTRA_URI)?.let(Uri::parse)
    private val title = savedStateHandle.get<String>(EXTRA_TITLE).orEmpty()
    private val filePath = savedStateHandle.get<String>(EXTRA_FILE_PATH)
    private var lastSaved: String? = null

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
            val result = repository.readText(target, filePath)
            lastSaved = result.content
            val canEdit = MediaRepository.isPlainTxt(title) && result.error == null && !result.truncated
            _uiState.update {
                it.copy(
                    content = result.content,
                    truncated = result.truncated,
                    canEdit = canEdit,
                    editing = false,
                    loading = false,
                    error = result.error,
                )
            }
        }
    }

    fun startEditing() {
        if (_uiState.value.canEdit) {
            _uiState.update { it.copy(editing = true) }
        }
    }

    fun finishEditing() {
        _uiState.update { it.copy(editing = false) }
    }

    fun save(content: String, notify: Boolean) {
        val target = uri ?: return
        val state = _uiState.value
        if (!state.editing || content == lastSaved) {
            if (notify && content == lastSaved) {
                _uiState.update { it.copy(saveMessage = getApplication<Application>().getString(com.mediacenter.app.R.string.text_saved)) }
            }
            return
        }
        viewModelScope.launch {
            repository.writeText(target, filePath, content)
                .onSuccess {
                    lastSaved = content
                    if (notify) {
                        _uiState.update { it.copy(saveMessage = getApplication<Application>().getString(com.mediacenter.app.R.string.text_saved)) }
                    }
                }
                .onFailure { error ->
                    if (notify) {
                        _uiState.update {
                            it.copy(
                                saveMessage = error.message
                                    ?: getApplication<Application>().getString(com.mediacenter.app.R.string.text_save_failed),
                            )
                        }
                    }
                }
        }
    }

    fun consumeSaveMessage() {
        _uiState.update { it.copy(saveMessage = null) }
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FILE_PATH = "file_path"
    }
}
