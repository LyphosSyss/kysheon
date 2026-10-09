package epheis.lyphossyss.kysheon.link.session

import epheis.lyphossyss.kysheon.link.Link
import epheis.lyphossyss.kysheon.link.LinkState
import epheis.lyphossyss.kysheon.link.LostReason
import epheis.lyphossyss.kysheon.link.protocol.DecodeResult
import epheis.lyphossyss.kysheon.link.protocol.EndReason
import epheis.lyphossyss.kysheon.link.protocol.Message
import epheis.lyphossyss.kysheon.link.protocol.MessageCodec
import epheis.lyphossyss.kysheon.link.protocol.PROTOCOL_VERSION
import epheis.lyphossyss.kysheon.link.protocol.Role
import epheis.lyphossyss.kysheon.link.sync.OffsetEstimator
import epheis.lyphossyss.kysheon.link.sync.PingTracker
import epheis.lyphossyss.kysheon.link.sync.SlewedClock
import epheis.lyphossyss.kysheon.link.sync.SyncSample
import epheis.lyphossyss.kysheon.link.time.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

sealed interface SessionState {
    /** Waiting for the peer's HELLO. */
    data object Handshaking : SessionState

    /** HELLO exchanged, waiting for both players to be ready. */
    data object Lobby : SessionState

    /** START received or sent: the game (re)starts at [atHostMicros] on the host timebase. */
    data class Countdown(val atHostMicros: Long) : SessionState

    data object Running : SessionState

    /** No frame for [SessionConfig.silenceBeforePause]. [from] is the state to come back to. */
    data class Paused(val from: SessionState) : SessionState

    /**
     * The round is over; [winner] is null for a draw (both fighters fell within the network delay).
     * Not terminal: READY from both sides starts a rematch.
     */
    data class Ended(val winner: Role?, val reason: EndReason) : SessionState

    /** Terminal. The owner closes the link. */
    data class Aborted(val reason: AbortReason) : SessionState
}

sealed interface AbortReason {
    data class IncompatibleVersion(val remoteVersion: Int) : AbortReason
    data object RoleConflict : AbortReason
    data object PeerSilent : AbortReason
    data class LinkLost(val reason: LostReason) : AbortReason
}

data class SessionConfig(
    val heartbeatInterval: Duration = 1.seconds,
    val silenceBeforePause: Duration = 3.seconds,
    val pauseBeforeAbort: Duration = 5.seconds,
    val burstSize: Int = 8,
    val pongTimeout: Duration = 500.milliseconds,
    val resyncInterval: Duration = 5.seconds,
    val countdown: Duration = 3.seconds,
    val resumeDelay: Duration = 1.seconds,
    val maxSlewRate: Double = 0.05,
    val watchdogTick: Duration = 50.milliseconds,
)

/** Clock synchronization figures for the debug overlay, in microseconds. */
data class SyncStatus(
    val bursts: Int = 0,
    val bestRttMicros: Long? = null,
    val jitterMicros: Long? = null,
    val targetOffsetMicros: Long? = null,
    val appliedOffsetMicros: Long? = null,
)

/**
 * Protocol state machine on top of a connected [Link]: handshake (HELLO, version and role check, agreed aspect),
 * clock synchronization (guest pings, host answers), READY/START, heartbeat watchdog with pause and abort,
 * and routing of game messages (HANDOFF, HIT, END).
 *
 * The host clock is the reference: [gameClock] is the local clock on the host and a [SlewedClock] on the guest.
 * READY also serves as a resume request: a host that receives READY during a game answers with a new START.
 *
 * Not thread-safe: run it on a single-threaded dispatcher (Main on device, the test dispatcher in tests).
 */
class Session(
    private val link: Link,
    private val localClock: MonotonicClock,
    val role: Role,
    private val localAspectMilli: Int,
    private val config: SessionConfig = SessionConfig(),
) {
    private val slewed = SlewedClock(localClock, config.maxSlewRate)

    /** Host timebase. Every timestamp of START, HANDOFF and HIT is read from it. */
    val gameClock: MonotonicClock = if (role == Role.Host) localClock else slewed

    private val mutableState = MutableStateFlow<SessionState>(SessionState.Handshaking)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private val mutableAspect = MutableStateFlow<Int?>(null)

    /** Smallest of the two HELLO aspects (× 1000): both sides compute the same value. */
    val agreedAspectMilli: StateFlow<Int?> = mutableAspect.asStateFlow()

    private val mutableSync = MutableStateFlow(SyncStatus())
    val sync: StateFlow<SyncStatus> = mutableSync.asStateFlow()

    private val game = Channel<Message>(Channel.UNLIMITED)

    /** HANDOFF, HIT and END received from the peer. Single collector. */
    val gameMessages: Flow<Message> = game.receiveAsFlow()

    var malformedFrames: Long = 0
        private set

    private val tracker = PingTracker(localClock, config.pongTimeout.inWholeMicroseconds)
    private val pongs = Channel<SyncSample>(Channel.UNLIMITED)
    private var synced = role == Role.Host
    private var localReady = false
    private var peerReady = false
    private var lastHeardMicros = 0L
    private var heartbeatSeq = 0
    private var lastHelloSentMicros = Long.MIN_VALUE / 2
    private var scope: CoroutineScope? = null
    private var countdownJob: Job? = null

    /** Runs until [SessionState.Aborted] (or cancellation). The link must be Connected. */
    suspend fun run() = coroutineScope {
        scope = this
        lastHeardMicros = localClock.nowMicros()
        launch { link.incoming.collect(::onFrame) }
        launch {
            val lost = link.state.first { it is LinkState.Lost } as LinkState.Lost
            abort(AbortReason.LinkLost(lost.reason))
        }
        sendHello()
        launch { heartbeatLoop() }
        launch { watchdogLoop() }
        if (role == Role.Guest) launch { syncLoop() }
        state.first { it is SessionState.Aborted }
        coroutineContext.cancelChildren()
    }

    /** The local player is ready (first game or rematch). The guest sends READY only once its clock is synced. */
    suspend fun ready() {
        localReady = true
        if (synced) sendMessage(Message.Ready)
        maybeStart()
    }

    /** Sends HANDOFF or HIT. Ignored outside of a running game. */
    suspend fun sendGame(message: Message) {
        require(message is Message.Handoff || message is Message.Hit) { "not a game message: $message" }
        if (mutableState.value == SessionState.Running) sendMessage(message)
    }

    /** The local half decided the end of the round (its fighter fell, or the player forfeits). */
    suspend fun end(winner: Role, reason: EndReason) {
        if (mutableState.value is SessionState.Aborted) return
        sendMessage(Message.End(winner, reason))
        enterEnded(winner, reason)
    }

    private suspend fun onFrame(frame: ByteArray) {
        val receivedAt = localClock.nowMicros()
        lastHeardMicros = receivedAt
        if (mutableState.value is SessionState.Aborted) return
        val decoded = MessageCodec.decode(frame)
        if (decoded is DecodeResult.IncompatibleVersion) {
            abort(AbortReason.IncompatibleVersion(decoded.remoteVersion))
            return
        }
        if (decoded !is DecodeResult.Ok) {
            malformedFrames++
            return
        }
        (mutableState.value as? SessionState.Paused)?.let { recover(it) }
        when (val message = decoded.message) {
            is Message.Hello -> onHello(message)
            is Message.Ping ->
                sendMessage(Message.Pong(message.seq, t1Micros = receivedAt, t2Micros = localClock.nowMicros()))
            is Message.Pong -> tracker.onPong(message, receivedAt)?.let { pongs.trySend(it) }
            Message.Ready -> onPeerReady()
            is Message.Start -> if (role == Role.Guest) enterCountdown(message.atHostMicros)
            is Message.Handoff, is Message.Hit -> when (mutableState.value) {
                SessionState.Running, is SessionState.Paused, is SessionState.Countdown -> game.trySend(message)
                else -> Unit
            }
            is Message.End -> {
                enterEnded(message.winner, message.reason)
                game.trySend(message)
            }
            is Message.Heartbeat -> Unit
        }
    }

    /**
     * A transport may drop frames that arrive while it is still finishing its own setup (e.g. a notification
     * received before the CCCD write is acknowledged), so HELLO can be lost. Recovery: a side still handshaking
     * resends HELLO every heartbeat; a side that already got through answers a repeated HELLO with its own, at most
     * once per heartbeat interval, which cannot loop.
     */
    private suspend fun onHello(hello: Message.Hello) {
        if (hello.role != role.opposite) {
            abort(AbortReason.RoleConflict)
            return
        }
        if (mutableState.value != SessionState.Handshaking &&
            localClock.nowMicros() - lastHelloSentMicros >= config.heartbeatInterval.inWholeMicroseconds
        ) {
            sendHello()
        }
        mutableAspect.value = minOf(localAspectMilli, hello.aspectMilli)
        if (mutableState.value == SessionState.Handshaking) {
            mutableState.value = SessionState.Lobby
            // READY may have crossed our HELLO: start now if both players were already ready.
            maybeStart()
        }
    }

    private suspend fun onPeerReady() {
        peerReady = true
        if (role != Role.Host) return
        when (mutableState.value) {
            // The guest recovered from a silence we did not notice: resume together.
            SessionState.Running -> scheduleStart(config.resumeDelay)
            else -> maybeStart()
        }
    }

    private suspend fun maybeStart() {
        if (role != Role.Host || !localReady || !peerReady) return
        when (mutableState.value) {
            SessionState.Lobby, is SessionState.Ended -> scheduleStart(config.countdown)
            else -> Unit
        }
    }

    private suspend fun scheduleStart(delay: Duration) {
        val at = gameClock.nowMicros() + delay.inWholeMicroseconds
        sendMessage(Message.Start(at))
        enterCountdown(at)
    }

    private fun enterCountdown(atHostMicros: Long) {
        when (mutableState.value) {
            is SessionState.Aborted, SessionState.Handshaking -> return
            else -> Unit
        }
        mutableState.value = SessionState.Countdown(atHostMicros)
        countdownJob?.cancel()
        countdownJob = scope?.launch {
            while (true) {
                val remaining = atHostMicros - gameClock.nowMicros()
                if (remaining <= 0) break
                delay(remaining.microseconds)
            }
            if (mutableState.value == SessionState.Countdown(atHostMicros)) {
                mutableState.value = SessionState.Running
            }
        }
    }

    /**
     * Each half only knows about its own fighter, so both may declare their own defeat while the other END is in
     * flight. Two opposite claims mean both fighters fell: a draw, reached identically on both sides.
     */
    private fun enterEnded(winner: Role, reason: EndReason) {
        val current = mutableState.value
        if (current is SessionState.Aborted) return
        countdownJob?.cancel()
        localReady = false
        peerReady = false
        mutableState.value = if (current is SessionState.Ended && current.winner != winner) {
            SessionState.Ended(winner = null, reason = EndReason.KnockOut)
        } else {
            SessionState.Ended(winner, reason)
        }
    }

    private suspend fun recover(paused: SessionState.Paused) {
        when (paused.from) {
            SessionState.Running, is SessionState.Countdown -> {
                // Both sides must resume at the same instant: the host decides, the guest asks.
                if (role == Role.Host) scheduleStart(config.resumeDelay) else sendMessage(Message.Ready)
            }
            else -> mutableState.value = paused.from
        }
    }

    private fun abort(reason: AbortReason) {
        if (mutableState.value is SessionState.Aborted) return
        countdownJob?.cancel()
        mutableState.value = SessionState.Aborted(reason)
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            delay(config.heartbeatInterval)
            sendMessage(Message.Heartbeat(heartbeatSeq))
            heartbeatSeq = (heartbeatSeq + 1) % 256
            if (mutableState.value == SessionState.Handshaking) sendHello()
        }
    }

    /** Any valid frame proves the peer alive, heartbeats guarantee there is at least one per second. */
    private suspend fun watchdogLoop() {
        val pauseAfter = config.silenceBeforePause.inWholeMicroseconds
        val abortAfter = pauseAfter + config.pauseBeforeAbort.inWholeMicroseconds
        while (true) {
            delay(config.watchdogTick)
            val silence = localClock.nowMicros() - lastHeardMicros
            when (val current = mutableState.value) {
                is SessionState.Aborted -> return
                is SessionState.Paused -> if (silence >= abortAfter) abort(AbortReason.PeerSilent)
                else -> if (silence >= pauseAfter) {
                    countdownJob?.cancel()
                    mutableState.value = SessionState.Paused(current)
                }
            }
        }
    }

    private suspend fun syncLoop() {
        while (true) {
            val samples = burst()
            OffsetEstimator.best(samples)?.let { best ->
                if (!synced) {
                    slewed.snapTo(best.offsetMicros)
                    synced = true
                    if (localReady) sendMessage(Message.Ready)
                } else {
                    slewed.setTarget(best.offsetMicros)
                }
                mutableSync.value = SyncStatus(
                    bursts = mutableSync.value.bursts + 1,
                    bestRttMicros = best.rttMicros,
                    jitterMicros = jitterOf(samples),
                    targetOffsetMicros = slewed.targetOffsetMicros,
                    appliedOffsetMicros = slewed.appliedOffsetMicros,
                )
            }
            delay(if (synced) config.resyncInterval else config.pongTimeout)
        }
    }

    private suspend fun burst(): List<SyncSample> {
        val samples = ArrayList<SyncSample>(config.burstSize)
        repeat(config.burstSize) {
            // Drop pongs left over from a previous burst.
            while (true) {
                if (pongs.tryReceive().isFailure) break
            }
            sendMessage(tracker.nextPing())
            withTimeoutOrNull(config.pongTimeout) { pongs.receive() }?.let(samples::add)
        }
        return samples
    }

    private fun jitterOf(samples: List<SyncSample>): Long? =
        if (samples.size < 2) null
        else samples.zipWithNext { a, b -> abs(b.rttMicros - a.rttMicros) }.sum() / (samples.size - 1)

    private suspend fun sendHello() {
        lastHelloSentMicros = localClock.nowMicros()
        sendMessage(Message.Hello(PROTOCOL_VERSION, role, localAspectMilli))
    }

    private suspend fun sendMessage(message: Message) {
        link.send(MessageCodec.encode(message))
    }
}
