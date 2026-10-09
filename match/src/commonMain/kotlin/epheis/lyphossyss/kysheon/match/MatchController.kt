package epheis.lyphossyss.kysheon.match

import epheis.lyphossyss.kysheon.core.EventKind
import epheis.lyphossyss.kysheon.core.FixedStepLoop
import epheis.lyphossyss.kysheon.core.HalfSimulation
import epheis.lyphossyss.kysheon.core.Rules
import epheis.lyphossyss.kysheon.core.Side
import epheis.lyphossyss.kysheon.link.protocol.EndReason
import epheis.lyphossyss.kysheon.link.protocol.Message
import epheis.lyphossyss.kysheon.link.protocol.Quant
import epheis.lyphossyss.kysheon.link.protocol.Role
import epheis.lyphossyss.kysheon.link.session.Session
import epheis.lyphossyss.kysheon.link.session.SessionState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Network counters for the debug overlay and the tests. */
data class MatchCounters(
    val handoffsSent: Int = 0,
    val handoffsReceived: Int = 0,
    val handoffsRejected: Int = 0,
    val hitsSent: Int = 0,
    val hitsReceived: Int = 0,
)

/**
 * Plays one half of the match: drives the local [HalfSimulation] at a fixed step on the shared game clock and
 * translates between simulation events and protocol messages.
 *
 * - A local projectile leaving the half becomes a HANDOFF stamped with the simulated exit instant (host timebase).
 * - A HANDOFF received enters the simulation advanced by `local simulated time − exit instant` (clamped by the
 *   simulation), so it appears where it would be had it never left the screen.
 * - The receiving half decides impacts: a local FighterHit becomes a HIT, a FighterDown ends the round.
 *
 * [frame] is called by the renderer on every frame; it never suspends and never allocates per step.
 * Not thread-safe: [run] and [frame] must share a single-threaded dispatcher (Main on device).
 */
class MatchController(
    val session: Session,
    val rules: Rules = Rules(),
) {
    val side: Side = if (session.role == Role.Host) Side.Left else Side.Right

    private val loop = FixedStepLoop(rules.stepMicros)
    private val outgoing = Channel<Message>(Channel.UNLIMITED)
    private var roundActive = false

    /** Simulated time of the current step, on the host timebase. */
    private var simMicros = 0L

    /** Simulation of the current round, null before the first countdown. */
    var simulation: HalfSimulation? = null
        private set

    /** Interpolation factor between the previous and the current simulated state, for the renderer. */
    val alpha: Double get() = loop.alpha

    var counters: MatchCounters = MatchCounters()
        private set

    /** Runs until cancelled, alongside [Session.run]. */
    suspend fun run(): Nothing = coroutineScope {
        launch { session.gameMessages.collect(::onGameMessage) }
        launch {
            for (message in outgoing) {
                when (message) {
                    is Message.End -> session.end(message.winner, message.reason)
                    else -> session.sendGame(message)
                }
            }
        }
        session.state.collect(::onSessionState)
    }

    /** Advances the simulation up to [gameNowMicros] (the session game clock). Only steps while the game runs. */
    fun frame(gameNowMicros: Long) {
        if (session.state.value != SessionState.Running) return
        val sim = simulation ?: return
        loop.advanceTo(gameNowMicros) {
            sim.step()
            simMicros += rules.stepMicros
            drain(sim)
        }
    }

    fun moveFighterTo(y: Double) {
        simulation?.setFighterTarget(y)
    }

    fun fire(): Boolean {
        if (session.state.value != SessionState.Running) return false
        return simulation?.fire() ?: false
    }

    private fun onSessionState(state: SessionState) {
        when (state) {
            is SessionState.Countdown -> {
                if (!roundActive) {
                    val aspect = (session.agreedAspectMilli.value ?: return) / Quant.ASPECT_SCALE
                    simulation = HalfSimulation(side, aspect, rules)
                    roundActive = true
                }
                // New round or resume after a pause: the simulation restarts exactly at the common instant.
                loop.reset(state.atHostMicros)
                simMicros = state.atHostMicros
            }
            is SessionState.Ended, is SessionState.Aborted -> roundActive = false
            else -> Unit
        }
    }

    private fun onGameMessage(message: Message) {
        val sim = simulation ?: return
        when (message) {
            is Message.Handoff -> {
                val accepted = sim.receiveProjectile(
                    id = message.projectileId,
                    yNormalized = Quant.decodeY(message.yQ),
                    vx = Quant.decodeVelocity(message.vxQ),
                    vy = Quant.decodeVelocity(message.vyQ),
                    elapsedMicros = simMicros - message.exitAtHostMicros,
                )
                counters = if (accepted) {
                    counters.copy(handoffsReceived = counters.handoffsReceived + 1)
                } else {
                    counters.copy(handoffsRejected = counters.handoffsRejected + 1)
                }
                drain(sim)
            }
            is Message.Hit -> {
                sim.onOpponentHit()
                counters = counters.copy(hitsReceived = counters.hitsReceived + 1)
            }
            else -> Unit
        }
    }

    private fun drain(sim: HalfSimulation) {
        val events = sim.events
        for (i in 0 until events.count) {
            when (events.kind(i)) {
                EventKind.ProjectileExited -> {
                    outgoing.trySend(
                        Message.Handoff(
                            projectileId = events.projectileId(i),
                            yQ = Quant.encodeY(events.yNormalized(i)),
                            vxQ = Quant.encodeVelocity(events.vx(i)),
                            vyQ = Quant.encodeVelocity(events.vy(i)),
                            exitAtHostMicros = simMicros,
                        ),
                    )
                    counters = counters.copy(handoffsSent = counters.handoffsSent + 1)
                }
                EventKind.FighterHit -> {
                    outgoing.trySend(Message.Hit(events.projectileId(i), simMicros))
                    counters = counters.copy(hitsSent = counters.hitsSent + 1)
                }
                EventKind.FighterDown -> outgoing.trySend(Message.End(session.role.opposite, EndReason.KnockOut))
                EventKind.ProjectileMissed -> Unit
            }
        }
        events.clear()
    }
}
