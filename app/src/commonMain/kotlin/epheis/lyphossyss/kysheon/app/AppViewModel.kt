package epheis.lyphossyss.kysheon.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import epheis.lyphossyss.kysheon.app.platform.LinkFactory
import epheis.lyphossyss.kysheon.link.DiagnosableLink
import epheis.lyphossyss.kysheon.link.Link
import epheis.lyphossyss.kysheon.link.LinkDiagnostics
import epheis.lyphossyss.kysheon.link.LinkState
import epheis.lyphossyss.kysheon.link.probe.LatencyProbe
import epheis.lyphossyss.kysheon.link.probe.ProbeStats
import epheis.lyphossyss.kysheon.link.protocol.Quant
import epheis.lyphossyss.kysheon.link.protocol.Role
import epheis.lyphossyss.kysheon.link.session.AbortReason
import epheis.lyphossyss.kysheon.link.session.Session
import epheis.lyphossyss.kysheon.link.session.SessionState
import epheis.lyphossyss.kysheon.link.time.MonotonicClock
import epheis.lyphossyss.kysheon.link.time.SystemMonotonicClock
import epheis.lyphossyss.kysheon.match.MatchController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Mode { Game, Probe }

/** Why we came back home, when it was not the user’s choice. */
sealed interface Notice {
    data class Lost(val state: LinkState.Lost) : Notice
    data class Aborted(val reason: AbortReason) : Notice
}

sealed interface Screen {
    data class Home(val notice: Notice? = null) : Screen

    data class Connecting(val role: Role, val state: StateFlow<LinkState>) : Screen

    data class Probe(
        val role: Role,
        val stats: StateFlow<ProbeStats>,
        val state: StateFlow<LinkState>,
        val diagnostics: StateFlow<LinkDiagnostics>?,
    ) : Screen

    data class Game(
        val session: Session,
        val match: MatchController,
        val linkState: StateFlow<LinkState>,
        val diagnostics: StateFlow<LinkDiagnostics>?,
    ) : Screen
}

/**
 * Owns the link for the whole session so that it survives configuration changes.
 * Runs on the main dispatcher (viewModelScope): the session, the match and the renderer share that thread.
 */
class AppViewModel(
    private val linkFactory: LinkFactory,
    private val clock: MonotonicClock = SystemMonotonicClock(),
) : ViewModel() {

    private val mutableScreen = MutableStateFlow<Screen>(Screen.Home())
    val screen: StateFlow<Screen> = mutableScreen.asStateFlow()

    private val mutableMode = MutableStateFlow(Mode.Game)
    val mode: StateFlow<Mode> = mutableMode.asStateFlow()

    private var link: Link? = null
    private var session: Job? = null

    /** Height / width of the area available to the game on this phone, sent in HELLO. */
    private var aspect = DEFAULT_ASPECT

    fun setMode(mode: Mode) {
        mutableMode.value = mode
    }

    fun onViewport(widthPx: Int, heightPx: Int) {
        if (widthPx > 0 && heightPx > 0) aspect = heightPx.toDouble() / widthPx
    }

    fun host() = start(Role.Host)

    fun join() = start(Role.Guest)

    /** Cancels the session, closes the link and goes back home. */
    fun finish(notice: Notice? = null) {
        session?.cancel()
        session = null
        val current = link
        link = null
        mutableScreen.value = Screen.Home(notice)
        if (current != null) viewModelScope.launch { current.close() }
    }

    private fun start(role: Role) {
        if (session?.isActive == true) return
        val created = linkFactory.create(viewModelScope) ?: return
        link = created
        val mode = mutableMode.value
        session = viewModelScope.launch {
            if (!connect(created, role)) return@launch
            when (mode) {
                Mode.Probe -> runProbe(created, role)
                Mode.Game -> runGame(created, role)
            }
        }
    }

    private suspend fun connect(link: Link, role: Role): Boolean {
        mutableScreen.value = Screen.Connecting(role, link.state)
        val connected = when (role) {
            Role.Host -> {
                link.host()
                link.state.first { it == LinkState.Connected || it is LinkState.Lost } == LinkState.Connected
            }
            Role.Guest -> link.join()
        }
        if (!connected) {
            val lost = link.state.value as? LinkState.Lost
            finish(lost?.let(Notice::Lost))
        }
        return connected
    }

    private suspend fun runProbe(link: Link, role: Role) {
        val probe = LatencyProbe(link, clock, role, aspectMilli = Quant.encodeAspect(aspect))
        mutableScreen.value = Screen.Probe(role, probe.stats, link.state, (link as? DiagnosableLink)?.diagnostics)
        coroutineScope {
            val probing = launch { probe.run() }
            // On loss, stop probing but stay on the probe screen so the numbers can still be read.
            link.state.first { it is LinkState.Lost }
            probing.cancel()
        }
    }

    private suspend fun runGame(link: Link, role: Role) {
        val session = Session(link, clock, role, localAspectMilli = Quant.encodeAspect(aspect))
        val match = MatchController(session)
        mutableScreen.value = Screen.Game(session, match, link.state, (link as? DiagnosableLink)?.diagnostics)
        coroutineScope {
            launch { match.run() }
            launch {
                session.state.first { it == SessionState.Lobby }
                session.ready()
            }
            session.run()
            coroutineContext.cancelChildren()
        }
        val aborted = session.state.value as? SessionState.Aborted
        finish(aborted?.let { Notice.Aborted(it.reason) })
    }

    /** Rematch after a round, from the result overlay. */
    fun rematch() {
        val game = mutableScreen.value as? Screen.Game ?: return
        viewModelScope.launch { game.session.ready() }
    }

    override fun onCleared() {
        val current = link ?: return
        link = null
        // viewModelScope is already cancelled here: release the radio on a short-lived scope.
        CoroutineScope(Dispatchers.Main).launch { withContext(NonCancellable) { current.close() } }
    }

    private companion object {
        const val DEFAULT_ASPECT = 2.0
    }
}
