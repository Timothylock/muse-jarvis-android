package dev.timlock.jarvis.feature.pairing.ui

import dev.timlock.jarvis.core.transport.PairOutcome
import dev.timlock.jarvis.core.transport.PairingStage
import dev.timlock.jarvis.core.transport.PairingUiSink
import dev.timlock.jarvis.core.transport.SdkToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val GOOD = "mgst_" + "A".repeat(42) + "A"

private class FakeDeps(var stored: SdkToken? = null, override val sim: Boolean = false) : PairingDeps {
    var readiness = Readiness.GREEN
    var gate = CompletableDeferred<PairOutcome>()
    var nodeId: String? = null
    var opened = 0
    val closed = mutableListOf<Boolean>()
    var pairCalls = 0
    var onPair: (PairingUiSink) -> Unit = {}
    var pairCancelled = false

    override fun storedToken() = stored
    override fun saveToken(token: SdkToken) { stored = token }
    override fun readiness() = readiness
    override suspend fun pair(token: SdkToken, ui: PairingUiSink): PairOutcome {
        pairCalls++
        onPair(ui)
        try { return gate.await() } catch (e: kotlinx.coroutines.CancellationException) { pairCancelled = true; throw e }
    }
    override fun nodeId() = nodeId
    override fun windowOpened() { opened++ }
    override fun windowClosed(paired: Boolean) { closed += paired }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PairingFlowTest {
    private fun TestScope.flow(d: FakeDeps, exits: MutableList<FlowExit> = mutableListOf(), note: String? = null) =
        PairingFlow(d, backgroundScope, { exits += it }, startNote = note)

    @Test fun tokenFormatValidation() {
        assertTrue(FlowState(entered = GOOD).enteredValid)
        assertTrue(FlowState(entered = "  $GOOD\n").enteredValid)
        assertFalse(FlowState(entered = "mgst_short").enteredValid)
        assertFalse(FlowState(entered = "").enteredValid)
    }

    @Test fun savedTokenShowsOnlyLast4() = runTest {
        val f = flow(FakeDeps(stored = SdkToken(GOOD)))
        val st = f.state.value
        assertEquals("AAAA", st.storedLast4)
        assertTrue(st.showSavedCard)
        assertFalse(st.toString().contains(GOOD))
        f.replaceStored()
        assertFalse(f.state.value.showSavedCard)
    }

    @Test fun useStoredAdvancesThroughReadinessIntoWindow() = runTest {
        val d = FakeDeps(stored = SdkToken(GOOD))
        d.readiness = Readiness.fromProblem("bluetooth_off")
        val f = flow(d)
        f.useStored(); runCurrent()
        assertEquals(PairStep.BLUETOOTH, f.state.value.step)
        assertEquals(0, d.opened)
        advanceTimeBy(2000); runCurrent()
        assertEquals(PairStep.BLUETOOTH, f.state.value.step)
        d.readiness = Readiness.GREEN
        advanceTimeBy(1000); runCurrent()
        assertEquals(PairStep.WINDOW, f.state.value.step)
        assertEquals(1, d.opened)
        assertEquals(1, d.pairCalls)
    }

    @Test fun enteredTokenIsStagedOnNextAndSavedOnlyWhenPaired() = runTest {
        val d = FakeDeps()
        val f = flow(d)
        f.onTokenChanged("junk"); f.next(); runCurrent()
        assertEquals(PairStep.TOKEN, f.state.value.step)
        assertNull(d.stored)
        f.onTokenChanged(" $GOOD "); f.next(); runCurrent()
        assertNull(d.stored) // staged in memory only
        assertEquals(PairStep.WINDOW, f.state.value.step) // readiness green -> straight through
        d.gate.complete(PairOutcome.PAIRED); runCurrent()
        assertEquals(GOOD, d.stored?.value)
    }

    @Test fun failedPairingNeverSavesTheStagedToken() = runTest {
        val d = FakeDeps()
        val f = flow(d)
        f.onTokenChanged(GOOD); f.next(); runCurrent()
        d.gate.complete(PairOutcome.WINDOW_CLOSED); runCurrent()
        assertNull(d.stored)
    }

    @Test fun windowProgressAndCountdownFollowSink() = runTest {
        val d = FakeDeps(stored = SdkToken(GOOD))
        d.onPair = { ui ->
            ui.onWindowOpen("MuseGadgetABC123", 600, true)
            ui.onProgress(PairingStage.ADVERTISING, .25f)
            ui.onProgress(PairingStage.CONNECTED, .5f)
            ui.onWindowRemaining(581)
        }
        val f = flow(d); f.useStored(); runCurrent()
        val st = f.state.value
        assertEquals("MuseGadgetABC123", st.bleName)
        assertEquals(2, st.stagesReached)
        assertEquals(581, st.remainingS)
        assertEquals("9:41", formatCountdown(581))
        assertEquals("0:05", formatCountdown(5))
    }

    @Test fun pairedShowsNodeIdAndAutoContinuesAfter3s() = runTest {
        val d = FakeDeps(stored = SdkToken(GOOD)); d.nodeId = "homelink-abc123"
        val exits = mutableListOf<FlowExit>()
        val f = flow(d, exits); f.useStored(); runCurrent()
        d.gate.complete(PairOutcome.PAIRED); runCurrent()
        assertEquals(ResultKind.PAIRED, f.state.value.result)
        assertEquals("homelink-abc123", f.state.value.nodeId)
        assertEquals(listOf(true), d.closed)
        advanceTimeBy(2900); runCurrent()
        assertTrue(exits.isEmpty())
        advanceTimeBy(200); runCurrent()
        assertEquals(listOf(FlowExit.DONE), exits)
    }

    @Test fun alreadyPairedWaitsForContinue() = runTest {
        val d = FakeDeps(stored = SdkToken(GOOD))
        val exits = mutableListOf<FlowExit>()
        val f = flow(d, exits); f.useStored(); runCurrent()
        d.gate.complete(PairOutcome.ALREADY_PAIRED); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertTrue(exits.isEmpty())
        f.continueAfterResult()
        assertEquals(listOf(FlowExit.DONE), exits)
    }

    @Test fun invalidTokenReturnsToTokenStepWithFocusAndProblemText() = runTest {
        val d = FakeDeps(stored = SdkToken(GOOD))
        d.onPair = { it.onTokenProblem("sdk_token has the wrong length") }
        val f = flow(d); f.useStored(); runCurrent()
        d.gate.complete(PairOutcome.INVALID_SDK_TOKEN); runCurrent()
        val st = f.state.value
        assertEquals(PairStep.TOKEN, st.step)
        assertEquals("sdk_token has the wrong length", st.tokenNote)
        assertEquals(1, st.focusRequest)
        assertTrue(st.replacing && st.entered.isEmpty())
        assertEquals(listOf(false), d.closed)
    }

    @Test fun windowClosedThenRetryThenThreeFailuresSuggestImport() = runTest {
        val d = FakeDeps(stored = SdkToken(GOOD))
        val f = flow(d); f.useStored(); runCurrent()
        d.gate.complete(PairOutcome.WINDOW_CLOSED); runCurrent()
        assertEquals(ResultKind.WINDOW_CLOSED, f.state.value.result)
        assertFalse(f.state.value.suggestImport)
        d.gate = CompletableDeferred(); f.retry(); runCurrent()
        assertEquals(PairStep.WINDOW, f.state.value.step)
        assertEquals(2, d.opened)
        d.gate.complete(PairOutcome.BLE_ERROR); runCurrent()
        assertEquals(ResultKind.BLE_ERROR, f.state.value.result)
        assertFalse(f.state.value.suggestImport)
        d.gate = CompletableDeferred(); f.retry(); runCurrent()
        d.gate.complete(PairOutcome.BLE_ERROR); runCurrent()
        assertEquals(3, f.state.value.failures)
        assertTrue(f.state.value.suggestImport)
    }

    @Test fun backFromResultGoesToTokenStep() = runTest {
        val d = FakeDeps(stored = SdkToken(GOOD))
        val f = flow(d); f.useStored(); runCurrent()
        d.gate.complete(PairOutcome.WINDOW_CLOSED); runCurrent()
        f.backToToken()
        assertEquals(PairStep.TOKEN, f.state.value.step)
    }

    @Test fun pairThrowingIsABleError() = runTest {
        val d = FakeDeps(stored = SdkToken(GOOD))
        d.gate.completeExceptionally(IllegalStateException("boom"))
        val f = flow(d); f.useStored(); runCurrent()
        assertEquals(ResultKind.BLE_ERROR, f.state.value.result)
    }

    @Test fun cancelDuringWindowCancelsPairAndClosesWindow() = runTest {
        val d = FakeDeps(stored = SdkToken(GOOD))
        val exits = mutableListOf<FlowExit>()
        val f = flow(d, exits); f.useStored(); runCurrent()
        f.cancel(); runCurrent()
        assertTrue(d.pairCancelled)
        assertEquals(listOf(false), d.closed)
        assertEquals(listOf(FlowExit.CANCELLED), exits)
        f.cancel() // idempotent
        assertEquals(1, exits.size)
    }

    @Test fun cancelFromAnyOtherStep() = runTest {
        val exits = mutableListOf<FlowExit>()
        val f = flow(FakeDeps(), exits)
        f.cancel()
        assertEquals(listOf(FlowExit.CANCELLED), exits)
        val d = FakeDeps(stored = SdkToken(GOOD)); d.readiness = Readiness.fromProblem("no_permission")
        val exits2 = mutableListOf<FlowExit>()
        val g = flow(d, exits2); g.useStored(); runCurrent()
        g.cancel(); advanceTimeBy(5000); runCurrent()
        assertEquals(listOf(FlowExit.CANCELLED), exits2)
        assertEquals(0, d.opened)
    }

    @Test fun importInsteadExitsWithImport() = runTest {
        val exits = mutableListOf<FlowExit>()
        val d = FakeDeps(stored = SdkToken(GOOD)); d.readiness = Readiness.fromProblem("advertise_unsupported")
        val f = flow(d, exits); f.useStored(); runCurrent()
        assertEquals(PairStep.BLUETOOTH, f.state.value.step)
        f.importInstead()
        assertEquals(listOf(FlowExit.IMPORT), exits)
    }

    @Test fun provisionOnlyAcceptedOnTokenStep() = runTest {
        val d = FakeDeps()
        val f = flow(d)
        assertNull(f.provisionToken(GOOD))
        assertNull(d.stored) // staged, not stored
        assertEquals(GOOD, f.state.value.entered)
        assertEquals("malformed token", f.provisionToken("nope"))
        d.readiness = Readiness.fromProblem("bluetooth_off")
        f.next(); runCurrent()
        assertEquals(PairStep.BLUETOOTH, f.state.value.step)
        assertEquals(PairingFlow.NOT_ON_TOKEN_SCREEN, f.provisionToken(GOOD))
    }

    @Test fun startNoteIsShown() = runTest {
        val f = flow(FakeDeps(), note = "Create a new token at gadgets.muse.ai")
        assertEquals("Create a new token at gadgets.muse.ai", f.state.value.tokenNote)
        assertNotNull(f.state)
    }

    @Test fun readinessMapping() {
        assertTrue(Readiness.fromProblem(null).allGreen)
        assertEquals(CheckStatus.FAIL, Readiness.fromProblem("bluetooth_off").bluetooth)
        assertEquals(CheckStatus.FAIL, Readiness.fromProblem("no_permission").permissions)
        assertEquals(CheckStatus.FAIL, Readiness.fromProblem("advertise_unsupported").advertising)
        assertEquals("weird", Readiness.fromProblem("weird").advertisingDetail)
    }
}
