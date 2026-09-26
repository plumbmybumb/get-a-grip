// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.settings

import run.nuri.getagrip.store.diagnosticTimeline
import run.nuri.getagrip.ui.units.WeightUnits
import run.nuri.getagrip.ui.units.WeightUnit
import run.nuri.getagrip.ui.components.Chip

import run.nuri.getagrip.ui.components.LocalFloatingTabBarInset
import android.icu.text.ListFormatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsInputAntenna
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import java.util.Locale
import run.nuri.getagrip.engine.GaugeKind
import run.nuri.getagrip.engine.GaugeProtocolSource
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.store.LocalSettingsStore
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.pressFeedback
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.readablePageWidth
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.rememberReduceMotion

private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_GAUGE_PICKER = "gauge-picker"

/// The Settings tab: what the gauge is doing, a door to choosing one, and the statements the
/// app owes whoever is using it.
///
/// TRANSLATION NOTE: iOS pushes onto the tab's own `NavigationStack`; here a NavHost scoped to
/// this tab, so predictive back pops it and the tab bar stays put.
///
/// **Every move is `dropUnlessResumed`, and back names where it goes.** A double tap, or a
/// back tap mid-push, fired twice: the picker pushed twice, or a bare `popBackStack()` from a
/// departing destination popped SETTINGS itself and blanked the tab. Only a resumed
/// destination navigates; the push is single-top; back pops TO Settings, never below.
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = ROUTE_SETTINGS, modifier = modifier.fillMaxSize()) {
        composable(ROUTE_SETTINGS) {
            SettingsRoot(
                onOpenGaugePicker = dropUnlessResumed {
                    nav.navigate(ROUTE_GAUGE_PICKER) { launchSingleTop = true }
                },
            )
        }
        composable(ROUTE_GAUGE_PICKER) {
            val back = dropUnlessResumed { nav.popBackStack(ROUTE_SETTINGS, inclusive = false) }
            InnerScreen(title = tr("Gauge"), onBack = back) { padding ->
                // A tap applies AND dismisses, as in the grip picker.
                GaugePickerScreen(Modifier.padding(padding), onSelected = back)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRoot(
    onOpenGaugePicker: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val device = LocalDeviceStore.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // `RootTabView`'s Scaffold already inset this subtree; a second inset would pad twice.
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
            // WHICH gauge, first, with a rim (Nuri, 2026-09-20): as a plain row among eight gauges it
            // read as a status line, and nobody with a WH-C06 saw it was the place to choose. The Device
            // card below says what the chosen one is doing.
            NavRow(
                icon = Icons.Outlined.SettingsInputAntenna,
                iconTint = palette.bleu,
                title = tr("Gauge"),
                subtitle = if (device.isMock) tr("Demo device") else device.gaugeKind.displayName,
                // Counted from the registry, so a ninth gauge cannot leave this claiming eight.
                note = tr("Tap to choose yours — Get a Grip works with %d different gauges.", GaugeKind.selectable.size),
                outline = palette.bleu,
                onClick = onOpenGaugePicker,
            )

            // What the chosen gauge is doing.
            Card {
                CapsLabel(tr("Device"))
                Spacer(Modifier.size(4.dp))
                LabelledValue(tr("Status"), device.state.label)
                device.deviceName?.let { LabelledValue(tr("Name"), it) }
                device.firmwareVersion?.let { LabelledValue(tr("Firmware"), it) }
                device.batteryFraction?.let {
                    LabelledValue(tr("Battery"), "${run.nuri.getagrip.ui.components.BatteryDisplay.percentage(it)}%")
                }
                // A synthetic number that looks like a measurement is worse than none, so demo mode is
                // never ambiguous. Otherwise this names the SELECTED gauge, not a hardcoded Progressor.
                LabelledValue(
                    tr("Source"),
                    if (device.isMock) tr("Demo device") else device.gaugeKind.displayName,
                )
            }

            WeightUnitSetting(LocalSettingsStore.current)
            BodyWeightSetting(LocalSettingsStore.current)

            // The live gauge is a button on Today's bar (2026-09-20): one door, not two.

            SupportCard(
                gauge = device.gaugeKind.displayName + if (device.isMock) " (${tr("Demo device")})" else "",
                diagnostics = {
                    if (device.diagnosticEntries.isEmpty()) null
                    else device.diagnosticEntries.diagnosticTimeline() +
                        "\n\n" + device.pipelineDiagnostics.report()
                },
            )
            LegalCard()
            OpenSourceCommunityCard()
            AboutCard()
        }
    }
}

// MARK: - Gauge picker

/// Which device the app measures with.
///
/// A pushed screen, not a card: eight rows with makers and caveats are 400 dp that do not
/// belong where you check a battery. Not a menu either: a menu shows names only, and this
/// list must carry which devices have actually been tested.
///
/// Nothing connects: constructing a client raises the Bluetooth prompt, so that waits for a
/// Connect tap.
@Composable
fun GaugePickerScreen(modifier: Modifier = Modifier, onSelected: () -> Unit) {
    val device = LocalDeviceStore.current
    val settings = LocalSettingsStore.current
    val palette = LocalGripPalette.current
    val haptics = LocalHapticFeedback.current

    /// The kind whose first selection waits on its maker's note (only the Frez Dyno, once; after
    /// Next it is persisted on this device).
    var kindAwaitingIntro by remember { mutableStateOf<GaugeKind?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .readablePageWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Metrics.hPadding)
            .padding(bottom = Metrics.spacing + LocalFloatingTabBarInset.current),
        verticalArrangement = Arrangement.spacedBy(Metrics.spacing),
    ) {
        // ONE single-choice list, so TalkBack says "2 of 8", which eight separate rows cannot give.
        Card(contentPadding = 0.dp) {
          Column(Modifier.selectableGroup()) {
            GaugeKind.selectable.forEachIndexed { index, kind ->
                if (index > 0) {
                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                // Demo mode is a client, not a kind, so nothing reads selected; tapping the selected gauge
                // leaves it.
                val isSelected = device.gaugeKind == kind && !device.isMock
                val interactionSource = remember { MutableInteractionSource() }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            // A radio group: TalkBack says "selected" as the state of a choice, not an adjective.
                            role = Role.RadioButton,
                        ) {
                            if (kind.capabilities.requiresRemoteCalibration && !settings.frezIntroSeen) {
                                // Nothing selected yet, so no tick: the note is the first half of this tap.
                                kindAwaitingIntro = kind
                                return@clickable
                            }
                            device.selectGaugeKind(kind)
                            // Feedback names its cause: the tick fires on the SELECTION tap.
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelected()
                        }
                        // `scales = false`: the row shares a card, and scaling it would shrink the content while
                        // the card stays put.
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

    // Next completes the selection the tap started; the note is read once, never again.
    kindAwaitingIntro?.let { kind ->
        FrezIntroSheet {
            settings.setFrezIntroSeen(true)
            kindAwaitingIntro = null
            device.selectGaugeKind(kind)
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onSelected()
        }
    }
}

/// Maker, plus the one fact that changes how much to trust the numbers — for a published
/// protocol, that the caveat is about testing, not provenance.
private fun detail(kind: GaugeKind): String {
    val parts = mutableListOf(kind.maker)
    if (!kind.capabilities.hardwareVerified) {
        parts.add(
            if (kind.capabilities.protocolSource == GaugeProtocolSource.ported) {
                L10n.tr("ported protocol")
            } else {
                L10n.tr("official protocol, untested here")
            },
        )
    }
    return parts.joinToString(" · ")
}

/// ONE shared footnote for every unverified row, phrased FROM THE CAPABILITY FLAGS so it
/// changes the day a device is verified instead of quietly lying.
@Composable
private fun Footnotes() {
    val palette = LocalGripPalette.current
    val verified = GaugeKind.selectable.filter { it.capabilities.hardwareVerified }.map { it.displayName }
    val broadcast = GaugeKind.selectable.filter { it.capabilities.isBroadcast }.map { it.displayName }
    // A published protocol is a different unknown from a port: documented bytes, untested device.
    val official = GaugeKind.selectable
        .filter {
            it.capabilities.protocolSource == GaugeProtocolSource.vendorDocumented &&
                !it.capabilities.hardwareVerified
        }
        .map { it.displayName }
    // The one gauge needing a lookup — the app's only non-platform server — said once, where
    // the choice is made.
    val calibrated = GaugeKind.selectable
        .filter { it.capabilities.requiresRemoteCalibration }
        .map { it.displayName }
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
        if (official.isNotEmpty()) {
            Text(
                tr(
                    "%s speaks a protocol its maker published, but no unit has been tried on this app yet.",
                    andList(official),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkTertiary,
            )
        }
        if (calibrated.isNotEmpty()) {
            Text(
                tr(
                    "%s sends raw sensor counts, so the first time a unit connects the app looks up its calibration once from its maker, by serial number. The answer is kept on this phone and never asked for again; no other gauge involves a server.",
                    andList(calibrated),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkTertiary,
            )
        }
        if (broadcast.isNotEmpty()) {
            // Broadcast gauges are a different shape of device, not a worse one: one sentence for its two consequences.
            Text(
                // ANDROID-ONLY WORDING: the iOS twin names iOS, so this lives in android_extra.json rather
                // than a catalog key that would be wrong in one app.
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

/// **ICU's list formatter, not a hand-written " and "** (iOS `.formatted(.list(type: .and))`):
/// conjunctions and separators differ by language, so the words come from the locale.
private fun andList(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    else -> ListFormatter.getInstance(Locale.getDefault()).format(items)
}

// MARK: - About

@Composable
private fun OpenSourceCommunityCard() {
    val palette = LocalGripPalette.current
    val reduceMotion = rememberReduceMotion()
    var expanded by remember { mutableStateOf(false) }
    val disclosureState = tr(if (expanded) "Expanded" else "Collapsed")

    Card {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Metrics.controlMinHeight)
                .clip(RoundedCornerShape(Metrics.radiusInner))
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = disclosureState },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tr("Open source & community"),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = palette.inkPrimary,
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = palette.inkTertiary,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = if (reduceMotion) fadeIn(Motion.state(true)) else
                expandVertically(Motion.state(false)) + fadeIn(Motion.state(false)),
            exit = if (reduceMotion) fadeOut(Motion.state(true)) else
                shrinkVertically(Motion.state(false)) + fadeOut(Motion.state(false)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CommunityLink(
                    title = tr("Source code"),
                    url = "https://github.com/plumbmybumb/get-a-grip",
                )
                CommunityLink(
                    title = tr("Open-source licenses"),
                    url = "https://github.com/plumbmybumb/get-a-grip/blob/main/THIRD_PARTY_NOTICES.txt",
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = palette.inkTertiary.copy(alpha = 0.15f),
                )
                CommunityLink(
                    title = "Grip Connect",
                    description = tr("Gauge protocols by Stevie-Ray Hartog (© 2024, BSD-2-Clause)."),
                    url = "https://github.com/Stevie-Ray/hangtime-grip-connect",
                )
                CommunityLink(
                    title = "Crimpdeq",
                    description = tr("Open-source force sensor. Thanks to its creator for testing Get a Grip."),
                    url = "https://crimpdeq.com/",
                )
            }
        }
    }
}

@Composable
private fun CommunityLink(title: String, url: String, description: String? = null) {
    val palette = LocalGripPalette.current
    val uriHandler = LocalUriHandler.current
    val actionLabel = tr("Open in a browser")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Metrics.controlMinHeight)
            .clip(RoundedCornerShape(Metrics.radiusInner))
            .clickable(role = Role.Button, onClickLabel = actionLabel) { uriHandler.openUri(url) }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = palette.graphite,
            )
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
            }
        }
        Icon(
            Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = palette.inkTertiary,
        )
    }
}

@Composable
private fun AboutCard() {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    val context = LocalContext.current
    val settings = LocalSettingsStore.current
    // One-shot LATCHES, not toggles: the row states what it did and stands down.
    var guideReset by remember { mutableStateOf(false) }
    var diagnosticsCopied by remember { mutableStateOf(false) }
    var copyGeneration by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(copyGeneration) {
        if (copyGeneration > 0) {
            kotlinx.coroutines.delay(2_000)
            diagnosticsCopied = false
        }
    }
    val inspecting = LocalInspectionMode.current
    // From the INSTALLED package, not a build constant: what the phone actually has is the only
    // version worth reporting in a bug.
    val version = remember(context, inspecting) {
        if (inspecting) {
            "1.0"
        } else {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: L10n.tr("—")
        }
    }
    val makers = remember { andList(GaugeKind.selectable.map { it.maker }.distinct()) }

    Card {
        CapsLabel(tr("About"))
        Spacer(Modifier.size(4.dp))
        LabelledValue(tr("App"), "Get a Grip")
        LabelledValue(tr("Version"), version)
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
        // Descriptive use only: the app is not made by, affiliated with, or endorsed by any of these
        // makers. **Built from the registry**, so a new gauge cannot leave a maker unnamed here.
        Text(
            tr(
                "Get a Grip works with force gauges from %s. It is not made by, affiliated with, or endorsed by any of them.",
                makers,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkSecondary,
        )

        HorizontalDivider(color = palette.inkTertiary.copy(alpha = 0.25f))

        ResetRow(
            done = guideReset,
            title = tr("Show the builder's hints again"),
            doneTitle = tr("Hints reset — open a routine to see them"),
            spoken = tr("Builder hints reset"),
            icon = Icons.Outlined.Refresh,
        ) {
            settings.setBuilderGuideDone(false)
            guideReset = true
        }
    }
}

/// A full-width row that does one irreversible-but-harmless thing and then says it did.
/// Disabled once fired: a row that still looks live after working gets pressed three times.
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
            // Full-width but tappable only on its words is half dead.
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(Metrics.radiusInner))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !done,
                role = Role.Button,
                onClick = onClick,
            )
            // `scales: false`, like every bare row on a shared card (see the gauge picker).
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

/// A full-width row that opens something. **The whole row is the hit target**, 44 dp minimum.
///
/// `note` is a third line in tertiary ink; `outline` a two-point rim (a hairline measured
/// under 3:1 on iOS) for the one row that is a choice rather than a status.
@Composable
private fun NavRow(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    outline: androidx.compose.ui.graphics.Color? = null,
) {
    val palette = LocalGripPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Metrics.radiusCard)
    Surface(
        shape = shape,
        color = palette.card,
        modifier = modifier
            .widthIn(max = Metrics.maxContentWidth)
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(if (outline != null) Modifier.border(2.dp, outline, shape) else Modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                // Without the role TalkBack announces the words but never the control type.
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
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkTertiary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
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


@Composable
internal fun WeightUnitSetting(settings: run.nuri.getagrip.store.SettingsStore) {
    val palette = LocalGripPalette.current
    Card {
        Text(tr("Weight units"), style = MaterialTheme.typography.titleMedium, color = palette.inkPrimary)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WeightUnit.entries.forEach { option ->
                Chip(
                    title = if (option == WeightUnit.kg) tr("Kilograms") + " · kg" else tr("Pounds") + " · lb",
                    isSelected = settings.weightUnit == option,
                    modifier = Modifier.weight(1f),
                ) { settings.setWeightUnit(option) }
            }
        }
    }
}

/// Asked once by the first critical force test; this is the only place it changes afterwards.
/// Each test froze its own copy, so a change here never rewrites a result.
@Composable
internal fun BodyWeightSetting(settings: run.nuri.getagrip.store.SettingsStore) {
    val palette = LocalGripPalette.current
    Card {
        // Typed, never a slider — see `BodyWeightField`. Titled like every card title here.
        run.nuri.getagrip.ui.components.BodyWeightField(
            kilograms = settings.bodyWeightKg,
            onChange = { settings.setBodyWeightKg(it) },
            titleStyle = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("settings.bodyWeight"),
        )
        Text(
            tr("Used to show critical force as a share of body weight. Each test keeps the weight it was taken at, so changing this never alters an old result."),
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkSecondary,
        )
    }
}
