package epheis.lyphossyss.kysheon.app.render

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import epheis.lyphossyss.kysheon.core.Side

@Composable
actual fun rememberBackdrop(side: Side, tint: Color): Backdrop = remember(side, tint) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // A shader that fails to compile on some vendor GPU must not take the game down: fall back.
        runCatching { AgslBackdrop(side, tint) }.getOrElse { FallbackBackdrop(side, tint) }
    } else {
        FallbackBackdrop(side, tint)
    }
}

/** AGSL runtime shader (API 33+), run once per size: the layer that draws it is cached. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AgslBackdrop(side: Side, tint: Color) : Backdrop {
    override val usesShader: Boolean = true

    private val shader = RuntimeShader(BACKDROP_SHADER).apply {
        setFloatUniform("side", if (side == Side.Left) 0f else 1f)
        setFloatUniform("tint", tint.red, tint.green, tint.blue, tint.alpha)
    }
    private val brush = ShaderBrush(shader)

    override fun DrawScope.drawSky() {
        shader.setFloatUniform("resolution", size.width, size.height)
        drawRect(brush)
    }
}
