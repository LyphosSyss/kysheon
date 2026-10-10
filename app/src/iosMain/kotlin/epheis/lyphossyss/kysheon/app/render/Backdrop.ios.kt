package epheis.lyphossyss.kysheon.app.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.drawscope.DrawScope
import epheis.lyphossyss.kysheon.core.Side
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

@Composable
actual fun rememberBackdrop(side: Side, tint: Color): Backdrop = remember(side, tint) {
    runCatching { SkslBackdrop(side, tint) }.getOrElse { FallbackBackdrop(side, tint) }
}

/**
 * Same program through Skia's runtime effects, which Compose uses to draw on iOS (Metal backend).
 * Skia shaders are immutable: a new one is built per draw, i.e. once per size (the layer is cached).
 */
private class SkslBackdrop(side: Side, tint: Color) : Backdrop {
    override val usesShader: Boolean = true

    private val builder = RuntimeShaderBuilder(RuntimeEffect.makeForShader(BACKDROP_SHADER)).apply {
        uniform("side", if (side == Side.Left) 0f else 1f)
        uniform("tint", tint.red, tint.green, tint.blue, tint.alpha)
    }

    override fun DrawScope.drawSky() {
        builder.uniform("resolution", size.width, size.height)
        drawRect(ShaderBrush(builder.makeShader().asComposeShader()))
    }
}
