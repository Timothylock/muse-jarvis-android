package dev.timlock.jarvis.render.orb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

internal object GlUtil {
    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            throw RuntimeException("shader compile failed: $log")
        }
        return s
    }

    fun program(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("program link failed: " + GLES30.glGetProgramInfoLog(p))
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        return p
    }

    /** Uploads [data] to a new VBO and returns a VAO with attributes described by (size) list, tightly interleaved. */
    fun vao(data: FloatArray, vararg sizes: Int): Int {
        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        GLES30.glGenBuffers(1, ids, 1)
        GLES30.glBindVertexArray(ids[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[1])
        val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        bb.asFloatBuffer().put(data)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, bb, GLES30.GL_STATIC_DRAW)
        val stride = sizes.sum() * 4
        var off = 0
        for ((i, sz) in sizes.withIndex()) {
            GLES30.glEnableVertexAttribArray(i)
            GLES30.glVertexAttribPointer(i, sz, GLES30.GL_FLOAT, false, stride, off)
            off += sz * 4
        }
        GLES30.glBindVertexArray(0)
        return ids[0]
    }

    fun emptyVao(): Int {
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        return ids[0]
    }

    // ---- meshes ----
    const val RING_SEGMENTS = 360

    /** (theta, v) strip shared by every ring. */
    fun ringStrip(): FloatArray {
        val out = FloatArray((RING_SEGMENTS + 1) * 4)
        for (i in 0..RING_SEGMENTS) {
            val th = (2.0 * PI * i / RING_SEGMENTS).toFloat()
            out[i * 4] = th; out[i * 4 + 1] = -1f; out[i * 4 + 2] = th; out[i * 4 + 3] = 1f
        }
        return out
    }

    /** Tick quads as a triangle list, vec4 (theta, along, side, index). */
    fun ticks(n: Int): FloatArray {
        val out = FloatArray(n * 6 * 4)
        val corners = floatArrayOf(0f, -1f, 0f, 1f, 1f, -1f, 1f, -1f, 0f, 1f, 1f, 1f)
        var o = 0
        for (i in 0 until n) {
            val th = (2.0 * PI * i / n).toFloat()
            for (c in 0 until 6) {
                out[o++] = th; out[o++] = corners[c * 2]; out[o++] = corners[c * 2 + 1]; out[o++] = i.toFloat()
            }
        }
        return out
    }

    /** 12 degree-label quads: (theta, x, y, cell). */
    fun labels(): FloatArray {
        val out = FloatArray(12 * 6 * 4)
        val cx = floatArrayOf(-1f, -1f, 1f, 1f, -1f, 1f)
        val cy = floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f)
        var o = 0
        for (i in 0 until 12) {
            val th = (2.0 * PI * i / 12).toFloat()
            for (c in 0 until 6) {
                out[o++] = th; out[o++] = cx[c]; out[o++] = cy[c]; out[o++] = i.toFloat()
            }
        }
        return out
    }

    /** 4 corner brackets (45/135/225/315 deg at 1.25 R, 40 px arms), vec4 + float per vertex. */
    fun brackets(): FloatArray {
        val out = FloatArray(8 * 6 * 5)
        var o = 0
        val arm = 40f / 260f
        val t = floatArrayOf(0f, 0f, 1f, 1f, 0f, 1f)
        val sd = floatArrayOf(-1f, 1f, -1f, -1f, 1f, 1f)
        for (k in 0 until 4) {
            val th = Math.toRadians(45.0 + 90.0 * k)
            val sx = 1.25f * sin(th).toFloat()
            val sy = -1.25f * cos(th).toFloat()
            val toward = floatArrayOf(if (sx > 0) -1f else 1f, 0f, 0f, if (sy > 0) -1f else 1f)
            for (a in 0 until 2) {
                val dx = toward[a * 2]; val dy = toward[a * 2 + 1]
                val nx = -dy; val ny = dx
                for (c in 0 until 6) {
                    out[o++] = sx + dx * arm * t[c]; out[o++] = sy + dy * arm * t[c]
                    out[o++] = nx; out[o++] = ny; out[o++] = sd[c]
                }
            }
        }
        return out
    }

    /** Static particle field: (seed, orbitR, phase, speed) + (size, depth, streamer, 0). */
    fun particles(n: Int): FloatArray {
        val rnd = java.util.Random(7)
        val out = FloatArray(n * 8)
        for (i in 0 until n) {
            val depth = rnd.nextFloat()
            val streamer = if (rnd.nextFloat() < 0.15f) 1f else 0f
            var speed = 3f + 17f * (0.3f + 0.7f * depth) * rnd.nextFloat()
            if (streamer > 0f) speed = (speed * 1.8f).coerceAtMost(40f)
            if (rnd.nextFloat() < 0.25f) speed = -speed
            val o = i * 8
            out[o] = rnd.nextFloat()
            out[o + 1] = 0.35f + 1.1f * sqrt(rnd.nextFloat())
            out[o + 2] = (rnd.nextFloat() * 2.0 * PI).toFloat()
            out[o + 3] = speed
            out[o + 4] = 2f + 3f * rnd.nextFloat()
            out[o + 5] = depth
            out[o + 6] = streamer
        }
        return out
    }

    // ---- textures ----
    /** 256x256 R8 radial sprite, gaussian falloff, mipmapped. */
    fun coreSprite(): Int {
        val n = 256
        val buf = ByteBuffer.allocateDirect(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val dx = (x + 0.5f) / n * 2f - 1f
            val dy = (y + 0.5f) / n * 2f - 1f
            val r = sqrt(dx * dx + dy * dy)
            var v = exp(-r * r * 6.5f) * 0.88f + exp(-r * r * 36f) * 0.45f
            val edge = ((1f - r) / 0.2f).coerceIn(0f, 1f)
            v *= edge * edge * (3f - 2f * edge)
            buf.put((v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
        }
        buf.position(0)
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, n, n, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        sampler()
        return id[0]
    }

    /** 1024x256 atlas, 4x3 cells of "000".."330", rasterized once with Canvas. */
    fun labelAtlas(): Int {
        val bmp = Bitmap.createBitmap(1024, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 62f
            textAlign = Paint.Align.CENTER
        }
        val ch = 256f / 3f
        val fm = p.fontMetrics
        for (i in 0 until 12) {
            val col = i % 4; val row = i / 4
            val cxp = col * 256f + 128f
            val cyp = row * ch + ch / 2f - (fm.ascent + fm.descent) / 2f
            c.drawText(String.format("%03d", i * 30), cxp, cyp, p)
        }
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        sampler()
        return id[0]
    }

    private fun sampler() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }
}
