package epheis.lyphossyss.kysheon.app.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import epheis.lyphossyss.kysheon.app.Screen
import epheis.lyphossyss.kysheon.app.debug.DebugOverlay
import epheis.lyphossyss.kysheon.app.render.ArenaRenderer
import epheis.lyphossyss.kysheon.app.render.FrameStats
import epheis.lyphossyss.kysheon.app.render.Letterbox
import epheis.lyphossyss.kysheon.app.resources.Res
import epheis.lyphossyss.kysheon.app.resources.action_debug
import epheis.lyphossyss.kysheon.app.resources.action_leave
import epheis.lyphossyss.kysheon.app.resources.action_rematch
import epheis.lyphossyss.kysheon.app.resources.game_by_forfeit
import epheis.lyphossyss.kysheon.app.resources.game_draw
import epheis.lyphossyss.kysheon.app.resources.game_hint
import epheis.lyphossyss.kysheon.app.resources.game_lose
import epheis.lyphossyss.kysheon.app.resources.game_paused
import epheis.lyphossyss.kysheon.app.resources.game_waiting_peer
import epheis.lyphossyss.kysheon.app.resources.game_win
import epheis.lyphossyss.kysheon.app.theme.KButton
import epheis.lyphossyss.kysheon.app.theme.KColors
import epheis.lyphossyss.kysheon.app.theme.KText
import epheis.lyphossyss.kysheon.link.protocol.EndReason
import epheis.lyphossyss.kysheon.link.protocol.Role
import epheis.lyphossyss.kysheon.link.session.SessionState
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

@Composable
fun GameScreen(screen: Screen.Game, onRematch: () -> Unit, onLeave: () -> Unit) {
    val session = screen.session
    val match = screen.match
    val state by session.state.collectAsState()
    val renderer = remember(match) { ArenaRenderer(match.side) }
    val frameStats = remember(match) { FrameStats() }
    var frameNanos by remember { mutableLongStateOf(0L) }
    var showDebug by remember { mutableStateOf(false) }
    var rematchRequested by remember(match) { mutableStateOf(false) }

    // Simulation and rendering are decoupled: the loop steps the simulation at 60 Hz on the shared clock,
    // whatever the display rate; the canvas only interpolates. Only the draw phase is invalidated per frame.
    LaunchedEffect(match) {
        while (true) {
            withFrameNanos { nanos ->
                frameStats.onFrame(nanos)
                match.frame(session.gameClock.nowMicros())
                frameNanos = nanos
            }
        }
    }
    LaunchedEffect(state) {
        if (state !is SessionState.Ended) rematchRequested = false
    }

    Box(Modifier.fillMaxSize().background(KColors.background)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(match) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val aspect = match.simulation?.aspect ?: return@awaitEachGesture
                        val box = Letterbox(size.width.toFloat(), size.height.toFloat(), aspect)
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!dragging && abs(change.position.y - down.position.y) > viewConfiguration.touchSlop) {
                                dragging = true
                            }
                            if (dragging) {
                                match.moveFighterTo(box.logicalY(change.position.y))
                                change.consume()
                            }
                            if (!change.pressed) break
                        }
                        // A touch that did not drag is a shot.
                        if (!dragging) match.fire()
                    }
                },
        ) {
            val sim = match.simulation ?: return@Canvas
            val frame = frameNanos // read in the draw phase: redraws every frame without recomposing
            with(renderer) {
                val roundOver = session.state.value is SessionState.Ended
                drawArena(sim, match.alpha, Letterbox(size.width, size.height, sim.aspect), frame, showProjectiles = !roundOver)
            }
        }

        StatusOverlay(
            state = state,
            isLocalWinner = { winner -> winner == session.role },
            nowHostMicros = { session.gameClock.nowMicros() },
            frameNanos = { frameNanos },
            rematchRequested = rematchRequested,
            onRematch = {
                rematchRequested = true
                onRematch()
            },
            onLeave = onLeave,
        )

        Column(Modifier.align(Alignment.TopCenter).padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            KButton(
                stringResource(Res.string.action_debug),
                onClick = { showDebug = !showDebug },
                accent = if (showDebug) KColors.text else KColors.surfaceHigh,
            )
        }
        if (showDebug) {
            DebugOverlay(screen, frameStats, Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
    }
}

@Composable
private fun StatusOverlay(
    state: SessionState,
    isLocalWinner: (Role) -> Boolean,
    nowHostMicros: () -> Long,
    frameNanos: () -> Long,
    rematchRequested: Boolean,
    onRematch: () -> Unit,
    onLeave: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        when (state) {
            SessionState.Handshaking, SessionState.Lobby ->
                KText(stringResource(Res.string.game_waiting_peer), color = KColors.muted)
            is SessionState.Countdown -> {
                // Reading the frame state here recomposes only this overlay, and only during the countdown.
                frameNanos()
                val remainingMicros = state.atHostMicros - nowHostMicros()
                val seconds = ((remainingMicros + 999_999) / 1_000_000).coerceAtLeast(0)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (seconds > 0) KText(seconds.toString(), size = 72.sp, weight = FontWeight.Bold)
                    KText(stringResource(Res.string.game_hint), color = KColors.muted)
                }
            }
            is SessionState.Paused -> KText(stringResource(Res.string.game_paused), color = KColors.danger, size = 20.sp)
            is SessionState.Ended -> ResultPanel(state, isLocalWinner, rematchRequested, onRematch, onLeave)
            SessionState.Running, is SessionState.Aborted -> Unit
        }
    }
}

@Composable
private fun ResultPanel(
    state: SessionState.Ended,
    isLocalWinner: (Role) -> Boolean,
    rematchRequested: Boolean,
    onRematch: () -> Unit,
    onLeave: () -> Unit,
) {
    Column(
        Modifier.background(KColors.surface.copy(alpha = 0.92f)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val winner = state.winner
        val (title, color) = when {
            winner == null -> stringResource(Res.string.game_draw) to KColors.text
            isLocalWinner(winner) -> stringResource(Res.string.game_win) to KColors.ok
            else -> stringResource(Res.string.game_lose) to KColors.danger
        }
        KText(title, color = color, size = 40.sp, weight = FontWeight.Bold)
        if (state.reason == EndReason.Forfeit) KText(stringResource(Res.string.game_by_forfeit), color = KColors.muted)
        if (rematchRequested) {
            KText(stringResource(Res.string.game_waiting_peer), color = KColors.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KButton(stringResource(Res.string.action_rematch), onRematch, accent = KColors.text, enabled = !rematchRequested)
            KButton(stringResource(Res.string.action_leave), onLeave)
        }
    }
}
