package com.mediacenter.app.ui.video

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.viewModels
import com.mediacenter.app.ui.BaseActivity
import android.widget.Toast
import com.mediacenter.app.ui.gallery.Dpad
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.mediacenter.app.R
import com.mediacenter.app.data.model.MediaItem as AppMediaItem
import com.mediacenter.app.databinding.ActivityVideoPlayerBinding

class VideoPlayerActivity : BaseActivity() {

    private val viewModel: VideoPlayerViewModel by viewModels()
    private var player: ExoPlayer? = null
    private lateinit var binding: ActivityVideoPlayerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.title = viewModel.title
        binding.toolbar.setNavigationOnClickListener { finish() }
        if (savedInstanceState != null) {
            viewModel.savePosition(savedInstanceState.getLong(KEY_POSITION, 0L))
        }
    }

    override fun onStart() {
        super.onStart()
        val uri = viewModel.uri ?: return
        val exo = ExoPlayer.Builder(this).build().also { player = it }
        binding.playerView.player = exo
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        val resumeAt = viewModel.position.value
        if (resumeAt > 0L) {
            exo.seekTo(resumeAt)
        }
        exo.playWhenReady = true
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@VideoPlayerActivity, R.string.media_open_failed, Toast.LENGTH_SHORT).show()
            }
        })
    }

    override fun onStop() {
        val exo = player
        viewModel.savePosition(exo?.currentPosition ?: 0L, exo?.duration ?: 0L)
        binding.playerView.player = null
        player?.release()
        player = null
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val exo = player
            when {
                Dpad.isPlayPause(event.keyCode) && exo != null -> {
                    if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) exo.pause()
                    else if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) exo.play()
                    else exo.playWhenReady = !exo.isPlaying
                    return true
                }
                event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || Dpad.isSeekBack(event.keyCode) -> {
                    seekBy(-10_000L)
                    return true
                }
                event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || Dpad.isSeekForward(event.keyCode) -> {
                    seekBy(10_000L)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0L, duration))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(KEY_POSITION, player?.currentPosition ?: viewModel.position.value)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val KEY_POSITION = "position"

        fun intent(context: Context, item: AppMediaItem): Intent {
            return Intent(context, VideoPlayerActivity::class.java)
                .putExtra(VideoPlayerViewModel.EXTRA_URI, item.uri.toString())
                .putExtra(VideoPlayerViewModel.EXTRA_TITLE, item.name)
                .putExtra(VideoPlayerViewModel.EXTRA_FILE_PATH, item.filePath)
        }
    }
}
