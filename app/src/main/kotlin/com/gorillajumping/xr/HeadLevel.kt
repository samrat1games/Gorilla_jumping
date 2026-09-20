package com.gorillajumping.xr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.gorillajumping.math.Quat
import com.gorillajumping.math.Vec3
import kotlin.math.acos
import kotlin.math.exp

/**
 * Выравнивание по гравитации. Monado берёт «вперёд» из позы телефона при старте сессии: запустили
 * игру, держа телефон экраном вверх, — и «низ» становится центром. Датчик гравитации знает настоящий
 * верх; поворачиваем всю сцену так, чтобы верх из OpenXR совпал с ним. Наклон исправляется,
 * повороты головы по сторонам (рыскание) не трогаем.
 */
class HeadLevel(private val context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    /** Верх по датчику в системе экрана (x вправо, y вверх, z к глазам), не нормирован. */
    @Volatile private var up: Vec3? = null
    /** Сглаженный настоящий верх в пространстве трекинга OpenXR. */
    private var worldUp: Vec3? = null
    private var logNs = 0L

    /** Поворот, который надо применить ко всем позам кадра. */
    var correction = Quat.IDENTITY
        private set

    fun start() {
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) { Log.w(TAG, "нет датчика гравитации — выравнивания не будет"); return }
        sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() = sensors.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val (gx, gy, gz) = Triple(e.values[0], e.values[1], e.values[2])
        // Оси датчика — оси телефона в естественной (портретной) ориентации; переводим в оси экрана.
        // ROTATION_90: телефон повёрнут против часовой — его верх (+y) смотрит влево, правый край (+x) вверх.
        // Датчик на столе показывает +g вверх — это и есть «верх».
        @Suppress("DEPRECATION")
        up = when (context.getSystemService(WindowManager::class.java).defaultDisplay.rotation) {
            Surface.ROTATION_90 -> Vec3(-gy, gx, gz)
            Surface.ROTATION_180 -> Vec3(-gx, -gy, gz)
            Surface.ROTATION_270 -> Vec3(gy, -gx, gz)
            else -> Vec3(gx, gy, gz)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    /** Раз в кадр: [head] — ориентация головы из OpenXR (до выравнивания), [dt] — шаг, с. */
    fun update(head: Quat, dt: Float) {
        val u = up?.takeIf { it.length() > 1f } ?: return
        // Где настоящий верх в пространстве трекинга. Смещение Monado постоянно, поэтому эта точка почти
        // не двигается, когда вертишь головой; сглаживаем ~1 с, чтобы толчки при прыжках не качали мир.
        val sample = head.rotate(u.normalized())
        val k = 1f - exp(-dt / SMOOTH_S)
        val w = worldUp?.let { it.lerp(sample, k).normalized() } ?: sample
        worldUp = w
        correction = fromTo(w, Vec3.UP)
        val now = System.nanoTime()
        if (now - logNs > 5_000_000_000L) {
            logNs = now
            Log.i(TAG, "наклон рантайма относительно гравитации %.1f°".format(Math.toDegrees(acos((w dot Vec3.UP).coerceIn(-1f, 1f)).toDouble())))
        }
    }

    /** Кратчайший поворот от [a] к [b] (оба единичные). Ось горизонтальна, если [b] — вертикаль: рыскание не меняется. */
    private fun fromTo(a: Vec3, b: Vec3): Quat {
        val c = (a dot b).coerceIn(-1f, 1f)
        if (c > .99999f) return Quat.IDENTITY
        val axis = (a cross b).takeIf { it.length() > 1e-6f }?.normalized() ?: Vec3(1f, 0f, 0f)
        return Quat.axisAngle(axis, acos(c))
    }

    private companion object {
        const val TAG = "GorillaXR"
        const val SMOOTH_S = 1f
    }
}
