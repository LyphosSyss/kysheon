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

/**
 * Game moments the presentation reacts to (particles, sound, haptics). Positions are in logical units of the local
 * half (x in [0, 1], y in [0, aspect]). A few per second at most: allocating them is fine.
 */
sealed interface Feedback {
    /** The local fighter fired. */
    data class Fired(val x: Double, val y: Double) : Feedback

    /** A projectile of the opponent crossed the seam into the local half. */
    data class Entered(val x: Double, val y: Double) : Feedback

    /** A local projectile left for the other half. */
    data class Exited(val x: Double, val y: Double) : Feedback

    /** The local fighter was hit. */
    data class HitTaken(val x: Double, val y: Double, val hpLeft: Int) : Feedback

    /** The peer reported that one of our projectiles hit its fighter. */
    data class HitDealt(val opponentHpLeft: Int) : Feedback

    /** The local fighter fell. */
    data class FighterDown(val x: Double, val y: Double) : Feedback
}

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

    /** Whole steps dropped by the catch-up cap (render stalls), for the debug overlay. */
    val droppedSteps: Long get() = loop.droppedSteps

    var counters: MatchCounters = MatchCounters()
        private set

    private val pendingFeedback = ArrayDeque<Feedback>()

    /** Next game moment to present, oldest first; null when there is none. Called by the renderer every frame. */
    fun pollFeedback(): Feedback? = pendingFeedback.removeFirstOrNull()

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
        val sim = simulation ?: return false
        val fired = sim.fire()
        if (fired) pendingFeedback.addLast(Feedback.Fired(sim.fighterX, sim.fighterY))
        return fired
    }

    private fun onSessionState(state: SessionState) {
        when (state) {
            is SessionState.Countdown -> {
                if (!roundActive) {
                    pendingFeedback.clear()
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
                if (accepted) {
                    val y = Quant.decodeY(message.yQ) * sim.aspect
                    pendingFeedback.addLast(Feedback.Entered(side.borderX, y))
                }
                counters = if (accepted) {
                    counters.copy(handoffsReceived = counters.handoffsReceived + 1)
                } else {
                    counters.copy(handoffsRejected = counters.handoffsRejected + 1)
                }
                drain(sim)
            }
            is Message.Hit -> {
                val opponentHpLeft = sim.onOpponentHit()
                pendingFeedback.addLast(Feedback.HitDealt(opponentHpLeft))
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
                    pendingFeedback.addLast(Feedback.Exited(side.borderX, events.yNormalized(i) * sim.aspect))
                }
                EventKind.FighterHit -> {
                    outgoing.trySend(Message.Hit(events.projectileId(i), simMicros))
                    counters = counters.copy(hitsSent = counters.hitsSent + 1)
                    pendingFeedback.addLast(Feedback.HitTaken(sim.fighterX, sim.fighterY, sim.hp))
                }
                EventKind.FighterDown -> {
                    outgoing.trySend(Message.End(session.role.opposite, EndReason.KnockOut))
                    pendingFeedback.addLast(Feedback.FighterDown(sim.fighterX, sim.fighterY))
                }
                EventKind.ProjectileMissed -> Unit
            }
        }
        events.clear()
    }
}
