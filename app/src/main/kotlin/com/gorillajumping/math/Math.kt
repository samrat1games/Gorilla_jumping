package com.gorillajumping.math

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Float) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(x * x + y * y + z * z)
    fun lengthSq() = x * x + y * y + z * z
    fun normalized(): Vec3 {
        val l = length()
        return if (l > 1e-8f) this / l else ZERO
    }
    fun horizontal() = Vec3(x, 0f, z)

    /** Как Vector3.ProjectOnPlane в Unity. */
    fun projectOnPlane(normal: Vec3) = this - normal * (this dot normal)

    fun clampLength(max: Float): Vec3 {
        val l = length()
        return if (l > max && l > 0f) this * (max / l) else this
    }

    fun lerp(o: Vec3, t: Float) = Vec3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UP = Vec3(0f, 1f, 0f)
        val DOWN = Vec3(0f, -1f, 0f)
    }
}

data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {
    operator fun times(q: Quat) = Quat(
        w * q.x + x * q.w + y * q.z - z * q.y,
        w * q.y - x * q.z + y * q.w + z * q.x,
        w * q.z + x * q.y - y * q.x + z * q.w,
        w * q.w - x * q.x - y * q.y - z * q.z
    )

    fun rotate(v: Vec3): Vec3 {
        val u = Vec3(x, y, z)
        val t = (u cross v) * 2f
        return v + t * w + (u cross t)
    }

    fun conjugate() = Quat(-x, -y, -z, w)

    /** Поворот вокруг вертикали, куда смотрит -Z этого кватерниона. */
    fun yaw(): Float {
        val f = rotate(Vec3(0f, 0f, -1f))
        return kotlin.math.atan2(-f.x, -f.z)
    }

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)
        fun axisAngle(axis: Vec3, angle: Float): Quat {
            val s = sin(angle / 2)
            return Quat(axis.x * s, axis.y * s, axis.z * s, cos(angle / 2))
        }
        fun yaw(angle: Float) = axisAngle(Vec3.UP, angle)
    }
}

/** Матрица 4x4 по столбцам, как в OpenGL. */
class Mat4(val m: FloatArray = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }) {
    operator fun times(o: Mat4): Mat4 {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += m[k * 4 + row] * o.m[c * 4 + k]
            r[c * 4 + row] = s
        }
        return Mat4(r)
    }

    fun transformPoint(v: Vec3) = Vec3(
        m[0] * v.x + m[4] * v.y + m[8] * v.z + m[12],
        m[1] * v.x + m[5] * v.y + m[9] * v.z + m[13],
        m[2] * v.x + m[6] * v.y + m[10] * v.z + m[14]
    )

    fun transformDir(v: Vec3) = Vec3(
        m[0] * v.x + m[4] * v.y + m[8] * v.z,
        m[1] * v.x + m[5] * v.y + m[9] * v.z,
        m[2] * v.x + m[6] * v.y + m[10] * v.z
    )

    companion object {
        fun identity() = Mat4()

        fun translation(t: Vec3) = Mat4().also { it.m[12] = t.x; it.m[13] = t.y; it.m[14] = t.z }

        fun scale(s: Vec3) = Mat4().also { it.m[0] = s.x; it.m[5] = s.y; it.m[10] = s.z }

        fun rotation(q: Quat): Mat4 {
            val (x, y, z, w) = q
            val r = Mat4()
            r.m[0] = 1 - 2 * (y * y + z * z); r.m[1] = 2 * (x * y + z * w); r.m[2] = 2 * (x * z - y * w)
            r.m[4] = 2 * (x * y - z * w); r.m[5] = 1 - 2 * (x * x + z * z); r.m[6] = 2 * (y * z + x * w)
            r.m[8] = 2 * (x * z + y * w); r.m[9] = 2 * (y * z - x * w); r.m[10] = 1 - 2 * (x * x + y * y)
            return r
        }

        fun trs(t: Vec3, q: Quat, s: Vec3 = Vec3(1f, 1f, 1f)) = translation(t) * rotation(q) * scale(s)

        /** Проекция по углам OpenXR (fov несимметричный). */
        fun projection(left: Float, right: Float, up: Float, down: Float, near: Float, far: Float): Mat4 {
            val l = tan(left); val r = tan(right); val u = tan(up); val d = tan(down)
            val w = r - l; val h = u - d
            val p = Mat4(FloatArray(16))
            p.m[0] = 2 / w
            p.m[5] = 2 / h
            p.m[8] = (r + l) / w
            p.m[9] = (u + d) / h
            p.m[10] = -(far + near) / (far - near)
            p.m[11] = -1f
            p.m[14] = -2 * far * near / (far - near)
            return p
        }

        /** Обратная к жёсткому преобразованию (поворот + перенос). */
        fun viewFromPose(position: Vec3, orientation: Quat): Mat4 {
            val inv = orientation.conjugate()
            val t = inv.rotate(-position)
            return translation(t) * rotation(inv)
        }
    }
}

fun clamp(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v

fun approxZero(v: Float) = abs(v) < 1e-6f
