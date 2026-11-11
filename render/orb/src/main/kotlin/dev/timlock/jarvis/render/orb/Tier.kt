package dev.timlock.jarvis.render.orb

/** DESIGN §3.6 quality tiers. */
enum class Tier(val particles: Int, val fps: Int, val sweep: Boolean) {
    A(600, 60, true),
    B(300, 60, true),
    C(120, 30, false),
    D(120, 20, false);

    /** Whether ring [ringId] (1,3,4,5,6,7 = layer number) gets its glow halo at this tier. */
    internal fun halo(ringId: Int): Boolean = when (this) {
        A -> true
        B -> ringId == 3 || ringId == 4 || ringId == 7
        C -> ringId == 7
        D -> false
    }
}
