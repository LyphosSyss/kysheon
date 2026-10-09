package epheis.lyphossyss.kysheon.app.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import epheis.lyphossyss.kysheon.app.theme.KColors
import epheis.lyphossyss.kysheon.core.HalfSimulation
import epheis.lyphossyss.kysheon.core.ProjectilePool
import epheis.lyphossyss.kysheon.core.Side

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
    fun length(logical: Double): Float = (logical * scale).toFloat()
    fun logicalY(px: Float): Double = ((px - top) / scale).toDouble()
}

/**
 * Draws one half. Reads the simulation in the draw phase only (no recomposition per frame) and allocates
 * nothing per frame: paths and the dash effect are reused, offsets are inline value classes.
 */
class ArenaRenderer(private val side: Side) {
    private val fighterPath = Path()
    private val seamEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f))
    private val outline = Stroke(width = 3f)
    private val ownColor = if (side == Side.Left) KColors.host else KColors.guest
    private val opponentColor = if (side == Side.Left) KColors.guest else KColors.host

    private var lastHp = -1
    private var flashUntilNanos = 0L

    /** [showProjectiles] is false once the round is over: the simulation is frozen, its projectiles would hang mid-air. */
    fun DrawScope.drawArena(sim: HalfSimulation, alpha: Double, box: Letterbox, frameNanos: Long, showProjectiles: Boolean) {
        drawRect(KColors.surface, Offset(box.left, box.top), Size(box.scale, box.length(sim.aspect)))
        drawSeam(box, sim.aspect)
        if (showProjectiles) {
            drawProjectiles(sim.localProjectiles, alpha, box, ownColor)
            drawProjectiles(sim.incomingProjectiles, alpha, box, opponentColor)
        }
        drawFighter(sim, alpha, box, frameNanos)
        drawHp(sim, box)
    }

    private fun DrawScope.drawSeam(box: Letterbox, aspect: Double) {
        val x = box.x(side.borderX) + if (side == Side.Left) -2f else 2f
        drawLine(
            color = opponentColor.copy(alpha = 0.6f),
            start = Offset(x, box.top),
            end = Offset(x, box.y(aspect)),
            strokeWidth = 4f,
            pathEffect = seamEffect,
        )
    }

    private fun DrawScope.drawProjectiles(pool: ProjectilePool, alpha: Double, box: Letterbox, color: Color) {
        val radius = box.length(PROJECTILE_DRAW_RADIUS)
        for (slot in 0 until pool.capacity) {
            if (!pool.isActive(slot)) continue
            val x = box.x(pool.interpolatedX(slot, alpha))
            val y = box.y(pool.interpolatedY(slot, alpha))
            // Short trail along the velocity: reads as motion without any particle system.
            val trail = box.length(TRAIL_SECONDS)
            drawLine(
                color = color.copy(alpha = 0.35f),
                start = Offset(x - (pool.vx(slot) * trail).toFloat(), y - (pool.vy(slot) * trail).toFloat()),
                end = Offset(x, y),
                strokeWidth = radius * 1.4f,
                cap = StrokeCap.Round,
            )
            drawCircle(color, radius, Offset(x, y))
        }
    }

    private fun DrawScope.drawFighter(sim: HalfSimulation, alpha: Double, box: Letterbox, frameNanos: Long) {
        if (lastHp >= 0 && sim.hp < lastHp) flashUntilNanos = frameNanos + FLASH_NANOS
        lastHp = sim.hp
        val cx = box.x(sim.fighterX)
        val cy = box.y(sim.fighterInterpolatedY(alpha))
        val r = box.length(sim.rules.fighterRadius)
        fighterPath.reset()
        if (side == Side.Left) {
            // Host: hexagon.
            fighterPath.moveTo(cx + r, cy)
            fighterPath.lineTo(cx + r * 0.5f, cy - r * 0.866f)
            fighterPath.lineTo(cx - r * 0.5f, cy - r * 0.866f)
            fighterPath.lineTo(cx - r, cy)
            fighterPath.lineTo(cx - r * 0.5f, cy + r * 0.866f)
            fighterPath.lineTo(cx + r * 0.5f, cy + r * 0.866f)
        } else {
            // Guest: diamond.
            fighterPath.moveTo(cx, cy - r * 1.1f)
            fighterPath.lineTo(cx + r, cy)
            fighterPath.lineTo(cx, cy + r * 1.1f)
            fighterPath.lineTo(cx - r, cy)
        }
        fighterPath.close()
        val color = when {
            sim.isDown -> KColors.muted
            frameNanos < flashUntilNanos -> KColors.text
            else -> ownColor
        }
        drawPath(fighterPath, color)
        drawPath(fighterPath, KColors.background, style = outline)
    }

    private fun DrawScope.drawHp(sim: HalfSimulation, box: Letterbox) {
        val pip = box.length(0.022)
        val gap = pip * 3f
        val y = box.top + gap
        // Own hit points near our outer edge, the opponent's near the seam.
        val ownStart = box.x(side.outerX) + if (side == Side.Left) gap else -gap
        val opponentStart = box.x(side.borderX) + if (side == Side.Left) -gap else gap
        val ownStep = if (side == Side.Left) gap else -gap
        for (i in 0 until sim.rules.maxHp) {
            val ownFilled = i < sim.hp
            val opponentFilled = i < sim.opponentHp
            drawCircle(if (ownFilled) ownColor else KColors.surfaceHigh, pip, Offset(ownStart + i * ownStep, y))
            drawCircle(
                if (opponentFilled) opponentColor else KColors.surfaceHigh,
                pip,
                Offset(opponentStart - i * ownStep, y),
            )
        }
    }

    private companion object {
        /** Drawn slightly larger than the collision radius so it stays readable. */
        const val PROJECTILE_DRAW_RADIUS = 0.025
        const val TRAIL_SECONDS = 0.06
        const val FLASH_NANOS = 150_000_000L
    }
}
