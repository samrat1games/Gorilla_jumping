package com.gorillajumping.game

import com.gorillajumping.math.Vec3

/**
 * Рука для физики. Сглаживание уже сделано так, как в PhoneXR (StableHand + переход рантайма),
 * поэтому здесь ничего не фильтруем — только отмечаем, когда рука появилась или пропала,
 * чтобы физика отпустила её без толчка.
 */
class HandInput {
    /** Позиция в пространстве трекинга. */
    var position = Vec3.ZERO
        private set

    /** Рука видна и ей можно доверять в физике. */
    var valid = false
        private set

    /** В этом кадре рука появилась или пропала: физика должна «отлипнуть» без прыжка. */
    var reset = true
        private set

    fun update(raw: Vec3?, tracked: Boolean) {
        val now = raw != null && tracked
        // Скачок трекинга (рука «перепрыгнула» за кадр) — отпускаем без толчка, иначе горилла улетит.
        val jumped = now && valid && raw != null && (raw - position).length() > MAX_STEP
        reset = now != valid || jumped
        valid = now
        if (raw != null) position = raw
    }

    private companion object {
        /** Больше этого за кадр (≈9 м/с при 72 к/с) настоящая рука не двигается. */
        const val MAX_STEP = 0.13f
    }
}
