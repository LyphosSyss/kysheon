package epheis.lyphossyss.kysheon.app.audio

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.setActive

@Composable
actual fun rememberSoundPlayer(enabled: Boolean): SoundPlayer = rememberBankedSoundPlayer(enabled) { IosSoundPlayer() }

/** Engine, voice pool and one float buffer per [Sfx] (index = ordinal, null when the buffer could not be made). */
private class IosBank(
    val engine: AVAudioEngine,
    val nodes: List<AVAudioPlayerNode>,
    val buffers: List<AVAudioPCMBuffer?>,
) {
    var next = 0
}

/**
 * [AVAudioEngine] with a pool of [VOICES] player nodes on the main mixer, see KYS-0040. Each [play] takes the next node
 * round-robin, so up to [VOICES] sounds overlap, whatever they are.
 *
 * Objective-C exceptions are not Kotlin exceptions: `try`/`catch` does not stop them. The calls that raise one (playing
 * a node while the engine is stopped, scheduling on an unconnected node) are therefore prevented by checks, not caught.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosSoundPlayer : BankedSoundPlayer<IosBank>() {

    override fun build(): IosBank? {
        // Ambient: obeys the silent switch and mixes with the music of other apps instead of stopping it.
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryAmbient, error = null)
        session.setActive(true, error = null)

        val format = AVAudioFormat(standardFormatWithSampleRate = Synth.SAMPLE_RATE.toDouble(), channels = 1u)
        val buffers = Sfx.entries.map { toBuffer(Synth.render(it), format) }
        val engine = AVAudioEngine()
        val nodes = List(VOICES) {
            AVAudioPlayerNode().also { node ->
                engine.attachNode(node)
                engine.connect(node, to = engine.mainMixerNode, format = format)
            }
        }
        engine.prepare()
        // A failed start is retried by the next play (for example, built while the session was interrupted).
        engine.startAndReturnError(null)
        return IosBank(engine, nodes, buffers)
    }

    override fun start(bank: IosBank, sfx: Sfx) {
        val buffer = bank.buffers[sfx.ordinal] ?: return
        // An interruption (call, Siri, alarm) or a route change stops the engine: restart it on demand.
        if (!bank.engine.running && !restart(bank.engine)) return
        val node = bank.nodes[bank.next]
        bank.next = (bank.next + 1) % bank.nodes.size
        node.stop()
        node.volume = volume
        node.scheduleBuffer(buffer, completionHandler = null)
        node.play()
    }

    override fun free(bank: IosBank) {
        for (node in bank.nodes) node.stop()
        bank.engine.stop()
        for (node in bank.nodes) bank.engine.detachNode(node)
    }

    private fun restart(engine: AVAudioEngine): Boolean {
        AVAudioSession.sharedInstance().setActive(true, error = null)
        engine.prepare()
        return engine.startAndReturnError(null) && engine.running
    }

    private fun toBuffer(pcm: ShortArray, format: AVAudioFormat): AVAudioPCMBuffer? {
        val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = pcm.size.toUInt())
        val channel = buffer.floatChannelData?.get(0) ?: return null
        for (i in pcm.indices) channel[i] = pcm[i] / PCM_SCALE
        buffer.frameLength = pcm.size.toUInt()
        return buffer
    }

    private companion object {
        const val VOICES = 6
        const val PCM_SCALE = 32_768f
    }
}
