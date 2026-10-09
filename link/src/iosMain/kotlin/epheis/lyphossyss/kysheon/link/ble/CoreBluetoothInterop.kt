package epheis.lyphossyss.kysheon.link.ble

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreBluetooth.CBAttribute
import platform.CoreBluetooth.CBPeer
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.create
import platform.UIKit.UIDevice
import platform.posix.memcpy

/** Parsed once: CBUUID instances are immutable and compared with `isEqual:` (Kotlin `==`). */
internal object GattUuids {
    val service: CBUUID = CBUUID.UUIDWithString(BleUuids.SERVICE)
    val rx: CBUUID = CBUUID.UUIDWithString(BleUuids.RX)
    val tx: CBUUID = CBUUID.UUIDWithString(BleUuids.TX)
}

/** ATT header of a Write Command / Handle Value Notification: opcode (1 byte) + attribute handle (2 bytes). */
internal const val ATT_HEADER_BYTES: Int = 3

/** CoreBluetooth reports an RSSI of 127 when the value is not available. */
internal const val RSSI_UNAVAILABLE: Int = 127

internal fun CBAttribute.hasUuid(uuid: CBUUID): Boolean = UUID == uuid

/**
 * Same remote device. Compares the `identifier` (an `NSUUID`, compared by value) rather than the objects, so the
 * check does not depend on CoreBluetooth handing out the same `CBPeripheral`/`CBCentral` instance in every
 * callback. Used internally only: the identifier never leaves this package.
 */
internal fun CBPeer.isSameAs(other: CBPeer?): Boolean = other != null && identifier == other.identifier

/**
 * Copies the bytes into a new `NSData` (`dataWithBytes:length:` copies, so the pinned Kotlin array is released
 * as soon as [usePinned] returns: no dangling pointer, nothing to free).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
}

/** Copies the bytes out of [NSData] into a fresh Kotlin array; the `NSData` stays owned by CoreBluetooth. */
@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val source = bytes ?: return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), source, length) }
    return out
}

/** Raw CoreBluetooth / ATT error code, for [epheis.lyphossyss.kysheon.link.LinkDiagnostics.lastPlatformCode]. */
internal val NSError.platformCode: Int get() = code.toInt()

/** Debug-overlay text: OS version and role only, never a device name or identifier. */
internal fun platformInfo(role: String): String = "iOS ${UIDevice.currentDevice.systemVersion} · $role"
