package epheis.lyphossyss.kysheon.link.ble

/** Dedicated GATT layout, shared by the Android and iOS implementations. Random v4 UUIDs generated for Kysheon. */
object BleUuids {
    const val SERVICE: String = "86caa7d9-44c1-4653-8db9-fc27ae64451d"

    /** Guest → host, write without response. */
    const val RX: String = "906d3743-b5d7-4e1a-bc5c-ea250c58d5c9"

    /** Host → guest, notify (with its CCCD 0x2902 on Android). */
    const val TX: String = "5e7e6ae5-fd33-4fe2-b72f-83e65e8dceec"

    const val CCCD: String = "00002902-0000-1000-8000-00805f9b34fb"

    /** Requested on Android; frames never need more than 20 bytes anyway (protocol guarantee). */
    const val REQUESTED_MTU: Int = 247
}
