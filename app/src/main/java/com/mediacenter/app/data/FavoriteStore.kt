package com.mediacenter.app.data

import android.content.Context
import android.net.Uri
import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.data.model.MediaType
import org.json.JSONArray
import org.json.JSONObject

class FavoriteStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var cached: List<MediaItem>? = null

    fun remove(item: MediaItem) {
        save(list().filterNot { same(it, item) })
    }

    fun toggle(item: MediaItem): Boolean {
        if (item.type == MediaType.FOLDER) return false
        val current = list()
        return if (current.any { same(it, item) }) {
            save(current.filterNot { same(it, item) })
            false
        } else {
            val next = ArrayList<MediaItem>(current.size + 1)
            next += item.copy(dateModified = System.currentTimeMillis())
            next += current
            save(next.take(MAX))
            true
        }
    }

    fun contains(item: MediaItem): Boolean {
        if (item.type == MediaType.FOLDER) return false
        return list().any { same(it, item) }
    }

    fun list(): List<MediaItem> {
        cached?.let { return it }
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val uri = obj.optString("uri").takeIf { it.isNotBlank() }?.let(Uri::parse)
                    ?: return@mapNotNull null
                val type = runCatching { MediaType.valueOf(obj.optString("type")) }
                    .getOrDefault(MediaType.FILE)
                MediaItem(
                    id = obj.optString("id").ifBlank { "favorite-$uri" },
                    uri = uri,
                    name = obj.optString("name").ifBlank { uri.lastPathSegment ?: "文件" },
                    mimeType = obj.optString("mime").takeIf { it.isNotBlank() },
                    size = obj.optLong("size"),
                    dateModified = obj.optLong("savedAt"),
                    type = type,
                    filePath = obj.optString("filePath").takeIf { it.isNotBlank() },
                    isFavorite = true,
                )
            }
        }.getOrDefault(emptyList()).also { cached = it }
    }

    private fun save(items: List<MediaItem>) {
        cached = items
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("uri", item.uri.toString())
                    .put("name", item.name)
                    .put("mime", item.mimeType.orEmpty())
                    .put("size", item.size)
                    .put("type", item.type.name)
                    .put("filePath", item.filePath.orEmpty())
                    .put("savedAt", item.dateModified),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun same(left: MediaItem, right: MediaItem): Boolean {
        if (left.uri == right.uri) return true
        val path = left.filePath
        return !path.isNullOrBlank() && path == right.filePath
    }

    companion object {
        private const val PREFS = "favorite_files"
        private const val KEY = "items"
        private const val MAX = 200
    }
}
