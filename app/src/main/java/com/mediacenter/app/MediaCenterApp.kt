package com.mediacenter.app

import android.app.Application
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.mediacenter.app.data.AnnotationStore
import com.mediacenter.app.data.FavoriteStore
import com.mediacenter.app.data.MediaRepository
import com.mediacenter.app.data.ReadingProgressStore
import com.mediacenter.app.data.RecentStore
import com.mediacenter.app.ui.gallery.ApkIcon
import com.mediacenter.app.ui.gallery.ApkIconModelLoader

class MediaCenterApp : Application() {

    lateinit var repository: MediaRepository
        private set

    lateinit var progressStore: ReadingProgressStore
        private set

    lateinit var recentStore: RecentStore
        private set

    lateinit var favoriteStore: FavoriteStore
        private set

    lateinit var annotationStore: AnnotationStore
        private set

    override fun onCreate() {
        super.onCreate()
        repository = MediaRepository(this)
        progressStore = ReadingProgressStore(this)
        recentStore = RecentStore(this)
        favoriteStore = FavoriteStore(this)
        annotationStore = AnnotationStore(this)
        Glide.get(this).registry.prepend(
            ApkIcon::class.java,
            Drawable::class.java,
            ApkIconModelLoader.Factory(this),
        )
    }
}
