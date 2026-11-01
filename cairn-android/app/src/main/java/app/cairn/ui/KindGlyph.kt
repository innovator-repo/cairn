package app.cairn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.cairn.data.Kind
import app.cairn.data.Shape

/**
 * The single place a kind becomes a shape: note circle, repo square, file page, issue triangle,
 * link diamond. The map, the legend, search results, node cards and lists all draw through this,
 * so a kind never looks different in two places.
 */
fun DrawScope.drawKindGlyph(kind: Kind, cx: Float, cy: Float, r: Float, color: Color, alpha: Float, path: Path) {
    when (kind.shape) {
        Shape.CIRCLE -> drawCircle(color, r, Offset(cx, cy), alpha)
        Shape.SQUARE -> drawRect(color, Offset(cx - r, cy - r), Size(2 * r, 2 * r), alpha)
        Shape.PAGE -> drawRoundRect(color, Offset(cx - r * 0.8f, cy - r * 1.1f), Size(r * 1.6f, r * 2.2f), CornerRadius(r * 0.3f), alpha = alpha)
        Shape.TRIANGLE -> {
            path.rewind()
            path.moveTo(cx, cy - r * 1.25f); path.lineTo(cx + r * 1.15f, cy + r * 0.85f); path.lineTo(cx - r * 1.15f, cy + r * 0.85f); path.close()
            drawPath(path, color, alpha)
        }
        Shape.DIAMOND -> {
            path.rewind()
            path.moveTo(cx, cy - r * 1.2f); path.lineTo(cx + r * 1.1f, cy); path.lineTo(cx, cy + r * 1.2f); path.lineTo(cx - r * 1.1f, cy); path.close()
            drawPath(path, color, alpha)
        }
    }
}

/** A kind's shape as a small icon, for legends, lists, cards and results. */
@Composable
fun KindIcon(kind: Kind, color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val path = remember { Path() }
    Canvas(modifier.size(size)) {
        drawKindGlyph(kind, this.size.width / 2f, this.size.height / 2f, this.size.minDimension * 0.30f, color, 1f, path)
    }
}
