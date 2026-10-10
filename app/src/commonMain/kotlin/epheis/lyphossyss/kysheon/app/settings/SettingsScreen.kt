package epheis.lyphossyss.kysheon.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import epheis.lyphossyss.kysheon.app.platform.SystemBackHandler
import epheis.lyphossyss.kysheon.app.resources.Res
import epheis.lyphossyss.kysheon.app.resources.action_back
import epheis.lyphossyss.kysheon.app.resources.settings_debug_button
import epheis.lyphossyss.kysheon.app.resources.settings_debug_on_start
import epheis.lyphossyss.kysheon.app.resources.settings_haptics
import epheis.lyphossyss.kysheon.app.resources.settings_section_debug
import epheis.lyphossyss.kysheon.app.resources.settings_section_haptics
import epheis.lyphossyss.kysheon.app.resources.settings_section_sound
import epheis.lyphossyss.kysheon.app.resources.settings_sound
import epheis.lyphossyss.kysheon.app.resources.settings_title
import epheis.lyphossyss.kysheon.app.resources.settings_volume
import epheis.lyphossyss.kysheon.app.resources.settings_volume_value
import epheis.lyphossyss.kysheon.app.theme.KButton
import epheis.lyphossyss.kysheon.app.theme.KColors
import epheis.lyphossyss.kysheon.app.theme.KSlider
import epheis.lyphossyss.kysheon.app.theme.KSwitch
import epheis.lyphossyss.kysheon.app.theme.KText
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    onPreviewSound: () -> Unit,
    onBack: () -> Unit,
) {
    SystemBackHandler(onBack = onBack)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        KText(stringResource(Res.string.settings_title), size = 28.sp, weight = FontWeight.Bold)

        Section(stringResource(Res.string.settings_section_sound)) {
            SwitchRow(stringResource(Res.string.settings_sound), settings.soundEnabled) { on ->
                onChange { it.copy(soundEnabled = on) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                KText(stringResource(Res.string.settings_volume), color = if (settings.soundEnabled) KColors.text else KColors.muted)
                KText(
                    stringResource(Res.string.settings_volume_value, (settings.volume * 100).roundToInt().toString()),
                    color = KColors.muted,
                    monospace = true,
                )
            }
            KSlider(
                value = settings.volume,
                onValueChange = { v -> onChange { it.copy(volume = v) } },
                onValueChangeFinished = onPreviewSound,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Section(stringResource(Res.string.settings_section_haptics)) {
            SwitchRow(stringResource(Res.string.settings_haptics), settings.hapticsEnabled) { on ->
                onChange { it.copy(hapticsEnabled = on) }
            }
        }

        Section(stringResource(Res.string.settings_section_debug)) {
            SwitchRow(stringResource(Res.string.settings_debug_button), settings.showDebugButton) { on ->
                onChange { it.copy(showDebugButton = on) }
            }
            SwitchRow(stringResource(Res.string.settings_debug_on_start), settings.debugOverlayOnStart) { on ->
                onChange { it.copy(debugOverlayOnStart = on) }
            }
        }

        KButton(stringResource(Res.string.action_back), onBack, Modifier.fillMaxWidth(), accent = KColors.text)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(KColors.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KText(title, color = KColors.host, weight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KText(label, Modifier.weight(1f))
        KSwitch(checked, onCheckedChange)
    }
}
