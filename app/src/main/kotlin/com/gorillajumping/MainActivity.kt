package com.gorillajumping

import android.app.Activity
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30.*
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import com.gorillajumping.audio.MusicPlayer
import com.gorillajumping.audio.Sound
import com.gorillajumping.game.AssetCache
import com.gorillajumping.game.Game
import com.gorillajumping.game.GpuAssets
import com.gorillajumping.math.Mat4
import com.gorillajumping.math.Quat
import com.gorillajumping.math.Vec3
import com.gorillajumping.net.Network
import com.gorillajumping.render.ImagePixels
import com.gorillajumping.render.Renderer
import com.gorillajumping.render.TextPanel
import com.gorillajumping.render.Textures
import com.gorillajumping.tracking.HandTracker
import com.gorillajumping.xr.HeadLevel
import com.gorillajumping.xr.XrBridge
import com.gorillajumping.xr.XrFrame
import com.phonexr.sdk.PhoneXRInput

/** Игра в шлеме (OpenXR): сразу в лес и на сервер, без меню. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    @Volatile private var running = true
    private var session: java.util.concurrent.Future<*>? = null
    @Volatile private var sdkState: PhoneXRInput.State? = null
    @Volatile private var sdkTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Телефон в очках не должен засыпать: иначе гаснет экран, а с ним камера и трекинг.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER; text = "Gorilla Jumping: запуск OpenXR…" }
        setContentView(status)
        AssetCache.load(assets)
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            status.text = "Разрешите доступ к камере — он нужен для трекинга рук"
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 1)
        } else start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        start() // без камеры тоже запускаемся: руки может отдавать PhoneXR
    }

    private fun start() {
        if (session != null) return
        session = xrThread.submit {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
            try { run() } catch (t: Throwable) { Log.e("GorillaXR", "xr", t) }
        }
    }

    override fun onDestroy() {
        running = false
        runCatching { session?.get(2, java.util.concurrent.TimeUnit.SECONDS) }
        super.onDestroy()
    }

    private fun say(text: String) = runOnUiThread { status.text = text }

    private fun run() {
        // EGL-контекст для OpenXR: рисуем только в свопчейны, поверхность — пустышка 16×16.
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)
        val configs = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_RENDERABLE_TYPE, 0x40 /* ES3 */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE
        ), 0, configs, 0, 1, IntArray(1), 0)
        val config = configs[0]!!
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(0x3098, 3, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, surface, surface, context)

        val error = XrBridge.nativeInit(this, display.nativeHandle, config.nativeHandle, context.nativeHandle)
        if (error != null) {
            say("OpenXR не запустился:\n$error\n\nЗапускайте игру из PhoneXR (нужен OpenXR Runtime Broker с Monado).")
            return
        }
        val (width, height, format) = XrBridge.nativeSwapchainInfo()
        Textures.srgb = format == GL_SRGB8_ALPHA8
        val renderer = Renderer().also { it.linearOutput = Textures.srgb }
        say("Игра в очках. Размер глаза: ${width}×$height")

        // Кадровые буферы с глубиной на каждое изображение свопчейна.
        val fbo = IntArray(1).also { glGenFramebuffers(1, it, 0) }[0]
        val depth = IntArray(1).also { glGenRenderbuffers(1, it, 0) }[0]
        glBindRenderbuffer(GL_RENDERBUFFER, depth)
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, width, height)

        // Ввод PhoneXR SDK читаем в своём потоке (read() блокирует до 0.5 с).
        val sdkThread = Thread {
            runCatching {
                PhoneXRInput().use { input ->
                    while (running) input.read()?.let { sdkState = it; sdkTime = System.nanoTime() }
                }
            }.onFailure { Log.w("GorillaXR", "PhoneXR SDK: порт 42425 занят или недоступен: ${it.message}") }
        }.apply { isDaemon = true; start() }

        val sound = Sound().also { it.start() }
        val music = MusicPlayer(this)
        // Трекинг рук камерой (tracking/HandTracker.kt): детекция в своём потоке, игра читает snapshot/palm.
        val hands = if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            HandTracker(this).also { it.start() } else null
        val prefs = getSharedPreferences("gorilla", MODE_PRIVATE)
        val net = Network(prefs)
        val level = HeadLevel(this).also { it.start() }
        val loadingPanel = TextPanel(renderer, 1.2f, 0.3f)
        var game: Game? = null
        var perfCheckNs = 0L
        val frameData = FloatArray(64)
        var last = System.nanoTime()

        while (running) {
            val state = XrBridge.nativePollEvents()
            if (state and 2 != 0) break
            if (state and 1 == 0) { Thread.sleep(10); continue }

            val shouldRender = XrBridge.nativeBeginFrame(frameData)
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
            last = now
            // Наклон «центра» Monado убираем по датчику гравитации (xr/HeadLevel.kt).
            level.update(Quat(frameData[4], frameData[5], frameData[6], frameData[7]), dt)
            val frame = XrFrame(frameData, level.correction)

            if (now - perfCheckNs > 2_000_000_000L) {
                perfCheckNs = now
                com.gorillajumping.render.Perf.skipMap = java.io.File(filesDir, "nomap").exists()
            }

            if (game == null) {
                AssetCache.cpu?.let { c ->
                    // Плакаты MUSOR DROP есть только в праздничной версии (src/prazdnik/assets).
                    val ad = if (BuildConfig.ADS) runCatching {
                        assets.open("ads/musordrop.png").use { ImagePixels.of(android.graphics.BitmapFactory.decodeStream(it)) }
                    }.getOrNull() else null
                    val g = Game(c.collision, GpuAssets(c, renderer), renderer, sound, prefs, music, hands, ad, net)
                    // Спавн в лесу лицом вперёд, куда бы ни смотрела голова.
                    g.player.teleport(g.player.spawnPoint, frame.headPosition, g.player.spawnYaw - frame.headOrientation.yaw())
                    game = g
                    last = System.nanoTime()
                }
            }
            val g = game
            val sdk = sdkState.takeIf { System.nanoTime() - sdkTime < 500_000_000L }
            g?.update(dt, frame, sdk)

            if (shouldRender) {
                for (eye in 0..1) {
                    val tex = XrBridge.nativeAcquire(eye)
                    glBindFramebuffer(GL_FRAMEBUFFER, fbo)
                    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0)
                    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth)
                    glViewport(0, 0, width, height)
                    glClearColor(renderer.fogColor[0], renderer.fogColor[1], renderer.fogColor[2], 1f)
                    glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

                    val e = frame.eyes[eye]
                    val proj = Mat4.projection(e.fov[0], e.fov[1], e.fov[2], e.fov[3], 0.03f, 900f)
                    if (g != null) {
                        val p = g.player
                        val eyeWorld = p.toWorld(e.position)
                        val eyeRot = p.worldRotation(e.orientation)
                        renderer.begin(proj * Mat4.viewFromPose(eyeWorld, eyeRot), eyeWorld)
                        g.draw()
                    } else {
                        renderer.begin(proj * Mat4.viewFromPose(e.position, e.orientation), e.position)
                        loadingPanel.set(AssetCache.error?.let { "Ошибка загрузки: ${it.message}" } ?: AssetCache.progress)
                        loadingPanel.draw(Mat4.translation(frame.headPosition + Vec3(0f, 0f, -1.5f)))
                    }
                    renderer.endEye()
                    glBindFramebuffer(GL_FRAMEBUFFER, 0)
                    XrBridge.nativeRelease(eye)
                }
            }
            XrBridge.nativeEndFrame(shouldRender)
        }

        running = false
        level.stop()
        sound.stop()
        music.stop()
        net.close()
        hands?.stop()
        XrBridge.nativeDestroy()
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglDestroySurface(display, surface)
        runOnUiThread { finish() }
    }

    companion object {
        /**
         * Все сессии OpenXR — в одном потоке, который живёт весь процесс. После xrDestroyInstance загрузчик
         * выгружает библиотеку рантайма, и если поток потом завершится, деструкторы thread_local рантайма
         * позовут уже выгруженный код (SIGSEGV при повторном запуске).
         */
        private val xrThread = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "xr-main").apply { isDaemon = true } }
    }
}
