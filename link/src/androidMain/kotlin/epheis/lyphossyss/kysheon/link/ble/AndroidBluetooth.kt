package epheis.lyphossyss.kysheon.link.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Bluetooth checks for the UI, to run before [AndroidBleLink.host] / [AndroidBleLink.join].
 *
 * Typical flow: if [availability] is [BluetoothAvailability.Unauthorized], request [requiredPermissions]
 * (for instance with `ActivityResultContracts.RequestMultiplePermissions`); if it is [BluetoothAvailability.Off],
 * ask the user to switch Bluetooth on (`BluetoothAdapter.ACTION_REQUEST_ENABLE`, which itself needs
 * BLUETOOTH_CONNECT on Android 12+).
 */
object AndroidBluetooth {

    /**
     * Runtime permissions needed by both roles. Android 12+ (API 31): BLUETOOTH_SCAN, BLUETOOTH_CONNECT,
     * BLUETOOTH_ADVERTISE. Below: ACCESS_FINE_LOCATION (BLE scanning requires it; BLUETOOTH and BLUETOOTH_ADMIN are
     * install-time permissions declared by the library manifest).
     */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /**
     * No adapter or no BLE feature → [BluetoothAvailability.Unsupported]; a permission of [requiredPermissions]
     * missing → [BluetoothAvailability.Unauthorized]; adapter disabled → [BluetoothAvailability.Off].
     *
     * Android 8 to 11 only: scanning also needs the system location setting switched on, which this function
     * does not check (neither test phone runs these versions, see docs/MANUAL-TESTS.md).
     */
    fun availability(context: Context): BluetoothAvailability {
        val adapter = adapterOrNull(context) ?: return BluetoothAvailability.Unsupported
        if (missingPermissions(context, requiredPermissions().asList()).isNotEmpty()) {
            return BluetoothAvailability.Unauthorized
        }
        return try {
            if (adapter.isEnabled) BluetoothAvailability.Ready else BluetoothAvailability.Off
        } catch (_: SecurityException) {
            BluetoothAvailability.Unauthorized
        }
    }

    /** Adapter of a device with BLE, or null. */
    internal fun adapterOrNull(context: Context): BluetoothAdapter? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return null
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        return manager.adapter
    }

    /** Permissions the host role (GATT server + advertiser) needs. */
    internal fun hostPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyList()
        }

    /** Permissions the guest role (scanner + GATT client) needs. */
    internal fun guestPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    internal fun missingPermissions(context: Context, permissions: List<String>): List<String> =
        permissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
}
