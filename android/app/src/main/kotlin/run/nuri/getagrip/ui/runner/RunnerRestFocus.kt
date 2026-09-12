// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.runner

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

/** The current phase and remaining rest own the largest type. The next hand stays
 * gray; only a real grip change earns orange. Reads coarse snapshot state only.
 */
@Composable
internal fun RunnerRestFocus(snapshot: RunnerSnapshot) {
    val palette = LocalGripPalette.current
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val hand = tr(when (snapshot.side) {
        Side.left -> "Left hand next"
        Side.right -> "Right hand next"
        Side.both -> "Both hands next"
        null -> "Next grip"
    })
    val phase = tr(when {
        snapshot.phase is RunnerPhase.Paused -> "PAUSED"
        snapshot.isSetBreak -> "SET BREAK"
        else -> "REST"
    })
    val phaseColor = if (snapshot.phase is RunnerPhase.Paused) palette.armedText else palette.inkPrimary
    Column(
        Modifier.fillMaxWidth().then(if (largeText) Modifier else Modifier.fillMaxSize())
            .testTag("runner.restFocus"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(hand, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, color = palette.inkSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("runner.restFocus.hand"))
            snapshot.grip?.let { grip ->
                Text(grip.line, style = MaterialTheme.typography.titleMedium,
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
                    style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    color = palette.inkSecondary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("runner.restFocus.target"))
            }
        }
        Text(phase, style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold, color = phaseColor, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("runner.restFocus.phase"))
        if (largeText) {
            RestCountdown(snapshot.secondsShown, phase,
                Modifier.height(with(LocalDensity.current) { 104.sp.toDp() }))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RestCount("Set", snapshot.setNumber ?: 1, snapshot.setCount,
                    "runner.restFocus.setCount", Modifier.weight(1f))
                RestCount("Pull", nextPull(snapshot), snapshot.plannedRepCount,
                    "runner.restFocus.pullCount", Modifier.weight(1f))
            }
        } else {
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RestCount("Set", snapshot.setNumber ?: 1, snapshot.setCount,
                    "runner.restFocus.setCount", Modifier.weight(1f))
                RestCountdown(snapshot.secondsShown, phase, Modifier.weight(1.8f))
                RestCount("Pull", nextPull(snapshot), snapshot.plannedRepCount,
                    "runner.restFocus.pullCount", Modifier.weight(1f))
            }
        }
    }
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

@Composable
private fun RestCountdown(seconds: Int, phase: String, modifier: Modifier) {
    val palette = LocalGripPalette.current
    Row(modifier, verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)) {
        BasicText("$seconds", maxLines = 1,
            style = TextStyle(fontSize = 80.sp, fontWeight = FontWeight.Thin,
                color = palette.inkPrimary, fontFeatureSettings = "tnum", letterSpacing = (-0.02).em),
            autoSize = TextAutoSize.StepBased(minFontSize = 40.sp, maxFontSize = 80.sp),
            modifier = Modifier.weight(1f, fill = false).testTag("runner.restFocus.countdown")
                .semantics { contentDescription = L10n.tr("%s, %d seconds remaining", phase, seconds) })
        Text(tr("s"), style = MaterialTheme.typography.titleLarge, color = palette.inkTertiary,
            modifier = Modifier.padding(bottom = 10.dp).clearAndSetSemantics {})
    }
}
