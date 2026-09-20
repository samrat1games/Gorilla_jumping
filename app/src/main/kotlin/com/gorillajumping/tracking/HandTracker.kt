package com.gorillajumping.tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.gorillajumping.math.Quat
import com.gorillajumping.math.Vec3
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Картинка камеры с точками рук для экрана «Трекинг». */
class PreviewFrame(val width: Int, val height: Int, val rgba: ByteBuffer)

/**
 * Трекинг рук камерой телефона — как HandTracker + HandTrackingService в PhoneXR:
 * CameraX 640×480, MediaPipe Hand Landmarker (VIDEO, 2 руки, GPU → CPU), кадр не чаще раза в 30 мс,
 * жесты [HandGestures], сглаживание и удержание [StableHand].
 *
 * Потоки: камера → детекция (свой поток, в нём же создан детектор) → [StableHand.update];
 * игровой цикл читает [snapshot] и [palm].
 *
 * @param frontCamera фронтальная (зеркальная) камера; по умолчанию задняя.
 */
class HandTracker(private val context: Context, private val frontCamera: Boolean = false) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override fun getLifecycle(): Lifecycle = registry

    @Volatile var status = "запуск…"
        private set
    @Volatile var gpu = false
        private set
    @Volatile var cameraFps = 0f
        private set
    @Volatile var detectFps = 0f
        private set
    @Volatile var detectMs = 0f
        private set
    /** Последний кадр для отладки (только когда [wantPreview]). */
    @Volatile var preview: PreviewFrame? = null
        private set
    @Volatile var wantPreview = false

    /** Детектор отвечает (иначе камеру держит кто-то другой, например сам PhoneXR). */
    val working get() = SystemClock.elapsedRealtime() - lastResultMs < 1000

    private val cameraExecutor = Executors.newSingleThreadExecutor { Thread(it, "hands-camera") }
    private val trackingExecutor = Executors.newSingleThreadExecutor { Thread(it, "hands-mediapipe") }
    private val busy = AtomicBoolean(false)
    private var landmarker: HandLandmarker? = null
    private var lastTimestamp = -1L
    private var lastFrameMs = 0L
    @Volatile private var lastResultMs = 0L
    @Volatile private var lastCameraMs = 0L
    @Volatile private var stopped = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** 0 — левая, 1 — правая. */
    private val hands = arrayOf(StableHand(.34f), StableHand(.66f))
    private val latches = arrayOf(HandGestures.PinchLatch(), HandGestures.PinchLatch())
    private val space = arrayOf(HandSpace(), HandSpace())

    private var cameraFrames = 0
    private var detectFrames = 0
    private var detectSum = 0f
    private var statsStart = 0L

    fun start() {
        // GPU-делегат надо создавать и вызывать в одном и том же потоке.
        trackingExecutor.execute {
            landmarker = runCatching { createLandmarker(true) }.onFailure { Log.w(TAG, "GPU недоступен: ${it.message}") }.getOrNull()
            gpu = landmarker != null
            if (landmarker == null) landmarker = runCatching { createLandmarker(false) }.onFailure { status = "MediaPipe: ${it.message}" }.getOrNull()
            Log.i(TAG, "MediaPipe на ${if (gpu) "GPU" else "CPU"}")
            ContextCompat.getMainExecutor(context).execute { bindCamera(); watchdog() }
        }
    }

    fun stop() {
        stopped = true
        ContextCompat.getMainExecutor(context).execute {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            trackingExecutor.execute { landmarker?.close(); landmarker = null }
            cameraExecutor.shutdown(); trackingExecutor.shutdown()
        }
    }

    /** Рука [i] (0 — левая, 1 — правая) в координатах кадра. Можно звать из любого потока. */
    fun snapshot(i: Int): HandSnapshot = hands[i].snapshot()

    /**
     * Ладонь в пространстве трекинга OpenXR (м) или null, если руки не видно. Раскладка — как в рантайме
     * PhoneXR (phone_hand_get_pose), от головы, с плавным переходом между кадрами камеры.
     * Звать из игрового цикла раз в кадр.
     */
    fun palm(i: Int, headPosition: Vec3, headOrientation: Quat): Vec3? {
        val s = snapshot(i)
        if (!s.present) { space[i].lost(); return null }
        return headPosition + headOrientation.rotate(space[i].local(s, System.nanoTime()))
    }

    private fun createLandmarker(useGpu: Boolean): HandLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("mediapipe/hand_landmarker.task")
            .setDelegate(if (useGpu) Delegate.GPU else Delegate.CPU)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(2)
            .setMinHandDetectionConfidence(.45f)
            .setMinHandPresenceConfidence(.45f)
            .setMinTrackingConfidence(.35f)
            .build()
        return HandLandmarker.createFromOptions(context, options)
    }

    /** Камера замолчала (телефон уснул, камеру забрали) — запускаем её заново. */
    private fun watchdog() {
        if (stopped) return
        if (lastCameraMs != 0L && SystemClock.elapsedRealtime() - lastCameraMs > 2000) {
            Log.w(TAG, "камера молчит — перезапуск")
            status = "перезапуск камеры…"
            lastCameraMs = SystemClock.elapsedRealtime()
            bindCamera()
        }
        main.postDelayed({ watchdog() }, 1000)
    }

    private fun bindCamera() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                @Suppress("DEPRECATION")
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { image ->
                    try {
                        cameraFrames++
                        lastCameraMs = SystemClock.elapsedRealtime()
                        val timestamp = image.imageInfo.timestamp / 1_000_000L
                        // Не чаще раза в 30 мс и только когда прошлый кадр уже обработан; лишние пропускаем.
                        if (timestamp - lastFrameMs >= FRAME_INTERVAL_MS && busy.compareAndSet(false, true)) {
                            lastFrameMs = timestamp
                            val frame = image.toBitmap()
                            val rotation = image.imageInfo.rotationDegrees
                            trackingExecutor.execute {
                                try { detect(frame, timestamp, rotation) }
                                catch (t: Throwable) { Log.w(TAG, "detect: ${t.message}") }
                                finally { if (!frame.isRecycled) frame.recycle(); busy.set(false) }
                            }
                        }
                    } catch (_: Throwable) {
                        busy.set(false)
                    } finally {
                        image.close()
                    }
                }
                provider.unbindAll()
                val selector = if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.bindToLifecycle(this, selector, analysis)
                status = "камера запущена"
            } catch (t: Throwable) {
                status = "камера занята: ${t.message}"
                Log.w(TAG, "camera", t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun detect(bitmap: Bitmap, timestampMs: Long, rotation: Int) {
        val lm = landmarker ?: return
        // MediaPipe VIDEO требует строго растущее время.
        val safeTimestamp = maxOf(timestampMs, lastTimestamp + 1)
        lastTimestamp = safeTimestamp
        val t0 = SystemClock.elapsedRealtime()
        val image = BitmapImageBuilder(bitmap).build()
        val result = lm.detectForVideo(image, ImageProcessingOptions.builder().setRotationDegrees(rotation).build(), safeTimestamp)
        image.close()
        val now = SystemClock.elapsedRealtime()
        lastResultMs = now
        onHands(result)
        countStats(now, now - t0)
        if (wantPreview) makePreview(bitmap, rotation, result)
    }

    private fun onHands(result: HandLandmarkerResult) {
        val seen = BooleanArray(2)
        result.landmarks().forEachIndexed { k, points ->
            if (points.size < 21) return@forEachIndexed
            val left = isPhysicalLeft(result, k)
            val i = if (left) 0 else 1
            if (seen[i]) return@forEachIndexed
            seen[i] = true
            val shape = HandGestures.shape(points, left)
            hands[i].update(shape, latches[i].update(shape))
        }
        // Пропавшая рука начинает щипок заново, а не с защёлкнутого состояния.
        for (i in 0..1) if (!seen[i]) latches[i].reset()
    }

    /**
     * Какая это рука на самом деле. MediaPipe считает кадр зеркальным (как у селфи), поэтому у
     * зеркальной фронтальной камеры метку надо переворачивать, а у задней — брать как есть
     * (проверено в шлеме: с переворотом руки менялись местами).
     */
    private fun isPhysicalLeft(result: HandLandmarkerResult, k: Int): Boolean {
        val reported = result.handednesses().getOrNull(k)?.firstOrNull()?.categoryName().orEmpty()
        return if (frontCamera) reported.equals("Right", true) else reported.equals("Left", true)
    }

    private fun countStats(now: Long, spentMs: Long) {
        detectFrames++; detectSum += spentMs.toFloat()
        if (statsStart == 0L) statsStart = now
        if (now - statsStart < 1000) return
        val span = (now - statsStart).toFloat()
        cameraFps = cameraFrames * 1000f / span
        detectFps = detectFrames * 1000f / span
        detectMs = detectSum / detectFrames.coerceAtLeast(1)
        cameraFrames = 0; detectFrames = 0; detectSum = 0f; statsStart = now
        status = "работает"
        Log.i(TAG, "камера %.0f к/с, распознавание %.0f к/с по %.0f мс (%s)".format(cameraFps, detectFps, detectMs, if (gpu) "GPU" else "CPU"))
    }

    // --- Отладочный оверлей: кадр камеры, 21 точка, прицел, жесты ---

    private val bone = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2.5f }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; isFakeBoldText = true; setShadowLayer(3f, 0f, 0f, Color.BLACK) }

    private fun makePreview(frame: Bitmap, rotation: Int, result: HandLandmarkerResult) {
        val pw = 320
        val ph = if (rotation % 180 == 0) 240 else 427
        val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        // Кадр поворачиваем так же, как MediaPipe: точки лягут на картинку.
        c.save()
        c.translate(pw / 2f, ph / 2f)
        c.rotate(rotation.toFloat())
        val sw = if (rotation % 180 == 0) pw else ph
        val sh = if (rotation % 180 == 0) ph else pw
        c.drawBitmap(frame, null, RectF(-sw / 2f, -sh / 2f, sw / 2f, sh / 2f), null)
        c.restore()

        result.landmarks().forEachIndexed { k, pts ->
            if (pts.size < 21) return@forEachIndexed
            val color = HAND_COLORS[if (isPhysicalLeft(result, k)) 0 else 1]
            bone.color = color; dot.color = color
            for ((a, b) in BONES) c.drawLine(pts[a].x() * pw, pts[a].y() * ph, pts[b].x() * pw, pts[b].y() * ph, bone)
            for (pt in pts) c.drawCircle(pt.x() * pw, pt.y() * ph, 3.5f, dot)
        }
        for (i in 0..1) {
            val s = hands[i].snapshot()
            label.color = HAND_COLORS[i]
            val name = if (i == 0) "Л" else "П"
            if (!s.present) {
                c.drawText("$name: нет", 6f + i * pw / 2f, ph - 8f, label)
                continue
            }
            // Прицел: кружок, при щипке — залитый, радиус по силе щипка.
            ring.color = HAND_COLORS[i]
            ring.style = if (s.pinch) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
            c.drawCircle(s.aimX * pw, s.aimY * ph, 12f - 6f * s.pinchStrength, ring)
            c.drawCircle(s.x * pw, s.y * ph, 2f, label)
            c.drawText("$name ${gestures(s)}", 6f + i * pw / 2f, ph - 8f, label)
        }

        val buf = ByteBuffer.allocateDirect(pw * ph * 4).order(ByteOrder.nativeOrder())
        out.copyPixelsToBuffer(buf)
        buf.position(0)
        out.recycle()
        preview = PreviewFrame(pw, ph, buf)
    }

    /** Жесты руки словами — для отладки. */
    fun gestures(s: HandSnapshot): String = listOfNotNull(
        "кулак".takeIf { s.fist }, "щипок".takeIf { s.pinch }, "указат.".takeIf { s.index },
        "большой".takeIf { s.thumb }, "ладонь к лицу".takeIf { s.palmToFace },
    ).joinToString(" ").ifEmpty { "открыта" }

    private companion object {
        const val TAG = "GorillaHands"
        const val FRAME_INTERVAL_MS = 30L
        val HAND_COLORS = intArrayOf(0xFF40FF60.toInt(), 0xFF40A0FF.toInt())
        val BONES = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8, 5 to 9, 9 to 10, 10 to 11, 11 to 12,
            9 to 13, 13 to 14, 14 to 15, 15 to 16, 13 to 17, 0 to 17, 17 to 18, 18 to 19, 19 to 20
        )
    }
}

/**
 * Координаты кадра → рука в системе головы (м), как phone_hand_get_pose в рантайме PhoneXR:
 * x вправо на 1.15 м по ширине кадра, y — 0.68 м по высоте (низ и верх кадра обрезаны),
 * глубина от 0.35 до 0.80 м. Между кадрами камеры — плавный переход (smoothstep, 40–90 мс),
 * иначе при 30 кадрах в секунду рука шагает ступеньками.
 */
private class HandSpace {
    private var target: Vec3? = null
    private var from = Vec3.ZERO
    private var startNs = 0L
    private var durationNs = 0L
    private var receivedNs = 0L
    private var lastX = Float.NaN; private var lastY = Float.NaN; private var lastZ = Float.NaN

    fun local(s: HandSnapshot, now: Long): Vec3 {
        if (s.x != lastX || s.y != lastY || s.z != lastZ) {
            lastX = s.x; lastY = s.y; lastZ = s.z
            val y = s.y.coerceIn(.08f, .88f)
            val p = Vec3((s.x - .5f) * 1.15f, (.60f - y) * .68f, -.35f - s.z * .45f)
            val prev = target
            if (prev == null) { from = p; durationNs = 0L }
            else { from = sample(now); durationNs = (now - receivedNs).coerceIn(40_000_000L, 90_000_000L) }
            target = p; startNs = now; receivedNs = now
        }
        return sample(now)
    }

    fun lost() { target = null; lastX = Float.NaN }

    private fun sample(now: Long): Vec3 {
        val p = target ?: return Vec3.ZERO
        if (durationNs == 0L || now >= startNs + durationNs) return p
        var t = (now - startNs).toFloat() / durationNs
        t = t * t * (3f - 2f * t)
        return from.lerp(p, t)
    }
}
