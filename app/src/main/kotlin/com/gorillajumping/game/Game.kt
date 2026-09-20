package com.gorillajumping.game

import android.content.SharedPreferences
import android.graphics.Color
import com.gorillajumping.audio.MusicPlayer
import com.gorillajumping.audio.Sound
import com.gorillajumping.math.Mat4
import com.gorillajumping.math.Quat
import com.gorillajumping.math.Vec3
import com.gorillajumping.net.NetEvent
import com.gorillajumping.net.Network
import com.gorillajumping.net.ServerInfo
import com.gorillajumping.physics.CollisionWorld
import com.gorillajumping.render.GpuMesh
import com.gorillajumping.render.ImagePixels
import com.gorillajumping.render.Material
import com.gorillajumping.render.Renderer
import com.gorillajumping.render.Shapes
import com.gorillajumping.render.TextPanel
import com.gorillajumping.render.Textures
import com.gorillajumping.tracking.HandTracker
import com.gorillajumping.xr.XrFrame
import com.phonexr.sdk.PhoneXRInput
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.random.Random

/** Что надето: индекс в списке косметики для каждого слота (-1 — ничего) и скин. */
class Outfit(var skin: Int = 0, val items: IntArray = IntArray(Slot.entries.size) { -1 })

/**
 * Карта из модели: точка спавна (x, z и высота, с которой ищем пол вниз) и куда смотреть.
 * [channel] — какой канал сервера: дерево и лес — одна карта.
 */
class MapDef(val id: String, val title: String, val x: Float, val probeY: Float, val z: Float, val yaw: Float, val channel: String = id)

/**
 * Точки найдены трассировкой по the_real_gorilla_tag_map (узлы forest, Tree_0, cave, canyon, mountain,
 * Shopping_Center). Первая — дом: поляна в лесу, со всех сторон деревья (ближайшее в 4 м).
 */
val MAPS = listOf(
    MapDef("forest", "Лес", 43f, 4.5f, -34f, PI.toFloat()),
    MapDef("tree", "Дерево", 65.9f, 16f, -84.5f, PI.toFloat(), "forest"),
    MapDef("cave", "Пещера", 69.3f, -16.5f, -40.65f, 0f),
    MapDef("canyon", "Каньон", 111.5f, 13f, -136.3f, 0f),
    MapDef("mountain", "Горы", -25.7f, 5f, -80.8f, 0f),
    MapDef("city", "Город", 58.6f, 21.5f, -122.15f, 0f)
)

/** Место на карте с поворотом: чтобы раскладывать щиты и кнопки в своих осях. */
private class Frame(val origin: Vec3, yaw: Float) {
    val q = Quat.yaw(yaw)
    val rot = Mat4.rotation(q)
    fun p(x: Float, y: Float, z: Float) = origin + q.rotate(Vec3(x, y, z))
    fun m(x: Float, y: Float, z: Float) = Mat4.translation(p(x, y, z)) * rot
}

class Game(
    private val world: CollisionWorld,
    private val gpu: GpuAssets,
    private val r: Renderer,
    private val sound: Sound,
    private val prefs: SharedPreferences,
    private val music: MusicPlayer,
    private val tracker: HandTracker?,
    adImage: ImagePixels?,
    private val net: Network
) {
    val player = GorillaPlayer(world)
    private val handInputs = arrayOf(HandInput(), HandInput())
    private var headRotWorld = Quat.IDENTITY
    private val handBall = GpuMesh(Shapes.sphere(0.055f, 8, 10))
    private val woodMat = Material(r.whiteTexture, floatArrayOf(0.45f, 0.3f, 0.18f, 1f))
    private val furMat = Material(r.whiteTexture, floatArrayOf(0.2f, 0.2f, 0.22f, 1f))
    private val lavaMat = Material(r.whiteTexture, floatArrayOf(1f, 0.35f, 0.1f, 1f))

    private val outfit = Outfit()

    private var infected = false

    // Дом — поляна в лесу: спавн, бумбокс и табличка с авторами моделей.
    private val home: Vec3
    private val creditsFrame: Frame
    private var currentMap = MAPS[0]
    private var boomboxHome: Vec3
    private var boomboxPos: Vec3
    private var boomboxVel = Vec3.ZERO
    private var boomboxYaw = 0.6f
    private var heldBy = -1
    private val handInside = BooleanArray(2)
    private val handVel = arrayOf(Vec3.ZERO, Vec3.ZERO)
    private val lastHandPos = arrayOf(Vec3.ZERO, Vec3.ZERO)
    private var turnLatch = false
    private val handSource = arrayOf("нет", "нет")
    /** Кулак по трекингу (для хвата бумбокса). */
    private val handFist = BooleanArray(2)
    private var lastHeadPos = Vec3.ZERO
    private var debugTimer = 0f

    private val credits = TextPanel(r, 1.1f, 0.35f, 768)
    private val nameTags = HashMap<String, TextPanel>()
    /** На сервер заходим сами, как только придёт список серверов. */
    private var serverChosen = false

    init {
        val t = MAPS[0]
        home = groundAt(t.x, t.z, t.probeY)
        fun near(dx: Float, dz: Float) = groundAt(home.x + dx, home.z + dz, home.y + 2f)
        creditsFrame = Frame(near(-2.0f, 0.3f), PI.toFloat() / 2) // справа, лицом к игроку
        boomboxHome = near(1.2f, 0.9f) + Vec3(0f, 0.15f, 0f)
        boomboxPos = boomboxHome
        player.boundsMin = world.minBound
        player.boundsMax = world.maxBound
        setSpawn(currentMap)
        player.teleport(player.spawnPoint, Vec3.ZERO, player.spawnYaw)

        loadOutfit()
        credits.set(
            "Модели CC-BY-4.0 (Sketchfab): карта — N0ahSw1ft,\nгориллы — KPMisParrot, косметика — JimboS2024,\nбумбокс — fergasol. Фанатская игра, не Another Axiom.",
            Color.argb(255, 20, 20, 24)
        )
    }

    private fun groundAt(x: Float, z: Float, probeY: Float = 20f): Vec3 {
        val hit = world.raycast(Vec3(x, probeY, z), Vec3.DOWN, 60f)
        return hit?.point ?: Vec3(x, probeY - 2f, z)
    }

    private fun setSpawn(m: MapDef) {
        val ground = groundAt(m.x, m.z, m.probeY)
        player.spawnPoint = ground + Vec3(0f, HEAD_HEIGHT, 0f)
        player.spawnYaw = m.yaw
    }

    /** Надписей в шлеме нет: события игры — только в logcat. */
    private fun show(text: String) = android.util.Log.i("GorillaXR", text)

    // --- Наряд: последний сохранённый ---

    private fun loadOutfit() {
        outfit.skin = prefs.getInt("skin", 0).coerceIn(0, SKINS.size - 2)
        for (s in Slot.entries) {
            val name = prefs.getString("slot_${s.name}", null)
            outfit.items[s.ordinal] = gpu.cosmetics.indexOfFirst { it.first.title == name && it.first.slot == s }
        }
    }

    /** Сразу на сервер: где больше всего игроков и есть место. Нет свободных — играем офлайн. */
    private fun joinBestServer() {
        if (serverChosen || net.servers.isEmpty()) return
        serverChosen = true
        val s = net.servers.filter { it.online < it.maxPlayers }.maxByOrNull { it.online } ?: run { show("Свободных серверов нет — офлайн"); return }
        net.join(s, currentMap.channel)
        show("Сервер: ${s.name}")
    }

    // --- Кадр ---

    private var lastHeadOri = Quat.IDENTITY

    fun update(dt: Float, f: XrFrame, sdk: PhoneXRInput.State?) {
        // Руки — трекинг PhoneXR. Если камеру держит сам PhoneXR, берём руки, которые он отдаёт в OpenXR.
        val own = tracker?.takeIf { it.working }
        for (i in 0..1) {
            val xh = f.hands[i]
            val s = sdk?.let { if (i == 0) it.left else it.right }
            val h = own?.snapshot(i)?.takeIf { it.present }
            val raw = if (own != null) own.palm(i, f.headPosition, f.headOrientation) else xh.position.takeIf { xh.valid }
            handSource[i] = if (own != null) (if (h != null) "камера" else "нет") else if (xh.valid) "OpenXR" else "нет"
            handInputs[i].update(raw?.let { gorillaArm(it, f.headPosition) }, raw != null)
            handFist[i] = h?.fist ?: (s?.fist == true || xh.squeeze > 0.6f)
        }
        lastHeadPos = f.headPosition
        lastHeadOri = f.headOrientation
        debugTimer -= dt
        if (debugTimer <= 0f) {
            debugTimer = 2f
            android.util.Log.i("GorillaXR", "hands L=${handSource[0]} R=${handSource[1]} cam=${tracker?.status} ${tracker?.cameraFps}/${tracker?.detectFps} к/с sdk=${sdk != null} " +
                "net=${net.status} players=${net.players.size} bots=" +
                bots.joinToString(" ") { "%.1f,%.1f,%.1f%s".format(it.head.x, it.head.y, it.head.z, if (it.infected) "*" else "") } +
                " me=%.1f,%.1f,%.1f%s".format(player.head.x, player.head.y, player.head.z, if (infected) "*" else ""))
        }

        // Стик Joy-Con: поворот на 30°.
        val sx = f.hands[1].stickX + (sdk?.right?.stickX ?: 0f)
        if (!turnLatch && kotlin.math.abs(sx) > 0.7f) { player.turn(if (sx > 0) -PI.toFloat() / 6 else PI.toFloat() / 6, f.headPosition); turnLatch = true }
        if (kotlin.math.abs(sx) < 0.3f) turnLatch = false

        player.update(dt, f.headPosition, handInputs)

        for (i in 0..1) {
            val h = player.hands[i]
            handVel[i] = (h.position - lastHandPos[i]) / dt.coerceAtLeast(1e-3f)
            lastHandPos[i] = h.position
            if (h.justTouched) sound.tap((handVel[i].length() / 4f).coerceIn(0.15f, 1f), if (i == 0) -0.6f else 0.6f)
        }

        headRotWorld = player.worldRotation(f.headOrientation)
        updateSwing(dt, headRotWorld.rotate(Vec3(0f, 0f, -1f)))

        joinBestServer()
        updateBoombox(dt, f, sdk)
        updateNetwork(dt)
        updateBots(dt)

    }

    private fun handActive(i: Int) = player.hands[i].valid

    private fun gripping(i: Int, f: XrFrame, sdk: PhoneXRInput.State?): Boolean {
        val s = sdk?.let { if (i == 0) it.left else it.right }
        return f.hands[i].squeeze > 0.6f || s?.fist == true || handFist[i] || s?.isPressed(PhoneXRInput.Button.SQUEEZE) == true
    }

    private fun updateBoombox(dt: Float, f: XrFrame, sdk: PhoneXRInput.State?) {
        if (heldBy >= 0) {
            val h = player.hands[heldBy]
            if (!gripping(heldBy, f, sdk) || !h.valid) {
                boomboxVel = handVel[heldBy].clampLength(8f)
                heldBy = -1
            } else {
                boomboxPos = h.position + Vec3(0f, -0.05f, 0f)
                boomboxYaw = player.yaw + f.headOrientation.yaw()
            }
        }
        if (heldBy < 0) {
            boomboxVel += Vec3.DOWN * (GorillaPlayer.GRAVITY * dt)
            boomboxPos += boomboxVel * dt
            val (p, n) = world.pushOut(boomboxPos, 0.14f)
            // На земле выталкиваем строго вверх: иначе бумбокс сползает по любому склону.
            boomboxPos = if (n != null && n.y > 0.5f) boomboxPos + Vec3(0f, (p - boomboxPos).length() / n.y, 0f) else p
            if (n != null) {
                val into = boomboxVel dot n
                if (into < 0) boomboxVel -= n * (into * 1.4f)
                if (n.y > 0.5f) {
                    boomboxVel = Vec3(boomboxVel.x * 0.9f, boomboxVel.y, boomboxVel.z * 0.9f)
                    if (boomboxVel.horizontal().length() < 0.05f) boomboxVel = Vec3(0f, boomboxVel.y, 0f)
                }
            }
            if (boomboxPos.y < world.minBound.y - 5f) { boomboxPos = boomboxHome + Vec3(0f, 0.3f, 0f); boomboxVel = Vec3.ZERO }
            for (i in 0..1) {
                val near = handActive(i) && (player.hands[i].position - boomboxPos).length() < 0.26f
                if (near && gripping(i, f, sdk)) { heldBy = i; break }
                if (near && !handInside[i]) { music.toggle(); sound.click() }
                handInside[i] = near
            }
        }
        // Громкость и панорама от положения бумбокса относительно головы.
        val rel = boomboxPos - player.head
        val dist = rel.length()
        val vol = (1.2f / (1f + dist * 0.35f)).coerceAtMost(1f)
        val right = player.worldRotation(f.headOrientation).rotate(Vec3(1f, 0f, 0f))
        music.setSpatial(vol, (rel.normalized() dot right).coerceIn(-0.8f, 0.8f))
    }

    // --- Сеть: другие игроки и «заражение» ---

    private var noItTimer = 0f
    private var allTaggedTimer = 0f

    private fun updateNetwork(dt: Float) {
        // Своё состояние: голова, куда смотрит модель, руки, наряд.
        val fwd = headRotWorld.rotate(Vec3(0f, 0f, -1f))
        net.sendState(
            player.head, atan2(fwd.x, fwd.z), player.hands[0].position, player.hands[1].position,
            if (infected) LAVA_SKIN else outfit.skin, outfit.items, infected
        )
        net.prune()
        for (p in net.players.values) p.smooth(dt)

        while (true) {
            when (val e = net.events.poll() ?: break) {
                is NetEvent.Tagged -> if (!infected) { infected = true; sound.tag(); show("Тебя осалили! Теперь води") }
                is NetEvent.Round -> startRoundLocal(e.it)
            }
        }
        val others = net.players.values.toList()
        if (!net.online || others.isEmpty()) return // без живых игроков раунды ведут боты (updateBots)

        // Я вожу: касание рукой головы или тела другой гориллы осаливает её.
        if (infected) for (p in others) {
            if (p.infected) continue
            val body = p.head + Vec3(0f, -0.35f, 0f)
            for (i in 0..1) {
                if (!handActive(i)) continue
                val hand = player.hands[i].position
                if ((hand - body).length() < 0.45f || (hand - p.head).length() < 0.3f) {
                    p.infected = true
                    net.sendTag(p.id)
                    sound.tag()
                    break
                }
            }
        }

        // Раундами управляет «хост» — игрок с наименьшим id в комнате.
        if (others.any { it.id < net.playerId }) return
        val states = others.map { it.infected } + infected
        when {
            states.none { it } -> { noItTimer += dt; if (noItTimer > 2f) newRound(others.map { it.id } + net.playerId) }
            states.all { it } -> {
                if (allTaggedTimer == 0f) show("Все осалены! Новый раунд…")
                allTaggedTimer += dt
                if (allTaggedTimer > 4f) newRound(others.map { it.id } + net.playerId)
            }
            else -> { noItTimer = 0f; allTaggedTimer = 0f }
        }
    }

    private fun newRound(ids: List<String>) {
        noItTimer = 0f; allTaggedTimer = 0f
        val it = ids.random()
        net.sendRound(it)
        startRoundLocal(it)
    }

    private fun startRoundLocal(it: String) {
        infected = it == net.playerId
        for (p in net.players.values) p.infected = p.id == it
        for (b in bots) { b.infected = false; b.waitTimer = 0f }
        show(if (infected) "Новый раунд! Ты водишь" else "Новый раунд! Убегай от лавовой гориллы")
        if (infected) sound.tag()
    }

    // --- Взмахи руками: ход и прыжок, когда рука не достала до земли ---

    private val swingCooldown = floatArrayOf(0f, 0f)
    private var lastSwingNs = 0L

    /**
     * В Gorilla Tag толкаются руками от земли. Камера телефона руку у самой земли видит не всегда,
     * поэтому резкий мах вниз тоже толкает — вперёд по взгляду и вверх, двумя руками сразу прыжок выше.
     * Направление берём от головы, а не от движения руки: глубину камера видит плохо, и с ней
     * направление скакало. Назад — развернувшись. В воздухе махать бесполезно: нужна опора.
     */
    private fun updateSwing(dt: Float, headForward: Vec3) {
        for (i in 0..1) if (swingCooldown[i] > 0f) swingCooldown[i] -= dt
        if (!player.grounded && !player.hands.any { it.touching }) return
        for (i in 0..1) {
            val h = player.hands[i]
            if (!h.valid || h.touching || swingCooldown[i] > 0f) continue
            val down = -handVel[i].y
            if (down < SWING_SPEED || h.position.y > player.head.y - SWING_BELOW) continue
            swingCooldown[i] = SWING_COOLDOWN
            val dir = headForward.horizontal().normalized()
            val strength = (down - SWING_SPEED).coerceAtMost(3f)
            val both = System.nanoTime() - lastSwingNs < 200_000_000L
            lastSwingNs = System.nanoTime()
            var v = player.velocity + dir * (strength * SWING_FORWARD) +
                Vec3(0f, strength * SWING_UP * (if (both) 1.7f else 1f), 0f)
            val hor = v.horizontal()
            if (hor.length() > SWING_MAX_SPEED) v = hor.normalized() * SWING_MAX_SPEED + Vec3(0f, v.y, 0f)
            player.velocity = Vec3(v.x, v.y.coerceAtMost(GorillaPlayer.MAX_JUMP_UP), v.z)
            sound.tap((strength / 3f).coerceIn(0.2f, 1f), if (i == 0) -0.6f else 0.6f)
        }
    }

    // --- Боты: заполняют комнату, пока нет живых игроков ---

    private val bots = ArrayList<Bot>()
    private var botNoItTimer = 0f
    private var botAllTimer = 0f

    /** Сколько горилл в комнате вместе с тобой: живые игроки вытесняют ботов по одному. */
    private fun wantedBots() = (ROOM_SIZE - 1 - net.players.size).coerceAtLeast(0)

    private fun spawnBot(): Bot {
        val a = Random.nextFloat() * 2f * PI.toFloat()
        val d = 2.5f + Random.nextFloat() * 2f
        val g = groundAt(home.x + kotlin.math.cos(a) * d, home.z + kotlin.math.sin(a) * d, home.y + 3f)
        val items = IntArray(Slot.entries.size) { slot ->
            val list = gpu.cosmetics.indices.filter { gpu.cosmetics[it].first.slot == Slot.entries[slot] }
            if (list.isEmpty() || Random.nextBoolean()) -1 else list.random()
        }
        return Bot(world, "Горилла${(1000..9999).random()}", Random.nextInt(SKINS.size - 1), items,
            g + Vec3(0f, HEAD_HEIGHT, 0f), Random.nextFloat() * 2f * PI.toFloat())
    }

    private fun updateBots(dt: Float) {
        // Зашёл живой игрок — уходит один бот; ушёл — бот возвращается.
        while (bots.size > wantedBots()) bots.removeAt(bots.size - 1)
        while (bots.size < wantedBots()) bots += spawnBot()
        if (bots.isEmpty()) return

        for (b in bots) {
            // Водящий гонится за ближайшим неосаленным, остальные убегают от ближайшего водящего.
            val target: Vec3?
            val flee: Boolean
            if (b.infected) {
                val prey = (listOf(player.head.takeIf { !infected }) + bots.filter { !it.infected }.map { it.head }).filterNotNull()
                target = prey.minByOrNull { (it - b.head).length() }
                flee = false
            } else {
                val hunters = (listOf(player.head.takeIf { infected }) + bots.filter { it.infected }.map { it.head } +
                    net.players.values.filter { it.infected }.map { it.head }).filterNotNull()
                target = hunters.minByOrNull { (it - b.head).length() }?.takeIf { (it - b.head).length() < FLEE_RADIUS }
                flee = true
            }
            val hz = when { target == null -> WANDER_HZ; flee -> FLEE_HZ; else -> CHASE_HZ }
            b.update(dt, target, flee, hz)
        }

        // Осаливание. Твои руки дотягиваются как у игроков (0.45 м), руки ботов короче — тебе легче.
        fun touches(hand: Vec3, head: Vec3, reach: Float) =
            (hand - (head + Vec3(0f, -0.35f, 0f))).length() < reach || (hand - head).length() < reach * 0.66f
        for (b in bots) {
            if (b.infected) continue
            if (infected && (0..1).any { handActive(it) && touches(player.hands[it].position, b.head, 0.45f) }) {
                b.infected = true; sound.tag(); continue
            }
            if (bots.any { o -> o.infected && o.waitTimer <= 0f && (0..1).any { touches(o.hand(it), b.head, BOT_REACH) } }) b.infected = true
        }
        if (!infected && bots.any { o -> o.infected && o.waitTimer <= 0f && (0..1).any { touches(o.hand(it), player.head, BOT_REACH) } }) {
            infected = true; sound.tag(); show("Тебя осалил бот! Теперь води")
        }

        // Раунды, пока в комнате нет живых игроков.
        if (net.players.isNotEmpty()) return
        val states = bots.map { it.infected } + infected
        when {
            states.none { it } -> { botNoItTimer += dt; if (botNoItTimer > 2f) newBotRound() }
            states.all { it } -> { botAllTimer += dt; if (botAllTimer > 4f) newBotRound() }
            else -> { botNoItTimer = 0f; botAllTimer = 0f }
        }
    }

    /** Новый раунд с ботами. Чаще водит бот: так тебе легче. */
    private fun newBotRound() {
        botNoItTimer = 0f; botAllTimer = 0f
        for (b in bots) { b.infected = false; b.waitTimer = 0f }
        infected = false
        if (Random.nextFloat() < ME_IT_CHANCE) {
            infected = true; sound.tag(); show("Новый раунд! Ты водишь")
        } else {
            val it = bots.random()
            it.infected = true
            it.waitTimer = BOT_START_WAIT
            show("Новый раунд! Убегай от ${it.name}")
        }
    }

    // --- Реклама MUSOR DROP (только в праздничной версии) ---

    private val adQuad = GpuMesh(Shapes.quad(2.8f, 1f))
    private val adPost = GpuMesh(Shapes.box(0.12f, 1f, 0.12f))
    private val adMaterial = adImage?.let { Material(Textures.upload(it, repeat = false), unlit = true) }
    private val ads = ArrayList<Pair<Mat4, Mat4>>()

    init { placeAds() } // здесь, а не в первом init: поля выше должны быть уже созданы

    private fun placeAds() {
        if (adMaterial == null) return
        // Плакаты на столбах в просветах между деревьями вокруг поляны.
        for ((dx, dz) in listOf(8f to 2f, -4f to -7f, 0f to -8f)) {
            val g = groundAt(home.x + dx, home.z + dz, home.y + 3f)
            val yaw = atan2(-dx, -dz) // плашка смотрит на поляну
            val pos = g + Vec3(0f, 2.3f, 0f)
            ads += (Mat4.translation(pos) * Mat4.rotation(Quat.yaw(yaw))) to
                (Mat4.translation(pos + Vec3(0f, -1.3f, -0.05f)) * Mat4.scale(Vec3(1f, 2.1f, 1f)))
        }
    }

    // --- Отрисовка ---

    fun draw() {
        gpu.sky.draw(r, Mat4.identity())
        if (!com.gorillajumping.render.Perf.skipMap) gpu.map.draw(r)

        // Другие игроки: горилла под головой, руки шарами, имя над головой.
        for (p in net.players.values) {
            drawGorilla(p.head + Vec3(0f, -HEAD_ABOVE_ORIGIN, 0f), p.yaw, p.skin.coerceIn(0, SKINS.size - 1), p.items)
            for (h in p.hands) r.draw(handBall, if (p.infected) lavaMat else furMat, Mat4.translation(h))
            val tag = nameTags.getOrPut(p.id) { TextPanel(r, 0.5f, 0.09f) }
            tag.set(p.name, if (p.infected) Color.rgb(150, 40, 10) else Color.argb(255, 20, 20, 24))
            val toMe = (player.head - p.head).horizontal()
            tag.draw(Mat4.translation(p.head + Vec3(0f, 0.35f, 0f)) * Mat4.rotation(Quat.yaw(atan2(toMe.x, toMe.z))))
        }
        // Боты рисуются так же, как живые игроки.
        for (b in bots) {
            drawGorilla(b.head + Vec3(0f, -HEAD_ABOVE_ORIGIN, 0f), b.drawYaw, if (b.infected) LAVA_SKIN else b.skin, b.items)
            for (i in 0..1) r.draw(handBall, if (b.infected) lavaMat else furMat, Mat4.translation(b.hand(i)))
            val tag = nameTags.getOrPut(b.name) { TextPanel(r, 0.5f, 0.09f) }
            tag.set(b.name, if (b.infected) Color.rgb(150, 40, 10) else Color.argb(255, 20, 20, 24))
            val toMe = (player.head - b.head).horizontal()
            tag.draw(Mat4.translation(b.head + Vec3(0f, 0.35f, 0f)) * Mat4.rotation(Quat.yaw(atan2(toMe.x, toMe.z))))
        }
        if (nameTags.size > net.players.size + bots.size + 8) nameTags.keys.retainAll(net.players.keys + bots.map { it.name })


        credits.draw(creditsFrame.m(-1.1f, 0.8f, 0f))

        gpu.boombox.draw(r, Mat4.translation(boomboxPos) * Mat4.rotation(Quat.yaw(boomboxYaw)))
        adMaterial?.let { m -> for ((ad, post) in ads) { r.draw(adQuad, m, ad); r.draw(adPost, woodMat, post) } }

        // Руки игрока — настоящие руки твоей гориллы (твой скин) из модели: от плеча невидимого тела
        // до точки, где рука касается мира. Руку поворачиваем от плеча к кулаку и чуть тянем по длине.
        val skin = if (infected) LAVA_SKIN else outfit.skin
        val fwd = headRotWorld.rotate(Vec3(0f, 0f, -1f))
        val bodyQ = Quat.yaw(atan2(fwd.x, fwd.z))
        val back = Quat.yaw(atan2(fwd.x, fwd.z) + PI.toFloat())
        for (i in 0..1) {
            val h = player.hands[i]
            val side = if (i == 0) -1f else 1f
            // Модель смотрит в +Z: её левая рука (+X) — это левая рука игрока.
            val (arm, axisModel) = gpu.arms[skin][i]
            val fist = h.position + Vec3(0f, HAND_LIFT, 0f)
            val shoulder = player.head + back.rotate(Vec3(side * SHOULDER_SIDE, -SHOULDER_DOWN, SHOULDER_BACK))
            val axis = bodyQ.rotate(axisModel)
            val target = fist - shoulder
            val len = target.length().coerceAtLeast(1e-3f)
            val scale = (len / axisModel.length()).coerceIn(0.6f, 1.6f)
            val rot = fromTo(axis.normalized(), target / len) * bodyQ
            // Кулак ровно в точке касания; плечо может чуть «плавать» — тела всё равно не видно.
            val origin = fist - rot.rotate(axisModel * scale)
            arm.draw(r, Mat4.translation(origin) * Mat4.rotation(rot) * Mat4.scale(Vec3(scale, scale, scale)))
        }

    }

    private fun drawGorilla(center: Vec3, yaw: Float, skin: Int, items: IntArray) {
        val m = Mat4.translation(center) * Mat4.rotation(Quat.yaw(yaw))
        gpu.skins[skin].draw(r, m)
        for (idx in items) if (idx >= 0) gpu.cosmetics.getOrNull(idx)?.second?.draw(r, m)
    }

    private fun fromTo(a: Vec3, b: Vec3): Quat {
        val c = a dot b
        if (c > 0.9999f) return Quat.IDENTITY
        if (c < -0.9999f) return Quat.axisAngle(Vec3(1f, 0f, 0f), PI.toFloat())
        val axis = (a cross b).normalized()
        return Quat.axisAngle(axis, kotlin.math.acos(c.coerceIn(-1f, 1f)))
    }

    /**
     * Длинные руки гориллы. Камера видит руку только перед лицом (примерно до 30 см ниже глаз),
     * а голова гориллы в ~75 см над землёй — без этого рука не достаёт до пола и толкаться нечем.
     * Высоту руки относительно головы растягиваем ×[ARM_STRETCH] и опускаем на [ARM_DROP]:
     * рука у глаз — на уровне груди, рука внизу кадра — в земле. Вниз-вверх руками = прыжок.
     */
    private fun gorillaArm(hand: Vec3, head: Vec3): Vec3 {
        val rel = hand - head
        return head + Vec3(rel.x * ARM_SIDE, rel.y * ARM_STRETCH - ARM_DROP, rel.z)
    }

    companion object {
        /** Мах вниз быстрее этого (м/с) толкает тело. */
        const val SWING_SPEED = 1.2f
        /** И только если рука опустилась ниже головы на столько (м). */
        const val SWING_BELOW = 0.25f
        /** Пауза между толчками одной рукой, с. */
        const val SWING_COOLDOWN = 0.3f
        /** Во что превращается мах: ход вперёд и подъём. */
        const val SWING_FORWARD = 0.9f
        const val SWING_UP = 0.6f
        /** Быстрее по земле от махов не разогнаться, м/с. */
        const val SWING_MAX_SPEED = 6f

        /** Горилл в комнате вместе с тобой; недостающих до этого числа заменяют боты. */
        const val ROOM_SIZE = 4
        // Боты медленнее человека: гребков в секунду, когда гонятся, убегают и бродят.
        const val CHASE_HZ = 1.25f
        const val FLEE_HZ = 1.15f
        const val WANDER_HZ = 0.6f
        /** Бот убегает, только если водящий ближе этого (м). */
        const val FLEE_RADIUS = 9f
        /** Дотягивание руки бота (у игрока 0.45 м). */
        const val BOT_REACH = 0.32f
        /** Водящий бот стоит в начале раунда, с. */
        const val BOT_START_WAIT = 3f
        /** Как часто водишь ты, а не бот. */
        const val ME_IT_CHANCE = 0.25f
        // Плечи невидимого тела относительно глаз (в сторону, вниз, назад) и длина костей руки.
        const val SHOULDER_SIDE = 0.2f
        const val SHOULDER_DOWN = 0.3f
        const val SHOULDER_BACK = 0.05f
        const val ARM_STRETCH = 1.6f
        const val ARM_DROP = 0.35f
        const val ARM_SIDE = 1.2f
        /** Кисть модели ~20 см высотой, а точка касания — сфера 5 см: поднимаем кисть над точкой. */
        const val HAND_LIFT = 0.05f
        const val HEAD_HEIGHT = 1.0f
        /** Глаза гориллы над началом модели (GorillaAnchors: центр лица ≈ 0.43). */
        const val HEAD_ABOVE_ORIGIN = 0.43f
    }
}
