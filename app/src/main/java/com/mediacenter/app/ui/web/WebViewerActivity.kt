package com.mediacenter.app.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewAssetLoader
import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.databinding.ActivityWebViewerBinding
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.launch

class WebViewerActivity : AppCompatActivity() {

    private val viewModel: WebViewerViewModel by viewModels()
    private lateinit var binding: ActivityWebViewerBinding
    private var pageLoaded = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.title = viewModel.title
        binding.toolbar.setNavigationOnClickListener { closeViewer() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    closeViewer()
                }
            },
        )
        binding.webView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)
            ) {
                closeViewer()
                true
            } else {
                false
            }
        }

        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.loadsImagesAutomatically = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.defaultTextEncodingName = "utf-8"
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = true

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.toolbar.title = state.title.ifBlank { viewModel.title }
                    if (pageLoaded || state.loading) return@collect
                    if (state.error != null) {
                        pageLoaded = true
                        binding.webView.loadData(state.error, "text/plain; charset=utf-8", "utf-8")
                        return@collect
                    }
                    val path = state.filePath
                    if (!path.isNullOrBlank()) {
                        pageLoaded = true
                        binding.webView.webViewClient = LocalWebViewClient()
                        binding.webView.loadUrl(Uri.fromFile(File(path)).toString())
                        return@collect
                    }
                    if (state.html.isNotEmpty()) {
                        pageLoaded = true
                        loadHtmlFallback(state.html)
                    }
                }
            }
        }
    }

    private fun loadHtmlFallback(html: String) {
        val uri = viewModel.uri
        val parent = uri?.let(::resolveParent)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/local/", DocumentPathHandler(this, parent, uri))
            .build()
        binding.webView.webViewClient = object : LocalWebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
                    ?: super.shouldInterceptRequest(view, request)
            }
        }
        binding.webView.loadDataWithBaseURL(
            "https://appassets.androidplatform.net/local/",
            html,
            "text/html",
            "utf-8",
            null,
        )
    }

    private fun resolveParent(uri: Uri): DocumentFile? {
        viewModel.parentUri?.let { parentUri ->
            DocumentFile.fromTreeUri(this, parentUri)?.let { tree ->
                findParentOf(tree, uri)?.let { return it }
            }
            DocumentFile.fromSingleUri(this, parentUri)?.let { return it }
        }
        return DocumentFile.fromSingleUri(this, uri)
    }

    private fun findParentOf(dir: DocumentFile, target: Uri): DocumentFile? {
        for (child in dir.listFiles()) {
            if (child.uri == target) return dir
            if (child.isDirectory) {
                findParentOf(child, target)?.let { return it }
            }
        }
        return null
    }

    private fun closeViewer() {
        if (isFinishing) return
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            closeViewer()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            val webView = binding.webView
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
        super.onDestroy()
    }

    private open inner class LocalWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean = false
    }

    private class DocumentPathHandler(
        private val context: Context,
        private val parent: DocumentFile?,
        private val pageUri: Uri?,
    ) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val file = resolve(path) ?: return fileFromDisk(path)
            val mime = file.type
                ?: MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(file.name?.substringAfterLast('.', ""))
                ?: "application/octet-stream"
            val stream = context.contentResolver.openInputStream(file.uri)
                ?: return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(ByteArray(0)))
            return WebResourceResponse(mime, "utf-8", stream)
        }

        private fun fileFromDisk(path: String): WebResourceResponse? {
            val parentPath = parent?.uri?.path ?: return null
            val target = File(parentPath, path.trimStart('/'))
            if (!target.isFile) return null
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(target.extension.lowercase())
                ?: "application/octet-stream"
            return WebResourceResponse(mime, "utf-8", FileInputStream(target))
        }

        private fun resolve(path: String): DocumentFile? {
            val clean = path.trimStart('/').substringAfter("local/", path.trimStart('/'))
            if (clean.isEmpty()) {
                return pageUri?.let { DocumentFile.fromSingleUri(context, it) }
            }
            var current = parent ?: return pageUri?.let { DocumentFile.fromSingleUri(context, it) }
            val parts = clean.split('/').filter { it.isNotEmpty() && it != "." }
            for (part in parts) {
                if (part == "..") {
                    current = current.parentFile ?: return null
                    continue
                }
                current = current.findFile(part) ?: return null
            }
            return current
        }
    }

    companion object {
        fun intent(context: Context, item: MediaItem): Intent {
            return Intent(context, WebViewerActivity::class.java)
                .putExtra(WebViewerViewModel.EXTRA_URI, item.uri.toString())
                .putExtra(WebViewerViewModel.EXTRA_TITLE, item.name)
                .putExtra(WebViewerViewModel.EXTRA_PARENT_URI, item.parentUri?.toString())
                .putExtra(WebViewerViewModel.EXTRA_FILE_PATH, item.filePath)
        }
    }
}
