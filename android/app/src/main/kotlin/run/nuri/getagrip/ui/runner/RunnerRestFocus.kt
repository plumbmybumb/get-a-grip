// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.runner

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.rememberReduceMotion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import run.nuri.getagrip.engine.RunnerPhase
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.runner.RunnerSnapshot
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.armedText
import run.nuri.getagrip.ui.units.WeightUnits

/** Measure the familiar live header, then place only the visible header. Keeping
 * the measurement in the same pass avoids a one-frame jump or remembered pixel
 * height that goes stale after an Activity, font, density or window change.
 * The unplaced header neither draws nor exposes its controls to accessibility.
 * The graph stays a sibling, mounted at its original size throughout the session.
 */
@Composable
internal fun RestFocusHeaderFrame(
    focused: Boolean,
    liveHeader: @Composable () -> Unit,
    restHeader: @Composable () -> Unit,
) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Box(Modifier.fillMaxWidth().then(if (focused) Modifier.clearAndSetSemantics {} else Modifier)) {
                liveHeader()
            }
            if (focused) Box(Modifier.fillMaxWidth()) { restHeader() }
        },
    ) { measurables, constraints ->
        val baseline = measurables[0].measure(constraints.copy(minHeight = 0))
        val rest = if (focused) measurables[1].measure(if (largeText) {
            Constraints(minWidth = baseline.width, maxWidth = baseline.width)
        } else Constraints.fixed(baseline.width, baseline.height)) else null
        // The runner already scrolls at accessibility sizes. Let words take their
        // natural height there rather than compressing them into the live metrics.
        layout(baseline.width, maxOf(baseline.height, rest?.height ?: 0)) {
            if (rest != null) rest.placeRelative(0, 0) else baseline.placeRelative(0, 0)
        }
    }
}

/** What the rest is FOR: the next hand, its grip (orange only for a real grip change) and
 * the next target, with the phase stepped down to a badge. Reads coarse snapshot state only.
 *
 * **The countdown is not here** (iOS `RunnerRestFocusSummary`). It is the ambient numeral in
 * the open graph (`AmbientRestCountdown`), readable from the wall; carrying it a second time
 * in the panel said the same number twice a hand's width apart. The room goes to the grip you
 * are about to pull.
 */
///
/// At ordinary sizes it covers only the block above the time bar, which keeps its own counters
/// row beneath. At accessibility sizes it stands alone: the Set / Pull counts come back, with
/// `routineRow` (the routine's pills) riding above them.
@Composable
internal fun RunnerRestFocus(
    snapshot: RunnerSnapshot,
    routineRow: (@Composable () -> Unit)? = null,
) {
    val palette = LocalGripPalette.current
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val hand = tr(when (snapshot.side) {
        Side.left -> "Left hand next"
        Side.right -> "Right hand next"
        Side.both -> "Both hands next"
        null -> "Next grip"
    })
    val paused = snapshot.phase is RunnerPhase.Paused
    val phase = tr(when {
        paused -> "PAUSED"
        snapshot.isSetBreak -> "SET BREAK"
        else -> "REST"
    })
    Column(
        Modifier.fillMaxWidth().then(if (largeText) Modifier else Modifier.fillMaxSize())
            .testTag("runner.restFocus"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(hand, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, color = palette.inkSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("runner.restFocus.hand"))
            snapshot.grip?.let { grip ->
                Text(grip.line, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (snapshot.gripChangesNext) palette.armedText else palette.inkSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("runner.restFocus.grip").semantics {
                        contentDescription = if (snapshot.gripChangesNext) {
                            run.nuri.getagrip.engine.L10n.tr("New grip: %s", grip.spoken)
                        } else grip.spoken
                    })
            }
            snapshot.targetBand?.let { band ->
                Text(tr("Next target: %s–%s %s", WeightUnits.number(band.start),
                    WeightUnits.number(band.endInclusive), WeightUnits.symbol),
                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                    color = palette.inkSecondary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("runner.restFocus.target"))
            }
        }
        // One headline per surface: the hand is the instruction, and the giant countdown and
        // the wash already say "rest" at screen scale, so the phase word steps down to a badge.
        // PAUSED keeps its amber as a FILL with fixed dark ink — amber text cannot carry a word
        // this small on the light field.
        PhaseBadge(phase, paused)
        if (largeText) {
            routineRow?.invoke()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RestCount("Set", snapshot.setNumber ?: 1, snapshot.setCount,
                    "runner.restFocus.setCount", Modifier.weight(1f))
                RestCount("Pull", nextPull(snapshot), snapshot.plannedRepCount,
                    "runner.restFocus.pullCount", Modifier.weight(1f))
            }
        }
    }
}

/// The badge is DRAWN around a full-width, centred line rather than laid out as a hugging
/// capsule: a wrap-content label rounds its width down and reports a clipped line on some
/// densities, and a French "REPOS" must never read as cut.
@Composable
private fun PhaseBadge(phase: String, paused: Boolean) {
    val palette = LocalGripPalette.current
    val fill = if (paused) palette.armed else palette.inkTertiary.copy(alpha = 0.16f)
    var lineWidth by remember { mutableFloatStateOf(0f) }
    Text(phase, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
        color = if (paused) Color(0xFF1B1F25) else palette.inkSecondary, textAlign = TextAlign.Center,
        onTextLayout = { layout -> lineWidth = (0 until layout.lineCount).maxOfOrNull {
            layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f },
        modifier = Modifier.fillMaxWidth()
            .drawBehind {
                val width = (lineWidth + 20.dp.toPx()).coerceAtMost(size.width)
                val height = size.height + 8.dp.toPx()
                drawRoundRect(fill, topLeft = Offset((size.width - width) / 2, -4.dp.toPx()),
                    size = Size(width, height), cornerRadius = CornerRadius(height / 2))
            }
            .testTag("runner.restFocus.phase"))
}

/// **The countdown you can read from the wall** (iOS `RunnerView.ambientCountdown`): while the
/// clock is the only thing happening — the count-in, a rest, a paused rest — the seconds fill
/// the open graph, huge and thin. It is THE rest countdown; the panel does not repeat it.
///
/// It SNAPS: Compose's digit slide read as laggy on the phone, so the Android clocks never roll.
/// Secondary ink at 0.75, as iOS measured above 3:1 at this size.
@Composable
internal fun AmbientRestCountdown(snapshot: RunnerSnapshot, modifier: Modifier = Modifier) {
    val palette = LocalGripPalette.current
    val reduceMotion = rememberReduceMotion()
    val phase = L10n.tr(when {
        snapshot.phase is RunnerPhase.Paused -> "PAUSED"
        snapshot.phase is RunnerPhase.LeadIn -> "GET READY"
        snapshot.isSetBreak -> "SET BREAK"
        else -> "REST"
    })
    // A whisper of scale with the fade when it arrives; the fade alone under Reduce Motion.
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, Motion.state(reduceMotion)) }
    Box(
        modifier.graphicsLayer {
            alpha = shown.value
            val scale = if (reduceMotion) 1f else 0.96f + 0.04f * shown.value
            scaleX = scale; scaleY = scale
        }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            "${snapshot.secondsShown}",
            maxLines = 1,
            style = TextStyle(fontSize = AMBIENT_SIZE, fontWeight = FontWeight.Thin,
                color = palette.inkSecondary.copy(alpha = 0.75f), fontFeatureSettings = "tnum",
                letterSpacing = (-0.02).em, textAlign = TextAlign.Center),
            autoSize = TextAutoSize.StepBased(minFontSize = 40.sp, maxFontSize = AMBIENT_SIZE),
            modifier = Modifier.fillMaxWidth().testTag("runner.restFocus.countdown")
                .semantics { contentDescription = L10n.tr("%s, %d seconds remaining", phase, snapshot.secondsShown) },
        )
    }
}

private val AMBIENT_SIZE = 176.sp

/// The phases whose only news is the clock (iOS `showsAmbientCountdown`).
internal fun showsAmbientCountdown(phase: RunnerPhase): Boolean = when (phase) {
    is RunnerPhase.Resting, is RunnerPhase.LeadIn -> true
    is RunnerPhase.Paused -> phase.before is RunnerPhase.Resting || phase.before is RunnerPhase.LeadIn
    else -> false
}

private fun nextPull(snapshot: RunnerSnapshot) =
    (snapshot.completedRepCount + 1).coerceAtMost(snapshot.plannedRepCount)

@Composable
private fun RestCount(label: String, current: Int, total: Int, tag: String, modifier: Modifier) {
    val palette = LocalGripPalette.current
    Column(modifier.testTag(tag).semantics(mergeDescendants = true) {
        contentDescription = L10n.tr(if (label == "Set") "Set %d of %d" else "Pull %d of %d", current, total)
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(tr(label), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            color = palette.inkSecondary, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
        BasicText("$current / $total", maxLines = 1,
            style = MaterialTheme.typography.titleLarge.copy(color = palette.inkSecondary,
                fontFeatureSettings = "tnum", textAlign = TextAlign.Center),
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 22.sp),
            modifier = Modifier.fillMaxWidth().testTag("$tag.value"))
    }
}
