package com.mediacenter.app.ui.book

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mediacenter.app.MediaCenterApp
import com.mediacenter.app.data.BookAnnotation
import com.mediacenter.app.data.EpubBook
import com.mediacenter.app.data.EpubLoader
import com.mediacenter.app.data.EpubTocItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BookUiState(
    val title: String = "",
    val chapterTitle: String = "",
    val chapterHtml: String? = null,
    val baseUrl: String? = null,
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val restoreScrollY: Int = 0,
    val useBookCss: Boolean = true,
    val toc: List<EpubTocItem> = emptyList(),
    val annotations: List<BookAnnotation> = emptyList(),
    val quotes: List<String> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class BookViewerViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val progressStore = (application as MediaCenterApp).progressStore
    private val repository = (application as MediaCenterApp).repository
    private val annotationStore = (application as MediaCenterApp).annotationStore

    val uri: Uri? = savedStateHandle.get<String>(EXTRA_URI)?.let(Uri::parse)
    val fallbackTitle: String = savedStateHandle.get<String>(EXTRA_TITLE).orEmpty()
    val filePath: String? = savedStateHandle.get<String>(EXTRA_FILE_PATH)
    val progressKey: String = progressStore.key(uri, filePath)

    private var book: EpubBook? = null
    private var scrollY: Int = progressStore.bookProgress(progressKey).scrollY
    private var useBookCss: Boolean = true

    private val _uiState = MutableStateFlow(BookUiState(title = fallbackTitle))
    val uiState: StateFlow<BookUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        val target = uri
        if (target == null) {
            _uiState.value = BookUiState(title = fallbackTitle, loading = false, error = "无效文件")
            return
        }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                EpubLoader.open(getApplication(), target, filePath, repository::resolveLocalPath)
            }
        }
        val result = loaded.getOrNull()
        if (result == null) {
            _uiState.value = BookUiState(
                title = fallbackTitle,
                loading = false,
                error = loaded.exceptionOrNull()?.message ?: "无法打开这本电子书，请使用 EPUB 格式",
            )
            return
        }
        book = result
        val saved = progressStore.bookProgress(progressKey)
        showChapter(saved.spineIndex, saved.scrollY)
    }

    fun nextChapter() = moveChapter(1)

    fun prevChapter() = moveChapter(-1)

    fun jumpTo(index: Int) {
        val chapters = book?.chapters.orEmpty()
        if (chapters.isEmpty()) return
        showChapter(index.coerceIn(0, chapters.lastIndex), 0)
    }

    fun toggleTheme() {
        useBookCss = !useBookCss
        showChapter(_uiState.value.chapterIndex, bindingScroll())
    }

    fun saveScroll(y: Int) {
        scrollY = y.coerceAtLeast(0)
        progressStore.saveBook(progressKey, _uiState.value.chapterIndex, scrollY)
    }

    fun addAnnotation(quote: String, note: String) {
        val text = quote.trim()
        if (text.isBlank()) return
        annotationStore.add(progressKey, _uiState.value.chapterIndex, text, note)
        showChapter(_uiState.value.chapterIndex, bindingScroll())
    }

    fun removeAnnotation(id: String) {
        annotationStore.remove(id)
        showChapter(_uiState.value.chapterIndex, bindingScroll())
    }

    private fun bindingScroll(): Int = scrollY

    private fun moveChapter(delta: Int) {
        val chapters = book?.chapters ?: return
        val next = (_uiState.value.chapterIndex + delta).coerceIn(0, chapters.lastIndex)
        if (next == _uiState.value.chapterIndex) return
        showChapter(next, 0)
    }

    private fun showChapter(index: Int, restoreScroll: Int) {
        val current = book ?: return
        if (current.chapters.isEmpty()) {
            _uiState.value = BookUiState(
                title = current.title.ifBlank { fallbackTitle },
                loading = false,
                error = "没有可读章节",
            )
            return
        }
        val safeIndex = index.coerceIn(0, current.chapters.lastIndex)
        val chapter = current.chapters[safeIndex]
        scrollY = restoreScroll
        progressStore.saveBook(progressKey, safeIndex, restoreScroll)
        val notes = annotationStore.list(progressKey)
        _uiState.value = BookUiState(
            title = current.title.ifBlank { fallbackTitle },
            chapterTitle = chapter.title,
            chapterHtml = styledHtml(chapter.file),
            baseUrl = chapter.file.parentFile?.toURI()?.toString(),
            chapterIndex = safeIndex,
            chapterCount = current.chapters.size,
            restoreScrollY = restoreScroll,
            useBookCss = useBookCss,
            toc = current.toc,
            annotations = notes,
            quotes = notes.filter { it.chapterIndex == safeIndex }.map { it.quote },
            loading = false,
        )
    }

    private fun styledHtml(file: java.io.File): String {
        val raw = runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(CHAPTER_MAX_BYTES)
                val read = input.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read, Charsets.UTF_8)
            }
        }.getOrDefault("")
        val style = if (useBookCss) {
            "<style>img,svg,video{max-width:100%;height:auto;}body{margin:16px;}mark{background:#FFE082;color:#111;}</style>"
        } else {
            "<style>html,body{background:#111;color:#EDEDED;font-size:18px;line-height:1.7;padding:12px 16px;}img,svg,video{max-width:100%;height:auto;}a{color:#7EB6FF;}mark{background:#FFE082;color:#111;}</style>"
        }
        return if (raw.contains("<head", ignoreCase = true)) {
            raw.replace(Regex("<head([^>]*)>", RegexOption.IGNORE_CASE), "<head$1>$style")
        } else {
            "<html><head>$style</head><body>$raw</body></html>"
        }
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FILE_PATH = "file_path"
        private const val CHAPTER_MAX_BYTES = 2 * 1024 * 1024
    }
}
