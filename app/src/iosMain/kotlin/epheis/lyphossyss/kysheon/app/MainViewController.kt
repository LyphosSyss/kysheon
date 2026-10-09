package epheis.lyphossyss.kysheon.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

@Suppress("FunctionName", "unused") // Called from Swift.
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
