package com.gorillajumping.net

import android.content.SharedPreferences
import android.util.Log
import com.gorillajumping.math.Vec3
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Проект Supabase (тот же, что у PhoneXR). Таблицы и функции — в gorillajump.sql в корне проекта. */
object Supabase {
    const val URL = "https://fjiostsfwfennbovpolc.supabase.co"
    /** Публикуемый ключ проекта (сделан, чтобы лежать в приложениях). */
    const val KEY = "sb_publishable_0we8-Uw_XxKmUINy6KOjDA_lySgpLiA"
}

class ServerInfo(val id: String, val name: String, val region: String, val maxPlayers: Int, val online: Int)

/** Другой игрок в комнате: последнее присланное состояние и сглаженное для отрисовки. */
class RemotePlayer(val id: String) {
    var name = ""
    var skin = 0
    var items = IntArray(0)
    var infected = false
    var head = Vec3.ZERO
    var yaw = 0f
    val hands = arrayOf(Vec3.ZERO, Vec3.ZERO)
    // Цели от сети; к ним плавно подтягиваемся в [smooth].
    @Volatile var targetHead = Vec3.ZERO
    @Volatile var targetYaw = 0f
    val targetHands = arrayOf(Vec3.ZERO, Vec3.ZERO)
    @Volatile var lastSeenNs = 0L
    var fresh = true

    fun smooth(dt: Float) {
        if (fresh) {
            head = targetHead; yaw = targetYaw; hands[0] = targetHands[0]; hands[1] = targetHands[1]; fresh = false
            return
        }
        val k = (dt * 14f).coerceAtMost(1f)
        head = head.lerp(targetHead, k)
        var dy = targetYaw - yaw
        while (dy > Math.PI) dy -= (2 * Math.PI).toFloat()
        while (dy < -Math.PI) dy += (2 * Math.PI).toFloat()
        yaw += dy * k
        for (i in 0..1) hands[i] = hands[i].lerp(targetHands[i], k)
    }
}

/** Событие от других игроков для игрового потока. */
sealed class NetEvent {
    class Tagged(val by: String) : NetEvent()
    class Round(val it: String) : NetEvent()
}

/**
 * Мультиплеер: список серверов из таблицы gorillajump_servers (REST), игроки одной карты одного
 * сервера — канал Supabase Realtime «gj:<сервер>:<карта>» с broadcast-сообщениями. Базу игра
 * не нагружает: раз в 10 с отмечается, где игрок (для счётчика онлайна в меню).
 */
class Network(prefs: SharedPreferences) {
    val playerId: String = prefs.getString("player_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("player_id", it).apply() }
    val playerName: String = prefs.getString("player_name", null)
        ?: "Горилла${(1000..9999).random()}".also { prefs.edit().putString("player_name", it).apply() }

    @Volatile var servers: List<ServerInfo> = emptyList()
        private set
    @Volatile var status = "Офлайн"
        private set
    @Volatile var server: ServerInfo? = null
        private set
    @Volatile var map = "forest"
        private set
    val players = ConcurrentHashMap<String, RemotePlayer>()
    val events = ConcurrentLinkedQueue<NetEvent>()

    private val io = Executors.newSingleThreadScheduledExecutor()
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var topic: String? = null
    private val ref = AtomicInteger(1)
    private var lastSendNs = 0L

    init {
        io.scheduleWithFixedDelay({ runCatching { refreshServers() } }, 0, 15, TimeUnit.SECONDS)
        io.scheduleWithFixedDelay({ runCatching { heartbeat() } }, 5, 10, TimeUnit.SECONDS)
        io.scheduleWithFixedDelay({ send("phoenix", "heartbeat", JSONObject()) }, 25, 25, TimeUnit.SECONDS)
    }

    val online get() = server != null

    /** Зайти на сервер (null — офлайн) и карту. Можно звать из игрового потока. */
    fun join(target: ServerInfo?, mapId: String) = io.execute {
        if (target == null && server == null) { map = mapId; return@execute }
        if (target?.id == server?.id && mapId == map && connected) return@execute
        topic?.let { send("realtime:$it", "phx_leave", JSONObject()) }
        topic = null
        players.clear()
        server = target
        map = mapId
        if (target == null) {
            socket?.close(1000, null); socket = null; connected = false
            status = "Офлайн"
            runCatching { rpc("gorillajump_leave", JSONObject().put("p_player", playerId)) }
            return@execute
        }
        status = "Подключение к ${target.name}…"
        if (socket == null) openSocket()
        if (connected) joinTopic()
        runCatching { heartbeat() }
    }

    private fun openSocket() {
        val host = Supabase.URL.removePrefix("https://")
        val request = Request.Builder().url("wss://$host/realtime/v1/websocket?apikey=${Supabase.KEY}&vsn=1.0.0").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                io.execute { connected = true; joinTopic() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "realtime: ${t.message}")
                io.execute { lost(webSocket) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                io.execute { lost(webSocket) }
            }
        })
    }

    /** Связь пропала: через 3 с переподключаемся, если игрок всё ещё на сервере. */
    private fun lost(ws: WebSocket) {
        if (socket !== ws) return
        socket = null; connected = false; topic = null
        players.clear()
        if (server == null) return
        status = "Нет связи, переподключаюсь…"
        io.schedule({ if (server != null && socket == null) openSocket() }, 3, TimeUnit.SECONDS)
    }

    private fun joinTopic() {
        val s = server ?: return
        val t = "gj:${s.id}:$map"
        topic = t
        val config = JSONObject()
            .put("broadcast", JSONObject().put("self", false).put("ack", false))
            .put("presence", JSONObject().put("key", playerId))
            .put("private", false)
        send("realtime:$t", "phx_join", JSONObject().put("config", config))
        status = s.name
    }

    private fun send(topic: String, event: String, payload: JSONObject) {
        val ws = socket ?: return
        val message = JSONObject().put("topic", topic).put("event", event).put("payload", payload).put("ref", ref.getAndIncrement().toString())
        ws.send(message.toString())
    }

    private fun broadcast(event: String, payload: JSONObject) {
        val t = topic ?: return
        if (!connected) return
        send("realtime:$t", "broadcast", JSONObject().put("type", "broadcast").put("event", event).put("payload", payload))
    }

    /** Состояние игрока, не чаще 10 раз в секунду (лимиты Realtime). */
    fun sendState(head: Vec3, yaw: Float, left: Vec3, right: Vec3, skin: Int, items: IntArray, infected: Boolean) {
        val now = System.nanoTime()
        if (now - lastSendNs < 100_000_000L || topic == null) return
        lastSendNs = now
        fun v(p: Vec3) = JSONArray().put(r(p.x)).put(r(p.y)).put(r(p.z))
        val payload = JSONObject().put("id", playerId).put("n", playerName)
            .put("h", v(head)).put("y", r(yaw)).put("l", v(left)).put("r", v(right))
            .put("s", skin).put("c", JSONArray(items.toList())).put("i", infected)
        io.execute { broadcast("state", payload) }
    }

    fun sendTag(target: String) = io.execute { broadcast("tag", JSONObject().put("target", target).put("by", playerId)) }

    fun sendRound(it: String) = io.execute { broadcast("round", JSONObject().put("it", it)) }

    private fun r(f: Float) = Math.round(f * 1000.0) / 1000.0

    private fun handle(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        val payload = message.optJSONObject("payload") ?: return
        when (message.optString("event")) {
            "broadcast" -> {
                val data = payload.optJSONObject("payload") ?: return
                when (payload.optString("event")) {
                    "state" -> onState(data)
                    "tag" -> if (data.optString("target") == playerId) events += NetEvent.Tagged(data.optString("by"))
                    "round" -> events += NetEvent.Round(data.optString("it"))
                }
            }
            "phx_reply" -> if (payload.optString("status") == "error") {
                status = "Ошибка сервера: ${payload.optJSONObject("response")?.optString("reason") ?: "?"}"
            }
        }
    }

    private fun onState(d: JSONObject) {
        val id = d.optString("id").takeIf { it.isNotEmpty() && it != playerId } ?: return
        fun v(key: String): Vec3? = d.optJSONArray(key)?.takeIf { it.length() == 3 }?.let {
            Vec3(it.optDouble(0).toFloat(), it.optDouble(1).toFloat(), it.optDouble(2).toFloat())
        }
        val p = players.getOrPut(id) { RemotePlayer(id) }
        p.name = d.optString("n").take(24)
        p.skin = d.optInt("s")
        p.items = d.optJSONArray("c")?.let { a -> IntArray(a.length()) { a.optInt(it, -1) } } ?: IntArray(0)
        p.infected = d.optBoolean("i")
        v("h")?.let { p.targetHead = it }
        p.targetYaw = d.optDouble("y").toFloat()
        v("l")?.let { p.targetHands[0] = it }
        v("r")?.let { p.targetHands[1] = it }
        p.lastSeenNs = System.nanoTime()
    }

    /** Убирает тех, от кого 3 секунды ничего не было. */
    fun prune() {
        val now = System.nanoTime()
        players.values.removeIf { now - it.lastSeenNs > 3_000_000_000L }
    }

    fun refreshServers() {
        val answer = JSONArray(rpc("gorillajump_server_list", JSONObject()))
        servers = List(answer.length()) { i ->
            val o = answer.getJSONObject(i)
            ServerInfo(o.getString("id"), o.getString("name"), o.optString("region"), o.optInt("max_players", 10), o.optInt("online"))
        }
    }

    private fun heartbeat() {
        val s = server ?: return
        rpc("gorillajump_heartbeat", JSONObject().put("p_player", playerId).put("p_server", s.id).put("p_map", map).put("p_name", playerName))
    }

    fun close() {
        server?.let { runCatching { io.submit { rpc("gorillajump_leave", JSONObject().put("p_player", playerId)) }.get(2, TimeUnit.SECONDS) } }
        io.shutdownNow()
        socket?.close(1000, null)
    }

    private fun rpc(name: String, body: JSONObject): String {
        val c = URL("${Supabase.URL}/rest/v1/rpc/$name").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.doOutput = true
        c.setRequestProperty("apikey", Supabase.KEY)
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        c.disconnect()
        if (code !in 200..299) error("$name: HTTP $code $text")
        return text.ifBlank { "null" }
    }

    private companion object { const val TAG = "GorillaNet" }
}
