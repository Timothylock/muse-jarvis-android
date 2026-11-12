package dev.timlock.jarvis.core.store

import dev.timlock.jarvis.core.model.Panel
import dev.timlock.jarvis.core.model.PanelContent
import dev.timlock.jarvis.core.model.PanelPlacement
import dev.timlock.jarvis.core.model.PanelStack
import dev.timlock.jarvis.core.model.PanelType

/** Deterministic panel heights: the UI renders each panel at exactly this height (DESIGN §2.5). */
object PanelHeights {
    const val TITLE_ROW = 56
    const val LINE = 34
    const val COLLAPSED = 56

    fun estimatePx(p: Panel): Int {
        if (p.collapsed) return COLLAPSED
        return when (val c = p.content) {
            is PanelContent.Card -> {
                val bodyLines = if (c.body.isNullOrEmpty()) 0 else minOf(6, (c.body!!.length + 43) / 44)
                val bullets = minOf(c.bullets.size, 6) + (if (c.moreBullets > 0) 1 else 0)
                (TITLE_ROW + bodyLines * LINE + bullets * LINE + 24).coerceIn(120, 420)
            }
            // 1 row: 56 title + 64 value + 24 label + 16 pad (+30 caption); 2 compact rows fill the 210 max.
            is PanelContent.Stats -> if (c.metrics.size > 3) 210 else if (c.caption != null) 190 else 160
            is PanelContent.ListBody -> (TITLE_ROW + minOf(c.items.size, 10) * LINE + 24).coerceIn(120, 520)
            is PanelContent.Timer -> 150
        }
    }
}

/** Pure functions over [PanelStack] (newest first, max 3): DESIGN §2.5 and §5.5. */
object PanelManager {
    const val MAX_PANELS = 3 // default; the runtime value is AppState.config.maxPanels
    const val ZONE_HEIGHT_PX = 656
    const val GAP_PX = 16
    const val STICKY_CAP_MS = 12L * 60 * 60 * 1000
    const val TIMER_RING_MS = 60_000L
    const val STATS_TTL_MS = 90_000L
    const val DEFAULT_TTL_MS = dev.timlock.jarvis.core.model.UserConfig.DEFAULT_PANEL_TTL_MS
    /** Arrival timeline offset (review C9): panels that skip the orb-slide stage start at the bracket stage. */
    const val BRACKET_STAGE_MS = 380L

    fun isSticky(p: Panel) = p.sticky || p.pinned

    /** Panels that persist on their own (sticky, pinned, timers) are chips while no call is open (H1 ruling). */
    fun persists(p: Panel) = isSticky(p) || p.type == PanelType.TIMER

    private fun placementFor(p: Panel, callOpen: Boolean) =
        if (callOpen) PanelPlacement.DOCK else if (persists(p)) PanelPlacement.CHIP else PanelPlacement.DOCK

    private fun ttlFor(p: Panel, cardListTtlMs: Long) = if (p.type == PanelType.STATS) STATS_TTL_MS else cardListTtlMs

    /** Expiry per §5.5; null while a call is open (non-sticky). [callEndMs] = when the call ended (or now). */
    fun lifetime(p: Panel, callOpen: Boolean, callEndMs: Long, ttlMs: Long = DEFAULT_TTL_MS): Long? {
        val c = p.content
        return when {
            c is PanelContent.Timer -> c.endsAtMs + TIMER_RING_MS
            isSticky(p) -> p.updatedAtMs + STICKY_CAP_MS
            callOpen -> null
            else -> maxOf(p.updatedAtMs, callEndMs) + ttlFor(p, ttlMs)
        }
    }

    fun upsert(
        stack: PanelStack, panel: Panel, now: Long, callOpen: Boolean, ttl: Long = DEFAULT_TTL_MS,
        /** True when the orb layout was already DOCKED before this input (C9 revealStartMs rule). */
        dockedBefore: Boolean = false,
        maxPanels: Int = MAX_PANELS,
    ): PanelStack {
        var toast = stack.replacedToastAtMs
        val idx = stack.panels.indexOfFirst { it.id == panel.id }
        var placed: Panel
        var list: List<Panel>
        if (idx >= 0) {
            val old = stack.panels[idx]
            val oc = old.content
            val nc = panel.content
            val newRows = if (oc is PanelContent.ListBody && nc is PanelContent.ListBody) {
                val prev = oc.items.map { it.text }.toSet()
                nc.items.indices.filter { nc.items[it].text !in prev }.toSet()
            } else emptySet()
            placed = panel.copy(
                createdAtMs = old.createdAtMs, updatedAtMs = now, version = old.version + 1,
                pinned = old.pinned, sticky = panel.sticky || old.pinned, newRows = newRows,
                collapsed = old.collapsed,
            )
            placed = placed.withPlacement(old, callOpen, now, dockedBefore)
            list = stack.panels.toMutableList().also { it[idx] = placed.relife(callOpen, now, ttl) }
        } else {
            placed = panel.copy(createdAtMs = now, updatedAtMs = now, version = 0, pinned = false, collapsed = false, newRows = emptySet())
            placed = placed.withPlacement(null, callOpen, now, dockedBefore)
            var cur = stack.panels
            while (cur.size >= maxPanels) {
                val victim = cur.lastOrNull { !isSticky(it) } ?: run { toast = now; cur.last() }
                cur = cur.filter { it !== victim }
            }
            list = listOf(placed.relife(callOpen, now, ttl)) + cur
        }
        list = enforceStickyCap(list, placed.id, callOpen, now, ttl, maxPanels)
        return layout(PanelStack(list, toast))
    }

    /** Placement per the H1 ruling plus the C9 revealStartMs rules. [old] = previous version, null on arrival. */
    private fun Panel.withPlacement(old: Panel?, callOpen: Boolean, now: Long, dockedBefore: Boolean): Panel {
        val pl = placementFor(this, callOpen)
        if (old != null && old.placement == pl) return copy(placement = pl, revealStartMs = old.revealStartMs, collapsed = if (pl == PanelPlacement.CHIP) false else collapsed)
        val start = if (pl == PanelPlacement.DOCK && !dockedBefore) now else now - BRACKET_STAGE_MS
        return copy(placement = pl, revealStartMs = start, collapsed = if (pl == PanelPlacement.CHIP) false else collapsed)
    }

    /** Call ended: sticky/pinned/timer panels leave the dock for chips (non-sticky docked panels stay until they expire). */
    fun demoteAtCallEnd(stack: PanelStack, now: Long): PanelStack = layout(stack.copy(panels = stack.panels.map {
        if (it.placement == PanelPlacement.DOCK && persists(it))
            it.copy(placement = PanelPlacement.CHIP, collapsed = false, revealStartMs = now - BRACKET_STAGE_MS)
        else it
    }))

    /**
     * Placement invariant, applied after every input: chips and a dock never coexist (a top-right chip would sit
     * on the panel column). Any DOCK panel pulls the chips into the dock (they keep their reveal, so they just
     * move); with no call open and only persisting panels left, everything goes back to chips (H1).
     */
    fun reconcilePlacement(stack: PanelStack, callOpen: Boolean, now: Long): PanelStack {
        val ps = stack.panels
        if (ps.none { it.placement == PanelPlacement.DOCK }) return stack
        val next = if (!callOpen && ps.all { persists(it) }) {
            ps.map { if (it.placement == PanelPlacement.DOCK) it.copy(placement = PanelPlacement.CHIP, collapsed = false, revealStartMs = now - BRACKET_STAGE_MS) else it }
        } else {
            ps.map { if (it.placement == PanelPlacement.CHIP) it.copy(placement = PanelPlacement.DOCK) else it }
        }
        return if (next == ps) stack else layout(stack.copy(panels = next))
    }

    private fun Panel.relife(callOpen: Boolean, now: Long, ttl: Long) =
        copy(expiresAtMs = lifetime(this, callOpen, now, ttl))

    /** At most 3 sticky (pinned counts): un-stick the oldest sticky other than [keepId]. */
    private fun enforceStickyCap(list: List<Panel>, keepId: String, callOpen: Boolean, now: Long, ttl: Long, maxPanels: Int = MAX_PANELS): List<Panel> {
        var cur = list
        while (cur.count { isSticky(it) } > maxPanels) {
            val victim = cur.filter { isSticky(it) && it.id != keepId }.minByOrNull { it.createdAtMs } ?: break
            cur = cur.map {
                if (it === victim) it.copy(sticky = false, pinned = false).let { u -> u.copy(expiresAtMs = lifetime(u, callOpen, now, ttl)) } else it
            }
        }
        return cur
    }

    /**
     * Recompute expiries. Sticky/pinned/timer: always from their rules. Non-sticky: null while a call is open;
     * otherwise keep an existing deadline, or start one now (the call just ended).
     */
    fun applyLifetimes(stack: PanelStack, callOpen: Boolean, now: Long, ttl: Long = DEFAULT_TTL_MS): PanelStack =
        stack.copy(panels = stack.panels.map { p ->
            val exp = when {
                p.content is PanelContent.Timer || isSticky(p) -> lifetime(p, callOpen, now, ttl)
                callOpen -> null
                else -> p.expiresAtMs ?: lifetime(p, false, now, ttl)
            }
            if (exp == p.expiresAtMs) p else p.copy(expiresAtMs = exp)
        })

    fun expire(stack: PanelStack, now: Long): Pair<PanelStack, List<String>> {
        val gone = stack.panels.filter { val e = it.expiresAtMs; e != null && e <= now }
        if (gone.isEmpty()) return stack to emptyList()
        val kept = stack.copy(panels = stack.panels.filter { p -> gone.none { it.id == p.id } })
        return layout(kept) to gone.map { it.id }
    }

    fun dismiss(stack: PanelStack, id: String): PanelStack =
        if (stack.panels.none { it.id == id }) stack else layout(stack.copy(panels = stack.panels.filter { it.id != id }))

    fun clearAll(stack: PanelStack): PanelStack = stack.copy(panels = emptyList())

    fun pin(stack: PanelStack, id: String, pinned: Boolean, now: Long, callOpen: Boolean, ttl: Long = DEFAULT_TTL_MS, maxPanels: Int = MAX_PANELS): PanelStack {
        if (stack.panels.none { it.id == id }) return stack
        val list = stack.panels.map {
            if (it.id != id) it else {
                val u = it.copy(pinned = pinned, sticky = pinned)
                u.copy(expiresAtMs = lifetime(u, callOpen, now, ttl))
            }
        }
        return layout(stack.copy(panels = enforceStickyCap(list, id, callOpen, now, ttl, maxPanels)))
    }

    /** Uncollapse [id]; collapse other DOCK panels (bottom-most first) until the stack fits again. */
    fun expand(stack: PanelStack, id: String): PanelStack {
        if (stack.panels.none { it.id == id && it.collapsed && it.placement == PanelPlacement.DOCK }) return stack
        var list = stack.panels.map { if (it.id == id) it.copy(collapsed = false) else it }
        while (total(list) > ZONE_HEIGHT_PX) {
            val i = list.indexOfLast { !it.collapsed && it.id != id && it.placement == PanelPlacement.DOCK }
            if (i < 0) break
            list = list.toMutableList().also { it[i] = it[i].copy(collapsed = true) }
        }
        return stack.copy(panels = list)
    }

    fun dockPanels(list: List<Panel>): List<Panel> = list.filter { it.placement == PanelPlacement.DOCK }

    /** Height of the DOCK column only (chips live elsewhere and never collapse). */
    fun total(list: List<Panel>): Int {
        val d = dockPanels(list)
        return if (d.isEmpty()) 0 else d.sumOf { PanelHeights.estimatePx(it) } + GAP_PX * (d.size - 1)
    }

    /** Fit the DOCK panels into the 656 px zone: collapse bottom-most non-collapsed (never the top); re-expand what fits. */
    fun layout(stack: PanelStack): PanelStack {
        var list = stack.panels.map { if (it.placement == PanelPlacement.CHIP && it.collapsed) it.copy(collapsed = false) else it }
        fun dockIdx() = list.indices.filter { list[it].placement == PanelPlacement.DOCK }
        while (total(list) > ZONE_HEIGHT_PX) {
            val d = dockIdx()
            val i = d.lastOrNull { !list[it].collapsed } ?: break
            if (i == d.first()) break
            list = list.toMutableList().also { it[i] = it[i].copy(collapsed = true) }
        }
        for (i in dockIdx()) {
            if (!list[i].collapsed) continue
            val candidate = list.toMutableList().also { it[i] = it[i].copy(collapsed = false) }
            if (total(candidate) <= ZONE_HEIGHT_PX) list = candidate
        }
        return if (list == stack.panels) stack else stack.copy(panels = list)
    }
}
