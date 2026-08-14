package com.mediacenter.app.ui.book

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import com.mediacenter.app.ui.BaseActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mediacenter.app.R
import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.databinding.ActivityBookViewerBinding
import kotlinx.coroutines.launch
import org.json.JSONArray

class BookViewerActivity : BaseActivity() {

    private val viewModel: BookViewerViewModel by viewModels()
    private lateinit var binding: ActivityBookViewerBinding
    private var loadedHtml: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.title = viewModel.fallbackTitle
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_toc -> {
                    showToc()
                    true
                }
                R.id.action_annotate -> {
                    captureSelection()
                    true
                }
                R.id.action_notes -> {
                    showNotes()
                    true
                }
                R.id.action_theme -> {
                    viewModel.toggleTheme()
                    true
                }
                else -> false
            }
        }
        binding.buttonPrev.setOnClickListener { viewModel.prevChapter() }
        binding.buttonNext.setOnClickListener { viewModel.nextChapter() }

        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.allowFileAccess = true
        settings.defaultTextEncodingName = "utf-8"
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val state = viewModel.uiState.value
                val y = state.restoreScrollY
                if (y > 0) view.post { view.scrollTo(0, y) }
                injectHighlights(state.quotes)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    override fun onPause() {
        viewModel.saveScroll(binding.webView.scrollY)
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> {
                viewModel.prevChapter()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN -> {
                viewModel.nextChapter()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun render(state: BookUiState) {
        binding.progress.isVisible = state.loading
        binding.error.isVisible = state.error != null
        binding.webView.isVisible = !state.loading && state.error == null
        binding.error.text = state.error
        binding.webView.setBackgroundColor(if (state.useBookCss) 0xFFFFFFFF.toInt() else 0xFF111111.toInt())
        binding.toolbar.title = if (state.chapterCount > 0) {
            val chapter = state.chapterTitle.ifBlank { state.title }
            getString(R.string.book_chapter, chapter, state.chapterIndex + 1, state.chapterCount)
        } else {
            state.title.ifBlank { viewModel.fallbackTitle }
        }
        binding.toolbar.menu.findItem(R.id.action_theme)?.setTitle(
            if (state.useBookCss) R.string.book_theme_night else R.string.book_theme_book,
        )
        binding.buttonPrev.isEnabled = state.chapterIndex > 0
        binding.buttonNext.isEnabled = state.chapterIndex < state.chapterCount - 1
        val html = state.chapterHtml
        if (!html.isNullOrBlank() && html != loadedHtml) {
            loadedHtml = html
            binding.webView.loadDataWithBaseURL(
                state.baseUrl,
                html,
                "text/html",
                "utf-8",
                null,
            )
        }
    }

    private fun showToc() {
        val toc = viewModel.uiState.value.toc
        if (toc.isEmpty()) {
            Toast.makeText(this, R.string.book_notes_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = toc.map { item ->
            "${"　".repeat(item.level)}${item.title}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.book_toc)
            .setItems(labels) { _, which ->
                viewModel.jumpTo(toc[which].spineIndex)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showNotes() {
        val notes = viewModel.uiState.value.annotations
        if (notes.isEmpty()) {
            Toast.makeText(this, R.string.book_notes_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = notes.map { note ->
            val quote = note.quote.take(40)
            if (note.note.isBlank()) quote else "$quote\n${note.note}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.book_notes)
            .setItems(labels) { _, which ->
                val note = notes[which]
                MaterialAlertDialogBuilder(this)
                    .setTitle(note.quote.take(30))
                    .setMessage(note.note.ifBlank { note.quote })
                    .setPositiveButton(R.string.action_ok) { _, _ ->
                        viewModel.jumpTo(note.chapterIndex)
                    }
                    .setNegativeButton(R.string.book_delete_note) { _, _ ->
                        viewModel.removeAnnotation(note.id)
                    }
                    .show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun captureSelection() {
        binding.webView.evaluateJavascript("(function(){return window.getSelection().toString();})()") { raw ->
            val quote = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
                ?.trim()
                .orEmpty()
            if (quote.isBlank() || quote == "null") {
                Toast.makeText(this, R.string.book_select_first, Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            val input = EditText(this).apply {
                hint = getString(R.string.book_note_hint)
                setPadding(48, 32, 48, 16)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(quote.take(40))
                .setView(input)
                .setPositiveButton(R.string.book_annotate) { _, _ ->
                    viewModel.addAnnotation(quote, input.text?.toString().orEmpty())
                    Toast.makeText(this, R.string.book_note_saved, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun injectHighlights(quotes: List<String>) {
        if (quotes.isEmpty()) return
        val json = JSONArray(quotes).toString()
        val script = """
            (function(){
              var quotes = $json;
              quotes.forEach(function(q){
                if(!q) return;
                var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
                var node;
                while(node = walker.nextNode()){
                  var i = node.nodeValue.indexOf(q);
                  if(i >= 0 && node.parentElement && node.parentElement.tagName !== 'MARK'){
                    var range = document.createRange();
                    range.setStart(node, i);
                    range.setEnd(node, i + q.length);
                    var mark = document.createElement('mark');
                    try { range.surroundContents(mark); } catch(e) {}
                    break;
                  }
                }
              });
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(script, null)
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            binding.webView.stopLoading()
            binding.webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context, item: MediaItem): Intent {
            return Intent(context, BookViewerActivity::class.java)
                .putExtra(BookViewerViewModel.EXTRA_URI, item.uri.toString())
                .putExtra(BookViewerViewModel.EXTRA_TITLE, item.name)
                .putExtra(BookViewerViewModel.EXTRA_FILE_PATH, item.filePath)
        }
    }
}
