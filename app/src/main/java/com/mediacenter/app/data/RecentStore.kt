package com.mediacenter.app.data

import android.content.Context
import android.net.Uri
import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.data.model.MediaType
import org.json.JSONArray
import org.json.JSONObject

class RecentStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun remove(item: MediaItem) {
        save(list().filterNot { it.uri == item.uri || samePath(it, item) })
    }

    fun add(item: MediaItem) {
        if (item.type == MediaType.FOLDER) return
        val next = list().filterNot { it.uri == item.uri }.toMutableList()
        next.add(
            0,
            item.copy(dateModified = System.currentTimeMillis()),
        )
        save(next.take(MAX))
    }

    fun list(): List<MediaItem> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val uri = obj.optString("uri").takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return@mapNotNull null
                val type = runCatching { MediaType.valueOf(obj.optString("type")) }.getOrDefault(MediaType.FILE)
                MediaItem(
                    id = obj.optString("id").ifBlank { "recent-$uri" },
                    uri = uri,
                    name = obj.optString("name").ifBlank { uri.lastPathSegment ?: "文件" },
                    mimeType = obj.optString("mime").takeIf { it.isNotBlank() },
                    size = obj.optLong("size"),
                    dateModified = obj.optLong("openedAt"),
                    type = type,
                    filePath = obj.optString("filePath").takeIf { it.isNotBlank() },
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun samePath(left: MediaItem, right: MediaItem): Boolean {
        val path = left.filePath
        return !path.isNullOrBlank() && path == right.filePath
    }

    private fun save(items: List<MediaItem>) {
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
                    .put("openedAt", item.dateModified),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "recent_files"
        private const val KEY = "items"
        private const val MAX = 80
    }
}
