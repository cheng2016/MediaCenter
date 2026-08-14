package com.mediacenter.app.ui.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.SeekBar
import com.mediacenter.app.ui.gallery.Dpad
import androidx.activity.viewModels
import com.mediacenter.app.ui.BaseActivity
import androidx.media3.common.MediaItem
import android.widget.Toast
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.mediacenter.app.R
import com.mediacenter.app.data.model.MediaItem as AppMediaItem
import com.mediacenter.app.databinding.ActivityAudioPlayerBinding
import java.util.Locale
import java.util.concurrent.TimeUnit

class AudioPlayerActivity : BaseActivity() {

    private val viewModel: AudioPlayerViewModel by viewModels()
    private var player: ExoPlayer? = null
    private lateinit var binding: ActivityAudioPlayerBinding
    private val handler = Handler(Looper.getMainLooper())
    private var seeking = false

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 400L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.buttonPrev.setOnClickListener { playRelative(-1) }
        binding.buttonNext.setOnClickListener { playRelative(1) }
        binding.buttonPlay.setOnClickListener {
            val exo = player ?: return@setOnClickListener
            exo.playWhenReady = !exo.isPlaying
            bindPlayButton()
        }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seeking = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val exo = player
                val duration = exo?.duration ?: 0L
                if (exo != null && duration > 0L) {
                    exo.seekTo(duration * (seekBar?.progress ?: 0) / 1000L)
                }
                seeking = false
            }
        })
        bindCurrent()
        Dpad.requestFocusIfRemote(binding.buttonPlay)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val exo = player
        return when {
            (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) && exo != null -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> exo.pause()
                    KeyEvent.KEYCODE_MEDIA_PLAY -> exo.play()
                    else -> exo.playWhenReady = !exo.isPlaying
                }
                bindPlayButton()
                true
            }
            Dpad.isPrevious(keyCode) -> {
                playRelative(-1)
                true
            }
            Dpad.isNext(keyCode) -> {
                playRelative(1)
                true
            }
            Dpad.isSeekBack(keyCode) -> {
                seekBy(-10_000L)
                true
            }
            Dpad.isSeekForward(keyCode) -> {
                seekBy(10_000L)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0L, duration))
        updateProgress()
    }

    override fun onStart() {
        super.onStart()
        val current = viewModel.current ?: return
        val exo = ExoPlayer.Builder(this).build().also { player = it }
        exo.setMediaItems(
            viewModel.playlist.map { MediaItem.fromUri(it.uri) },
            viewModel.index,
            viewModel.resumePositionMs(current),
        )
        exo.prepare()
        exo.playWhenReady = true
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                viewModel.setIndex(exo.currentMediaItemIndex)
                bindCurrent()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                bindPlayButton()
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@AudioPlayerActivity, R.string.media_open_failed, Toast.LENGTH_SHORT).show()
            }
        })
        handler.post(ticker)
        bindPlayButton()
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        val exo = player
        viewModel.savePosition(exo?.currentPosition ?: 0L, exo?.duration ?: 0L)
        player?.release()
        player = null
        super.onStop()
    }

    private fun playRelative(delta: Int) {
        val exo = player ?: return
        viewModel.savePosition(exo.currentPosition, exo.duration)
        val next = viewModel.moveBy(delta) ?: return
        exo.seekTo(viewModel.index, viewModel.resumePositionMs(next))
        exo.playWhenReady = true
        bindCurrent()
        bindPlayButton()
    }

    private fun bindCurrent() {
        val item = viewModel.current ?: return
        binding.toolbar.title = getString(R.string.filter_music)
        binding.title.text = item.name.substringBeforeLast('.')
        binding.index.text = "${viewModel.index + 1} / ${viewModel.playlist.size}"
        Glide.with(binding.cover)
            .load(item.uri)
            .placeholder(R.drawable.ic_nav_music)
            .error(R.drawable.ic_nav_music)
            .centerCrop()
            .into(binding.cover)
    }

    private fun bindPlayButton() {
        val playing = player?.isPlaying == true
        binding.buttonPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.buttonPlay.contentDescription = getString(if (playing) R.string.audio_pause else R.string.audio_play)
    }

    private fun updateProgress() {
        val exo = player ?: return
        val duration = exo.duration.coerceAtLeast(0L)
        val position = exo.currentPosition.coerceAtLeast(0L)
        if (!seeking && duration > 0L) {
            binding.seekBar.progress = ((position * 1000L) / duration).toInt()
        }
        binding.position.text = formatTime(position)
        binding.duration.text = if (duration > 0L) formatTime(duration) else "--:--"
    }

    private fun formatTime(ms: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return String.format(Locale.CHINA, "%d:%02d", minutes, seconds)
    }

    companion object {
        fun intent(context: Context, item: AppMediaItem): Intent {
            return Intent(context, AudioPlayerActivity::class.java)
                .putExtra(AudioPlayerViewModel.EXTRA_URI, item.uri.toString())
                .putExtra(AudioPlayerViewModel.EXTRA_TITLE, item.name)
                .putExtra(AudioPlayerViewModel.EXTRA_FILE_PATH, item.filePath)
        }
    }
}
