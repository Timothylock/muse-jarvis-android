package dev.timlock.jarvis

import android.content.Context
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.TransportKind
import dev.timlock.jarvis.core.secure.CredentialNames
import dev.timlock.jarvis.core.secure.CredentialStore
import dev.timlock.jarvis.core.secure.CredentialUnreadableException
import dev.timlock.jarvis.core.store.AppStore
import dev.timlock.jarvis.core.store.Intent as StoreIntent
import dev.timlock.jarvis.core.transport.PairOutcome
import dev.timlock.jarvis.core.transport.PairingUiSink
import dev.timlock.jarvis.core.transport.SdkToken
import dev.timlock.jarvis.feature.pairing.ble.AndroidBlePeripheral
import dev.timlock.jarvis.feature.pairing.ui.PairingDeps
import dev.timlock.jarvis.feature.pairing.ui.Readiness
import dev.timlock.jarvis.transport.sdk.SdkTransport

/**
 * Connects the pure pairing flow to the app: credentials (REAL only; sim never touches real credentials),
 * the active transport, and the store's PAIRING orb state. In sim the Bluetooth check is a fake all-green.
 */
class AppPairingDeps(
    override val sim: Boolean,
    private val context: Context,
    private val credentials: CredentialStore,
    private val store: AppStore,
    /** Explicit re-pair: pass force=true to the transport so an existing pairing does not short-circuit to ALREADY_PAIRED. */
    private val force: Boolean,
    private val transport: () -> dev.timlock.jarvis.core.transport.GadgetTransport?,
) : PairingDeps {

    override fun storedToken(): SdkToken? {
        if (sim) return null
        // An unreadable stored token is treated as "none" here (the user can paste a new one); the unreadable file
        // itself is left untouched.
        val raw = try {
            credentials.read(CredentialNames.SDK_TOKEN)
        } catch (e: CredentialUnreadableException) {
            null
        }
        return raw?.let { SdkToken(String(it, Charsets.UTF_8).trim()) }?.takeIf { it.isWellFormed() }
    }

    override fun saveToken(token: SdkToken) {
        if (sim) return
        credentials.write(CredentialNames.SDK_TOKEN, token.value.trim().toByteArray(Charsets.UTF_8))
    }

    override fun readiness(): Readiness {
        if (sim) return Readiness.GREEN
        val peripheral = (transport() as? SdkTransport)?.peripheral ?: AndroidBlePeripheral(context)
        return Readiness.fromProblem(peripheral.readinessProblem())
    }

    override suspend fun pair(token: SdkToken, ui: PairingUiSink): PairOutcome {
        val t = transport() ?: return PairOutcome.BLE_ERROR
        if ((t.kind == TransportKind.SIM) != sim) return PairOutcome.BLE_ERROR
        if (sim) AppGraph.simController.applyPairSettings()
        return t.pair(token, ui, force)
    }

    override fun nodeId(): String? {
        val t = transport() ?: return null
        (t.link.value as? LinkState.Online)?.let { return it.nodeId }
        return (t as? SdkTransport)?.diagnostics?.value?.nodeId
    }

    override fun windowOpened() { store.dispatch(StoreIntent.PairingOpen) }
    override fun windowClosed(paired: Boolean) { store.dispatch(StoreIntent.PairingClosed(paired)) }
}
