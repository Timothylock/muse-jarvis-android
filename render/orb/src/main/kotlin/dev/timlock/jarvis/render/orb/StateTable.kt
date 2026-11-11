package dev.timlock.jarvis.render.orb

import dev.timlock.jarvis.core.model.OrbState
import kotlin.math.pow

/** Layer indices for visibility/alpha arrays. */
internal object Lyr {
    const val GRID = 0; const val BEZEL = 1; const val LABELS = 2; const val SEG = 3; const val DATA = 4
    const val CROWN = 5; const val DASH = 6; const val REACT = 7; const val CORE = 8; const val PART = 9; const val HUD = 10
    const val COUNT = 11
}

/** Indices into a per-state parameter row (DESIGN §3.1/§3.2). Tint entries are linear-light RGB. */
internal object P {
    const val SPEED = 0
    const val CORE = 1
    const val CORE_MIC = 2
    const val CORE_OUT = 3
    const val CORE_PULSE = 4
    const val PVEL = 5
    const val PVEL_OUT = 6
    const val PBR = 7
    const val PBR_OUT = 8
    const val L7_GAIN = 9
    const val L7_SYN = 10
    const val SWEEP = 11
    const val MIC_W = 12
    const val OUT_W = 13
    const val L4_ADD = 14
    const val L4_AMP_T = 15
    const val L4_ALPHA = 16
    const val L7_R = 17
    const val PULL = 18
    const val BREATH = 19
    const val RADAR = 20
    const val CRAWL = 21
    const val VIS = 22 // 11 entries
    const val TINT_START = VIS + Lyr.COUNT // 33
    const val TINT_INNER = TINT_START
    const val TINT_L4 = TINT_START + 3
    const val TINT_L3 = TINT_START + 6
    const val N = TINT_START + 9
}

internal object StateTable {
    private fun lin(hex: Int, out: FloatArray, at: Int) {
        val c = Palette.rgb(hex)
        for (i in 0..2) out[at + i] = c[i].pow(2.2f)
    }


    private fun row(parent: FloatArray?, edit: (FloatArray) -> Unit): FloatArray {
        val r = parent?.copyOf() ?: FloatArray(P.N)
        edit(r)
        return r
    }

    private const val CYAN_BRIGHT = 0x5CE8F5
    private const val CYAN_MID = 0x1FB6C9
    private const val MINT = 0x7DF9C9
    private const val BLUE = 0x4FA8FF
    private const val AMBER = 0xFFB547

    private val idle: FloatArray = row(null) { r ->
        r[P.SPEED] = 1f; r[P.CORE] = 0.55f; r[P.PBR] = 0.5f; r[P.L7_SYN] = 0.05f; r[P.SWEEP] = 90f
        r[P.L4_ALPHA] = 0.9f; r[P.L7_R] = 0.46f; r[P.PULL] = 1f; r[P.BREATH] = 1f
        for (i in 0 until Lyr.COUNT) r[P.VIS + i] = 1f
        lin(CYAN_BRIGHT, r, P.TINT_INNER); lin(CYAN_BRIGHT, r, P.TINT_L4); lin(CYAN_MID, r, P.TINT_L3)
    }

    val rows: Array<FloatArray> = Array(OrbState.values().size) { idle }.also { a ->
        a[OrbState.IDLE.ordinal] = idle
        a[OrbState.BOOT.ordinal] = row(idle) { }
        a[OrbState.CONNECTING.ordinal] = row(idle) { r ->
            r[P.SPEED] = 2f; r[P.CORE] = 0.7f; r[P.PVEL] = -20f; r[P.PBR] = 0.7f; r[P.L7_SYN] = 0f
            r[P.SWEEP] = 180f; r[P.L7_R] = 0.42f
            lin(MINT, r, P.TINT_INNER)
        }
        a[OrbState.LISTENING.ordinal] = row(idle) { r ->
            r[P.SPEED] = 1.3f; r[P.CORE] = 0.6f; r[P.CORE_MIC] = 0.4f; r[P.PVEL] = -8f; r[P.PBR] = 0.7f
            r[P.L7_GAIN] = 1f; r[P.L7_SYN] = 0f; r[P.SWEEP] = 120f; r[P.MIC_W] = 1f
            lin(MINT, r, P.TINT_INNER)
        }
        a[OrbState.THINKING.ordinal] = row(idle) { r ->
            r[P.SPEED] = 2.5f; r[P.CORE] = 0.7f; r[P.CORE_PULSE] = 0.15f; r[P.PBR] = 0.8f
            r[P.L7_SYN] = 0.15f; r[P.SWEEP] = 360f; r[P.L4_ADD] = 32f; r[P.L4_AMP_T] = 28f
            r[P.PULL] = 0.72f; r[P.BREATH] = 0f
            lin(BLUE, r, P.TINT_INNER)
        }
        a[OrbState.SPEAKING.ordinal] = row(idle) { r ->
            r[P.SPEED] = 1.6f; r[P.CORE] = 0.6f; r[P.CORE_OUT] = 0.6f; r[P.PVEL] = 6f; r[P.PVEL_OUT] = 30f
            r[P.PBR] = 0.6f; r[P.PBR_OUT] = 0.4f; r[P.L7_GAIN] = 1f; r[P.L7_SYN] = 0f
            r[P.SWEEP] = 120f; r[P.OUT_W] = 1f
        }
        a[OrbState.STANDBY.ordinal] = row(idle) { r ->
            r[P.SPEED] = 1.1f; r[P.CORE] = 0.6f; r[P.PBR] = 0.55f; r[P.L4_ALPHA] = 1.0f
        }
        a[OrbState.OFFLINE.ordinal] = row(idle) { r ->
            r[P.SPEED] = 0.4f; r[P.CORE] = 0.3f; r[P.PBR] = 0.3f; r[P.SWEEP] = 30f; r[P.CRAWL] = 1f
            lin(AMBER, r, P.TINT_L4)
        }
        a[OrbState.ERROR.ordinal] = row(idle) { r ->
            r[P.CORE] = 0.5f; r[P.PBR] = 0.4f
            lin(AMBER, r, P.TINT_INNER); lin(AMBER, r, P.TINT_L4)
        }
        a[OrbState.UNPAIRED.ordinal] = row(idle) { r ->
            r[P.SPEED] = 0.5f; r[P.CORE] = 0.35f; r[P.PBR] = 0.3f; r[P.SWEEP] = 0f; r[P.L7_SYN] = 0f
            r[P.VIS + Lyr.GRID] = 0.5f
            r[P.VIS + Lyr.DATA] = 0f; r[P.VIS + Lyr.CROWN] = 0f; r[P.VIS + Lyr.DASH] = 0f; r[P.VIS + Lyr.REACT] = 0f
            r[P.VIS + Lyr.PART] = 0.3f
            lin(AMBER, r, P.TINT_INNER)
        }
        a[OrbState.PAIRING.ordinal] = row(idle) { r ->
            r[P.CORE] = 0.5f; r[P.RADAR] = 1f
        }
    }

    // ---- transitions (§3.1) ----
    const val LINEAR = 0
    const val OUT_QUAD = 1
    const val IN_OUT = 2
    const val OUT_CUBIC = 3

    fun durationMs(prev: OrbState, to: OrbState): Float = when {
        to == OrbState.ERROR -> 80f
        prev == OrbState.ERROR -> 600f
        to == OrbState.CONNECTING -> 300f
        prev == OrbState.CONNECTING && to == OrbState.LISTENING -> 200f
        prev == OrbState.LISTENING && to == OrbState.THINKING -> 400f
        prev == OrbState.THINKING && to == OrbState.SPEAKING -> 250f
        prev == OrbState.SPEAKING && to == OrbState.STANDBY -> 800f
        prev == OrbState.STANDBY && to == OrbState.IDLE -> 1500f
        else -> 400f
    }

    fun easeId(prev: OrbState, to: OrbState): Int = when {
        to == OrbState.ERROR -> LINEAR
        prev == OrbState.ERROR -> OUT_QUAD
        to == OrbState.CONNECTING -> OUT_QUAD
        prev == OrbState.CONNECTING && to == OrbState.LISTENING -> LINEAR
        prev == OrbState.THINKING && to == OrbState.SPEAKING -> OUT_CUBIC
        else -> IN_OUT
    }

    fun ease(id: Int, x: Float): Float = when (id) {
        LINEAR -> x
        OUT_QUAD -> 1f - (1f - x) * (1f - x)
        OUT_CUBIC -> 1f - (1f - x) * (1f - x) * (1f - x)
        else -> x * x * (3f - 2f * x)
    }

    fun easeInOutCubic(x: Float): Float =
        if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
}
