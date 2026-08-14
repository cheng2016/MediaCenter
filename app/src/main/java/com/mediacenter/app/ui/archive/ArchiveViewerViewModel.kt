package com.mediacenter.app.ui.archive

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import com.mediacenter.app.MediaCenterApp
import com.mediacenter.app.data.MediaRepository
import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.data.model.MediaType
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ArchiveViewerViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repository = (application as MediaCenterApp).repository
    val title: String = savedStateHandle.get<String>(EXTRA_TITLE).orEmpty()
    private val sourceUri: Uri? = savedStateHandle.get<String>(EXTRA_URI)?.let(Uri::parse)
    private val sourcePath: String? = savedStateHandle.get<String>(EXTRA_FILE_PATH)

    private val dirStack = ArrayList<String>()
    private var zipFile: File? = null

    suspend fun open(): String? = withContext(Dispatchers.IO) {
        if (!MediaRepository.isZipArchive(title)) {
            return@withContext "unsupported"
        }
        zipFile = resolveZipFile()
        val file = zipFile ?: return@withContext "failed"
        runCatching { ZipFile(file).use { it.size() } }.fold(
            onSuccess = { null },
            onFailure = { "failed" },
        )
    }

    fun canGoUp(): Boolean = dirStack.isNotEmpty()

    fun goUp(): Boolean {
        if (dirStack.isEmpty()) return false
        dirStack.removeAt(dirStack.lastIndex)
        return true
    }

    fun enter(folder: MediaItem) {
        val path = folder.filePath ?: return
        dirStack.add(path)
    }

    fun currentTitle(): String {
        return dirStack.lastOrNull()?.trimEnd('/')?.substringAfterLast('/') ?: title
    }

    suspend fun listEntries(): List<MediaItem> = withContext(Dispatchers.IO) {
        val file = zipFile ?: return@withContext emptyList()
        val prefix = currentPrefix()
        val folders = LinkedHashSet<String>()
        val files = ArrayList<MediaItem>()
        runCatching {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val original = entry.name
                    val raw = original.replace('\\', '/')
                    if (raw.startsWith("__MACOSX") || raw.startsWith(".")) continue
                    if (prefix.isNotEmpty() && !raw.startsWith(prefix)) continue
                    val rest = raw.removePrefix(prefix)
                    if (rest.isEmpty()) continue
                    val slash = rest.indexOf('/')
                    if (slash >= 0) {
                        val folderName = rest.substring(0, slash)
                        if (folderName.isNotEmpty()) folders += folderName
                        continue
                    }
                    if (entry.isDirectory) {
                        val name = rest.trimEnd('/')
                        if (name.isNotEmpty()) folders += name
                        continue
                    }
                    files += MediaItem(
                        id = "zip-file-$raw",
                        uri = sourceUri ?: Uri.EMPTY,
                        name = rest,
                        mimeType = null,
                        size = entry.size,
                        dateModified = entry.time,
                        type = repository.classify(rest, null),
                        filePath = original,
                    )
                }
            }
        }
        val folderItems = folders.filter { it.isNotEmpty() }.map { name ->
            MediaItem(
                id = "zip-dir-$prefix$name",
                uri = sourceUri ?: Uri.EMPTY,
                name = name,
                mimeType = null,
                size = 0L,
                dateModified = 0L,
                type = MediaType.FOLDER,
                filePath = "$prefix$name/",
            )
        }
        folderItems.sortedBy { it.name.lowercase() } + files.sortedBy { it.name.lowercase() }
    }

    suspend fun extract(item: MediaItem): MediaItem? = withContext(Dispatchers.IO) {
        val zip = zipFile ?: return@withContext null
        val entryPath = item.filePath ?: return@withContext null
        val root = File(
            getApplication<Application>().cacheDir,
            "archive-extract/${zip.nameWithoutExtension}",
        ).canonicalFile
        val dest = File(root, entryPath).canonicalFile
        if (dest != root && !dest.path.startsWith(root.path + File.separator)) {
            return@withContext null
        }
        dest.parentFile?.mkdirs()
        val copied = runCatching {
            ZipFile(zip).use { archive ->
                val entry = archive.getEntry(entryPath) ?: return@use false
                archive.getInputStream(entry).use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                true
            }
        }.getOrDefault(false)
        if (!copied || !dest.isFile) return@withContext null
        val uri = runCatching {
            FileProvider.getUriForFile(
                getApplication(),
                "${getApplication<Application>().packageName}.fileprovider",
                dest,
            )
        }.getOrElse { Uri.fromFile(dest) }
        item.copy(
            id = "extracted-${dest.absolutePath}",
            uri = uri,
            filePath = dest.absolutePath,
            type = repository.classify(item.name, null),
        )
    }

    private fun currentPrefix(): String = dirStack.lastOrNull().orEmpty()

    private fun resolveZipFile(): File? {
        sourcePath?.let { path ->
            val file = File(path)
            if (file.isFile && file.canRead()) return file
        }
        val uri = sourceUri ?: return null
        if (uri.scheme == "file") {
            uri.path?.let { path ->
                val file = File(path)
                if (file.isFile && file.canRead()) return file
            }
        }
        val cached = File(getApplication<Application>().cacheDir, "archives/${uri.hashCode()}.zip")
        if (cached.isFile && cached.length() > 0L) return cached
        cached.parentFile?.mkdirs()
        return runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                cached.outputStream().use { input.copyTo(it) }
            }
            cached.takeIf { it.isFile && it.length() > 0L }
        }.getOrNull()
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FILE_PATH = "file_path"
    }
}
