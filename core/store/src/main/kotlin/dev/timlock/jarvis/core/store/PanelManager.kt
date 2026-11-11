package dev.timlock.jarvis.core.store

import dev.timlock.jarvis.core.model.Panel
import dev.timlock.jarvis.core.model.PanelContent
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
            is PanelContent.Stats -> if (c.metrics.size <= 3) 150 else 210
            is PanelContent.ListBody -> (TITLE_ROW + minOf(c.items.size, 10) * LINE + 24).coerceIn(120, 520)
            is PanelContent.Timer -> 150
        }
    }
}

/** Pure functions over [PanelStack] (newest first, max 3): DESIGN §2.5 and §5.5. */
object PanelManager {
    const val MAX_PANELS = 3
    const val ZONE_HEIGHT_PX = 656
    const val GAP_PX = 16
    const val STICKY_CAP_MS = 12L * 60 * 60 * 1000
    const val TIMER_RING_MS = 60_000L
    const val STATS_TTL_MS = 90_000L
    const val DEFAULT_TTL_MS = 120_000L

    fun isSticky(p: Panel) = p.sticky || p.pinned

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
    ): PanelStack {
        var toast = stack.replacedToastAtMs
        val idx = stack.panels.indexOfFirst { it.id == panel.id }
        val placed: Panel
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
            list = stack.panels.toMutableList().also { it[idx] = placed.relife(callOpen, now, ttl) }
        } else {
            placed = panel.copy(createdAtMs = now, updatedAtMs = now, version = 0, pinned = false, collapsed = false, newRows = emptySet())
            var cur = stack.panels
            if (cur.size >= MAX_PANELS) {
                val victim = cur.lastOrNull { !isSticky(it) } ?: run { toast = now; cur.last() }
                cur = cur.filter { it !== victim }
            }
            list = listOf(placed.relife(callOpen, now, ttl)) + cur
        }
        list = enforceStickyCap(list, placed.id, callOpen, now, ttl)
        return layout(PanelStack(list, toast))
    }

    private fun Panel.relife(callOpen: Boolean, now: Long, ttl: Long) =
        copy(expiresAtMs = lifetime(this, callOpen, now, ttl))

    /** At most 3 sticky (pinned counts): un-stick the oldest sticky other than [keepId]. */
    private fun enforceStickyCap(list: List<Panel>, keepId: String, callOpen: Boolean, now: Long, ttl: Long): List<Panel> {
        var cur = list
        while (cur.count { isSticky(it) } > MAX_PANELS) {
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

    fun pin(stack: PanelStack, id: String, pinned: Boolean, now: Long, callOpen: Boolean, ttl: Long = DEFAULT_TTL_MS): PanelStack {
        if (stack.panels.none { it.id == id }) return stack
        val list = stack.panels.map {
            if (it.id != id) it else {
                val u = it.copy(pinned = pinned, sticky = pinned)
                u.copy(expiresAtMs = lifetime(u, callOpen, now, ttl))
            }
        }
        return layout(stack.copy(panels = enforceStickyCap(list, id, callOpen, now, ttl)))
    }

    /** Uncollapse [id]; collapse other panels (bottom-most first) until the stack fits again. */
    fun expand(stack: PanelStack, id: String): PanelStack {
        if (stack.panels.none { it.id == id && it.collapsed }) return stack
        var list = stack.panels.map { if (it.id == id) it.copy(collapsed = false) else it }
        while (total(list) > ZONE_HEIGHT_PX) {
            val i = list.indexOfLast { !it.collapsed && it.id != id }
            if (i < 0) break
            list = list.toMutableList().also { it[i] = it[i].copy(collapsed = true) }
        }
        return stack.copy(panels = list)
    }

    fun total(list: List<Panel>): Int =
        if (list.isEmpty()) 0 else list.sumOf { PanelHeights.estimatePx(it) } + GAP_PX * (list.size - 1)

    /** Fit the stack into the 656 px zone: collapse bottom-most non-collapsed (never the top); re-expand what fits. */
    fun layout(stack: PanelStack): PanelStack {
        var list = stack.panels
        while (total(list) > ZONE_HEIGHT_PX) {
            val i = list.indexOfLast { !it.collapsed }
            if (i <= 0) break
            list = list.toMutableList().also { it[i] = it[i].copy(collapsed = true) }
        }
        for (i in list.indices) {
            if (!list[i].collapsed) continue
            val candidate = list.toMutableList().also { it[i] = it[i].copy(collapsed = false) }
            if (total(candidate) <= ZONE_HEIGHT_PX) list = candidate
        }
        return if (list == stack.panels) stack else stack.copy(panels = list)
    }
}
