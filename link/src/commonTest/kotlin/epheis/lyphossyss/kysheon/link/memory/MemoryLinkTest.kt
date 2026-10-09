package epheis.lyphossyss.kysheon.link.memory

import epheis.lyphossyss.kysheon.link.LinkState
import epheis.lyphossyss.kysheon.link.LostReason
import epheis.lyphossyss.kysheon.link.protocol.Role
import epheis.lyphossyss.kysheon.link.time.MonotonicClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryLinkTest {

    private fun TestScope.virtualClock() = MonotonicClock { testScheduler.currentTime * 1_000 }

    /** Background coroutines are not run by advanceUntilIdle: advance virtual time explicitly. */
    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    private suspend fun TestScope.connectedPair(conditions: LinkConditions = LinkConditions(), seed: Int = 0): MemoryLinkPair {
        val pair = MemoryLinkPair(backgroundScope, virtualClock(), conditions, Random(seed))
        pair.host.host()
        assertTrue(pair.guest.join())
        return pair
    }

    /** Collects frames received by [role] with their virtual arrival time in ms. */
    private fun TestScope.record(pair: MemoryLinkPair, role: Role): MutableList<Pair<Long, Int>> {
        val received = mutableListOf<Pair<Long, Int>>()
        pair.link(role).incoming
            .onEach { received += testScheduler.currentTime to it[0].toInt() }
            .launchIn(backgroundScope)
        return received
    }

    @Test
    fun connectsThroughTheExpectedStates() = runTest {
        val pair = MemoryLinkPair(backgroundScope, virtualClock())
        assertEquals(LinkState.Idle, pair.host.state.value)
        pair.host.host()
        assertEquals(LinkState.Advertising, pair.host.state.value)
        assertTrue(pair.guest.join())
        assertEquals(LinkState.Connected, pair.host.state.value)
        assertEquals(LinkState.Connected, pair.guest.state.value)
    }

    @Test
    fun joinFailsWhenNobodyHosts() = runTest {
        val pair = MemoryLinkPair(backgroundScope, virtualClock())
        val start = testScheduler.currentTime
        assertFalse(pair.guest.join())
        assertEquals(LinkState.Lost(LostReason.PeerNotFound), pair.guest.state.value)
        assertEquals(5_000, testScheduler.currentTime - start)
    }

    @Test
    fun joinWaitsForAHostThatStartsLater() = runTest {
        val pair = MemoryLinkPair(backgroundScope, virtualClock())
        val joined = async { pair.guest.join() }
        advanceTimeBy(1_000)
        pair.host.host()
        assertTrue(joined.await())
    }

    @Test
    fun deliversAfterTheConfiguredLatencyBothWays() = runTest {
        val pair = connectedPair(LinkConditions(latency = 50.milliseconds))
        val atGuest = record(pair, Role.Guest)
        val atHost = record(pair, Role.Host)
        runCurrent()
        val sentAt = testScheduler.currentTime

        pair.host.send(byteArrayOf(1))
        pair.guest.send(byteArrayOf(2))
        settle()

        assertEquals(listOf(sentAt + 50 to 1), atGuest)
        assertEquals(listOf(sentAt + 50 to 2), atHost)
    }

    @Test
    fun jitterStaysInBoundsAndOrderIsPreserved() = runTest {
        val pair = connectedPair(LinkConditions(latency = 50.milliseconds, jitter = 20.milliseconds), seed = 42)
        val atGuest = record(pair, Role.Guest)
        runCurrent()
        val sentAt = testScheduler.currentTime

        repeat(100) { pair.host.send(byteArrayOf(it.toByte())) }
        settle()

        assertEquals((0 until 100).toList(), atGuest.map { it.second })
        val delays = atGuest.map { it.first - sentAt }
        assertTrue(delays.all { it in 50..70 }, "delays out of [50, 70] ms: $delays")
        assertTrue(delays.toSet().size > 1, "jitter had no effect")
    }

    @Test
    fun lossRateDropsAboutTheRequestedShare() = runTest {
        val pair = connectedPair(LinkConditions(lossRate = 0.2), seed = 7)
        val atGuest = record(pair, Role.Guest)
        runCurrent()

        repeat(1_000) { pair.host.send(byteArrayOf(0)) }
        settle()

        assertEquals(1_000, atGuest.size + pair.host.droppedFrames)
        assertTrue(pair.host.droppedFrames in 150..250, "dropped ${pair.host.droppedFrames} of 1000")
    }

    @Test
    fun silenceDropsFramesWhileStayingConnected() = runTest {
        val pair = connectedPair()
        val atGuest = record(pair, Role.Guest)
        runCurrent()

        pair.silenced = true
        pair.host.send(byteArrayOf(1))
        settle()
        assertTrue(atGuest.isEmpty())
        assertEquals(LinkState.Connected, pair.guest.state.value)

        pair.silenced = false
        pair.host.send(byteArrayOf(2))
        settle()
        assertEquals(listOf(2), atGuest.map { it.second })
    }

    @Test
    fun cutLosesTheLocalSideAndThePeerOnlyAfterSupervision() = runTest {
        val pair = connectedPair()
        pair.cut(Role.Guest, peerNoticesAfter = 2.seconds)

        assertEquals(LinkState.Lost(LostReason.BluetoothOff), pair.guest.state.value)
        assertEquals(LinkState.Connected, pair.host.state.value)
        advanceTimeBy(2_001)
        assertEquals(LinkState.Lost(LostReason.PeerDisconnected), pair.host.state.value)
    }

    @Test
    fun cutWithoutNoticeLeavesThePeerConnectedButDeaf() = runTest {
        val pair = connectedPair()
        val atGuest = record(pair, Role.Guest)
        runCurrent()

        pair.cut(Role.Guest, peerNoticesAfter = null)
        pair.host.send(byteArrayOf(1))
        settle()

        assertEquals(LinkState.Connected, pair.host.state.value)
        assertTrue(atGuest.isEmpty())
    }

    @Test
    fun closeResetsLocallyAndDisconnectsThePeer() = runTest {
        val pair = connectedPair()
        pair.guest.close()
        assertEquals(LinkState.Idle, pair.guest.state.value)
        assertEquals(LinkState.Lost(LostReason.PeerDisconnected), pair.host.state.value)
        pair.guest.close()
        assertEquals(LinkState.Idle, pair.guest.state.value)
    }

    @Test
    fun framesAreCopiedOnSend() = runTest {
        val pair = connectedPair(LinkConditions(latency = 10.milliseconds))
        val atGuest = record(pair, Role.Guest)
        runCurrent()

        val frame = byteArrayOf(1)
        pair.host.send(frame)
        frame[0] = 99
        settle()

        assertEquals(listOf(1), atGuest.map { it.second })
    }
}
