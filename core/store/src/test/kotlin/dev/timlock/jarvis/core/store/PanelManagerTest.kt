package dev.timlock.jarvis.core.store

import dev.timlock.jarvis.core.model.ListItem
import dev.timlock.jarvis.core.model.Metric
import dev.timlock.jarvis.core.model.Panel
import dev.timlock.jarvis.core.model.PanelContent
import dev.timlock.jarvis.core.model.PanelStack
import dev.timlock.jarvis.core.model.PanelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelManagerTest {
    private fun card(id: String, sticky: Boolean = false, body: String? = null, bullets: Int = 0, more: Int = 0) = Panel(
        id = id, type = PanelType.CARD, title = id,
        content = PanelContent.Card(body, List(bullets) { "b$it" }, more), sticky = sticky, createdAtMs = 0, updatedAtMs = 0,
    )
    private fun list(id: String, vararg texts: String, sticky: Boolean = false) = Panel(
        id = id, type = PanelType.LIST, title = id, content = PanelContent.ListBody(texts.map { ListItem(it) }),
        sticky = sticky, createdAtMs = 0, updatedAtMs = 0,
    )
    private fun stats(id: String, n: Int = 2) = Panel(
        id = id, type = PanelType.STATS, title = id,
        content = PanelContent.Stats(List(n) { Metric("l", "1") }), createdAtMs = 0, updatedAtMs = 0,
    )
    private fun timer(id: String, endsAt: Long) = Panel(
        id = id, type = PanelType.TIMER, title = id, content = PanelContent.Timer(10, endsAt), createdAtMs = 0, updatedAtMs = 0,
    )
    private fun ids(s: PanelStack) = s.panels.map { it.id }
    private fun PanelStack.up(p: Panel, now: Long, call: Boolean = false) = PanelManager.upsert(this, p, now, call)

    @Test fun newestOnTop() {
        val s = PanelStack().up(card("a"), 1).up(card("b"), 2)
        assertEquals(listOf("b", "a"), ids(s))
    }

    @Test fun replaceInPlaceKeepsPositionAndCreated() {
        var s = PanelStack().up(card("a"), 10).up(card("b"), 20).up(card("c"), 30)
        s = s.up(card("b", body = "new"), 99)
        assertEquals(listOf("c", "b", "a"), ids(s))
        val b = s.panels[1]
        assertEquals(20L, b.createdAtMs)
        assertEquals(99L, b.updatedAtMs)
        assertEquals(1, b.version)
        s = s.up(card("b"), 120)
        assertEquals(2, s.panels[1].version)
    }

    @Test fun replaceKeepsPinned() {
        var s = PanelStack().up(card("a"), 1)
        s = PanelManager.pin(s, "a", true, 2, false)
        s = s.up(card("a"), 3)
        assertTrue(s.panels[0].pinned)
        assertTrue(s.panels[0].sticky)
    }

    @Test fun evictsOldestNonSticky() {
        var s = PanelStack().up(card("a"), 1).up(card("b", sticky = true), 2).up(card("c"), 3)
        s = s.up(card("d"), 4)
        assertEquals(listOf("d", "c", "b"), ids(s))
        assertEquals(0L, s.replacedToastAtMs)
    }

    @Test fun evictsOldestNonStickyWhenOldestIsSticky() {
        var s = PanelStack().up(card("a", sticky = true), 1).up(card("b"), 2).up(card("c"), 3)
        s = s.up(card("d"), 4)
        assertEquals(listOf("d", "c", "a"), ids(s))
    }

    @Test fun allStickyEvictsOldestAndToasts() {
        var s = PanelStack().up(card("a", true), 1).up(card("b", true), 2).up(card("c", true), 3)
        s = s.up(card("d", true), 50)
        assertEquals(listOf("d", "c", "b"), ids(s))
        assertEquals(50L, s.replacedToastAtMs)
        assertTrue(s.panels.count { PanelManager.isSticky(it) } <= 3)
    }

    @Test fun stickyCapNeverExceedsThree() {
        var s = PanelStack()
        for (i in 1..6) s = s.up(card("p$i", true), i.toLong())
        assertEquals(3, s.panels.size)
        assertEquals(3, s.panels.count { PanelManager.isSticky(it) })
    }

    @Test fun newRowsFromPreviousVersion() {
        var s = PanelStack().up(list("l", "a", "b"), 1)
        assertTrue(s.panels[0].newRows.isEmpty())
        s = s.up(list("l", "a", "x", "b", "y"), 2)
        assertEquals(setOf(1, 3), s.panels[0].newRows)
    }

    @Test fun lifetimeDuringAndAfterCall() {
        val s = PanelStack().up(card("c"), 1000, call = true).up(stats("s"), 1000, call = true)
        assertTrue(s.panels.all { it.expiresAtMs == null })
        val ended = PanelManager.applyLifetimes(s, callOpen = false, now = 5000)
        assertEquals(5000L + 90_000, ended.panels.first { it.id == "s" }.expiresAtMs)
        assertEquals(5000L + 120_000, ended.panels.first { it.id == "c" }.expiresAtMs)
        // an already-running deadline is not restarted
        assertEquals(ended, PanelManager.applyLifetimes(ended, false, 9000))
        // call reopens: deadlines are suspended
        assertTrue(PanelManager.applyLifetimes(ended, true, 9000).panels.all { it.expiresAtMs == null })
    }

    @Test fun lifetimeWithoutCall() {
        val s = PanelStack().up(card("c"), 1000).up(stats("s"), 2000)
        assertEquals(1000L + 120_000, s.panels.first { it.id == "c" }.expiresAtMs)
        assertEquals(2000L + 90_000, s.panels.first { it.id == "s" }.expiresAtMs)
        val (kept, gone) = PanelManager.expire(s, 2000L + 90_000)
        assertEquals(listOf("s"), gone)
        assertEquals(listOf("c"), ids(kept))
        assertEquals(listOf<String>(), PanelManager.expire(kept, 1L).second)
    }

    @Test fun stickyTwelveHourCap() {
        val s = PanelStack().up(card("a", true), 1000, call = true)
        assertEquals(1000L + 12 * 3600_000L, s.panels[0].expiresAtMs)
        val (kept, gone) = PanelManager.expire(s, 1000L + 12 * 3600_000L)
        assertEquals(listOf("a"), gone)
        assertTrue(kept.panels.isEmpty())
    }

    @Test fun timerRingWindowRegardlessOfCall() {
        val t = timer("t", endsAt = 70_000)
        for (call in listOf(true, false)) {
            val s = PanelStack().up(t, 10_000, call)
            assertEquals(130_000L, s.panels[0].expiresAtMs)
            assertEquals(130_000L, PanelManager.applyLifetimes(s, call, 99).panels[0].expiresAtMs)
        }
    }

    @Test fun dismissAndClearAll() {
        val s = PanelStack().up(card("a"), 1).up(card("b"), 2)
        assertEquals(listOf("a"), ids(PanelManager.dismiss(s, "b")))
        assertEquals(s, PanelManager.dismiss(s, "zz"))
        assertTrue(PanelManager.clearAll(s).panels.isEmpty())
    }

    @Test fun pinAndUnpin() {
        var s = PanelStack().up(card("a"), 1000)
        s = PanelManager.pin(s, "a", true, 2000, callOpen = false)
        assertTrue(s.panels[0].pinned && s.panels[0].sticky)
        assertEquals(1000L + 12 * 3600_000L, s.panels[0].expiresAtMs)
        s = PanelManager.pin(s, "a", false, 3000, callOpen = false)
        assertFalse(s.panels[0].pinned || s.panels[0].sticky)
        assertEquals(3000L + 120_000, s.panels[0].expiresAtMs)
        s = PanelManager.pin(s, "a", true, 3500, true)
        s = PanelManager.pin(s, "a", false, 4000, callOpen = true)
        assertNull(s.panels[0].expiresAtMs)
    }

    @Test fun heightEstimates() {
        assertEquals(120, PanelHeights.estimatePx(card("a")))
        assertEquals(56 + 2 * 34 + 24, PanelHeights.estimatePx(card("a", body = "x".repeat(45))))
        assertEquals(56 + 6 * 34 + 24, PanelHeights.estimatePx(card("a", body = "x".repeat(1000))))
        assertEquals(150, PanelHeights.estimatePx(stats("s", 3)))
        assertEquals(210, PanelHeights.estimatePx(stats("s", 4)))
        assertEquals(150, PanelHeights.estimatePx(timer("t", 5)))
        assertEquals(120, PanelHeights.estimatePx(list("l", "a")))
        assertEquals(56 + 10 * 34 + 24, PanelHeights.estimatePx(list("l", *Array(30) { "i$it" })))
        assertEquals(56, PanelHeights.estimatePx(stats("s").copy(collapsed = true)))
    }

    @Test fun cardHeightClamps() {
        assertEquals(420, PanelHeights.estimatePx(card("a", body = "x".repeat(1000), bullets = 6, more = 2)))
    }

    @Test fun collapsesBottomWhenOverflowing() {
        // 56+10*34+24 = 420 for each of two lists: 420+16+420 = 856 > 656
        val l = { id: String -> list(id, *Array(10) { "i$it" }) }
        var s = PanelStack().up(l("a"), 1)
        assertFalse(s.panels[0].collapsed)
        s = s.up(l("b"), 2)
        assertEquals(listOf("b", "a"), ids(s))
        assertFalse(s.panels[0].collapsed)
        assertTrue(s.panels[1].collapsed)
        assertTrue(PanelManager.total(s.panels) <= 656)
    }

    @Test fun expandCollapsesAnother() {
        val l = { id: String -> list(id, *Array(10) { "i$it" }) }
        var s = PanelStack().up(l("a"), 1).up(l("b"), 2)
        s = PanelManager.expand(s, "a")
        assertFalse(s.panels.first { it.id == "a" }.collapsed)
        assertTrue(s.panels.first { it.id == "b" }.collapsed)
        assertTrue(PanelManager.total(s.panels) <= 656)
        // expanding a non-collapsed panel is a no-op
        assertEquals(s, PanelManager.expand(s, "a"))
    }

    @Test fun collapsedPanelReexpandsWhenSpaceFrees() {
        val l = { id: String -> list(id, *Array(10) { "i$it" }) }
        var s = PanelStack().up(l("a"), 1).up(l("b"), 2)
        s = PanelManager.dismiss(s, "b")
        assertFalse(s.panels[0].collapsed)
    }

    @Test fun topNeverCollapsedByLayout() {
        val big = { id: String -> list(id, *Array(30) { "i$it" }) } // 420
        var s = PanelStack().up(big("a"), 1).up(big("b"), 2).up(big("c"), 3)
        assertFalse(s.panels[0].collapsed)
        assertTrue(PanelManager.total(s.panels) <= 656)
    }
}
