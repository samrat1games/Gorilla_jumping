package com.gorillajumping.game

import com.gorillajumping.math.Vec3
import com.gorillajumping.physics.CollisionWorld
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Бот-горилла на той же физике, что и игрок ([GorillaPlayer]): голова стоит в начале своего
 * пространства трекинга, а руки гребут по кругу — вперёд, вниз в землю, назад по земле, вверх.
 * Рука, упёршаяся в землю, прилипает, и тело едет на обратное движение руки, как у человека;
 * отпустил на скорости — прыжок. Поворачивается бот так же, как игрок стиком: вокруг головы.
 *
 * Бот слабее человека: гребёт реже, чем может махать игрок, и дотягивается короче.
 */
class Bot(world: CollisionWorld, val name: String, val skin: Int, val items: IntArray, spawn: Vec3, yaw: Float) {
    val body = GorillaPlayer(world).apply {
        boundsMin = world.minBound
        boundsMax = world.maxBound
        spawnPoint = spawn
        spawnYaw = yaw
        teleport(spawn, Vec3.ZERO, yaw)
    }
    private val inputs = arrayOf(HandInput(), HandInput())
    private var phase = Random.nextFloat()

    var infected = false
    /** Водящий бот в начале раунда стоит, чтобы игрок успел убежать. */
    var waitTimer = 0f

    // Бродить: куда идти и когда передумать; застрял — развернуться.
    private var wanderYaw = yaw
    private var wanderTimer = 0f
    private var stuckTimer = 0f
    private var lastPos = spawn
    private var escapeTimer = 0f

    val head get() = body.head
    fun hand(i: Int) = body.hands[i].position

    /** Куда смотрит модель (как yaw у удалённых игроков). */
    val drawYaw: Float get() = (body.yaw + PI).toFloat()

    /**
     * [target] — за кем бежать (водящий) или от кого убегать ([flee]); null — бродить.
     * [strokeHz] — сколько гребков в секунду.
     */
    fun update(dt: Float, target: Vec3?, flee: Boolean, strokeHz: Float) {
        if (waitTimer > 0f) { waitTimer -= dt; stand(dt); return }

        // Направление: к цели, от неё или куда глаза глядят.
        wanderTimer -= dt
        if (wanderTimer <= 0f) { wanderTimer = 3f + Random.nextFloat() * 4f; wanderYaw += (Random.nextFloat() - .5f) * 2.5f }
        val desired = when {
            escapeTimer > 0f -> wanderYaw
            target != null -> {
                val d = (if (flee) head - target else target - head).horizontal()
                if (d.length() < 1e-3f) body.yaw else atan2(-d.x, -d.z)
            }
            else -> wanderYaw
        }
        escapeTimer -= dt
        var delta = desired - body.yaw
        while (delta > PI) delta -= (2 * PI).toFloat()
        while (delta < -PI) delta += (2 * PI).toFloat()
        body.turn(delta.coerceIn(-TURN_RATE * dt, TURN_RATE * dt), Vec3.ZERO)

        // Застрял (дерево, склон): отворачиваемся и уходим в сторону.
        stuckTimer += dt
        if (stuckTimer > 2f) {
            if ((head - lastPos).horizontal().length() < 0.4f) {
                wanderYaw = body.yaw + (if (Random.nextBoolean()) 1f else -1f) * (PI / 2).toFloat()
                escapeTimer = 1.5f
            }
            stuckTimer = 0f
            lastPos = head
        }

        // Гребки: две руки в противофазе, путь — эллипс в системе головы (вперёд — −Z).
        phase = (phase + dt * strokeHz) % 1f
        for (i in 0..1) {
            val a = ((phase + i * .5f) % 1f) * 2f * PI.toFloat()
            val side = if (i == 0) -SIDE else SIDE
            // Внизу (sin a > 0) рука в земле и идёт назад (+Z): тело едет вперёд.
            val p = Vec3(side, CENTER_Y - RADIUS_Y * sin(a), CENTER_Z - RADIUS_Z * cos(a))
            inputs[i].update(p, true)
        }
        body.update(dt, Vec3.ZERO, inputs)
    }

    /** Стоять на месте: руки опущены в землю под плечами. */
    private fun stand(dt: Float) {
        for (i in 0..1) inputs[i].update(Vec3(if (i == 0) -SIDE else SIDE, -1.1f, -0.2f), true)
        body.update(dt, Vec3.ZERO, inputs)
    }

    private companion object {
        const val SIDE = 0.25f
        const val CENTER_Y = -0.85f
        const val RADIUS_Y = 0.35f
        const val CENTER_Z = -0.25f
        const val RADIUS_Z = 0.3f
        /** Рад/с. */
        const val TURN_RATE = 2.2f
    }
}
