package epheis.lyphossyss.kysheon.app.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
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

/** Original Kysheon palette: dark slate, ember for the host side, teal for the guest side. */
object KColors {
    val background = Color(0xFF0E1116)
    val surface = Color(0xFF171C24)
    val surfaceHigh = Color(0xFF222936)
    val text = Color(0xFFE8ECF2)
    val muted = Color(0xFF8A94A6)
    val host = Color(0xFFFF8A4C)
    val guest = Color(0xFF4FD1C5)
    val danger = Color(0xFFFF5D6C)
    val ok = Color(0xFF7BD88F)
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
