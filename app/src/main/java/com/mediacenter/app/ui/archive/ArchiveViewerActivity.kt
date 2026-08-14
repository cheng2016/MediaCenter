package com.mediacenter.app.ui.archive

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import com.mediacenter.app.ui.BaseActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mediacenter.app.R
import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.data.model.MediaType
import com.mediacenter.app.databinding.ActivityArchiveViewerBinding
import com.mediacenter.app.ui.gallery.Dpad
import com.mediacenter.app.ui.gallery.MediaAdapter
import com.mediacenter.app.ui.gallery.MediaIntents
import kotlinx.coroutines.launch

class ArchiveViewerActivity : BaseActivity() {

    private val viewModel: ArchiveViewerViewModel by viewModels()
    private lateinit var binding: ActivityArchiveViewerBinding
    private val adapter = MediaAdapter(::onEntryClick)

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (viewModel.goUp()) {
                reload()
            } else {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArchiveViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        onBackPressedDispatcher.addCallback(this, backCallback)
        binding.toolbar.title = viewModel.title
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.entryList.layoutManager = LinearLayoutManager(this)
        binding.entryList.adapter = adapter
        binding.entryList.itemAnimator = null

        lifecycleScope.launch {
            val error = viewModel.open()
            when (error) {
                "unsupported" -> {
                    Toast.makeText(this@ArchiveViewerActivity, R.string.archive_unsupported, Toast.LENGTH_SHORT).show()
                    finish()
                }
                "failed" -> {
                    Toast.makeText(this@ArchiveViewerActivity, R.string.archive_open_failed, Toast.LENGTH_SHORT).show()
                    finish()
                }
                else -> reload()
            }
        }
    }

    private fun reload() {
        lifecycleScope.launch {
            binding.progress.isVisible = true
            val items = viewModel.listEntries()
            binding.progress.isVisible = false
            adapter.submitMedia(items)
            binding.entryList.isVisible = items.isNotEmpty()
            binding.empty.isVisible = items.isEmpty()
            binding.empty.setText(R.string.empty_archive)
            binding.toolbar.title = viewModel.currentTitle()
            if (items.isNotEmpty()) {
                binding.entryList.post {
                    Dpad.focusPosition(binding.entryList, 0)
                }
            }
        }
    }

    private fun onEntryClick(item: MediaItem) {
        if (item.type == MediaType.FOLDER) {
            viewModel.enter(item)
            reload()
            return
        }
        lifecycleScope.launch {
            binding.progress.isVisible = true
            val extracted = viewModel.extract(item)
            binding.progress.isVisible = false
            if (extracted == null) {
                Toast.makeText(this@ArchiveViewerActivity, R.string.archive_extract_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            openExtracted(extracted)
        }
    }

    private fun openExtracted(item: MediaItem) {
        val type = MediaIntents.resolveType(item)
        if (type == MediaType.APK) {
            val (intent, message) = MediaIntents.apkIntent(this, item)
            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
            startActivity(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            return
        }
        val intent = MediaIntents.viewerIntent(this, item)
        if (intent == null) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        fun intent(context: Context, item: MediaItem): Intent {
            return Intent(context, ArchiveViewerActivity::class.java)
                .putExtra(ArchiveViewerViewModel.EXTRA_URI, item.uri.toString())
                .putExtra(ArchiveViewerViewModel.EXTRA_TITLE, item.name)
                .putExtra(ArchiveViewerViewModel.EXTRA_FILE_PATH, item.filePath)
        }
    }
}
