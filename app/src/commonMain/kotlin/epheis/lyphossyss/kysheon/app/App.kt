package epheis.lyphossyss.kysheon.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import epheis.lyphossyss.kysheon.app.connect.ConnectingScreen
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

    Box(
        Modifier
            .fillMaxSize()
            .background(KColors.background)
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        when (val current = screen) {
            is Screen.Home -> HomeScreen(current, bluetooth, linkFactory.supported, onHost = viewModel::host, onJoin = viewModel::join)
            is Screen.Connecting -> ConnectingScreen(current, onCancel = viewModel::finish)
            is Screen.Probe -> ProbeScreen(current, onFinish = viewModel::finish)
        }
    }
}
