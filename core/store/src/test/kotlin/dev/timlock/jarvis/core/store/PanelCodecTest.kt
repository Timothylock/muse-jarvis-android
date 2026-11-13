package dev.timlock.jarvis.core.store

import dev.timlock.jarvis.core.model.ListItem
import dev.timlock.jarvis.core.model.Metric
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.Panel
import dev.timlock.jarvis.core.model.PanelContent
import dev.timlock.jarvis.core.model.PanelPlacement
import dev.timlock.jarvis.core.model.PanelStack
import dev.timlock.jarvis.core.model.PanelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelCodecTest {
    private val mono0 = 1_000_000L
    private val wall0 = 1_700_000_000_000L

    private fun stack() = PanelStack(
        panels = listOf(
            Panel(
                id = "timer-1", type = PanelType.TIMER, title = "Tea",
                content = PanelContent.Timer(totalSeconds = 300, endsAtMs = mono0 + 120_000),
                createdAtMs = mono0 - 180_000, updatedAtMs = mono0 - 180_000,
                placement = PanelPlacement.CHIP, revealStartMs = mono0 - 180_380,
            ),
            Panel(
                id = "list-1", type = PanelType.LIST, title = "Shopping",
                content = PanelContent.ListBody(listOf(ListItem("milk", 0, true), ListItem("eggs", 1, false)), ordered = true),
                sticky = true, pinned = true, createdAtMs = mono0 - 5000, updatedAtMs = mono0 - 1000,
                version = 2, newRows = setOf(1), expiresAtMs = null,
            ),
            Panel(
                id = "stats-1", type = PanelType.STATS, title = "Weather",
                content = PanelContent.Stats(listOf(Metric("Temp", "21", "°C", true), Metric("Wind", "4.5", "m/s")), caption = "Now"),
                sticky = true, createdAtMs = mono0 - 3000, updatedAtMs = mono0 - 3000,
            ),
            Panel(
                id = "card-1", type = PanelType.CARD, title = "Note",
                content = PanelContent.Card("hello", listOf("a", "b"), moreBullets = 3),
                sticky = true, createdAtMs = mono0 - 2000, updatedAtMs = mono0 - 2000, expiresAtMs = mono0 + 9000,
                collapsed = true,
            ),
        ),
        replacedToastAtMs = mono0 - 100,
    )

    @Test fun roundTripSameClock() {
        val s = stack()
        assertEquals(s, PanelCodec.decode(PanelCodec.encode(s, mono0, wall0), mono0, wall0))
    }

    @Test fun timerConvertedAcrossReboot() {
        val json = PanelCodec.encode(stack(), mono0, wall0)
        val out = PanelCodec.decode(json, 5_000L, wall0 + 60_000)!!
        val t = out.panels[0].content as PanelContent.Timer
        // 120 s remaining at encode, 60 s of wall time elapsed -> 60 s remaining on the new mono clock.
        assertEquals(5_000L + 60_000L, t.endsAtMs)
        assertEquals(300, t.totalSeconds)
        assertEquals(5_000L - 60_000L - 180_000L, out.panels[0].createdAtMs)
    }

    @Test fun garbageReturnsNull() {
        assertNull(PanelCodec.decode("not json", 0, 0))
        assertNull(PanelCodec.decode("{}", 0, 0))
        assertNull(PanelCodec.decode("""{"v":1,"panels":[{"id":"x"}]}""", 0, 0))
        assertNull(PanelCodec.decode("""{"v":99,"panels":[]}""", 0, 0))
    }

    @Test fun restoreIntentYieldsPanelsInReal() {
        val json = PanelCodec.encode(stack(), mono0, wall0)
        val now = 5_000L
        val decoded = PanelCodec.decode(json, now, wall0 + 200_000)!! // timer long past its end
        val r = reduce(AppState(mode = Mode.REAL), Intent.RestorePanels(decoded), now)
        val byId = r.state.panels.panels.associateBy { it.id }
        assertTrue("list-1" in byId)
        assertTrue("timer-1" in byId)
        assertTrue((byId.getValue("timer-1").content as PanelContent.Timer).chimed)
    }

    @Test fun restoreIgnoredInSim() {
        val decoded = PanelCodec.decode(PanelCodec.encode(stack(), mono0, wall0), mono0, wall0)!!
        val r = reduce(AppState(mode = Mode.SIM), Intent.RestorePanels(decoded), mono0)
        assertTrue(r.state.panels.panels.isEmpty())
    }
}
