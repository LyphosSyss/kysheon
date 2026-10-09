package epheis.lyphossyss.kysheon.link

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Ordered, unacknowledged frame transport between exactly two devices.
 *
 * Implementations: [epheis.lyphossyss.kysheon.link.memory.MemoryLinkPair] (tests), `AndroidBleLink`, `IosBleLink`.
 * Failures never escape as exceptions: they are reported through [state] as [LinkState.Lost].
 *
 * This contract is frozen (see AGENTS.md). Changing it requires a written decision.
 */
interface Link {

    /** Frames received from the peer, in arrival order. Meant for a single collector. */
    val incoming: Flow<ByteArray>

    val state: StateFlow<LinkState>

    /**
     * Becomes discoverable (peripheral role, GATT server). Returns once advertising has started or failed;
     * [state] then moves to [LinkState.Connected] when a peer joins.
     */
    suspend fun host()

    /** Scans, picks the closest host and connects (central role). Returns true once [state] is Connected. */
    suspend fun join(): Boolean

    /**
     * Queues a frame for the peer. Size must be ≤ MTU − 3; the protocol guarantees ≤ 20 bytes.
     * Platform writes are serialized. Frames sent while not Connected are dropped.
     */
    suspend fun send(frame: ByteArray)

    /** Idempotent. Releases radio resources; [state] goes back to [LinkState.Idle]. */
    suspend fun close()
}
