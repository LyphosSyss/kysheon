package epheis.lyphossyss.kysheon.link

sealed interface LinkState {
    data object Idle : LinkState
    data object Advertising : LinkState
    data object Scanning : LinkState
    data object Connecting : LinkState
    data object Connected : LinkState

    /** [platformCode] is the raw GATT / CoreBluetooth code, shown in the debug overlay only. */
    data class Lost(val reason: LostReason, val platformCode: Int? = null) : LinkState
}

/** Typed on purpose: the UI maps each reason to a localized string resource. */
enum class LostReason {
    BluetoothOff,
    PermissionDenied,
    Unsupported,
    PeerNotFound,
    ConnectionFailed,
    PeerDisconnected,
}
