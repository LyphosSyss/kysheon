package epheis.lyphossyss.kysheon.link.ble

/**
 * Whether the local Bluetooth LE radio can be used right now, checked by the UI before `host()` / `join()`.
 * Shared by the Android and iOS implementations; the UI maps each value to a localized message.
 */
enum class BluetoothAvailability {
    /** Adapter present, switched on, permissions granted. */
    Ready,

    /** Adapter present but switched off. */
    Off,

    /** A required runtime permission is missing (or was revoked). */
    Unauthorized,

    /** No Bluetooth LE hardware on this device. */
    Unsupported,
}
