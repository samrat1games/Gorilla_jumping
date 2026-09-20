package com.gorillajumping.game

import com.gorillajumping.math.Quat
import com.gorillajumping.math.Vec3
import com.gorillajumping.physics.CollisionWorld
import com.gorillajumping.physics.Hit
import kotlin.math.max

/**
 * Локомоция Gorilla Tag один в один: перенос открытого GorillaLocomotion.Player (Another Axiom, MIT)
 * с числами из его префаба Gorilla Rig: история скоростей 8 кадров, рука до 1.5 м, радиус руки 0.05,
 * голова 0.15, velocityLimit 0.4, maxJumpSpeed 6.5, jumpMultiplier 1.1, скольжение 0.03, точность 0.995.
 *
 * Рука, коснувшаяся поверхности, «прилипает», и тело сдвигается на обратное движение руки; отпустил —
 * летишь со средней скоростью последних кадров × 1.1. Тело — Rigidbody Unity: гравитация 9.81,
 * без сопротивления воздуха, трение о землю как у материала по умолчанию (0.6).
 *
 * Сверх оригинала: скорость вверх ограничена MAX_JUMP_UP (чтобы не улетать высоко), рука, пропавшая
 * или прыгнувшая в трекинге, отпускается без толчка, и возврат на спавн, если вылетел за карту.
 */
class GorillaPlayer(private val world: CollisionWorld) {
    /** Где в мире находится начало пространства трекинга, и как оно повёрнуто. */
    var origin = Vec3.ZERO
    var yaw = 0f
        private set
    private var yawQ = Quat.IDENTITY

    var velocity = Vec3.ZERO
    var grounded = false
        private set

    var head = Vec3.ZERO
        private set
    private var lastHead = Vec3.ZERO

    val hands = arrayOf(HandState(), HandState())

    private val velocityHistory = Array(VELOCITY_HISTORY) { Vec3.ZERO }
    private var velocityIndex = 0
    private var velocityAverage = Vec3.ZERO
    private var lastPosition = Vec3.ZERO

    var spawnPoint = Vec3.ZERO
    var spawnYaw = 0f
    var boundsMin = Vec3(-1e6f, -1e6f, -1e6f)
    var boundsMax = Vec3(1e6f, 1e6f, 1e6f)

    /** Сколько раз пришлось вернуть игрока на спавн (для подсказки на экране). */
    var respawns = 0
        private set

    class HandState {
        /** Где рука «на самом деле» в мире с учётом прилипания — там её и рисуем (lastHandPosition). */
        var position = Vec3.ZERO
        /** Куда рука тянется по трекингу (CurrentHandPosition). */
        var target = Vec3.ZERO
        var touching = false
        var wasTouching = false
        var valid = false
        var justTouched = false
    }

    fun toWorld(tracking: Vec3) = origin + yawQ.rotate(tracking)

    fun worldRotation(q: Quat) = yawQ * q

    fun setYaw(value: Float) {
        yaw = value
        yawQ = Quat.yaw(value)
    }

    /** Поворот вокруг головы (стик Joy-Con): голова остаётся на месте. */
    fun turn(delta: Float, headTracking: Vec3) {
        val before = toWorld(headTracking)
        setYaw(yaw + delta)
        val after = toWorld(headTracking)
        origin += before - after
    }

    /** Ставит голову игрока в [headWorld]. */
    fun teleport(headWorld: Vec3, headTracking: Vec3, newYaw: Float) {
        setYaw(newYaw)
        origin = headWorld - yawQ.rotate(headTracking)
        head = headWorld
        lastHead = headWorld
        lastPosition = origin
        velocity = Vec3.ZERO
        clearHistory()
        for (h in hands) {
            h.touching = false; h.wasTouching = false; h.position = headWorld; h.target = headWorld
        }
    }

    fun respawn(headTracking: Vec3) {
        respawns++
        teleport(spawnPoint, headTracking, spawnYaw)
    }

    private fun clearHistory() {
        for (i in velocityHistory.indices) velocityHistory[i] = Vec3.ZERO
        velocityAverage = Vec3.ZERO
    }

    /**
     * Один кадр Player.Update(). [headTracking] — голова в пространстве трекинга,
     * [handInputs] — отфильтрованные руки (левая, правая).
     */
    fun update(dt0: Float, headTracking: Vec3, handInputs: Array<HandInput>) {
        val dt = dt0.coerceIn(1f / 240f, 1f / 30f)

        // Rigidbody (FixedUpdate в Unity): гравитация, столкновения тела, трение.
        stepRigidBody(dt, headTracking)
        head = toWorld(headTracking)

        for (i in 0..1) {
            val h = hands[i]
            val input = handInputs[i]
            h.justTouched = false
            if (input.reset) {
                // Сбой трекинга: отпускаем руку на месте, историю скоростей не используем для прыжка.
                if (h.touching || h.wasTouching) clearHistory()
                h.touching = false
                h.wasTouching = false
            }
            h.valid = input.valid
            if (!input.valid) {
                h.touching = false
                h.wasTouching = false
            }
        }
        fun current(i: Int): Vec3 =
            if (handInputs[i].valid) clampArm(toWorld(handInputs[i].position)) else head + (hands[i].position - lastHead)

        // Первая итерация: рука с «гравитационным» довеском 2·9.8·dt², как в оригинале.
        val first = arrayOf(Vec3.ZERO, Vec3.ZERO)
        val colliding = booleanArrayOf(false, false)
        val gravityNudge = Vec3.DOWN * (2f * 9.8f * dt * dt)
        for (i in 0..1) {
            val h = hands[i]
            if (!h.valid) continue
            val cur = current(i)
            val end = iterativeSphereCast(h.position, HAND_RADIUS, cur - h.position + gravityNudge, PRECISION, true)
            if (end != null) {
                first[i] = if (h.wasTouching) h.position - cur else end - cur
                velocity = Vec3.ZERO
                colliding[i] = true
            }
        }
        val both = (colliding[0] || hands[0].wasTouching) && (colliding[1] || hands[1].wasTouching)
        var movement = if (both) (first[0] + first[1]) * 0.5f else first[0] + first[1]

        // Голова не должна пройти сквозь стену.
        val end = iterativeSphereCast(lastHead, HEAD_RADIUS, head + movement - lastHead, PRECISION, false)
        if (end != null) {
            movement = end - lastHead
            val check = head - lastHead + movement
            if (world.raycast(lastHead, check, check.length() + HEAD_RADIUS * PRECISION * 0.999f) != null) {
                movement = lastHead - head
            }
        }
        if (movement != Vec3.ZERO) {
            origin += movement
            head = toWorld(headTracking)
        }
        lastHead = head

        // Окончательное положение рук (CurrentHandPosition — уже после сдвига тела).
        for (i in 0..1) {
            val h = hands[i]
            val cur = current(i)
            h.target = cur
            if (!h.valid) {
                h.position = cur
                continue
            }
            val e = iterativeSphereCast(h.position, HAND_RADIUS, cur - h.position, PRECISION, !both)
            if (e != null) {
                h.position = e
                colliding[i] = true
            } else {
                h.position = cur
            }
        }

        storeVelocities(dt)

        // Прыжок.
        if (colliding[0] || colliding[1]) {
            val speed = velocityAverage.length()
            if (speed > VELOCITY_LIMIT) {
                var v = if (speed * JUMP_MULTIPLIER > MAX_JUMP_SPEED) velocityAverage.normalized() * MAX_JUMP_SPEED
                else velocityAverage * JUMP_MULTIPLIER
                // Не улетать высоко: вверх не быстрее MAX_JUMP_UP (≈0.5 м над точкой толчка).
                if (v.y > MAX_JUMP_UP) v = Vec3(v.x, MAX_JUMP_UP, v.z)
                velocity = v
            }
        }

        // Отлипание: рука ушла далеко от точки касания и между головой и рукой ничего нет.
        for (i in 0..1) {
            val h = hands[i]
            if (!colliding[i]) continue
            val cur = h.target
            val toHand = cur - head
            if ((cur - h.position).length() > UNSTICK_DISTANCE &&
                world.sphereCast(head, HAND_RADIUS * PRECISION, toHand, toHand.length() - HAND_RADIUS) == null
            ) {
                h.position = cur
                colliding[i] = false
            }
        }

        for (i in 0..1) {
            val h = hands[i]
            h.justTouched = colliding[i] && !h.wasTouching
            h.touching = colliding[i]
            h.wasTouching = colliding[i]
        }

        // Страховка: вылетел за карту — обратно на спавн.
        if (head.y < boundsMin.y - 10f || head.x < boundsMin.x - 20f || head.x > boundsMax.x + 20f ||
            head.z < boundsMin.z - 20f || head.z > boundsMax.z + 20f || head.y > boundsMax.y + 30f
        ) {
            respawn(headTracking)
        }
    }

    private fun clampArm(p: Vec3): Vec3 {
        val d = p - head
        return if (d.length() < MAX_ARM_LENGTH) p else head + d.normalized() * MAX_ARM_LENGTH
    }

    private fun stepRigidBody(dt: Float, headTracking: Vec3) {
        velocity += Vec3.DOWN * (GRAVITY * dt)
        // Подшаги, чтобы быстрое тело не проскочило тонкую стену (в Unity это делает непрерывная коллизия).
        val move = velocity * dt
        val steps = max(1, (move.length() / (BODY_RADIUS * 0.5f)).toInt() + 1).coerceAtMost(16)
        grounded = false
        repeat(steps) {
            origin += move / steps.toFloat()
            resolveBody(headTracking)
        }
        // Кулоновское трение о землю: замедление μ·g, как у физматериала Unity по умолчанию.
        if (grounded) {
            val h = velocity.horizontal()
            val speed = h.length()
            val drop = GROUND_FRICTION * GRAVITY * dt
            velocity = if (speed <= drop) Vec3(0f, velocity.y, 0f) else velocity - h * (drop / speed)
        }
    }

    /** Выталкивает голову и корпус из геометрии и гасит скорость в стену. */
    private fun resolveBody(headTracking: Vec3) {
        for ((offset, radius) in BODY_SPHERES) {
            val center = toWorld(headTracking) + offset
            val (pushed, normal) = world.pushOut(center, radius)
            if (normal != null) {
                // Пол выталкивает строго вверх. По нормали склона тело каждый кадр сдвигалось бы
                // чуть вбок — и игрок, стоя на месте, медленно сползал бы под горку.
                origin += if (normal.y > 0.55f) Vec3(0f, (pushed - center).length() / normal.y, 0f) else pushed - center
                val into = velocity dot normal
                if (into < 0) velocity -= normal * into
                if (normal.y > 0.55f) grounded = true
            }
        }
    }

    private fun storeVelocities(dt: Float) {
        velocityIndex = (velocityIndex + 1) % VELOCITY_HISTORY
        val oldest = velocityHistory[velocityIndex]
        val current = (origin - lastPosition) / dt
        velocityAverage += (current - oldest) / VELOCITY_HISTORY.toFloat()
        velocityHistory[velocityIndex] = current
        lastPosition = origin
    }

    // --- Перенос IterativeCollisionSphereCast / CollisionsSphereCast из GorillaLocomotion ---

    private fun iterativeSphereCast(start: Vec3, radius: Float, movement: Vec3, precision: Float, singleHand: Boolean): Vec3? {
        val first = collisionsSphereCast(start, radius * precision, movement, precision)
        if (first != null) {
            val (firstPosition, hit) = first
            val slip = if (!singleHand) DEFAULT_SLIDE else 0.001f
            val slide = (start + movement - firstPosition).projectOnPlane(hit.normal) * slip
            collisionsSphereCast(firstPosition, radius, slide, precision * precision)?.let { return it.first }
            val from = slide + firstPosition
            collisionsSphereCast(from, radius, start + movement - from, precision * precision * precision)?.let { return it.first }
            return firstPosition
        }
        val smaller = radius * precision * 0.66f
        val len = movement.length()
        if (len > 0f && collisionsSphereCast(start, smaller, movement.normalized() * (len + radius * precision * 0.34f), precision * 0.66f) != null) {
            return start
        }
        return null
    }

    private fun collisionsSphereCast(start: Vec3, radius: Float, movement: Vec3, precision: Float): Pair<Vec3, Hit>? {
        val len = movement.length()
        if (len < 1e-7f) return null
        val hit = world.sphereCast(start, radius * precision, movement, len + radius * (1 - precision))
        if (hit != null) {
            var final = hit.point + hit.normal * radius
            val toFinal = final - start
            val inner = world.sphereCast(start, radius * precision * precision, toFinal, toFinal.length() + radius * (1 - precision * precision))
            if (inner != null) {
                final = start + toFinal.normalized() * max(0f, hit.distance - radius * (1f - precision * precision))
                return final to inner
            }
            val ray = world.raycast(start, toFinal, toFinal.length() + radius * precision * precision * 0.999f)
            if (ray != null) return start to ray
            return final to hit
        }
        val ray = world.raycast(start, movement, len + radius * precision * 0.999f)
        if (ray != null) return start to ray
        return null
    }

    companion object {
        const val GRAVITY = 9.81f
        /** minimumRaycastDistance */
        const val HAND_RADIUS = 0.05f
        const val HEAD_RADIUS = 0.15f
        const val BODY_RADIUS = 0.15f
        const val PRECISION = 0.995f
        const val DEFAULT_SLIDE = 0.03f
        const val MAX_ARM_LENGTH = 1.5f
        const val UNSTICK_DISTANCE = 1f
        const val VELOCITY_LIMIT = 0.4f
        const val JUMP_MULTIPLIER = 1.1f
        /** В оригинале 6.5; трекинг камерой рвётся, поэтому ниже — чтобы не улетать. */
        const val MAX_JUMP_SPEED = 5.0f
        /** Предел скорости вверх, чтобы не улетать высоко (в оригинале его нет). */
        const val MAX_JUMP_UP = 2.8f
        const val VELOCITY_HISTORY = 8
        /** Трение физматериала Unity по умолчанию. */
        const val GROUND_FRICTION = 0.6f

        /** Голова и капсула тела (радиус 0.15, высота 0.5) под ней. */
        private val BODY_SPHERES = listOf(
            Vec3.ZERO to HEAD_RADIUS,
            Vec3(0f, -0.4f, 0f) to BODY_RADIUS,
            Vec3(0f, -0.6f, 0f) to BODY_RADIUS
        )
    }
}
