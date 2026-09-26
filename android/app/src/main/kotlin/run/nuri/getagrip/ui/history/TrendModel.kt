// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.history

import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.RepOutcome
import run.nuri.getagrip.engine.RepSummary
import run.nuri.getagrip.engine.SessionKind
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/// Everything History's "Load per grip" deck draws, built OFF the main thread from plain
/// values — the twin of iOS's `TrendModel`.
///
/// Built in the composition, the first open of History would decode every rep blob on the
/// main thread: a freeze growing every week of training. `TrendDeck` hands the feed's logs
/// and its write-once rep cache to `build` on a background dispatcher. The rules live here,
/// pure, where a test can reach them.
data class TrendModel(
    /// Most recently trained first: the FRONT card is the routine you are mid-way through.
    val routines: List<Routine>,
) {
    data class GripOption(val key: String, val grip: GripSpec, val count: Int)

    data class Point(val id: UUID, val date: Instant, val avgKg: Double)

    /// One card: one routine's grips, most-trained first, and each grip's series.
    data class Routine(
        val key: String,
        /// The routine's live name where it exists, else the newest frozen one in the group.
        val name: String,
        val grips: List<GripOption>,
        /// Oldest first, keyed by grip key.
        val series: Map<String, List<Point>>,
    ) {
        /// The shared selection when this routine trained it, else its own most-trained grip —
        /// so the card that DID train the chosen grip keeps it as you swipe back.
        fun selectedGrip(shared: String?): String? {
            if (shared != null && grips.any { it.key == shared }) return shared
            return grips.firstOrNull()?.key
        }
    }

    /// What the line under a chart says. The words are the screen's; the threshold is here.
    sealed interface Summary {
        data class Steady(val lastKg: Double) : Summary
        data class Up(val deltaKg: Double, val sessions: Int) : Summary
        data class Down(val deltaKg: Double, val sessions: Int) : Summary
    }

    companion object {
        /// Under half a kilo across a series is grip noise; calling it progress would be
        /// flattery. Kilograms whatever the display unit, as on iOS.
        const val STEADY_BAND_KG = 0.5

        /// A rep the LOAD chart may count: it finished, and a gauge was watching. Gauge-free
        /// reps log 0 kg (truthfully: nothing was measured), and charting those zeros dragged
        /// a grip's line to the floor. The month grid still counts those sessions; only the
        /// KILOGRAM chart ignores them.
        fun chartable(rep: RepSummary): Boolean = rep.outcome == RepOutcome.completed && rep.avgKg > 0

        /// The grouping key: the frozen `templateID` when present, else the frozen name. ID
        /// first so a renamed routine keeps ONE line; the name catches pre-ID sessions.
        ///
        /// Upper-case, as `uuidString` spells it, so a key means the same thing on both
        /// platforms.
        fun routineKey(templateID: UUID?, templateName: String): String =
            templateID?.toString()?.uppercase() ?: templateName

        fun summary(series: List<Point>): Summary? {
            val first = series.firstOrNull() ?: return null
            val last = series.last()
            val delta = last.avgKg - first.avgKg
            if (abs(delta) < STEADY_BAND_KG) return Summary.Steady(last.avgKg)
            return if (delta > 0) Summary.Up(abs(delta), series.size) else Summary.Down(abs(delta), series.size)
        }

        /// `logs` newest first, as the feed sorts them; only hang sessions are read.
        /// `routineNames` is the store's live-name map: a session whose routine no longer
        /// exists gets no card (Nuri, 2026-09-20). Deleting a routine deletes its sessions, so
        /// this catches rows from before that rule; both still list as sessions.
        ///
        /// `reps` is the feed's write-once cache (`HistoryFeed.reps`), so a rebuild after a
        /// save decodes only the new session. `isCancelled` lets a superseded build stop early.
        fun build(
            logs: List<WorkoutLogEntity>,
            routineNames: Map<UUID, String>,
            reps: (WorkoutLogEntity) -> List<RepSummary> = { it.reps },
            isCancelled: () -> Boolean = { false },
        ): TrendModel {
            class Group {
                var name: String? = null
                val gripCounts = LinkedHashMap<String, Pair<GripSpec, Int>>()
                /// Newest first while folding; reversed at the end.
                val series = HashMap<String, MutableList<Point>>()
            }
            val groups = HashMap<String, Group>()
            val order = mutableListOf<String>()

            for (log in logs) {
                if (isCancelled()) return TrendModel(emptyList())
                if (log.kind != SessionKind.hang) continue
                val key = routineKey(log.templateID, log.templateName)
                val group = groups.getOrPut(key) { Group() }
                // One pass per session: grip counts and each grip's time-weighted mean — a rep
                // dropped after a second must not weigh like a full hang (as in `avgKg`).
                val held = LinkedHashMap<String, Double>()
                val weighted = HashMap<String, Double>()
                var chartedAny = false
                for (rep in reps(log)) {
                    if (!chartable(rep)) continue
                    chartedAny = true
                    val grip = rep.grip.key
                    group.gripCounts[grip] = rep.grip to ((group.gripCounts[grip]?.second ?: 0) + 1)
                    held[grip] = (held[grip] ?: 0.0) + rep.heldSeconds
                    weighted[grip] = (weighted[grip] ?: 0.0) + rep.avgKg * rep.heldSeconds
                }
                for ((grip, seconds) in held) {
                    if (seconds <= 0) continue
                    group.series.getOrPut(grip) { mutableListOf() }
                        .add(Point(log.id, log.startedAt, (weighted[grip] ?: 0.0) / seconds))
                }
                // A card exists from the newest session that charted anything and is named from
                // it; a routine deleted from the store gets none.
                val live = log.templateID?.let { routineNames.containsKey(it) } ?: true
                if (chartedAny && live && group.name == null) {
                    group.name = log.templateID?.let { routineNames[it] } ?: log.templateName
                    order.add(key)
                }
            }

            val routines = order.mapNotNull { key ->
                val group = groups[key] ?: return@mapNotNull null
                val name = group.name ?: return@mapNotNull null
                val grips = group.gripCounts
                    .map { (gripKey, value) -> GripOption(gripKey, value.first, value.second) }
                    // Count descending, then key, so ties do not shuffle between launches.
                    .sortedWith(compareByDescending<GripOption> { it.count }.thenBy { it.key })
                Routine(key, name, grips, group.series.mapValues { it.value.reversed() })
            }
            return TrendModel(routines)
        }
    }
}
