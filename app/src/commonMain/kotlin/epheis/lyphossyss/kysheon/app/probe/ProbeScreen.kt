package epheis.lyphossyss.kysheon.app.probe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import epheis.lyphossyss.kysheon.app.Screen
import epheis.lyphossyss.kysheon.app.formatMillis
import epheis.lyphossyss.kysheon.app.formatOneDecimal
import epheis.lyphossyss.kysheon.app.lostMessage
import epheis.lyphossyss.kysheon.app.platform.deviceLabel
import epheis.lyphossyss.kysheon.app.resources.Res
import epheis.lyphossyss.kysheon.app.resources.action_finish
import epheis.lyphossyss.kysheon.app.resources.decimal_separator
import epheis.lyphossyss.kysheon.app.resources.lost_code
import epheis.lyphossyss.kysheon.app.resources.probe_device
import epheis.lyphossyss.kysheon.app.resources.probe_drift
import epheis.lyphossyss.kysheon.app.resources.probe_drift_value
import epheis.lyphossyss.kysheon.app.resources.probe_elapsed
import epheis.lyphossyss.kysheon.app.resources.probe_frames
import epheis.lyphossyss.kysheon.app.resources.probe_frames_value
import epheis.lyphossyss.kysheon.app.resources.probe_hint
import epheis.lyphossyss.kysheon.app.resources.probe_incompatible
import epheis.lyphossyss.kysheon.app.resources.probe_jitter
import epheis.lyphossyss.kysheon.app.resources.probe_lost
import epheis.lyphossyss.kysheon.app.resources.probe_mtu
import epheis.lyphossyss.kysheon.app.resources.probe_offset
import epheis.lyphossyss.kysheon.app.resources.probe_offset_value
import epheis.lyphossyss.kysheon.app.resources.probe_peer_version
import epheis.lyphossyss.kysheon.app.resources.probe_rtt
import epheis.lyphossyss.kysheon.app.resources.probe_rtt_values
import epheis.lyphossyss.kysheon.app.resources.probe_samples
import epheis.lyphossyss.kysheon.app.resources.probe_title
import epheis.lyphossyss.kysheon.app.resources.value_ms
import epheis.lyphossyss.kysheon.app.resources.value_pending
import epheis.lyphossyss.kysheon.app.roleLabel
import epheis.lyphossyss.kysheon.app.stateLabel
import epheis.lyphossyss.kysheon.app.theme.KButton
import epheis.lyphossyss.kysheon.app.theme.KColors
import epheis.lyphossyss.kysheon.app.theme.KText
import epheis.lyphossyss.kysheon.link.LinkState
import epheis.lyphossyss.kysheon.link.protocol.Role
import org.jetbrains.compose.resources.stringResource

@Composable
fun ProbeScreen(screen: Screen.Probe, onFinish: () -> Unit) {
    val stats by screen.stats.collectAsState()
    val state by screen.state.collectAsState()
    val diagnostics = screen.diagnostics?.collectAsState()?.value
    val sep = stringResource(Res.string.decimal_separator)
    val pending = stringResource(Res.string.value_pending)
    fun ms(micros: Long?) = micros?.let { formatMillis(it, sep) } ?: pending

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KText(stringResource(Res.string.probe_title), size = 28.sp, weight = FontWeight.Bold)
        KText(stringResource(Res.string.probe_device, deviceLabel()), color = KColors.muted)
        KText(
            roleLabel(screen.role),
            color = if (screen.role == Role.Host) KColors.host else KColors.guest,
            weight = FontWeight.SemiBold,
        )
        KText(stateLabel(state), color = if (state == LinkState.Connected) KColors.ok else KColors.danger)
        (state as? LinkState.Lost)?.let { lost ->
            KText(lostMessage(lost), color = KColors.danger)
            lost.platformCode?.let { KText(stringResource(Res.string.lost_code, it.toString()), color = KColors.muted, size = 12.sp) }
        }
        KText(stringResource(Res.string.probe_elapsed, (stats.elapsedMicros / 1_000_000).toString()), color = KColors.muted)

        if (stats.incompatiblePeer) {
            KText(stringResource(Res.string.probe_incompatible, stats.peerVersion.toString()), color = KColors.danger)
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(KColors.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Metric(stringResource(Res.string.probe_samples), stats.samples.toString())
            Metric(
                stringResource(Res.string.probe_rtt),
                stringResource(
                    Res.string.probe_rtt_values,
                    ms(stats.rttMinMicros), ms(stats.rttP50Micros), ms(stats.rttP95Micros), ms(stats.rttMaxMicros),
                ),
            )
            Metric(stringResource(Res.string.probe_jitter), stats.jitterMicros?.let { stringResource(Res.string.value_ms, ms(it)) } ?: pending)
            Metric(
                stringResource(Res.string.probe_offset),
                if (stats.offsetMicros == null) pending
                else stringResource(Res.string.probe_offset_value, ms(stats.offsetMicros), ms(stats.offsetBoundMicros)),
            )
            Metric(
                stringResource(Res.string.probe_drift),
                stats.driftPpm?.let { stringResource(Res.string.probe_drift_value, formatOneDecimal(it, sep)) } ?: pending,
            )
            Metric(stringResource(Res.string.probe_lost), stats.lostPings.toString())
            Metric(stringResource(Res.string.probe_mtu), diagnostics?.mtu?.toString() ?: pending)
            Metric(
                stringResource(Res.string.probe_frames),
                diagnostics?.let {
                    stringResource(Res.string.probe_frames_value, it.framesSent.toString(), it.framesReceived.toString())
                } ?: pending,
            )
            Metric(stringResource(Res.string.probe_peer_version), stats.peerVersion?.toString() ?: pending)
            diagnostics?.platformInfo?.takeIf { it.isNotEmpty() }?.let {
                KText(it, color = KColors.muted, size = 11.sp, monospace = true)
            }
        }

        KText(stringResource(Res.string.probe_hint), color = KColors.muted, size = 13.sp)
        KButton(stringResource(Res.string.action_finish), onFinish, Modifier.fillMaxWidth(), accent = KColors.text)
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        KText(label, color = KColors.muted, size = 14.sp)
        KText(value, size = 14.sp, monospace = true)
    }
}
