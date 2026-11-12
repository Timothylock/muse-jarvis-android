package dev.timlock.jarvis.render.orb

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import dev.timlock.jarvis.core.model.AudioVisualBus
import dev.timlock.jarvis.core.model.OrbState
import dev.timlock.jarvis.core.model.OrbStateHolder
import dev.timlock.jarvis.core.model.monoMillis
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
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
    /** Debug: start a C7 micro-event on the next frame (1 L1 ticks, 2 L4 arcs, 3 comet, 4 core blink, -1 random). Consumed. */
    @Volatile var debugMicroEvent: Int = 0
    /** Debug: force night ember on/off for screenshots (null = follow the snapshot). */
    @Volatile var debugNight: Boolean? = null
    /** Cadence the view's Choreographer should drive (60/30/20). */
    @Volatile var targetFps: Int = 60
        private set

    internal val governor = QualityGovernor(headroom)
    private val scene = OrbScene(stateHolder, bus)

    private var broken = false
    private var w = 1; private var h = 1

    // programs
    private var pVig = 0; private var pRing = 0; private var pTick = 0; private var pLine = 0
    private var pLead = 0; private var pSprite = 0; private var pGrid = 0; private var pLabel = 0; private var pPart = 0
    private lateinit var uVig: IntArray
    private lateinit var uRing: IntArray; private lateinit var uTick: IntArray; private lateinit var uLine: IntArray
    private lateinit var uSpr: IntArray; private lateinit var uGrid: IntArray; private lateinit var uLab: IntArray
    private lateinit var uPart: IntArray
    private lateinit var uLead: IntArray
    private lateinit var sTick: IntArray; private lateinit var sLine: IntArray
    private var vaoTick120 = 0; private var vaoTick60 = 0; private var vaoLabels = 0
    private var vaoBrackets = 0; private var vaoParts = 0; private var vaoEmpty = 0
    private var texCore = 0; private var texAtlas = 0
    private val nMain = 600
    private val nParts = nMain + 96 // + 6 burst slots x 16 motes (C3)

    // perf
    private var lastFrameNs = 0L
    private var tierFps = 60
    private var lastNowMs = 0L
    private val sec = FloatArray(512); private var secN = 0
    private var secStart = 0L; private var secFrames = 0
    private val sb = StringBuilder(160)
    private val cpuSec = FloatArray(512); private var cpuN = 0
    private val gpuSec = FloatArray(512); private var gpuN = 0
    private val waitSec = FloatArray(512); private var waitN = 0
    /** ns spent in the frame's first glClear: the driver dequeues the next buffer there (back-pressure, not work). */
    private var clearNs = 0L

    // H2: GL_EXT_disjoint_timer_query ring (results read 1-3 frames late, never stalls)
    private var hasTimer = false
    private val qIds = IntArray(QN)
    private val qBusy = BooleanArray(QN)
    private var qOpen = -1
    private val qTmp = IntArray(1)

    // C1: instanced ring pass. Two batches (before / after the core sprite), each with its own VAO + instance VBO.
    private class RingBatch(val vao: Int, val vbo: Int, val buf: FloatBuffer) { var n = 0 }
    private lateinit var batchA: RingBatch
    private lateinit var batchB: RingBatch
    private lateinit var batch: RingBatch

    private val tmp3 = FloatArray(3)
    private val tmp3b = FloatArray(3)

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
        pRing = GlUtil.program(Shaders.RING_VS, Shaders.RING_FS)
        pTick = GlUtil.program(Shaders.TICK_VS, Shaders.STROKE_FS)
        pLine = GlUtil.program(Shaders.LINE_VS, Shaders.STROKE_FS)
        pLead = GlUtil.program(Shaders.LEAD_VS, Shaders.LEAD_FS)
        pSprite = GlUtil.program(Shaders.SPRITE_VS, Shaders.SPRITE_FS)
        pGrid = GlUtil.program(Shaders.SPRITE_VS, Shaders.GRID_FS)
        pLabel = GlUtil.program(Shaders.LABEL_VS, Shaders.LABEL_FS)
        pPart = GlUtil.program(Shaders.PART_VS, Shaders.PART_FS)
        uVig = locs(pVig, "uDeep", "uVoid", "uCenterPx", "uInv")
        val stroke = arrayOf("uHalfW", "uStroke", "uGlow", "uSigma", "uRot", "uSoft", "uCap", "uRadPx", "uMode", "uArcs", "uPer", "uRev", "uRevHot")
        sTick = locs(pTick, *stroke); sLine = locs(pLine, *stroke)
        uRing = locs(pRing, "uRes", "uDisp", "uRevHot")
        uTick = locs(
            pTick, "uRes", "uCenter", "uRadius", "uHalfW", "uRot", "uLenS", "uLenL", "uLongEvery", "uDir",
            "uColS", "uColL", "uColSweep", "uAlphaS", "uAlphaL", "uSweepOn", "uHead", "uCount",
            "uTRev", "uSw2", "uHotCol",
        )
        uLine = locs(pLine, "uRes", "uCenter", "uR", "uHalfW", "uColor", "uAlpha")
        uSpr = locs(pSprite, "uRes", "uCenter", "uHalf", "uTex", "uTint", "uHot", "uHotMix", "uInt")
        uGrid = locs(pGrid, "uRes", "uCenter", "uHalf", "uR", "uRot", "uCol", "uA", "uRadar", "uRadarAng", "uExp", "uK")
        uLab = locs(pLabel, "uRes", "uCenter", "uRadius", "uRot", "uHalfSize", "uTex", "uColor", "uAlpha")
        uPart = locs(
            pPart, "uRes", "uCenter", "uR", "uOrbit", "uRadOff", "uTime", "uCount", "uDepthLo", "uDepthHi",
            "uBright", "uPull", "uScale", "uTint", "uTeal", "uBrightCol",
            "uBurstBase", "uBurstBr", "uHotCol", "uBurst", "uBurstS",
            "uDrift", "uCometIdx", "uCometOff", "uComet",
        )
        uLead = locs(
            pLead, "uRes", "uP0", "uDir", "uLc", "uPad", "uHalf", "uGlowSig", "uAlpha", "uCol", "uHot",
            "uHeadAmt", "uHeadSig", "uPulseS", "uPulseAmt", "uPulseSig",
        )
        val strip = GlUtil.ringStrip()
        batchA = ringBatch(strip); batchB = ringBatch(strip); batch = batchA
        vaoTick120 = GlUtil.vao(GlUtil.ticks(120), 4)
        vaoTick60 = GlUtil.vao(GlUtil.ticks(60), 4)
        vaoLabels = GlUtil.vao(GlUtil.labels(), 4)
        vaoBrackets = GlUtil.vao(GlUtil.brackets(), 4, 1)
        val pdata = GlUtil.particles(nParts)
        vaoParts = GlUtil.vao(pdata, 4, 4)
        pickComets(pdata)
        vaoEmpty = GlUtil.emptyVao()
        texCore = GlUtil.coreSprite()
        texAtlas = GlUtil.labelAtlas()
        initTimer()
    }

    private fun ringBatch(strip: FloatArray): RingBatch {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        val vbo = ids[0]
        val bytes = MAX_RINGS * INST_FLOATS * 4
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, bytes, null, GLES30.GL_STREAM_DRAW)
        val vao = GlUtil.vao(strip, 2) // attribute 0 from the shared strip VBO; leaves the VAO unbound
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        for (i in 0 until 6) {
            GLES30.glEnableVertexAttribArray(1 + i)
            GLES30.glVertexAttribPointer(1 + i, 4, GLES30.GL_FLOAT, false, INST_FLOATS * 4, i * 16)
            GLES30.glVertexAttribDivisor(1 + i, 1)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        val fb = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer()
        return RingBatch(vao, vbo, fb)
    }

    /** C7(c): four fixed streamer vertices (among the first 120, always inside the live particle count). */
    private fun pickComets(pdata: FloatArray) {
        var k = 0
        for (i in 0 until 120) {
            if (pdata[i * 8 + 6] > 0f && k < 4) scene.cometIdx[k++] = i.toFloat()
        }
    }

    private fun initTimer() {
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        hasTimer = ext.contains("GL_EXT_disjoint_timer_query")
        if (hasTimer) {
            GLES30.glGenQueries(QN, qIds, 0)
            java.util.Arrays.fill(qBusy, false)
            GLES30.glGetIntegerv(GPU_DISJOINT_EXT, qTmp, 0) // clear any stale disjoint flag
        }
        qOpen = -1
        Log.i("JARVIS_PERF", "gpu timer query: " + (if (hasTimer) "available" else "unavailable"))
    }

    /** Collect finished queries (non-blocking) and open a new one on a free slot. */
    private fun gpuBegin() {
        if (!hasTimer) return
        var anyBusy = false
        for (i in 0 until QN) if (qBusy[i]) anyBusy = true
        if (anyBusy) {
            GLES30.glGetIntegerv(GPU_DISJOINT_EXT, qTmp, 0)
            val disjoint = qTmp[0] != 0
            for (i in 0 until QN) {
                if (!qBusy[i]) continue
                GLES30.glGetQueryObjectuiv(qIds[i], GLES30.GL_QUERY_RESULT_AVAILABLE, qTmp, 0)
                if (qTmp[0] == 0) continue
                GLES30.glGetQueryObjectuiv(qIds[i], GLES30.GL_QUERY_RESULT, qTmp, 0)
                qBusy[i] = false
                if (!disjoint && gpuN < gpuSec.size) gpuSec[gpuN++] = (qTmp[0].toLong() and 0xFFFFFFFFL) / 1e6f
            }
        }
        for (i in 0 until QN) {
            if (!qBusy[i]) {
                GLES30.glBeginQuery(TIME_ELAPSED_EXT, qIds[i]); qBusy[i] = true; qOpen = i
                return
            }
        }
    }

    private fun gpuEnd() {
        if (qOpen >= 0) { GLES30.glEndQuery(TIME_ELAPSED_EXT); qOpen = -1 }
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
        val night = debugNight ?: sn.night
        val idleLong = sn.state == OrbState.IDLE && nowMs - sn.sinceMs >= OrbScene.AMBIENT_AFTER_MS
        val ovr = qualityOverride
        val tier = ovr ?: if (night) Tier.D else governor.tier
        var fps = tier.fps
        // H7: don't switch cadence while the halos are still easing to the new tier (hold the old one)
        if (fps != tierFps && scene.haloSettling) fps = tierFps else tierFps = fps
        if (night) fps = min(fps, 20)
        if (idleLong || scene.ambientMix > 0.99f) fps = min(fps, 30)
        targetFps = fps

        scene.ambientOverride = ambientStyleOverride
        val dbg = debugMicroEvent
        if (dbg != 0) { debugMicroEvent = 0; scene.triggerMicro(dbg) }
        scene.update(nowMs, dt, w, h, tier, night)
        val td = System.nanoTime()
        gpuBegin()
        draw(tier)
        gpuEnd()
        val waitMs = clearNs / 1e6f
        val cpuMs = (System.nanoTime() - td) / 1e6f - waitMs
        if (cpuN < cpuSec.size) cpuSec[cpuN++] = cpuMs
        if (waitN < waitSec.size) waitSec[waitN++] = waitMs

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
        var p50 = 0f; var p95 = 0f; var cpu = 0f; var gpu = -1f
        if (cpuN > 0) { Arrays.sort(cpuSec, 0, cpuN); cpu = cpuSec[(cpuN - 1) / 2] }
        if (gpuN > 0) { Arrays.sort(gpuSec, 0, gpuN); gpu = gpuSec[(gpuN - 1) / 2] }
        var wait = 0f; var cpu95 = 0f
        if (waitN > 0) { Arrays.sort(waitSec, 0, waitN); wait = waitSec[(waitN - 1) / 2] }
        if (cpuN > 0) cpu95 = cpuSec[((cpuN - 1) * 0.95f).toInt()]
        if (secN > 0) {
            Arrays.sort(sec, 0, secN)
            p50 = sec[(secN - 1) / 2]; p95 = sec[((secN - 1) * 0.95f).toInt()]
        }
        val parts = scene.partCount.toInt()
        PerfStats.cpuP50Ms = cpu; PerfStats.gpuP50Ms = gpu; PerfStats.fps = fpsMeasured; PerfStats.p50Ms = p50; PerfStats.p95Ms = p95
        PerfStats.tier = tier; PerfStats.particles = parts; PerfStats.thermal = governor.thermalStatus
        PerfStats.targetFps = targetFps
        sb.setLength(0)
        sb.append("{\"fps\":"); fixed(fpsMeasured); sb.append(",\"p50\":"); fixed(p50)
        sb.append(",\"p95\":"); fixed(p95)
        sb.append(",\"cpuMs\":"); fixed(cpu); sb.append(",\"cpuP95\":"); fixed(cpu95); sb.append(",\"waitMs\":"); fixed(wait)
        sb.append(",\"gpuMs\":"); if (gpu < 0f) sb.append("-1") else fixed(gpu)
        sb.append(",\"tier\":\"").append(tier.name)
        sb.append("\",\"particles\":").append(parts).append(",\"thermal\":").append(governor.thermalStatus).append('}')
        Log.i("JARVIS_PERF", sb.toString())
        secN = 0; cpuN = 0; gpuN = 0; waitN = 0; secFrames = 0; secStart = nowMs
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
        val Rc = sc.radius; val s = sc.scale
        val R = sc.oRadius // outer group (L0-L3, H); core-group rings use Rc
        val cx = sc.cx; val cy = sc.cy
        val ocx = sc.ocx; val ocy = sc.ocy
        val lay = sc.layer
        val hm = sc.haloMix

        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(Palette.Void[0] * g, Palette.Void[1] * g, Palette.Void[2] * g, 1f)
        val tc = System.nanoTime()
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        clearNs = System.nanoTime() - tc

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

        batchA.n = 0; batchB.n = 0; batch = batchA

        // L0 polar grid + radar
        if (lay[Lyr.GRID] > 0.003f) {
            GLES30.glUseProgram(pGrid)
            GLES30.glUniform2f(uGrid[0], w.toFloat(), h.toFloat())
            GLES30.glUniform2f(uGrid[1], ocx, ocy)
            val gk = 1.6f - 0.25f * sc.dockMix // H16: grid radius fades to 1.35 R when DOCKED
            GLES30.glUniform1f(uGrid[2], gk * R) // quad half-size
            GLES30.glUniform1f(uGrid[10], gk)
            GLES30.glUniform1f(uGrid[3], R)
            GLES30.glUniform1f(uGrid[4], deg(sc.rotL0))
            GLES30.glUniform3f(uGrid[5], Palette.GridFaint[0] * 2.2f, Palette.GridFaint[1] * 2.2f, Palette.GridFaint[2] * 2.2f)
            val gr = sc.rev[Lyr.GRID]
            GLES30.glUniform1f(uGrid[6], lay[Lyr.GRID] * g * 0.9f * min(1f, gr * 3f))
            GLES30.glUniform1f(uGrid[9], 0.3f + 1.4f * gr) // C2: grid expands 0.3 R -> 1.6 R
            GLES30.glUniform1f(uGrid[7], sc.cur[P.RADAR] * lay[Lyr.GRID])
            GLES30.glUniform1f(uGrid[8], deg(sc.radarDeg))
            GLES30.glBindVertexArray(vaoEmpty)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }

        drawParticles(0f, 0.5f, nMain)

        ctrOuter = true
        // L1 bezel + ticks (+ countdown / crawl dot, sim ring)
        val a1 = lay[Lyr.BEZEL] * g
        if (a1 > 0.003f) {
            val w1 = (1.5f + 0.5f * sc.ambientMix) * s // H10: strokes >= 2 px in ambient
            ring(1.0f * R, w1, 4f * s * hm[1], 0.3f * hm[1], Palette.CyanDim, 0.7f * a1, 0f, 0, null, 0f, 0f, 0f)
            setTickRev(Lyr.BEZEL, 2f)
            val mh = sc.microTickHead // C7(a)
            ticks(vaoTick120, 120, R, w1, 5f * s, 12f * s, 10f, 1f, Palette.CyanDim, Palette.CyanMid, Palette.CyanDim,
                0.7f * a1, 0.9f * a1, mh >= 0f, mh, deg(sc.rotL1), 1.6f)
            clearTickRev()
            if (sc.cdAlpha > 0.003f) {
                // H6: 3 px countdown arc with a 6 px bright head dot that visibly shortens
                val cdLen = sc.cdFrac * 6.2831855f
                tmp4[0] = 0f; tmp4[1] = cdLen; tmp4[2] = 0f; tmp4[3] = 0f
                ring(1.0f * R, 3f * s, 3f * s, 0.5f, Palette.CyanBright, 0.8f * sc.cdAlpha * g, 0f, 2, tmp4, 0f, 0f, 0f)
                if (cdLen > 0.01f) {
                    val dot = 6f * s / R
                    tmp4[0] = max(0f, cdLen - dot); tmp4[1] = min(dot, cdLen)
                    ring(1.0f * R, 6f * s, 4f * s, 0.6f, sc.hot, sc.cdAlpha * g, 0f, 2, tmp4, 0f, 0f, 0f)
                }
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
            GLES30.glUniform2f(uLab[1], ocx, ocy)
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
            setRev(Lyr.SEG, true)
            ring(0.88f * R, 3f * s, 10f * s * hm[3], 0.45f * hm[3], sc.l3c, 0.85f * a3 * pk, sc.rotL3, 1, null,
                deg2rad(60f), deg2rad(38f), 0f)
            if (sc.glint3 > 0.01f) {
                tmp4[0] = deg2rad(60f * sc.glint3Arc); tmp4[1] = deg2rad(38f); tmp4[2] = 0f; tmp4[3] = 0f
                ring(0.88f * R, 3f * s, 10f * s * hm[3], 0.5f * hm[3], Palette.CyanBright, 0.6f * a3 * sc.glint3, sc.rotL3, 2, tmp4, 0f, 0f, 0f)
            }
            clearRev()
        }

        ctrOuter = false
        // L4 data arcs
        val a4 = lay[Lyr.DATA] * g
        if (a4 > 0.003f) {
            tmp4[0] = sc.arc0; tmp4[1] = sc.arc0Len; tmp4[2] = sc.arc1; tmp4[3] = sc.arc1Len
            ring(0.80f * Rc, 6f * s * (1f + 0.3f * sc.attention), 14f * s * hm[4], 0.5f * hm[4], sc.l4c,
                a4 * sc.l4Alpha, sc.rotL4, 2, tmp4, 0f, 0f, 0.7f)
        }

        // L5 tick crown
        val a5 = lay[Lyr.CROWN] * g
        if (a5 > 0.003f) {
            val sweepOn = tier.sweep
            setTickRev(Lyr.CROWN, if (sc.sleeping) 1f else 0f)
            // C11: second, counter-running sweep in THINKING; where the heads cross the ticks flash cyan.hot
            sw2Head = sc.sweep2Deg / 6f; sw2Amt = if (sweepOn) sc.thinkMix else 0f
            ticks(vaoTick60, 60, 0.72f * Rc, 1f * s, 8f * s, 8f * s, 0f, 1f, Palette.CyanDim, Palette.CyanDim,
                Palette.CyanBright, 0.75f * a5, 0.75f * a5, sweepOn, sc.sweepDeg / 6f, 0f, 1f + 0.5f * sc.glint5)
            sw2Amt = 0f
            clearTickRev()
        }

        // L6 dashed ring
        val a6 = lay[Lyr.DASH] * g
        if (a6 > 0.003f) {
            for (i in 0..2) tmp3[i] = Palette.TealDeep[i] + (sc.inner[i] - Palette.TealDeep[i]) * 0.4f
            setRev(Lyr.DASH, true)
            ring(0.58f * Rc, 2f * s, 5f * s * hm[6], 0.3f * hm[6], tmp3, 0.8f * a6, sc.rotL6, 1, null,
                deg2rad(7.5f), deg2rad(3.75f), 0f)
            clearRev()
        }

        // L7 reactive ring + echoes
        val a7 = lay[Lyr.REACT] * g
        if (a7 > 0.003f) {
            val l7r = sc.cur[P.L7_R] * Rc
            // C5: loud syllables fatten and bloom the ring (stroke 2.5->4.5 px, halo 12->22 px) and warm it toward cyan.hot
            val ev = sc.env.coerceIn(0f, 1f)
            val hk = ((ev - 0.7f) / 0.3f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
            for (i in 0..2) tmp3b[i] = sc.inner[i] + (sc.hot[i] - sc.inner[i]) * hk
            val halo7 = (12f + 10f * ev + 8f * sc.l7Pulse) * s * hm[7]  // H6: STANDBY awake pulse 12->20 px
            setRev(Lyr.REACT, false)
            ring(l7r, (2.5f + 2f * ev) * s, halo7, 0.55f * hm[7], tmp3b, 0.9f * a7, sc.rotL7, 0, null, 0f, 0f, 0f, true)
            clearRev()
            val now = monoMillis()
            for (i in 0 until 3) {
                val st = sc.echoStart[i]
                if (st < 0) continue
                val k = (now - st) / OrbScene.ECHO_MS.toFloat()
                if (k >= 1f) { sc.echoStart[i] = -1L; continue }
                val e = 1f - (1f - k) * (1f - k)
                // H13: alpha 0.25 + 0.5 * onset strength
                ring((0.46f + 0.49f * e) * Rc, 2f * s, 8f * s * hm[7], 0.5f * hm[7], sc.inner,
                    (0.25f + 0.5f * sc.echoStr[i]) * (1f - k) * a7, 0f, 0, null, 0f, 0f, 0f)
            }
        }
        ctrOuter = true
        if (sc.latchedAmt > 0.01f && sc.nightMix < 0.99f) {
            ring(0.94f * R, 2.5f * s, 8f * s, 0.5f, Palette.Listen, 0.9f * sc.latchedAmt * g * (1f - sc.nightMix), 0f, 0, null, 0f, 0f, 0f)
        }

        ctrOuter = false
        flushRings(batchA)
        batch = batchB
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
                GLES30.glUniform1f(uSpr[2], 0.95f * Rc)
                GLES30.glUniform1f(uSpr[6], 0f)
                GLES30.glUniform1f(uSpr[7], 0.22f * sc.coreInt * a8)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            }
            GLES30.glUniform1f(uSpr[2], 0.36f * Rc * cs)
            GLES30.glUniform1f(uSpr[6], 1f)
            GLES30.glUniform1f(uSpr[7], 1.05f * sc.coreInt * a8) // H12
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            ring(0.12f * Rc * cs, 1.2f * s, 0f, 0f, sc.inner, 0.55f * a8, 0f, 0, null, 0f, 0f, 0f)
            ring(0.20f * Rc * cs, 1.2f * s, 0f, 0f, sc.inner, 0.4f * a8, 0f, 0, null, 0f, 0f, 0f)
        }

        flushRings(batchB)
        drawParticles(0.5f, 1.01f, nParts)

        // H brackets (outer group)
        ctrOuter = true
        val ah = lay[Lyr.HUD] * g
        if (ah > 0.003f) {
            GLES30.glUseProgram(pLine)
            GLES30.glUniform2f(uLine[0], w.toFloat(), h.toFloat())
            GLES30.glUniform2f(uLine[1], ocx, ocy)
            GLES30.glUniform1f(uLine[2], R)
            GLES30.glUniform1f(uLine[3], 1.5f * s)
            // H14: colour mixed by the exponential pulse instead of switching
            val pu = sc.pulse
            val c0 = Palette.CyanDim; val c1 = Palette.CyanBright
            val al = min(1f, 0.6f + 1.2f * pu) * ah
            GLES30.glBindVertexArray(vaoBrackets)
            // H17: with leaders, the bracket facing the docked panels is the anchor (cyan.mid, 2 px); the rest stay faint
            var pick = -1
            if (sc.leaderN > 0) {
                var mx = 0f; var my = 0f
                for (i in 0 until sc.leaderN) { mx += sc.ldDx[i]; my += sc.ldDy[i] }
                // bracket k sits at 45 + 90k deg: (sin, -cos); choose max dot with the mean leader direction
                var best = -1e9f
                for (k in 0 until 4) {
                    val th = (45f + 90f * k) * (PI.toFloat() / 180f)
                    val d = kotlin.math.sin(th) * mx - kotlin.math.cos(th) * my
                    if (d > best) { best = d; pick = k }
                }
            }
            GLES30.glUniform3f(uLine[4], c0[0] + (c1[0] - c0[0]) * pu, c0[1] + (c1[1] - c0[1]) * pu, c0[2] + (c1[2] - c0[2]) * pu)
            GLES30.glUniform1f(uLine[5], al)
            strokeU(sLine, 1.5f * s, 0.5f * s + 0.25f, 0f, 1f, 0f, 1f, 0f, R, 0)
            if (pick < 0) {
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 48)
            } else {
                if (pick > 0) GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, pick * 12)
                if (pick < 3) GLES30.glDrawArrays(GLES30.GL_TRIANGLES, (pick + 1) * 12, (3 - pick) * 12)
                val cm = Palette.CyanMid
                GLES30.glUniform1f(uLine[3], 2f * s)
                GLES30.glUniform3f(uLine[4], cm[0], cm[1], cm[2])
                GLES30.glUniform1f(uLine[5], ah)
                strokeU(sLine, 2f * s, 1f * s + 0.25f, 0f, 1f, 0f, 1f, 0f, R, 0)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, pick * 12, 12)
            }
        }

        drawLeaders(g)
    }

    /** GL leader lines to the DOCK panels (H4/C9/C15): one quad draw per leader (head + pulse dots in the same draw). */
    private fun drawLeaders(g: Float) {
        val sc = scene
        if (sc.leaderN == 0) return
        val s = sc.scale
        val cm = Palette.CyanMid; val ch = sc.hot
        GLES30.glUseProgram(pLead)
        GLES30.glUniform2f(uLead[0], w.toFloat(), h.toFloat())
        GLES30.glUniform1f(uLead[5], 0.5f * max(s, 1f))
        GLES30.glUniform1f(uLead[6], 3f * s)
        GLES30.glUniform3f(uLead[8], cm[0], cm[1], cm[2])
        GLES30.glUniform3f(uLead[9], ch[0] * g, ch[1] * g, ch[2] * g)
        GLES30.glUniform1f(uLead[11], 3f * s)
        GLES30.glUniform1f(uLead[14], 4f * s)
        GLES30.glUniform1f(uLead[4], 16f * s)
        GLES30.glBindVertexArray(vaoEmpty)
        for (i in 0 until sc.leaderN) {
            GLES30.glUniform2f(uLead[1], sc.ldX0[i], sc.ldY0[i])
            GLES30.glUniform2f(uLead[2], sc.ldDx[i], sc.ldDy[i])
            GLES30.glUniform1f(uLead[3], sc.ldLen[i])
            GLES30.glUniform1f(uLead[7], sc.ldAlpha[i] * g)
            GLES30.glUniform1f(uLead[10], sc.ldHead[i])
            GLES30.glUniform1f(uLead[12], sc.ldPulseS[i])
            GLES30.glUniform1f(uLead[13], sc.ldPulseAmt[i])
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    private val tmp4 = FloatArray(4)

    // C2 reveal state consumed by ring()/ticks() (set around the layer's draws; no extra draw calls)
    private var revOn = false; private var revV = 1f; private var revHead = 0f; private var revMask = true
    private var tRevOn = false; private var tRevV = 1f; private var tRevMode = 0f; private var tRevHead = 0f
    private var sw2Head = 0f; private var sw2Amt = 0f

    private fun setRev(layer: Int, mask: Boolean) {
        val v = scene.rev[layer]
        revOn = v < 0.999f; revV = v; revHead = scene.revHead[layer]; revMask = mask
    }
    private fun clearRev() { revOn = false }
    /** [mode] 0 index mask + head (wake), 1 hash drop-out (sleep), 2 head pass only. */
    private fun setTickRev(layer: Int, modeOrSleep: Float) {
        val v = scene.rev[layer]
        tRevOn = v < 0.999f; tRevV = v; tRevHead = scene.revHead[layer]
        tRevMode = modeOrSleep
    }
    private fun clearTickRev() { tRevOn = false }
    private var ctrOuter = false

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
     * Queue one ring/arc strip into the current instance batch (C1). [stroke] full width px, [halo] extra glow reach px.
     * [mode] 0 full, 1 periodic (period,len in [per0],[per1]), 2 arcs from [arcs] (start0,len0,start1,len1).
     * Nothing is drawn here; [flushRings] issues one instanced draw per batch.
     */
    private fun ring(
        radiusPx: Float, stroke: Float, halo: Float, glowAmt: Float, col: FloatArray, alpha: Float, rotDeg: Float,
        mode: Int, arcs: FloatArray?, per0: Float, per1: Float, cap: Float, disp: Boolean = false,
    ) {
        if (alpha <= 0.002f) return
        val b = batch
        if (b.n >= MAX_RINGS) return
        val half = stroke * 0.5f
        val halfW = half + halo + 1f
        val f = b.buf
        val o = b.n * INST_FLOATS
        f.put(o, radiusPx); f.put(o + 1, halfW); f.put(o + 2, half); f.put(o + 3, if (halo > 0.05f) glowAmt else 0f)
        f.put(o + 4, max(halo / 2.4f, 0.5f)); f.put(o + 5, deg2rad(rotDeg)); f.put(o + 6, cap); f.put(o + 7, mode.toFloat())
        if (mode == 1) { f.put(o + 8, per0); f.put(o + 9, per1); f.put(o + 10, 0f); f.put(o + 11, 0f) }
        else if (arcs != null) { f.put(o + 8, arcs[0]); f.put(o + 9, arcs[1]); f.put(o + 10, arcs[2]); f.put(o + 11, arcs[3]) }
        else { f.put(o + 8, 0f); f.put(o + 9, 0f); f.put(o + 10, 0f); f.put(o + 11, 0f) }
        f.put(o + 12, if (revOn) 1f else 0f); f.put(o + 13, revV); f.put(o + 14, revHead); f.put(o + 15, if (revMask) 1f else 0f)
        f.put(o + 16, col[0]); f.put(o + 17, col[1]); f.put(o + 18, col[2]); f.put(o + 19, alpha)
        f.put(o + 20, if (ctrOuter) scene.ocx else scene.cx); f.put(o + 21, if (ctrOuter) scene.ocy else scene.cy)
        f.put(o + 22, if (disp) 1f else 0f); f.put(o + 23, 0f)
        b.n++
    }

    private fun flushRings(b: RingBatch) {
        if (b.n == 0) return
        val bytes = b.n * INST_FLOATS * 4
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, MAX_RINGS * INST_FLOATS * 4, null, GLES30.GL_STREAM_DRAW) // orphan: no stall
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, bytes, b.buf)
        GLES30.glUseProgram(pRing)
        GLES30.glUniform2f(uRing[0], w.toFloat(), h.toFloat())
        GLES30.glUniform1fv(uRing[1], 32, scene.disp, 0)
        val hc = scene.hot
        GLES30.glUniform3f(uRing[2], hc[0], hc[1], hc[2])
        GLES30.glBindVertexArray(b.vao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, (GlUtil.RING_SEGMENTS + 1) * 2, b.n)
        b.n = 0
    }

    private fun ticks(
        vao: Int, count: Int, radiusPx: Float, width: Float, lenS: Float, lenL: Float, longEvery: Float, dirIn: Float,
        colS: FloatArray, colL: FloatArray, colSweep: FloatArray, aS: Float, aL: Float, sweepOn: Boolean, head: Float,
        rotRad: Float, sweepBoost: Float = 1f,
    ) {
        val halfW = width * 0.5f + 1f
        GLES30.glUseProgram(pTick)
        GLES30.glUniform2f(uTick[0], w.toFloat(), h.toFloat())
        GLES30.glUniform2f(uTick[1], if (ctrOuter) scene.ocx else scene.cx, if (ctrOuter) scene.ocy else scene.cy)
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
        GLES30.glUniform4f(uTick[17], if (tRevOn) 1f else 0f, tRevV, tRevMode, tRevHead)
        GLES30.glUniform2f(uTick[18], sw2Head, sw2Amt)
        GLES30.glUniform3f(uTick[19], scene.hot[0], scene.hot[1], scene.hot[2])
        strokeU(sTick, halfW, width * 0.5f, 0f, 1f, 0f, 1f, 0f, radiusPx, 0)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, count * 6)
    }

    private fun drawParticles(lo: Float, hi: Float, count: Int) {
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
        GLES30.glUniform1f(uPart[15], nMain.toFloat())
        GLES30.glUniform1f(uPart[16], 1.1f * a)
        GLES30.glUniform3f(uPart[17], sc.hot[0], sc.hot[1], sc.hot[2])
        GLES30.glUniform1fv(uPart[18], 6, sc.burstT, 0)
        GLES30.glUniform1fv(uPart[19], 6, sc.burstS, 0)
        GLES30.glUniform2f(uPart[20], sc.driftX, sc.driftY)
        GLES30.glUniform1fv(uPart[21], 4, sc.cometIdx, 0)
        GLES30.glUniform1fv(uPart[22], 4, sc.cometOff, 0)
        GLES30.glUniform2f(uPart[23], sc.cometAct, sc.cometBoost)
        GLES30.glBindVertexArray(vaoParts)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, count)
    }

    private companion object {
        const val MAX_RINGS = 32
        const val INST_FLOATS = 24
        const val QN = 3
        const val TIME_ELAPSED_EXT = 0x88BF
        const val GPU_DISJOINT_EXT = 0x8FBB
    }
}
