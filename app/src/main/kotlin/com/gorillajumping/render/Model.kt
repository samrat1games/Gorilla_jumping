package com.gorillajumping.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.gorillajumping.gltf.Glb
import com.gorillajumping.gltf.GltfMaterial
import com.gorillajumping.gltf.MeshData
import com.gorillajumping.math.Mat4

/** Кусок модели на CPU: сетка + индекс материала glTF. Готовится в фоне. */
class CpuPart(val mesh: CpuMesh, val material: Int)

/** Модель на GPU: набор (сетка, материал). */
class Model(val parts: List<Pair<GpuMesh, Material>>) {
    fun draw(r: Renderer, model: Mat4? = null, tint: FloatArray? = null) {
        for ((mesh, material) in parts) r.draw(mesh, material, model, tint)
    }
}

/**
 * Склеивает примитивы с одинаковым материалом в одну сетку, чтобы было мало draw call-ов.
 * [cell] > 0 — ещё и режет по сетке в метрах, чтобы работало отсечение по пирамиде видимости.
 */
class Batcher(private val cell: Float = 0f) {
    private class Acc {
        val vertices = FloatArrayBuilder()
        val indices = IntArrayBuilder()
    }

    private val groups = LinkedHashMap<Long, Pair<Int, Acc>>()

    fun add(m: MeshData) {
        val key: Long = if (cell > 0f) {
            var sx = 0f; var sy = 0f; var sz = 0f
            val n = m.vertexCount
            for (i in 0 until n) { sx += m.positions[i * 3]; sy += m.positions[i * 3 + 1]; sz += m.positions[i * 3 + 2] }
            val cx = kotlin.math.floor(sx / n / cell).toInt().coerceIn(-511, 511) + 512
            val cy = kotlin.math.floor(sy / n / cell).toInt().coerceIn(-511, 511) + 512
            val cz = kotlin.math.floor(sz / n / cell).toInt().coerceIn(-511, 511) + 512
            (m.material.toLong() + 1) shl 32 or (cx.toLong() shl 20) or (cy.toLong() shl 10) or cz.toLong()
        } else (m.material.toLong() + 1)
        val acc = groups.getOrPut(key) { m.material to Acc() }.second
        val base = acc.vertices.size / 8
        for (i in 0 until m.vertexCount) {
            val v = acc.vertices
            v.add(m.positions[i * 3]); v.add(m.positions[i * 3 + 1]); v.add(m.positions[i * 3 + 2])
            v.add(m.normals[i * 3]); v.add(m.normals[i * 3 + 1]); v.add(m.normals[i * 3 + 2])
            v.add(m.uvs[i * 2]); v.add(m.uvs[i * 2 + 1])
        }
        for (idx in m.indices) acc.indices.add(base + idx)
    }

    fun build(): List<CpuPart> = groups.values.map { (mat, acc) -> CpuPart(CpuMesh(acc.vertices.toArray(), acc.indices.toArray()), mat) }
}

class FloatArrayBuilder {
    private var data = FloatArray(1024)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray() = data.copyOf(size)
}

class IntArrayBuilder {
    private var data = IntArray(1024)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray() = data.copyOf(size)
}

/**
 * Материалы и текстуры одного glb. Текстуры грузятся в GL лениво, по одной, в потоке рендера.
 */
class MaterialSet(private val glb: Glb, private val maxTexture: Int) {
    private val gpuMaterials = HashMap<Int, Material>()
    private val decoded = HashMap<Int, ImagePixels>()
    private val textureIds = HashMap<Int, Int>()

    val gltfMaterials: List<GltfMaterial> get() = glb.materials

    /** В фоне: декодировать картинки, которые нужны указанным материалам. */
    fun decodeFor(materials: Collection<Int>) {
        val images = materials.mapNotNull { glb.materials.getOrNull(it)?.image?.takeIf { i -> i >= 0 } }.toSet()
        for (i in images) {
            if (decoded.containsKey(i) || textureIds.containsKey(i)) continue
            val bmp = glb.decodeImage(i, maxTexture) ?: continue
            synchronized(decoded) { decoded[i] = ImagePixels.of(bmp) }
        }
    }

    /** Для какого рендера (контекста OpenGL) загружены текстуры. */
    private var owner: Renderer? = null

    /**
     * В потоке рендера. Ассеты живут весь процесс (AssetCache), а контекст OpenGL — одну сессию шлема:
     * при новом рендере загружаем текстуры заново, иначе старые номера текстур дают чёрную карту.
     * Поэтому картинки после загрузки не выбрасываем.
     */
    fun material(index: Int, renderer: Renderer): Material {
        if (owner !== renderer) { owner = renderer; gpuMaterials.clear(); textureIds.clear() }
        return gpuMaterials.getOrPut(index) { newMaterial(index, renderer) }
    }

    private fun newMaterial(index: Int, renderer: Renderer): Material {
        val gm = glb.materials.getOrNull(index)
        val texture = gm?.image?.takeIf { it >= 0 }?.let { img ->
            textureIds.getOrPut(img) {
                val pixels = synchronized(decoded) { decoded[img] }
                pixels?.let { Textures.upload(it) } ?: renderer.whiteTexture
            }
        } ?: renderer.whiteTexture
        val alphaCut = when (gm?.alphaMode) { "MASK", "BLEND" -> 0.5f; else -> 0f }
        return Material(texture, gm?.baseColor?.copyOf() ?: floatArrayOf(1f, 1f, 1f, 1f), alphaCut)
    }

    fun upload(parts: List<CpuPart>, renderer: Renderer): Model =
        Model(parts.map { GpuMesh(it.mesh) to material(it.material, renderer) })
}

/** Плашка с текстом: рисуем Canvas-ом в картинку и кладём на квад. */
class TextPanel(private val renderer: Renderer, val width: Float, val height: Float, private val pxWidth: Int = 512) {
    private val pxHeight = (pxWidth * height / width).toInt().coerceAtLeast(16)
    private val mesh = GpuMesh(Shapes.quad(width, height))
    private val material = Material(renderer.whiteTexture, unlit = true)
    private var current: String? = null
    private var texture = 0

    fun set(text: String, background: Int = Color.argb(255, 30, 30, 36), textColor: Int = Color.WHITE) {
        if (text == current) return
        current = text
        val bmp = Bitmap.createBitmap(pxWidth, pxHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(background)
        val lines = text.split('\n')
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        var size = pxHeight / (lines.size + 0.6f) * 0.8f
        paint.textSize = size
        val widest = lines.maxOf { paint.measureText(it) }
        if (widest > pxWidth * 0.94f) { size *= pxWidth * 0.94f / widest; paint.textSize = size }
        val lineH = size * 1.2f
        var y = (pxHeight - lineH * lines.size) / 2 + size
        for (line in lines) { canvas.drawText(line, pxWidth / 2f, y, paint); y += lineH }
        if (texture != 0) android.opengl.GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        bmp.setPremultiplied(false)
        texture = Textures.upload(ImagePixels.of(bmp), repeat = false, mipmaps = true)
        material.texture = texture
    }

    fun draw(model: Mat4) = renderer.draw(mesh, material, model)
}
