package com.mediacenter.app

import android.app.Application
import com.mediacenter.app.data.MediaRepository

class MediaCenterApp : Application() {

    lateinit var repository: MediaRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = MediaRepository(this)
    }
}
