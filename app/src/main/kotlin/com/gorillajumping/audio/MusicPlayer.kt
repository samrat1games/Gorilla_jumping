package com.gorillajumping.audio

import android.content.Context
import android.media.MediaPlayer

/** Треки бумбокса из assets/music. Громкость и панорама — от положения бумбокса. */
class MusicPlayer(private val context: Context) {
    val tracks = listOf(
        "music/track1.mp3" to "NO BATIDÃO"
    )
    @Volatile var current = -1
        private set
    private var player: MediaPlayer? = null
    private var volume = 1f
    private var pan = 0f

    val playing get() = player?.isPlaying == true

    @Synchronized fun play(index: Int) {
        stop()
        val afd = context.assets.openFd(tracks[index].first)
        player = MediaPlayer().apply {
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            isLooping = true
            prepare()
            start()
        }
        current = index
        apply()
    }

    @Synchronized fun stop() {
        player?.run { runCatching { stop() }; release() }
        player = null
        current = -1
    }

    /** Тап по бумбоксу: пауза/продолжить (или первый трек, если ничего не играло). */
    @Synchronized fun toggle() {
        val p = player
        when {
            p == null -> play(0)
            p.isPlaying -> p.pause()
            else -> p.start()
        }
    }

    @Synchronized fun setSpatial(volume: Float, pan: Float) {
        this.volume = volume; this.pan = pan
        apply()
    }

    private fun apply() {
        player?.setVolume(volume * (1f - pan).coerceAtMost(1f), volume * (1f + pan).coerceAtMost(1f))
    }
}
