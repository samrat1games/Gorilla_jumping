package com.gorillajumping.game

import android.content.res.AssetManager
import com.gorillajumping.gltf.Bounds
import com.gorillajumping.gltf.Glb
import com.gorillajumping.math.Mat4
import com.gorillajumping.math.Vec3
import com.gorillajumping.physics.CollisionWorld
import com.gorillajumping.render.Batcher
import com.gorillajumping.render.CpuPart
import com.gorillajumping.render.FloatArrayBuilder
import com.gorillajumping.render.MaterialSet
import com.gorillajumping.render.Model
import com.gorillajumping.render.Renderer

/** Скины горилл из gorilla_tag_gorillas.glb: имя узла-группы и подпись. */
val SKINS = listOf(
    "gorilla_001__2_" to "Классика",
    "gorilla" to "Пузыри",
    "gorilla_001__3_" to "Синяя краска",
    "gorilla__1_" to "Оранжевая краска",
    "gorilla_001__1_" to "Лёд",
    "gorilla_001" to "Лава"
)
const val LAVA_SKIN = 5

/** Всё, что готовится в фоне (CPU). */
class CpuAssets(
    val mapParts: List<CpuPart>,
    val skyParts: List<CpuPart>,
    val mapMaterials: MaterialSet,
    val collision: CollisionWorld,
    val skins: List<List<CpuPart>>,
    /** Кисти каждого скина (левая, правая), вырезанные из тела; центр кисти в начале координат. */
    val hands: List<List<List<CpuPart>>>,
    /** Руки целиком (плечо → кулак) каждого скина: плечо в начале координат и вектор плечо→кулак. */
    val arms: List<List<Pair<List<CpuPart>, Vec3>>>,
    val gorillaMaterials: MaterialSet,
    val anchors: GorillaAnchors,
    val cosmetics: List<Pair<CosmeticDef, List<CpuPart>>>,
    val cosmeticMaterials: MaterialSet,
    val boombox: List<CpuPart>,
    val boomboxMaterials: MaterialSet,
    val boomboxSize: Vec3
) {
    companion object {
        fun load(am: AssetManager, progress: (String) -> Unit): CpuAssets {
            fun read(name: String) = am.open("models/$name").use { it.readBytes() }

            progress("Карта…")
            val map = Glb(read("map.glb"))
            // Небо — отдельная сфера: рисуем без света и не кладём в коллизию.
            val skyNode = map.nodes.indexOfFirst { it.name.startsWith("Sphere_") }
            val batcher = Batcher(cell = 24f)
            val sky = Batcher()
            val tris = FloatArrayBuilder()
            val used = HashSet<Int>()
            map.collectScene(skip = { skyNode >= 0 && it === map.nodes[skyNode] }) { mesh ->
                used += mesh.material; batcher.add(mesh); addTris(mesh.positions, mesh.indices, tris)
            }
            if (skyNode >= 0) map.collect(skyNode, Mat4.identity()) { used += it.material; sky.add(it) }
            val mapParts = batcher.build()
            val collisionTris = tris.toArray()
            progress("Физика карты…")
            val collision = CollisionWorld(collisionTris)
            progress("Текстуры карты…")
            val mapMaterials = MaterialSet(map, 256).also { it.decodeFor(used) }

            progress("Гориллы…")
            val gorillas = Glb(read("gorillas.glb"))
            val skins = SKINS.map { (node, _) ->
                val i = gorillas.findNode(node)
                val bounds = Bounds()
                gorillas.collect(i, Mat4.identity()) { bounds.add(it.positions) }
                val b = Batcher()
                gorillas.collect(i, Mat4.translation(Vec3(-bounds.center.x, 0f, -bounds.center.z))) { b.add(it) }
                b.build()
            }
            val hands = skins.map { parts -> listOf(cutHand(parts, 1f), cutHand(parts, -1f)) }
            val arms = skins.map { parts -> listOf(cutArm(parts, 1f), cutArm(parts, -1f)) }
            val gorillaMaterials = MaterialSet(gorillas, 512).also { it.decodeFor(gorillas.materials.indices.toList()) }
            val anchors = anchorsOf(gorillas)

            progress("Косметика…")
            val cosGlb = Glb(read("cosmetics.glb"))
            val cosmetics = CosmeticCatalog.all.mapNotNull { def -> buildCosmetic(cosGlb, def, anchors)?.let { def to it } }
            val cosmeticMaterials = MaterialSet(cosGlb, 512).also { m -> m.decodeFor(cosmetics.flatMap { c -> c.second.map { it.material } }.toSet()) }

            progress("Бумбокс…")
            val bbGlb = Glb(read("boombox.glb"))
            val bb = Bounds()
            bbGlb.collectScene { bb.add(it.positions) }
            val s = BOOMBOX_WIDTH / bb.size.x
            val bbBatch = Batcher()
            bbGlb.collectScene { bbBatch.add(it) }
            val bbParts = bbBatch.build().map { part ->
                val v = part.mesh.vertices.copyOf()
                for (k in 0 until v.size / 8) {
                    v[k * 8] = (v[k * 8] - bb.center.x) * s
                    v[k * 8 + 1] = (v[k * 8 + 1] - bb.center.y) * s
                    v[k * 8 + 2] = (v[k * 8 + 2] - bb.center.z) * s
                }
                CpuPart(com.gorillajumping.render.CpuMesh(v, part.mesh.indices), part.material)
            }
            val bbMaterials = MaterialSet(bbGlb, 512).also { it.decodeFor(bbGlb.materials.indices.toList()) }

            return CpuAssets(
                mapParts, sky.build(), mapMaterials, collision, skins, hands, arms, gorillaMaterials, anchors,
                cosmetics, cosmeticMaterials, bbParts, bbMaterials, bb.size * s
            )
        }

        const val BOOMBOX_WIDTH = 0.45f

        /**
         * Рука гориллы целиком из тела: всё, что сбоку дальше |x| > 0.18 (плечо у y ≈ 0.3, кулак внизу
         * у y ≈ −0.53). Сдвигаем так, чтобы плечо было в начале координат; второе — вектор плечо→кулак.
         */
        private fun cutArm(parts: List<CpuPart>, side: Float): Pair<List<CpuPart>, Vec3> {
            fun inArm(v: FloatArray, i: Int) = v[i * 8] * side > 0.18f
            var sh = Vec3.ZERO; var shN = 0
            var fist = Vec3.ZERO; var fistN = 0
            for (part in parts) {
                val v = part.mesh.vertices
                for (i in 0 until v.size / 8) if (inArm(v, i)) {
                    val p = Vec3(v[i * 8], v[i * 8 + 1], v[i * 8 + 2])
                    if (p.y > 0.2f) { sh += p; shN++ }
                    if (p.y < -0.5f) { fist += p; fistN++ }
                }
            }
            val shoulder = if (shN > 0) sh / shN.toFloat() else Vec3(side * 0.25f, 0.3f, 0f)
            val hand = if (fistN > 0) fist / fistN.toFloat() else Vec3(side * 0.28f, -0.53f, 0f)
            val out = parts.mapNotNull { part ->
                val v = part.mesh.vertices; val idx = part.mesh.indices
                val b = FloatArrayBuilder(); val outIdx = ArrayList<Int>(); val remap = HashMap<Int, Int>()
                for (t in 0 until idx.size / 3) {
                    val a = idx[t * 3]; val c = idx[t * 3 + 1]; val d = idx[t * 3 + 2]
                    if (!inArm(v, a) || !inArm(v, c) || !inArm(v, d)) continue
                    for (i in intArrayOf(a, c, d)) outIdx += remap.getOrPut(i) {
                        val o = floatArrayOf(shoulder.x, shoulder.y, shoulder.z)
                        for (k in 0 until 8) b.add(if (k < 3) v[i * 8 + k] - o[k] else v[i * 8 + k])
                        remap.size
                    }
                }
                if (outIdx.isEmpty()) null else CpuPart(com.gorillajumping.render.CpuMesh(b.toArray(), outIdx.toIntArray()), part.material)
            }
            return out to (hand - shoulder)
        }

        /**
         * Кисть гориллы из тела: треугольники внизу сбоку (длинные руки висят до земли, кулаки у
         * |x| ≈ 0.28, y < −0.43). [side] = 1 — левая кисть (модель смотрит в +Z), −1 — правая.
         */
        private fun cutHand(parts: List<CpuPart>, side: Float): List<CpuPart> {
            fun inHand(v: FloatArray, i: Int) = v[i * 8] * side > 0.2f && v[i * 8 + 1] < -0.43f
            val lo = floatArrayOf(1e9f, 1e9f, 1e9f); val hi = floatArrayOf(-1e9f, -1e9f, -1e9f)
            for (part in parts) {
                val v = part.mesh.vertices
                for (i in 0 until v.size / 8) if (inHand(v, i)) for (k in 0..2) { lo[k] = minOf(lo[k], v[i * 8 + k]); hi[k] = maxOf(hi[k], v[i * 8 + k]) }
            }
            val c = FloatArray(3) { (lo[it] + hi[it]) / 2 }
            return parts.mapNotNull { part ->
                val v = part.mesh.vertices; val idx = part.mesh.indices
                val out = FloatArrayBuilder(); val outIdx = ArrayList<Int>()
                val remap = HashMap<Int, Int>()
                for (t in 0 until idx.size / 3) {
                    val a = idx[t * 3]; val b = idx[t * 3 + 1]; val d = idx[t * 3 + 2]
                    if (!inHand(v, a) || !inHand(v, b) || !inHand(v, d)) continue
                    for (i in intArrayOf(a, b, d)) outIdx += remap.getOrPut(i) {
                        for (k in 0 until 8) out.add(if (k < 3) v[i * 8 + k] - c[k] else v[i * 8 + k])
                        remap.size
                    }
                }
                if (outIdx.isEmpty()) null else CpuPart(com.gorillajumping.render.CpuMesh(out.toArray(), outIdx.toIntArray()), part.material)
            }
        }

        private fun addTris(p: FloatArray, idx: IntArray, out: FloatArrayBuilder) {
            for (t in 0 until idx.size / 3) for (k in 0 until 3) {
                val v = idx[t * 3 + k]
                out.add(p[v * 3]); out.add(p[v * 3 + 1]); out.add(p[v * 3 + 2])
            }
        }

        private fun anchorsOf(glb: Glb): GorillaAnchors {
            fun boundsOf(name: String): Bounds {
                val b = Bounds()
                val group = glb.findNode("gorilla")
                val gb = Bounds()
                glb.collect(group, Mat4.identity()) { gb.add(it.positions) }
                val i = glb.findNode(name)
                if (i >= 0) glb.collect(i, Mat4.translation(Vec3(-gb.center.x, 0f, -gb.center.z))) { b.add(it.positions) }
                return b
            }
            val face = boundsOf("gorilla_GorillaFace_0")
            val chest = boundsOf("gorilla_GorillaChest_0")
            val body = boundsOf("gorilla")
            if (face.isEmpty || chest.isEmpty) return GorillaAnchors(0.635f, 0.19f, 0.425f, 0.15f, 0.26f)
            return GorillaAnchors(body.max.y, face.max.z, face.center.y, chest.max.z, chest.center.y)
        }
    }
}

/** То же на GPU. */
class GpuAssets(cpu: CpuAssets, r: Renderer) {
    val map: Model = cpu.mapMaterials.upload(cpu.mapParts, r)
    val sky: Model = Model(cpu.mapMaterials.upload(cpu.skyParts, r).parts.map { (m, mat) ->
        m to com.gorillajumping.render.Material(mat.texture, mat.color, 0f, unlit = true)
    })
    val skins: List<Model> = cpu.skins.map { cpu.gorillaMaterials.upload(it, r) }
    val hands: List<List<Model>> = cpu.hands.map { pair -> pair.map { cpu.gorillaMaterials.upload(it, r) } }
    val arms: List<List<Pair<Model, Vec3>>> = cpu.arms.map { pair -> pair.map { (parts, axis) -> cpu.gorillaMaterials.upload(parts, r) to axis } }
    /** Средняя точка текстуры меха на кисти каждого скина — этим мехом красим руки. */
    val furUv: List<FloatArray> = cpu.hands.map { pair ->
        var u = 0.0; var v = 0.0; var n = 0
        for (part in pair[0]) { val vs = part.mesh.vertices; for (k in 0 until vs.size / 8) { u += vs[k * 8 + 6]; v += vs[k * 8 + 7]; n++ } }
        if (n == 0) floatArrayOf(0.5f, 0.5f) else floatArrayOf((u / n).toFloat(), (v / n).toFloat())
    }
    val cosmetics: List<Pair<CosmeticDef, Model>> = cpu.cosmetics.map { (d, p) -> d to cpu.cosmeticMaterials.upload(p, r) }
    val boombox: Model = cpu.boomboxMaterials.upload(cpu.boombox, r)
}
