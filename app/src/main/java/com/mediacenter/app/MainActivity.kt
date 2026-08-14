package com.mediacenter.app

import android.os.Bundle
import androidx.fragment.app.commit
import com.mediacenter.app.databinding.ActivityMainBinding
import com.mediacenter.app.ui.BaseActivity
import com.mediacenter.app.ui.gallery.GalleryFragment

class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.container, GalleryFragment())
            }
        }
    }
}
