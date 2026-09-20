package com.gorillajumping.tracking

import android.os.SystemClock

/** Что игра видит об одной руке. Координаты кадра камеры 0..1, z: 0 — у камеры, 1 — далеко. */
class HandSnapshot(
    val present: Boolean,
    val x: Float,
    val y: Float,
    val z: Float,
    val aimX: Float,
    val aimY: Float,
    val pinch: Boolean,
    val pinchStrength: Float,
    val fist: Boolean,
    val index: Boolean,
    val thumb: Boolean,
    val palmToFace: Boolean,
)

/**
 * Одна рука (StableHand из PhoneXR): убирает дрожь точек One Euro-фильтром и держит жесты
 * достаточно долго, чтобы игра их прочитала. [update] зовёт поток детекции, [snapshot] — игровой цикл.
 */
class StableHand(private val restingX: Float) {
    private var x = restingX
    private var y = .5f
    private var z = .5f
    private var aimX = restingX
    private var aimY = .5f
    private val fx = HandGestures.OneEuro(minCutoff = .6f, beta = 1.4f, derivativeCutoff = 1f, deadZone = .002f)
    private val fy = HandGestures.OneEuro(minCutoff = .6f, beta = 1.4f, derivativeCutoff = 1f, deadZone = .002f)
    private val fz = HandGestures.OneEuro(minCutoff = .3f, beta = .6f, derivativeCutoff = 1f, deadZone = .004f)
    // Прицел фильтруем так же, как X/Y, иначе курсор дрожит.
    private val fax = HandGestures.OneEuro(minCutoff = .6f, beta = 1.4f, derivativeCutoff = 1f, deadZone = .002f)
    private val fay = HandGestures.OneEuro(minCutoff = .6f, beta = 1.4f, derivativeCutoff = 1f, deadZone = .002f)
    private var pinchStrength = 0f
    private var palmToFace = false
    private var lastSeenMs = 0L
    private var pinchUntilMs = 0L
    private var fistUntilMs = 0L
    private var indexUntilMs = 0L
    private var thumbUntilMs = 0L

    /** Рука найдена в кадре; [pinch] — уже после [HandGestures.PinchLatch]. */
    @Synchronized
    fun update(shape: HandGestures.Shape, pinch: Boolean) {
        val now = SystemClock.elapsedRealtime()
        // Давно не видели — сброс, иначе рука «прилетит» из старой точки.
        if (lastSeenMs == 0L || now - lastSeenMs > RESET_MS) { fx.reset(); fy.reset(); fz.reset(); fax.reset(); fay.reset() }
        val ns = now * 1_000_000L
        x = fx.filter(shape.x, ns)
        y = fy.filter(shape.y, ns)
        z = fz.filter(shape.z, ns)
        aimX = fax.filter(shape.aimX, ns)
        aimY = fay.filter(shape.aimY, ns)
        lastSeenMs = now
        pinchStrength = shape.pinchStrength
        palmToFace = shape.palmToFace
        if (pinch) pinchUntilMs = now + PINCH_HOLD_MS
        if (shape.fist) {
            fistUntilMs = now + FIST_HOLD_MS
            indexUntilMs = 0L
            thumbUntilMs = 0L
        } else {
            // Увидели открытую руку — ложный кулак сразу отпускаем.
            fistUntilMs = 0L
            if (shape.index) indexUntilMs = now + FINGER_HOLD_MS
            if (shape.thumb) thumbUntilMs = now + FINGER_HOLD_MS
        }
    }

    @Synchronized
    fun snapshot(): HandSnapshot {
        val now = SystemClock.elapsedRealtime()
        val present = lastSeenMs != 0L && now - lastSeenMs < PRESENT_MS
        if (!present) return HandSnapshot(false, restingX, .5f, .5f, restingX, .5f, false, 0f, false, false, false, false)
        return HandSnapshot(
            present = true,
            x = x, y = y, z = z,
            aimX = aimX, aimY = aimY,
            pinch = now < pinchUntilMs,
            pinchStrength = pinchStrength,
            fist = now < fistUntilMs,
            index = now < indexUntilMs,
            thumb = now < thumbUntilMs,
            palmToFace = palmToFace,
        )
    }

    private companion object {
        const val RESET_MS = 300L
        const val PRESENT_MS = 420L
        const val PINCH_HOLD_MS = 90L
        const val FIST_HOLD_MS = 110L
        const val FINGER_HOLD_MS = 150L
    }
}
