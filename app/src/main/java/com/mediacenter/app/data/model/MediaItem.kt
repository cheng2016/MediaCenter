package com.mediacenter.app.data.model

import android.net.Uri

data class MediaItem(
    val id: String,
    val uri: Uri,
    val name: String,
    val mimeType: String?,
    val size: Long,
    val dateModified: Long,
    val durationMs: Long = 0L,
    val type: MediaType,
    val parentUri: Uri? = null,
    val bucketId: String? = null,
    val bucketName: String? = null,
    val coverUri: Uri? = null,
    val childCount: Int = 0,
    val filePath: String? = null,
    val volumeId: String? = null,
    val isFavorite: Boolean = false,
    val isMissing: Boolean = false,
) {
    val isSafFolder: Boolean get() = id.startsWith("saf-folder")
    val isFileFolder: Boolean
        get() = id.startsWith("file-folder") ||
            (type == MediaType.FOLDER && !filePath.isNullOrBlank() && !isSafFolder)
    val isVolumeRoot: Boolean get() = id.startsWith("volume-")
}
