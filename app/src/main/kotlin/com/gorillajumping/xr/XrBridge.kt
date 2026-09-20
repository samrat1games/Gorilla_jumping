package com.gorillajumping.xr

import android.app.Activity
import com.gorillajumping.math.Quat
import com.gorillajumping.math.Vec3

object XrBridge {
    init { System.loadLibrary("gorillaxr") }

    external fun nativeInit(activity: Activity, display: Long, config: Long, context: Long): String?
    external fun nativeSwapchainInfo(): IntArray
    external fun nativePollEvents(): Int
    external fun nativeRequestExit()
    external fun nativeBeginFrame(out: FloatArray): Boolean
    external fun nativeAcquire(eye: Int): Int
    external fun nativeRelease(eye: Int)
    external fun nativeEndFrame(rendered: Boolean)
    external fun nativeDestroy()
}

class Eye(val position: Vec3, val orientation: Quat, val fov: FloatArray)

class XrHand(val valid: Boolean, val position: Vec3, val orientation: Quat, val squeeze: Float, val trigger: Float,
             val primary: Boolean, val secondary: Boolean, val stickX: Float, val stickY: Float)

/** Разбор массива кадра из xr_bridge.cpp. [level] поворачивает все позы (выравнивание по гравитации, см. [HeadLevel]). */
class XrFrame(a: FloatArray, private val level: Quat = Quat.IDENTITY) {
    private fun v(i: Int, a: FloatArray) = level.rotate(Vec3(a[i], a[i + 1], a[i + 2]))
    private fun q(i: Int, a: FloatArray) = level * Quat(a[i], a[i + 1], a[i + 2], a[i + 3])

    val headPosition = v(1, a)
    val headOrientation = q(4, a)
    val eyes = List(2) { e -> val o = 8 + e * 11; Eye(v(o, a), q(o + 3, a), a.copyOfRange(o + 7, o + 11)) }
    /** Через сколько секунд этот кадр появится на экране (к этому моменту предсказываем руки). */
    val displayAhead = if (a.size > 62) a[62] else 0.02f
    /** Период кадров дисплея, с. */
    val displayPeriod = if (a.size > 63 && a[63] > 0f) a[63] else 1f / 60f
    val hands = List(2) { h ->
        val o = 30 + h * 16
        XrHand(a[o] > 0.5f, v(o + 1, a), q(o + 4, a), a[o + 8], a[o + 9], a[o + 10] > 0.5f, a[o + 11] > 0.5f, a[o + 12], a[o + 13])
    }
}
