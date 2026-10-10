package epheis.lyphossyss.kysheon.app.platform

import androidx.compose.runtime.Composable

/** System back (Android back gesture / button). No-op on iOS, where screens provide their own Back button. */
@Composable
expect fun SystemBackHandler(enabled: Boolean = true, onBack: () -> Unit)
