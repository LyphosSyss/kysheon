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
import epheis.lyphossyss.kysheon.link.protocol.Role
import epheis.lyphossyss.kysheon.link.time.MonotonicClock
import epheis.lyphossyss.kysheon.link.time.SystemMonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    /** [notice]: why we came back home, if it was not the user’s choice. */
    data class Home(val notice: LinkState.Lost? = null) : Screen

    data class Connecting(val role: Role, val state: StateFlow<LinkState>) : Screen

    data class Probe(
        val role: Role,
        val stats: StateFlow<ProbeStats>,
        val state: StateFlow<LinkState>,
        val diagnostics: StateFlow<LinkDiagnostics>?,
    ) : Screen
}

/**
 * Owns the link for the whole session so that it survives configuration changes.
 * Runs on the main dispatcher (viewModelScope), which the latency probe requires.
 */
class AppViewModel(
    private val linkFactory: LinkFactory,
    private val clock: MonotonicClock = SystemMonotonicClock(),
) : ViewModel() {

    private val mutableScreen = MutableStateFlow<Screen>(Screen.Home())
    val screen: StateFlow<Screen> = mutableScreen.asStateFlow()

    private var link: Link? = null
    private var session: Job? = null

    fun host() = start(Role.Host)

    fun join() = start(Role.Guest)

    /** Cancels the session, closes the link and goes back home. */
    fun finish() {
        session?.cancel()
        session = null
        val current = link
        link = null
        mutableScreen.value = Screen.Home()
        if (current != null) viewModelScope.launch { current.close() }
    }

    private fun start(role: Role) {
        if (session?.isActive == true) return
        val created = linkFactory.create(viewModelScope) ?: return
        link = created
        session = viewModelScope.launch { runSession(created, role) }
    }

    private suspend fun runSession(link: Link, role: Role) {
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
            link.close()
            this.link = null
            mutableScreen.value = Screen.Home(notice = lost)
            return
        }
        val probe = LatencyProbe(link, clock, role)
        mutableScreen.value = Screen.Probe(role, probe.stats, link.state, (link as? DiagnosableLink)?.diagnostics)
        coroutineScope {
            val probing = launch { probe.run() }
            // On loss, stop probing but stay on the probe screen so the numbers can still be read.
            link.state.first { it is LinkState.Lost }
            probing.cancel()
        }
    }

    override fun onCleared() {
        val current = link ?: return
        link = null
        // viewModelScope is already cancelled here: release the radio on a short-lived scope.
        CoroutineScope(Dispatchers.Main).launch { withContext(NonCancellable) { current.close() } }
    }
}
