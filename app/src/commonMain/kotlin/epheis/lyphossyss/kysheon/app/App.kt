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
import epheis.lyphossyss.kysheon.app.audio.Sfx
import epheis.lyphossyss.kysheon.app.feel.rememberHaptics
import epheis.lyphossyss.kysheon.app.audio.rememberSoundPlayer
import epheis.lyphossyss.kysheon.app.settings.SettingsRepository
import epheis.lyphossyss.kysheon.app.settings.SettingsScreen
import epheis.lyphossyss.kysheon.app.settings.rememberKeyValueStore
import androidx.compose.runtime.LaunchedEffect

@Composable
fun App() {
    val linkFactory = rememberLinkFactory()
    val bluetooth = rememberBluetoothAccess()
    val store = rememberKeyValueStore()
    val viewModel = viewModel { AppViewModel(linkFactory, SettingsRepository(store)) }
    val screen by viewModel.screen.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val settings by viewModel.settings.collectAsState()
    // One player for the whole app, so that the settings screen can preview the volume.
    val sound = rememberSoundPlayer(enabled = settings.soundEnabled)
    LaunchedEffect(sound, settings.volume) { sound.setVolume(settings.volume) }
    val haptics = rememberHaptics(settings.hapticsEnabled, settings.hapticsIntensity)

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
                onSettings = viewModel::openSettings,
            )
            Screen.Settings -> SettingsScreen(
                settings = settings,
                onChange = viewModel::updateSettings,
                onPreviewSound = { sound.play(Sfx.HitDealt) },
                onPreviewHaptics = { haptics.impact() },
                onBack = viewModel::closeSettings,
            )
            is Screen.Connecting -> ConnectingScreen(current, onCancel = { viewModel.finish() })
            is Screen.Probe -> ProbeScreen(current, onFinish = { viewModel.finish() })
            is Screen.Game -> GameScreen(
                current,
                settings = settings,
                sound = sound,
                haptics = haptics,
                onRematch = viewModel::rematch,
                onLeave = { viewModel.finish() },
            )
        }
    }
}
