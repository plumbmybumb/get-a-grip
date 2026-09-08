// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.settings

import run.nuri.getagrip.store.diagnosticTimeline

import run.nuri.getagrip.ui.components.LocalFloatingTabBarInset
import android.icu.text.ListFormatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import run.nuri.getagrip.ui.theme.InstrumentSurface as Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import java.util.Locale
import run.nuri.getagrip.engine.GaugeKind
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.store.LocalSettingsStore
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.pressFeedback
import run.nuri.getagrip.ui.gauge.GaugeScreen
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.readablePageWidth
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.tour.LocalTourController

private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_GAUGE_PICKER = "gauge-picker"
private const val ROUTE_LIVE_GAUGE = "live-gauge"

/// The Settings tab: what the gauge is doing, a door to choosing one, a door to the live
/// gauge, and the statements the app owes whoever is using it.
///
/// The live gauge lives HERE rather than on Today. Today is the ritual — one routine, one
/// tap — and a second card offering a different, more interesting screen is the first
/// millimetre of the library the whole app exists to avoid.
///
/// TRANSLATION NOTE: iOS pushes these onto the tab's own `NavigationStack`. The Compose
/// twin is a NavHost scoped to this tab, which is also what makes predictive back work
/// unmodified: the system gesture pops this host, and the tab bar stays put.
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = ROUTE_SETTINGS, modifier = modifier.fillMaxSize()) {
        composable(ROUTE_SETTINGS) {
            SettingsRoot(
                onOpenGaugePicker = { nav.navigate(ROUTE_GAUGE_PICKER) },
                onOpenLiveGauge = { nav.navigate(ROUTE_LIVE_GAUGE) },
            )
        }
        composable(ROUTE_GAUGE_PICKER) {
            InnerScreen(title = tr("Gauge"), onBack = { nav.popBackStack() }) { padding ->
                // A tap applies AND dismisses — the same rule the grip picker follows.
                GaugePickerScreen(Modifier.padding(padding)) { nav.popBackStack() }
            }
        }
        composable(ROUTE_LIVE_GAUGE) {
            InnerScreen(title = tr("Live gauge"), onBack = { nav.popBackStack() }) { padding ->
                GaugeScreen(Modifier.padding(padding))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRoot(
    onOpenGaugePicker: () -> Unit,
    onOpenLiveGauge: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val device = LocalDeviceStore.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // `RootTabView`'s Scaffold has already inset this subtree for the status and
        // navigation bars; a nested Scaffold that adds its own would pad both twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeTopAppBar(
                title = { Text(tr("Settings")) },
                scrollBehavior = scrollBehavior,
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    scrolledContainerColor = palette.card,
                    titleContentColor = palette.inkPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .readablePageWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.hPadding)
                .padding(bottom = Metrics.spacing + LocalFloatingTabBarInset.current),
            verticalArrangement = Arrangement.spacedBy(Metrics.spacing),
        ) {
            // The device card first: it is the answer to the question that brings anyone
            // to this screen.
            Card {
                CapsLabel(tr("Device"))
                Spacer(Modifier.size(4.dp))
                LabelledValue(tr("Status"), device.state.label)
                device.deviceName?.let { LabelledValue(tr("Name"), it) }
                device.firmwareVersion?.let { LabelledValue(tr("Firmware"), it) }
                device.batteryFraction?.let {
                    LabelledValue(tr("Battery"), "${run.nuri.getagrip.ui.components.BatteryDisplay.percentage(it)}%")
                }
                // A synthetic number that looks like a measurement is worse than no number,
                // so demo mode is never allowed to be ambiguous here. Otherwise this states
                // the SELECTED gauge — the app drives eight of them, and one hardcoded
                // "Tindeq Progressor" was a claim about only the first.
                LabelledValue(
                    tr("Source"),
                    if (device.isMock) tr("Demo device") else device.gaugeKind.displayName,
                )
            }

            // WHICH gauge before the live gauge itself: choosing the device precedes using
            // it, and everything below this row describes whatever it selects.
            NavRow(
                icon = Icons.Outlined.SettingsInputAntenna,
                iconTint = palette.graphite,
                title = tr("Gauge"),
                subtitle = if (device.isMock) tr("Demo device") else device.gaugeKind.displayName,
                onClick = onOpenGaugePicker,
            )
            NavRow(
                icon = Icons.Outlined.Speed,
                iconTint = palette.bleu,
                title = tr("Live gauge"),
                subtitle = tr("Pull and watch the force in real time"),
                onClick = onOpenLiveGauge,
            )

            SupportCard(
                gauge = device.gaugeKind.displayName + if (device.isMock) " (${tr("Demo device")})" else "",
                diagnostics = {
                    if (device.diagnosticEntries.isEmpty()) null
                    else device.diagnosticEntries.diagnosticTimeline() +
                        "\n\n" + device.pipelineDiagnostics.report()
                },
            )
            LegalCard()
            AboutCard()
        }
    }
}

// MARK: - Gauge picker

/// Which device the app measures with.
///
/// A pushed screen rather than a card on Settings: eight rows, each owing a maker and — for
/// seven of them — the same honest caveat, is 400 dp that has no business on a screen you
/// open to check a battery level. It is also NOT a menu: a menu can show the names and
/// nothing else, and the one thing this list has to carry is which of these devices has
/// actually been tested.
///
/// Nothing connects: `selectGaugeKind` deliberately leaves that to a Connect tap, because
/// constructing a client is what raises the Bluetooth prompt.
@Composable
fun GaugePickerScreen(modifier: Modifier = Modifier, onSelected: () -> Unit) {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    val haptics = LocalHapticFeedback.current

    Column(
        modifier
            .fillMaxSize()
            .readablePageWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Metrics.hPadding)
            .padding(bottom = Metrics.spacing + LocalFloatingTabBarInset.current),
        verticalArrangement = Arrangement.spacedBy(Metrics.spacing),
    ) {
        // ONE single-choice list, so TalkBack says "2 of 8" as you move through it — which
        // it cannot infer from eight independent rows that merely happen to be selectable.
        Card(contentPadding = 0.dp) {
          Column(Modifier.selectableGroup()) {
            GaugeKind.selectable.forEachIndexed { index, kind ->
                if (index > 0) {
                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                // Demo mode is a client, not a kind, so nothing reads as selected while it
                // runs — and tapping the gauge you already had selected is how you leave it.
                val isSelected = device.gaugeKind == kind && !device.isMock
                val interactionSource = remember { MutableInteractionSource() }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            // A one-of-eight list is a radio group, not eight buttons — the
                            // role is what makes TalkBack say "selected" as a STATE of a
                            // choice rather than as an adjective on a button.
                            role = Role.RadioButton,
                        ) {
                            device.selectGaugeKind(kind)
                            // Feedback names its cause: the tick is the SELECTION, fired on
                            // the tap rather than on the value settling.
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelected()
                        }
                        // `scales = false`: this row shares one card with every other gauge,
                        // and scaling it on press would shrink the row's content while the
                        // card behind all of them stays put.
                        .pressFeedback(interactionSource, scales = false)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .semantics { selected = isSelected },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            kind.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = palette.inkPrimary,
                        )
                        Text(
                            detail(kind),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary,
                        )
                    }
                    if (isSelected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            // Graphite, the INTERACTIVE ink — bleu is reserved for live force.
                            tint = palette.graphite,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
          }
        }
        Footnotes()
    }
}

/// Maker, plus the one fact that changes how much to trust the numbers.
private fun detail(kind: GaugeKind): String {
    val parts = mutableListOf(kind.maker)
    if (!kind.capabilities.hardwareVerified) parts.add(L10n.tr("ported protocol"))
    return parts.joinToString(" · ")
}

/// ONE shared footnote for every unverified row, not a warning repeated eight times — and
/// phrased FROM THE CAPABILITY FLAGS, so the day a device is verified the sentence changes
/// with it instead of quietly lying.
@Composable
private fun Footnotes() {
    val palette = LocalGripPalette.current
    val verified = GaugeKind.selectable.filter { it.capabilities.hardwareVerified }.map { it.displayName }
    val broadcast = GaugeKind.selectable.filter { it.capabilities.isBroadcast }.map { it.displayName }
    val ported = tr("Anything marked as a ported protocol speaks a protocol taken from the open-source hangtime-grip-connect project and has never been tested against that hardware here. Check the first pull on one against a number you already trust.")

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (verified.isEmpty()) {
                ported
            } else {
                L10n.tr("%s is the gauge this app has been verified against on real hardware. %s", andList(verified), ported)
            },
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkTertiary,
        )
        if (broadcast.isNotEmpty()) {
            // Broadcast gauges are a different shape of device, not a worse one, and the
            // two consequences a climber actually meets are worth one sentence.
            Text(
                // ANDROID-ONLY WORDING: the iOS twin of this sentence names iOS. Same
                // fact, different platform, so it is its own string in android_extra.json
                // rather than a catalog key that would be wrong in one of the two apps.
                tr(
                    "%s broadcasts its weight instead of connecting, so there is nothing to pair and nothing to zero on the device — Tare subtracts what is hanging on it. Android stops delivering broadcasts while Get a Grip is in the background, so a session on one pauses when you leave the app.",
                    andList(broadcast),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkTertiary,
            )
        }
    }
}

/// **ICU's list formatter, not a hand-written " and ".** iOS spells this
/// `.formatted(.list(type: .and))`; a literal conjunction would still say "and" in French,
/// and the separators differ by language too. Same rule as every other sentence here: the
/// words come from the locale, never from the code.
private fun andList(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    else -> ListFormatter.getInstance(Locale.getDefault()).format(items)
}

// MARK: - About

@Composable
private fun AboutCard() {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    val context = LocalContext.current
    val settings = LocalSettingsStore.current
    val templates = LocalTemplateStore.current
    val tour = LocalTourController.current
    // Both are one-shot LATCHES, not toggles: the row states what it did and stands down.
    // Nothing here is undoable and nothing needs to be pressed twice.
    var guideReset by remember { mutableStateOf(false) }
    var tourReset by remember { mutableStateOf(false) }
    var diagnosticsCopied by remember { mutableStateOf(false) }
    var copyGeneration by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(copyGeneration) {
        if (copyGeneration > 0) {
            kotlinx.coroutines.delay(2_000)
            diagnosticsCopied = false
        }
    }
    val inspecting = LocalInspectionMode.current
    // Read from the INSTALLED package rather than a build constant: what this says is then
    // what the phone actually has, which is the only version worth reporting in a bug.
    val version = remember(context, inspecting) {
        if (inspecting) {
            "1.0"
        } else {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: L10n.tr("—")
        }
    }
    val sourceUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val makers = remember { andList(GaugeKind.selectable.map { it.maker }.distinct()) }

    Card {
        CapsLabel(tr("About"))
        Spacer(Modifier.size(4.dp))
        LabelledValue(tr("App"), "Get a Grip")
        LabelledValue(tr("Version"), version)
        androidx.compose.material3.TextButton(onClick = {
            sourceUriHandler.openUri("https://github.com/plumbmybumb/get-a-grip")
        }) { Text(tr("Source code")) }
        androidx.compose.material3.TextButton(onClick = {
            sourceUriHandler.openUri("https://github.com/plumbmybumb/get-a-grip/blob/main/THIRD_PARTY_NOTICES.txt")
        }) { Text(tr("Open-source licenses")) }
        androidx.compose.material3.TextButton(onClick = {
            val report = device.diagnosticEntries.diagnosticTimeline() +
                "\n\n" + device.pipelineDiagnostics.report()
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Gauge diagnostics", report))
            diagnosticsCopied = true
            copyGeneration += 1
        }) {
            Text(tr(if (diagnosticsCopied) "Copied" else "Copy the diagnostics to the clipboard"),
                Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
        }

        Spacer(Modifier.size(4.dp))
        // Descriptive use only, and unconditional: the app is not made by, affiliated with,
        // or endorsed by any of these makers, and this is the sentence that says so. **The
        // list is built from the registry**, so adding a gauge cannot leave a maker unnamed
        // in the one place they all have to appear.
        Text(
            tr(
                "Get a Grip works with force gauges from %s. It is not made by, affiliated with, or endorsed by any of them.",
                makers,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkSecondary,
        )

        HorizontalDivider(color = palette.inkTertiary.copy(alpha = 0.25f))

        // **TWO DIFFERENT THINGS**, and the old labels ("Show the setup guide again" / "Take
        // the tour again") were close enough to read as one feature listed twice. This one is
        // the step-by-step hints printed INSIDE the routine builder; the one below is the
        // spotlight walkthrough of the whole app.
        ResetRow(
            done = guideReset,
            title = tr("Show the builder's hints again"),
            doneTitle = tr("Hints reset — open a routine to see them"),
            spoken = tr("Builder hints reset"),
            icon = Icons.Outlined.Refresh,
        ) {
            settings.setBuilderGuideDone(false)
            guideReset = true
            // Back to Today, or the reset happens two tabs away from anywhere you could see
            // it and reads as a dead button.
            tour.requestedTab = 0
        }

        // The spotlight tour, not the builder's inline guide above. Both exist and teach
        // different things, which is why they are two rows rather than one. `replay` clears
        // ALL THREE `tour.seen.<act>` flags — the builder and session acts happen minutes or
        // days later, and a replay that only restarted the intro would never reach them.
        ResetRow(
            done = tourReset,
            title = tr("Take the spotlight tour again"),
            doneTitle = tr("Tour restarted — it is running on Today"),
            spoken = tr("Tour restarted"),
            icon = Icons.Outlined.AutoAwesome,
        ) {
            tour.replay(hasRoutine = templates.routines.isNotEmpty())
            tourReset = true
        }

        Text(
            tr("The hints are written into the routine builder. The tour dims the screen and walks you through Today, the builder and a session."),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = palette.inkTertiary,
        )
    }
}

/// A full-width row that does one irreversible-but-harmless thing and then says it did.
///
/// Disabled once fired, because the ONLY thing a second press could do is re-fire a reset that
/// has already happened — and a row that keeps looking live after it worked is how people
/// press it three times and wonder which one counted.
@Composable
private fun ResetRow(
    done: Boolean,
    title: String,
    doneTitle: String,
    spoken: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            // A row that draws full-width and is only tappable on its words is half dead.
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(Metrics.radiusInner))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !done,
                role = Role.Button,
                onClick = onClick,
            )
            // `scales: false`, matching every other bare row on a shared card: a row with no
            // background of its own scaling on press shrinks its content while the card's
            // backdrop stays put.
            .pressFeedback(interaction, scales = false)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (done) Icons.Filled.Check else icon,
            contentDescription = null,
            tint = if (done) palette.inkSecondary else palette.graphite,
            modifier = Modifier.size(18.dp),
        )
        Text(
            if (done) doneTitle else title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (done) palette.inkSecondary else palette.graphite,
        )
    }
}

// MARK: - House pieces

@Composable
private fun Card(
    contentPadding: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = LocalGripPalette.current.card,
        modifier = Modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(if (contentPadding == 0.dp) 0.dp else 8.dp),
            content = content,
        )
    }
}

@Composable
private fun LabelledValue(label: String, value: String) {
    val palette = LocalGripPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = palette.inkSecondary)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Medium,
            color = palette.inkPrimary,
        )
    }
}

/// A full-width row that opens something. **The whole row is the hit target**, 44 dp at
/// minimum: a row that draws full-width and is only tappable on its words is half dead.
@Composable
private fun NavRow(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGripPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = palette.card,
        modifier = modifier
            .widthIn(max = Metrics.maxContentWidth)
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                // A full-width row that opens something IS a button, and without this
                // TalkBack announces the words and never the control type.
                role = Role.Button,
                onClick = onClick,
            )
            .pressFeedback(interactionSource, scales = false),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary,
                )
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = palette.inkSecondary)
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = palette.inkTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/// Inner screens get an ordinary top bar with a back arrow. The system's predictive-back
/// gesture pops the same host, so the arrow is a second door, never the only one.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InnerScreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    val palette = LocalGripPalette.current
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    scrolledContainerColor = palette.card,
                    titleContentColor = palette.inkPrimary,
                    navigationIconContentColor = palette.inkPrimary,
                ),
            )
        },
        content = content,
    )
}
