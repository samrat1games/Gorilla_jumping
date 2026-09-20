package com.gorillajumping.gltf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.gorillajumping.math.Mat4
import com.gorillajumping.math.Quat
import com.gorillajumping.math.Vec3
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Материал glTF в том объёме, который нужен игре: цвет, текстура, прозрачность. */
class GltfMaterial(
    val name: String,
    val baseColor: FloatArray,
    val image: Int,
    val alphaMode: String,
    val uvTransform: FloatArray? // KHR_texture_transform: offset.xy, scale.xy, rotation
)

class GltfNode(
    val name: String,
    val local: Mat4,
    val mesh: Int,
    val children: IntArray
)

/** Геометрия одного примитива, уже в нужной системе координат. */
class MeshData(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val indices: IntArray,
    val material: Int
) {
    val vertexCount get() = positions.size / 3
}

/**
 * Разбор .glb (glTF 2.0 бинарный). Модели из папки проекта экспортированы Sketchfab-ом:
 * без Draco и без KTX2, так что хватает простого чтения аксессоров.
 */
class Glb(bytes: ByteArray) {
    private val json: JSONObject
    private val bin: ByteBuffer
    val nodes: List<GltfNode>
    val materials: List<GltfMaterial>
    val sceneRoots: IntArray
    private val parents: IntArray

    init {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.getInt(0) == 0x46546C67) { "Not a GLB file" }
        val jsonLength = buf.getInt(12)
        json = JSONObject(String(bytes, 20, jsonLength, Charsets.UTF_8))
        val binStart = 20 + jsonLength
        val binLength = buf.getInt(binStart)
        bin = ByteBuffer.wrap(bytes, binStart + 8, binLength).slice().order(ByteOrder.LITTLE_ENDIAN)

        val jNodes = json.optJSONArray("nodes") ?: JSONArray()
        nodes = List(jNodes.length()) { i ->
            val n = jNodes.getJSONObject(i)
            GltfNode(
                n.optString("name", "node$i"),
                localMatrix(n),
                n.optInt("mesh", -1),
                n.optJSONArray("children")?.let { a -> IntArray(a.length()) { a.getInt(it) } } ?: IntArray(0)
            )
        }
        parents = IntArray(nodes.size) { -1 }
        nodes.forEachIndexed { i, n -> n.children.forEach { parents[it] = i } }

        val textures = json.optJSONArray("textures") ?: JSONArray()
        val jMaterials = json.optJSONArray("materials") ?: JSONArray()
        materials = List(jMaterials.length()) { i ->
            val m = jMaterials.getJSONObject(i)
            val pbr = m.optJSONObject("pbrMetallicRoughness") ?: JSONObject()
            val factor = pbr.optJSONArray("baseColorFactor")
            val color = FloatArray(4) { factor?.optDouble(it, 1.0)?.toFloat() ?: 1f }
            val texInfo = pbr.optJSONObject("baseColorTexture")
            val image = texInfo?.let { textures.optJSONObject(it.optInt("index"))?.optInt("source", -1) } ?: -1
            val transform = texInfo?.optJSONObject("extensions")?.optJSONObject("KHR_texture_transform")?.let { t ->
                val off = t.optJSONArray("offset")
                val sc = t.optJSONArray("scale")
                floatArrayOf(
                    off?.optDouble(0, 0.0)?.toFloat() ?: 0f, off?.optDouble(1, 0.0)?.toFloat() ?: 0f,
                    sc?.optDouble(0, 1.0)?.toFloat() ?: 1f, sc?.optDouble(1, 1.0)?.toFloat() ?: 1f,
                    t.optDouble("rotation", 0.0).toFloat()
                )
            }
            GltfMaterial(m.optString("name", "mat$i"), color, image, m.optString("alphaMode", "OPAQUE"), transform)
        }
        val scenes = json.optJSONArray("scenes")
        val scene = scenes?.optJSONObject(json.optInt("scene", 0))
        sceneRoots = scene?.optJSONArray("nodes")?.let { a -> IntArray(a.length()) { a.getInt(it) } } ?: IntArray(0)
    }

    val imageCount get() = json.optJSONArray("images")?.length() ?: 0

    fun findNode(name: String) = nodes.indexOfFirst { it.name == name }

    fun parentOf(node: Int) = parents[node]

    fun worldMatrix(node: Int): Mat4 {
        var m = nodes[node].local
        var p = parents[node]
        while (p >= 0) {
            m = nodes[p].local * m
            p = parents[p]
        }
        return m
    }

    /** Обходит поддерево и отдаёт примитивы с вершинами, переведёнными матрицей [base] * мир узла. */
    fun collect(root: Int, base: Mat4, skip: (GltfNode) -> Boolean = { false }, out: (MeshData) -> Unit) {
        val parent = parents[root]
        val start = if (parent >= 0) base * worldMatrix(parent) else base
        fun walk(i: Int, m: Mat4) {
            val node = nodes[i]
            if (skip(node)) return
            val world = m * node.local
            if (node.mesh >= 0) meshPrimitives(node.mesh, world, out)
            node.children.forEach { walk(it, world) }
        }
        walk(root, start)
    }

    fun collectScene(skip: (GltfNode) -> Boolean = { false }, out: (MeshData) -> Unit) {
        sceneRoots.forEach { collect(it, Mat4.identity(), skip, out) }
    }

    private fun meshPrimitives(mesh: Int, matrix: Mat4, out: (MeshData) -> Unit) {
        val prims = json.getJSONArray("meshes").getJSONObject(mesh).getJSONArray("primitives")
        for (p in 0 until prims.length()) {
            val prim = prims.getJSONObject(p)
            if (prim.optInt("mode", 4) != 4) continue // только треугольники
            val attrs = prim.getJSONObject("attributes")
            if (!attrs.has("POSITION")) continue
            val local = readFloats(attrs.getInt("POSITION"))
            val count = local.size / 3
            val normalsLocal = if (attrs.has("NORMAL")) readFloats(attrs.getInt("NORMAL")) else null
            var uvs = if (attrs.has("TEXCOORD_0")) readFloats(attrs.getInt("TEXCOORD_0")) else FloatArray(count * 2)
            val indices = if (prim.has("indices")) readIndices(prim.getInt("indices")) else IntArray(count) { it }
            val material = prim.optInt("material", -1)
            materials.getOrNull(material)?.uvTransform?.let { uvs = applyUvTransform(uvs, it) }

            val positions = FloatArray(count * 3)
            val normals = FloatArray(count * 3)
            val flip = determinant3(matrix) < 0
            for (v in 0 until count) {
                val w = matrix.transformPoint(Vec3(local[v * 3], local[v * 3 + 1], local[v * 3 + 2]))
                positions[v * 3] = w.x; positions[v * 3 + 1] = w.y; positions[v * 3 + 2] = w.z
                val n = if (normalsLocal != null)
                    matrix.transformDir(Vec3(normalsLocal[v * 3], normalsLocal[v * 3 + 1], normalsLocal[v * 3 + 2])).normalized()
                else Vec3.UP
                normals[v * 3] = n.x; normals[v * 3 + 1] = n.y; normals[v * 3 + 2] = n.z
            }
            if (flip) for (t in 0 until indices.size / 3) {
                val a = indices[t * 3 + 1]; indices[t * 3 + 1] = indices[t * 3 + 2]; indices[t * 3 + 2] = a
            }
            if (normalsLocal == null) computeFlatNormals(positions, indices, normals)
            out(MeshData(positions, normals, uvs, indices, material))
        }
    }

    /** Декодирует картинку, уменьшая её так, чтобы большая сторона была не больше [maxSize]. */
    fun decodeImage(index: Int, maxSize: Int): Bitmap? {
        val img = json.optJSONArray("images")?.optJSONObject(index) ?: return null
        if (!img.has("bufferView")) return null
        val view = json.getJSONArray("bufferViews").getJSONObject(img.getInt("bufferView"))
        val offset = view.optInt("byteOffset", 0)
        val length = view.getInt("byteLength")
        val data = ByteArray(length)
        bin.duplicate().apply { position(offset) }.get(data)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, length, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSize) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
        }
        return BitmapFactory.decodeByteArray(data, 0, length, options)
    }

    private fun readFloats(accessorIndex: Int): FloatArray {
        val acc = json.getJSONArray("accessors").getJSONObject(accessorIndex)
        val comps = componentsOf(acc.getString("type"))
        val count = acc.getInt("count")
        val out = FloatArray(count * comps)
        if (!acc.has("bufferView")) return out
        val view = json.getJSONArray("bufferViews").getJSONObject(acc.getInt("bufferView"))
        val type = acc.getInt("componentType")
        val size = componentSize(type)
        val stride = view.optInt("byteStride", 0).takeIf { it > 0 } ?: (size * comps)
        val base = view.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
        val normalized = acc.optBoolean("normalized", false)
        for (i in 0 until count) for (c in 0 until comps) {
            val at = base + i * stride + c * size
            out[i * comps + c] = when (type) {
                5126 -> bin.getFloat(at)
                5121 -> (bin.get(at).toInt() and 0xFF).let { if (normalized) it / 255f else it.toFloat() }
                5120 -> bin.get(at).toInt().let { if (normalized) max(it / 127f, -1f) else it.toFloat() }
                5123 -> (bin.getShort(at).toInt() and 0xFFFF).let { if (normalized) it / 65535f else it.toFloat() }
                5122 -> bin.getShort(at).toInt().let { if (normalized) max(it / 32767f, -1f) else it.toFloat() }
                5125 -> bin.getInt(at).toFloat()
                else -> 0f
            }
        }
        return out
    }

    private fun readIndices(accessorIndex: Int): IntArray {
        val acc = json.getJSONArray("accessors").getJSONObject(accessorIndex)
        val count = acc.getInt("count")
        val view = json.getJSONArray("bufferViews").getJSONObject(acc.getInt("bufferView"))
        val type = acc.getInt("componentType")
        val size = componentSize(type)
        val stride = view.optInt("byteStride", 0).takeIf { it > 0 } ?: size
        val base = view.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
        return IntArray(count) { i ->
            val at = base + i * stride
            when (type) {
                5121 -> bin.get(at).toInt() and 0xFF
                5123 -> bin.getShort(at).toInt() and 0xFFFF
                else -> bin.getInt(at)
            }
        }
    }

    companion object {
        private fun componentsOf(type: String) = when (type) {
            "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16; else -> 1
        }

        private fun componentSize(type: Int) = when (type) {
            5120, 5121 -> 1; 5122, 5123 -> 2; else -> 4
        }

        private fun localMatrix(n: JSONObject): Mat4 {
            n.optJSONArray("matrix")?.let { a -> return Mat4(FloatArray(16) { a.getDouble(it).toFloat() }) }
            val t = n.optJSONArray("translation")?.let { Vec3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) } ?: Vec3.ZERO
            val r = n.optJSONArray("rotation")?.let {
                Quat(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat(), it.getDouble(3).toFloat())
            } ?: Quat.IDENTITY
            val s = n.optJSONArray("scale")?.let { Vec3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) } ?: Vec3(1f, 1f, 1f)
            return Mat4.trs(t, r, s)
        }

        private fun determinant3(m: Mat4): Float {
            val a = m.m
            return a[0] * (a[5] * a[10] - a[9] * a[6]) - a[4] * (a[1] * a[10] - a[9] * a[2]) + a[8] * (a[1] * a[6] - a[5] * a[2])
        }

        private fun applyUvTransform(uvs: FloatArray, t: FloatArray): FloatArray {
            val (ox, oy, sx, sy) = t
            val r = t[4]
            val c = cos(r); val s = sin(r)
            val out = FloatArray(uvs.size)
            for (i in 0 until uvs.size / 2) {
                val u = uvs[i * 2] * sx; val v = uvs[i * 2 + 1] * sy
                out[i * 2] = c * u + s * v + ox
                out[i * 2 + 1] = -s * u + c * v + oy
            }
            return out
        }

        private fun computeFlatNormals(p: FloatArray, idx: IntArray, n: FloatArray) {
            for (t in 0 until idx.size / 3) {
                val a = idx[t * 3]; val b = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
                val va = Vec3(p[a * 3], p[a * 3 + 1], p[a * 3 + 2])
                val nn = ((Vec3(p[b * 3], p[b * 3 + 1], p[b * 3 + 2]) - va) cross (Vec3(p[c * 3], p[c * 3 + 1], p[c * 3 + 2]) - va)).normalized()
                for (v in intArrayOf(a, b, c)) { n[v * 3] = nn.x; n[v * 3 + 1] = nn.y; n[v * 3 + 2] = nn.z }
            }
        }
    }
}

/** Габариты набора вершин. */
class Bounds {
    var min = Vec3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
    var max = Vec3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
    val isEmpty get() = min.x > max.x
    val center get() = (min + max) * 0.5f
    val size get() = max - min

    fun add(positions: FloatArray) {
        var x0 = min.x; var y0 = min.y; var z0 = min.z
        var x1 = max.x; var y1 = max.y; var z1 = max.z
        for (i in 0 until positions.size / 3) {
            val x = positions[i * 3]; val y = positions[i * 3 + 1]; val z = positions[i * 3 + 2]
            x0 = min(x0, x); y0 = min(y0, y); z0 = min(z0, z)
            x1 = max(x1, x); y1 = max(y1, y); z1 = max(z1, z)
        }
        min = Vec3(x0, y0, z0); max = Vec3(x1, y1, z1)
    }
}
