package com.mediacenter.app.ui.text

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import com.mediacenter.app.ui.BaseActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mediacenter.app.R
import com.mediacenter.app.data.MediaRepository
import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.databinding.ActivityTextViewerBinding
import kotlinx.coroutines.launch

class TextViewerActivity : BaseActivity() {

    private val viewModel: TextViewerViewModel by viewModels()
    private lateinit var binding: ActivityTextViewerBinding
    private var loaded = false
    private var wasEditing = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            leaveEditing()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        onBackPressedDispatcher.addCallback(this, backCallback)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_edit -> {
                    viewModel.startEditing()
                    true
                }
                R.id.action_save -> {
                    viewModel.save(binding.content.text?.toString().orEmpty(), notify = true)
                    true
                }
                else -> false
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.toolbar.title = state.title
                    binding.progress.isVisible = state.loading
                    binding.content.isVisible = !state.loading
                    binding.truncated.isVisible = state.truncated || state.error != null
                    binding.toolbar.menu.findItem(R.id.action_edit)?.isVisible =
                        state.canEdit && !state.editing
                    binding.toolbar.menu.findItem(R.id.action_save)?.isVisible = state.editing
                    backCallback.isEnabled = state.editing
                    applyEditorMode(state.editing)
                    if (state.editing && !wasEditing) {
                        binding.content.requestFocus()
                    }
                    wasEditing = state.editing
                    if (state.truncated) {
                        binding.truncated.text = getString(
                            if (MediaRepository.isPlainTxt(state.title)) {
                                R.string.text_read_only
                            } else {
                                R.string.text_truncated
                            },
                        )
                    } else if (state.error != null) {
                        binding.truncated.text = state.error
                    }
                    if (!loaded && !state.loading) {
                        binding.content.setText(state.error ?: state.content)
                        loaded = true
                    }
                    state.saveMessage?.let { message ->
                        Toast.makeText(this@TextViewerActivity, message, Toast.LENGTH_SHORT).show()
                        viewModel.consumeSaveMessage()
                    }
                }
            }
        }
    }

    override fun onStop() {
        if (loaded && viewModel.uiState.value.editing) {
            viewModel.save(binding.content.text?.toString().orEmpty(), notify = false)
        }
        super.onStop()
    }

    private fun leaveEditing() {
        viewModel.save(binding.content.text?.toString().orEmpty(), notify = false)
        viewModel.finishEditing()
        binding.content.clearFocus()
    }

    private fun applyEditorMode(editing: Boolean) {
        binding.content.isCursorVisible = editing
        binding.content.isFocusable = true
        binding.content.isFocusableInTouchMode = editing
        if (editing) {
            binding.content.inputType =
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        } else {
            binding.content.keyListener = null
            binding.content.setTextIsSelectable(true)
        }
    }

    companion object {
        fun intent(context: Context, item: MediaItem): Intent {
            return Intent(context, TextViewerActivity::class.java)
                .putExtra(TextViewerViewModel.EXTRA_URI, item.uri.toString())
                .putExtra(TextViewerViewModel.EXTRA_TITLE, item.name)
                .putExtra(TextViewerViewModel.EXTRA_FILE_PATH, item.filePath)
        }
    }
}
