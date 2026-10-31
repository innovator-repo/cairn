package app.cairn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp

/** A rounded pill: a shortcut chip, a view switch or an area filter. 48 dp tall to tap. */
@Composable
fun Pill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    dot: Color? = null,
    filled: Boolean = false,
) {
    val c = LocalCairnColors.current
    val bg = when { selected -> c.raised; filled -> c.accent; else -> Color.Transparent }
    val fg = if (filled) c.onAccent else if (selected) c.ink else c.inkSecondary
    Box(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .background(bg, RoundedCornerShape(50))
                .border(1.dp, if (selected) c.inkSecondary.copy(alpha = .5f) else c.hairline, RoundedCornerShape(50))
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { this.selected = selected }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (dot != null) Box(Modifier.size(9.dp).background(dot, CircleShape))
            Text(label, color = fg, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Three stacked stones: Cairn's emblem. */
@Composable
fun Emblem(modifier: Modifier = Modifier) {
    val c = LocalCairnColors.current
    Canvas(modifier.size(28.dp).semantics { contentDescription = "Cairn" }) {
        val w = size.width
        val stones = listOf(
            Triple(.78f, .30f, .70f), Triple(.56f, .26f, .42f), Triple(.34f, .22f, .16f),
        )
        for ((i, s) in stones.withIndex()) {
            val sw = w * s.first; val sh = w * s.second
            val col = if (i == 2) c.accent else c.inkSecondary
            drawRoundRect(col, Offset((w - sw) / 2f, w * s.third + w * 0.1f), Size(sw, sh), CornerRadius(sh / 2f, sh / 2f))
        }
    }
}

