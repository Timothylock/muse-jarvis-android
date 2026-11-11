package dev.timlock.jarvis.render.orb

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import dev.timlock.jarvis.core.model.AudioVisualBus
import dev.timlock.jarvis.core.model.OrbState
import dev.timlock.jarvis.core.model.OrbStateHolder
import dev.timlock.jarvis.core.model.monoMillis
import java.util.Arrays
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** GLES 3.0 orb renderer (DESIGN §2.4, §3). Everything is preallocated; onDrawFrame does not allocate. */
class OrbRenderer(
    private val stateHolder: OrbStateHolder,
    bus: AudioVisualBus,
    headroom: () -> Float = { 0f },
) : GLSurfaceView.Renderer {
    /** null = auto (OD1 lean c); true = force minimal ambient style; false = force classic. */
    @Volatile var ambientStyleOverride: Boolean? = null
    @Volatile var qualityOverride: Tier? = null
    /** Cadence the view's Choreographer should drive (60/30/20). */
    @Volatile var targetFps: Int = 60
        private set

    internal val governor = QualityGovernor(headroom)
    private val scene = OrbScene(stateHolder, bus)

    private var broken = false
    private var w = 1; private var h = 1

    // programs
    private var pVig = 0; private var pRing = 0; private var pTick = 0; private var pLine = 0
    private var pSprite = 0; private var pGrid = 0; private var pLabel = 0; private var pPart = 0
    private lateinit var uVig: IntArray
    private lateinit var uRing: IntArray; private lateinit var uTick: IntArray; private lateinit var uLine: IntArray
    private lateinit var uSpr: IntArray; private lateinit var uGrid: IntArray; private lateinit var uLab: IntArray
    private lateinit var uPart: IntArray
    private lateinit var sRing: IntArray; private lateinit var sTick: IntArray; private lateinit var sLine: IntArray
    private var vaoRing = 0; private var vaoTick120 = 0; private var vaoTick60 = 0; private var vaoLabels = 0
    private var vaoBrackets = 0; private var vaoParts = 0; private var vaoEmpty = 0
    private var texCore = 0; private var texAtlas = 0
    private val nParts = 600

    // perf
    private var lastFrameNs = 0L
    private var lastNowMs = 0L
    private val sec = FloatArray(512); private var secN = 0
    private var secStart = 0L; private var secFrames = 0
    private val sb = StringBuilder(128)

    private val tmp3 = FloatArray(3)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            init()
            broken = false
        } catch (t: Throwable) {
            Log.e("JARVIS_UI", "orb renderer init failed: " + t.message)
            broken = true
        }
        lastFrameNs = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = max(1, width); h = max(1, height)
        GLES30.glViewport(0, 0, w, h)
    }

    private fun init() {
        pVig = GlUtil.program(Shaders.VIGNETTE_VS, Shaders.VIGNETTE_FS)
        pRing = GlUtil.program(Shaders.RING_VS, Shaders.STROKE_FS)
        pTick = GlUtil.program(Shaders.TICK_VS, Shaders.STROKE_FS)
        pLine = GlUtil.program(Shaders.LINE_VS, Shaders.STROKE_FS)
        pSprite = GlUtil.program(Shaders.SPRITE_VS, Shaders.SPRITE_FS)
        pGrid = GlUtil.program(Shaders.SPRITE_VS, Shaders.GRID_FS)
        pLabel = GlUtil.program(Shaders.LABEL_VS, Shaders.LABEL_FS)
        pPart = GlUtil.program(Shaders.PART_VS, Shaders.PART_FS)
        uVig = locs(pVig, "uDeep", "uVoid", "uCenterPx", "uInv")
        val stroke = arrayOf("uHalfW", "uStroke", "uGlow", "uSigma", "uRot", "uSoft", "uCap", "uRadPx", "uMode", "uArcs", "uPer")
        sRing = locs(pRing, *stroke); sTick = locs(pTick, *stroke); sLine = locs(pLine, *stroke)
        uRing = locs(pRing, "uRes", "uCenter", "uRadius", "uHalfW", "uRot", "uDisp", "uDispOn", "uColor", "uAlpha")
        uTick = locs(
            pTick, "uRes", "uCenter", "uRadius", "uHalfW", "uRot", "uLenS", "uLenL", "uLongEvery", "uDir",
            "uColS", "uColL", "uColSweep", "uAlphaS", "uAlphaL", "uSweepOn", "uHead", "uCount",
        )
        uLine = locs(pLine, "uRes", "uCenter", "uR", "uHalfW", "uColor", "uAlpha")
        uSpr = locs(pSprite, "uRes", "uCenter", "uHalf", "uTex", "uTint", "uHot", "uHotMix", "uInt")
        uGrid = locs(pGrid, "uRes", "uCenter", "uHalf", "uR", "uRot", "uCol", "uA", "uRadar", "uRadarAng")
        uLab = locs(pLabel, "uRes", "uCenter", "uRadius", "uRot", "uHalfSize", "uTex", "uColor", "uAlpha")
        uPart = locs(
            pPart, "uRes", "uCenter", "uR", "uOrbit", "uRadOff", "uTime", "uCount", "uDepthLo", "uDepthHi",
            "uBright", "uPull", "uScale", "uTint", "uTeal", "uBrightCol",
        )
        vaoRing = GlUtil.vao(GlUtil.ringStrip(), 2)
        vaoTick120 = GlUtil.vao(GlUtil.ticks(120), 4)
        vaoTick60 = GlUtil.vao(GlUtil.ticks(60), 4)
        vaoLabels = GlUtil.vao(GlUtil.labels(), 4)
        vaoBrackets = GlUtil.vao(GlUtil.brackets(), 4, 1)
        vaoParts = GlUtil.vao(GlUtil.particles(nParts), 4, 4)
        vaoEmpty = GlUtil.emptyVao()
        texCore = GlUtil.coreSprite()
        texAtlas = GlUtil.labelAtlas()
    }

    private fun locs(p: Int, vararg names: String): IntArray =
        IntArray(names.size) { GLES30.glGetUniformLocation(p, names[it]) }

    // ------------------------------------------------------------------ frame
    override fun onDrawFrame(gl: GL10?) {
        val t0 = System.nanoTime()
        val nowMs = t0 / 1_000_000L
        if (broken) {
            GLES30.glClearColor(0.008f, 0.027f, 0.039f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT); return
        }
        val dt = if (lastFrameNs == 0L) 0.016f else min(0.1f, (t0 - lastFrameNs) / 1e9f)
        val interval = if (lastFrameNs == 0L) 0f else (t0 - lastFrameNs) / 1e6f
        lastFrameNs = t0

        val sn = stateHolder.snapshot
        val night = sn.night
        val idleLong = sn.state == OrbState.IDLE && nowMs - sn.sinceMs >= OrbScene.AMBIENT_AFTER_MS
        val ovr = qualityOverride
        val tier = ovr ?: if (night) Tier.D else governor.tier
        var fps = tier.fps
        if (night) fps = min(fps, 20)
        if (idleLong || scene.ambientMix > 0.99f) fps = min(fps, 30)
        targetFps = fps

        scene.ambientOverride = ambientStyleOverride
        scene.update(nowMs, dt, w, h, tier, night)
        draw(tier)

        // ---- perf ----
        val drawMs = (System.nanoTime() - t0) / 1e6f
        if (interval in 1f..400f) {
            val late = max(0f, interval - 1000f / fps)
            val sample = drawMs + late
            if (ovr == null && !night) governor.record(sample, nowMs, fps)
            if (secN < sec.size) sec[secN++] = sample
        }
        secFrames++
        if (secStart == 0L) secStart = nowMs
        if (nowMs - secStart >= 1000) logPerf(nowMs, tier)
    }

    private fun logPerf(nowMs: Long, tier: Tier) {
        val span = (nowMs - secStart) / 1000f
        val fpsMeasured = secFrames / span
        var p50 = 0f; var p95 = 0f
        if (secN > 0) {
            Arrays.sort(sec, 0, secN)
            p50 = sec[(secN - 1) / 2]; p95 = sec[((secN - 1) * 0.95f).toInt()]
        }
        val parts = scene.partCount.toInt()
        PerfStats.fps = fpsMeasured; PerfStats.p50Ms = p50; PerfStats.p95Ms = p95
        PerfStats.tier = tier; PerfStats.particles = parts; PerfStats.thermal = governor.thermalStatus
        PerfStats.targetFps = targetFps
        sb.setLength(0)
        sb.append("{\"fps\":"); fixed(fpsMeasured); sb.append(",\"p50\":"); fixed(p50)
        sb.append(",\"p95\":"); fixed(p95); sb.append(",\"tier\":\"").append(tier.name)
        sb.append("\",\"particles\":").append(parts).append(",\"thermal\":").append(governor.thermalStatus).append('}')
        Log.i("JARVIS_PERF", sb.toString())
        secN = 0; secFrames = 0; secStart = nowMs
    }

    private fun fixed(v: Float) {
        val iv = (v * 10f + 0.5f).toInt()
        sb.append(iv / 10).append('.').append(iv % 10)
    }

    // ------------------------------------------------------------------ drawing
    private fun draw(tier: Tier) {
        val sc = scene
        val sn = sc.snap
        val g = sc.gain
        val R = sc.radius; val s = sc.scale
        val cx = sc.cx; val cy = sc.cy
        val lay = sc.layer

        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(Palette.Void[0] * g, Palette.Void[1] * g, Palette.Void[2] * g, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // vignette (window background), no blending
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(pVig)
        GLES30.glUniform3f(uVig[0], Palette.Deep[0] * g, Palette.Deep[1] * g, Palette.Deep[2] * g)
        GLES30.glUniform3f(uVig[1], Palette.Void[0] * g, Palette.Void[1] * g, Palette.Void[2] * g)
        GLES30.glUniform2f(uVig[2], w * 0.5f, h * 0.5f)
        GLES30.glUniform1f(uVig[3], 1f / (0.7f * hypot(w.toFloat(), h.toFloat())))
        GLES30.glBindVertexArray(vaoEmpty)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)

        // L0 polar grid + radar
        if (lay[Lyr.GRID] > 0.003f) {
            GLES30.glUseProgram(pGrid)
            GLES30.glUniform2f(uGrid[0], w.toFloat(), h.toFloat())
            GLES30.glUniform2f(uGrid[1], cx, cy)
            GLES30.glUniform1f(uGrid[2], 1.6f * R)
            GLES30.glUniform1f(uGrid[3], R)
            GLES30.glUniform1f(uGrid[4], deg(sc.rotL0))
            GLES30.glUniform3f(uGrid[5], Palette.GridFaint[0] * 2.2f, Palette.GridFaint[1] * 2.2f, Palette.GridFaint[2] * 2.2f)
            GLES30.glUniform1f(uGrid[6], lay[Lyr.GRID] * g * 0.9f)
            GLES30.glUniform1f(uGrid[7], sc.cur[P.RADAR] * lay[Lyr.GRID])
            GLES30.glUniform1f(uGrid[8], deg(sc.radarDeg))
            GLES30.glBindVertexArray(vaoEmpty)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }

        drawParticles(tier, 0f, 0.5f)

        // L1 bezel + ticks (+ countdown / crawl dot, sim ring)
        val a1 = lay[Lyr.BEZEL] * g
        if (a1 > 0.003f) {
            ring(1.0f * R, 1.5f * s, if (tier.halo(1)) 4f * s else 0f, 0.3f, Palette.CyanDim, 0.7f * a1, 0f, 0, null, 0f, 0f, 0f)
            ticks(vaoTick120, 120, R, 1.5f * s, 5f * s, 12f * s, 10f, 1f, Palette.CyanDim, Palette.CyanMid, Palette.CyanDim,
                0.7f * a1, 0.9f * a1, false, 0f, deg(sc.rotL1))
            if (sc.cdAlpha > 0.003f) {
                tmp4[0] = 0f; tmp4[1] = sc.cdFrac * 6.2831855f; tmp4[2] = 0f; tmp4[3] = 0f
                ring(1.0f * R, 1.2f * s, 3f * s, 0.5f, Palette.CyanBright, 0.95f * sc.cdAlpha * g, 0f, 2, tmp4, 0f, 0f, 0f)
            }
            if (sc.crawlAmt > 0.01f) {
                tmp4[0] = deg2rad(sc.crawlDeg); tmp4[1] = deg2rad(2.5f); tmp4[2] = 0f; tmp4[3] = 0f
                ring(1.0f * R, 3.5f * s, 8f * s, 0.7f, Palette.Amber, sc.crawlAmt * a1, 0f, 2, tmp4, 0f, 0f, 0f)
            }
        }
        if (sn.sim && a1 > 0.003f) {
            ring(1.18f * R, 1.2f * s, 4f * s, 0.35f, Palette.Violet, 0.85f * g * max(a1, 0.6f), 0f, 0, null, 0f, 0f, 0f)
        }

        // L2 labels
        val a2 = lay[Lyr.LABELS] * g
        if (a2 > 0.003f) {
            GLES30.glUseProgram(pLabel)
            GLES30.glUniform2f(uLab[0], w.toFloat(), h.toFloat())
            GLES30.glUniform2f(uLab[1], cx, cy)
            GLES30.glUniform1f(uLab[2], 1.085f * R + 4f * s)
            GLES30.glUniform1f(uLab[3], deg2rad(sc.rotL1))
            GLES30.glUniform2f(uLab[4], 27f * s, 9f * s)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texAtlas)
            GLES30.glUniform1i(uLab[5], 0)
            val c = if (sn.sim) Palette.Violet else Palette.TextMuted
            val k = if (sn.sim) 1.0f else 1.5f
            GLES30.glUniform3f(uLab[6], c[0] * k, c[1] * k, c[2] * k)
            GLES30.glUniform1f(uLab[7], a2)
            GLES30.glBindVertexArray(vaoLabels)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 72)
        }

        // L3 segmented arcs
        val a3 = lay[Lyr.SEG] * g
        if (a3 > 0.003f) {
            val pk = 1f + 1.4f * sc.pulse
            ring(0.88f * R, 3f * s, if (tier.halo(3)) 10f * s else 0f, 0.45f, sc.l3c, 0.85f * a3 * pk, sc.rotL3, 1, null,
                deg2rad(60f), deg2rad(38f), 0f)
            if (sc.glint3 > 0.01f) {
                tmp4[0] = deg2rad(60f * sc.glint3Arc); tmp4[1] = deg2rad(38f); tmp4[2] = 0f; tmp4[3] = 0f
                ring(0.88f * R, 3f * s, 10f * s, 0.5f, Palette.CyanBright, 0.6f * a3 * sc.glint3, sc.rotL3, 2, tmp4, 0f, 0f, 0f)
            }
        }

        // L4 data arcs
        val a4 = lay[Lyr.DATA] * g
        if (a4 > 0.003f) {
            tmp4[0] = sc.arc0; tmp4[1] = sc.arc0Len; tmp4[2] = sc.arc1; tmp4[3] = sc.arc1Len
            ring(0.80f * R, 6f * s * (1f + 0.3f * sc.attention), if (tier.halo(4)) 14f * s else 0f, 0.5f, sc.l4c,
                a4 * sc.l4Alpha, sc.rotL4, 2, tmp4, 0f, 0f, 0.7f)
        }

        // L5 tick crown
        val a5 = lay[Lyr.CROWN] * g
        if (a5 > 0.003f) {
            val sweepOn = tier.sweep
            ticks(vaoTick60, 60, 0.72f * R, 1f * s, 8f * s, 8f * s, 0f, 1f, Palette.CyanDim, Palette.CyanDim,
                Palette.CyanBright, 0.75f * a5, 0.75f * a5, sweepOn, sc.sweepDeg / 6f, 0f, 1f + 0.5f * sc.glint5)
        }

        // L6 dashed ring
        val a6 = lay[Lyr.DASH] * g
        if (a6 > 0.003f) {
            for (i in 0..2) tmp3[i] = Palette.TealDeep[i] + (sc.inner[i] - Palette.TealDeep[i]) * 0.4f
            ring(0.58f * R, 2f * s, if (tier.halo(6)) 5f * s else 0f, 0.3f, tmp3, 0.8f * a6, sc.rotL6, 1, null,
                deg2rad(7.5f), deg2rad(3.75f), 0f)
        }

        // L7 reactive ring + echoes
        val a7 = lay[Lyr.REACT] * g
        if (a7 > 0.003f) {
            val l7r = sc.cur[P.L7_R] * R
            ring(l7r, 2.5f * s, if (tier.halo(7)) 12f * s else 0f, 0.55f, sc.inner, 0.9f * a7, sc.rotL7, 0, null, 0f, 0f, 0f, true)
            val now = monoMillis()
            for (i in 0 until 3) {
                val st = sc.echoStart[i]
                if (st < 0) continue
                val k = (now - st) / OrbScene.ECHO_MS.toFloat()
                if (k >= 1f) { sc.echoStart[i] = -1L; continue }
                val e = 1f - (1f - k) * (1f - k)
                ring((0.46f + 0.49f * e) * R, 2f * s, if (tier.halo(7)) 8f * s else 0f, 0.5f, sc.inner, 0.5f * (1f - k) * a7, 0f, 0, null, 0f, 0f, 0f)
            }
        }
        if (sc.latchedAmt > 0.01f) {
            ring(0.94f * R, 2.5f * s, 8f * s, 0.5f, Palette.Listen, 0.9f * sc.latchedAmt * g, 0f, 0, null, 0f, 0f, 0f)
        }

        // L8 core
        val a8 = lay[Lyr.CORE] * g
        if (a8 > 0.003f) {
            val cs = sc.coreScale
            GLES30.glUseProgram(pSprite)
            GLES30.glUniform2f(uSpr[0], w.toFloat(), h.toFloat())
            GLES30.glUniform2f(uSpr[1], cx, cy)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texCore)
            GLES30.glUniform1i(uSpr[3], 0)
            GLES30.glUniform3f(uSpr[4], sc.inner[0], sc.inner[1], sc.inner[2])
            GLES30.glUniform3f(uSpr[5], sc.hot[0], sc.hot[1], sc.hot[2])
            GLES30.glBindVertexArray(vaoEmpty)
            if (tier != Tier.D) {
                GLES30.glUniform1f(uSpr[2], 0.95f * R)
                GLES30.glUniform1f(uSpr[6], 0f)
                GLES30.glUniform1f(uSpr[7], 0.16f * sc.coreInt * a8)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            }
            GLES30.glUniform1f(uSpr[2], 0.36f * R * cs)
            GLES30.glUniform1f(uSpr[6], 1f)
            GLES30.glUniform1f(uSpr[7], 1.5f * sc.coreInt * a8)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            ring(0.12f * R * cs, 1.2f * s, 0f, 0f, sc.inner, 0.55f * a8, 0f, 0, null, 0f, 0f, 0f)
            ring(0.20f * R * cs, 1.2f * s, 0f, 0f, sc.inner, 0.4f * a8, 0f, 0, null, 0f, 0f, 0f)
        }

        drawParticles(tier, 0.5f, 1.01f)

        // H brackets
        val ah = lay[Lyr.HUD] * g
        if (ah > 0.003f) {
            GLES30.glUseProgram(pLine)
            GLES30.glUniform2f(uLine[0], w.toFloat(), h.toFloat())
            GLES30.glUniform2f(uLine[1], cx, cy)
            GLES30.glUniform1f(uLine[2], R)
            GLES30.glUniform1f(uLine[3], 1.5f * s)
            val c = if (sc.pulse > 0f) Palette.CyanBright else Palette.CyanDim
            GLES30.glUniform3f(uLine[4], c[0], c[1], c[2])
            GLES30.glUniform1f(uLine[5], min(1f, 0.6f + 1.2f * sc.pulse) * ah)
            strokeU(sLine, 1.5f * s, 0.5f * s + 0.25f, 0f, 1f, 0f, 1f, 0f, R, 0)
            GLES30.glBindVertexArray(vaoBrackets)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 48)
        }
    }

    private val tmp4 = FloatArray(4)

    private fun deg(d: Float) = d * (PI.toFloat() / 180f)
    private fun deg2rad(d: Float) = d * (PI.toFloat() / 180f)

    /** Shared FS uniforms. */
    private fun strokeU(
        u: IntArray, halfW: Float, strokeHalf: Float, glow: Float, sigma: Float, rot: Float, soft: Float, cap: Float,
        radPx: Float, mode: Int,
    ) {
        GLES30.glUniform1f(u[0], halfW)
        GLES30.glUniform1f(u[1], strokeHalf)
        GLES30.glUniform1f(u[2], glow)
        GLES30.glUniform1f(u[3], sigma)
        GLES30.glUniform1f(u[4], rot)
        GLES30.glUniform1f(u[5], soft)
        GLES30.glUniform1f(u[6], cap)
        GLES30.glUniform1f(u[7], radPx)
        GLES30.glUniform1i(u[8], mode)
    }

    /**
     * Draw one ring/arc strip. [stroke] full width px, [halo] extra glow reach px. [mode] 0 full, 1 periodic
     * (period,len in [per0],[per1]), 2 arcs from [arcs] (start0,len0,start1,len1; second pair may be 0 length).
     */
    private fun ring(
        radiusPx: Float, stroke: Float, halo: Float, glowAmt: Float, col: FloatArray, alpha: Float, rotDeg: Float,
        mode: Int, arcs: FloatArray?, per0: Float, per1: Float, cap: Float, disp: Boolean = false,
    ) {
        if (alpha <= 0.002f) return
        val half = stroke * 0.5f
        val halfW = half + halo + 1f
        val rotRad = deg2rad(rotDeg)
        GLES30.glUseProgram(pRing)
        GLES30.glUniform2f(uRing[0], w.toFloat(), h.toFloat())
        GLES30.glUniform2f(uRing[1], scene.cx, scene.cy)
        GLES30.glUniform1f(uRing[2], radiusPx)
        GLES30.glUniform1f(uRing[3], halfW)
        GLES30.glUniform1f(uRing[4], rotRad)
        if (disp) GLES30.glUniform1fv(uRing[5], 32, scene.disp, 0)
        GLES30.glUniform1f(uRing[6], if (disp) 1f else 0f)
        GLES30.glUniform3f(uRing[7], col[0], col[1], col[2])
        GLES30.glUniform1f(uRing[8], alpha)
        strokeU(sRing, halfW, half, if (halo > 0f) glowAmt else 0f, max(halo / 2.4f, 0.5f), rotRad, 1.3f / max(radiusPx, 1f), cap, radiusPx, mode)
        GLES30.glUniform2f(sRing[10], per0, per1)
        if (arcs != null) GLES30.glUniform4f(sRing[9], arcs[0], arcs[1], arcs[2], arcs[3])
        GLES30.glBindVertexArray(vaoRing)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, (GlUtil.RING_SEGMENTS + 1) * 2)
    }

    private fun ticks(
        vao: Int, count: Int, radiusPx: Float, width: Float, lenS: Float, lenL: Float, longEvery: Float, dirIn: Float,
        colS: FloatArray, colL: FloatArray, colSweep: FloatArray, aS: Float, aL: Float, sweepOn: Boolean, head: Float,
        rotRad: Float, sweepBoost: Float = 1f,
    ) {
        val halfW = width * 0.5f + 1f
        GLES30.glUseProgram(pTick)
        GLES30.glUniform2f(uTick[0], w.toFloat(), h.toFloat())
        GLES30.glUniform2f(uTick[1], scene.cx, scene.cy)
        GLES30.glUniform1f(uTick[2], radiusPx)
        GLES30.glUniform1f(uTick[3], halfW)
        GLES30.glUniform1f(uTick[4], rotRad)
        GLES30.glUniform1f(uTick[5], lenS)
        GLES30.glUniform1f(uTick[6], lenL)
        GLES30.glUniform1f(uTick[7], longEvery)
        GLES30.glUniform1f(uTick[8], dirIn)
        GLES30.glUniform3f(uTick[9], colS[0], colS[1], colS[2])
        GLES30.glUniform3f(uTick[10], colL[0], colL[1], colL[2])
        GLES30.glUniform3f(uTick[11], colSweep[0] * sweepBoost, colSweep[1] * sweepBoost, colSweep[2] * sweepBoost)
        GLES30.glUniform1f(uTick[12], aS)
        GLES30.glUniform1f(uTick[13], aL)
        GLES30.glUniform1f(uTick[14], if (sweepOn) 1f else 0f)
        GLES30.glUniform1f(uTick[15], head)
        GLES30.glUniform1f(uTick[16], count.toFloat())
        strokeU(sTick, halfW, width * 0.5f, 0f, 1f, 0f, 1f, 0f, radiusPx, 0)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, count * 6)
    }

    private fun drawParticles(tier: Tier, lo: Float, hi: Float) {
        val sc = scene
        val a = sc.layer[Lyr.PART] * sc.gain
        if (a < 0.003f || sc.partCount < 1f) return
        GLES30.glUseProgram(pPart)
        GLES30.glUniform2f(uPart[0], w.toFloat(), h.toFloat())
        GLES30.glUniform2f(uPart[1], sc.cx, sc.cy)
        GLES30.glUniform1f(uPart[2], sc.radius)
        GLES30.glUniform1f(uPart[3], sc.partOrbit)
        GLES30.glUniform1f(uPart[4], sc.partRadOff)
        GLES30.glUniform1f(uPart[5], sc.timeSec)
        GLES30.glUniform1f(uPart[6], sc.partCount)
        GLES30.glUniform1f(uPart[7], lo)
        GLES30.glUniform1f(uPart[8], hi)
        GLES30.glUniform1f(uPart[9], sc.partBright * a * 1.5f)
        GLES30.glUniform1f(uPart[10], sc.partPull)
        GLES30.glUniform1f(uPart[11], sc.scale)
        GLES30.glUniform3f(uPart[12], sc.inner[0], sc.inner[1], sc.inner[2])
        GLES30.glUniform3f(uPart[13], Palette.TealDeep[0], Palette.TealDeep[1], Palette.TealDeep[2])
        GLES30.glUniform3f(uPart[14], Palette.CyanBright[0], Palette.CyanBright[1], Palette.CyanBright[2])
        GLES30.glBindVertexArray(vaoParts)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, nParts)
    }
}
