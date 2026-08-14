package com.mediacenter.app.ui.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mediacenter.app.R
import com.mediacenter.app.data.MediaRepository
import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.data.model.MediaType
import com.mediacenter.app.ui.archive.ArchiveViewerActivity
import com.mediacenter.app.ui.audio.AudioPlayerActivity
import com.mediacenter.app.ui.book.BookViewerActivity
import com.mediacenter.app.ui.image.ImageViewerActivity
import com.mediacenter.app.ui.pdf.PdfViewerActivity
import com.mediacenter.app.ui.text.TextViewerActivity
import com.mediacenter.app.ui.video.VideoPlayerActivity
import com.mediacenter.app.ui.web.WebViewerActivity
import java.io.File

object MediaIntents {

    fun resolveType(item: MediaItem): MediaType {
        return when {
            MediaRepository.isWebPage(item.name, item.mimeType) -> MediaType.WEB
            MediaRepository.isPdf(item.name, item.mimeType) -> MediaType.PDF
            MediaRepository.isBook(item.name, item.mimeType) -> MediaType.BOOK
            MediaRepository.isArchive(item.name, item.mimeType) -> MediaType.ARCHIVE
            MediaRepository.isApk(item.name, item.mimeType) -> MediaType.APK
            MediaRepository.isAudio(item.name, item.mimeType) -> MediaType.AUDIO
            else -> item.type
        }
    }

    fun viewerIntent(context: Context, item: MediaItem): Intent? {
        return when (resolveType(item)) {
            MediaType.IMAGE -> ImageViewerActivity.intent(context, item)
            MediaType.VIDEO -> VideoPlayerActivity.intent(context, item)
            MediaType.AUDIO -> AudioPlayerActivity.intent(context, item)
            MediaType.WEB -> WebViewerActivity.intent(context, item)
            MediaType.TEXT -> TextViewerActivity.intent(context, item)
            MediaType.PDF -> PdfViewerActivity.intent(context, item)
            MediaType.BOOK -> BookViewerActivity.intent(context, item)
            MediaType.ARCHIVE -> ArchiveViewerActivity.intent(context, item)
            MediaType.FILE -> genericViewIntent(context, item)
            MediaType.APK, MediaType.FOLDER -> null
        }
    }

    fun apkIntent(context: Context, item: MediaItem): Pair<Intent, Int?> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
            return settings to R.string.allow_unknown_sources
        }
        val uri = shareableUri(context, item)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent to null
    }

    private fun genericViewIntent(context: Context, item: MediaItem): Intent? {
        val mime = item.mimeType ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(item.uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    private fun shareableUri(context: Context, item: MediaItem): Uri {
        val path = item.filePath
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.isFile) {
                return runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }.getOrDefault(item.uri)
            }
        }
        return item.uri
    }
}
