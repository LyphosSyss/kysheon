package epheis.lyphossyss.kysheon.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.runtime.Composable

@Composable
actual fun rememberSoundPlayer(enabled: Boolean): SoundPlayer = rememberBankedSoundPlayer(enabled) { AndroidSoundPlayer() }

/**
 * One static [AudioTrack] per (sound, voice), see KYS-0040. A static track holds its whole buffer in shared memory:
 * replaying it is a rewind, with no copy and no allocation. The in-match sounds get two voices played round-robin, so
 * that two quick shots or hits overlap instead of cutting each other; the ceremony sounds (countdown, start, result)
 * never overlap themselves and get one, which keeps the track count (13) low enough for the fast mixer slots.
 */
private class AndroidSoundPlayer : BankedSoundPlayer<Array<Array<AudioTrack>>>() {
    private val next = IntArray(Sfx.entries.size)

    override fun build(): Array<Array<AudioTrack>> {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(Synth.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        return Array(Sfx.entries.size) { index ->
            val sfx = Sfx.entries[index]
            val pcm = Synth.render(sfx)
            // A voice the system refuses (track limit, no output) is skipped: fewer voices, not no sound at all.
            Array(voicesOf(sfx)) { newTrack(pcm, attributes, format) }.filterNotNull().toTypedArray()
        }
    }

    override fun start(bank: Array<Array<AudioTrack>>, sfx: Sfx) {
        val voices = bank[sfx.ordinal]
        if (voices.isEmpty()) return
        val voice = next[sfx.ordinal]
        next[sfx.ordinal] = (voice + 1) % voices.size
        val track = voices[voice]
        // A static track that played to its end stays "playing" with its head at the end: stop, rewind, play.
        track.stop()
        track.reloadStaticData()
        track.play()
    }

    override fun free(bank: Array<Array<AudioTrack>>) {
        for (voices in bank) for (track in voices) releaseQuietly(track)
    }

    private fun newTrack(pcm: ShortArray, attributes: AudioAttributes, format: AudioFormat): AudioTrack? {
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (_: Exception) {
            return null
        }
        val written = try {
            track.write(pcm, 0, pcm.size)
        } catch (_: Exception) {
            -1
        }
        if (written != pcm.size || track.state != AudioTrack.STATE_INITIALIZED) {
            releaseQuietly(track)
            return null
        }
        return track
    }

    private fun releaseQuietly(track: AudioTrack) {
        try {
            track.release()
        } catch (_: Exception) {
            // Already released or never initialized.
        }
    }

    private fun voicesOf(sfx: Sfx): Int = when (sfx) {
        Sfx.Fire, Sfx.ProjectileEnter, Sfx.HitDealt, Sfx.HitTaken -> 2
        Sfx.Countdown, Sfx.Start, Sfx.Victory, Sfx.Defeat, Sfx.Draw -> 1
    }
}
