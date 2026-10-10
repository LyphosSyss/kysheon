package epheis.lyphossyss.kysheon.app.render

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint

/**
 * Pixel-art sprites drawn in code: placeholders for the V2 creatures, original and asset-free.
 * Each row is one line of pixels; every character maps to a color of the sprite's palette, '.' is transparent.
 */
object PixelSprites {

    /** The Sun (host side): a radiant disc. */
    val SUN = listOf(
        ".......l........",
        "...l...g...l....",
        "....l..g..l.....",
        ".......o........",
        "..l..ooooo..l...",
        "....oggglgo.....",
        "...ogggllggo....",
        "llogggglggggol..",
        "...ogggggggdo...",
        "...oggggggddo...",
        "....ogggddgo....",
        "..l..ooooo..l...",
        ".......o........",
        "....l..g..l.....",
        "...l...g...l....",
        ".......l........",
    )

    /** The Moon (guest side): a crescent with a faint halo. */
    val MOON = listOf(
        "................",
        ".....oooo.......",
        "...oolllgo......",
        "..olllgoo.......",
        "..ollgo.....h...",
        ".ollgo..........",
        ".ollgo.....h....",
        ".olggo..........",
        ".olggo..........",
        ".ollgo.....h....",
        ".ollggo.........",
        "..olgggo....h...",
        "..olllggoo......",
        "...oolllggo.....",
        ".....oooo.......",
        "................",
    )

    /** A projectile: a small orb of light. */
    val ORB = listOf(
        "..o..",
        ".olo.",
        "olllo",
        ".olo.",
        "..o..",
    )

    /** Renders [rows] into an image, one image pixel per art pixel (drawn later without smoothing). */
    fun render(rows: List<String>, palette: Map<Char, Color>): ImageBitmap {
        val width = rows.maxOf { it.length }
        val image = ImageBitmap(width, rows.size)
        val canvas = Canvas(image)
        val paint = Paint()
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                val color = palette[c] ?: return@forEachIndexed
                paint.color = color
                canvas.drawRect(Rect(x.toFloat(), y.toFloat(), x + 1f, y + 1f), paint)
            }
        }
        return image
    }

    fun sunPalette(main: Color, light: Color, outline: Color) =
        mapOf('o' to outline, 'g' to main, 'l' to light, 'd' to main.darken(0.7f))

    fun moonPalette(main: Color, light: Color, outline: Color) =
        mapOf('o' to outline, 'g' to main, 'l' to light, 'h' to main.copy(alpha = 0.5f))

    fun orbPalette(main: Color, light: Color) = mapOf('o' to main, 'l' to light)

    private fun Color.darken(factor: Float) = Color(red * factor, green * factor, blue * factor, alpha)
}
