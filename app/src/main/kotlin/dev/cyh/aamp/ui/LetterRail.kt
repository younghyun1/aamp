package dev.cyh.aamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cyh.aamp.library.LetterIndex
import kotlinx.coroutines.launch

/**
 * Alphabetical sections of a list as the rail shows them: each label with the list position its
 * section starts at, in list order. Android Auto's order puts "#" last, but titles starting with a
 * digit sort first, so the rail follows the list and dragging down it always moves down the list.
 */
@Immutable
internal class RailSections(val labels: Array<String>, val positions: IntArray) {
    val size: Int get() = labels.size

    companion object {
        /** [letters] laid over a list; [positionOf] maps an entry to its position in that list. */
        fun of(letters: LetterIndex, positionOf: (entry: Int) -> Int = { it }): RailSections {
            val starts = IntArray(letters.size) { positionOf(letters.members(it)[0]) }
            val order = starts.indices.sortedBy { starts[it] }
            return RailSections(Array(order.size) { letters.labels[order[it]] }, IntArray(order.size) { starts[order[it]] })
        }
    }
}

/**
 * [content] with a letter rail along its end edge when [sections] has more than one section.
 * [content] gets the rail's width to keep clear of. Touching or dragging over a label calls
 * [scrollTo] with that section's position, without animation.
 */
@Composable
internal fun WithLetterRail(
    sections: RailSections?,
    scrollTo: suspend (position: Int) -> Unit,
    content: @Composable (railWidth: Dp) -> Unit,
) {
    val shown = sections != null && sections.size > 1
    Box(Modifier.fillMaxSize()) {
        content(if (shown) RAIL_WIDTH else 0.dp)
        if (sections != null && shown) {
            val scope = rememberCoroutineScope()
            var active by remember { mutableIntStateOf(-1) }
            LetterRail(
                sections,
                onActive = { active = it },
                onSelect = { scope.launch { scrollTo(sections.positions[it]) } },
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(RAIL_WIDTH),
            )
            if (active >= 0) SectionBubble(sections.labels[active], Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun LetterRail(sections: RailSections, onActive: (Int) -> Unit, onSelect: (Int) -> Unit, modifier: Modifier) {
    val colors = LocalColors.current
    val haptics = LocalHapticFeedback.current
    val active by rememberUpdatedState(onActive)
    val select by rememberUpdatedState(onSelect)
    Column(
        modifier.pointerInput(sections) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                var current = -1
                fun pick(y: Float) {
                    val section = (y / size.height * sections.size).toInt().coerceIn(0, sections.size - 1)
                    if (section == current) return
                    current = section
                    active(section)
                    select(section)
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                pick(down.position.y)
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                    pick(change.position.y)
                }
                active(-1)
            }
        },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val style = colors.caption.copy(color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        for (label in sections.labels) BasicText(label, style = style)
    }
}

/** The section being jumped to, large in the middle of the list. */
@Composable
private fun SectionBubble(label: String, modifier: Modifier) {
    val colors = LocalColors.current
    Box(
        modifier.size(88.dp).clip(RoundedCornerShape(20.dp)).background(colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = colors.heading.copy(fontSize = 40.sp, textAlign = TextAlign.Center))
    }
}

private val RAIL_WIDTH = 24.dp
