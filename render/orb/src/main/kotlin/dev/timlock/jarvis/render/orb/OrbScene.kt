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
import kotlin.math.cos

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
    private val dispRaw = FloatArray(32)
    private var sleepBreath = 0f
    var glint3 = 0f; var glint3Arc = 0; var glint5 = 0f
    var pulse = 0f; var attention = 0f
    var shakeX = 0f
    val echoStart = LongArray(3) { -1L }
    var latchedAmt = 0f
    var cdAlpha = 0f; var cdFrac = 0f
    var crawlAmt = 0f
    var ambientMix = 0f
    var env = 0f
    /** H16: 0 centered .. 1 docked (follows the outer spring); grid radius fades 1.6 R -> 1.35 R. */
    var dockMix = 0f
    /** C12: Lissajous drift offset (px) applied to the orb centre, used for particle parallax. */
    var driftX = 0f; var driftY = 0f
    /** C14: 0 day .. 1 night ember. */
    var nightMix = 0f
    private var nightInit = false
    // C7 micro-events (ambient "systems check"), outputs
    var microTickHead = -1f      // L1 tick sweep head (tick index units), < 0 = off
    var microArcM = 0f           // L4 arc swap 0..1
    var microBlink = 1f          // core intensity factor
    val cometIdx = FloatArray(4) { -1f }   // streamer vertex ids, set by the renderer
    val cometOff = FloatArray(4)           // accumulated extra orbit (time units) per slot
    var cometAct = -1f; var cometBoost = 0f
    private var microKind = 0; private var microAt = 0L; private var microTick0 = 0f
    private var nextMicroMs = 0L; private var lastMicroEndMs = 0L; private var nextNightBlinkMs = 0L
    private var pendingMicro = 0
    // particles
    var partOrbit = 0f; var partRadOff = 0f; var partCount = 120f; var partBright = 0f; var partPull = 1f
    var timeSec = 0f
    // M5: echo strength, speech bursts (C3), per-ring halo scalar (H7), wake/sleep reveal (C2), thinking sweeps (C11)
    val echoStr = FloatArray(3)
    val burstT = FloatArray(6) { -100f }
    val burstS = FloatArray(6)
    private var burstNext = 0
    val haloMix = FloatArray(8) { 1f }
    var haloSettling = false
    private var haloInit = false
    /** Per-layer reveal 0..1 (1 = steady state), head amount, see [Lyr]. */
    val rev = FloatArray(Lyr.COUNT) { 1f }
    val revHead = FloatArray(Lyr.COUNT)
    var thinkMix = 0f; var sweep2Deg = 0f
    var standbyMix = 0f; var l7Pulse = 0f
    private var ambMode = 0 // 0 classic, 1 sleeping, 2 ambient, 3 waking
    private var ambAt = 0L
    private var ambInit = false
    private var lastWant = false
    private var cockPrev = 0f

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
            // H11: barge-in, one last echo ring spawned at the cut so the interruption is seen
            if (trackState == OrbState.SPEAKING && sn.state == OrbState.LISTENING) spawnEcho(nowMs, 1f)
            cockPrev = 0f
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
        ambient(nowMs, wantMin)
        if (!nightInit) { nightInit = true; nightMix = if (isNight) 1f else 0f }
        else nightMix += ((if (isNight) 1f else 0f) - nightMix) * min(1f, dt * 2.5f)
        micro(nowMs, dt, ambMode == 2, isNight)

        // ---- audio ----
        bus.mic.read(micBuf); bus.out.read(outBuf)
        val k = min(1f, dt / 0.04f)
        envMic += (micBuf[0] - envMic) * k
        envOut += (outBuf[0] - envOut) * k
        val micW = cur[P.MIC_W]; val outW = cur[P.OUT_W]
        val onset = outBuf[1].toInt()
        if (lastOnset >= 0 && onset != lastOnset && trackState == OrbState.SPEAKING) {
            val str = outBuf[2 + AudioLevels.BANDS].coerceIn(0f, 1f)
            spawnEcho(nowMs, str)
            spawnBurst(t, str)
        }
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
        // C6: on entering LISTENING, L3 cocks back 15 deg over 300 ms (ease-out-cubic) and then resumes turning
        val stT = max(0f, (nowMs - trackSince) / 1000f)
        if (trackState == OrbState.LISTENING) {
            val x = 1f - min(1f, stT / 0.3f)
            val cock = 15f * (1f - x * x * x)
            rotL3 = wrap(rotL3 + (cock - cockPrev)); cockPrev = cock
        }
        // C11: second (counter) sweep in THINKING
        thinkMix += ((if (trackState == OrbState.THINKING) 1f else 0f) - thinkMix) * min(1f, dt * 3f)
        sweep2Deg = wrap(sweep2Deg - 240f * dt)
        // H6: STANDBY awake pulse on L7 (0.5 Hz ease-in-out-sine)
        standbyMix += ((if (trackState == OrbState.STANDBY) 1f else 0f) - standbyMix) * min(1f, dt * 3f)
        l7Pulse = standbyMix * (0.5f - 0.5f * cos(TWO_PI * 0.5f * t))
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
        val am = 40f * microArcM // C7(b): 140 -> 100 deg while 70 -> 110 deg
        arc0 = 0f; arc0Len = rad(140f + breathe + think - am)
        arc1 = rad(200f); arc1Len = rad(70f - breathe * 0.7f - think * 0.6f + am)

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
        pulse = if (sn.pulseAtMs > 0 && pt in 0f..300f) exp(-pt / 90f) else 0f // H14
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

        // ---- layer visibility: state * boot draw-in; ambient wake/sleep handled by [rev] (C2) ----
        gain = if (isNight) 0.35f else 1f
        val boot = ((nowMs - startMs) / BOOT_MS)
        for (i in 0 until Lyr.COUNT) {
            val order = BOOT_ORDER[i]
            val rv = ((boot - order * 0.065f) / 0.35f).coerceIn(0f, 1f)
            layer[i] = cur[P.VIS + i] * rv * rv * (3f - 2f * rv) * (1f + (StateTable.nightVis[i] - 1f) * nightMix)
        }
        revealTimeline(nowMs)

        // ---- H7: per-ring halo scalar eases at 1/s toward the tier's target ----
        haloSettling = false
        for (r in 1..7) {
            val tg = if (tier.halo(r)) 1f else 0f
            if (!haloInit) haloMix[r] = tg
            else {
                val d = tg - haloMix[r]
                haloMix[r] = if (d > dt) haloMix[r] + dt else if (d < -dt) haloMix[r] - dt else tg
                if (haloMix[r] != tg) haloSettling = true
            }
        }
        haloInit = true

        // ---- core ----
        val breathK = cur[P.BREATH]
        val breath = sin(TWO_PI * t / 4f)
        coreScale = (1f + 0.04f * breath * breathK) * (1f + 0.12f * env) * (1f + sleepBreath)
        coreInt = cur[P.CORE] + 0.05f * breath * breathK + cur[P.CORE_MIC] * envMic + cur[P.CORE_OUT] * outEff +
            cur[P.CORE_PULSE] * sin(TWO_PI * 1.6f * t)
        if (nightMix > 0.001f) { // C14 ember: 12 s breathing cycle, L1 breathes with it
            val br = sin(TWO_PI * t / StateTable.NIGHT_BREATH_S)
            coreInt += (0.75f + 0.15f * br - coreInt) * nightMix
            coreScale += (1f + 0.05f * br - coreScale) * nightMix
            layer[Lyr.BEZEL] *= 1f + nightMix * (0.15f * br - 0.1f)
        }
        coreInt *= microBlink

        // ---- L7 displacement (C5): bipolar +-18 px (scaled) around 0.46 R from (band - 0.5)*2*env, 3-tap smoothed ----
        val maxPx = 0.0692f * radius
        val gainReal = cur[P.L7_GAIN]; val syn = cur[P.L7_SYN]
        for (j in 0 until 32) {
            var f = j / 32f + phaseRev
            f -= floor(f)
            val m = if (f < 0.5f) f * 2f else (1f - f) * 2f
            val pos = m * 7f
            val i0 = min(pos.toInt(), 7); val i1 = min(i0 + 1, 7); val fr = pos - i0
            val bv = bands[i0] * (1f - fr) + bands[i1] * fr
            val real = gainReal * (bv - 0.5f) * 2f * env
            val sv = 0.5f + 0.25f * sin(t * 1.3f + j * 0.9f) + 0.25f * sin(t * 0.77f - j * 1.7f)
            dispRaw[j] = ((real + syn * (sv - 0.5f) * 2f * 0.25f) * maxPx).coerceIn(-maxPx, maxPx)
        }
        for (j in 0 until 32) disp[j] = 0.25f * dispRaw[(j + 31) and 31] + 0.5f * dispRaw[j] + 0.25f * dispRaw[(j + 1) and 31]

        // ---- particles ----
        // C6 gather: first 600 ms of LISTENING, radial -40 px/s (settles to -8), tangential x1.6, ease-out
        var gw = 0f
        if (trackState == OrbState.LISTENING && stT < 0.6f) { val x = 1f - stT / 0.6f; gw = x * x }
        val vel = cur[P.PVEL] + cur[P.PVEL_OUT] * outEff - 32f * gw
        partOrbit += sp * (1f + 0.6f * gw) * (1f - 0.6f * ambientMix) * dt // H10
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

    /** Debug hook: start a micro-event now (1..4, or -1 for a random one). GL thread only. */
    fun triggerMicro(kind: Int) { pendingMicro = kind }

    private fun startMicro(nowMs: Long, kind: Int) {
        microKind = if (kind in 1..4) kind else 1 + (rand() * 4f).toInt().coerceIn(0, 3)
        microAt = nowMs
        microTick0 = floor(rand() * 120f)
        if (microKind == 3) cometAct = (rand() * 4f).toInt().coerceIn(0, 3).toFloat()
    }

    /** C7 / C14. Everything is a uniform on existing draws; durations: a 1.7 s, b 4 s, c 2 s, d 0.72 s. */
    private fun micro(nowMs: Long, dt: Float, ambientNow: Boolean, isNight: Boolean) {
        if (pendingMicro != 0) { startMicro(nowMs, pendingMicro); pendingMicro = 0 }
        else if (microKind == 0) {
            if (isNight) {
                if (nextNightBlinkMs == 0L) nextNightBlinkMs = nowMs + StateTable.NIGHT_BLINK_MIN_MS + (rand() * StateTable.NIGHT_BLINK_SPAN_MS).toLong()
                if (nowMs >= nextNightBlinkMs) {
                    startMicro(nowMs, 4)
                    nextNightBlinkMs = nowMs + StateTable.NIGHT_BLINK_MIN_MS + (rand() * StateTable.NIGHT_BLINK_SPAN_MS).toLong()
                }
            } else if (ambientNow) {
                if (nextMicroMs == 0L) nextMicroMs = nowMs + 45_000L + (rand() * 75_000f).toLong()
                if (nowMs >= nextMicroMs && nowMs - lastMicroEndMs >= 30_000L) {
                    startMicro(nowMs, -1)
                    nextMicroMs = nowMs + 45_000L + (rand() * 75_000f).toLong()
                }
            } else nextMicroMs = 0L
            if (!isNight) nextNightBlinkMs = 0L
        }
        microTickHead = -1f; microArcM = 0f; microBlink = 1f; cometBoost = 0f
        if (microKind == 0) { cometAct = -1f; return }
        val tm = (nowMs - microAt) / 1000f
        when (microKind) {
            1 -> {
                if (tm >= 1.7f) endMicro(nowMs) else microTickHead = microTick0 + 18f * (tm / 1.7f)
            }
            2 -> {
                if (tm >= 4f) endMicro(nowMs) else {
                    val x = if (tm < 2f) tm / 2f else (4f - tm) / 2f
                    microArcM = 0.5f - 0.5f * cos(PI.toFloat() * x) // ease-in-out-sine
                }
            }
            3 -> {
                if (tm >= 2f) { endMicro(nowMs); cometAct = -1f } else {
                    val b = sin(PI.toFloat() * tm / 2f)
                    cometBoost = b
                    val k = cometAct.toInt()
                    if (k in 0..3) cometOff[k] += 3f * b * dt
                }
            }
            else -> {
                if (tm >= 0.72f) endMicro(nowMs) else if (tm < 0.12f) microBlink = 1f - 0.55f * (tm / 0.12f)
                else { val x = (tm - 0.12f) / 0.6f; microBlink = 0.45f + 0.55f * (1f - (1f - x) * (1f - x)) }
            }
        }
    }

    private fun endMicro(nowMs: Long) { microKind = 0; lastMicroEndMs = nowMs }

    private fun spawnEcho(nowMs: Long, strength: Float) {
        var slot = -1; var oldest = Long.MAX_VALUE
        for (i in 0 until 3) {
            if (echoStart[i] < 0 || nowMs - echoStart[i] > ECHO_MS) { slot = i; break }
            if (echoStart[i] < oldest) { oldest = echoStart[i]; slot = i }
        }
        echoStart[slot] = nowMs
        echoStr[slot] = strength
    }

    /** C3: round-robin one of 6 burst slots (16 motes each, all motion in the vertex shader). */
    private fun spawnBurst(tSec: Float, strength: Float) {
        burstT[burstNext] = tSec; burstS[burstNext] = max(0.35f, strength)
        burstNext = (burstNext + 1) % 6
    }

    private fun q4(x: Float): Float { val c = x.coerceIn(0f, 1f); val i = 1f - c; return 1f - i * i * i * i }
    private fun c01(x: Float) = x.coerceIn(0f, 1f)

    /** Ambient state machine (C2). 0 classic, 1 sleeping (2.5 s outside-in), 2 minimal ambient, 3 waking (650 ms draw-in). */
    private fun ambient(nowMs: Long, want: Boolean) {
        if (!ambInit) { ambInit = true; lastWant = want; ambMode = if (want) 2 else 0 }
        else if (want != lastWant) {
            lastWant = want; ambAt = nowMs
            ambMode = if (want) 1 else if (ambMode == 0) 0 else 3
        }
        val tm = (nowMs - ambAt).toFloat()
        if (ambMode == 1 && tm >= SLEEP_MS) ambMode = 2
        if (ambMode == 3 && tm >= WAKE_MS) ambMode = 0
        ambientMix = when (ambMode) { 1 -> tm / SLEEP_MS; 2 -> 1f; 3 -> 1f - tm / WAKE_MS; else -> 0f }.coerceIn(0f, 1f)
    }

    private var labelsA = 1f; private var hudA = 1f
    val sleeping: Boolean get() = ambMode == 1

    /** Fills [rev]/[revHead] and gates the ambient-hidden layers. Wake: L7 0, L6 60, L5 120, L3 180, L1 ticks 240, grid+labels 300 ms. */
    private fun revealTimeline(nowMs: Long) {
        for (i in 0 until Lyr.COUNT) { rev[i] = 1f; revHead[i] = 0f }
        labelsA = 1f; hudA = 1f; sleepBreath = 0f
        val tm = (nowMs - ambAt).toFloat()
        when (ambMode) {
            2 -> {
                rev[Lyr.SEG] = 0f; rev[Lyr.CROWN] = 0f; rev[Lyr.DASH] = 0f; rev[Lyr.GRID] = 0f
                labelsA = 0f; hudA = 0f
            }
            3 -> {
                rev[Lyr.REACT] = q4((tm - 0f) / 250f)
                rev[Lyr.DASH] = q4((tm - 60f) / 250f)
                rev[Lyr.CROWN] = q4((tm - 120f) / 250f)
                rev[Lyr.SEG] = q4((tm - 180f) / 250f)
                rev[Lyr.BEZEL] = q4((tm - 240f) / 400f)
                rev[Lyr.GRID] = q4((tm - 300f) / 350f)
                labelsA = c01((tm - 300f) / 250f); hudA = labelsA
                for (i in WAKE_LAYERS) {
                    val r = rev[i]
                    revHead[i] = if (r >= 1f || r <= 0f) 0f else 1f - smooth(0.85f, 1f, r)
                    if (r <= 0f) revHead[i] = 0f
                }
                // L1 ticks and L7 are visible in ambient: only a head pass, no mask
            }
            1 -> {
                // outside-in: grid contracts, crown ticks drop out in random order, L3 then L6 arcs shorten from their tails
                rev[Lyr.GRID] = 1f - smoothIO(tm / 700f)
                labelsA = 1f - c01(tm / 400f); hudA = labelsA
                rev[Lyr.CROWN] = 1f - c01((tm - 300f) / 900f)
                rev[Lyr.SEG] = 1f - smoothIO((tm - 900f) / 800f)
                rev[Lyr.DASH] = 1f - smoothIO((tm - 1300f) / 800f)
                val x = c01((tm - 1900f) / 600f)
                sleepBreath = 0.06f * sin(PI.toFloat() * x)
            }
        }
        // hidden layers draw nothing
        if (rev[Lyr.GRID] < 0.01f) layer[Lyr.GRID] = 0f
        if (rev[Lyr.SEG] <= 0.001f) layer[Lyr.SEG] = 0f
        if (rev[Lyr.CROWN] <= 0.001f) layer[Lyr.CROWN] = 0f
        if (rev[Lyr.DASH] <= 0.001f) layer[Lyr.DASH] = 0f
        layer[Lyr.LABELS] *= labelsA
        layer[Lyr.HUD] *= hudA
    }

    private fun smooth(a: Float, b: Float, x: Float): Float { val t = c01((x - a) / (b - a)); return t * t * (3f - 2f * t) }
    private fun smoothIO(x: Float): Float { val t = c01(x); return t * t * (3f - 2f * t) }

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
        driftX = dx; driftY = dy
        dockMix = ((LAY_CX[0] - sp6[3]) / (LAY_CX[0] - LAY_CX[1])).coerceIn(0f, 1f)
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
        private val WAKE_LAYERS = intArrayOf(Lyr.REACT, Lyr.DASH, Lyr.CROWN, Lyr.SEG, Lyr.BEZEL)
        const val SLEEP_MS = 2500f
        const val WAKE_MS = 700f
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
