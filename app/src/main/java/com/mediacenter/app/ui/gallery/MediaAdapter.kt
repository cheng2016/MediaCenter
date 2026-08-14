package com.mediacenter.app.ui.gallery

import android.content.Context
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.mediacenter.app.R
import com.mediacenter.app.data.MediaRepository
import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.data.model.MediaType
import com.mediacenter.app.databinding.ItemFileRowBinding
import com.mediacenter.app.databinding.ItemFolderBinding
import com.mediacenter.app.databinding.ItemFolderRowBinding
import com.mediacenter.app.databinding.ItemMediaBinding
import com.mediacenter.app.databinding.ItemSectionHeaderBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MediaAdapter(
    private val onClick: (MediaItem) -> Unit,
    private val onFocusSidebar: () -> Unit = {},
    private val onItemMenu: (android.view.View, MediaItem) -> Unit = { _, _ -> },
    private val onFocusToolbar: () -> Unit = {},
) : ListAdapter<GalleryRow, RecyclerView.ViewHolder>(Diff) {

    var listMode: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    var albumCards: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    private var sourceItems: List<MediaItem> = emptyList()
    private var groupByDate: Boolean = false
    private val collapsedKeys = linkedSetOf<String>()

    fun submitMedia(items: List<MediaItem>, groupByDate: Boolean = false) {
        sourceItems = items
        this.groupByDate = groupByDate
        if (!groupByDate) collapsedKeys.clear()
        submitList(buildRows())
    }

    fun toggleSection(key: String) {
        if (!collapsedKeys.add(key)) collapsedKeys.remove(key)
        submitList(buildRows())
    }

    private fun buildRows(): List<GalleryRow> {
        return if (groupByDate) {
            DateSections.rows(sourceItems, collapsedKeys)
        } else {
            sourceItems.map { GalleryRow.Media(it) }
        }
    }

    fun isHeader(position: Int): Boolean {
        return position in 0 until itemCount && getItem(position) is GalleryRow.Header
    }

    fun isFullSpan(position: Int): Boolean = isHeader(position)

    fun indexOfItem(id: String): Int {
        return currentList.indexOfFirst { row -> row is GalleryRow.Media && row.item.id == id }
    }

    fun firstFocusablePosition(): Int {
        if (itemCount == 0) return 0
        return 0
    }

    fun headerPositionOf(position: Int): Int {
        for (index in position downTo 0) {
            if (isHeader(index)) return index
        }
        return -1
    }

    fun nextHeaderAfter(position: Int): Int {
        for (index in (position + 1) until itemCount) {
            if (isHeader(index)) return index
        }
        return -1
    }

    fun sameSection(first: Int, second: Int): Boolean {
        return headerPositionOf(first) == headerPositionOf(second)
    }

    fun nextFocusable(from: Int): Int {
        val next = from + 1
        return if (next in 0 until itemCount) next else -1
    }

    fun previousFocusable(from: Int): Int {
        val previous = from - 1
        return if (previous in 0 until itemCount) previous else -1
    }

    override fun getItemViewType(position: Int): Int {
        return when (val row = getItem(position)) {
            is GalleryRow.Header -> VIEW_HEADER
            is GalleryRow.Media -> when {
                row.item.type == MediaType.FOLDER && albumCards -> VIEW_ALBUM
                row.item.type == MediaType.FOLDER -> VIEW_FOLDER_ROW
                listMode ||
                    (row.item.type != MediaType.IMAGE && row.item.type != MediaType.VIDEO) -> VIEW_FILE_ROW
                else -> VIEW_FILE_GRID
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_HEADER -> HeaderHolder(
                ItemSectionHeaderBinding.inflate(inflater, parent, false),
                onFocusSidebar,
                onFocusToolbar,
            )
            VIEW_ALBUM -> AlbumHolder(
                ItemFolderBinding.inflate(inflater, parent, false),
                onClick,
                onFocusSidebar,
                onItemMenu,
                onFocusToolbar,
            )
            VIEW_FOLDER_ROW -> FolderRowHolder(
                ItemFolderRowBinding.inflate(inflater, parent, false),
                onClick,
                onFocusSidebar,
                onItemMenu,
                onFocusToolbar,
            )
            VIEW_FILE_ROW -> FileRowHolder(
                ItemFileRowBinding.inflate(inflater, parent, false),
                onClick,
                onFocusSidebar,
                onItemMenu,
                onFocusToolbar,
            )
            else -> FileGridHolder(
                ItemMediaBinding.inflate(inflater, parent, false),
                onClick,
                onFocusSidebar,
                onItemMenu,
                onFocusToolbar,
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is GalleryRow.Header -> (holder as HeaderHolder).bind(row) { toggleSection(row.id) }
            is GalleryRow.Media -> when (holder) {
                is AlbumHolder -> holder.bind(row.item)
                is FolderRowHolder -> holder.bind(row.item)
                is FileRowHolder -> holder.bind(row.item)
                is FileGridHolder -> holder.bind(row.item)
            }
        }
    }

    class HeaderHolder(
        private val binding: ItemSectionHeaderBinding,
        private val onFocusSidebar: () -> Unit,
        private val onFocusToolbar: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(header: GalleryRow.Header, onToggle: () -> Unit) {
            Dpad.bindItem(binding.root)
            binding.title.text = binding.root.resources.getString(
                R.string.section_header,
                header.title,
                header.count,
            )
            binding.chevron.animate().cancel()
            binding.chevron.rotation = if (header.collapsed) 0f else 90f
            binding.root.setOnClickListener { onToggle() }
            bindRemoteKeys(binding.root, onToggle, onFocusSidebar, onFocusToolbar)
        }
    }

    class AlbumHolder(
        private val binding: ItemFolderBinding,
        private val onClick: (MediaItem) -> Unit,
        private val onFocusSidebar: () -> Unit,
        private val onItemMenu: (android.view.View, MediaItem) -> Unit,
        private val onFocusToolbar: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaItem) {
            Dpad.bindItem(binding.root)
            binding.name.text = item.name
            binding.count.text = binding.root.resources.getString(R.string.folder_count, item.childCount)
            Glide.with(binding.cover)
                .load(item.coverUri ?: item.uri)
                .centerCrop()
                .placeholder(R.drawable.bg_thumb)
                .into(binding.cover)
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onItemMenu(binding.root, item)
                true
            }
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                binding.root.strokeWidth = if (hasFocus) 4 else 1
                binding.root.strokeColor = ContextCompat.getColor(
                    binding.root.context,
                    if (hasFocus) R.color.md_primary else R.color.md_stroke,
                )
            }
            bindRemoteKeys(binding.root, { onClick(item) }, onFocusSidebar, onFocusToolbar) {
                onItemMenu(binding.root, item)
            }
        }
    }

    class FolderRowHolder(
        private val binding: ItemFolderRowBinding,
        private val onClick: (MediaItem) -> Unit,
        private val onFocusSidebar: () -> Unit,
        private val onItemMenu: (android.view.View, MediaItem) -> Unit,
        private val onFocusToolbar: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaItem) {
            Dpad.bindItem(binding.root)
            binding.name.text = item.name
            binding.meta.text = binding.root.resources.getString(
                R.string.folder_meta,
                formatDate(item.dateModified),
                item.childCount,
            )
            binding.root.alpha = if (item.isMissing) 0.45f else 1f
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onItemMenu(binding.root, item)
                true
            }
            bindRemoteKeys(binding.root, { onClick(item) }, onFocusSidebar, onFocusToolbar) {
                onItemMenu(binding.root, item)
            }
        }
    }

    class FileRowHolder(
        private val binding: ItemFileRowBinding,
        private val onClick: (MediaItem) -> Unit,
        private val onFocusSidebar: () -> Unit,
        private val onItemMenu: (android.view.View, MediaItem) -> Unit,
        private val onFocusToolbar: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaItem) {
            Dpad.bindItem(binding.root)
            binding.favorite.isVisible = item.isFavorite && !item.isMissing
            binding.name.text = item.name
            val sizeText = formatSize(binding.root.context, item.size)
            binding.meta.text = when {
                item.isMissing -> binding.root.resources.getString(R.string.file_missing)
                item.type == MediaType.ARCHIVE && !MediaRepository.isZipArchive(item.name) ->
                    binding.root.resources.getString(
                        R.string.file_meta,
                        formatDate(item.dateModified),
                        sizeText,
                        binding.root.resources.getString(R.string.archive_unsupported_label),
                    )
                item.type == MediaType.VIDEO && item.durationMs > 0L ->
                    binding.root.resources.getString(
                        R.string.file_meta,
                        formatDate(item.dateModified),
                        sizeText,
                        formatDuration(item.durationMs),
                    )
                else -> binding.root.resources.getString(
                    R.string.file_meta,
                    formatDate(item.dateModified),
                    sizeText,
                    typeLabel(item.type),
                )
            }
            binding.root.alpha = if (item.isMissing ||
                (item.type == MediaType.ARCHIVE && !MediaRepository.isZipArchive(item.name))
            ) {
                0.45f
            } else {
                1f
            }
            when (item.type) {
                MediaType.IMAGE, MediaType.VIDEO -> {
                    binding.icon.setPadding(0, 0, 0, 0)
                    binding.icon.scaleType = ImageView.ScaleType.CENTER_CROP
                    Glide.with(binding.icon)
                        .load(item.uri)
                        .centerCrop()
                        .placeholder(R.drawable.bg_file_thumb)
                        .into(binding.icon)
                }
                MediaType.APK -> bindApkIcon(binding.icon, item)
                MediaType.AUDIO -> bindTypeIcon(binding.icon, R.drawable.ic_nav_music)
                MediaType.WEB -> bindTypeIcon(binding.icon, R.drawable.ic_nav_web)
                MediaType.TEXT -> bindTypeIcon(binding.icon, R.drawable.ic_nav_text)
                MediaType.PDF, MediaType.BOOK -> bindTypeIcon(binding.icon, R.drawable.ic_nav_book)
                MediaType.ARCHIVE -> bindTypeIcon(binding.icon, R.drawable.ic_nav_archive)
                MediaType.FILE -> bindTypeIcon(binding.icon, R.drawable.ic_nav_file)
                MediaType.FOLDER -> Unit
            }
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onItemMenu(binding.root, item)
                true
            }
            bindRemoteKeys(binding.root, { onClick(item) }, onFocusSidebar, onFocusToolbar) {
                onItemMenu(binding.root, item)
            }
        }
    }

    class FileGridHolder(
        private val binding: ItemMediaBinding,
        private val onClick: (MediaItem) -> Unit,
        private val onFocusSidebar: () -> Unit,
        private val onItemMenu: (android.view.View, MediaItem) -> Unit,
        private val onFocusToolbar: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaItem) {
            Dpad.bindItem(binding.root)
            binding.favorite.isVisible = item.isFavorite && !item.isMissing
            binding.name.isVisible = false
            binding.root.alpha = if (item.isMissing) 0.45f else 1f
            binding.badge.isVisible = item.type == MediaType.VIDEO
            binding.badge.setImageResource(R.drawable.ic_badge_video)
            val showDuration = item.type == MediaType.VIDEO && item.durationMs > 0L
            binding.duration.isVisible = showDuration
            if (showDuration) {
                binding.duration.text = formatDuration(item.durationMs)
            }
            if (item.type == MediaType.APK) {
                bindApkIcon(binding.thumb, item)
            } else {
                binding.thumb.setPadding(0, 0, 0, 0)
                binding.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                Glide.with(binding.thumb)
                    .load(item.uri)
                    .centerCrop()
                    .placeholder(R.drawable.bg_thumb)
                    .error(R.drawable.bg_thumb)
                    .into(binding.thumb)
            }
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onItemMenu(binding.root, item)
                true
            }
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                binding.root.strokeWidth = if (hasFocus) 4 else 0
                binding.root.strokeColor = ContextCompat.getColor(binding.root.context, R.color.md_primary)
            }
            bindRemoteKeys(binding.root, { onClick(item) }, onFocusSidebar, onFocusToolbar) {
                onItemMenu(binding.root, item)
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<GalleryRow>() {
        override fun areItemsTheSame(oldItem: GalleryRow, newItem: GalleryRow): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: GalleryRow, newItem: GalleryRow): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val VIEW_FOLDER_ROW = 1
        private const val VIEW_FILE_ROW = 2
        private const val VIEW_FILE_GRID = 3
        private const val VIEW_ALBUM = 4
        private const val VIEW_HEADER = 5
        private val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.CHINA)

        fun bindRemoteKeys(
            view: android.view.View,
            onActivate: () -> Unit,
            onFocusSidebar: () -> Unit,
            onFocusToolbar: () -> Unit,
            onMenu: (() -> Unit)? = null,
        ) {
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when {
                    Dpad.isActivate(keyCode) -> {
                        onActivate()
                        true
                    }
                    onMenu != null && Dpad.isFavoriteAction(keyCode) -> {
                        onMenu()
                        true
                    }
                    else -> Dpad.handleContentKey(view, keyCode, event, onFocusSidebar, onFocusToolbar)
                }
            }
        }

        fun formatDate(timeMs: Long): String {
            if (timeMs <= 0L) return "--"
            return dateFormat.format(Date(timeMs))
        }

        fun formatSize(context: Context, bytes: Long): String {
            if (bytes <= 0L) return "--"
            return Formatter.formatShortFileSize(context, bytes)
        }

        private fun bindTypeIcon(view: ImageView, iconRes: Int) {
            Glide.with(view).clear(view)
            view.setPadding(0, 0, 0, 0)
            view.scaleType = ImageView.ScaleType.CENTER
            view.setImageResource(iconRes)
        }

        private fun bindApkIcon(view: ImageView, item: MediaItem) {
            val pad = (6 * view.resources.displayMetrics.density).toInt()
            view.setPadding(pad, pad, pad, pad)
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            Glide.with(view)
                .load(ApkIcon(item.filePath, item.uri))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .placeholder(R.drawable.ic_nav_apk)
                .error(R.drawable.ic_nav_apk)
                .into(view)
        }

        fun formatDuration(durationMs: Long): String {
            val total = TimeUnit.MILLISECONDS.toSeconds(durationMs.coerceAtLeast(0L))
            val hours = total / 3600
            val minutes = (total % 3600) / 60
            val seconds = total % 60
            return if (hours > 0) {
                String.format(Locale.CHINA, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.CHINA, "%d:%02d", minutes, seconds)
            }
        }

        fun typeLabel(type: MediaType): String {
            return when (type) {
                MediaType.IMAGE -> "图片"
                MediaType.VIDEO -> "视频"
                MediaType.AUDIO -> "音乐"
                MediaType.WEB -> "网页"
                MediaType.TEXT -> "文本"
                MediaType.PDF -> "PDF"
                MediaType.BOOK -> "电子书"
                MediaType.ARCHIVE -> "压缩包"
                MediaType.APK -> "安装包"
                MediaType.FOLDER -> "文件夹"
                MediaType.FILE -> "文件"
            }
        }
    }
}
