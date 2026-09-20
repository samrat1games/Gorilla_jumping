package com.gorillajumping.tracking

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/** Жесты по 21 точке MediaPipe и фильтр One Euro — как HandGestures + HandTrackingService.classify в PhoneXR. */
object HandGestures {
    /** Сырое состояние руки из одного кадра камеры, координаты кадра 0..1. */
    class Shape(
        /** Центр ладони: среднее точек 0, 5, 9, 13, 17. */
        val x: Float,
        val y: Float,
        /** 0 — у камеры, 1 — далеко (по ширине ладони). */
        val z: Float,
        /** Точка прицела между основаниями большого и указательного: не прыгает при щипке. */
        val aimX: Float,
        val aimY: Float,
        /** Расстояние кончиков большого и указательного в ширинах ладони (для [PinchLatch]). */
        val pinchGap: Float,
        val pinchStrength: Float,
        val fist: Boolean,
        /** Разогнут только указательный. */
        val index: Boolean,
        /** Показан только большой палец. */
        val thumb: Boolean,
        val palmToFace: Boolean,
    )

    /** [physicalLeft] — настоящая левая рука пользователя (зеркальность MediaPipe уже учтена). */
    fun shape(p: List<NormalizedLandmark>, physicalLeft: Boolean): Shape {
        fun d(a: Int, b: Int): Float {
            val dx = p[a].x() - p[b].x()
            val dy = p[a].y() - p[b].y()
            return sqrt(dx * dx + dy * dy)
        }
        fun extended(mcp: Int, pip: Int, tip: Int): Boolean {
            val ax = p[mcp].x() - p[pip].x()
            val ay = p[mcp].y() - p[pip].y()
            val bx = p[tip].x() - p[pip].x()
            val by = p[tip].y() - p[pip].y()
            val length = sqrt((ax * ax + ay * ay) * (bx * bx + by * by)).coerceAtLeast(.0001f)
            val jointCosine = (ax * bx + ay * by) / length
            return jointCosine < -.48f && d(0, tip) > d(0, pip) * 1.01f
        }
        val index = extended(5, 6, 8)
        val middle = extended(9, 10, 12)
        val ring = extended(13, 14, 16)
        val pinky = extended(17, 18, 20)
        val palmWidth = d(5, 17).coerceAtLeast(.035f)
        val thumb = extended(2, 3, 4) && d(4, 5) > palmWidth * .62f
        val folded = listOf(index, middle, ring, pinky).count { !it }
        val indexOnly = index && !middle && !ring && !pinky
        val thumbOnly = thumb && !index && folded >= 3
        val tips = intArrayOf(8, 12, 16, 20)
        val pips = intArrayOf(6, 10, 14, 18)
        val tightlyFolded = tips.indices.count { d(0, tips[it]) < d(0, pips[it]) * 1.04f }
        // Указательный или большой палец — это кнопка, а не кулак.
        val fist = tightlyFolded >= 3 && !indexOnly && !thumbOnly

        val palm = d(5, 17).coerceAtLeast(.02f)
        val gap = d(4, 8) / palm
        // Обход запястье → основание указательного → основание мизинца: для задней камеры правая рука
        // показывает тыльную сторону (ладонь к лицу), когда он идёт по часовой.
        val ax = p[5].x() - p[0].x(); val ay = p[5].y() - p[0].y()
        val bx = p[17].x() - p[0].x(); val by = p[17].y() - p[0].y()
        val cross = ax * by - ay * bx
        val toFace = (if (physicalLeft) cross < 0 else cross > 0) && abs(cross) > palm * palm * .15f

        return Shape(
            x = (p[0].x() + p[5].x() + p[9].x() + p[13].x() + p[17].x()) / 5f,
            y = (p[0].y() + p[5].y() + p[9].y() + p[13].y() + p[17].y()) / 5f,
            z = ((.17f - palmWidth) / .13f).coerceIn(0f, 1f),
            aimX = p[2].x() * .3f + p[5].x() * .45f + (p[4].x() + p[8].x()) / 2 * .25f,
            aimY = p[2].y() * .3f + p[5].y() * .45f + (p[4].y() + p[8].y()) / 2 * .25f,
            pinchGap = gap,
            pinchStrength = ((.9f - gap) / .6f).coerceIn(0f, 1f),
            fist = fist,
            index = indexOnly,
            thumb = thumbOnly,
            palmToFace = toFace,
        )
    }

    /** Щипок с гистерезисом: начинается, когда пальцы сомкнулись, кончается, только когда явно разошлись. */
    class PinchLatch(private val close: Float = .30f, private val open: Float = .48f) {
        var pinching = false
            private set

        fun update(shape: Shape): Boolean {
            pinching = if (shape.fist) false else if (pinching) shape.pinchGap < open else shape.pinchGap < close
            return pinching
        }

        fun reset() { pinching = false }
    }

    /** One Euro (Casiez 2012) с мёртвой зоной: в покое рука не дрожит вовсе, в движении почти без задержки. */
    class OneEuro(
        private val minCutoff: Float,
        private val beta: Float,
        private val derivativeCutoff: Float = 1f,
        private val deadZone: Float = 0f,
    ) {
        private var value = Float.NaN
        private var shown = Float.NaN
        private var derivative = 0f
        private var lastNs = 0L

        fun filter(raw: Float, timeNs: Long): Float {
            if (value.isNaN() || lastNs == 0L) {
                value = raw; shown = raw; lastNs = timeNs
                return raw
            }
            val dt = ((timeNs - lastNs) / 1e9f).coerceIn(.001f, .2f)
            lastNs = timeNs
            derivative += alpha(derivativeCutoff, dt) * ((raw - value) / dt - derivative)
            value += alpha(minCutoff + beta * abs(derivative), dt) * (raw - value)
            // Выход сдвигается, только когда фильтр ушёл дальше мёртвой зоны, и отстаёт ровно на неё.
            val gap = value - shown
            if (abs(gap) > deadZone) shown = value - deadZone * sign(gap)
            return shown
        }

        fun reset() {
            value = Float.NaN; shown = Float.NaN; derivative = 0f; lastNs = 0L
        }

        private fun alpha(cutoff: Float, dt: Float): Float {
            val tau = 1f / (2f * PI.toFloat() * cutoff)
            return 1f / (1f + tau / dt)
        }
    }
}
