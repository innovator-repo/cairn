package app.cairn.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * How far the sheet is pulled up past its peek: 0 collapsed, [travel] fully open. Dragging the header
 * moves it directly; dragging a list inside it scrolls the list first when open, and only pulls the
 * sheet down once the list is at its top.
 */
class SheetState internal constructor(private val scope: CoroutineScope) {
    var reveal by mutableFloatStateOf(0f); internal set
    internal var travel = 0f
    private var job: Job? = null

    val expanded: Boolean get() = travel > 0f && reveal > travel / 2f

    /** [dy] is the finger's movement, positive down. Returns how much of it the sheet used. */
    fun dragBy(dy: Float): Float {
        job?.cancel()
        val old = reveal
        val new = (old - dy).coerceIn(0f, travel)
        reveal = new
        return old - new
    }

    /** Settle to the nearer end, or the end the fling points to. [vy] is px/s, positive down. */
    fun settle(vy: Float) {
        val target = when {
            vy < -SETTLE_VELOCITY -> travel
            vy > SETTLE_VELOCITY -> 0f
            reveal > travel / 2f -> travel
            else -> 0f
        }
        animateTo(target)
    }

    fun expand() = animateTo(travel)
    fun collapse() = animateTo(0f)

    private fun animateTo(target: Float) {
        job?.cancel()
        job = scope.launch { animate(reveal, target, animationSpec = tween(260)) { v, _ -> reveal = v } }
    }

    private companion object { const val SETTLE_VELOCITY = 500f }
}

@Composable
fun rememberSheetState(): SheetState {
    val scope = rememberCoroutineScope()
    return remember { SheetState(scope) }
}

/**
 * A bottom sheet that shows [peek] of itself and pulls up to about 90% of the screen. [header] is
 * always visible and drags the sheet; [body] fills the rest and may hold a LazyColumn, which scrolls
 * properly when the sheet is open.
 */
@Composable
fun PullUpSheet(
    state: SheetState,
    peek: Dp,
    modifier: Modifier = Modifier,
    containerColor: androidx.compose.ui.graphics.Color,
    header: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val fullPx = constraints.maxHeight.toFloat()
        val sheetH = fullPx * 0.9f
        val peekPx = with(density) { peek.toPx() }
        state.travel = (sheetH - peekPx).coerceAtLeast(0f)
        if (state.reveal > state.travel) state.reveal = state.travel

        val connection = remember(state) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    val dy = available.y
                    return if (dy < 0f && state.reveal < state.travel) Offset(0f, state.dragBy(dy)) else Offset.Zero
                }
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    val dy = available.y
                    return if (dy > 0f && state.reveal > 0f) Offset(0f, state.dragBy(dy)) else Offset.Zero
                }
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (state.reveal > 0.5f && state.reveal < state.travel - 0.5f) { state.settle(available.y); return available }
                    return Velocity.Zero
                }
                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    if (state.reveal > 0.5f && state.reveal < state.travel - 0.5f) state.settle(available.y)
                    return Velocity.Zero
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(density) { sheetH.toDp() })
                .offset { IntOffset(0, (sheetH - peekPx - state.reveal).roundToInt()) }
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .nestedScroll(connection)
                .testTag("sheet"),
            color = containerColor,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.testTag("sheetHeader").draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { state.dragBy(it) },
                        onDragStopped = { state.settle(it) },
                    ),
                ) { header() }
                body()
            }
        }
    }
}
