package com.mediacenter.app.ui.gallery

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mediacenter.app.data.model.MediaFilter
import com.mediacenter.app.databinding.ItemNavBinding

data class NavDestination(
    val id: String,
    val title: String,
    val iconRes: Int,
    val filter: MediaFilter? = null,
    val volumeId: String? = null,
) {
    companion object {
        const val STORAGE_ID = "storage"
        const val RECENT_ID = "recent"
        const val FAVORITE_ID = "favorite"
        const val SEARCH_ID = "search"
        const val IMAGE_ID = "image"
        const val VIDEO_ID = "video"
        const val MUSIC_ID = "music"
        const val WEB_ID = "web"
        const val TEXT_ID = "text"
        const val BOOK_ID = "book"
        const val ARCHIVE_ID = "archive"
        const val APK_ID = "apk"
    }
}

class NavAdapter(
    private val onClick: (NavDestination) -> Unit,
    private val onFocusContent: () -> Unit = {},
) : RecyclerView.Adapter<NavAdapter.Holder>() {

    private val items = ArrayList<NavDestination>()
    var selectedId: String = NavDestination.STORAGE_ID
        set(value) {
            if (field == value) return
            val old = items.indexOfFirst { it.id == field }
            val next = items.indexOfFirst { it.id == value }
            field = value
            if (old >= 0) notifyItemChanged(old, PAYLOAD_SELECTION)
            if (next >= 0) notifyItemChanged(next, PAYLOAD_SELECTION)
        }

    val selectedIndex: Int
        get() = items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)

    fun submit(next: List<NavDestination>, selected: String) {
        if (items == next) {
            selectedId = selected
            return
        }
        items.clear()
        items.addAll(next)
        selectedId = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemNavBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        Dpad.bindItem(binding.root)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], items[position].id == selectedId)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.setChecked(items[position].id == selectedId)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemNavBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: NavDestination, checked: Boolean) {
            binding.label.text = item.title
            binding.icon.setImageResource(item.iconRes)
            setChecked(checked)
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnKeyListener { view, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when {
                    Dpad.isActivate(keyCode) -> {
                        onClick(item)
                        true
                    }
                    keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> Dpad.move(view, 1)
                    keyCode == KeyEvent.KEYCODE_DPAD_UP -> Dpad.move(view, -1)
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onFocusContent()
                        true
                    }
                    else -> false
                }
            }
        }

        fun setChecked(checked: Boolean) {
            binding.root.isSelected = checked
        }
    }

    companion object {
        private const val PAYLOAD_SELECTION = "selection"
    }
}
