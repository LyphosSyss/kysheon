package epheis.lyphossyss.kysheon.app.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import epheis.lyphossyss.kysheon.app.theme.KColors
import epheis.lyphossyss.kysheon.core.HalfSimulation
import epheis.lyphossyss.kysheon.core.ProjectilePool
import epheis.lyphossyss.kysheon.core.Side
import epheis.lyphossyss.kysheon.match.Feedback
import kotlin.math.PI
import kotlin.math.cos
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
 * Draws one half in the sacred direction: the Sun (host, left) faces the Moon (guest, right) across a celestial
 * veil. Reads the simulation in the draw phase only and allocates nothing per frame: paths, strokes and particle
 * arrays are reused, offsets and colors are inline value classes.
 *
 * Presentation effects (particles, shake, veil ripples, hit flash) live here and are driven by [update] and
 * [onFeedback]; they never touch the simulation.
 */
class ArenaRenderer(private val side: Side) {
    private val own = if (side == Side.Left) KColors.host else KColors.guest
    private val opponent = if (side == Side.Left) KColors.guest else KColors.host
    private val palette = arrayOf(own, opponent, KColors.light)

    private val particles = ParticleField(capacity = 1024)
    private val shake = Shake()
    private val ripples = Ripples()

    private val corePath = Path()
    private val veilPath = Path()
    private val thin = Stroke(width = 2f, cap = StrokeCap.Round)
    private val outline = Stroke(width = 3f)
    private val veilStroke = Stroke(width = 3f, cap = StrokeCap.Round)
    private val veilGlow = Stroke(width = 18f, cap = StrokeCap.Round)

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
                    sizeMin = 0.004f, sizeMax = 0.009f, direction = direction, spread = 1.2f,
                )
            }
            is Feedback.Entered -> {
                ripples.add(feedback.y.toFloat())
                particles.burst(
                    feedback.x.toFloat(), feedback.y.toFloat(), n = 12, tone = TONE_OPPONENT,
                    speedMin = 0.05f, speedMax = 0.25f, lifeMin = 0.2f, lifeMax = 0.5f,
                    sizeMin = 0.003f, sizeMax = 0.007f,
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
        translate(box.length(shake.offsetX), box.length(shake.offsetY)) {
            drawBands(box, sim.aspect)
            drawVeil(box, sim.aspect)
            if (showProjectiles) {
                drawProjectiles(sim.localProjectiles, alpha, box, own)
                drawProjectiles(sim.incomingProjectiles, alpha, box, opponent)
            }
            drawParticles(box)
            drawFighter(sim, alpha, box)
            drawHp(sim, box)
        }
    }

    private fun trail(pool: ProjectilePool, alpha: Double, tone: Int) {
        for (slot in 0 until pool.capacity) {
            if (!pool.isActive(slot)) continue
            particles.mote(
                pool.interpolatedX(slot, alpha).toFloat(), pool.interpolatedY(slot, alpha).toFloat(),
                tone, jitter = 0.01f, life = 0.45f, size = 0.006f,
            )
        }
    }

    private fun lightBurst(x: Float, y: Float, n: Int, speed: Float) {
        particles.burst(x, y, n / 2, TONE_LIGHT, 0.1f, speed, 0.25f, 0.6f, 0.004f, 0.012f)
        particles.burst(x, y, n - n / 2, TONE_OWN, 0.05f, speed * 0.7f, 0.3f, 0.9f, 0.003f, 0.010f)
    }

    /** Letterbox bands: darken what lies outside the shared logical area. */
    private fun DrawScope.drawBands(box: Letterbox, aspect: Double) {
        if (box.top > 0f) {
            drawRect(KColors.background.copy(alpha = 0.7f), Offset.Zero, Size(size.width, box.top))
            val bottom = box.y(aspect)
            drawRect(KColors.background.copy(alpha = 0.7f), Offset(0f, bottom), Size(size.width, size.height - bottom))
        }
    }

    /** The celestial veil along the seam, waving where projectiles cross it. */
    private fun DrawScope.drawVeil(box: Letterbox, aspect: Double) {
        val baseX = side.borderX.toFloat() + if (side == Side.Left) -0.012f else 0.012f
        veilPath.reset()
        val steps = 48
        for (k in 0..steps) {
            val ly = (aspect * k / steps).toFloat()
            val sway = sin(ly * 7f + time * 0.9f) * 0.003f + ripples.displacement(ly, time)
            val px = box.x(baseX + sway)
            val py = box.y(ly)
            if (k == 0) veilPath.moveTo(px, py) else veilPath.lineTo(px, py)
        }
        drawPath(veilPath, opponent.copy(alpha = 0.10f), style = veilGlow, blendMode = BlendMode.Plus)
        drawPath(veilPath, KColors.light.copy(alpha = 0.35f + 0.1f * sin(time * 1.7f)), style = veilStroke)
    }

    private fun DrawScope.drawProjectiles(pool: ProjectilePool, alpha: Double, box: Letterbox, color: Color) {
        val radius = box.length(PROJECTILE_DRAW_RADIUS)
        for (slot in 0 until pool.capacity) {
            if (!pool.isActive(slot)) continue
            val center = Offset(box.x(pool.interpolatedX(slot, alpha)), box.y(pool.interpolatedY(slot, alpha)))
            // Additive halo: soft discs read as a glow on the dark sky, without any gradient allocation.
            drawCircle(color, radius * 3.2f, center, alpha = 0.06f, blendMode = BlendMode.Plus)
            drawCircle(color, radius * 2.0f, center, alpha = 0.14f, blendMode = BlendMode.Plus)
            drawCircle(color, radius, center)
            drawCircle(KColors.light, radius * 0.45f, center, alpha = 0.9f)
        }
    }

    private fun DrawScope.drawParticles(box: Letterbox) {
        for (i in 0 until particles.count) {
            val fade = particles.fade(i)
            drawCircle(
                color = palette[particles.tone[i]],
                radius = box.length(particles.size[i]) * (0.4f + 0.6f * fade),
                center = Offset(box.x(particles.x[i]), box.y(particles.y[i])),
                alpha = fade * 0.9f,
                blendMode = BlendMode.Plus,
            )
        }
    }

    private fun DrawScope.drawFighter(sim: HalfSimulation, alpha: Double, box: Letterbox) {
        val center = Offset(box.x(sim.fighterX), box.y(sim.fighterInterpolatedY(alpha)))
        val r = box.length(sim.rules.fighterRadius)
        val body = when {
            sim.isDown -> KColors.muted
            flash > 0f -> KColors.light
            else -> own
        }

        drawCircle(body, r * 3.0f, center, alpha = 0.05f, blendMode = BlendMode.Plus)
        drawCircle(body, r * 2.0f, center, alpha = 0.10f, blendMode = BlendMode.Plus)

        if (!sim.isDown) {
            if (side == Side.Left) sunHalo(center, r, body) else moonHalo(center, r, body)
        }

        // Core shape: hexagon for the Sun, diamond for the Moon.
        corePath.reset()
        if (side == Side.Left) {
            for (k in 0 until 6) {
                val a = (PI / 3 * k).toFloat()
                val px = center.x + cos(a) * r
                val py = center.y + sin(a) * r
                if (k == 0) corePath.moveTo(px, py) else corePath.lineTo(px, py)
            }
        } else {
            corePath.moveTo(center.x, center.y - r * 1.15f)
            corePath.lineTo(center.x + r, center.y)
            corePath.lineTo(center.x, center.y + r * 1.15f)
            corePath.lineTo(center.x - r, center.y)
        }
        corePath.close()
        drawPath(corePath, body)
        drawPath(corePath, KColors.background, style = outline)
        drawCircle(KColors.light, r * 0.28f, center, alpha = if (sim.isDown) 0.2f else 0.85f)
    }

    /** Rotating rays and three orbiting motes: an invented solar glyph, no real-world symbol. */
    private fun DrawScope.sunHalo(center: Offset, r: Float, color: Color) {
        rotate(degrees = time * 25f, pivot = center) {
            for (k in 0 until 10) {
                val a = (2 * PI / 10 * k).toFloat()
                val inner = r * (if (k % 2 == 0) 1.45f else 1.6f)
                val outer = r * (if (k % 2 == 0) 2.05f else 1.85f)
                drawLine(
                    color.copy(alpha = 0.75f),
                    Offset(center.x + cos(a) * inner, center.y + sin(a) * inner),
                    Offset(center.x + cos(a) * outer, center.y + sin(a) * outer),
                    strokeWidth = thin.width,
                    cap = StrokeCap.Round,
                )
            }
        }
        orbit(center, r * 2.5f, color, speed = 0.8f)
    }

    /** Two counter-rotating crescents and three orbiting motes: an invented lunar glyph. */
    private fun DrawScope.moonHalo(center: Offset, r: Float, color: Color) {
        val ring = Size(r * 3.6f, r * 3.6f)
        val topLeft = Offset(center.x - ring.width / 2, center.y - ring.height / 2)
        drawArc(color.copy(alpha = 0.7f), time * 30f, 120f, false, topLeft, ring, style = thin)
        drawArc(color.copy(alpha = 0.7f), time * 30f + 180f, 120f, false, topLeft, ring, style = thin)
        val inner = Size(r * 2.8f, r * 2.8f)
        drawArc(
            color.copy(alpha = 0.45f), -time * 45f, 70f, false,
            Offset(center.x - inner.width / 2, center.y - inner.height / 2), inner, style = thin,
        )
        orbit(center, r * 2.4f, color, speed = -0.6f)
    }

    private fun DrawScope.orbit(center: Offset, radius: Float, color: Color, speed: Float) {
        for (k in 0 until 3) {
            val a = time * speed + (2 * PI / 3 * k).toFloat()
            val dot = Offset(center.x + cos(a) * radius, center.y + sin(a) * radius)
            drawCircle(color, radius * 0.05f, dot, alpha = 0.9f)
            drawCircle(color, radius * 0.12f, dot, alpha = 0.12f, blendMode = BlendMode.Plus)
        }
    }

    private fun DrawScope.drawHp(sim: HalfSimulation, box: Letterbox) {
        val pip = box.length(0.022)
        val gap = pip * 3f
        val y = box.top + gap
        // Own hit points near our outer edge, the opponent's near the seam.
        val ownStart = box.x(side.outerX) + if (side == Side.Left) gap else -gap
        val opponentStart = box.x(side.borderX) + if (side == Side.Left) -gap else gap
        val step = if (side == Side.Left) gap else -gap
        for (i in 0 until sim.rules.maxHp) {
            pipAt(Offset(ownStart + i * step, y), pip, own, filled = i < sim.hp)
            pipAt(Offset(opponentStart - i * step, y), pip, opponent, filled = i < sim.opponentHp)
        }
    }

    private fun DrawScope.pipAt(center: Offset, radius: Float, color: Color, filled: Boolean) {
        if (filled) {
            drawCircle(color, radius * 2f, center, alpha = 0.12f, blendMode = BlendMode.Plus)
            drawCircle(color, radius, center)
        } else {
            drawCircle(color.copy(alpha = 0.35f), radius, center, style = thin)
        }
    }

    private companion object {
        /** Drawn slightly larger than the collision radius so it stays readable. */
        const val PROJECTILE_DRAW_RADIUS = 0.022
        const val TRAIL_PERIOD = 1f / 60f
        const val TONE_OWN = 0
        const val TONE_OPPONENT = 1
        const val TONE_LIGHT = 2
    }
}
