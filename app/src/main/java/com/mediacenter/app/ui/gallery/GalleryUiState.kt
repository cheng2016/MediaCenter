package com.mediacenter.app.ui.gallery

import com.mediacenter.app.data.VolumeInfo
import com.mediacenter.app.data.model.MediaFilter
import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.data.model.SortMode

data class GalleryUiState(
    val items: List<MediaItem> = emptyList(),
    val filter: MediaFilter = MediaFilter.ALL,
    val currentFolder: MediaItem? = null,
    val title: String = "",
    val subtitle: String = "",
    val showingFolders: Boolean = true,
    val listMode: Boolean = true,
    val sortMode: SortMode = SortMode.DATE,
    val canGoBack: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val permissionNeeded: Boolean = false,
    val usbNeedsAccess: Boolean = false,
    val selectedVolumeId: String? = null,
    val navItems: List<NavDestination> = emptyList(),
    val selectedNavId: String = NavDestination.STORAGE_ID,
    val volumes: List<VolumeInfo> = emptyList(),
)
