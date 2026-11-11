package dev.timlock.jarvis.render.orb

import kotlin.math.pow

/** sim.violet (#B48CFF), DESIGN §7.6.3. The single definition inside this module (mirrors SimStyling in :ui:hud). */
internal const val SimColor: Int = 0xB48CFF

/** §2.1 tokens as sRGB float triples. Shader math is done on gamma values; tints are mixed in linear light. */
internal object Palette {
    fun rgb(hex: Int): FloatArray = floatArrayOf(
        ((hex shr 16) and 255) / 255f, ((hex shr 8) and 255) / 255f, (hex and 255) / 255f,
    )

    val Void = rgb(0x02070A)
    val Deep = rgb(0x04141A)
    val GridFaint = rgb(0x0B2A33)
    val CyanDim = rgb(0x0E5A66)
    val CyanMid = rgb(0x1FB6C9)
    val CyanBright = rgb(0x5CE8F5)
    val CyanHot = rgb(0xC8FBFF)
    val TealDeep = rgb(0x0A7F86)
    val TextMuted = rgb(0x4A7C84)
    val Listen = rgb(0x7DF9C9)
    val Think = rgb(0x4FA8FF)
    val Amber = rgb(0xFFB547)
    val Red = rgb(0xFF5A6A)
    val Violet = rgb(SimColor)

    fun toLinear(x: Float): Float = x.pow(2.2f)
    fun toSrgb(x: Float): Float = x.pow(1f / 2.2f)
}
