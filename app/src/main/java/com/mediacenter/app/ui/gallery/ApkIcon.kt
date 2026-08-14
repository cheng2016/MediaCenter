package com.mediacenter.app.ui.gallery

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import com.mediacenter.app.MediaCenterApp
import java.io.File

data class ApkIcon(
    val path: String?,
    val uri: Uri,
)

class ApkIconModelLoader(
    private val context: Context,
) : ModelLoader<ApkIcon, Drawable> {

    override fun handles(model: ApkIcon): Boolean = true

    override fun buildLoadData(
        model: ApkIcon,
        width: Int,
        height: Int,
        options: Options,
    ): ModelLoader.LoadData<Drawable> {
        return ModelLoader.LoadData(ObjectKey("${model.uri}|${model.path}"), Fetcher(context, model))
    }

    class Factory(
        private val context: Context,
    ) : ModelLoaderFactory<ApkIcon, Drawable> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<ApkIcon, Drawable> {
            return ApkIconModelLoader(context)
        }

        override fun teardown() = Unit
    }

    private class Fetcher(
        private val context: Context,
        private val model: ApkIcon,
    ) : DataFetcher<Drawable> {

        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in Drawable>) {
            val icon = runCatching { decode() }.getOrNull()
            if (icon != null) {
                callback.onDataReady(icon)
            } else {
                callback.onLoadFailed(IllegalStateException("apk icon missing"))
            }
        }

        private fun decode(): Drawable? {
            val app = context.applicationContext as? MediaCenterApp
            val path = app?.repository?.resolveLocalPath(model.uri, model.path)
                ?: model.path?.takeIf { File(it).isFile }
                ?: return null
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(path, 0)
            } ?: return null
            val appInfo = info.applicationInfo ?: return null
            appInfo.sourceDir = path
            appInfo.publicSourceDir = path
            return appInfo.loadIcon(pm)
        }

        override fun cleanup() = Unit
        override fun cancel() = Unit
        override fun getDataClass(): Class<Drawable> = Drawable::class.java
        override fun getDataSource(): DataSource = DataSource.LOCAL
    }
}
