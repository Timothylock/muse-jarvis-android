package dev.timlock.jarvis.core.store

import dev.timlock.jarvis.core.model.Level
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.OrbState
import dev.timlock.jarvis.core.model.TransportKind
import dev.timlock.jarvis.core.transport.TransportEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReducerTest {
    private fun run(vararg inputs: Input, log: (String) -> Unit = {}): AppState =
        inputs.fold(AppState()) { s, i -> reduce(s, i, now = 1000L, log = log) }

    @Test fun bootUntilBootComplete() {
        val s = run(Intent.ChooseMode(Mode.SIM))
        assertEquals(OrbState.BOOT, s.orbState)
        assertEquals(OrbState.IDLE, reduce(s, Intent.BootComplete, 2000L).orbState)
    }

    @Test fun simModeIsIdle() {
        val s = run(Intent.ChooseMode(Mode.SIM), Intent.BootComplete)
        assertEquals(OrbState.IDLE, s.orbState)
    }

    @Test fun simInterstitialSetAndCleared() {
        val s = run(Intent.ChooseMode(Mode.SIM), Intent.BootComplete)
        assertEquals(1000L + SIM_INTERSTITIAL_MS, s.simInterstitialUntil)
        assertNull(reduce(s, Intent.ClearInterstitial, 5000L).simInterstitialUntil)
    }

    @Test fun realUnpaired() {
        val s = run(Intent.ChooseMode(Mode.REAL), Intent.TransportLink(LinkState.Unpaired), Intent.BootComplete)
        assertEquals(OrbState.UNPAIRED, s.orbState)
        assertNull(s.simInterstitialUntil)
    }

    @Test fun realPairingLostIsUnpaired() {
        val s = run(Intent.ChooseMode(Mode.REAL), Intent.BootComplete, Intent.TransportLink(LinkState.PairingLost("revoked")))
        assertEquals(OrbState.UNPAIRED, s.orbState)
    }

    @Test fun realConnectingAndOfflineAreOffline() {
        val base = run(Intent.ChooseMode(Mode.REAL), Intent.BootComplete)
        assertEquals(OrbState.OFFLINE, base.orbState) // link starts Connecting
        assertEquals(OrbState.OFFLINE, reduce(base, Intent.TransportLink(LinkState.Offline("x")), 1L).orbState)
    }

    @Test fun realOnlineIsIdleAndSinceUpdates() {
        val base = run(Intent.ChooseMode(Mode.REAL), Intent.BootComplete)
        val s = reduce(base, Intent.TransportLink(LinkState.Online("n", "vm")), 4242L)
        assertEquals(OrbState.IDLE, s.orbState)
        assertEquals(4242L, s.orbStateSince)
        // unchanged orb state keeps its timestamp
        assertEquals(4242L, reduce(s, Intent.TransportLink(LinkState.Online("n", "vm2")), 9999L).orbStateSince)
    }

    @Test fun mismatchedEventIsDroppedAndLogged() {
        val logs = mutableListOf<String>()
        val s = run(Intent.ChooseMode(Mode.REAL), Intent.BootComplete)
        val out = reduce(s, Event(TransportEvent.Diagnostic(Level.INFO, "X", TransportKind.SIM)), 5L) { logs += it }
        assertEquals(s, out)
        assertTrue(logs.single().startsWith("MODE_MISMATCH"))
    }

    @Test fun matchingEventNotLogged() {
        val logs = mutableListOf<String>()
        val s = run(Intent.ChooseMode(Mode.SIM), Intent.BootComplete)
        reduce(s, Event(TransportEvent.TurnStarted("t", TransportKind.SIM)), 5L) { logs += it }
        assertTrue(logs.isEmpty())
    }

    @Test fun resetReturnsToFirstRun() {
        val s = run(Intent.ChooseMode(Mode.SIM), Intent.BootComplete, Intent.ResetMode)
        assertNull(s.mode)
        assertNull(s.simInterstitialUntil)
    }
}
