package epheis.lyphossyss.kysheon.app.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import epheis.lyphossyss.kysheon.app.Screen
import epheis.lyphossyss.kysheon.app.resources.Res
import epheis.lyphossyss.kysheon.app.resources.action_cancel
import epheis.lyphossyss.kysheon.app.roleLabel
import epheis.lyphossyss.kysheon.app.stateLabel
import epheis.lyphossyss.kysheon.app.theme.KButton
import epheis.lyphossyss.kysheon.app.theme.KColors
import epheis.lyphossyss.kysheon.app.theme.KText
import epheis.lyphossyss.kysheon.link.protocol.Role
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConnectingScreen(screen: Screen.Connecting, onCancel: () -> Unit) {
    val state by screen.state.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KText(
            roleLabel(screen.role),
            color = if (screen.role == Role.Host) KColors.host else KColors.guest,
            weight = FontWeight.SemiBold,
        )
        KText(stateLabel(state), size = 22.sp)
        KButton(stringResource(Res.string.action_cancel), onCancel, accent = KColors.text)
    }
}
