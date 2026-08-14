package com.mediacenter.app.ui.image

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.viewModels
import com.mediacenter.app.ui.BaseActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.databinding.ActivityImageViewerBinding
import com.mediacenter.app.databinding.ItemImagePageBinding
import kotlinx.coroutines.launch

class ImageViewerActivity : BaseActivity() {

    private val viewModel: ImageViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val pagerAdapter = ImagePagerAdapter(viewModel.images)
        binding.pager.adapter = pagerAdapter
        binding.pager.setCurrentItem(viewModel.startIndex, false)
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                viewModel.onPageChanged(position)
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.title.collect { binding.toolbar.title = it }
            }
        }
    }

    private class ImagePagerAdapter(
        private val items: List<MediaItem>,
    ) : RecyclerView.Adapter<ImagePagerAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemImagePageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class Holder(private val binding: ItemImagePageBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: MediaItem) {
                Glide.with(binding.image)
                    .load(item.uri)
                    .fitCenter()
                    .into(binding.image)
            }
        }
    }

    companion object {
        fun intent(context: Context, item: MediaItem): Intent {
            return Intent(context, ImageViewerActivity::class.java)
                .putExtra(ImageViewerViewModel.EXTRA_URI, item.uri.toString())
                .putExtra(ImageViewerViewModel.EXTRA_TITLE, item.name)
        }
    }
}
