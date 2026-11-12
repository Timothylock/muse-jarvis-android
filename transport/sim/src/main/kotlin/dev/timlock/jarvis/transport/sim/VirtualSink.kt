package dev.timlock.jarvis.transport.sim

import dev.timlock.jarvis.core.transport.PcmSink
import dev.timlock.jarvis.core.transport.PcmSinkListener
import kotlinx.coroutines.CoroutineScope

/**
 * M4 CONTRACT (Track A implements). A [PcmSink] that plays nothing: it tracks a wall-clock playhead over the
 * written 24 kHz PCM so that `onFirstAudible` fires when the first sample after silence is "played" and
 * `onDrained` when the playhead passes the last written sample. write() never blocks; flush() discards
 * everything queued (no onDrained for flushed audio). Stand-in for the AudioTrack Player until M5.
 */
class VirtualSink(private val scope: CoroutineScope) : PcmSink {
    override fun write(pcm: ShortArray) { TODO("M4 Track A") }
    override fun flush() { TODO("M4 Track A") }
    override fun setListener(listener: PcmSinkListener?) { TODO("M4 Track A") }
}
