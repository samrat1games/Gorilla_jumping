package com.gorillajumping.physics

import com.gorillajumping.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Hit(
    /** Сколько прошёл центр луча/сферы до касания. */
    val distance: Float,
    /** Точка касания на поверхности. */
    val point: Vec3,
    /** Нормаль поверхности в точке касания (к центру сферы). */
    val normal: Vec3
)

/**
 * Статичная геометрия карты для физики: треугольники в BVH.
 * Запросы повторяют то, чем пользуется оригинальная локомоция Gorilla Tag в Unity:
 * Raycast, SphereCast (без касаний, которые уже были в начале), плюс выталкивание сферы.
 */
class CollisionWorld(private val tris: FloatArray) {
    val triangleCount = tris.size / 9
    private val order = IntArray(triangleCount) { it }

    // Узлы BVH в плоских массивах.
    private var nodeCount = 0
    private var bmin = FloatArray(0)
    private var bmax = FloatArray(0)
    private var left = IntArray(0)   // для листа: начало в order
    private var count = IntArray(0)  // для листа: число треугольников, для узла: 0
    private var right = IntArray(0)

    val minBound: Vec3
    val maxBound: Vec3

    init {
        val cap = max(1, triangleCount * 2 / LEAF_SIZE + 16)
        bmin = FloatArray(cap * 3); bmax = FloatArray(cap * 3)
        left = IntArray(cap); right = IntArray(cap); count = IntArray(cap)
        val centroids = FloatArray(triangleCount * 3)
        for (t in 0 until triangleCount) for (a in 0 until 3)
            centroids[t * 3 + a] = (tris[t * 9 + a] + tris[t * 9 + 3 + a] + tris[t * 9 + 6 + a]) / 3f
        if (triangleCount > 0) build(0, triangleCount, centroids)
        minBound = Vec3(bmin[0], bmin[1], bmin[2])
        maxBound = Vec3(bmax[0], bmax[1], bmax[2])
    }

    private fun newNode(): Int {
        if (nodeCount == left.size) {
            val n = left.size * 2
            bmin = bmin.copyOf(n * 3); bmax = bmax.copyOf(n * 3)
            left = left.copyOf(n); right = right.copyOf(n); count = count.copyOf(n)
        }
        return nodeCount++
    }

    private fun build(start: Int, end: Int, centroids: FloatArray): Int {
        val node = newNode()
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        var cx0 = Float.MAX_VALUE; var cy0 = Float.MAX_VALUE; var cz0 = Float.MAX_VALUE
        var cx1 = -Float.MAX_VALUE; var cy1 = -Float.MAX_VALUE; var cz1 = -Float.MAX_VALUE
        for (i in start until end) {
            val t = order[i]
            for (v in 0 until 3) {
                val o = t * 9 + v * 3
                x0 = min(x0, tris[o]); y0 = min(y0, tris[o + 1]); z0 = min(z0, tris[o + 2])
                x1 = max(x1, tris[o]); y1 = max(y1, tris[o + 1]); z1 = max(z1, tris[o + 2])
            }
            cx0 = min(cx0, centroids[t * 3]); cy0 = min(cy0, centroids[t * 3 + 1]); cz0 = min(cz0, centroids[t * 3 + 2])
            cx1 = max(cx1, centroids[t * 3]); cy1 = max(cy1, centroids[t * 3 + 1]); cz1 = max(cz1, centroids[t * 3 + 2])
        }
        bmin[node * 3] = x0; bmin[node * 3 + 1] = y0; bmin[node * 3 + 2] = z0
        bmax[node * 3] = x1; bmax[node * 3 + 1] = y1; bmax[node * 3 + 2] = z1
        val n = end - start
        val ex = cx1 - cx0; val ey = cy1 - cy0; val ez = cz1 - cz0
        if (n <= LEAF_SIZE || max(ex, max(ey, ez)) < 1e-5f) {
            left[node] = start; count[node] = n
            return node
        }
        val axis = if (ex >= ey && ex >= ez) 0 else if (ey >= ez) 1 else 2
        val mid = start + n / 2
        select(start, end - 1, mid, axis, centroids)
        count[node] = 0
        val l = build(start, mid, centroids)
        val r = build(mid, end, centroids)
        left[node] = l; right[node] = r
        return node
    }

    /** Quickselect: ставит на место [k] медиану по оси. */
    private fun select(lo0: Int, hi0: Int, k: Int, axis: Int, c: FloatArray) {
        var lo = lo0; var hi = hi0
        while (hi > lo) {
            val pivot = c[order[(lo + hi) ushr 1] * 3 + axis]
            var i = lo; var j = hi
            while (i <= j) {
                while (c[order[i] * 3 + axis] < pivot) i++
                while (c[order[j] * 3 + axis] > pivot) j--
                if (i <= j) {
                    val tmp = order[i]; order[i] = order[j]; order[j] = tmp
                    i++; j--
                }
            }
            if (k <= j) hi = j else if (k >= i) lo = i else return
        }
    }

    private val stack = IntArray(128)

    /** Перебирает треугольники, чьи узлы пересекают AABB. */
    private inline fun query(qx0: Float, qy0: Float, qz0: Float, qx1: Float, qy1: Float, qz1: Float, visit: (Int) -> Unit) {
        if (nodeCount == 0) return
        var sp = 0
        stack[sp++] = 0
        while (sp > 0) {
            val node = stack[--sp]
            val o = node * 3
            if (bmin[o] > qx1 || bmax[o] < qx0 || bmin[o + 1] > qy1 || bmax[o + 1] < qy0 || bmin[o + 2] > qz1 || bmax[o + 2] < qz0) continue
            if (count[node] > 0) {
                for (i in left[node] until left[node] + count[node]) visit(order[i])
            } else if (sp < stack.size - 2) {
                stack[sp++] = left[node]
                stack[sp++] = right[node]
            }
        }
    }

    private fun vert(t: Int, v: Int) = Vec3(tris[t * 9 + v * 3], tris[t * 9 + v * 3 + 1], tris[t * 9 + v * 3 + 2])

    fun raycast(origin: Vec3, direction: Vec3, maxDistance: Float): Hit? {
        val dir = direction.normalized()
        if (dir == Vec3.ZERO || maxDistance <= 0f) return null
        val end = origin + dir * maxDistance
        var best = maxDistance
        var bestTri = -1
        query(min(origin.x, end.x), min(origin.y, end.y), min(origin.z, end.z), max(origin.x, end.x), max(origin.y, end.y), max(origin.z, end.z)) { t ->
            val d = rayTriangle(origin, dir, t)
            if (d in 0f..best) { best = d; bestTri = t }
        }
        if (bestTri < 0) return null
        var n = triNormal(bestTri)
        if ((n dot dir) > 0) n = -n
        return Hit(best, origin + dir * best, n)
    }

    private fun rayTriangle(o: Vec3, d: Vec3, t: Int): Float {
        val a = vert(t, 0); val b = vert(t, 1); val c = vert(t, 2)
        val e1 = b - a; val e2 = c - a
        val p = d cross e2
        val det = e1 dot p
        if (abs(det) < 1e-9f) return -1f
        val inv = 1f / det
        val s = o - a
        val u = (s dot p) * inv
        if (u < 0f || u > 1f) return -1f
        val q = s cross e1
        val v = (d dot q) * inv
        if (v < 0f || u + v > 1f) return -1f
        return (e2 dot q) * inv
    }

    private fun triNormal(t: Int): Vec3 {
        val a = vert(t, 0)
        return ((vert(t, 1) - a) cross (vert(t, 2) - a)).normalized()
    }

    /**
     * Как Physics.SphereCast: сфера радиуса [radius] едет из [origin] по [direction] на [maxDistance].
     * Треугольники, которых сфера касается уже в начале, пропускаются.
     */
    fun sphereCast(origin: Vec3, radius: Float, direction: Vec3, maxDistance: Float): Hit? {
        val dir = direction.normalized()
        if (dir == Vec3.ZERO || maxDistance <= 0f) return null
        val end = origin + dir * maxDistance
        var best = maxDistance
        var bestPoint: Vec3? = null
        query(
            min(origin.x, end.x) - radius, min(origin.y, end.y) - radius, min(origin.z, end.z) - radius,
            max(origin.x, end.x) + radius, max(origin.y, end.y) + radius, max(origin.z, end.z) + radius
        ) { t ->
            val a = vert(t, 0); val b = vert(t, 1); val c = vert(t, 2)
            if ((closestPointOnTriangle(origin, a, b, c) - origin).lengthSq() < radius * radius) return@query
            val toi = sweptSphereTriangle(origin, dir, radius, a, b, c, best)
            if (toi != null && toi.first < best) {
                best = toi.first
                bestPoint = toi.second
            }
        }
        val point = bestPoint ?: return null
        val center = origin + dir * best
        var normal = (center - point).normalized()
        if (normal == Vec3.ZERO) normal = -dir
        return Hit(best, point, normal)
    }

    /** Время касания сферы с треугольником: грань, рёбра, вершины. Возвращает (путь, точка касания). */
    private fun sweptSphereTriangle(o: Vec3, d: Vec3, r: Float, a: Vec3, b: Vec3, c: Vec3, limit: Float): Pair<Float, Vec3>? {
        var best = limit
        var point: Vec3? = null
        var n = ((b - a) cross (c - a)).normalized()
        if (n == Vec3.ZERO) return null
        if ((n dot d) > 0) n = -n
        val denom = n dot d
        val dist = (o - a) dot n
        if (denom < -1e-7f && dist >= r) {
            val t = (dist - r) / -denom
            if (t <= best) {
                val contact = o + d * t - n * r
                if (pointInTriangle(contact, a, b, c, n)) {
                    best = t; point = contact
                }
            }
        }
        if (point != null) return best to point
        for ((p, q) in arrayOf(a to b, b to c, c to a)) {
            val t = rayCapsuleSide(o, d, p, q, r)
            if (t != null && t in 0f..best) {
                val center = o + d * t
                best = t
                point = closestPointOnSegment(center, p, q)
            }
        }
        for (p in arrayOf(a, b, c)) {
            val t = raySphere(o, d, p, r)
            if (t != null && t in 0f..best) {
                best = t; point = p
            }
        }
        return point?.let { best to it }
    }

    private fun pointInTriangle(p: Vec3, a: Vec3, b: Vec3, c: Vec3, n: Vec3): Boolean {
        val e = -1e-5f
        return (((b - a) cross (p - a)) dot n) >= e && (((c - b) cross (p - b)) dot n) >= e && (((a - c) cross (p - c)) dot n) >= e
    }

    /** Луч против бесконечного цилиндра вокруг отрезка, с проверкой, что касание внутри отрезка. */
    private fun rayCapsuleSide(o: Vec3, d: Vec3, p: Vec3, q: Vec3, r: Float): Float? {
        val axis = q - p
        val len2 = axis.lengthSq()
        if (len2 < 1e-10f) return null
        val m = o - p
        val md = m dot axis; val nd = d dot axis; val dd = len2
        val nn = d dot d; val mn = m dot d
        val aq = dd * nn - nd * nd
        val k = (m dot m) - r * r
        val cq = dd * k - md * md
        if (abs(aq) < 1e-10f) return null
        val bq = dd * mn - nd * md
        val disc = bq * bq - aq * cq
        if (disc < 0) return null
        val t = (-bq - sqrt(disc)) / aq
        if (t < 0) return null
        val s = md + t * nd
        return if (s in 0f..dd) t else null
    }

    private fun raySphere(o: Vec3, d: Vec3, center: Vec3, r: Float): Float? {
        val m = o - center
        val b = m dot d
        val c = (m dot m) - r * r
        if (c > 0 && b > 0) return null
        val disc = b * b - c
        if (disc < 0) return null
        val t = -b - sqrt(disc)
        return if (t >= 0) t else null
    }

    /**
     * Выталкивает сферу из геометрии. Возвращает новый центр и среднюю нормаль опоры
     * (null, если касаний не было).
     */
    fun pushOut(center: Vec3, radius: Float, iterations: Int = 4): Pair<Vec3, Vec3?> {
        var c = center
        var normalSum = Vec3.ZERO
        var touched = false
        repeat(iterations) {
            var moved = false
            var best: Vec3? = null
            var bestDepth = 0f
            query(c.x - radius, c.y - radius, c.z - radius, c.x + radius, c.y + radius, c.z + radius) { t ->
                val p = closestPointOnTriangle(c, vert(t, 0), vert(t, 1), vert(t, 2))
                val delta = c - p
                val d2 = delta.lengthSq()
                if (d2 < radius * radius) {
                    val d = sqrt(d2)
                    val depth = radius - d
                    if (depth > bestDepth) {
                        bestDepth = depth
                        best = if (d > 1e-6f) delta / d else triNormal(t)
                    }
                }
            }
            best?.let {
                c += it * (bestDepth + 1e-4f)
                normalSum += it
                touched = true
                moved = true
            }
            if (!moved) return c to (if (touched) normalSum.normalized() else null)
        }
        return c to (if (touched) normalSum.normalized() else null)
    }

    fun overlaps(center: Vec3, radius: Float): Boolean {
        var hit = false
        query(center.x - radius, center.y - radius, center.z - radius, center.x + radius, center.y + radius, center.z + radius) { t ->
            if (!hit && (closestPointOnTriangle(center, vert(t, 0), vert(t, 1), vert(t, 2)) - center).lengthSq() < radius * radius) hit = true
        }
        return hit
    }

    companion object {
        private const val LEAF_SIZE = 6

        fun closestPointOnSegment(p: Vec3, a: Vec3, b: Vec3): Vec3 {
            val ab = b - a
            val t = ((p - a) dot ab) / max(ab.lengthSq(), 1e-12f)
            return a + ab * t.coerceIn(0f, 1f)
        }

        /** Ericson, Real-Time Collision Detection, 5.1.5. */
        fun closestPointOnTriangle(p: Vec3, a: Vec3, b: Vec3, c: Vec3): Vec3 {
            val ab = b - a; val ac = c - a; val ap = p - a
            val d1 = ab dot ap; val d2 = ac dot ap
            if (d1 <= 0 && d2 <= 0) return a
            val bp = p - b
            val d3 = ab dot bp; val d4 = ac dot bp
            if (d3 >= 0 && d4 <= d3) return b
            val vc = d1 * d4 - d3 * d2
            if (vc <= 0 && d1 >= 0 && d3 <= 0) return a + ab * (d1 / (d1 - d3))
            val cp = p - c
            val d5 = ab dot cp; val d6 = ac dot cp
            if (d6 >= 0 && d5 <= d6) return c
            val vb = d5 * d2 - d1 * d6
            if (vb <= 0 && d2 >= 0 && d6 <= 0) return a + ac * (d2 / (d2 - d6))
            val va = d3 * d6 - d5 * d4
            if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) return b + (c - b) * ((d4 - d3) / ((d4 - d3) + (d5 - d6)))
            val denom = 1f / (va + vb + vc)
            return a + ab * (vb * denom) + ac * (vc * denom)
        }
    }
}
