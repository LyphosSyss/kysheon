package epheis.lyphossyss.kysheon.link.session

import epheis.lyphossyss.kysheon.link.LostReason
import epheis.lyphossyss.kysheon.link.memory.LinkConditions
import epheis.lyphossyss.kysheon.link.memory.MemoryLinkPair
import epheis.lyphossyss.kysheon.link.protocol.EndReason
import epheis.lyphossyss.kysheon.link.protocol.Message
import epheis.lyphossyss.kysheon.link.protocol.MessageCodec
import epheis.lyphossyss.kysheon.link.protocol.Role
import epheis.lyphossyss.kysheon.link.time.MonotonicClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {

    private val hostClockOffset = 987_654_321L

    private class Rig(
        val pair: MemoryLinkPair,
        val host: Session,
        val guest: Session,
        val hostClock: MonotonicClock,
    )

    private fun TestScope.trueMicros() = testScheduler.currentTime * 1_000

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private suspend fun TestScope.rig(
        conditions: LinkConditions = LinkConditions(latency = 50.milliseconds, jitter = 20.milliseconds),
        guestRole: Role = Role.Guest,
    ): Rig {
        val pair = MemoryLinkPair(backgroundScope, MonotonicClock { trueMicros() }, conditions, Random(3))
        pair.host.host()
        assertTrue(pair.guest.join())
        val hostClock = MonotonicClock { trueMicros() + hostClockOffset }
        val host = Session(pair.host, hostClock, Role.Host, localAspectMilli = 2000)
        val guest = Session(pair.guest, MonotonicClock { trueMicros() }, guestRole, localAspectMilli = 2222)
        backgroundScope.launch { host.run() }
        backgroundScope.launch { guest.run() }
        runCurrent()
        return Rig(pair, host, guest, hostClock)
    }

    /** Virtual time (ms) at which each state was entered. */
    private fun TestScope.history(session: Session): MutableList<Pair<Long, SessionState>> {
        val entries = mutableListOf<Pair<Long, SessionState>>()
        session.state.onEach { entries += testScheduler.currentTime to it }.launchIn(backgroundScope)
        return entries
    }

    private suspend fun TestScope.startGame(rig: Rig) {
        advance(2_000)
        rig.host.ready()
        rig.guest.ready()
        advance(5_000)
        assertEquals(SessionState.Running, rig.host.state.value)
        assertEquals(SessionState.Running, rig.guest.state.value)
    }

    @Test
    fun handshakeAgreesOnTheSmallestAspect() = runTest {
        val rig = rig()
        advance(500)
        assertEquals(SessionState.Lobby, rig.host.state.value)
        assertEquals(SessionState.Lobby, rig.guest.state.value)
        assertEquals(2000, rig.host.agreedAspectMilli.value)
        assertEquals(2000, rig.guest.agreedAspectMilli.value)
    }

    @Test
    fun guestGameClockConvergesOnTheHostClock() = runTest {
        val rig = rig()
        advance(2_000)
        val error = rig.guest.gameClock.nowMicros() - rig.hostClock.nowMicros()
        assertTrue(abs(error) <= 10_000, "guest clock off by $error µs with 20 ms of jitter")
        assertTrue(rig.guest.sync.value.bursts >= 1)
        assertEquals(rig.host.gameClock.nowMicros(), rig.hostClock.nowMicros(), "the host is the reference")
    }

    @Test
    fun bothHalvesStartTogether() = runTest {
        val rig = rig()
        val hostHistory = history(rig.host)
        val guestHistory = history(rig.guest)
        startGame(rig)

        val hostStart = hostHistory.first { it.second == SessionState.Running }.first
        val guestStart = guestHistory.first { it.second == SessionState.Running }.first
        assertTrue(abs(hostStart - guestStart) <= 12, "host started at $hostStart ms, guest at $guestStart ms")
        assertIs<SessionState.Countdown>(guestHistory.first { it.second is SessionState.Countdown }.second)
    }

    @Test
    fun silencePausesThenBothResume() = runTest {
        val rig = rig()
        startGame(rig)

        rig.pair.silenced = true
        advance(4_100)
        assertIs<SessionState.Paused>(rig.host.state.value)
        assertIs<SessionState.Paused>(rig.guest.state.value)

        rig.pair.silenced = false
        advance(3_000)
        assertEquals(SessionState.Running, rig.host.state.value)
        assertEquals(SessionState.Running, rig.guest.state.value)
    }

    @Test
    fun longSilenceAbortsBothSides() = runTest {
        val rig = rig()
        startGame(rig)

        rig.pair.silenced = true
        advance(6_500)
        assertIs<SessionState.Paused>(rig.host.state.value, "still paused before 8 s of silence")
        advance(1_700)
        assertEquals(SessionState.Aborted(AbortReason.PeerSilent), rig.host.state.value)
        assertEquals(SessionState.Aborted(AbortReason.PeerSilent), rig.guest.state.value)
    }

    @Test
    fun bluetoothCutAbortsAtOnceLocallyAndAfterTheWatchdogRemotely() = runTest {
        val rig = rig()
        startGame(rig)

        rig.pair.cut(Role.Guest, peerNoticesAfter = null)
        runCurrent()
        assertEquals(SessionState.Aborted(AbortReason.LinkLost(LostReason.BluetoothOff)), rig.guest.state.value)
        advance(4_100)
        assertIs<SessionState.Paused>(rig.host.state.value)
        advance(5_000)
        assertEquals(SessionState.Aborted(AbortReason.PeerSilent), rig.host.state.value)
    }

    @Test
    fun incompatibleVersionAbortsCleanly() = runTest {
        val pair = MemoryLinkPair(backgroundScope, MonotonicClock { trueMicros() })
        pair.host.host()
        assertTrue(pair.guest.join())
        val guest = Session(pair.guest, MonotonicClock { trueMicros() }, Role.Guest, 2000)
        backgroundScope.launch { guest.run() }
        runCurrent()

        pair.host.send(byteArrayOf(1, 2, 0, 0x07, 0xD0.toByte(), 0, 0))
        advance(10)
        assertEquals(SessionState.Aborted(AbortReason.IncompatibleVersion(2)), guest.state.value)
    }

    @Test
    fun twoHostsRefuseEachOther() = runTest {
        val rig = rig(guestRole = Role.Host)
        advance(500)
        assertEquals(SessionState.Aborted(AbortReason.RoleConflict), rig.host.state.value)
        assertEquals(SessionState.Aborted(AbortReason.RoleConflict), rig.guest.state.value)
    }

    @Test
    fun gameMessagesReachThePeerWhileRunning() = runTest {
        val rig = rig()
        val received = mutableListOf<Message>()
        rig.guest.gameMessages.onEach { received += it }.launchIn(backgroundScope)

        val handoff = Message.Handoff(projectileId = 3, yQ = 100, vxQ = 900, vyQ = 0, exitAtHostMicros = 42)
        rig.host.sendGame(handoff)
        advance(500)
        assertTrue(received.isEmpty(), "not running yet: dropped")

        startGame(rig)
        rig.host.sendGame(handoff)
        advance(500)
        assertEquals(listOf<Message>(handoff), received)
    }

    @Test
    fun endThenRematch() = runTest {
        val rig = rig()
        val atHost = mutableListOf<Message>()
        rig.host.gameMessages.onEach { atHost += it }.launchIn(backgroundScope)
        startGame(rig)

        rig.guest.end(winner = Role.Host, reason = EndReason.KnockOut)
        assertEquals(SessionState.Ended(Role.Host, EndReason.KnockOut), rig.guest.state.value)
        advance(500)
        assertEquals(SessionState.Ended(Role.Host, EndReason.KnockOut), rig.host.state.value)
        assertEquals(listOf<Message>(Message.End(Role.Host, EndReason.KnockOut)), atHost)

        rig.host.ready()
        rig.guest.ready()
        advance(5_000)
        assertEquals(SessionState.Running, rig.host.state.value)
        assertEquals(SessionState.Running, rig.guest.state.value)
    }

    @Test
    fun readyDuringAGameMakesTheHostScheduleAResume() = runTest {
        val rig = rig()
        val hostHistory = history(rig.host)
        startGame(rig)
        val countdownsBefore = hostHistory.count { it.second is SessionState.Countdown }

        rig.pair.guest.send(MessageCodec.encode(Message.Ready))
        advance(3_000)

        assertEquals(countdownsBefore + 1, hostHistory.count { it.second is SessionState.Countdown })
        assertEquals(SessionState.Running, rig.host.state.value)
        assertEquals(SessionState.Running, rig.guest.state.value)
    }
}
