package com.mediacenter.app.ui.gallery

import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.data.model.MediaType
import java.util.Calendar

object DateSections {

    fun rows(items: List<MediaItem>, collapsedKeys: Set<String> = emptySet()): List<GalleryRow> {
        val folders = items.filter { it.type == MediaType.FOLDER }
        val files = items.filter { it.type != MediaType.FOLDER }
        val rows = ArrayList<GalleryRow>(items.size + 16)
        folders.forEach { rows += GalleryRow.Media(it) }
        val sections = files.groupBy { dayKey(it.dateModified) }
            .toList()
            .sortedByDescending { (_, group) -> group.maxOf { it.dateModified } }
        for ((key, group) in sections) {
            val collapsed = key in collapsedKeys
            rows += GalleryRow.Header(
                id = key,
                title = label(group.first().dateModified),
                count = group.size,
                collapsed = collapsed,
            )
            if (!collapsed) {
                group.forEach { rows += GalleryRow.Media(it) }
            }
        }
        return rows
    }

    fun dayKey(timeMs: Long): String {
        if (timeMs <= 0L) return "unknown"
        val date = Calendar.getInstance().apply { timeInMillis = timeMs }
        return String.format(
            "%04d-%02d-%02d",
            date.get(Calendar.YEAR),
            date.get(Calendar.MONTH) + 1,
            date.get(Calendar.DAY_OF_MONTH),
        )
    }

    fun label(timeMs: Long): String {
        if (timeMs <= 0L) return "更早"
        val now = Calendar.getInstance()
        val date = Calendar.getInstance().apply { timeInMillis = timeMs }
        if (sameDay(now, date)) return "今天"
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (sameDay(yesterday, date)) return "昨天"
        val month = date.get(Calendar.MONTH) + 1
        val day = date.get(Calendar.DAY_OF_MONTH)
        return if (now.get(Calendar.YEAR) == date.get(Calendar.YEAR)) {
            "${month}月${day}日"
        } else {
            "${date.get(Calendar.YEAR)}年${month}月${day}日"
        }
    }

    private fun sameDay(a: Calendar, b: Calendar): Boolean {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }
}

sealed class GalleryRow {
    abstract val id: String

    data class Header(
        override val id: String,
        val title: String,
        val count: Int,
        val collapsed: Boolean = false,
    ) : GalleryRow()

    data class Media(
        val item: MediaItem,
    ) : GalleryRow() {
        override val id: String get() = item.id
    }
}
