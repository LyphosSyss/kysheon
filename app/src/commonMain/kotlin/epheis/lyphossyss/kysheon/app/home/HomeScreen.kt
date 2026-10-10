package epheis.lyphossyss.kysheon.app.home

import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.painterResource
import epheis.lyphossyss.kysheon.app.resources.title_banner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import epheis.lyphossyss.kysheon.app.Mode
import epheis.lyphossyss.kysheon.app.Screen
import epheis.lyphossyss.kysheon.app.noticeMessage
import epheis.lyphossyss.kysheon.app.platform.BluetoothAccess
import epheis.lyphossyss.kysheon.app.resources.Res
import epheis.lyphossyss.kysheon.app.resources.action_enable
import epheis.lyphossyss.kysheon.app.resources.action_grant
import epheis.lyphossyss.kysheon.app.resources.app_name
import epheis.lyphossyss.kysheon.app.resources.bt_off
import epheis.lyphossyss.kysheon.app.resources.bt_platform_pending
import epheis.lyphossyss.kysheon.app.resources.bt_ready
import epheis.lyphossyss.kysheon.app.resources.bt_unauthorized
import epheis.lyphossyss.kysheon.app.resources.bt_unsupported
import epheis.lyphossyss.kysheon.app.resources.home_host
import epheis.lyphossyss.kysheon.app.resources.home_join
import epheis.lyphossyss.kysheon.app.resources.home_probe_hint
import epheis.lyphossyss.kysheon.app.resources.home_tagline
import epheis.lyphossyss.kysheon.app.resources.lost_code
import epheis.lyphossyss.kysheon.app.resources.mode_game
import epheis.lyphossyss.kysheon.app.resources.mode_probe
import epheis.lyphossyss.kysheon.app.resources.action_settings
import epheis.lyphossyss.kysheon.app.Notice
import epheis.lyphossyss.kysheon.app.theme.KButton
import epheis.lyphossyss.kysheon.app.theme.KColors
import epheis.lyphossyss.kysheon.app.theme.KText
import epheis.lyphossyss.kysheon.link.ble.BluetoothAvailability
import org.jetbrains.compose.resources.stringResource

@Composable
fun HomeScreen(
    screen: Screen.Home,
    bluetooth: BluetoothAccess,
    platformSupported: Boolean,
    mode: Mode,
    onMode: (Mode) -> Unit,
    onHost: () -> Unit,
    onJoin: () -> Unit,
    onSettings: () -> Unit,
) {
    val availability by bluetooth.availability.collectAsState()
    val ready = platformSupported && availability == BluetoothAvailability.Ready

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(Res.drawable.title_banner),
            contentDescription = stringResource(Res.string.app_name),
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
        )
        KText(stringResource(Res.string.home_tagline), color = KColors.host, size = 17.sp, weight = FontWeight.SemiBold)

        if (!platformSupported) {
            KText(stringResource(Res.string.bt_platform_pending), color = KColors.danger, size = 14.sp)
        } else KText(
            text = stringResource(
                when (availability) {
                    BluetoothAvailability.Ready -> Res.string.bt_ready
                    BluetoothAvailability.Off -> Res.string.bt_off
                    BluetoothAvailability.Unauthorized -> Res.string.bt_unauthorized
                    BluetoothAvailability.Unsupported -> Res.string.bt_unsupported
                },
            ),
            color = if (ready) KColors.ok else KColors.danger,
            size = 14.sp,
        )
        if (platformSupported) when (availability) {
            BluetoothAvailability.Unauthorized ->
                KButton(stringResource(Res.string.action_grant), bluetooth::requestPermissions, accent = KColors.text)
            BluetoothAvailability.Off ->
                KButton(stringResource(Res.string.action_enable), bluetooth::requestEnable, accent = KColors.text)
            else -> Unit
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (option in Mode.entries) {
                val selected = option == mode
                KButton(
                    label = stringResource(if (option == Mode.Game) Res.string.mode_game else Res.string.mode_probe),
                    onClick = { onMode(option) },
                    modifier = Modifier.weight(1f),
                    accent = if (selected) KColors.text else KColors.surfaceHigh,
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KButton(stringResource(Res.string.home_host), onHost, Modifier.weight(1f), KColors.host, enabled = ready)
            KButton(stringResource(Res.string.home_join), onJoin, Modifier.weight(1f), KColors.guest, enabled = ready)
        }

        KButton(stringResource(Res.string.action_settings), onSettings, Modifier.fillMaxWidth())

        screen.notice?.let { notice ->
            KText(noticeMessage(notice), color = KColors.danger)
            (notice as? Notice.Lost)?.state?.platformCode?.let { code ->
                KText(stringResource(Res.string.lost_code, code.toString()), color = KColors.muted, size = 12.sp)
            }
        }
        KText(
            stringResource(Res.string.home_probe_hint),
            color = KColors.muted,
            size = 13.sp,
        )
    }
}
