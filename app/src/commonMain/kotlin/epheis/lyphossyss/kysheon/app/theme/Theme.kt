package epheis.lyphossyss.kysheon.app.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Original Kysheon palette, sacred direction: night sky, the Sun's gold on the host side, the Moon's silver on the guest side. */
object KColors {
    val background = Color(0xFF0B0D16)
    val surface = Color(0xFF151A26)
    val surfaceHigh = Color(0xFF212838)
    val text = Color(0xFFEEF0F6)
    val muted = Color(0xFF8B93A7)
    val host = Color(0xFFF4B942)
    val guest = Color(0xFFA9C9F2)
    val light = Color(0xFFFFF6E0)
    val danger = Color(0xFFFF6B7A)
    val ok = Color(0xFF8FDDA0)
}

@Composable
fun KText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = KColors.text,
    size: TextUnit = 16.sp,
    weight: FontWeight = FontWeight.Normal,
    monospace: Boolean = false,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        ),
    )
}

@Composable
fun KButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = KColors.surfaceHigh,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) accent else KColors.surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        KText(
            text = label,
            // Dark text on light accents, light text on dark ones.
            color = when {
                !enabled -> KColors.muted
                accent.luminance() > 0.25f -> KColors.background
                else -> KColors.text
            },
            weight = FontWeight.SemiBold,
        )
    }
}

/** On/off switch drawn with foundation only, exposed as a toggleable control to accessibility services. */
@Composable
fun KSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(width = 52.dp, height = 30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (checked) KColors.host else KColors.surfaceHigh)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(4.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(if (checked) KColors.background else KColors.muted),
        )
    }
}

/**
 * Horizontal slider for a value in [0, 1]: drag or tap on the track. Exposes a progress range and a set-progress
 * action to accessibility services. [onValueChangeFinished] fires when the finger lifts (e.g. to preview a sound).
 */
@Composable
fun KSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: () -> Unit = {},
) {
    val current = androidx.compose.runtime.rememberUpdatedState(onValueChange)
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .height(36.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                setProgress { target ->
                    current.value(target.coerceIn(0f, 1f))
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    current.value((down.position.x / size.width).coerceIn(0f, 1f))
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        current.value((change.position.x / size.width).coerceIn(0f, 1f))
                        change.consume()
                        if (!change.pressed) break
                    }
                    onValueChangeFinished()
                }
            },
    ) {
        val y = size.height / 2
        val x = size.width * value.coerceIn(0f, 1f)
        drawLine(KColors.surfaceHigh, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 8f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(KColors.host, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(x, y), strokeWidth = 8f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(KColors.light, radius = size.height * 0.32f, center = androidx.compose.ui.geometry.Offset(x, y))
    }
}
