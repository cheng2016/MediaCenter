package com.mediacenter.app.ui.gallery

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.widget.EditText
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
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.mediacenter.app.R
import com.mediacenter.app.data.model.SortMode
import com.mediacenter.app.data.model.MediaFilter
import com.mediacenter.app.data.model.MediaItem
import com.mediacenter.app.data.model.MediaType
import com.mediacenter.app.databinding.FragmentGalleryBinding
import kotlinx.coroutines.launch

class GalleryFragment : Fragment() {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: GalleryViewModel by viewModels()
    private val adapter = MediaAdapter(::onItemClick, ::focusSidebar, ::showFileMenu)
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
            if (hasFocus && !binding.navList.isInTouchMode && binding.navList.focusedChild == null) {
                focusSidebar()
            }
        }
        binding.contentList.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !binding.contentList.isInTouchMode && binding.contentList.focusedChild == null) {
                focusContent()
            }
        }

        binding.buttonToggleSidebar.setOnClickListener { toggleSidebar() }
        binding.buttonBack.setOnClickListener { viewModel.closeFolder() }
        binding.buttonCreate.setOnClickListener { showCreateMenu() }
        binding.buttonAddFolder.setOnClickListener { folderLauncher.launch(null) }
        binding.buttonSort.setOnClickListener { showSortMenu() }
        binding.buttonEmptyAddFolder.setOnClickListener { viewModel.requestUsbAccess() }
        binding.buttonPermission.setOnClickListener { requestStorageAccess() }
        binding.searchInput.doAfterTextChanged { viewModel.setSearchQuery(it?.toString().orEmpty()) }

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
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.messageEvent.collect { message ->
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.openCreatedEvent.collect(::onItemClick)
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
        binding.buttonCreate.isVisible = state.canCreate
        binding.buttonBack.isVisible = state.canGoBack
        backCallback.isEnabled = state.canGoBack
        binding.searchInput.isVisible = state.showSearch
        if (state.showSearch) {
            binding.searchInput.hint = state.searchHint.ifBlank { getString(R.string.search_hint) }
        }
        if (state.showSearch && binding.searchInput.text.toString() != state.searchQuery) {
            binding.searchInput.setText(state.searchQuery)
            binding.searchInput.setSelection(state.searchQuery.length)
        }
        binding.buttonEmptyAddFolder.isVisible = state.usbNeedsAccess
        binding.buttonEmptyAddFolder.setText(R.string.action_open_usb)
        binding.buttonPermission.setText(R.string.permission_all_files)
        binding.emptyIcon.setImageResource(
            when {
                state.usbNeedsAccess -> R.drawable.ic_usb
                state.showingFolders -> R.drawable.ic_folder_yellow
                state.filter == MediaFilter.WEB -> R.drawable.ic_nav_web
                state.filter == MediaFilter.BOOK -> R.drawable.ic_nav_book
                state.filter == MediaFilter.MUSIC -> R.drawable.ic_nav_music
                state.filter == MediaFilter.ARCHIVE -> R.drawable.ic_nav_archive
                state.filter == MediaFilter.APK -> R.drawable.ic_nav_apk
                state.filter == MediaFilter.RECENT -> R.drawable.ic_nav_recent
                state.filter == MediaFilter.FAVORITE -> R.drawable.ic_nav_favorite
                state.filter == MediaFilter.SEARCH -> R.drawable.ic_nav_search
                else -> R.drawable.ic_nav_text
            },
        )
        binding.emptyText.text = when {
            state.error != null -> state.error
            state.usbNeedsAccess -> getString(R.string.empty_usb)
            state.filter == MediaFilter.WEB -> getString(R.string.empty_web)
            state.filter == MediaFilter.TEXT -> getString(R.string.empty_text)
            state.filter == MediaFilter.BOOK -> getString(R.string.empty_book)
            state.filter == MediaFilter.MUSIC -> getString(R.string.empty_music)
            state.filter == MediaFilter.ARCHIVE -> getString(R.string.empty_archive)
            state.filter == MediaFilter.APK -> getString(R.string.empty_apk)
            state.filter == MediaFilter.RECENT -> getString(R.string.empty_recent)
            state.filter == MediaFilter.FAVORITE -> getString(R.string.empty_favorite)
            state.filter == MediaFilter.SEARCH && state.searchQuery.isBlank() ->
                getString(R.string.empty_search)
            state.filter == MediaFilter.SEARCH -> getString(R.string.empty_search_none)
            state.currentFolder != null && state.searchQuery.isNotBlank() ->
                getString(R.string.empty_search_none)
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

    private fun showCreateMenu() {
        val popup = PopupMenu(requireContext(), binding.buttonCreate)
        popup.menuInflater.inflate(R.menu.menu_create, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.create_folder -> {
                    askName(getString(R.string.create_folder), getString(R.string.create_folder_default)) { name ->
                        viewModel.createFolder(name)
                    }
                    true
                }
                R.id.create_text -> {
                    askName(getString(R.string.create_text), getString(R.string.create_text_default)) { name ->
                        viewModel.createTextFile(name)
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun askName(title: String, defaultName: String, onConfirm: (String) -> Unit) {
        val input = EditText(requireContext()).apply {
            setText(defaultName)
            setSelection(0, defaultName.substringBeforeLast('.', defaultName).length)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.md_on_surface))
            setHintTextColor(ContextCompat.getColor(requireContext(), R.color.md_on_surface_variant))
            hint = getString(R.string.create_name_hint)
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(48, 32, 48, 16)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(input)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val name = input.text?.toString().orEmpty()
                if (name.isNotBlank()) onConfirm(name)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        input.requestFocus()
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
        if (!binding.navList.isInTouchMode) {
            binding.navList.post { focusSidebar() }
        }
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
            binding.searchInput.isVisible -> binding.searchInput.requestFocus()
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

    private fun showFileMenu(anchor: android.view.View, item: MediaItem) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_file, popup.menu)
        val canModify = viewModel.canModify(item)
        popup.menu.findItem(R.id.file_favorite).isVisible = item.type != MediaType.FOLDER && !item.isMissing
        popup.menu.findItem(R.id.file_favorite).title =
            if (item.isFavorite) getString(R.string.action_unfavorite) else getString(R.string.action_favorite)
        popup.menu.findItem(R.id.file_rename).isVisible = canModify
        popup.menu.findItem(R.id.file_move).isVisible = canModify && item.type != MediaType.FOLDER
        popup.menu.findItem(R.id.file_delete).isVisible = canModify
        popup.menu.findItem(R.id.file_remove_record).isVisible = item.isMissing
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.file_favorite -> {
                    val added = viewModel.toggleFavorite(item)
                    Toast.makeText(
                        requireContext(),
                        if (added) R.string.favorite_added else R.string.favorite_removed,
                        Toast.LENGTH_SHORT,
                    ).show()
                    true
                }
                R.id.file_rename -> {
                    askName(getString(R.string.action_rename), item.name) { name ->
                        viewModel.renameItem(item, name)
                    }
                    true
                }
                R.id.file_move -> {
                    pickMoveDestination(item)
                    true
                }
                R.id.file_delete -> {
                    confirmDelete(item)
                    true
                }
                R.id.file_remove_record -> {
                    viewModel.dismissMissing(item)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun confirmDelete(item: MediaItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.action_delete)
            .setMessage(getString(R.string.confirm_delete, item.name))
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.deleteItem(item) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun pickMoveDestination(item: MediaItem) {
        viewModel.loadMoveDestinations(item) { folders ->
            if (!isAdded) return@loadMoveDestinations
            if (folders.isEmpty()) {
                Toast.makeText(requireContext(), R.string.move_no_target, Toast.LENGTH_SHORT).show()
                return@loadMoveDestinations
            }
            val names = folders.map { it.name }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.action_move)
                .setItems(names) { _, which ->
                    viewModel.moveItem(item, folders[which])
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun onItemClick(item: MediaItem) {
        if (item.isMissing) {
            Toast.makeText(requireContext(), R.string.file_missing, Toast.LENGTH_SHORT).show()
            viewModel.dismissMissing(item)
            return
        }
        if (item.type == MediaType.FOLDER) {
            viewModel.openFolder(item)
            return
        }
        viewModel.openItem(item)
        val openType = MediaIntents.resolveType(item)
        if (openType == MediaType.APK) {
            val (intent, message) = MediaIntents.apkIntent(requireContext(), item)
            if (message != null) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            }
            try {
                startActivity(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(requireContext(), R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (openType == MediaType.ARCHIVE &&
            !com.mediacenter.app.data.MediaRepository.isZipArchive(item.name)
        ) {
            Toast.makeText(requireContext(), R.string.archive_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = MediaIntents.viewerIntent(requireContext(), item)
        if (intent == null) {
            Toast.makeText(requireContext(), R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        }
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
                Manifest.permission.READ_MEDIA_AUDIO,
            )
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.contentList.adapter = null
        binding.navList.adapter = null
        _binding = null
    }
}
