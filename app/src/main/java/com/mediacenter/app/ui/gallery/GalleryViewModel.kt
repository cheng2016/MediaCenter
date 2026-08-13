package com.mediacenter.app.ui.gallery

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mediacenter.app.MediaCenterApp
import com.mediacenter.app.R
import com.mediacenter.app.data.MediaRepository
import com.mediacenter.app.data.VolumeInfo
import com.mediacenter.app.data.model.MediaFilter
import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.data.model.MediaType
import com.mediacenter.app.data.model.SortMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as MediaCenterApp).repository

    private var library = MediaRepository.Library(emptyList(), emptyList())
    private val folderStack = ArrayList<MediaItem>()
    private val safChildren = HashMap<String, List<MediaItem>>()
    private var selectedVolumeId: String? = null
    private var sortMode: SortMode = SortMode.DATE
    private var forceListMode: Boolean? = null

    private val _uiState = MutableStateFlow(GalleryUiState(loading = true, title = "内部存储"))
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    private val _usbAccessEvent = MutableSharedFlow<VolumeInfo>(extraBufferCapacity = 1)
    val usbAccessEvent: SharedFlow<VolumeInfo> = _usbAccessEvent.asSharedFlow()

    fun onPermissionNeeded() {
        _uiState.update { it.copy(permissionNeeded = true, loading = false) }
    }

    fun onPermissionGranted() {
        _uiState.update { it.copy(permissionNeeded = false) }
        refresh()
    }

    fun onNavClick(item: NavDestination) {
        folderStack.clear()
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
            folderStack.add(folder)
            safChildren[folder.id] = repository.listFolderContents(folder)
            publish()
        }
    }

    fun closeFolder(): Boolean {
        if (folderStack.isEmpty()) return false
        folderStack.removeAt(folderStack.lastIndex)
        publish()
        return true
    }

    fun openItem(item: MediaItem) {
        lastOpenedItemId = item.id
        if (item.type == MediaType.IMAGE) {
            repository.lastOpenedImages = visibleItems().filter { it.type == MediaType.IMAGE }
        }
    }

    fun setSortMode(mode: SortMode) {
        sortMode = mode
        publish()
    }

    fun toggleListMode() {
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
        val items = sortItems(visibleItems(filter, folder))
        val showingFolders = filter == MediaFilter.ALL && folder == null
        val autoList = showingFolders || folder != null ||
            filter == MediaFilter.WEB || filter == MediaFilter.TEXT
        val volume = selectedVolumeId?.let { id -> library.volumes.firstOrNull { it.id == id } }
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
                listMode = forceListMode ?: autoList,
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
            )
        }
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
            filter == MediaFilter.ALL && folder != null -> folderContents(folder)
            filter == MediaFilter.IMAGE -> library.files.filter { it.type == MediaType.IMAGE }
            filter == MediaFilter.VIDEO -> library.files.filter { it.type == MediaType.VIDEO }
            filter == MediaFilter.WEB -> library.files.filter { it.type == MediaType.WEB }
            filter == MediaFilter.TEXT -> library.files.filter { it.type == MediaType.TEXT }
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

    private fun folderContents(folder: MediaItem): List<MediaItem> {
        safChildren[folder.id]?.let { return it }
        return when {
            folder.volumeId != null -> library.volumeRoots[folder.volumeId].orEmpty()
            else -> repository.albumItems(library.files, folder.bucketId)
        }
    }

    private fun defaultTitle(filter: MediaFilter, volume: VolumeInfo?): String {
        if (volume != null && filter == MediaFilter.ALL) return volume.name
        return when (filter) {
            MediaFilter.ALL -> "内部存储"
            MediaFilter.IMAGE -> "图片"
            MediaFilter.VIDEO -> "视频"
            MediaFilter.WEB -> "网页"
            MediaFilter.TEXT -> "文本"
        }
    }

    private fun selectedNavId(filter: MediaFilter): String {
        selectedVolumeId?.let { return "volume-$it" }
        return when (filter) {
            MediaFilter.ALL -> NavDestination.STORAGE_ID
            MediaFilter.IMAGE -> NavDestination.IMAGE_ID
            MediaFilter.VIDEO -> NavDestination.VIDEO_ID
            MediaFilter.WEB -> NavDestination.WEB_ID
            MediaFilter.TEXT -> NavDestination.TEXT_ID
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
            else -> ""
        }
    }
}
