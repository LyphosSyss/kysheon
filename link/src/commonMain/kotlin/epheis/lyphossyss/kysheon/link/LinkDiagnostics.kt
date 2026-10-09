package epheis.lyphossyss.kysheon.link

import kotlinx.coroutines.flow.StateFlow

/**
 * Optional transport details for the debug overlay and the latency probe. Not part of the frozen [Link] contract:
 * the UI checks `link is DiagnosableLink`.
 */
interface DiagnosableLink {
    val diagnostics: StateFlow<LinkDiagnostics>
}

data class LinkDiagnostics(
    /** Negotiated ATT MTU, null while unknown. */
    val mtu: Int? = null,
    val framesSent: Long = 0,
    val framesReceived: Long = 0,
    val framesDropped: Long = 0,
    /** Highest number of frames waiting in the serialized write queue. */
    val maxQueueDepth: Int = 0,
    /** Last raw GATT / CoreBluetooth status code seen, null if none. */
    val lastPlatformCode: Int? = null,
    /** Human-readable platform details for debugging only (never shown outside the debug overlay). */
    val platformInfo: String = "",
)
