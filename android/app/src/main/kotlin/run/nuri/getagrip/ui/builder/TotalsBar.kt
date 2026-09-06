// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.MaxTable
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette

/// Two quiet lines and, when the plan gets silly, one advisory that FLAGS and never blocks
/// — an hour of no-hangs is a choice, not an error.
///
/// The exact figure, not the estimate: the top bar's subtitle already carries "≈21 min ·
/// 36 pulls" as the price of every edit, and this is where the arithmetic is spelled out.
@Composable
fun TotalsBar(
    draft: RoutineDraft,
    modifier: Modifier = Modifier,
    maxes: MaxTable = MaxTable(),
) {
    val palette = LocalGripPalette.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            PlanMath.totalsLine(draft.plan),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = palette.inkSecondary,
        )
        // null when the mode has one side, so the copy drops "per side" instead of dividing
        // by a hand that isn't there.
        PlanMath.perSideLine(draft.plan)?.let { perSide ->
            Text(
                perSide,
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkTertiary,
            )
        }
        if (draft.plan.executable.sets.any { it.targetBand == null && PlanMath.targetPercent(it, draft.plan) != null }) {
            Text(
                tr("Percentage targets use your saved maxes. These may no longer reflect your current strength."),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkTertiary,
            )
        }
        if (PlanMath.missingBenchmarkGripCount(draft.plan, maxes) > 0) {
            Text(
                tr("Some percentage targets have no saved max, so they will show no target."),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = palette.armed,
            )
        }
        if (BuilderDraft.isVeryLong(draft)) {
            Row(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = palette.armed,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    tr("That's over an hour. Fine if you mean it."),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = palette.armed,
                )
            }
        }
    }
}

@Preview(name = "TotalsBar", showBackground = true, widthDp = 360)
@Composable
private fun TotalsBarPreview() {
    GetAGripTheme {
        Column(Modifier.padding(16.dp)) { TotalsBar(RoutineDraft.starter) }
    }
}
