package epheis.lyphossyss.kysheon.app.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import epheis.lyphossyss.kysheon.core.Side
import kotlin.math.floor
import kotlin.math.sin

/**
 * The night sky and dark marble behind one half. Static: drawn once per size on its own cached layer (a runtime
 * shader over the whole screen is far too expensive to re-run while the arena renders at 120 Hz). The twinkling
 * stars are drawn separately by [StarField], a few hundred pixels at most.
 */
interface Backdrop {
    /** True when drawn by a GPU shader, false for the Canvas fallback (shown in the debug overlay). */
    val usesShader: Boolean

    fun DrawScope.drawSky()
}

/** Shader backdrop where the platform supports runtime shaders (Android 13+, iOS through Skia), else the fallback. */
@Composable
expect fun rememberBackdrop(side: Side, tint: Color): Backdrop

/** Art pixels across one half, shared by the backdrop, the stars and the arena. */
const val ART_COLUMNS: Float = 96f

/**
 * One source for both platforms: AGSL (Android) is a subset of SkSL (Skia, used by Compose on iOS), so this program
 * compiles on both. Coordinates are normalized by the width, i.e. logical units of the half; every art pixel samples
 * once and colors are quantized, for a pixel-art sky.
 */
const val BACKDROP_SHADER: String = """
uniform float2 resolution;
uniform float side;
uniform float4 tint;

float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * noise(p);
        p *= 2.03;
        a *= 0.5;
    }
    return v;
}

half4 main(float2 fragCoord) {
    float cell = resolution.x / 96.0;
    float2 uv = (floor(fragCoord / cell) + 0.5) * cell / resolution.x;

    // Dark marble: domain-warped noise with faint veins.
    float2 q = float2(fbm(uv * 3.0), fbm(uv * 3.0 + float2(5.2, 1.3)));
    float m = fbm(uv * 2.0 + 2.5 * q);
    float vein = abs(sin((uv.x + 2.0 * uv.y + 4.0 * m) * 3.0));
    vein = pow(1.0 - vein, 10.0);
    float3 color = mix(float3(0.030, 0.035, 0.065), float3(0.060, 0.068, 0.110), m);
    color += vein * 0.07 * float3(0.75, 0.80, 1.00);

    // Light of the side's celestial body from the outer edge, and the veil's glow at the seam.
    float outer = side < 0.5 ? uv.x : 1.0 - uv.x;
    color += tint.rgb * 0.12 * exp(-outer * 3.0);
    color += float3(0.60, 0.65, 0.95) * 0.05 * exp(-(1.0 - outer) * 12.0);
    color = floor(color * 40.0 + 0.5) / 40.0;
    return half4(half3(color), 1.0);
}
"""

/** Canvas fallback (Android 8 to 12, or a shader that fails to compile): a night gradient and the side light. */
class FallbackBackdrop(private val side: Side, private val tint: Color) : Backdrop {
    override val usesShader: Boolean = false

    private var cachedSize = Size.Zero
    private var night: Brush? = null
    private var light: Brush? = null

    override fun DrawScope.drawSky() {
        if (size != cachedSize) {
            cachedSize = size
            night = Brush.verticalGradient(listOf(Color(0xFF0A0C16), Color(0xFF11141F), Color(0xFF0A0C16)))
            light = Brush.horizontalGradient(
                0f to tint.copy(alpha = if (side == Side.Left) 0.14f else 0f),
                1f to tint.copy(alpha = if (side == Side.Left) 0f else 0.14f),
                startX = 0f,
                endX = size.width,
            )
        }
        drawRect(night!!)
        drawRect(light!!)
    }
}

/** Twinkling one-pixel stars on the art grid. Fixed positions, nothing allocated per draw. */
class StarField(private val count: Int = 110) {
    private val starX = FloatArray(count)
    private val starY = FloatArray(count)
    private val phase = FloatArray(count)

    init {
        var s = 0x2545F491
        fun next(): Float {
            s = s xor (s shl 13)
            s = s xor (s ushr 17)
            s = s xor (s shl 5)
            return (s ushr 8) / 16_777_216f
        }
        for (i in 0 until count) {
            starX[i] = next()
            starY[i] = next()
            phase[i] = next() * 40f
        }
    }

    fun DrawScope.drawStars(timeSeconds: Float) {
        val px = size.width / ART_COLUMNS
        for (i in 0 until count) {
            val twinkle = 0.5f + 0.5f * sin(timeSeconds * (0.8f + (i % 7) * 0.3f) + phase[i])
            drawRect(
                color = STAR,
                topLeft = Offset(floor(starX[i] * ART_COLUMNS) * px, floor(starY[i] * size.height / px) * px),
                size = Size(px, px),
                alpha = 0.75f * twinkle,
            )
        }
    }

    private companion object {
        val STAR = Color(0xFFEAEFFF)
    }
}
