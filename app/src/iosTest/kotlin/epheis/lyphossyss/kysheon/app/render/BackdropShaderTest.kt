package epheis.lyphossyss.kysheon.app.render

import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.test.Test
import kotlin.test.assertNotNull

/** Runtime shaders compile at run time only: compile the real source with Skia here (the AGSL side needs a phone). */
class BackdropShaderTest {

    @Test
    fun backdropShaderCompilesWithSkia() {
        val builder = RuntimeShaderBuilder(RuntimeEffect.makeForShader(BACKDROP_SHADER))
        builder.uniform("resolution", 1080f, 2400f)
        builder.uniform("time", 12.5f)
        builder.uniform("side", 1f)
        builder.uniform("tint", 0.6f, 0.8f, 1f, 1f)
        assertNotNull(builder.makeShader())
    }
}
