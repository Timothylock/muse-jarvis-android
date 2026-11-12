package dev.timlock.jarvis.render.orb

import dev.timlock.jarvis.core.model.AudioLevels
import dev.timlock.jarvis.core.model.AudioVisualBus
import dev.timlock.jarvis.core.model.OrbLayout
import dev.timlock.jarvis.core.model.OrbSnapshot
import dev.timlock.jarvis.core.model.OrbState
import dev.timlock.jarvis.core.model.OrbStateHolder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Turns (snapshot, audio, time) into the per-frame visual parameters the GL code draws.
 * Allocation-free after construction. GL thread only.
 */
internal class OrbScene(private val holder: OrbStateHolder, private val bus: AudioVisualBus) {
    // ---- outputs ----
    var snap: OrbSnapshot = OrbSnapshot(); private set
    val cur = FloatArray(P.N)
    val layer = FloatArray(Lyr.COUNT)
    /** Core group (L4-L8, particles). */
    var cx = 0f; var cy = 0f; var radius = 1f; var scale = 1f
    /** Outer group (L0-L3, H brackets, L1 labels): lags the core spring (review C4). */
    var ocx = 0f; var ocy = 0f; var oRadius = 1f
    // leader lines (review H4/C9/C15); px in surface space. Slot i valid for i < leaderN.
    var leaderN = 0
    val ldX0 = FloatArray(3); val ldY0 = FloatArray(3); val ldDx = FloatArray(3); val ldDy = FloatArray(3)
    val ldLen = FloatArray(3)      // currently drawn length px
    val ldAlpha = FloatArray(3); val ldHead = FloatArray(3)
    val ldPulseS = FloatArray(3)   // pulse dot position along line px
    val ldPulseAmt = FloatArray(3)
    var gain = 1f
    var night = false
    val inner = FloatArray(3); val l4c = FloatArray(3); val l3c = FloatArray(3); val hot = FloatArray(3)
    var rotL0 = 0f; var rotL1 = 0f; var rotL3 = 0f; var rotL4 = 0f; var rotL6 = 0f; var rotL7 = 0f
    var sweepDeg = 0f; var radarDeg = 0f; var crawlDeg = 0f
    var arc0 = 0f; var arc0Len = 0f; var arc1 = 0f; var arc1Len = 0f
    var l4Alpha = 0f
    var coreScale = 1f; var coreInt = 0f
    val disp = FloatArray(32)
    var glint3 = 0f; var glint3Arc = 0; var glint5 = 0f
    var pulse = 0f; var attention = 0f
    var shakeX = 0f
    val echoStart = LongArray(3) { -1L }
    var latchedAmt = 0f
    var cdAlpha = 0f; var cdFrac = 0f
    var crawlAmt = 0f
    var ambientMix = 0f
    var env = 0f
    // particles
    var partOrbit = 0f; var partRadOff = 0f; var partCount = 120f; var partBright = 0f; var partPull = 1f
    var timeSec = 0f

    // ---- internal state ----
    private val from = FloatArray(P.N)
    private var inited = false
    private var trackState = OrbState.BOOT
    private var trackSince = Long.MIN_VALUE
    private var durMs = 400f
    private var easeId = StateTable.IN_OUT
    private var startMs = -1L
    private var layoutIdx = -1
    private var lToIdx = 0
    private var outerIdx = 0
    private var outerAtMs = 0L
    private var outerPending = false
    // springs: [0..2] core cx,cy,d ; [3..5] outer cx,cy,d (design px)
    private val sp6 = FloatArray(6); private val sv6 = FloatArray(6)
    private var offY = 0f
    // leader y easing state (design px)
    private val ldY = FloatArray(3); private val ldV = FloatArray(3); private val ldRs = LongArray(3)
    private var ldPrevN = 0
    private val ldNewY = FloatArray(3); private val ldNewV = FloatArray(3)
    private val micBuf = FloatArray(AudioLevels.FRAME_SIZE)
    private val outBuf = FloatArray(AudioLevels.FRAME_SIZE)
    private val bands = FloatArray(8)
    private var envMic = 0f; private var envOut = 0f
    private var lastOnset = -1
    private var lastLoudMs = 0L
    private var relax = 1f
    private var l4Phase = 0f
    private var nextGlintMs = 0L; private var glintStart = -10_000L
    private var rng = 0x2545F491L
    private var phaseRev = 0f
    private var lastCdStart = 0L; private var lastCdEnd = 0L
    var ambientOverride: Boolean? = null

    private fun rand(): Float {
        rng = rng xor (rng shl 13); rng = rng xor (rng ushr 7); rng = rng xor (rng shl 17)
        return ((rng ushr 11) and 0xFFFFFF).toFloat() / 16777216f
    }

    private fun nz(t: Float, ph: Float): Float =
        (sin(TWO_PI * t / 20f + ph) + 0.5f * sin(TWO_PI * t / 13.7f + ph * 1.9f)) / 1.5f

    fun update(nowMs: Long, dt: Float, w: Int, h: Int, tier: Tier, isNight: Boolean) {
        val sn = holder.snapshot
        snap = sn
        night = isNight
        if (startMs < 0) { startMs = nowMs; nextGlintMs = nowMs + 6000 }
        val t = (nowMs - startMs) / 1000f
        timeSec = t

        // ---- state interpolation ----
        if (!inited) {
            StateTable.rows[sn.previous.ordinal].copyInto(from)
            trackState = sn.state; trackSince = sn.sinceMs
            durMs = StateTable.durationMs(sn.previous, sn.state); easeId = StateTable.easeId(sn.previous, sn.state)
            inited = true
        } else if (sn.state != trackState || sn.sinceMs != trackSince) {
            cur.copyInto(from)
            durMs = StateTable.durationMs(trackState, sn.state); easeId = StateTable.easeId(trackState, sn.state)
            trackState = sn.state; trackSince = sn.sinceMs
        }
        val el = (nowMs - trackSince).toFloat()
        val e = StateTable.ease(easeId, (el / durMs).coerceIn(0f, 1f))
        val et = (el / 400f).coerceIn(0f, 1f)
        val row = StateTable.rows[trackState.ordinal]
        for (i in 0 until P.TINT_START) cur[i] = from[i] + (row[i] - from[i]) * e
        for (i in P.TINT_START until P.N) cur[i] = from[i] + (row[i] - from[i]) * et

        // ---- ambient (minimal) morph, OD1 lean (c) ----
        val auto = trackState == OrbState.IDLE && nowMs - trackSince >= AMBIENT_AFTER_MS && !isNight
        val wantMin = ambientOverride ?: auto
        val step = dt / 1.5f
        ambientMix = if (wantMin) min(1f, ambientMix + step) else max(0f, ambientMix - step)

        // ---- audio ----
        bus.mic.read(micBuf); bus.out.read(outBuf)
        val k = min(1f, dt / 0.04f)
        envMic += (micBuf[0] - envMic) * k
        envOut += (outBuf[0] - envOut) * k
        val micW = cur[P.MIC_W]; val outW = cur[P.OUT_W]
        val onset = outBuf[1].toInt()
        if (lastOnset >= 0 && onset != lastOnset && trackState == OrbState.SPEAKING) spawnEcho(nowMs)
        lastOnset = onset
        if (envOut > 0.08f) lastLoudMs = nowMs
        val relaxTarget = if (trackState == OrbState.SPEAKING && nowMs - lastLoudMs > 280) 0.3f else 1f
        relax += (relaxTarget - relax) * min(1f, dt * 8f)
        val outEff = envOut * relax
        env = micW * envMic + outW * outEff
        for (i in 0 until 8) bands[i] = micW * micBuf[2 + i] + outW * outBuf[2 + i] * relax

        // ---- layout ----
        layout(nowMs, sn, dt, w, h, t)

        // ---- rotations ----
        val sp = cur[P.SPEED]
        rotL0 = wrap(rotL0 + 0.5f * (if (sn.state == OrbState.PAIRING) 0.2f else 1f) * dt)
        rotL1 = wrap(rotL1 + 2f * sp * (1f + 0.15f * nz(t, 0.3f)) * dt)
        rotL3 = wrap(rotL3 - 6f * sp * (1f + 0.15f * nz(t, 1.7f)) * dt)
        rotL4 = wrap(rotL4 + (11f * sp + cur[P.L4_ADD]) * (1f + 0.15f * nz(t, 2.9f)) * dt)
        rotL6 = wrap(rotL6 - 18f * sp * (1f + 0.15f * nz(t, 4.1f)) * dt)
        rotL7 = wrap(rotL7 + 4f * sp * (1f + 0.15f * nz(t, 5.3f)) * dt)
        sweepDeg = wrap(sweepDeg + cur[P.SWEEP] * (1f + 0.15f * nz(t, 6.1f)) * dt)
        radarDeg = wrap(radarDeg + 50f * dt)
        crawlDeg = wrap(crawlDeg + 55f * dt)
        phaseRev = (phaseRev + 0.03f * dt) % 1f

        // ---- L4 arcs ----
        val breathe = 10f * sin(TWO_PI * t / 6f)
        val think = cur[P.L4_AMP_T] * sin(TWO_PI * t / 1.2f)
        arc0 = 0f; arc0Len = rad(140f + breathe + think)
        arc1 = rad(200f); arc1Len = rad(70f - breathe * 0.7f - think * 0.6f)

        // ---- glints (§3.4) ----
        if (nowMs >= nextGlintMs) {
            glintStart = nowMs
            glint3Arc = (rand() * 6f).toInt().coerceIn(0, 5)
            nextGlintMs = nowMs + 6000 + (rand() * 8000f).toLong()
        }
        val gt = (nowMs - glintStart).toFloat()
        glint3 = if (gt in 0f..500f) sin(PI.toFloat() * gt / 500f) else 0f
        glint5 = if (gt in 0f..1500f) sin(PI.toFloat() * gt / 1500f) else 0f

        // ---- overlays ----
        val pt = (nowMs - sn.pulseAtMs).toFloat()
        pulse = if (sn.pulseAtMs > 0 && pt in 0f..300f) 1f - pt / 300f else 0f
        val at = (nowMs - sn.attentionAtMs).toFloat()
        attention = if (sn.attentionAtMs > 0 && at in 0f..600f) sin(PI.toFloat() * at / 600f) else 0f
        val st = (nowMs - sn.shakeAtMs).toFloat()
        shakeX = if (sn.shakeAtMs > 0 && st in 0f..300f) 6f * scale * sin(TWO_PI * 3f * st / 300f) else 0f
        cx += shakeX; ocx += shakeX
        latchedAmt += ((if (sn.latched) 1f else 0f) - latchedAmt) * min(1f, dt * 8f)
        l4Alpha = cur[P.L4_ALPHA] * (1f + attention)

        // countdown arc on L1 (STANDBY / PAIRING)
        val cdOn = (trackState == OrbState.STANDBY || trackState == OrbState.PAIRING) &&
            sn.countdownEndMs > sn.countdownStartMs
        if (cdOn) { lastCdStart = sn.countdownStartMs; lastCdEnd = sn.countdownEndMs }
        if (lastCdEnd > lastCdStart) {
            cdFrac = ((lastCdEnd - nowMs).toFloat() / (lastCdEnd - lastCdStart)).coerceIn(0f, 1f)
        }
        cdAlpha = if (cdOn) min(1f, cdAlpha + dt / 0.4f) else max(0f, cdAlpha - dt / 1.5f)
        crawlAmt = cur[P.CRAWL]
        leaders(nowMs, sn, dt)

        // ---- tints (linear -> sRGB) ----
        var red = 0f
        if (trackState == OrbState.ERROR) red = if (el < 80f) el / 80f else max(0f, 1f - (el - 80f) / 600f)
        for (i in 0..2) {
            inner[i] = Palette.toSrgb(cur[P.TINT_INNER + i])
            l4c[i] = Palette.toSrgb(cur[P.TINT_L4 + i])
            val l3 = cur[P.TINT_L3 + i]
            l3c[i] = Palette.toSrgb(l3 + (Palette.Red[i].let { Palette.toLinear(it) } - l3) * red)
            hot[i] = if (isNight) Palette.CyanBright[i] else Palette.CyanHot[i]
        }

        // ---- layer visibility: state * boot draw-in * ambient fade ----
        gain = if (isNight) 0.35f else 1f
        val boot = ((nowMs - startMs) / BOOT_MS)
        val fadeOut = 1f - ambientMix
        for (i in 0 until Lyr.COUNT) {
            val order = BOOT_ORDER[i]
            val rev = ((boot - order * 0.065f) / 0.35f).coerceIn(0f, 1f)
            var v = cur[P.VIS + i] * rev * rev * (3f - 2f * rev)
            if (i == Lyr.GRID || i == Lyr.LABELS || i == Lyr.SEG || i == Lyr.CROWN || i == Lyr.DASH || i == Lyr.HUD) v *= fadeOut
            layer[i] = v
        }

        // ---- core ----
        val breathK = cur[P.BREATH]
        val breath = sin(TWO_PI * t / 4f)
        coreScale = (1f + 0.04f * breath * breathK) * (1f + 0.12f * env)
        coreInt = cur[P.CORE] + 0.05f * breath * breathK + cur[P.CORE_MIC] * envMic + cur[P.CORE_OUT] * outEff +
            cur[P.CORE_PULSE] * sin(TWO_PI * 1.6f * t)

        // ---- L7 displacement ----
        val maxPx = 12f * scale
        val amp = 0.046f * radius
        val gainReal = cur[P.L7_GAIN]; val syn = cur[P.L7_SYN]
        for (j in 0 until 32) {
            var f = j / 32f + phaseRev
            f -= floor(f)
            val m = if (f < 0.5f) f * 2f else (1f - f) * 2f
            val pos = m * 7f
            val i0 = min(pos.toInt(), 7); val i1 = min(i0 + 1, 7); val fr = pos - i0
            val bv = bands[i0] * (1f - fr) + bands[i1] * fr
            val real = gainReal * bv * env
            val s = 0.5f + 0.25f * sin(t * 1.3f + j * 0.9f) + 0.25f * sin(t * 0.77f - j * 1.7f)
            disp[j] = min(amp * (real + syn * s), maxPx)
        }

        // ---- particles ----
        val vel = cur[P.PVEL] + cur[P.PVEL_OUT] * outEff
        partOrbit += sp * dt
        partRadOff = (partRadOff + vel / max(radius, 1f) * dt) % 1.1f
        if (partRadOff < 0f) partRadOff += 1.1f
        partBright = cur[P.PBR] + cur[P.PBR_OUT] * outEff
        partPull = cur[P.PULL]
        var target = (if (isNight) 120 else tier.particles).toFloat()
        target += (min(target, 150f) - target) * ambientMix
        val dir = target - partCount
        val stepC = 480f * dt
        partCount = if (dir > stepC) partCount + stepC else if (dir < -stepC) partCount - stepC else target
    }

    private fun spawnEcho(nowMs: Long) {
        var slot = -1; var oldest = Long.MAX_VALUE
        for (i in 0 until 3) {
            if (echoStart[i] < 0 || nowMs - echoStart[i] > ECHO_MS) { slot = i; break }
            if (echoStart[i] < oldest) { oldest = echoStart[i]; slot = i }
        }
        echoStart[slot] = nowMs
    }

    private fun spring(i: Int, target: Float, w: Float, z: Float, h: Float) {
        val a = w * w * (target - sp6[i]) - 2f * z * w * sv6[i]
        sv6[i] += a * h
        sp6[i] += sv6[i] * h
    }

    /** Layout morph: two 2nd-order springs (review C4). Core: w=14 z=0.85, outer: z=0.70 + 40 ms delay. */
    private fun layout(nowMs: Long, sn: OrbSnapshot, dt: Float, w: Int, h: Int, t: Float) {
        val idx = sn.layout.ordinal
        if (layoutIdx < 0) {
            lToIdx = idx; outerIdx = idx; layoutIdx = idx
            for (g in 0..1) {
                sp6[g * 3] = LAY_CX[idx]; sp6[g * 3 + 1] = LAY_CY[idx]; sp6[g * 3 + 2] = LAY_D[idx]
            }
        } else if (idx != lToIdx) {
            lToIdx = idx
            val base = if (sn.layoutSinceMs > 0 && nowMs - sn.layoutSinceMs < 600) sn.layoutSinceMs else nowMs
            outerAtMs = base + OUTER_DELAY_MS; outerPending = true
        }
        if (outerPending && nowMs >= outerAtMs) { outerIdx = lToIdx; outerPending = false }
        var rem = dt
        while (rem > 1e-4f) {
            val hs = min(rem, MAX_SPRING_DT)
            spring(0, LAY_CX[lToIdx], SPRING_W, CORE_ZETA, hs)
            spring(1, LAY_CY[lToIdx], SPRING_W, CORE_ZETA, hs)
            spring(2, LAY_D[lToIdx], SPRING_W, CORE_ZETA, hs)
            spring(3, LAY_CX[outerIdx], SPRING_W, OUTER_ZETA, hs)
            spring(4, LAY_CY[outerIdx], SPRING_W, OUTER_ZETA, hs)
            spring(5, LAY_D[outerIdx], SPRING_W, OUTER_ZETA, hs)
            rem -= hs
        }
        scale = w / DESIGN_W
        offY = (h - DESIGN_H * scale) * 0.5f
        val drift = 6f * scale
        val dx = drift * sin(TWO_PI * t / 660f)
        val dy = drift * sin(TWO_PI * t / 780f + 1.3f)
        cx = sp6[0] * scale + dx
        cy = offY + sp6[1] * scale + dy
        radius = max(sp6[2], 1f) * 0.5f * scale
        ocx = sp6[3] * scale + dx
        ocy = offY + sp6[4] * scale + dy
        oRadius = max(sp6[5], 1f) * 0.5f * scale
    }

    /** Leader lines (review H4/C9/C15). Reads [OrbSnapshot.leaders]; call after layout + shake. */
    private fun leaders(nowMs: Long, sn: OrbSnapshot, dt: Float) {
        val src = sn.leaders
        val n = if (sn.layout == OrbLayout.DOCKED) min(src.size, 3) else 0
        // docked-arrival gate: wait until the outer group has mostly arrived (the C9 timeline already starts at 250 ms)
        val arrive = (1f - abs(sp6[3] - LAY_CX[OrbLayout.DOCKED.ordinal]) / 60f).coerceIn(0f, 1f)
        if (n == 0) { leaderN = 0; ldPrevN = 0; return }
        // carry y/velocity across slot shifts (panels slide down when a new one arrives on top)
        for (i in 0 until n) {
            val a = src[i]
            var j = -1
            for (k in 0 until ldPrevN) if (ldRs[k] == a.revealStartMs) { j = k; break }
            if (j >= 0) { ldNewY[i] = ldY[j]; ldNewV[i] = ldV[j] } else { ldNewY[i] = a.yPx; ldNewV[i] = 0f }
        }
        var rem = dt
        while (rem > 1e-4f) {
            val hs = min(rem, MAX_SPRING_DT)
            for (i in 0 until n) { // critically damped, ~350 ms settle
                val acc = LEAD_W * LEAD_W * (src[i].yPx - ldNewY[i]) - 2f * LEAD_W * ldNewV[i]
                ldNewV[i] += acc * hs; ldNewY[i] += ldNewV[i] * hs
            }
            rem -= hs
        }
        for (i in 0 until n) { ldY[i] = ldNewY[i]; ldV[i] = ldNewV[i]; ldRs[i] = src[i].revealStartMs }
        ldPrevN = n

        val s = scale
        val tx = LEADER_X * s
        var cnt = 0
        for (i in 0 until n) {
            val a = src[i]
            val tt = (nowMs - a.revealStartMs).toFloat()
            if (a.revealStartMs <= 0L || tt < 250f) continue
            val ty = offY + ldY[i] * s
            val vx = tx - ocx; val vy = ty - ocy
            val dist = sqrt(vx * vx + vy * vy)
            val full = dist - oRadius
            if (dist < 1f || full < 2f) continue
            val ux = vx / dist; val uy = vy / dist
            val frac: Float; val alpha: Float; val head: Float
            if (tt < 430f) {
                val u = (tt - 250f) / 180f
                frac = if (u >= 1f) 1f else 1f - exp(-10f * u * 0.6931472f) // easeOutExpo: 1 - 2^(-10u)
                alpha = 1f; head = 1f
            } else {
                frac = 1f
                val u = ((tt - 430f) / 2000f).coerceIn(0f, 1f)
                val inv = 1f - u
                alpha = 0.25f + 0.75f * inv * inv * inv
                head = (1f - (tt - 430f) / 100f).coerceIn(0f, 1f)
            }
            ldX0[cnt] = ocx + ux * oRadius; ldY0[cnt] = ocy + uy * oRadius
            ldDx[cnt] = ux; ldDy[cnt] = uy
            ldLen[cnt] = full * frac
            ldAlpha[cnt] = alpha * arrive; ldHead[cnt] = head * arrive
            val pt = (nowMs - a.pulseAtMs).toFloat()
            if (a.pulseAtMs > 0L && pt >= 0f && pt <= 350f) {
                val p = pt / 350f
                ldPulseS[cnt] = min(full * p, full * frac)
                ldPulseAmt[cnt] = min(1f, min(p, 1f - p) * 8f) * arrive
            } else { ldPulseS[cnt] = 0f; ldPulseAmt[cnt] = 0f }
            cnt++
        }
        leaderN = cnt
    }

    private fun wrap(d: Float): Float = if (d >= 360f) d - 360f else if (d < 0f) d + 360f else d
    private fun rad(deg: Float) = deg * (PI.toFloat() / 180f)

    companion object {
        const val TWO_PI = 6.2831855f
        const val DESIGN_W = 1340f
        const val DESIGN_H = 800f
        const val AMBIENT_AFTER_MS = 120_000L
        const val BOOT_MS = 1400f
        const val ECHO_MS = 700L
        const val SPRING_W = 14f
        const val CORE_ZETA = 0.85f
        const val OUTER_ZETA = 0.70f
        const val OUTER_DELAY_MS = 40L
        const val MAX_SPRING_DT = 1f / 30f
        const val LEAD_W = 13f
        const val LEADER_X = 625f
        // CENTERED, DOCKED, PAIRING, FIRST_RUN (OrbLayout ordinal order)
        val LAY_CX = floatArrayOf(670f, 305f, 670f, 670f)
        val LAY_CY = floatArrayOf(392f, 392f, 200f, 226f)
        val LAY_D = floatArrayOf(520f, 440f, 260f, 300f)
        // draw-in order index per layer: core first, then outward
        val BOOT_ORDER = floatArrayOf(8f, 7f, 6f, 5f, 4f, 3f, 2f, 1f, 0f, 1f, 9f)
    }
}
