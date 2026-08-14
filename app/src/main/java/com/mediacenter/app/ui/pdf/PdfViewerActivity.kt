package com.mediacenter.app.ui.pdf

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.viewModels
import com.mediacenter.app.ui.BaseActivity
import androidx.core.view.isVisible
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.mediacenter.app.R
import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.databinding.ActivityPdfViewerBinding
import com.mediacenter.app.databinding.ItemPdfPageBinding
import com.mediacenter.app.ui.gallery.Dpad
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PdfViewerActivity : BaseActivity() {

    private val viewModel: PdfViewerViewModel by viewModels()
    private lateinit var binding: ActivityPdfViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.title = viewModel.title
        binding.toolbar.setNavigationOnClickListener { finish() }

        lifecycleScope.launch {
            val error = viewModel.open()
            binding.progress.isVisible = false
            if (error != null) {
                binding.error.isVisible = true
                binding.error.text = error
                return@launch
            }
            val adapter = PdfPageAdapter(viewModel)
            binding.pager.adapter = adapter
            binding.pager.setCurrentItem(viewModel.currentPage, false)
            updateTitle(viewModel.currentPage)
            binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    viewModel.savePage(position)
                    updateTitle(position)
                }
            })
            Dpad.requestFocusIfRemote(binding.pager)
        }
    }

    override fun onStop() {
        viewModel.savePage(binding.pager.currentItem)
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val page = binding.pager.currentItem
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (page > 0) binding.pager.currentItem = page - 1
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (page < viewModel.pageCount - 1) binding.pager.currentItem = page + 1
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun updateTitle(page: Int) {
        binding.toolbar.title = if (viewModel.pageCount > 0) {
            getString(R.string.pdf_page, page + 1, viewModel.pageCount)
        } else {
            viewModel.title
        }
    }

    companion object {
        fun intent(context: Context, item: MediaItem): Intent {
            return Intent(context, PdfViewerActivity::class.java)
                .putExtra(PdfViewerViewModel.EXTRA_URI, item.uri.toString())
                .putExtra(PdfViewerViewModel.EXTRA_TITLE, item.name)
                .putExtra(PdfViewerViewModel.EXTRA_FILE_PATH, item.filePath)
        }
    }
}

private class PdfPageAdapter(
    private val viewModel: PdfViewerViewModel,
) : RecyclerView.Adapter<PdfPageAdapter.Holder>() {

    override fun getItemCount(): Int = viewModel.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemPdfPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(position)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.recycle()
    }

    inner class Holder(private val binding: ItemPdfPageBinding) : RecyclerView.ViewHolder(binding.root) {
        private var job: Job? = null

        fun bind(page: Int) {
            job?.cancel()
            binding.page.setImageBitmap(null)
            val width = binding.root.resources.displayMetrics.widthPixels
            val owner = binding.root.findViewTreeLifecycleOwner() ?: return
            job = owner.lifecycleScope.launch {
                val bitmap = viewModel.render(page, width)
                if (bindingAdapterPosition == page) {
                    binding.page.setImageBitmap(bitmap)
                    binding.page.scaleType = ImageView.ScaleType.FIT_CENTER
                } else {
                    bitmap?.recycle()
                }
            }
        }

        fun recycle() {
            job?.cancel()
            binding.page.setImageBitmap(null)
        }
    }
}
