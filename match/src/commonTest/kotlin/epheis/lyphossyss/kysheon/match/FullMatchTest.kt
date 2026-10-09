package epheis.lyphossyss.kysheon.match

import epheis.lyphossyss.kysheon.link.memory.LinkConditions
import epheis.lyphossyss.kysheon.link.memory.MemoryLinkPair
import epheis.lyphossyss.kysheon.link.protocol.EndReason
import epheis.lyphossyss.kysheon.link.protocol.Role
import epheis.lyphossyss.kysheon.link.session.Session
import epheis.lyphossyss.kysheon.link.session.SessionState
import epheis.lyphossyss.kysheon.link.time.MonotonicClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Acceptance test: two complete devices (session + simulation) play a whole match over a MemoryLink
 * with 50 ms latency and 20 ms jitter, on virtual time, without any hardware.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FullMatchTest {

    private class Device(val session: Session, val match: MatchController) {
        val feedback = mutableListOf<Feedback>()
    }

    private fun TestScope.trueMicros() = testScheduler.currentTime * 1_000

    private suspend fun TestScope.devices(conditions: LinkConditions): Pair<Device, Device> {
        val pair = MemoryLinkPair(backgroundScope, MonotonicClock { trueMicros() }, conditions, Random(5))
        pair.host.host()
        assertTrue(pair.guest.join())
        // Unrelated clock origins, like two phones booted at different times.
        val host = Session(pair.host, MonotonicClock { trueMicros() + 3_141_592_653L }, Role.Host, localAspectMilli = 2_100)
        val guest = Session(pair.guest, MonotonicClock { trueMicros() + 42L }, Role.Guest, localAspectMilli = 2_222)
        val devices = Device(host, MatchController(host)) to Device(guest, MatchController(guest))
        for (device in devices.toList()) {
            backgroundScope.launch { device.session.run() }
            backgroundScope.launch { device.match.run() }
        }
        runCurrent()
        return devices
    }

    /** Renders at 60 Hz and plays: stays in the middle and fires every [fireEveryMs]. */
    private fun TestScope.bot(device: Device, fireEveryMs: Long) {
        backgroundScope.launch {
            var sinceShot = fireEveryMs
            while (true) {
                delay(16)
                device.match.frame(device.session.gameClock.nowMicros())
                while (true) device.feedback += device.match.pollFeedback() ?: break
                device.match.simulation?.let { device.match.moveFighterTo(it.aspect / 2) }
                sinceShot += 16
                if (sinceShot >= fireEveryMs && device.match.fire()) sinceShot = 0
            }
        }
    }

    private suspend fun TestScope.playRound(host: Device, guest: Device): Pair<SessionState.Ended, SessionState.Ended> {
        host.session.ready()
        guest.session.ready()
        withTimeout(10.seconds) { host.session.state.first { it == SessionState.Running } }
        withTimeout(120.seconds) { host.session.state.first { it is SessionState.Ended } }
        withTimeout(5.seconds) { guest.session.state.first { it is SessionState.Ended } }
        // Crossing END messages may still turn a first result into a draw: read the settled states.
        advanceTimeBy(1_000)
        runCurrent()
        return host.session.state.value as SessionState.Ended to guest.session.state.value as SessionState.Ended
    }

    @Test
    fun completeMatchWith50msLatencyAnd20msJitter() = runTest {
        val (host, guest) = devices(LinkConditions(latency = 50.milliseconds, jitter = 20.milliseconds))
        advanceTimeBy(2_000)
        runCurrent()
        bot(host, fireEveryMs = 250)
        bot(guest, fireEveryMs = 1_500)

        val (hostEnd, guestEnd) = playRound(host, guest)

        assertEquals(SessionState.Ended(Role.Host, EndReason.KnockOut), hostEnd)
        assertEquals(hostEnd, guestEnd, "both halves agree on the result")

        val hostSim = assertNotNull(host.match.simulation)
        val guestSim = assertNotNull(guest.match.simulation)
        assertEquals(0, guestSim.hp, "the guest fighter fell")
        assertEquals(0, hostSim.opponentHp, "the host saw every hit it scored")
        assertEquals(hostSim.hp, guestSim.opponentHp, "the guest saw every hit it scored")
        assertEquals(3, guest.match.counters.hitsSent)
        assertEquals(guest.match.counters.hitsSent, host.match.counters.hitsReceived)
        assertEquals(host.match.counters.hitsSent, guest.match.counters.hitsReceived)
        assertTrue(host.match.counters.handoffsSent > 0)
        assertEquals(0, host.match.counters.handoffsRejected + guest.match.counters.handoffsRejected)
        assertEquals(2_100, host.session.agreedAspectMilli.value)
        assertEquals(2_100, guest.session.agreedAspectMilli.value)
        assertEquals(0L, host.session.malformedFrames + guest.session.malformedFrames)

        // Presentation feedback mirrors the game: 3 hits taken by the guest, 3 dealt by the host, one fall.
        assertEquals(3, guest.feedback.count { it is Feedback.HitTaken })
        assertEquals(listOf(2, 1, 0), guest.feedback.filterIsInstance<Feedback.HitTaken>().map { it.hpLeft })
        assertEquals(3, host.feedback.count { it is Feedback.HitDealt })
        assertEquals(1, guest.feedback.count { it is Feedback.FighterDown })
        assertEquals(host.match.counters.handoffsSent, host.feedback.count { it is Feedback.Exited })
        assertEquals(guest.match.counters.handoffsReceived, guest.feedback.count { it is Feedback.Entered })
        assertTrue(host.feedback.count { it is Feedback.Fired } >= host.match.counters.handoffsSent)
    }

    @Test
    fun rematchStartsAFreshRound() = runTest {
        val (host, guest) = devices(LinkConditions(latency = 50.milliseconds, jitter = 20.milliseconds))
        advanceTimeBy(2_000)
        runCurrent()
        bot(host, fireEveryMs = 1_500)
        bot(guest, fireEveryMs = 250)

        val first = playRound(host, guest)
        assertEquals(Role.Guest, first.first.winner)
        val firstSimulation = host.match.simulation

        val second = playRound(host, guest)
        assertEquals(second.first, second.second)
        assertTrue(host.match.simulation !== firstSimulation, "a new simulation for the new round")
    }

    @Test
    fun symmetricPlayEndsWithTheSameResultOnBothSides() = runTest {
        val (host, guest) = devices(LinkConditions(latency = 50.milliseconds, jitter = 20.milliseconds))
        advanceTimeBy(2_000)
        runCurrent()
        bot(host, fireEveryMs = 250)
        bot(guest, fireEveryMs = 250)

        val (hostEnd, guestEnd) = playRound(host, guest)

        // Simultaneous knock-outs are a draw; whatever the outcome, both halves must agree.
        assertEquals(hostEnd, guestEnd)
    }
}
