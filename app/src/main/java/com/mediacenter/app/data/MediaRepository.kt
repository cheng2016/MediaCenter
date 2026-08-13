package com.mediacenter.app.data

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.data.model.MediaType
import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    var lastOpenedImages: List<MediaItem> = emptyList()

    fun getSavedFolderUri(): Uri? = getSavedFolderUris().firstOrNull()

    fun getSavedFolderUris(): List<Uri> {
        val set = prefs.getStringSet(KEY_FOLDER_URIS, null)
        if (!set.isNullOrEmpty()) return set.map(Uri::parse)
        val legacy = prefs.getString(KEY_FOLDER_URI, null)
        return if (legacy != null) listOf(Uri.parse(legacy)) else emptyList()
    }

    fun saveFolderUri(uri: Uri) {
        val next = getSavedFolderUris().map { it.toString() }.toMutableSet()
        next.add(uri.toString())
        prefs.edit()
            .putStringSet(KEY_FOLDER_URIS, next)
            .remove(KEY_FOLDER_URI)
            .apply()
    }

    fun hasSavedFolder(): Boolean = getSavedFolderUris().isNotEmpty()

    fun saveVolumeTree(volumeId: String, uri: Uri) {
        saveFolderUri(uri)
        prefs.edit().putString(volumeTreeKey(volumeId), uri.toString()).apply()
    }

    fun volumeTreeUri(volumeId: String): Uri? =
        prefs.getString(volumeTreeKey(volumeId), null)?.let(Uri::parse)

    fun listVolumes(): List<VolumeInfo> {
        return StorageVolumes.list(context) { path -> canList(File(path)) }
    }

    fun openVolumeTreeIntent(volumeId: String): Intent? =
        StorageVolumes.openTreeIntent(context, volumeId)

    suspend fun loadLibrary(): Library = withContext(Dispatchers.IO) {
        val volumes = listVolumes()
        val mediaStoreFiles = ArrayList<MediaItem>()
        for (volume in volumes) {
            mediaStoreFiles += queryImages(volume.mediaStoreName, volume.id, volume.directoryPath)
            mediaStoreFiles += queryVideos(volume.mediaStoreName, volume.id, volume.directoryPath)
            mediaStoreFiles += queryDocuments(volume.mediaStoreName, volume.id)
        }
        val primaryFiles = mediaStoreFiles.filter { it.volumeId == StorageVolumes.PRIMARY_ID || it.volumeId == null }
        val albums = buildAlbums(primaryFiles)
        val scannedFiles = ArrayList<MediaItem>()
        val volumeRoots = HashMap<String, List<MediaItem>>()

        val primary = volumes.firstOrNull { it.isPrimary }
        val primaryRoot = primary?.directoryPath?.let(::File)
        val primaryChildren = if (primaryRoot != null && canList(primaryRoot)) {
            listDirectory(primaryRoot)
        } else {
            emptyList()
        }
        if (primary != null) {
            volumeRoots[primary.id] = primaryChildren
        }
        scannedFiles += collectFiles(primaryChildren)

        for (volume in volumes.filter { !it.isPrimary }) {
            val children = loadVolumeRoot(volume)
            volumeRoots[volume.id] = children
            scannedFiles += collectFiles(children)
        }

        val safFolders = ArrayList<MediaItem>()
        if (primaryChildren.isEmpty()) {
            for (tree in getSavedFolderUris()) {
                val root = DocumentFile.fromTreeUri(context, tree) ?: continue
                val files = ArrayList<MediaItem>()
                walk(root, files)
                scannedFiles += files
                safFolders += MediaItem(
                    id = "saf-folder-${tree}",
                    uri = tree,
                    name = root.name ?: "本地文件夹",
                    mimeType = null,
                    size = 0L,
                    dateModified = root.lastModified(),
                    type = MediaType.FOLDER,
                    parentUri = tree,
                    coverUri = files.firstOrNull { it.type == MediaType.IMAGE || it.type == MediaType.VIDEO }?.uri,
                    childCount = files.size,
                )
            }
        }

        val storageFolders = primaryChildren.ifEmpty { safFolders + albums }

        Library(
            folders = storageFolders.distinctBy { it.id },
            files = (mediaStoreFiles + scannedFiles)
                .distinctBy { it.uri.toString() }
                .sortedByDescending { it.dateModified },
            volumes = volumes,
            volumeRoots = volumeRoots,
        )
    }

    suspend fun listFileChildren(path: String): List<MediaItem> = withContext(Dispatchers.IO) {
        listDirectory(File(path))
    }

    suspend fun listFolderContents(folder: MediaItem): List<MediaItem> = withContext(Dispatchers.IO) {
        folder.filePath?.let { path ->
            val dir = File(path)
            if (canList(dir)) return@withContext listDirectory(dir)
        }
        if (folder.isSafFolder || folder.uri.scheme == "content") {
            val saf = listSafChildren(folder.uri)
            if (saf.isNotEmpty()) return@withContext saf
        }
        folder.bucketId?.let { return@withContext listBucketFiles(it, folder.volumeId) }
        emptyList()
    }

    private fun loadVolumeRoot(volume: VolumeInfo): List<MediaItem> {
        val path = volume.directoryPath
        if (path != null && canList(File(path))) {
            return listDirectory(File(path))
        }
        volumeTreeUri(volume.id)?.let { tree ->
            val root = DocumentFile.fromTreeUri(context, tree)
            if (root != null) {
                return listSafImmediate(root)
            }
        }
        return emptyList()
    }

    private fun collectFiles(items: List<MediaItem>): List<MediaItem> {
        val files = ArrayList<MediaItem>()
        for (item in items) {
            if (item.type == MediaType.FOLDER) {
                item.filePath?.let { files += scanFileTree(File(it)) }
            } else {
                files += item
            }
        }
        return files
    }

    private fun scanFileTree(dir: File): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        walkFiles(dir, out, 0)
        return out
    }

    private fun walkFiles(dir: File, out: ArrayList<MediaItem>, depth: Int) {
        if (depth > 8 || out.size >= MAX_SAF_FILES) return
        val children = dir.listFiles() ?: return
        for (file in children) {
            if (out.size >= MAX_SAF_FILES) return
            if (file.isDirectory) {
                walkFiles(file, out, depth + 1)
                continue
            }
            out += fileItem(file, classify(file.name, null), dir)
        }
    }

    private fun listDirectory(dir: File): List<MediaItem> {
        val children = dir.listFiles() ?: return emptyList()
        val items = ArrayList<MediaItem>()
        for (file in children) {
            if (file.isDirectory) {
                val nested = file.listFiles()?.size ?: 0
                items += MediaItem(
                    id = "file-folder-${file.absolutePath}",
                    uri = fileUri(file),
                    name = file.name,
                    mimeType = null,
                    size = 0L,
                    dateModified = file.lastModified(),
                    type = MediaType.FOLDER,
                    childCount = nested,
                    filePath = file.absolutePath,
                )
                continue
            }
            items += fileItem(file, classify(file.name, null), dir)
        }
        return items.sortedWith(
            compareBy<MediaItem> { it.type != MediaType.FOLDER }.thenBy { it.name.lowercase() },
        )
    }

    private fun listSafImmediate(dir: DocumentFile): List<MediaItem> {
        val children = try {
            dir.listFiles()
        } catch (_: Exception) {
            return emptyList()
        }
        val items = ArrayList<MediaItem>()
        for (file in children) {
            if (file.isDirectory) {
                val nested = try {
                    file.listFiles().size
                } catch (_: Exception) {
                    0
                }
                items += MediaItem(
                    id = "saf-folder-${file.uri}",
                    uri = file.uri,
                    name = file.name ?: "文件夹",
                    mimeType = null,
                    size = 0L,
                    dateModified = file.lastModified(),
                    type = MediaType.FOLDER,
                    parentUri = dir.uri,
                    childCount = nested,
                )
                continue
            }
            val name = file.name.orEmpty()
            items += MediaItem(
                id = "saf-${file.uri}",
                uri = file.uri,
                name = name,
                mimeType = file.type,
                size = file.length(),
                dateModified = file.lastModified(),
                type = classify(name, file.type),
                parentUri = dir.uri,
            )
        }
        return items.sortedWith(
            compareBy<MediaItem> { it.type != MediaType.FOLDER }.thenBy { it.name.lowercase() },
        )
    }

    private fun fileItem(file: File, type: MediaType, parent: File): MediaItem {
        return MediaItem(
            id = "file-${file.absolutePath}",
            uri = fileUri(file),
            name = file.name,
            mimeType = null,
            size = file.length(),
            dateModified = file.lastModified(),
            type = type,
            parentUri = fileUri(parent),
            filePath = file.absolutePath,
        )
    }

    private fun fileUri(file: File): Uri {
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) {
            Uri.fromFile(file)
        }
    }

    private fun canList(dir: File): Boolean {
        return dir.exists() && dir.canRead() && dir.listFiles() != null
    }

    private fun volumeAsFolder(volume: VolumeInfo, children: List<MediaItem>): MediaItem {
        return MediaItem(
            id = "volume-${volume.id}",
            uri = volume.directoryPath?.let { fileUri(File(it)) } ?: Uri.EMPTY,
            name = volume.name,
            mimeType = null,
            size = 0L,
            dateModified = System.currentTimeMillis(),
            type = MediaType.FOLDER,
            childCount = children.size,
            filePath = volume.directoryPath,
            volumeId = volume.id,
        )
    }

    private fun volumeTreeKey(volumeId: String) = "volume_tree_$volumeId"

    suspend fun listSafChildren(folderUri: Uri): List<MediaItem> = withContext(Dispatchers.IO) {
        listSafByContract(folderUri) ?: run {
            val dir = findDocument(folderUri) ?: return@withContext emptyList()
            listSafImmediate(dir)
        }
    }

    private fun listSafByContract(folderUri: Uri): List<MediaItem>? {
        val treeUri = getSavedFolderUris().firstOrNull { isUnderTree(folderUri, it) } ?: folderUri
        val docId = try {
            DocumentsContract.getDocumentId(folderUri)
        } catch (_: Exception) {
            try {
                DocumentsContract.getTreeDocumentId(treeUri)
            } catch (_: Exception) {
                return null
            }
        }
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        } catch (_: Exception) {
            return null
        }
        val items = ArrayList<MediaItem>()
        try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val dateIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(idIdx) ?: continue
                    val name = cursor.getString(nameIdx).orEmpty()
                    val mime = cursor.getString(mimeIdx)
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        items += MediaItem(
                            id = "saf-folder-$childUri",
                            uri = childUri,
                            name = name.ifBlank { "文件夹" },
                            mimeType = null,
                            size = 0L,
                            dateModified = cursor.getLong(dateIdx),
                            type = MediaType.FOLDER,
                            parentUri = folderUri,
                        )
                    } else {
                        items += MediaItem(
                            id = "saf-$childUri",
                            uri = childUri,
                            name = name,
                            mimeType = mime,
                            size = cursor.getLong(sizeIdx),
                            dateModified = cursor.getLong(dateIdx),
                            type = classify(name, mime),
                            parentUri = folderUri,
                        )
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }
        if (items.isEmpty()) return null
        return items.sortedWith(
            compareBy<MediaItem> { it.type != MediaType.FOLDER }.thenBy { it.name.lowercase() },
        )
    }

    private fun isUnderTree(documentUri: Uri, treeUri: Uri): Boolean {
        return documentUri == treeUri ||
            documentUri.toString().startsWith(treeUri.toString()) ||
            runCatching {
                val treeId = DocumentsContract.getTreeDocumentId(treeUri)
                val docId = DocumentsContract.getDocumentId(documentUri)
                docId == treeId || docId.startsWith("$treeId/") || docId.startsWith("$treeId:")
            }.getOrDefault(false)
    }

    private fun listBucketFiles(bucketId: String, volumeId: String?): List<MediaItem> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val volumeName = listVolumes().firstOrNull { it.id == volumeId }?.mediaStoreName
            ?: MediaStore.VOLUME_EXTERNAL
        val collection = MediaStore.Files.getContentUri(volumeName)
        val items = ArrayList<MediaItem>()
        try {
            @Suppress("DEPRECATION")
            context.contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.MIME_TYPE,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATE_MODIFIED,
                    MediaStore.Files.FileColumns.DATA,
                ),
                "${MediaStore.Files.FileColumns.BUCKET_ID}=?",
                arrayOf(bucketId),
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val dataIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx).orEmpty()
                    val mime = cursor.getString(mimeIdx)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR ||
                        mime == "vnd.android.document/directory"
                    ) {
                        continue
                    }
                    val id = cursor.getLong(idIdx)
                    val dataPath = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                    items += MediaItem(
                        id = "doc-bucket-$id",
                        uri = ContentUris.withAppendedId(collection, id),
                        name = name,
                        mimeType = mime,
                        size = cursor.getLong(sizeIdx),
                        dateModified = cursor.getLong(dateIdx) * 1000,
                        type = classify(name, mime),
                        bucketId = bucketId,
                        volumeId = volumeId,
                        filePath = dataPath,
                    )
                }
            }
        } catch (_: Exception) {
            // Bucket query not available on this volume.
        }
        return items
    }

    fun albumItems(files: List<MediaItem>, bucketId: String?): List<MediaItem> {
        if (bucketId == null) return emptyList()
        return files.filter { it.bucketId == bucketId }
    }

    suspend fun readText(uri: Uri, maxBytes: Int = TEXT_MAX_BYTES): TextLoadResult =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(maxBytes + 1)
                var offset = 0
                while (offset < buffer.size) {
                    val read = input.read(buffer, offset, buffer.size - offset)
                    if (read <= 0) break
                    offset += read
                }
                val truncated = offset > maxBytes
                val size = minOf(offset, maxBytes)
                TextLoadResult(String(buffer, 0, size, Charsets.UTF_8), truncated)
            } ?: TextLoadResult("", truncated = false, error = "无法打开文件")
        }

    fun classify(name: String, mime: String?): MediaType {
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        val mimeType = mime?.lowercase().orEmpty()
        return when {
            mimeType.startsWith("image/") || ext in IMAGE_EXT -> MediaType.IMAGE
            mimeType.startsWith("video/") || ext in VIDEO_EXT -> MediaType.VIDEO
            isWebPage(name, mime) -> MediaType.WEB
            mimeType.startsWith("text/") || ext in TEXT_EXT -> MediaType.TEXT
            else -> MediaType.FILE
        }
    }

    private fun buildAlbums(files: List<MediaItem>): List<MediaItem> {
        val albums = LinkedHashMap<String, MediaItem>()
        for (file in files) {
            val bucketId = file.bucketId ?: continue
            val existing = albums[bucketId]
            if (existing == null) {
                albums[bucketId] = MediaItem(
                    id = "album-$bucketId",
                    uri = file.uri,
                    name = file.bucketName?.takeIf { it.isNotBlank() } ?: "相册",
                    mimeType = null,
                    size = 0L,
                    dateModified = file.dateModified,
                    type = MediaType.FOLDER,
                    bucketId = bucketId,
                    coverUri = file.uri,
                    childCount = 1,
                    filePath = file.filePath,
                    volumeId = file.volumeId,
                )
            } else {
                albums[bucketId] = existing.copy(
                    childCount = existing.childCount + 1,
                    dateModified = maxOf(existing.dateModified, file.dateModified),
                    coverUri = if (file.dateModified >= existing.dateModified) file.uri else existing.coverUri,
                )
            }
        }
        return albums.values.toList()
    }

    private fun queryImages(volumeName: String?, volumeId: String, volumePath: String?): List<MediaItem> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && volumeName != null) {
            MediaStore.Images.Media.getContentUri(volumeName)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return queryMediaStore(
            collection = collection,
            idColumn = MediaStore.Images.Media._ID,
            nameColumn = MediaStore.Images.Media.DISPLAY_NAME,
            mimeColumn = MediaStore.Images.Media.MIME_TYPE,
            sizeColumn = MediaStore.Images.Media.SIZE,
            dateColumn = MediaStore.Images.Media.DATE_MODIFIED,
            bucketIdColumn = MediaStore.Images.Media.BUCKET_ID,
            bucketNameColumn = MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            type = MediaType.IMAGE,
            volumeId = volumeId,
            volumePath = volumePath,
        )
    }

    private fun queryVideos(volumeName: String?, volumeId: String, volumePath: String?): List<MediaItem> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && volumeName != null) {
            MediaStore.Video.Media.getContentUri(volumeName)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        return queryMediaStore(
            collection = collection,
            idColumn = MediaStore.Video.Media._ID,
            nameColumn = MediaStore.Video.Media.DISPLAY_NAME,
            mimeColumn = MediaStore.Video.Media.MIME_TYPE,
            sizeColumn = MediaStore.Video.Media.SIZE,
            dateColumn = MediaStore.Video.Media.DATE_MODIFIED,
            durationColumn = MediaStore.Video.Media.DURATION,
            bucketIdColumn = MediaStore.Video.Media.BUCKET_ID,
            bucketNameColumn = MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            type = MediaType.VIDEO,
            volumeId = volumeId,
            volumePath = volumePath,
        )
    }

    private fun queryDocuments(volumeName: String?, volumeId: String): List<MediaItem> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || volumeName == null) return emptyList()
        val collection = MediaStore.Files.getContentUri(volumeName)
        val items = ArrayList<MediaItem>()
        try {
            context.contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.MIME_TYPE,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATE_MODIFIED,
                ),
                null,
                null,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx).orEmpty()
                    val mime = cursor.getString(mimeIdx)
                    val type = classify(name, mime)
                    if (type != MediaType.WEB && type != MediaType.TEXT) continue
                    val id = cursor.getLong(idIdx)
                    items += MediaItem(
                        id = "doc-$volumeId-$id",
                        uri = ContentUris.withAppendedId(collection, id),
                        name = name,
                        mimeType = mime,
                        size = cursor.getLong(sizeIdx),
                        dateModified = cursor.getLong(dateIdx) * 1000,
                        type = type,
                        volumeId = volumeId,
                    )
                }
            }
        } catch (_: Exception) {
            // Some volumes do not expose Files.
        }
        return items
    }

    private fun queryMediaStore(
        collection: Uri,
        idColumn: String,
        nameColumn: String,
        mimeColumn: String,
        sizeColumn: String,
        dateColumn: String,
        durationColumn: String? = null,
        bucketIdColumn: String,
        bucketNameColumn: String,
        type: MediaType,
        volumeId: String,
        volumePath: String? = null,
    ): List<MediaItem> {
        val projection = buildList {
            add(idColumn)
            add(nameColumn)
            add(mimeColumn)
            add(sizeColumn)
            add(dateColumn)
            add(bucketIdColumn)
            add(bucketNameColumn)
            if (durationColumn != null) add(durationColumn)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
        }.toTypedArray()
        val items = ArrayList<MediaItem>()
        try {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "$dateColumn DESC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(idColumn)
                val nameIdx = cursor.getColumnIndexOrThrow(nameColumn)
                val mimeIdx = cursor.getColumnIndexOrThrow(mimeColumn)
                val sizeIdx = cursor.getColumnIndexOrThrow(sizeColumn)
                val dateIdx = cursor.getColumnIndexOrThrow(dateColumn)
                val bucketIdIdx = cursor.getColumnIndexOrThrow(bucketIdColumn)
                val bucketNameIdx = cursor.getColumnIndexOrThrow(bucketNameColumn)
                val durationIdx = durationColumn?.let { cursor.getColumnIndex(it) } ?: -1
                val relativeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val relativePath = if (relativeIdx >= 0) cursor.getString(relativeIdx) else null
                    val parentPath = parentPathOf(null, relativePath, volumePath)
                    items += MediaItem(
                        id = "${type.name}-$id",
                        uri = uri,
                        name = cursor.getString(nameIdx).orEmpty(),
                        mimeType = cursor.getString(mimeIdx),
                        size = cursor.getLong(sizeIdx),
                        dateModified = cursor.getLong(dateIdx) * 1000,
                        durationMs = if (durationIdx >= 0) cursor.getLong(durationIdx) else 0L,
                        type = type,
                        bucketId = cursor.getLong(bucketIdIdx).toString(),
                        bucketName = cursor.getString(bucketNameIdx),
                        volumeId = volumeId,
                        filePath = parentPath,
                    )
                }
            }
        } catch (_: Exception) {
            // Permission missing, or this volume does not expose DATA/RELATIVE_PATH.
        }
        return items
    }

    private fun walk(dir: DocumentFile, out: ArrayList<MediaItem>) {
        if (out.size >= MAX_SAF_FILES) return
        val children = try {
            dir.listFiles()
        } catch (_: Exception) {
            return
        }
        for (file in children) {
            if (out.size >= MAX_SAF_FILES) return
            if (file.isDirectory) {
                walk(file, out)
                continue
            }
            val name = file.name.orEmpty()
            val type = classify(name, file.type)
            out += MediaItem(
                id = "saf-${file.uri}",
                uri = file.uri,
                name = name,
                mimeType = file.type,
                size = file.length(),
                dateModified = file.lastModified(),
                type = type,
                parentUri = dir.uri,
            )
        }
    }

    private fun parentPathOf(dataPath: String?, relativePath: String?, volumePath: String?): String? {
        if (!dataPath.isNullOrBlank()) {
            return File(dataPath).parent
        }
        if (!relativePath.isNullOrBlank()) {
            val root = volumePath ?: Environment.getExternalStorageDirectory().absolutePath
            return File(root, relativePath.trimEnd('/')).absolutePath
        }
        return null
    }

    fun resolveLocalPath(uri: Uri?, filePath: String?): String? {
        if (!filePath.isNullOrBlank()) {
            val file = File(filePath)
            if (file.isFile && file.canRead()) return file.absolutePath
            if (file.isDirectory) return null
        }
        if (uri == null) return null
        if (uri.scheme == "file") {
            return uri.path?.takeIf { File(it).isFile }
        }
        if (uri.authority?.endsWith(".fileprovider") == true) {
            val raw = uri.path.orEmpty()
            val candidates = listOf(
                raw.removePrefix("/root"),
                raw.removePrefix("/external"),
                raw.removePrefix("/external_files"),
            )
            for (candidate in candidates) {
                val file = File(candidate)
                if (file.isFile && file.canRead()) return file.absolutePath
            }
        }
        return null
    }

    fun readHtml(uri: Uri, filePath: String? = null): String {
        resolveLocalPath(uri, filePath)?.let { path ->
            return decodeText(File(path).readBytes())
        }
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        return if (bytes != null) decodeText(bytes) else ""
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val charsetName = Regex(
            "charset\\s*=\\s*['\"]?([\\w-]+)",
            RegexOption.IGNORE_CASE,
        ).find(head)?.groupValues?.get(1)
        val charset = when (charsetName?.lowercase()) {
            null, "utf-8", "utf8" -> Charsets.UTF_8
            "gbk", "gb2312", "gb18030" -> runCatching { Charset.forName("GBK") }.getOrDefault(Charsets.UTF_8)
            else -> runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
        }
        return String(bytes, charset)
    }

    private fun findDocument(uri: Uri): DocumentFile? {
        for (tree in getSavedFolderUris()) {
            val root = DocumentFile.fromTreeUri(context, tree) ?: continue
            if (root.uri == uri) return root
            findIn(root, uri)?.let { return it }
        }
        return DocumentFile.fromTreeUri(context, uri) ?: DocumentFile.fromSingleUri(context, uri)
    }

    private fun findIn(dir: DocumentFile, target: Uri): DocumentFile? {
        val children = try {
            dir.listFiles()
        } catch (_: Exception) {
            return null
        }
        for (child in children) {
            if (child.uri == target) return child
            if (child.isDirectory) {
                findIn(child, target)?.let { return it }
            }
        }
        return null
    }

    data class Library(
        val folders: List<MediaItem>,
        val files: List<MediaItem>,
        val volumes: List<VolumeInfo> = emptyList(),
        val volumeRoots: Map<String, List<MediaItem>> = emptyMap(),
    )

    data class TextLoadResult(
        val content: String,
        val truncated: Boolean,
        val error: String? = null,
    )

    companion object {
        const val TEXT_MAX_BYTES = 512 * 1024
        private const val PREFS_NAME = "media_center"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_FOLDER_URIS = "folder_uris"
        private const val MAX_SAF_FILES = 2000
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "3gp", "avi", "mov")
        private val WEB_EXT = setOf("html", "htm", "xhtml", "mhtml", "shtml", "mht")
        private val TEXT_EXT = setOf("txt", "md", "json", "xml", "log", "csv")

        fun isWebPage(name: String, mime: String?): Boolean {
            val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            val mimeType = mime?.lowercase().orEmpty()
            return mimeType == "text/html" ||
                mimeType == "application/xhtml+xml" ||
                mimeType.contains("html") ||
                ext in WEB_EXT
        }
    }
}
