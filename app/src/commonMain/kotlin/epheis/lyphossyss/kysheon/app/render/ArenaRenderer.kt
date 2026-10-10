package epheis.lyphossyss.kysheon.app.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import epheis.lyphossyss.kysheon.app.theme.KColors
import epheis.lyphossyss.kysheon.core.HalfSimulation
import epheis.lyphossyss.kysheon.core.ProjectilePool
import epheis.lyphossyss.kysheon.core.Side
import epheis.lyphossyss.kysheon.match.Feedback
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Maps the logical half (1 wide, `aspect` high) onto the screen. The half always spans the full width so that
 * it touches the seam between the two phones; extra height becomes bands above and below (letterbox).
 */
class Letterbox(val widthPx: Float, val heightPx: Float, val aspect: Double) {
    val scale: Float = minOf(widthPx, (heightPx / aspect).toFloat())
    val left: Float = (widthPx - scale) / 2f
    val top: Float = (heightPx - (aspect * scale).toFloat()) / 2f

    fun x(logical: Double): Float = left + (logical * scale).toFloat()
    fun y(logical: Double): Float = top + (logical * scale).toFloat()
    fun x(logical: Float): Float = left + logical * scale
    fun y(logical: Float): Float = top + logical * scale
    fun length(logical: Double): Float = (logical * scale).toFloat()
    fun length(logical: Float): Float = logical * scale
    fun logicalY(px: Float): Double = ((px - top) / scale).toDouble()
}

/**
 * Draws one half in pixel art: the Sun (host, left) faces the Moon (guest, right) across a celestial veil.
 * Everything snaps to a grid of "art pixels" ([ART_GRID] across the half), sprites are drawn without smoothing.
 * Reads the simulation in the draw phase only and allocates nothing per frame: sprites are rendered once, offsets,
 * sizes and colors are inline value classes.
 *
 * Presentation effects (particles, shake, veil ripples, hit flash) live here and are driven by [update] and
 * [onFeedback]; they never touch the simulation.
 */
class ArenaRenderer(private val side: Side) {
    private val own = if (side == Side.Left) KColors.host else KColors.guest
    private val opponent = if (side == Side.Left) KColors.guest else KColors.host
    private val palette = arrayOf(own, opponent, KColors.light)

    private val fighterSprite = if (side == Side.Left) {
        PixelSprites.render(PixelSprites.SUN, PixelSprites.sunPalette(own, KColors.light, OUTLINE))
    } else {
        PixelSprites.render(PixelSprites.MOON, PixelSprites.moonPalette(own, KColors.light, OUTLINE))
    }
    private val ownOrb = PixelSprites.render(PixelSprites.ORB, PixelSprites.orbPalette(own, KColors.light))
    private val opponentOrb = PixelSprites.render(PixelSprites.ORB, PixelSprites.orbPalette(opponent, KColors.light))
    private val flashFilter = ColorFilter.tint(KColors.light, BlendMode.SrcAtop)
    private val downFilter = ColorFilter.tint(KColors.muted, BlendMode.SrcAtop)

    private val particles = ParticleField(capacity = 1024)
    private val shake = Shake()
    private val ripples = Ripples()

    private var time = 0f
    private var flash = 0f
    private var trailClock = 0f

    /** Particles currently alive, for the debug overlay. */
    val particleCount: Int get() = particles.count

    /** Advances effects by [dt] seconds and lays golden dust behind every projectile. */
    fun update(dt: Float, sim: HalfSimulation?, alpha: Double) {
        time += dt
        flash = (flash - dt * 5f).coerceAtLeast(0f)
        particles.update(dt)
        shake.update(dt)
        ripples.update(dt)
        trailClock += dt
        if (sim != null && trailClock >= TRAIL_PERIOD) {
            trailClock = 0f
            trail(sim.localProjectiles, alpha, TONE_OWN)
            trail(sim.incomingProjectiles, alpha, TONE_OPPONENT)
        }
    }

    fun onFeedback(feedback: Feedback) {
        when (feedback) {
            is Feedback.Fired -> {
                val direction = if (side == Side.Left) 0f else PI.toFloat()
                particles.burst(
                    feedback.x.toFloat(), feedback.y.toFloat(), n = 10, tone = TONE_OWN,
                    speedMin = 0.1f, speedMax = 0.35f, lifeMin = 0.15f, lifeMax = 0.35f,
                    sizeMin = 0.008f, sizeMax = 0.016f, direction = direction, spread = 1.2f,
                )
            }
            is Feedback.Entered -> {
                ripples.add(feedback.y.toFloat())
                particles.burst(
                    feedback.x.toFloat(), feedback.y.toFloat(), n = 12, tone = TONE_OPPONENT,
                    speedMin = 0.05f, speedMax = 0.25f, lifeMin = 0.2f, lifeMax = 0.5f,
                    sizeMin = 0.008f, sizeMax = 0.012f,
                )
            }
            is Feedback.Exited -> ripples.add(feedback.y.toFloat())
            is Feedback.HitTaken -> {
                flash = 1f
                shake.add(0.55f)
                lightBurst(feedback.x.toFloat(), feedback.y.toFloat(), n = 40, speed = 0.6f)
            }
            is Feedback.HitDealt -> Unit
            is Feedback.FighterDown -> {
                shake.add(1f)
                lightBurst(feedback.x.toFloat(), feedback.y.toFloat(), n = 120, speed = 0.9f)
            }
        }
    }

    /** New round: no leftover effect. */
    fun reset() {
        particles.clear()
        flash = 0f
    }

    /** [showProjectiles] is false once the round is over: the simulation is frozen, projectiles would hang mid-air. */
    fun DrawScope.drawArena(sim: HalfSimulation, alpha: Double, box: Letterbox, showProjectiles: Boolean) {
        val px = box.scale / ART_GRID
        // Shake moves the whole scene by whole art pixels: the grid never breaks.
        translate(snap(box.length(shake.offsetX), px), snap(box.length(shake.offsetY), px)) {
            drawBands(box, sim.aspect)
            drawVeil(box, sim.aspect, px)
            if (showProjectiles) {
                drawProjectiles(sim.localProjectiles, alpha, box, px, ownOrb, own)
                drawProjectiles(sim.incomingProjectiles, alpha, box, px, opponentOrb, opponent)
            }
            drawParticles(box, px)
            drawFighter(sim, alpha, box, px)
            drawHp(sim, box, px)
        }
    }

    private fun trail(pool: ProjectilePool, alpha: Double, tone: Int) {
        for (slot in 0 until pool.capacity) {
            if (!pool.isActive(slot)) continue
            particles.mote(
                pool.interpolatedX(slot, alpha).toFloat(), pool.interpolatedY(slot, alpha).toFloat(),
                tone, jitter = 0.012f, life = 0.45f, size = 0.011f,
            )
        }
    }

    private fun lightBurst(x: Float, y: Float, n: Int, speed: Float) {
        particles.burst(x, y, n / 2, TONE_LIGHT, 0.1f, speed, 0.25f, 0.6f, 0.008f, 0.02f)
        particles.burst(x, y, n - n / 2, TONE_OWN, 0.05f, speed * 0.7f, 0.3f, 0.9f, 0.008f, 0.016f)
    }

    /** Letterbox bands: darken what lies outside the shared logical area. */
    private fun DrawScope.drawBands(box: Letterbox, aspect: Double) {
        if (box.top > 0f) {
            drawRect(KColors.background.copy(alpha = 0.7f), Offset.Zero, Size(size.width, box.top))
            val bottom = box.y(aspect)
            drawRect(KColors.background.copy(alpha = 0.7f), Offset(0f, bottom), Size(size.width, size.height - bottom))
        }
    }

    /** The celestial veil along the seam: a column of pixels waving where projectiles cross it. */
    private fun DrawScope.drawVeil(box: Letterbox, aspect: Double, px: Float) {
        val baseX = side.borderX.toFloat() + if (side == Side.Left) -0.02f else 0.02f
        val rows = (aspect * ART_GRID).toInt()
        val glow = opponent.copy(alpha = 0.12f)
        val line = KColors.light.copy(alpha = 0.30f + 0.1f * sin(time * 1.7f))
        var k = 0
        while (k < rows) {
            val ly = k / ART_GRID
            val sway = sin(ly * 7f + time * 0.9f) * 0.004f + ripples.displacement(ly, time)
            val x = snap(box.x(baseX + sway), px)
            val y = box.top + k * px
            drawRect(glow, Offset(x - px, y), Size(px * 3, px * 2), blendMode = BlendMode.Plus)
            // Dotted line: one lit pixel out of two, shifting slowly upward.
            if ((k + (time * 6f).toInt()) % 2 == 0) drawRect(line, Offset(x, y), Size(px, px))
            k += 2
        }
    }

    private fun DrawScope.drawProjectiles(
        pool: ProjectilePool,
        alpha: Double,
        box: Letterbox,
        px: Float,
        orb: ImageBitmap,
        color: Color,
    ) {
        val side = orb.width * px
        for (slot in 0 until pool.capacity) {
            if (!pool.isActive(slot)) continue
            val cx = snap(box.x(pool.interpolatedX(slot, alpha)), px)
            val cy = snap(box.y(pool.interpolatedY(slot, alpha)), px)
            drawRect(color, Offset(cx - side, cy - side), Size(side * 2, side * 2), alpha = 0.08f, blendMode = BlendMode.Plus)
            drawImage(
                orb,
                dstOffset = IntOffset((cx - side / 2).toInt(), (cy - side / 2).toInt()),
                dstSize = IntSize(side.toInt(), side.toInt()),
                filterQuality = FilterQuality.None,
            )
        }
    }

    private fun DrawScope.drawParticles(box: Letterbox, px: Float) {
        for (i in 0 until particles.count) {
            val fade = particles.fade(i)
            val sizePx = snap(box.length(particles.size[i]) * (0.5f + 0.5f * fade), px).coerceAtLeast(px)
            drawRect(
                color = palette[particles.tone[i]],
                topLeft = Offset(snap(box.x(particles.x[i]), px), snap(box.y(particles.y[i]), px)),
                size = Size(sizePx, sizePx),
                alpha = fade * 0.9f,
                blendMode = BlendMode.Plus,
            )
        }
    }

    private fun DrawScope.drawFighter(sim: HalfSimulation, alpha: Double, box: Letterbox, px: Float) {
        val cx = snap(box.x(sim.fighterX), px)
        val cy = snap(box.y(sim.fighterInterpolatedY(alpha)), px)
        val spriteSide = fighterSprite.width * px * SPRITE_SCALE

        // Aura: two concentric squares of light, additive.
        drawRect(own, Offset(cx - spriteSide, cy - spriteSide), Size(spriteSide * 2, spriteSide * 2), alpha = 0.04f, blendMode = BlendMode.Plus)
        drawRect(own, Offset(cx - spriteSide * 0.7f, cy - spriteSide * 0.7f), Size(spriteSide * 1.4f, spriteSide * 1.4f), alpha = 0.06f, blendMode = BlendMode.Plus)

        if (!sim.isDown) orbit(cx, cy, spriteSide * 0.85f, px)

        drawImage(
            fighterSprite,
            dstOffset = IntOffset((cx - spriteSide / 2).toInt(), (cy - spriteSide / 2).toInt()),
            dstSize = IntSize(spriteSide.toInt(), spriteSide.toInt()),
            filterQuality = FilterQuality.None,
            colorFilter = when {
                sim.isDown -> downFilter
                flash > 0f -> flashFilter
                else -> null
            },
        )
    }

    /** Three motes orbiting the fighter, snapped to the pixel grid: an invented glyph, no real-world symbol. */
    private fun DrawScope.orbit(cx: Float, cy: Float, radius: Float, px: Float) {
        val speed = if (side == Side.Left) 0.8f else -0.6f
        for (k in 0 until 3) {
            val a = time * speed + (2 * PI / 3 * k).toFloat()
            val x = snap(cx + cos(a) * radius, px)
            val y = snap(cy + sin(a) * radius, px)
            drawRect(own, Offset(x - px, y - px), Size(px * 3, px * 3), alpha = 0.15f, blendMode = BlendMode.Plus)
            drawRect(KColors.light, Offset(x, y), Size(px, px))
        }
    }

    private fun DrawScope.drawHp(sim: HalfSimulation, box: Letterbox, px: Float) {
        val pip = px * 3
        val gap = px * 5
        val y = snap(box.top + gap, px)
        // Own hit points near our outer edge, the opponent's near the seam.
        val ownStart = snap(box.x(side.outerX) + if (side == Side.Left) gap else -gap - pip, px)
        val opponentStart = snap(box.x(side.borderX) + if (side == Side.Left) -gap - pip else gap, px)
        val step = if (side == Side.Left) gap else -gap
        for (i in 0 until sim.rules.maxHp) {
            pipAt(ownStart + i * step, y, pip, px, own, filled = i < sim.hp)
            pipAt(opponentStart - i * step, y, pip, px, opponent, filled = i < sim.opponentHp)
        }
    }

    private fun DrawScope.pipAt(x: Float, y: Float, pip: Float, px: Float, color: Color, filled: Boolean) {
        if (filled) {
            drawRect(color, Offset(x - px, y - px), Size(pip + 2 * px, pip + 2 * px), alpha = 0.15f, blendMode = BlendMode.Plus)
            drawRect(color, Offset(x, y), Size(pip, pip))
            drawRect(KColors.light, Offset(x, y), Size(px, px), alpha = 0.8f)
        } else {
            drawRect(color.copy(alpha = 0.35f), Offset(x, y), Size(pip, pip), style = Stroke(width = px))
        }
    }

    private fun snap(value: Float, px: Float): Float = floor(value / px) * px

    private companion object {
        /** Art pixels across one half: the resolution of the pixel-art grid. */
        const val ART_GRID = 96f

        /** Fighter sprites are drawn at their native size on the art grid (16 art pixels, a sixth of the half). */
        const val SPRITE_SCALE = 1f
        const val TRAIL_PERIOD = 1f / 30f
        const val TONE_OWN = 0
        const val TONE_OPPONENT = 1
        const val TONE_LIGHT = 2
        val OUTLINE = Color(0xFF1A1420)
    }
}
