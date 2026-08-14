package com.mediacenter.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class BookAnnotation(
    val id: String,
    val bookKey: String,
    val chapterIndex: Int,
    val quote: String,
    val note: String,
    val createdAt: Long,
)

class AnnotationStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(bookKey: String): List<BookAnnotation> {
        if (bookKey.isBlank()) return emptyList()
        return all().filter { it.bookKey == bookKey }.sortedByDescending { it.createdAt }
    }

    fun add(bookKey: String, chapterIndex: Int, quote: String, note: String): BookAnnotation {
        val item = BookAnnotation(
            id = "${bookKey}-${System.currentTimeMillis()}",
            bookKey = bookKey,
            chapterIndex = chapterIndex,
            quote = quote.trim(),
            note = note.trim(),
            createdAt = System.currentTimeMillis(),
        )
        save(listOf(item) + all().filterNot { it.id == item.id })
        return item
    }

    fun remove(id: String) {
        save(all().filterNot { it.id == id })
    }

    private fun all(): List<BookAnnotation> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                BookAnnotation(
                    id = obj.optString("id"),
                    bookKey = obj.optString("bookKey"),
                    chapterIndex = obj.optInt("chapterIndex"),
                    quote = obj.optString("quote"),
                    note = obj.optString("note"),
                    createdAt = obj.optLong("createdAt"),
                ).takeIf { it.id.isNotBlank() && it.quote.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(items: List<BookAnnotation>) {
        val array = JSONArray()
        items.take(MAX).forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("bookKey", item.bookKey)
                    .put("chapterIndex", item.chapterIndex)
                    .put("quote", item.quote)
                    .put("note", item.note)
                    .put("createdAt", item.createdAt),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "book_annotations"
        private const val KEY = "items"
        private const val MAX = 400
    }
}
