package com.gorillajumping.render

import android.graphics.Bitmap
import android.opengl.GLES30.*
import android.util.Log
import com.gorillajumping.math.Mat4
import com.gorillajumping.math.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Вершины в одном массиве: позиция(3) нормаль(3) uv(2). */
class CpuMesh(val vertices: FloatArray, val indices: IntArray) {
    val center: Vec3
    val radius: Float

    init {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (i in 0 until vertices.size / 8) {
            val x = vertices[i * 8]; val y = vertices[i * 8 + 1]; val z = vertices[i * 8 + 2]
            if (x < x0) x0 = x; if (y < y0) y0 = y; if (z < z0) z0 = z
            if (x > x1) x1 = x; if (y > y1) y1 = y; if (z > z1) z1 = z
        }
        center = Vec3((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2)
        radius = Vec3(x1 - x0, y1 - y0, z1 - z0).length() / 2
    }

    companion object {
        /** Собирает сетку из [MeshData]-подобных массивов. */
        fun of(positions: FloatArray, normals: FloatArray, uvs: FloatArray, indices: IntArray): CpuMesh {
            val n = positions.size / 3
            val v = FloatArray(n * 8)
            for (i in 0 until n) {
                v[i * 8] = positions[i * 3]; v[i * 8 + 1] = positions[i * 3 + 1]; v[i * 8 + 2] = positions[i * 3 + 2]
                v[i * 8 + 3] = normals[i * 3]; v[i * 8 + 4] = normals[i * 3 + 1]; v[i * 8 + 5] = normals[i * 3 + 2]
                v[i * 8 + 6] = uvs[i * 2]; v[i * 8 + 7] = uvs[i * 2 + 1]
            }
            return CpuMesh(v, indices)
        }
    }
}

class GpuMesh(cpu: CpuMesh) {
    val vao: Int
    val count = cpu.indices.size
    val center = cpu.center
    val radius = cpu.radius
    private val buffers = IntArray(2)

    init {
        val ids = IntArray(1)
        glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        glBindVertexArray(vao)
        glGenBuffers(2, buffers, 0)
        glBindBuffer(GL_ARRAY_BUFFER, buffers[0])
        val vb = floatBuffer(cpu.vertices)
        glBufferData(GL_ARRAY_BUFFER, cpu.vertices.size * 4, vb, GL_STATIC_DRAW)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        val ib = intBuffer(cpu.indices)
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, cpu.indices.size * 4, ib, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 32, 0)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 32, 12)
        glEnableVertexAttribArray(2)
        glVertexAttribPointer(2, 2, GL_FLOAT, false, 32, 24)
        glBindVertexArray(0)
    }

    fun draw() {
        glBindVertexArray(vao)
        glDrawElements(GL_TRIANGLES, count, GL_UNSIGNED_INT, 0)
    }

    companion object {
        fun floatBuffer(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(a).also { it.position(0) }

        fun intBuffer(a: IntArray): IntBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(a).also { it.position(0) }
    }
}

/** Картинка, уже развёрнутая в RGBA, — её можно готовить в фоне, а в GL грузить в потоке рендера. */
class ImagePixels(val width: Int, val height: Int, val rgba: ByteBuffer) {
    companion object {
        fun of(bitmap: Bitmap): ImagePixels {
            val bmp = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val buf = ByteBuffer.allocateDirect(bmp.width * bmp.height * 4).order(ByteOrder.nativeOrder())
            bmp.copyPixelsToBuffer(buf)
            buf.position(0)
            val result = ImagePixels(bmp.width, bmp.height, buf)
            if (bmp !== bitmap) bmp.recycle()
            bitmap.recycle()
            return result
        }
    }
}

/** Переключатели для замеров производительности (файл files/nomap через adb run-as). */
object Perf {
    @Volatile var skipMap = false
}

object Textures {
    /** Если свопчейн в sRGB, текстуры надо помечать как sRGB, чтобы GPU их правильно смешал. */
    var srgb = false

    fun upload(image: ImagePixels, repeat: Boolean = true, mipmaps: Boolean = true): Int {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexImage2D(GL_TEXTURE_2D, 0, if (srgb) GL_SRGB8_ALPHA8 else GL_RGBA8, image.width, image.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, image.rgba)
        val wrap = if (repeat) GL_REPEAT else GL_CLAMP_TO_EDGE
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, if (mipmaps) GL_LINEAR_MIPMAP_LINEAR else GL_LINEAR)
        if (mipmaps) glGenerateMipmap(GL_TEXTURE_2D)
        return ids[0]
    }

    fun white(): Int {
        val buf = ByteBuffer.allocateDirect(4).put(byteArrayOf(-1, -1, -1, -1)).also { it.position(0) }
        return upload(ImagePixels(1, 1, buf), mipmaps = false)
    }
}

class Material(
    var texture: Int,
    val color: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
    val alphaCut: Float = 0f,
    val unlit: Boolean = false
)

class Frustum {
    private val planes = FloatArray(24)

    fun set(vp: Mat4) {
        val m = vp.m
        fun row(r: Int, c: Int) = m[c * 4 + r]
        for (i in 0 until 6) {
            val r = i / 2
            val sign = if (i % 2 == 0) 1f else -1f
            var a = row(3, 0) + sign * row(r, 0)
            var b = row(3, 1) + sign * row(r, 1)
            var c = row(3, 2) + sign * row(r, 2)
            var d = row(3, 3) + sign * row(r, 3)
            val len = sqrt(a * a + b * b + c * c)
            a /= len; b /= len; c /= len; d /= len
            planes[i * 4] = a; planes[i * 4 + 1] = b; planes[i * 4 + 2] = c; planes[i * 4 + 3] = d
        }
    }

    fun visible(center: Vec3, radius: Float): Boolean {
        for (i in 0 until 6) {
            val d = planes[i * 4] * center.x + planes[i * 4 + 1] * center.y + planes[i * 4 + 2] * center.z + planes[i * 4 + 3]
            if (d < -radius) return false
        }
        return true
    }
}

/** Один шейдер на всё: текстура × цвет, мягкий свет, туман, альфа-срез для листвы. */
class Renderer {
    private val program: Int
    private val uViewProj: Int
    private val uModel: Int
    private val uColor: Int
    private val uAlphaCut: Int
    private val uUnlit: Int
    private val uLinearOut: Int
    private val uFogColor: Int
    private val uCamPos: Int
    val frustum = Frustum()
    var viewProj = Mat4()
        private set
    var cameraPos = Vec3.ZERO
        private set
    var linearOutput = false
    val fogColor = floatArrayOf(0.55f, 0.75f, 0.95f)
    val whiteTexture: Int

    init {
        program = link(VERTEX, FRAGMENT)
        uViewProj = glGetUniformLocation(program, "uViewProj")
        uModel = glGetUniformLocation(program, "uModel")
        uColor = glGetUniformLocation(program, "uColor")
        uAlphaCut = glGetUniformLocation(program, "uAlphaCut")
        uUnlit = glGetUniformLocation(program, "uUnlit")
        uLinearOut = glGetUniformLocation(program, "uLinearOut")
        uFogColor = glGetUniformLocation(program, "uFogColor")
        uCamPos = glGetUniformLocation(program, "uCamPos")
        whiteTexture = Textures.white()
    }

    fun begin(viewProj: Mat4, cameraPos: Vec3) {
        this.viewProj = viewProj
        this.cameraPos = cameraPos
        frustum.set(viewProj)
        glUseProgram(program)
        glUniformMatrix4fv(uViewProj, 1, false, viewProj.m, 0)
        glUniform1f(uLinearOut, if (linearOutput) 1f else 0f)
        glUniform3fv(uFogColor, 1, fogColor, 0)
        glUniform3f(uCamPos, cameraPos.x, cameraPos.y, cameraPos.z)
        glEnable(GL_DEPTH_TEST)
        glDisable(GL_CULL_FACE) // у моделей со Sketchfab порядок обхода гуляет
        glActiveTexture(GL_TEXTURE0)
    }

    private var boundTexture = -1

    fun draw(mesh: GpuMesh, material: Material, model: Mat4? = null, tint: FloatArray? = null) {
        if (model == null) {
            if (!frustum.visible(mesh.center, mesh.radius)) return
            // Дальше DRAW_DISTANCE карту не рисуем (там уже туман): экономия видеочипа для трекинга.
            val dx = mesh.center.x - cameraPos.x; val dy = mesh.center.y - cameraPos.y; val dz = mesh.center.z - cameraPos.z
            val far = DRAW_DISTANCE + mesh.radius
            if (dx * dx + dy * dy + dz * dz > far * far) return
            glUniformMatrix4fv(uModel, 1, false, IDENTITY, 0)
        } else {
            glUniformMatrix4fv(uModel, 1, false, model.m, 0)
        }
        val c = material.color
        if (tint != null) glUniform4f(uColor, c[0] * tint[0], c[1] * tint[1], c[2] * tint[2], c[3] * tint[3])
        else glUniform4f(uColor, c[0], c[1], c[2], c[3])
        glUniform1f(uAlphaCut, material.alphaCut)
        glUniform1f(uUnlit, if (material.unlit) 1f else 0f)
        if (boundTexture != material.texture) {
            glBindTexture(GL_TEXTURE_2D, material.texture)
            boundTexture = material.texture
        }
        mesh.draw()
    }

    fun endEye() {
        boundTexture = -1
        glBindVertexArray(0)
    }

    companion object {
        /** Дальность прорисовки карты, м (туман полностью закрывает её к 70 м). */
        const val DRAW_DISTANCE = 70f
        private val IDENTITY = Mat4().m

        private const val VERTEX = """#version 300 es
            layout(location=0) in vec3 aPos;
            layout(location=1) in vec3 aNormal;
            layout(location=2) in vec2 aUv;
            uniform mat4 uViewProj;
            uniform mat4 uModel;
            out vec2 vUv;
            out vec3 vNormal;
            out vec3 vWorld;
            void main() {
                vec4 w = uModel * vec4(aPos, 1.0);
                vWorld = w.xyz;
                vNormal = mat3(uModel) * aNormal;
                vUv = aUv;
                gl_Position = uViewProj * w;
            }"""

        private const val FRAGMENT = """#version 300 es
            precision mediump float;
            in vec2 vUv;
            in vec3 vNormal;
            in vec3 vWorld;
            uniform sampler2D uTex;
            uniform vec4 uColor;
            uniform float uAlphaCut;
            uniform float uUnlit;
            uniform float uLinearOut;
            uniform vec3 uFogColor;
            uniform vec3 uCamPos;
            out vec4 fragColor;
            void main() {
                vec4 c = texture(uTex, vUv) * uColor;
                if (c.a < uAlphaCut) discard;
                vec3 n = normalize(vNormal);
                if (!gl_FrontFacing) n = -n;
                float light = mix(0.62 + 0.45 * max(dot(n, normalize(vec3(0.35, 0.85, 0.4))), 0.0) + 0.08 * n.y, 1.0, uUnlit);
                vec3 rgb = c.rgb * light;
                float dist = length(vWorld - uCamPos);
                float fog = clamp((dist - 20.0) / 50.0, 0.0, 1.0) * (1.0 - uUnlit);
                rgb = mix(rgb, uFogColor, fog);
                if (uLinearOut > 0.5) rgb = pow(rgb, vec3(2.2));
                fragColor = vec4(rgb, 1.0);
            }"""

        private fun compile(type: Int, source: String): Int {
            val s = glCreateShader(type)
            glShaderSource(s, source)
            glCompileShader(s)
            val ok = IntArray(1)
            glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) error("Shader compile: " + glGetShaderInfoLog(s))
            return s
        }

        fun link(vs: String, fs: String): Int {
            val p = glCreateProgram()
            glAttachShader(p, compile(GL_VERTEX_SHADER, vs))
            glAttachShader(p, compile(GL_FRAGMENT_SHADER, fs))
            glLinkProgram(p)
            val ok = IntArray(1)
            glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) error("Program link: " + glGetProgramInfoLog(p))
            Log.i("GorillaXR", "shader program ready")
            return p
        }
    }
}

/** Простые сетки: кубы, сферы, плашки. Всё в том же формате вершин. */
object Shapes {
    fun box(sx: Float, sy: Float, sz: Float): CpuMesh {
        val v = ArrayList<Float>()
        val idx = ArrayList<Int>()
        val hx = sx / 2; val hy = sy / 2; val hz = sz / 2
        val faces = arrayOf(
            floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f)
        )
        for (f in faces) {
            val n = Vec3(f[0], f[1], f[2])
            val u = if (kotlin.math.abs(n.y) > 0.5f) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f)
            val w = n cross u
            val base = v.size / 8
            for ((a, b) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                val p = n + u * a + w * b
                v += listOf(p.x * hx, p.y * hy, p.z * hz, n.x, n.y, n.z, (a + 1) / 2, (b + 1) / 2)
            }
            idx += listOf(base, base + 1, base + 2, base, base + 2, base + 3)
        }
        return CpuMesh(v.toFloatArray(), idx.toIntArray())
    }

    fun sphere(radius: Float, rings: Int = 12, segments: Int = 16, squashY: Float = 1f): CpuMesh {
        val v = ArrayList<Float>()
        val idx = ArrayList<Int>()
        for (r in 0..rings) {
            val phi = PI * r / rings
            for (s in 0..segments) {
                val theta = 2 * PI * s / segments
                val n = Vec3((sin(phi) * cos(theta)).toFloat(), cos(phi).toFloat(), (sin(phi) * sin(theta)).toFloat())
                v += listOf(n.x * radius, n.y * radius * squashY, n.z * radius, n.x, n.y, n.z, s.toFloat() / segments, r.toFloat() / rings)
            }
        }
        for (r in 0 until rings) for (s in 0 until segments) {
            val a = r * (segments + 1) + s
            val b = a + segments + 1
            idx += listOf(a, b, a + 1, a + 1, b, b + 1)
        }
        return CpuMesh(v.toFloatArray(), idx.toIntArray())
    }

    /** Плашка в плоскости XY, лицом к +Z. */
    fun quad(w: Float, h: Float): CpuMesh {
        val hw = w / 2; val hh = h / 2
        return CpuMesh(
            floatArrayOf(
                -hw, -hh, 0f, 0f, 0f, 1f, 0f, 1f,
                hw, -hh, 0f, 0f, 0f, 1f, 1f, 1f,
                hw, hh, 0f, 0f, 0f, 1f, 1f, 0f,
                -hw, hh, 0f, 0f, 0f, 1f, 0f, 0f
            ),
            intArrayOf(0, 1, 2, 0, 2, 3)
        )
    }
}
