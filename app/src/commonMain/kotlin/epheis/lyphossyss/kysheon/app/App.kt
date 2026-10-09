package epheis.lyphossyss.kysheon.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.viewmodel.compose.viewModel
import epheis.lyphossyss.kysheon.app.connect.ConnectingScreen
import epheis.lyphossyss.kysheon.app.game.GameScreen
import epheis.lyphossyss.kysheon.app.home.HomeScreen
import epheis.lyphossyss.kysheon.app.platform.rememberBluetoothAccess
import epheis.lyphossyss.kysheon.app.platform.rememberLinkFactory
import epheis.lyphossyss.kysheon.app.probe.ProbeScreen
import epheis.lyphossyss.kysheon.app.theme.KColors

@Composable
fun App() {
    val linkFactory = rememberLinkFactory()
    val bluetooth = rememberBluetoothAccess()
    val viewModel = viewModel { AppViewModel(linkFactory) }
    val screen by viewModel.screen.collectAsState()
    val mode by viewModel.mode.collectAsState()

    // The game uses the whole safe area (no side padding: the arena must touch the seam between the phones).
    Box(
        Modifier
            .fillMaxSize()
            .background(KColors.background)
            .safeDrawingPadding()
            .onSizeChanged { viewModel.onViewport(it.width, it.height) },
    ) {
        when (val current = screen) {
            is Screen.Home -> HomeScreen(
                screen = current,
                bluetooth = bluetooth,
                platformSupported = linkFactory.supported,
                mode = mode,
                onMode = viewModel::setMode,
                onHost = viewModel::host,
                onJoin = viewModel::join,
            )
            is Screen.Connecting -> ConnectingScreen(current, onCancel = { viewModel.finish() })
            is Screen.Probe -> ProbeScreen(current, onFinish = { viewModel.finish() })
            is Screen.Game -> GameScreen(current, onRematch = viewModel::rematch, onLeave = { viewModel.finish() })
        }
    }
}
