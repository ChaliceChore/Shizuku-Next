package moe.shizuku.manager.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.ui.theme.LocalTelevision

/** The gap between the focused item and the outline drawn around it. */
private val OutlineGap = 4.dp
private val OutlineWidth = 3.dp

/** The most an outline's corners round, so a tall card is not drawn as a stadium. */
private val OutlineMaxCorner = 20.dp

/**
 * An outline around whatever is focused anywhere inside this, on a TV.
 *
 * A remote's focus is the only cursor a TV has, and a state layer on the focused item - however
 * strong - is a change of shade that has to be compared with its neighbours to be seen. An
 * outline a little way out from the item is the convention TV apps use for the same reason: it
 * is a shape, not a shade, and it reads against any colour the item itself happens to be.
 *
 * Drawn once here for the whole screen rather than by every button, switch, chip and card: the
 * focused item reports its bounds up the tree, and those are what the outline follows as focus
 * moves and as the item scrolls. Off a TV this is nothing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.televisionFocusOutline(): Modifier {
    if (!LocalTelevision.current) return this

    var container by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var focused by remember { mutableStateOf<Rect?>(null) }
    val color = MaterialTheme.colorScheme.primary

    return this
        .onGloballyPositioned { container = it }
        .onFocusedBoundsChanged { coordinates ->
            val box = container
            // Clipped to what is visible, so an item half scrolled under a bar is outlined
            // where it can be seen rather than over the bar.
            focused = if (coordinates != null && box != null && coordinates.isAttached && box.isAttached) {
                box.localBoundingBoxOf(coordinates)
            } else {
                null
            }
        }
        .drawWithContent {
            drawContent()
            val bounds = focused ?: return@drawWithContent
            if (bounds.isEmpty) return@drawWithContent
            val gap = OutlineGap.toPx()
            val outline = bounds.inflate(gap)
            val corner = minOf(bounds.height / 2f, OutlineMaxCorner.toPx()) + gap
            drawRoundRect(
                color = color,
                topLeft = outline.topLeft,
                size = outline.size,
                cornerRadius = CornerRadius(corner),
                style = Stroke(width = OutlineWidth.toPx())
            )
        }
}
