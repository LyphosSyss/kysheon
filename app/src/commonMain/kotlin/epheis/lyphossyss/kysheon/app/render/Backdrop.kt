package epheis.lyphossyss.kysheon.app.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import epheis.lyphossyss.kysheon.core.Side
import kotlin.math.exp
import kotlin.math.sin

/** The night-sky and dark-marble background of one half. Redrawn at a low rate on its own layer. */
interface Backdrop {
    /** True when drawn by a GPU shader, false for the Canvas fallback (shown in the debug overlay). */
    val usesShader: Boolean

    fun DrawScope.drawBackdrop(timeSeconds: Float)
}

/** Shader backdrop where the platform supports runtime shaders (Android 13+, iOS through Skia), else the fallback. */
@Composable
expect fun rememberBackdrop(side: Side, tint: Color): Backdrop

/**
 * One source for both platforms: AGSL (Android) is a subset of SkSL (Skia, used by Compose on iOS), so this program
 * compiles on both. Coordinates are normalized by the width, i.e. logical units of the half.
 */
const val BACKDROP_SHADER: String = """
uniform float2 resolution;
uniform float time;
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
    float2 uv = fragCoord / resolution.x;

    // Dark marble: domain-warped noise drifting very slowly, with faint veins.
    float2 q = float2(fbm(uv * 3.0 + time * 0.010), fbm(uv * 3.0 + float2(5.2, 1.3) - time * 0.008));
    float m = fbm(uv * 2.0 + 2.5 * q);
    float vein = abs(sin((uv.x + 2.0 * uv.y + 4.0 * m) * 3.0));
    vein = pow(1.0 - vein, 10.0);
    float3 color = mix(float3(0.030, 0.035, 0.065), float3(0.060, 0.068, 0.110), m);
    color += vein * 0.07 * float3(0.75, 0.80, 1.00);

    // Stars: one candidate per grid cell, few lit, each twinkling at its own pace.
    float2 g = uv * 38.0;
    float2 cell = floor(g);
    float h = hash(cell);
    if (h > 0.90) {
        float2 o = float2(hash(cell + 1.7), hash(cell + 3.1)) - 0.5;
        float d = length(fract(g) - 0.5 - o * 0.6);
        float twinkle = 0.55 + 0.45 * sin(time * (0.8 + h * 2.5) + h * 40.0);
        color += smoothstep(0.09, 0.0, d) * twinkle * 0.75 * float3(0.92, 0.94, 1.00);
    }

    // Light of the side's celestial body from the outer edge, and the veil's glow at the seam.
    float outer = side < 0.5 ? uv.x : 1.0 - uv.x;
    color += tint.rgb * 0.12 * exp(-outer * 3.0);
    color += float3(0.60, 0.65, 0.95) * 0.05 * exp(-(1.0 - outer) * 12.0);
    return half4(half3(color), 1.0);
}
"""

/**
 * Canvas fallback (Android 8 to 12, or a shader that fails to compile): a vertical night gradient, a fixed starry
 * field twinkling, and the same side light. Star positions come from a fixed sequence, nothing is allocated per draw
 * except the gradient brushes, which are created once per size.
 */
class FallbackBackdrop(private val side: Side, private val tint: Color) : Backdrop {
    override val usesShader: Boolean = false

    private val starX = FloatArray(STARS)
    private val starY = FloatArray(STARS)
    private val starPhase = FloatArray(STARS)
    private var cachedSize = Size.Zero
    private var night: Brush? = null
    private var light: Brush? = null

    init {
        var s = 0x2545F491
        fun next(): Float {
            s = s xor (s shl 13)
            s = s xor (s ushr 17)
            s = s xor (s shl 5)
            return (s ushr 8) / 16_777_216f
        }
        for (i in 0 until STARS) {
            starX[i] = next()
            starY[i] = next()
            starPhase[i] = next() * 40f
        }
    }

    override fun DrawScope.drawBackdrop(timeSeconds: Float) {
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
        for (i in 0 until STARS) {
            val twinkle = 0.55f + 0.45f * sin(timeSeconds * (0.8f + (i % 7) * 0.3f) + starPhase[i])
            drawCircle(
                color = STAR,
                radius = 1.2f + (i % 3) * 0.6f,
                center = Offset(starX[i] * size.width, starY[i] * size.height),
                alpha = 0.6f * twinkle * (1f - 0.5f * exp(-starY[i] * 3f)),
            )
        }
    }

    private companion object {
        const val STARS = 90
        val STAR = Color(0xFFEAEFFF)
    }
}
