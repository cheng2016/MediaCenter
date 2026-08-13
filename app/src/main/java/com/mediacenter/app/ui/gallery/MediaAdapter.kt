package com.mediacenter.app.ui.gallery

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.mediacenter.app.R
import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.data.model.MediaType
import com.mediacenter.app.databinding.ItemFileRowBinding
import com.mediacenter.app.databinding.ItemFolderRowBinding
import com.mediacenter.app.databinding.ItemMediaBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaAdapter(
    private val onClick: (MediaItem) -> Unit,
    private val onFocusSidebar: () -> Unit = {},
) : ListAdapter<MediaItem, RecyclerView.ViewHolder>(Diff) {

    var listMode: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return when {
            item.type == MediaType.FOLDER -> VIEW_FOLDER_ROW
            listMode -> VIEW_FILE_ROW
            else -> VIEW_FILE_GRID
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_FOLDER_ROW -> FolderRowHolder(
                ItemFolderRowBinding.inflate(inflater, parent, false),
                onClick,
                onFocusSidebar,
            )
            VIEW_FILE_ROW -> FileRowHolder(
                ItemFileRowBinding.inflate(inflater, parent, false),
                onClick,
                onFocusSidebar,
            )
            else -> FileGridHolder(
                ItemMediaBinding.inflate(inflater, parent, false),
                onClick,
                onFocusSidebar,
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is FolderRowHolder -> holder.bind(item)
            is FileRowHolder -> holder.bind(item)
            is FileGridHolder -> holder.bind(item)
        }
    }

    class FolderRowHolder(
        private val binding: ItemFolderRowBinding,
        private val onClick: (MediaItem) -> Unit,
        private val onFocusSidebar: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaItem) {
            Dpad.bindItem(binding.root)
            binding.name.text = item.name
            binding.meta.text = binding.root.resources.getString(
                R.string.folder_meta,
                formatDate(item.dateModified),
                item.childCount,
            )
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnKeyListener { view, keyCode, event ->
                Dpad.handleContentKey(view, keyCode, event, onFocusSidebar)
            }
        }
    }

    class FileRowHolder(
        private val binding: ItemFileRowBinding,
        private val onClick: (MediaItem) -> Unit,
        private val onFocusSidebar: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaItem) {
            Dpad.bindItem(binding.root)
            binding.name.text = item.name
            binding.meta.text = binding.root.resources.getString(
                R.string.file_meta,
                formatDate(item.dateModified),
                typeLabel(item.type),
            )
            when (item.type) {
                MediaType.IMAGE, MediaType.VIDEO -> {
                    binding.icon.scaleType = ImageView.ScaleType.CENTER_CROP
                    Glide.with(binding.icon)
                        .load(item.uri)
                        .centerCrop()
                        .placeholder(R.drawable.bg_file_thumb)
                        .into(binding.icon)
                }
                MediaType.WEB -> {
                    Glide.with(binding.icon).clear(binding.icon)
                    binding.icon.scaleType = ImageView.ScaleType.CENTER
                    binding.icon.setImageResource(R.drawable.ic_nav_web)
                }
                MediaType.TEXT -> {
                    Glide.with(binding.icon).clear(binding.icon)
                    binding.icon.scaleType = ImageView.ScaleType.CENTER
                    binding.icon.setImageResource(R.drawable.ic_nav_text)
                }
                MediaType.FILE -> {
                    Glide.with(binding.icon).clear(binding.icon)
                    binding.icon.scaleType = ImageView.ScaleType.CENTER
                    binding.icon.setImageResource(R.drawable.ic_nav_file)
                }
                MediaType.FOLDER -> Unit
            }
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnKeyListener { view, keyCode, event ->
                Dpad.handleContentKey(view, keyCode, event, onFocusSidebar)
            }
        }
    }

    class FileGridHolder(
        private val binding: ItemMediaBinding,
        private val onClick: (MediaItem) -> Unit,
        private val onFocusSidebar: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaItem) {
            Dpad.bindItem(binding.root)
            binding.name.text = item.name
            binding.badge.isVisible = item.type == MediaType.VIDEO
            binding.badge.setImageResource(R.drawable.ic_badge_video)
            binding.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            Glide.with(binding.thumb)
                .load(item.uri)
                .centerCrop()
                .placeholder(R.drawable.bg_thumb)
                .into(binding.thumb)
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                binding.root.strokeWidth = if (hasFocus) 4 else 0
                binding.root.strokeColor = ContextCompat.getColor(binding.root.context, R.color.md_primary)
            }
            binding.root.setOnKeyListener { view, keyCode, event ->
                Dpad.handleContentKey(view, keyCode, event, onFocusSidebar)
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val VIEW_FOLDER_ROW = 1
        private const val VIEW_FILE_ROW = 2
        private const val VIEW_FILE_GRID = 3
        private val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.CHINA)

        fun formatDate(timeMs: Long): String {
            if (timeMs <= 0L) return "--"
            return dateFormat.format(Date(timeMs))
        }

        fun typeLabel(type: MediaType): String {
            return when (type) {
                MediaType.IMAGE -> "图片"
                MediaType.VIDEO -> "视频"
                MediaType.WEB -> "网页"
                MediaType.TEXT -> "文本"
                MediaType.FOLDER -> "文件夹"
                MediaType.FILE -> "文件"
            }
        }
    }
}
