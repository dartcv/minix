package me.dartcv.minix.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocalAudioController(private val context: Context) {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var player: MediaPlayer? = null
    private var currentUri: String? = null
    private var prepared = false

    fun toggle(uriValue: String?) {
        if (uriValue.isNullOrBlank()) return
        val existing = player
        if (existing != null && currentUri == uriValue) {
            if (!prepared) return
            if (existing.isPlaying) {
                existing.pause()
                _isPlaying.value = false
            } else {
                existing.start()
                _isPlaying.value = true
            }
            return
        }

        releasePlayer()
        currentUri = uriValue
        runCatching {
            MediaPlayer().also { mediaPlayer ->
                player = mediaPlayer
                mediaPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build(),
                )
                mediaPlayer.setDataSource(context, uriValue.toUri())
                mediaPlayer.setOnPreparedListener {
                    prepared = true
                    it.start()
                    _isPlaying.value = true
                }
                mediaPlayer.setOnCompletionListener {
                    _isPlaying.value = false
                    it.seekTo(0)
                }
                mediaPlayer.setOnErrorListener { _, _, _ ->
                    releasePlayer()
                    true
                }
                mediaPlayer.prepareAsync()
            }
        }.onFailure {
            releasePlayer()
        }
    }

    fun stop() {
        player?.runCatching {
            if (isPlaying) stop()
        }
        releasePlayer()
    }

    fun release() {
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        currentUri = null
        prepared = false
        _isPlaying.value = false
    }
}
