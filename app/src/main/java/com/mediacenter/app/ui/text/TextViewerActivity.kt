package com.mediacenter.app.ui.text

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mediacenter.app.R
import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.databinding.ActivityTextViewerBinding
import kotlinx.coroutines.launch

class TextViewerActivity : AppCompatActivity() {

    private val viewModel: TextViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityTextViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.toolbar.title = state.title
                    binding.progress.isVisible = state.loading
                    binding.content.isVisible = !state.loading
                    binding.truncated.isVisible = state.truncated
                    binding.content.text = state.error ?: state.content
                    if (state.truncated) {
                        binding.truncated.text = getString(R.string.text_truncated)
                    }
                }
            }
        }
    }

    companion object {
        fun intent(context: Context, item: MediaItem): Intent {
            return Intent(context, TextViewerActivity::class.java)
                .putExtra(TextViewerViewModel.EXTRA_URI, item.uri.toString())
                .putExtra(TextViewerViewModel.EXTRA_TITLE, item.name)
        }
    }
}
