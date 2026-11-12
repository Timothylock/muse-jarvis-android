package dev.timlock.jarvis.transport.sdk

import com.chaquo.python.PyObject
import dev.timlock.jarvis.core.transport.BlePeripheral

/**
 * Kotlin -> Python entry points for the BLE setup flow (jarvis_bridge.pair_write etc.). Called on GATT binder
 * threads: the Python side only feeds the SDK's assembler/queue, so these return quickly. Never logs packet bytes.
 */
internal class PairListener(private val bridge: PyObject) : BlePeripheral.Listener {
    override fun onConnected() { bridge.callAttr("pair_connected") }
    override fun onWrite(packet: ByteArray) { bridge.callAttr("pair_write", packet) }
    override fun onDisconnected() { bridge.callAttr("pair_disconnected") }
}
