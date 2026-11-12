package dev.timlock.jarvis.core.model

/**
 * Display-protocol panel model (DESIGN §2.5, §5). Produced by the pure panel manager in :core:store,
 * rendered by :ui:hud. All strings are already normalized (§5.3): trimmed, markdown stripped,
 * truncated with an ellipsis where needed. All times are [monoMillis].
 */
enum class PanelType { CARD, STATS, LIST, TIMER }

/**
 * Where a panel renders (overseer ruling on review H1). DOCK = the 665 px column beside the docked orb.
 * CHIP = compact 260x96 card top-right while the orb stays CENTERED (sticky/pinned panels and timers
 * while no call is open). Only DOCK panels make the layout DOCKED and get leader lines.
 */
enum class PanelPlacement { DOCK, CHIP }

data class Metric(
    val label: String,
    /** Display-ready value (numbers formatted, max 2 decimals). */
    val value: String,
    val unit: String? = null,
    /** True for %, °, °C, °F: rendered attached to the value; otherwise with a thin space (§5.3). */
    val unitAttached: Boolean = false,
)

data class ListItem(
    val text: String,
    /** 0..2 indentation level. */
    val level: Int = 0,
    val done: Boolean = false,
)

sealed interface PanelContent {
    data class Card(
        val body: String? = null,
        /** At most 6 shown; [moreBullets] = count hidden behind "+N more". */
        val bullets: List<String> = emptyList(),
        val moreBullets: Int = 0,
    ) : PanelContent

    data class Stats(
        /** 1..6 metrics; laid out 3 per row, max 2 rows. */
        val metrics: List<Metric>,
        val caption: String? = null,
    ) : PanelContent

    data class ListBody(
        val items: List<ListItem>,
        val ordered: Boolean = false,
    ) : PanelContent

    data class Timer(
        val totalSeconds: Int,
        /** Countdown reaches zero at this monotonic time. */
        val endsAtMs: Long,
        /** Store bookkeeping: the zero-chime has already been requested. */
        val chimed: Boolean = false,
    ) : PanelContent
}

data class Panel(
    /** ≤ 40 chars [A-Za-z0-9._-]; auto-generated ("card-7f3a") when Muse omits it. */
    val id: String,
    val type: PanelType,
    /** Title (timer: label). */
    val title: String,
    val content: PanelContent,
    /** Muse-sticky or user-pinned. */
    val sticky: Boolean = false,
    /** User long-press pin (shows the pin glyph). Implies [sticky]. */
    val pinned: Boolean = false,
    /** First arrival (drives the 450 ms reveal + leader line draw-in). */
    val createdAtMs: Long,
    /** Last in-place update by id (drives the 250 ms content crossfade). == createdAtMs if never updated. */
    val updatedAtMs: Long,
    /** Increments on every in-place update (use as a Compose key for the crossfade). */
    val version: Int = 0,
    /** For LIST updates: indices of rows that are new vs. the previous version (600 ms highlight). */
    val newRows: Set<Int> = emptySet(),
    /** Expiry deadline, or null (sticky, or a call is active, or timer not yet finished). */
    val expiresAtMs: Long? = null,
    /** Collapsed to a 56 px title strip because the stack overflowed 656 px (§2.5). */
    val collapsed: Boolean = false,
    /** Created while mode == SIM (violet panel marker, §7.6 rule 4). */
    val sim: Boolean = false,
    val placement: PanelPlacement = PanelPlacement.DOCK,
    /**
     * Origin of the arrival timeline (review C9), monotonic ms. First docked panel: == arrival (orb slides
     * 0-500, leader 250-430, brackets pop 380-500, scan 450-670). Panels arriving while already docked, and
     * chips: arrival - 380 (they start at the bracket stage). Re-set on DOCK promotion of a chip.
     */
    val revealStartMs: Long = createdAtMs,
    /** Local panel showing a fallback-path text reply (§4.3.1 step 4); not a Muse display command. */
    val textReply: Boolean = false,
)

/** Panel stack as rendered, newest first (index 0 = top). Max 3. */
data class PanelStack(
    val panels: List<Panel> = emptyList(),
    /** Monotonic ms of the last "Panel replaced" toast (all-sticky eviction, §2.5 rule 3); 0 = never. */
    val replacedToastAtMs: Long = 0L,
)
