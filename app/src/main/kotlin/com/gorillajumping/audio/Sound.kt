package com.gorillajumping.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Весь звук игры синтезируется: музыка бумбокса (бит + бас + аккорды), стук рук о поверхность,
 * звук осаливания. Один AudioTrack, микширование в своём потоке.
 */
class Sound {
    @Volatile var musicOn = false
    @Volatile var musicVolume = 0f
    @Volatile var musicPan = 0f // -1 слева, 1 справа

    private class Voice(val kind: Int, val volume: Float, val pan: Float) { var t = 0 }
    private val voices = ArrayList<Voice>()
    @Volatile private var running = true
    private val thread = Thread(::loop, "sound").apply { isDaemon = true }
    private var musicSample = 0L

    fun start() = thread.start()
    fun stop() { running = false }

    fun tap(volume: Float, pan: Float) = synchronized(voices) { if (voices.size < 12) voices += Voice(0, volume, pan) }
    fun tag() = synchronized(voices) { voices += Voice(1, 0.7f, 0f) }
    fun click() = synchronized(voices) { voices += Voice(2, 0.5f, 0f) }

    private fun loop() {
        val buf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(buf, 4096))
            .build()
        track.play()
        val frames = 512
        val out = ShortArray(frames * 2)
        while (running) {
            val active = synchronized(voices) { ArrayList(voices) }
            for (i in 0 until frames) {
                var l = 0f; var r = 0f
                if (musicOn) {
                    val m = music(musicSample++) * musicVolume
                    l += m * (1f - musicPan) * 0.5f; r += m * (1f + musicPan) * 0.5f
                }
                for (v in active) {
                    val s = sfx(v) * v.volume
                    l += s * (1f - v.pan) * 0.5f; r += s * (1f + v.pan) * 0.5f
                    v.t++
                }
                out[i * 2] = (l.coerceIn(-1f, 1f) * 32000).toInt().toShort()
                out[i * 2 + 1] = (r.coerceIn(-1f, 1f) * 32000).toInt().toShort()
            }
            synchronized(voices) { voices.removeAll { it.t > RATE / 2 } }
            track.write(out, 0, out.size)
        }
        track.stop(); track.release()
    }

    private val rnd = java.util.Random(1)

    private fun sfx(v: Voice): Float {
        val t = v.t.toFloat() / RATE
        return when (v.kind) {
            0 -> (sin(2 * PI * 140 * t).toFloat() * 0.8f + (rnd.nextFloat() - 0.5f) * 0.5f) * exp(-t * 35f)
            1 -> sin(2 * PI * (if (t < 0.12f) 660 else 440) * t).toFloat() * exp(-t * 6f) * 0.6f
            else -> sin(2 * PI * 1200 * t).toFloat() * exp(-t * 60f)
        }
    }

    /** 110 BPM, 4 такта по кругу. */
    private fun music(n: Long): Float {
        val beat = RATE * 60.0 / 110
        val pos = n / beat
        val b = pos.toInt()
        val inBeat = ((pos - b) * beat / RATE).toFloat()
        val eighth = ((pos * 2) % 1.0 * beat / 2 / RATE).toFloat()
        var s = 0f
        if (b % 2 == 0) s += sin(2 * PI * (50 + 90 * exp(-inBeat * 25f)) * inBeat).toFloat() * exp(-inBeat * 7f) * 0.8f
        if (b % 4 == 1 || b % 4 == 3) s += (rnd.nextFloat() - 0.5f) * exp(-inBeat * 18f) * 0.5f
        s += (rnd.nextFloat() - 0.5f) * exp(-eighth * 60f) * 0.15f
        val roots = floatArrayOf(55f, 55f, 43.65f, 49f)
        val root = roots[(b / 4) % 4]
        val t = n.toFloat() / RATE
        val bassEnv = exp(-eighth * 5f)
        s += (if (sin(2 * PI * root * t) > 0) 0.18f else -0.18f) * bassEnv
        for (k in floatArrayOf(4f, 5f, 6f)) s += sin(2 * PI * root * k * t).toFloat() * 0.04f * exp(-inBeat * 2f)
        return s * 0.6f
    }

    companion object { const val RATE = 44100 }
}
