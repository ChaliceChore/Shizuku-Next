package moe.shizuku.manager.ui.component

import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * How far a rightward drag has to travel before it means the same thing as the back arrow.
 *
 * Far enough that a page's own horizontal movement or a sloppy tap is not read as a gesture, and
 * short enough that it does not have to cross the screen.
 */
private const val SWIPE_BACK_DP = 72f

/**
 * A right swipe on a whole-screen view means its back arrow.
 *
 * The module's page, its action console and the catalog all take the screen, and the app's tab
 * pager no longer takes their horizontal swipes - so without this a swipe to the right would sit
 * there doing nothing. Read as a back gesture, it does the one thing it can: closes the view and
 * leaves the module list behind it.
 *
 * Only a sideways drag counts. [detectHorizontalDragGestures] waits for horizontal slop before it
 * reports anything, so a list under this still scrolls vertically as it always did.
 */
@Composable
fun Modifier.moduleSwipeBack(onBack: () -> Unit): Modifier {
    val threshold = with(LocalDensity.current) { SWIPE_BACK_DP.dp.toPx() }
    var travelled by remember { mutableStateOf(0f) }

    return this.pointerInput(threshold) {
        detectHorizontalDragGestures(
            onDragStart = { travelled = 0f },
            onDragCancel = { travelled = 0f },
            onDragEnd = {
                if (travelled >= threshold) onBack()
                travelled = 0f
            },
            onHorizontalDrag = { _, amount -> travelled += amount }
        )
    }
}

/**
 * The same gesture for a view Compose cannot see the touches of.
 *
 * A WebView is a real Android view inside a Compose screen, and Compose's pointer input never
 * receives a touch that lands on one - so a module's page has to read its own touch stream for the
 * drag the wrapper above would otherwise catch. Nothing is consumed: this answers false to every
 * event, so taps, scrolls and text selection on the page still reach the page.
 */
fun View.swipeBackToModules(onBack: () -> Unit) {
    val threshold = SWIPE_BACK_DP * resources.displayMetrics.density
    var startX = 0f
    var startY = 0f
    var fired = false

    setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                fired = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - startX
                val dy = event.y - startY

                if (!fired && dx >= threshold && dx >= abs(dy)) {
                    fired = true
                    onBack()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fired = false
        }

        false
    }
}
