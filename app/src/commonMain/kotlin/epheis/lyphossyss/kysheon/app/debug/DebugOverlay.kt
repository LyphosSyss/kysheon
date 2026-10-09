package epheis.lyphossyss.kysheon.app.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import epheis.lyphossyss.kysheon.app.Screen
import epheis.lyphossyss.kysheon.app.formatMillis
import epheis.lyphossyss.kysheon.app.render.FrameStats
import epheis.lyphossyss.kysheon.app.resources.Res
import epheis.lyphossyss.kysheon.app.resources.debug_bursts
import epheis.lyphossyss.kysheon.app.resources.debug_dropped_steps
import epheis.lyphossyss.kysheon.app.resources.debug_frames
import epheis.lyphossyss.kysheon.app.resources.debug_handoffs
import epheis.lyphossyss.kysheon.app.resources.debug_hits
import epheis.lyphossyss.kysheon.app.resources.debug_janky
import epheis.lyphossyss.kysheon.app.resources.debug_jitter
import epheis.lyphossyss.kysheon.app.resources.debug_link
import epheis.lyphossyss.kysheon.app.resources.debug_mtu
import epheis.lyphossyss.kysheon.app.resources.debug_offset
import epheis.lyphossyss.kysheon.app.resources.debug_rtt
import epheis.lyphossyss.kysheon.app.resources.decimal_separator
import epheis.lyphossyss.kysheon.app.resources.value_ms
import epheis.lyphossyss.kysheon.app.resources.value_ms_triple
import epheis.lyphossyss.kysheon.app.resources.value_pair
import epheis.lyphossyss.kysheon.app.resources.value_pending
import epheis.lyphossyss.kysheon.app.roleLabel
import epheis.lyphossyss.kysheon.app.stateLabel
import epheis.lyphossyss.kysheon.app.theme.KColors
import epheis.lyphossyss.kysheon.app.theme.KText
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * RTT, offset, jitter, link state and frame times. Refreshed four times per second instead of every frame,
 * so the overlay itself does not disturb the frame times it reports.
 */
@Composable
fun DebugOverlay(screen: Screen.Game, frameStats: FrameStats, modifier: Modifier = Modifier) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            tick++
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    tick

    val linkState by screen.linkState.collectAsState()
    val sync by screen.session.sync.collectAsState()
    val diagnostics = screen.diagnostics?.collectAsState()?.value
    val counters = screen.match.counters
    val sep = stringResource(Res.string.decimal_separator)
    val pending = stringResource(Res.string.value_pending)
    @Composable
    fun ms(micros: Long?): String = micros?.let { stringResource(Res.string.value_ms, formatMillis(it, sep)) } ?: pending

    Column(
        modifier
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(KColors.background.copy(alpha = 0.85f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Line(stringResource(Res.string.debug_link), "${roleLabel(screen.session.role)} · ${stateLabel(linkState)}")
        Line(stringResource(Res.string.debug_rtt), ms(sync.bestRttMicros))
        Line(
            stringResource(Res.string.debug_offset),
            if (sync.appliedOffsetMicros == null) pending
            else stringResource(Res.string.value_pair, ms(sync.appliedOffsetMicros), ms(sync.targetOffsetMicros)),
        )
        Line(stringResource(Res.string.debug_jitter), ms(sync.jitterMicros))
        Line(stringResource(Res.string.debug_bursts), sync.bursts.toString())
        Line(
            stringResource(Res.string.debug_frames),
            stringResource(
                Res.string.value_ms_triple,
                formatMillis(frameStats.p50Micros, sep),
                formatMillis(frameStats.p95Micros, sep),
                formatMillis(frameStats.p99Micros, sep),
            ),
        )
        Line(stringResource(Res.string.debug_janky), frameStats.jankyFrames.toString())
        Line(stringResource(Res.string.debug_dropped_steps), screen.match.droppedSteps.toString())
        Line(
            stringResource(Res.string.debug_handoffs),
            stringResource(Res.string.value_pair, counters.handoffsSent.toString(), counters.handoffsReceived.toString()),
        )
        Line(
            stringResource(Res.string.debug_hits),
            stringResource(Res.string.value_pair, counters.hitsSent.toString(), counters.hitsReceived.toString()),
        )
        Line(stringResource(Res.string.debug_mtu), diagnostics?.mtu?.toString() ?: pending)
        diagnostics?.platformInfo?.takeIf { it.isNotEmpty() }?.let {
            KText(it, color = KColors.muted, size = 10.sp, monospace = true)
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KText(label, color = KColors.muted, size = 12.sp)
        KText(value, size = 12.sp, monospace = true)
    }
}
