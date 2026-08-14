package com.mediacenter.app.data

import android.content.Context
import android.net.Uri

class ReadingProgressStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun key(uri: Uri?, filePath: String?): String {
        val uriKey = uri?.toString().orEmpty()
        if (uriKey.isNotBlank() && uri != Uri.EMPTY) return uriKey
        return filePath.orEmpty()
    }

    fun videoPositionMs(key: String): Long {
        if (key.isBlank()) return 0L
        return prefs.getLong(videoKey(key), 0L).coerceAtLeast(0L)
    }

    fun saveVideo(key: String, positionMs: Long, durationMs: Long) {
        if (key.isBlank()) return
        val nearEnd = durationMs > 0L && positionMs >= durationMs - 3_000L
        val value = if (nearEnd || positionMs < 1_000L) 0L else positionMs
        prefs.edit().putLong(videoKey(key), value).apply()
    }

    fun pdfPage(key: String): Int {
        if (key.isBlank()) return 0
        return prefs.getInt(pdfKey(key), 0).coerceAtLeast(0)
    }

    fun savePdfPage(key: String, page: Int) {
        if (key.isBlank()) return
        prefs.edit().putInt(pdfKey(key), page.coerceAtLeast(0)).apply()
    }

    fun bookProgress(key: String): BookProgress {
        if (key.isBlank()) return BookProgress()
        return BookProgress(
            spineIndex = prefs.getInt(bookSpineKey(key), 0).coerceAtLeast(0),
            scrollY = prefs.getInt(bookScrollKey(key), 0).coerceAtLeast(0),
        )
    }

    fun saveBook(key: String, spineIndex: Int, scrollY: Int) {
        if (key.isBlank()) return
        prefs.edit()
            .putInt(bookSpineKey(key), spineIndex.coerceAtLeast(0))
            .putInt(bookScrollKey(key), scrollY.coerceAtLeast(0))
            .apply()
    }

    private fun videoKey(key: String) = "video:$key"
    private fun pdfKey(key: String) = "pdf:$key"
    private fun bookSpineKey(key: String) = "book-spine:$key"
    private fun bookScrollKey(key: String) = "book-scroll:$key"

    data class BookProgress(
        val spineIndex: Int = 0,
        val scrollY: Int = 0,
    )

    companion object {
        private const val PREFS = "reading_progress"
    }
}
