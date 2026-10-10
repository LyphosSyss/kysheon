package epheis.lyphossyss.kysheon.app.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays the synthesized [Sfx] (see KYS-0040).
 *
 * [play] never blocks the caller on rendering or I/O and never throws: before the buffers are ready, after [release],
 * or when the platform refuses (interruption, no output), the sound is silently dropped. Audio is never worth a crash.
 */
interface SoundPlayer {
    /** Starts [sfx] now. Overlapping calls are mixed, within the platform voice budget. Call from the main thread. */
    fun play(sfx: Sfx)

    /** Frees every platform resource. [play] becomes a no-op. Idempotent. */
    fun release()
}

/** Plays nothing: sound turned off, previews, tests. */
object SilentSoundPlayer : SoundPlayer {
    override fun play(sfx: Sfx) = Unit

    override fun release() = Unit
}

/**
 * Remembers the platform [SoundPlayer] for the composition, released when it leaves the composition.
 *
 * With [enabled] false nothing is allocated (no audio session, no track) and [SilentSoundPlayer] is returned. When it
 * turns true, the buffers are rendered by [Synth] and loaded on a background dispatcher; sounds played before the end
 * of that loading (a few tens of milliseconds) are dropped.
 */
@Composable
expect fun rememberSoundPlayer(enabled: Boolean): SoundPlayer

/**
 * Shared lifecycle of the platform players: the bank of buffers ([B]) is built on [Dispatchers.Default], then installed
 * on the main thread, which is the only thread touching it afterwards ([play], [release]). No lock is needed.
 */
internal abstract class BankedSoundPlayer<B : Any> : SoundPlayer {
    private var bank: B? = null
    private var released = false

    /** Renders the [Synth] buffers and creates the platform resources. Background thread. Null when unavailable. */
    protected abstract fun build(): B?

    /** Starts [sfx] on [bank]. Main thread. */
    protected abstract fun start(bank: B, sfx: Sfx)

    /** Frees [bank]. Main thread. */
    protected abstract fun free(bank: B)

    /** Starts loading. The scope is not tied to the composition, so a bank built after [release] is still freed. */
    fun load() {
        CoroutineScope(Dispatchers.Main).launch {
            val built = withContext(Dispatchers.Default) {
                try {
                    build()
                } catch (_: Exception) {
                    null
                }
            } ?: return@launch
            if (released) freeQuietly(built) else bank = built
        }
    }

    final override fun play(sfx: Sfx) {
        val current = bank ?: return
        try {
            start(current, sfx)
        } catch (_: Exception) {
            // A lost sound is better than a crash.
        }
    }

    final override fun release() {
        released = true
        val current = bank ?: return
        bank = null
        freeQuietly(current)
    }

    private fun freeQuietly(bank: B) {
        try {
            free(bank)
        } catch (_: Exception) {
            // Nothing left to do: the platform reclaims the rest with the process.
        }
    }
}

/** Common body of the `actual` [rememberSoundPlayer]: creates, loads and releases with the composition. */
@Composable
internal fun rememberBankedSoundPlayer(enabled: Boolean, create: () -> BankedSoundPlayer<*>): SoundPlayer {
    val player = remember(enabled) { if (enabled) create() else SilentSoundPlayer }
    DisposableEffect(player) {
        (player as? BankedSoundPlayer<*>)?.load()
        onDispose { player.release() }
    }
    return player
}
