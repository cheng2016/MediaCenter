package com.mediacenter.app.ui.pdf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import com.mediacenter.app.MediaCenterApp
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PdfViewerViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val progressStore = (application as MediaCenterApp).progressStore
    private val repository = (application as MediaCenterApp).repository

    val uri: Uri? = savedStateHandle.get<String>(EXTRA_URI)?.let(Uri::parse)
    val title: String = savedStateHandle.get<String>(EXTRA_TITLE).orEmpty()
    val filePath: String? = savedStateHandle.get<String>(EXTRA_FILE_PATH)
    val progressKey: String = progressStore.key(uri, filePath)

    @Volatile
    var pageCount: Int = 0
        private set

    var currentPage: Int = progressStore.pdfPage(progressKey)
        private set

    private val mutex = Mutex()
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    suspend fun open(): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeLocked()
            val target = uri ?: return@withLock "无效文件"
            val pfd = runCatching {
                getApplication<Application>().contentResolver.openFileDescriptor(target, "r")
                    ?: materialize(target)?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
            }.getOrNull()
            if (pfd == null) return@withLock "无法打开这份 PDF"
            descriptor = pfd
            renderer = PdfRenderer(pfd)
            pageCount = renderer?.pageCount ?: 0
            currentPage = currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            if (pageCount <= 0) "这份 PDF 没有可显示的页面" else null
        }
    }

    suspend fun render(pageIndex: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pdf = renderer ?: return@withLock null
            if (pageIndex !in 0 until pdf.pageCount) return@withLock null
            pdf.openPage(pageIndex).use { page ->
                val w = width.coerceAtLeast(720)
                val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }

    fun savePage(page: Int) {
        currentPage = page.coerceAtLeast(0)
        progressStore.savePdfPage(progressKey, currentPage)
    }

    override fun onCleared() {
        runCatching {
            renderer?.close()
            descriptor?.close()
        }
        renderer = null
        descriptor = null
        super.onCleared()
    }

    private fun closeLocked() {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        renderer = null
        descriptor = null
    }

    private fun materialize(uri: Uri): File? {
        val path = repository.resolveLocalPath(uri, filePath)
        if (path != null) return File(path)
        val dest = File(getApplication<Application>().cacheDir, "pdf-${uri.hashCode()}.pdf")
        return runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            dest.takeIf { it.isFile && it.length() > 0L }
        }.getOrNull()
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FILE_PATH = "file_path"
    }
}
