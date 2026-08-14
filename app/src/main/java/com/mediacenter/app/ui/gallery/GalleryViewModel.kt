package com.mediacenter.app.ui.gallery

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mediacenter.app.MediaCenterApp
import com.mediacenter.app.R
import com.mediacenter.app.data.CreateParent
import com.mediacenter.app.data.MediaRepository
import com.mediacenter.app.data.VolumeInfo
import com.mediacenter.app.data.model.MediaFilter
import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.data.model.MediaType
import com.mediacenter.app.data.model.SortMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as MediaCenterApp).repository
    private val recentStore = (application as MediaCenterApp).recentStore
    private val favoriteStore = (application as MediaCenterApp).favoriteStore

    private var library = MediaRepository.Library(emptyList(), emptyList())
    private val folderStack = ArrayList<MediaItem>()
    private val safChildren = HashMap<String, List<MediaItem>>()
    private var selectedVolumeId: String? = null
    private var sortMode: SortMode = SortMode.DATE
    private var forceListMode: Boolean? = null
    private var searchQuery: String = ""

    private val _uiState = MutableStateFlow(GalleryUiState(loading = true, title = "内部存储"))
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    private val _usbAccessEvent = MutableSharedFlow<VolumeInfo>(extraBufferCapacity = 1)
    val usbAccessEvent: SharedFlow<VolumeInfo> = _usbAccessEvent.asSharedFlow()

    private val _messageEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messageEvent: SharedFlow<String> = _messageEvent.asSharedFlow()

    private val _openCreatedEvent = MutableSharedFlow<MediaItem>(extraBufferCapacity = 1)
    val openCreatedEvent: SharedFlow<MediaItem> = _openCreatedEvent.asSharedFlow()

    fun onPermissionNeeded() {
        _uiState.update { it.copy(permissionNeeded = true, loading = false) }
    }

    fun onPermissionGranted() {
        _uiState.update { it.copy(permissionNeeded = false) }
        refresh()
    }

    fun onNavClick(item: NavDestination) {
        folderStack.clear()
        searchQuery = ""
        if (item.volumeId != null) {
            selectedVolumeId = item.volumeId
            requestUsbAccessIfNeeded(item.volumeId)
            publish(MediaFilter.ALL)
            return
        }
        selectedVolumeId = null
        publish(item.filter ?: MediaFilter.ALL)
    }

    fun setFilter(filter: MediaFilter) {
        selectedVolumeId = null
        folderStack.clear()
        publish(filter = filter)
    }

    private var lastOpenedItemId: String? = null

    fun consumeLastOpenedItemId(): String? {
        val id = lastOpenedItemId
        lastOpenedItemId = null
        return id
    }

    fun refresh() {
        viewModelScope.launch {
            if (_uiState.value.items.isEmpty()) {
                _uiState.update { it.copy(loading = true, error = null) }
            }
            runCatching { repository.loadLibrary() }
                .onSuccess { loaded ->
                    library = loaded
                    reloadOpenFolders()
                    publish()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(loading = false, error = error.message ?: "加载失败")
                    }
                }
        }
    }

    fun addFolder(uri: Uri) {
        repository.saveFolderUri(uri)
        refresh()
    }

    fun addVolumeAccess(volumeId: String, uri: Uri) {
        repository.saveVolumeTree(volumeId, uri)
        refresh()
    }

    fun openFolder(folder: MediaItem) {
        if (folder.isVolumeRoot && folder.volumeId != null) {
            onNavClick(
                NavDestination(
                    id = "volume-${folder.volumeId}",
                    title = folder.name,
                    iconRes = R.drawable.ic_usb,
                    volumeId = folder.volumeId,
                ),
            )
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            folderStack.add(folder)
            searchQuery = ""
            runCatching { repository.listFolderContents(folder) }
                .onSuccess { safChildren[folder.id] = it }
                .onFailure { error ->
                    if (folderStack.isNotEmpty()) folderStack.removeAt(folderStack.lastIndex)
                    _messageEvent.tryEmit(error.message ?: "无法打开文件夹")
                }
            publish()
        }
    }

    fun closeFolder(): Boolean {
        if (folderStack.isEmpty()) return false
        folderStack.removeAt(folderStack.lastIndex)
        searchQuery = ""
        publish()
        return true
    }

    fun openItem(item: MediaItem) {
        lastOpenedItemId = item.id
        recentStore.add(item)
        if (item.type == MediaType.IMAGE) {
            repository.lastOpenedImages = visibleItems().filter { it.type == MediaType.IMAGE }
        }
        if (item.type == MediaType.AUDIO) {
            val playlist = visibleItems().filter { it.type == MediaType.AUDIO && !it.isMissing }
            repository.lastOpenedAudio = playlist.ifEmpty { listOf(item) }
        }
        if (_uiState.value.filter == MediaFilter.RECENT) {
            publish()
        }
    }

    fun dismissMissing(item: MediaItem) {
        recentStore.remove(item)
        favoriteStore.remove(item)
        publish()
    }

    fun canModify(item: MediaItem): Boolean = repository.canModify(item)

    fun deleteItem(item: MediaItem) {
        viewModelScope.launch {
            repository.deleteItem(item)
                .onSuccess {
                    recentStore.remove(item)
                    favoriteStore.remove(item)
                    reloadAfterCreate()
                }
                .onFailure { _messageEvent.tryEmit(it.message ?: "无法删除") }
        }
    }

    fun renameItem(item: MediaItem, name: String) {
        viewModelScope.launch {
            repository.renameItem(item, name)
                .onSuccess { reloadAfterCreate() }
                .onFailure { _messageEvent.tryEmit(it.message ?: "无法重命名") }
        }
    }

    fun loadMoveDestinations(exclude: MediaItem, onReady: (List<MediaItem>) -> Unit) {
        val parent = writableParent() ?: run {
            onReady(emptyList())
            return
        }
        viewModelScope.launch {
            val folders = repository.listChildFolders(parent).filter { dest ->
                dest.id != exclude.id && dest.filePath != exclude.filePath
            }
            onReady(folders)
        }
    }

    fun moveItem(item: MediaItem, destFolder: MediaItem) {
        val dest = when {
            repository.canWriteDirectory(destFolder.filePath) -> CreateParent.Path(destFolder.filePath!!)
            destFolder.isSafFolder || destFolder.id.startsWith("saf-") -> CreateParent.Saf(destFolder.uri)
            else -> {
                _messageEvent.tryEmit("目标目录不可写")
                return
            }
        }
        viewModelScope.launch {
            repository.moveItem(item, dest)
                .onSuccess { reloadAfterCreate() }
                .onFailure { _messageEvent.tryEmit(it.message ?: "无法移动") }
        }
    }

    fun createFolder(name: String) {
        val parent = writableParent() ?: return
        viewModelScope.launch {
            repository.createFolder(parent, name)
                .onSuccess { reloadAfterCreate() }
                .onFailure { _messageEvent.tryEmit(it.message ?: "无法创建文件夹") }
        }
    }

    fun createTextFile(name: String) {
        val parent = writableParent() ?: return
        val trimmed = name.trim()
        val base = if (trimmed.endsWith(".txt", ignoreCase = true)) {
            trimmed.dropLast(4)
        } else {
            trimmed.substringBeforeLast('.', trimmed)
        }.ifBlank { "未命名" }
        viewModelScope.launch {
            repository.createTextFile(parent, "$base.txt", "txt", "")
                .onSuccess { item ->
                    reloadAfterCreate()
                    _openCreatedEvent.tryEmit(item)
                }
                .onFailure { _messageEvent.tryEmit(it.message ?: "无法创建文件") }
        }
    }

    private suspend fun reloadAfterCreate() {
        if (folderStack.isNotEmpty()) {
            val folder = folderStack.last()
            safChildren[folder.id] = repository.listFolderContents(folder)
            publish()
        } else {
            runCatching { repository.loadLibrary() }
                .onSuccess { loaded ->
                    library = loaded
                    publish()
                }
        }
    }

    fun toggleFavorite(item: MediaItem): Boolean {
        val added = favoriteStore.toggle(item)
        publish()
        return added
    }

    fun setSearchQuery(query: String) {
        if (searchQuery == query) return
        searchQuery = query
        if (_uiState.value.filter == MediaFilter.SEARCH || folderStack.isNotEmpty()) {
            publish()
        }
    }

    fun setSortMode(mode: SortMode) {
        sortMode = mode
        publish()
    }

    fun toggleListMode() {
        val filter = _uiState.value.filter
        if (filter != MediaFilter.IMAGE && filter != MediaFilter.VIDEO) return
        forceListMode = !_uiState.value.listMode
        publish()
    }

    fun requestUsbAccess() {
        val id = selectedVolumeId ?: return
        requestUsbAccessIfNeeded(id, force = true)
    }

    fun volumeTreeIntent(volumeId: String): android.content.Intent? =
        repository.openVolumeTreeIntent(volumeId)

    private fun requestUsbAccessIfNeeded(volumeId: String, force: Boolean = false) {
        val volume = library.volumes.firstOrNull { it.id == volumeId } ?: return
        val children = library.volumeRoots[volumeId].orEmpty()
        if (force || (!volume.hasAccess && children.isEmpty() && repository.volumeTreeUri(volumeId) == null)) {
            _usbAccessEvent.tryEmit(volume)
        }
    }

    private fun publish(filter: MediaFilter = _uiState.value.filter) {
        val folder = folderStack.lastOrNull()
        val rawItems = sortItems(visibleItems(filter, folder))
        val showingFolders = filter == MediaFilter.ALL && folder == null
        val mediaTimeline = filter == MediaFilter.IMAGE || filter == MediaFilter.VIDEO
        val listMode = if (mediaTimeline) forceListMode ?: false else true
        val volume = selectedVolumeId?.let { id -> library.volumes.firstOrNull { it.id == id } }
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                rawItems.map { item ->
                    val missing = (filter == MediaFilter.RECENT || filter == MediaFilter.FAVORITE) &&
                        !repository.exists(item)
                    item.copy(
                        isFavorite = item.type != MediaType.FOLDER && favoriteStore.contains(item),
                        isMissing = missing,
                    )
                }
            }
            val usbNeedsAccess = volume != null &&
                folder == null &&
                items.isEmpty() &&
                !volume.hasAccess &&
                repository.volumeTreeUri(volume.id) == null
            _uiState.update {
                it.copy(
                    items = items,
                    filter = filter,
                    currentFolder = folder,
                    title = folder?.name ?: defaultTitle(filter, volume),
                    subtitle = subtitle(filter, folder, items),
                    showingFolders = showingFolders,
                    listMode = listMode,
                    albumCards = false,
                    groupByDate = mediaTimeline && !listMode,
                    canToggleView = mediaTimeline,
                    sortMode = sortMode,
                    canGoBack = folder != null,
                    loading = false,
                    error = null,
                    permissionNeeded = false,
                    usbNeedsAccess = usbNeedsAccess,
                    selectedVolumeId = selectedVolumeId,
                    navItems = buildNavItems(),
                    selectedNavId = selectedNavId(filter),
                    volumes = library.volumes,
                    searchQuery = searchQuery,
                    showSearch = (filter == MediaFilter.SEARCH && folder == null) || folder != null,
                    searchHint = if (folder != null) {
                        getApplication<Application>().getString(R.string.search_hint_folder)
                    } else {
                        getApplication<Application>().getString(R.string.search_hint_indexed)
                    },
                    canCreate = writableParent(filter, folder) != null,
                )
            }
        }
    }

    private fun writableParent(
        filter: MediaFilter = _uiState.value.filter,
        folder: MediaItem? = folderStack.lastOrNull(),
    ): CreateParent? {
        if (filter != MediaFilter.ALL) return null
        if (folder != null) {
            if (repository.canWriteDirectory(folder.filePath)) {
                return CreateParent.Path(folder.filePath!!)
            }
            if (folder.isSafFolder || folder.id.startsWith("saf-")) {
                return CreateParent.Saf(folder.uri)
            }
            return null
        }
        val volume = selectedVolumeId?.let { id -> library.volumes.firstOrNull { it.id == id } }
            ?: library.volumes.firstOrNull { it.isPrimary }
        if (repository.canWriteDirectory(volume?.directoryPath)) {
            return CreateParent.Path(volume!!.directoryPath!!)
        }
        selectedVolumeId?.let { id ->
            repository.volumeTreeUri(id)?.let { return CreateParent.Saf(it) }
        }
        return null
    }

    private fun visibleItems(
        filter: MediaFilter = _uiState.value.filter,
        folder: MediaItem? = folderStack.lastOrNull(),
    ): List<MediaItem> {
        val volumeId = selectedVolumeId
        return when {
            filter == MediaFilter.ALL && folder == null && volumeId != null ->
                library.volumeRoots[volumeId].orEmpty()
            filter == MediaFilter.ALL && folder == null -> library.folders
            filter == MediaFilter.ALL && folder != null -> {
                val children = folderContents(folder)
                val query = searchQuery.trim()
                if (query.isEmpty()) children
                else children.filter { it.name.contains(query, ignoreCase = true) }
            }
            filter == MediaFilter.IMAGE && folder == null ->
                library.files.filter { it.type == MediaType.IMAGE }
            filter == MediaFilter.VIDEO && folder == null ->
                library.files.filter { it.type == MediaType.VIDEO }
            filter == MediaFilter.IMAGE && folder != null ->
                mediaFolderItems(folder, MediaType.IMAGE)
            filter == MediaFilter.VIDEO && folder != null ->
                mediaFolderItems(folder, MediaType.VIDEO)
            filter == MediaFilter.WEB -> library.files.filter { it.type == MediaType.WEB }
            filter == MediaFilter.TEXT -> library.files.filter { it.type == MediaType.TEXT }
            filter == MediaFilter.BOOK -> library.files.filter {
                it.type == MediaType.PDF || it.type == MediaType.BOOK
            }
            filter == MediaFilter.MUSIC -> library.files.filter { it.type == MediaType.AUDIO }
            filter == MediaFilter.ARCHIVE -> library.files.filter { it.type == MediaType.ARCHIVE }
            filter == MediaFilter.APK -> library.files.filter { it.type == MediaType.APK }
            filter == MediaFilter.RECENT -> recentStore.list()
            filter == MediaFilter.FAVORITE -> favoriteStore.list()
            filter == MediaFilter.SEARCH -> {
                val query = searchQuery.trim()
                if (query.isEmpty()) emptyList()
                else library.files.filter { it.name.contains(query, ignoreCase = true) }
            }
            else -> emptyList()
        }
    }

    private fun sortItems(items: List<MediaItem>): List<MediaItem> {
        val foldersFirst = compareBy<MediaItem> { it.type != MediaType.FOLDER }
        return when (sortMode) {
            SortMode.NAME -> items.sortedWith(foldersFirst.thenBy { it.name.lowercase() })
            SortMode.DATE -> items.sortedWith(foldersFirst.thenByDescending { it.dateModified })
            SortMode.TYPE -> items.sortedWith(foldersFirst.thenBy { it.type.name }.thenBy { it.name.lowercase() })
        }
    }

    private suspend fun reloadOpenFolders() {
        val next = HashMap<String, List<MediaItem>>()
        for (folder in folderStack) {
            next[folder.id] = repository.listFolderContents(folder)
        }
        safChildren.clear()
        safChildren.putAll(next)
    }

    private fun mediaFolderItems(folder: MediaItem, type: MediaType): List<MediaItem> {
        val children = folderContents(folder).filter { it.type == type || it.type == MediaType.FOLDER }
        val query = searchQuery.trim()
        return if (query.isEmpty()) children
        else children.filter { it.name.contains(query, ignoreCase = true) }
    }

    private fun folderContents(folder: MediaItem): List<MediaItem> {
        if (folder.id.startsWith("album-")) {
            val fromLibrary = when {
                folder.bucketId != null -> repository.albumItems(library.files, folder.bucketId)
                folder.filePath != null -> library.files.filter { file ->
                    file.filePath?.let { path ->
                        val parent = java.io.File(path).let { if (it.isFile) it.parent else it.absolutePath }
                        parent == folder.filePath
                    } == true
                }
                else -> emptyList()
            }
            if (fromLibrary.isNotEmpty()) return fromLibrary
        }
        safChildren[folder.id]?.takeIf { it.isNotEmpty() }?.let { return it }
        return when {
            folder.volumeId != null -> library.volumeRoots[folder.volumeId].orEmpty()
            else -> repository.albumItems(library.files, folder.bucketId)
        }
    }

    private fun defaultTitle(filter: MediaFilter, volume: VolumeInfo?): String {
        if (volume != null && filter == MediaFilter.ALL) return volume.name
        return when (filter) {
            MediaFilter.ALL -> "内部存储"
            MediaFilter.RECENT -> "最近"
            MediaFilter.FAVORITE -> "收藏"
            MediaFilter.SEARCH -> "搜索"
            MediaFilter.IMAGE -> "图片"
            MediaFilter.VIDEO -> "视频"
            MediaFilter.MUSIC -> "音乐"
            MediaFilter.WEB -> "网页"
            MediaFilter.TEXT -> "文本"
            MediaFilter.BOOK -> "电子书"
            MediaFilter.ARCHIVE -> "压缩包"
            MediaFilter.APK -> "安装包"
        }
    }

    private fun selectedNavId(filter: MediaFilter): String {
        selectedVolumeId?.let { return "volume-$it" }
        return when (filter) {
            MediaFilter.ALL -> NavDestination.STORAGE_ID
            MediaFilter.RECENT -> NavDestination.RECENT_ID
            MediaFilter.FAVORITE -> NavDestination.FAVORITE_ID
            MediaFilter.SEARCH -> NavDestination.SEARCH_ID
            MediaFilter.IMAGE -> NavDestination.IMAGE_ID
            MediaFilter.VIDEO -> NavDestination.VIDEO_ID
            MediaFilter.MUSIC -> NavDestination.MUSIC_ID
            MediaFilter.WEB -> NavDestination.WEB_ID
            MediaFilter.TEXT -> NavDestination.TEXT_ID
            MediaFilter.BOOK -> NavDestination.BOOK_ID
            MediaFilter.ARCHIVE -> NavDestination.ARCHIVE_ID
            MediaFilter.APK -> NavDestination.APK_ID
        }
    }

    private fun buildNavItems(): List<NavDestination> {
        val app = getApplication<Application>()
        val items = ArrayList<NavDestination>()
        items += NavDestination(
            id = NavDestination.STORAGE_ID,
            title = app.getString(R.string.nav_storage),
            iconRes = R.drawable.ic_folder_yellow,
            filter = MediaFilter.ALL,
        )
        for (volume in library.volumes.filter { !it.isPrimary }) {
            items += NavDestination(
                id = "volume-${volume.id}",
                title = volume.name,
                iconRes = R.drawable.ic_usb,
                volumeId = volume.id,
            )
        }
        items += NavDestination(
            id = NavDestination.RECENT_ID,
            title = app.getString(R.string.filter_recent),
            iconRes = R.drawable.ic_nav_recent,
            filter = MediaFilter.RECENT,
        )
        items += NavDestination(
            id = NavDestination.FAVORITE_ID,
            title = app.getString(R.string.filter_favorite),
            iconRes = R.drawable.ic_nav_favorite,
            filter = MediaFilter.FAVORITE,
        )
        items += NavDestination(
            id = NavDestination.SEARCH_ID,
            title = app.getString(R.string.filter_search),
            iconRes = R.drawable.ic_nav_search,
            filter = MediaFilter.SEARCH,
        )
        items += NavDestination(
            id = NavDestination.IMAGE_ID,
            title = app.getString(R.string.filter_image),
            iconRes = R.drawable.ic_nav_image,
            filter = MediaFilter.IMAGE,
        )
        items += NavDestination(
            id = NavDestination.VIDEO_ID,
            title = app.getString(R.string.filter_video),
            iconRes = R.drawable.ic_nav_video,
            filter = MediaFilter.VIDEO,
        )
        items += NavDestination(
            id = NavDestination.MUSIC_ID,
            title = app.getString(R.string.filter_music),
            iconRes = R.drawable.ic_nav_music,
            filter = MediaFilter.MUSIC,
        )
        items += NavDestination(
            id = NavDestination.WEB_ID,
            title = app.getString(R.string.filter_web),
            iconRes = R.drawable.ic_nav_web,
            filter = MediaFilter.WEB,
        )
        items += NavDestination(
            id = NavDestination.TEXT_ID,
            title = app.getString(R.string.filter_text),
            iconRes = R.drawable.ic_nav_text,
            filter = MediaFilter.TEXT,
        )
        items += NavDestination(
            id = NavDestination.BOOK_ID,
            title = app.getString(R.string.filter_book),
            iconRes = R.drawable.ic_nav_book,
            filter = MediaFilter.BOOK,
        )
        items += NavDestination(
            id = NavDestination.ARCHIVE_ID,
            title = app.getString(R.string.filter_archive),
            iconRes = R.drawable.ic_nav_archive,
            filter = MediaFilter.ARCHIVE,
        )
        items += NavDestination(
            id = NavDestination.APK_ID,
            title = app.getString(R.string.filter_apk),
            iconRes = R.drawable.ic_nav_apk,
            filter = MediaFilter.APK,
        )
        return items
    }

    private fun subtitle(filter: MediaFilter, folder: MediaItem?, items: List<MediaItem>): String {
        return when {
            folder != null -> "${items.size} 项"
            filter == MediaFilter.ALL -> "${items.size} 个文件夹"
            filter == MediaFilter.IMAGE -> "${items.size} 张图片"
            filter == MediaFilter.VIDEO -> "${items.size} 个视频"
            filter == MediaFilter.WEB -> "${items.size} 个网页"
            filter == MediaFilter.TEXT -> "${items.size} 个文本"
            filter == MediaFilter.BOOK -> "${items.size} 本电子书"
            filter == MediaFilter.MUSIC -> "${items.size} 首音乐"
            filter == MediaFilter.ARCHIVE -> "${items.size} 个压缩包"
            filter == MediaFilter.APK -> "${items.size} 个安装包"
            filter == MediaFilter.RECENT -> "${items.size} 个最近打开"
            filter == MediaFilter.FAVORITE -> "${items.size} 个收藏"
            filter == MediaFilter.SEARCH -> if (searchQuery.isBlank()) {
                "只搜已索引的文件"
            } else {
                "${items.size} 个结果 · 只搜已索引的文件"
            }
            else -> ""
        }
    }
}
