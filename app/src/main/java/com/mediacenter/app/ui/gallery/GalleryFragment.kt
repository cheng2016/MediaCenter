package com.mediacenter.app.ui.gallery

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.mediacenter.app.R
import com.mediacenter.app.data.MediaRepository
import com.mediacenter.app.data.model.SortMode
import com.mediacenter.app.data.model.MediaFilter
import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.data.model.MediaType
import com.mediacenter.app.databinding.FragmentGalleryBinding
import com.mediacenter.app.ui.image.ImageViewerActivity
import com.mediacenter.app.ui.text.TextViewerActivity
import com.mediacenter.app.ui.video.VideoPlayerActivity
import com.mediacenter.app.ui.web.WebViewerActivity
import kotlinx.coroutines.launch

class GalleryFragment : Fragment() {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: GalleryViewModel by viewModels()
    private val adapter = MediaAdapter(::onItemClick, ::focusSidebar)
    private val navAdapter = NavAdapter(::onNavSelected, ::focusContent)
    private var requestedInitialFocus = false
    private val listLayout by lazy { LinearLayoutManager(requireContext()) }
    private val gridLayout by lazy { GridLayoutManager(requireContext(), 3) }
    private var pendingVolumeId: String? = null

    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            viewModel.refresh()
        }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (!viewModel.closeFolder()) {
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            viewModel.onPermissionGranted()
        } else {
            viewModel.onPermissionNeeded()
        }
    }

    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) persistAndAdd(uri, pendingVolumeId)
    }

    private val volumeTreeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        persistAndAdd(uri, pendingVolumeId)
    }

    private val allFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (hasAllFilesAccess() || hasMediaPermission()) {
            viewModel.onPermissionGranted()
        } else {
            viewModel.onPermissionNeeded()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        binding.navList.layoutManager = LinearLayoutManager(requireContext())
        binding.navList.adapter = navAdapter
        binding.navList.itemAnimator = null
        binding.contentList.layoutManager = listLayout
        binding.contentList.adapter = adapter
        binding.contentList.itemAnimator = null
        binding.navList.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && binding.navList.focusedChild == null) focusSidebar()
        }
        binding.contentList.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && binding.contentList.focusedChild == null) focusContent()
        }

        binding.buttonToggleSidebar.setOnClickListener { toggleSidebar() }
        binding.buttonBack.setOnClickListener { viewModel.closeFolder() }
        binding.buttonAddFolder.setOnClickListener { folderLauncher.launch(null) }
        binding.buttonSort.setOnClickListener { showSortMenu() }
        binding.buttonEmptyAddFolder.setOnClickListener { viewModel.requestUsbAccess() }
        binding.buttonPermission.setOnClickListener { requestStorageAccess() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.usbAccessEvent.collect(::openVolumeAccess)
            }
        }

        if (hasAllFilesAccess() || hasMediaPermission()) {
            if (viewModel.uiState.value.items.isEmpty()) {
                viewModel.refresh()
            }
        } else {
            viewModel.onPermissionNeeded()
            requestStorageAccess()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addDataScheme("file")
        }
        try {
            ContextCompat.registerReceiver(
                requireContext(),
                storageReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (_: Exception) {
            // Some devices reject this combination; refresh still runs on resume.
        }
    }

    override fun onStop() {
        try {
            requireContext().unregisterReceiver(storageReceiver)
        } catch (_: Exception) {
            // Not registered.
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        restoreFocusAfterViewer()
    }

    private fun render(state: GalleryUiState) {
        navAdapter.submit(state.navItems, state.selectedNavId)
        adapter.listMode = state.listMode
        adapter.submitList(state.items)
        if (!requestedInitialFocus && state.navItems.isNotEmpty()) {
            requestedInitialFocus = true
            binding.navList.post { focusSidebar() }
        }
        val manager = if (state.listMode) listLayout else gridLayout
        if (binding.contentList.layoutManager != manager) {
            binding.contentList.layoutManager = manager
        }
        binding.progress.isVisible = state.loading
        binding.contentList.isVisible = !state.loading && !state.permissionNeeded && state.items.isNotEmpty()
        binding.emptyGroup.isVisible = !state.loading && !state.permissionNeeded && state.items.isEmpty()
        binding.permissionGroup.isVisible = state.permissionNeeded
        binding.contentTitle.text = state.title.ifEmpty { getString(R.string.nav_storage) }
        binding.buttonBack.isVisible = state.canGoBack
        backCallback.isEnabled = state.canGoBack
        binding.buttonEmptyAddFolder.isVisible = state.usbNeedsAccess
        binding.buttonEmptyAddFolder.setText(R.string.action_open_usb)
        binding.buttonPermission.setText(R.string.permission_all_files)
        binding.emptyIcon.setImageResource(
            when {
                state.usbNeedsAccess -> R.drawable.ic_usb
                state.showingFolders -> R.drawable.ic_folder_yellow
                state.filter == MediaFilter.WEB -> R.drawable.ic_nav_web
                else -> R.drawable.ic_nav_text
            },
        )
        binding.emptyText.text = when {
            state.error != null -> state.error
            state.usbNeedsAccess -> getString(R.string.empty_usb)
            state.filter == MediaFilter.WEB -> getString(R.string.empty_web)
            state.filter == MediaFilter.TEXT -> getString(R.string.empty_text)
            state.showingFolders -> getString(R.string.empty_folders)
            else -> getString(R.string.empty_gallery)
        }
    }

    private fun openVolumeAccess(volume: com.mediacenter.app.data.VolumeInfo) {
        pendingVolumeId = volume.id
        val intent = viewModel.volumeTreeIntent(volume.id)
        if (intent != null) {
            volumeTreeLauncher.launch(intent)
        } else {
            folderLauncher.launch(null)
        }
    }

    private fun persistAndAdd(uri: Uri, volumeId: String?) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Session-only access is still usable.
        }
        if (volumeId != null) {
            viewModel.addVolumeAccess(volumeId, uri)
        } else {
            viewModel.addFolder(uri)
        }
        pendingVolumeId = null
    }

    private fun toggleSidebar() {
        val show = !binding.sidebar.isVisible
        binding.sidebar.isVisible = show
        binding.sidebarDivider.isVisible = show
    }

    private fun showSortMenu() {
        val state = viewModel.uiState.value
        val popup = PopupMenu(requireContext(), binding.buttonSort)
        popup.menuInflater.inflate(R.menu.menu_sort, popup.menu)
        popup.menu.findItem(R.id.sort_name).isChecked = state.sortMode == SortMode.NAME
        popup.menu.findItem(R.id.sort_date).isChecked = state.sortMode == SortMode.DATE
        popup.menu.findItem(R.id.sort_type).isChecked = state.sortMode == SortMode.TYPE
        popup.menu.findItem(R.id.sort_view_mode).title =
            if (state.listMode) getString(R.string.view_grid) else getString(R.string.view_list)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.sort_name -> viewModel.setSortMode(SortMode.NAME)
                R.id.sort_date -> viewModel.setSortMode(SortMode.DATE)
                R.id.sort_type -> viewModel.setSortMode(SortMode.TYPE)
                R.id.sort_view_mode -> viewModel.toggleListMode()
                R.id.sort_refresh -> ensurePermissionThen { viewModel.refresh() }
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun onNavSelected(item: NavDestination) {
        viewModel.onNavClick(item)
        binding.navList.post { focusSidebar() }
    }

    private fun focusSidebar() {
        if (_binding == null) return
        if (!binding.sidebar.isVisible) {
            binding.sidebar.isVisible = true
            binding.sidebarDivider.isVisible = true
        }
        Dpad.focusPosition(binding.navList, navAdapter.selectedIndex)
    }

    private fun focusContent() {
        if (_binding == null) return
        when {
            binding.contentList.isVisible && adapter.itemCount > 0 -> {
                Dpad.focusPosition(binding.contentList, 0)
            }
            binding.buttonEmptyAddFolder.isVisible -> binding.buttonEmptyAddFolder.requestFocus()
            binding.buttonPermission.isVisible -> binding.buttonPermission.requestFocus()
            binding.buttonSort.isVisible -> binding.buttonSort.requestFocus()
        }
    }

    private fun restoreFocusAfterViewer() {
        if (_binding == null) return
        val id = viewModel.consumeLastOpenedItemId() ?: return
        binding.contentList.post {
            if (_binding == null) return@post
            val index = adapter.currentList.indexOfFirst { it.id == id }
            if (index >= 0 && binding.contentList.isVisible) {
                Dpad.focusPosition(binding.contentList, index)
            }
        }
    }

    private fun onItemClick(item: MediaItem) {
        if (item.type == MediaType.FOLDER) {
            viewModel.openFolder(item)
            return
        }
        viewModel.openItem(item)
        val openType = if (MediaRepository.isWebPage(item.name, item.mimeType)) {
            MediaType.WEB
        } else {
            item.type
        }
        val intent = when (openType) {
            MediaType.IMAGE -> ImageViewerActivity.intent(requireContext(), item)
            MediaType.VIDEO -> VideoPlayerActivity.intent(requireContext(), item)
            MediaType.WEB -> WebViewerActivity.intent(requireContext(), item)
            MediaType.TEXT -> TextViewerActivity.intent(requireContext(), item)
            MediaType.FILE -> openGenericFile(item) ?: return
            MediaType.FOLDER -> return
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGenericFile(item: MediaItem): Intent? {
        val ext = item.name.substringAfterLast('.', "")
        val mime = item.mimeType
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
            ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(item.uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (intent.resolveActivity(requireContext().packageManager) == null) {
            Toast.makeText(requireContext(), R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
            return null
        }
        return intent
    }

    private fun ensurePermissionThen(onGranted: () -> Unit) {
        if (hasMediaPermission()) {
            onGranted()
        } else {
            viewModel.onPermissionNeeded()
        }
    }

    private fun hasMediaPermission(): Boolean {
        return mediaPermissions().any {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            hasMediaPermission()
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${requireContext().packageName}")
            }
            val launched = runCatching { allFilesLauncher.launch(appIntent) }.isSuccess
            if (!launched) {
                runCatching {
                    allFilesLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
            return
        }
        requestMediaPermissions()
    }

    private fun requestMediaPermissions() {
        permissionLauncher.launch(mediaPermissions())
    }

    private fun mediaPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.contentList.adapter = null
        binding.navList.adapter = null
        _binding = null
    }
}
