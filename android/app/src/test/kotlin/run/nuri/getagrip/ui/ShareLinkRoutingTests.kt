// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import android.content.Intent
import run.nuri.getagrip.ShareLinkRouting
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.RoutineShare
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// **How a shared routine actually reaches Today**, tested at the seam where a phone hands
/// the app a string.
///
/// `MainActivity` does exactly two things with an incoming intent — ask whether it is ours,
/// and if so drop the URL in the store's inbox — and both halves are worth pinning: routing
/// something that is NOT ours would have the app alerting about another handler's link, and
/// failing to route one that IS ours loses a code somebody's friend just showed them.
///
/// TRANSLATION NOTE: iOS gets this for free from `onOpenURL`, which only ever fires for URLs
/// the app declared. Android delivers whatever an intent filter matched — plus whatever any
/// other app chose to launch this activity with — so the predicate is the app's own and has
/// to be tested. `Intent.ACTION_VIEW` is a plain string constant, so this stays a JVM test
/// with no Robolectric runtime behind it.
class ShareLinkRoutingTests {

    /// The shape the intent filter declares, and the shape `RoutineShare.url` mints.
    @Test
    fun aRoutineLinkIsRouted() {
        val url = "getagrip://routine#H4sIAAAAAAAA"
        assertEquals(url, ShareLinkRouting.routineLink(Intent.ACTION_VIEW, url))
    }

    /// **A foreign link is left completely alone** — not refused, not alerted about. Another
    /// app's link is not this app's business, and an error dialog about one would be
    /// answering for somebody else's handler.
    @Test
    fun aForeignLinkIsIgnored() {
        assertNull(ShareLinkRouting.routineLink(Intent.ACTION_VIEW, "https://example.com/blog"))
        assertNull(ShareLinkRouting.routineLink(Intent.ACTION_VIEW, "otherapp://routine#payload"))
        assertNull(ShareLinkRouting.routineLink(Intent.ACTION_VIEW, "mailto:someone@example.com"))
    }

    /// Our scheme with the wrong host is somebody typing, or a future feature that does not
    /// exist yet. `isRoutineLink` answers no, and the app stays quiet.
    @Test
    fun ourSchemeWithAnotherHostIsIgnored() {
        assertNull(ShareLinkRouting.routineLink(Intent.ACTION_VIEW, "getagrip://session#x"))
    }

    /// The ordinary launch. No data, nothing to route — and this is the one that fires on
    /// every single cold start, so it must cost nothing and say nothing.
    @Test
    fun aLauncherIntentRoutesNothing() {
        assertNull(ShareLinkRouting.routineLink(Intent.ACTION_MAIN, null))
        assertNull(ShareLinkRouting.routineLink(Intent.ACTION_VIEW, null))
        assertNull(ShareLinkRouting.routineLink(null, null))
    }

    /// `ACTION_VIEW` only. A `getagrip://` URL can ride an `ACTION_SEND` as plain text, but
    /// the app declares no SEND filter, so accepting one would be answering for a door that
    /// does not exist.
    @Test
    fun onlyAViewIntentRoutes() {
        val url = "getagrip://routine#H4sIAAAAAAAA"
        assertNull(ShareLinkRouting.routineLink(Intent.ACTION_SEND, url))
    }

    /// **A link with our shape but no payload IS routed**, deliberately. It is ours to answer
    /// for, and the store turns it into a readable "this code couldn't be read" — silence
    /// would be indistinguishable from a tap that missed.
    @Test
    fun ourSchemeWithNoPayloadIsStillOursToAnswerFor() {
        assertNotNull(ShareLinkRouting.routineLink(Intent.ACTION_VIEW, "getagrip://routine"))
    }

    /// The https-shaped form the decoder already accepts, for the universal link that is
    /// still out of scope. The MANIFEST declares only the custom scheme (an https filter
    /// needs a verified domain), so nothing routes one here today — but the predicate must
    /// keep saying yes, or a code minted by a future build cannot be pasted in either.
    @Test
    fun theHttpsFormIsRecognisedEvenThoughNoFilterDeclaresIt() {
        val url = "https://nuri.run/getagrip/routine#H4sIAAAAAAAA"
        assertTrue(RoutineShare.isRoutineLink(url))
        assertEquals(url, ShareLinkRouting.routineLink(Intent.ACTION_VIEW, url))
    }

    /// The whole round trip in one test: a routine becomes a URL, the URL survives the
    /// routing gate, and what comes back out the other side is the same plan. This is the
    /// path a scan takes end to end, and it is the one that would break silently if either
    /// half of `RoutineShare` and this predicate ever drifted apart.
    @Test
    fun aSharedRoutineSurvivesTheWholeTrip() {
        val draft = RoutineDraft(
            plan = SessionPlan(
                name = "Max day",
                sets = listOf(SetPlan(grip = GripSpec(edgeMM = 20), repsPerSide = 4)),
                holdSeconds = 7,
                restSeconds = 180,
            ),
            sessionsPerDay = 1,
        )
        val url = assertNotNull(RoutineShare.url(draft))

        val routed = assertNotNull(ShareLinkRouting.routineLink(Intent.ACTION_VIEW, url))
        val landed = RoutineShare.draft(routed)

        assertEquals("Max day", landed.plan.name)
        assertEquals(1, landed.plan.sets.size)
        assertEquals(20, landed.plan.sets[0].grip.edgeMM)
        assertEquals(4, landed.plan.sets[0].repsPerSide)
        assertEquals(7, landed.plan.holdSeconds)
        assertEquals(180, landed.plan.restSeconds)
        assertEquals(1, landed.sessionsPerDay)
        // Never inherited from the wire: a code carrying its author's id would mint a routine
        // wearing somebody else's identity, and reminders are personal hours the payload
        // deliberately omits.
        assertNull(landed.templateID)
        assertTrue(!landed.remindersEnabled)
    }
}
