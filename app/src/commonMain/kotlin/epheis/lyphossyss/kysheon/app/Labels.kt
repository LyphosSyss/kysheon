package epheis.lyphossyss.kysheon.app

import androidx.compose.runtime.Composable
import epheis.lyphossyss.kysheon.app.resources.Res
import epheis.lyphossyss.kysheon.app.resources.abort_incompatible
import epheis.lyphossyss.kysheon.app.resources.abort_peer_silent
import epheis.lyphossyss.kysheon.app.resources.abort_role_conflict
import epheis.lyphossyss.kysheon.app.resources.lost_bluetooth_off
import epheis.lyphossyss.kysheon.app.resources.lost_connection_failed
import epheis.lyphossyss.kysheon.app.resources.lost_not_found
import epheis.lyphossyss.kysheon.app.resources.lost_peer
import epheis.lyphossyss.kysheon.app.resources.lost_permission
import epheis.lyphossyss.kysheon.app.resources.lost_unsupported
import epheis.lyphossyss.kysheon.app.resources.role_guest
import epheis.lyphossyss.kysheon.app.resources.role_host
import epheis.lyphossyss.kysheon.app.resources.state_advertising
import epheis.lyphossyss.kysheon.app.resources.state_connected
import epheis.lyphossyss.kysheon.app.resources.state_connecting
import epheis.lyphossyss.kysheon.app.resources.state_idle
import epheis.lyphossyss.kysheon.app.resources.state_lost
import epheis.lyphossyss.kysheon.app.resources.state_scanning
import epheis.lyphossyss.kysheon.link.LinkState
import epheis.lyphossyss.kysheon.link.LostReason
import epheis.lyphossyss.kysheon.link.protocol.Role
import epheis.lyphossyss.kysheon.link.session.AbortReason
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToLong

@Composable
fun stateLabel(state: LinkState): String = stringResource(
    when (state) {
        LinkState.Idle -> Res.string.state_idle
        LinkState.Advertising -> Res.string.state_advertising
        LinkState.Scanning -> Res.string.state_scanning
        LinkState.Connecting -> Res.string.state_connecting
        LinkState.Connected -> Res.string.state_connected
        is LinkState.Lost -> Res.string.state_lost
    },
)

@Composable
fun lostMessage(lost: LinkState.Lost): String = stringResource(
    when (lost.reason) {
        LostReason.BluetoothOff -> Res.string.lost_bluetooth_off
        LostReason.PermissionDenied -> Res.string.lost_permission
        LostReason.Unsupported -> Res.string.lost_unsupported
        LostReason.PeerNotFound -> Res.string.lost_not_found
        LostReason.ConnectionFailed -> Res.string.lost_connection_failed
        LostReason.PeerDisconnected -> Res.string.lost_peer
    },
)

@Composable
fun noticeMessage(notice: Notice): String = when (notice) {
    is Notice.Lost -> lostMessage(notice.state)
    is Notice.Aborted -> when (val reason = notice.reason) {
        is AbortReason.IncompatibleVersion -> stringResource(Res.string.abort_incompatible, reason.remoteVersion.toString())
        AbortReason.RoleConflict -> stringResource(Res.string.abort_role_conflict)
        AbortReason.PeerSilent -> stringResource(Res.string.abort_peer_silent)
        is AbortReason.LinkLost -> lostMessage(LinkState.Lost(reason.reason))
    }
}

@Composable
fun roleLabel(role: Role): String = stringResource(if (role == Role.Host) Res.string.role_host else Res.string.role_guest)

/** Microseconds → milliseconds with one decimal, using the locale's decimal [separator]. */
fun formatMillis(micros: Long, separator: String): String {
    val tenths = (abs(micros) + 50) / 100
    val sign = if (micros < 0 && tenths != 0L) "-" else ""
    return "$sign${tenths / 10}$separator${tenths % 10}"
}

/** One decimal, using the locale's decimal [separator]. */
fun formatOneDecimal(value: Double, separator: String): String {
    val tenths = (abs(value) * 10).roundToLong()
    val sign = if (value < 0 && tenths != 0L) "-" else ""
    return "$sign${tenths / 10}$separator${tenths % 10}"
}
