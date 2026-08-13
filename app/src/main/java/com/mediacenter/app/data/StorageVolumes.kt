package com.mediacenter.app.data

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import java.io.File

data class VolumeInfo(
    val id: String,
    val name: String,
    val uuid: String?,
    val isPrimary: Boolean,
    val isRemovable: Boolean,
    val mediaStoreName: String?,
    val directoryPath: String?,
    val hasAccess: Boolean,
)

object StorageVolumes {

    fun list(context: Context, canReadPath: (String) -> Boolean): List<VolumeInfo> {
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        val mediaNames = mediaStoreVolumeNames(context)
        return manager.storageVolumes
            .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
            .map { volume ->
                val uuid = volume.uuid
                val id = when {
                    volume.isPrimary -> PRIMARY_ID
                    !uuid.isNullOrBlank() -> uuid
                    else -> volume.getDescription(context).ifBlank { "volume-${volume.hashCode()}" }
                }
                val directory = directoryOf(volume)
                val mediaName = mediaStoreNameOf(volume, mediaNames)
                VolumeInfo(
                    id = id,
                    name = if (volume.isPrimary) {
                        "内部存储"
                    } else {
                        volume.getDescription(context).ifBlank { "U盘" }
                    },
                    uuid = uuid,
                    isPrimary = volume.isPrimary,
                    isRemovable = volume.isRemovable,
                    mediaStoreName = mediaName,
                    directoryPath = directory?.absolutePath,
                    hasAccess = directory != null && canReadPath(directory.absolutePath),
                )
            }
    }

    fun openTreeIntent(context: Context, volumeId: String): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val manager = context.getSystemService(StorageManager::class.java) ?: return null
        val volume = manager.storageVolumes.firstOrNull { matches(it, volumeId) } ?: return null
        return volume.createOpenDocumentTreeIntent()
    }

    private fun matches(volume: StorageVolume, volumeId: String): Boolean {
        return if (volumeId == PRIMARY_ID) {
            volume.isPrimary
        } else {
            volume.uuid == volumeId
        }
    }

    private fun directoryOf(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory?.let { return it }
        }
        if (volume.isPrimary) {
            return Environment.getExternalStorageDirectory()
        }
        val uuid = volume.uuid ?: return null
        val candidate = File("/storage/$uuid")
        return candidate.takeIf { it.exists() }
    }

    private fun mediaStoreNameOf(volume: StorageVolume, names: Set<String>): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.mediaStoreVolumeName?.let { return it }
        }
        if (volume.isPrimary) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.VOLUME_EXTERNAL_PRIMARY
            } else {
                null
            }
        }
        val uuid = volume.uuid?.lowercase() ?: return null
        return names.firstOrNull { it.equals(uuid, ignoreCase = true) }
    }

    private fun mediaStoreVolumeNames(context: Context): Set<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(context)
        } else {
            emptySet()
        }
    }

    const val PRIMARY_ID = "primary"
}
