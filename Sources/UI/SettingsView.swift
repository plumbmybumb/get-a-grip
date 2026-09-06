// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import SwiftUI
import UIKit

/// ONE rendering of the breadcrumb ring, for every way it can leave the phone.
///
/// The clipboard button and the bug report's attachment carry the SAME text because they
/// call the same function: two renderings of one report is two things to keep in step,
/// and the one that goes stale is always the one nobody is looking at.
///
/// OLDEST FIRST, unlike the list on screen. The list is newest-first because the thing you
/// just saw happen is the thing you came to look at; a pasted or attached report is read
/// as a narrative — Siri arrived, then the scene changed, then the trace flushed 40 times
/// — and a narrative runs forwards. Seconds included: the whole question is whether two
/// events happened together or seconds apart.
enum DiagnosticReport {
    static func text(from entries: [DiagnosticBreadcrumbEntry]) -> String {
        let stamp = Date.FormatStyle(date: .numeric, time: .standard)
        return entries
            .map { "\($0.date.formatted(stamp))  \($0.text)" }
            .joined(separator: "\n")
    }
}

/// Three cards: what the gauge is doing, a door to the live gauge, and the statements
/// the app owes whoever is using it.
///
/// The live gauge lives HERE rather than on Today. Today is the ritual — one routine,
/// one tap — and a second card offering a different, more interesting screen is the
/// first millimetre of the library the whole app exists to avoid. The gauge is still
/// one tap from the runner, which is where a raw reading is actually wanted.
struct SettingsView: View {
    @Environment(DeviceStore.self) private var device
    @Environment(TemplateStore.self) private var templates
    @Environment(TourController.self) private var tour
    /// Routines exist at all — which decides whether replaying the tour opens with the
    /// build-one-first act or goes straight to pointing at the card.
    @Query private var routines: [SessionTemplate]
    @Environment(SettingsStore.self) private var settings

    /// Confirmation for the tap that just happened, not a fact about the app —
    /// deliberately not persisted, so reopening Settings offers the reset again.
    @State private var guideReset = false
    @State private var tourReset = false
    @State private var copiedTick = 0
    /// Shows "Copied" for a couple of seconds after each tap, then reverts — the ring is
    /// meant to be re-copied as breadcrumbs accrue, and a label stuck on "Copied" from
    /// the first tap gave the second and third tap no visual acknowledgement at all.
    @State private var justCopied = false
    @State private var copyResetTask: Task<Void, Never>?

    var body: some View {
        ScreenScaffold(title: String(localized: "Settings")) {
            deviceCard.staggerIn(0)
            // WHICH gauge before the live gauge itself: choosing the device precedes using
            // it, and everything below this row describes whatever it selects.
            gaugeKindRow.staggerIn(1)
            gaugeRow.staggerIn(2)
            maxesRow.staggerIn(3)
            // ABOVE About, deliberately. About is the block of statements the app OWES
            // whoever is using it — storage, attribution, licence — and a door out to a
            // person is a thing you DO, so it belongs with the other actions rather than
            // filed under the small print.
            SupportCard().staggerIn(4)
            openSourceCard.staggerIn(5)
            aboutCard.staggerIn(6)
        }
    }

    // MARK: - Device

    private var deviceCard: some View {
        MaterialCard {
            VStack(alignment: .leading, spacing: 12) {
                CapsLabel(String(localized: "Device"))
                LabeledContent("Status") { Text(device.state.label) }
                if let name = device.deviceName {
                    LabeledContent("Name") { Text(name) }
                }
                if let version = device.firmwareVersion {
                    LabeledContent("Firmware") { Text(version) }
                }
                if let battery = device.batteryFraction {
                    LabeledContent("Battery") {
                        Text(battery, format: .percent.precision(.fractionLength(0)))
                            .monospacedDigit()
                            .contentTransition(.numericText())
                    }
                }
                // A synthetic number that looks like a measurement is worse than no
                // number, so demo mode is never allowed to be ambiguous. Otherwise this
                // states the SELECTED gauge — the app drives eight of them now, and
                // "Tindeq Progressor" was a hardcoded claim about one.
                LabeledContent("Source") {
                    Text(device.isMock ? String(localized: "Demo device") : device.gaugeKind.displayName)
                }
            }
            .font(.system(.subheadline))
        }
    }

    // MARK: - Which gauge

    private var gaugeKindRow: some View {
        NavigationLink {
            GaugePickerView()
        } label: {
            MaterialCard {
                HStack(spacing: 14) {
                    Image(systemName: "dot.radiowaves.left.and.right")
                        .font(.system(.title2))
                        .foregroundStyle(Accent.graphite)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Gauge")
                            .font(.system(.headline, weight: .semibold))
                            .foregroundStyle(Ink.primary)
                        Text(device.isMock ? String(localized: "Demo device") : device.gaugeKind.displayName)
                            .font(.system(.subheadline))
                            .foregroundStyle(Ink.secondary)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right")
                        .font(.system(.footnote, weight: .semibold))
                        .foregroundStyle(Ink.tertiary)
                        .accessibilityHidden(true)
                }
                .fixedSize(horizontal: false, vertical: true)
            }
        }
        // Full-width row, full-width hit area — the card's material and the Spacer
        // contribute nothing to SwiftUI's default opaque-content hit test.
        .contentShape(RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel("Gauge. Currently \(device.isMock ? "the demo device" : device.gaugeKind.displayName).")
    }

    // MARK: - Live gauge

    private var gaugeRow: some View {
        NavigationLink {
            GaugeView()
        } label: {
            MaterialCard {
                HStack(spacing: 14) {
                    Image(systemName: "gauge.with.dots.needle.bottom.50percent")
                        .font(.system(.title2))
                        .foregroundStyle(StatusTint.engaged)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Live gauge")
                            .font(.system(.headline, weight: .semibold))
                            .foregroundStyle(Ink.primary)
                        Text("Pull and watch the force in real time")
                            .font(.system(.subheadline))
                            .foregroundStyle(Ink.secondary)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right")
                        .font(.system(.footnote, weight: .semibold))
                        .foregroundStyle(Ink.tertiary)
                        .accessibilityHidden(true)
                }
                .fixedSize(horizontal: false, vertical: true)
            }
        }
        // A row that draws full-width must hit full-width: SwiftUI's default hit area
        // is the label's OPAQUE content, so the Spacer and the card's material
        // contribute nothing without this.
        .contentShape(RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel("Live gauge. Pull and watch the force in real time.")
    }

    // MARK: - Maxes

    /// Without a max on record, every "about 25 % of your max" caption in the set editor
    /// has nothing to say — so this row is what turns target loads from a number you
    /// guess into a percentage of something real.
    private var maxesRow: some View {
        NavigationLink {
            MaxesView()
        } label: {
            MaterialCard {
                HStack(spacing: 14) {
                    Image(systemName: "scalemass")
                        .font(.system(.title2))
                        .foregroundStyle(Accent.graphite)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Maxes")
                            .font(.system(.headline, weight: .semibold))
                            .foregroundStyle(Ink.primary)
                        Text("What you can pull on each grip")
                            .font(.system(.subheadline))
                            .foregroundStyle(Ink.secondary)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right")
                        .font(.system(.footnote, weight: .semibold))
                        .foregroundStyle(Ink.tertiary)
                        .accessibilityHidden(true)
                }
                .fixedSize(horizontal: false, vertical: true)
            }
        }
        .contentShape(RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel("Maxes. What you can pull on each grip.")
        .tourAnchor(.settingsMaxes)
    }

    private var hasRoutine: Bool { !routines.isEmpty }

    /// Every maker Doigt speaks to, in picker order, de-duplicated — one gauge per maker
    /// today, but the fold is what keeps the sentence right the day two devices share one.
    private var makersSentence: String {
        var makers: [String] = []
        for kind in GaugeKind.selectable where !makers.contains(kind.maker) {
            makers.append(kind.maker)
        }
        return makers.formatted(.list(type: .and))
    }

    private var diagnosticReport: String {
        DiagnosticReport.text(from: device.diagnosticEntries) + "\n\n" + device.pipelineDiagnostics.report
    }

    private var openSourceCard: some View {
        MaterialCard {
            SettingsDisclosure("Open source") {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Get a Grip is open source under the Mozilla Public License 2.0.")
                        .font(.footnote).foregroundStyle(Ink.secondary)
                    sourceLink("Source code", symbol: "chevron.left.forwardslash.chevron.right",
                               path: "")
                    sourceLink("MPL 2.0", symbol: "doc.text", path: "/blob/main/LICENSE")
                    sourceLink("Open-source licenses", symbol: "doc.on.doc",
                               path: "/blob/main/THIRD_PARTY_NOTICES.txt")
                }
            }
        }
    }

    private func sourceLink(_ title: LocalizedStringKey, symbol: String, path: String) -> some View {
        Link(destination: URL(string: "https://github.com/plumbmybumb/get-a-grip" + path)!) {
            HStack(spacing: 10) {
                Label(title, systemImage: symbol)
                Spacer(minLength: 8)
                Image(systemName: "arrow.up.right").font(.caption).foregroundStyle(Ink.tertiary)
            }
            .font(.subheadline.weight(.medium))
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .tint(Accent.graphite)
    }

    // MARK: - About

    private var aboutCard: some View {
        MaterialCard {
            VStack(alignment: .leading, spacing: 10) {
                CapsLabel(String(localized: "About"))

                storageLine

                PrivacyPolicyLink()

                SettingsDisclosure("Device compatibility") {
                    VStack(alignment: .leading, spacing: 10) {

                        // Descriptive use only, and unconditional: the app is not made by,
                        // affiliated with, or endorsed by any of these makers, and this is the
                        // sentence that says so. **The list is built from the registry**, so
                        // adding a gauge cannot leave a maker unnamed in the one place they all
                        // have to appear — the failure mode of a typed-out list is a legal line
                        // that silently goes stale on the next device.
                        Text("Get a Grip works with force gauges from \(makersSentence). It is not made by, affiliated with, or endorsed by any of them.")
                            .font(.system(.footnote))
                            .foregroundStyle(Ink.secondary)

                        // The Tindeq protocol is published by Tindeq itself; everything else here
                        // is somebody's reverse-engineering work, given away under a licence that
                        // asks for exactly this notice.
                        Text("Protocol support for every gauge other than the Tindeq Progressor is ported from hangtime-grip-connect (BSD-2-Clause, © 2024 Stevie-Ray Hartog).")
                            .font(.system(.footnote))
                            .foregroundStyle(Ink.secondary)

                    }
                }

                if !device.diagnosticEntries.isEmpty {
                    SettingsDisclosure("Diagnostics") {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack(alignment: .firstTextBaseline) {
                                // WITHOUT THIS THE FEATURE DOES NOT WORK. The whole point of the
                                // ring is to travel from Nuri's phone to whoever is diagnosing the
                                // drop; a 240 pt scroll view you can only read means retyping
                                // timestamps by hand, which nobody does. Read-only evidence that
                                // cannot leave the device is not evidence.
                                Button {
                                    UIPasteboard.general.string = diagnosticReport
                                    copiedTick += 1
                                    justCopied = true
                                    copyResetTask?.cancel()
                                    copyResetTask = Task {
                                        try? await Task.sleep(for: .seconds(2))
                                        guard !Task.isCancelled else { return }
                                        justCopied = false
                                    }
                                } label: {
                                    Text(justCopied ? "Copied" : "Copy")
                                        .font(.system(.footnote, weight: .semibold))
                                        .foregroundStyle(Accent.graphite)
                                        .padding(.horizontal, 12)
                                        .frame(minHeight: 44)
                                        .accessibleGlass(nil, in: .capsule)
                                        .contentShape(.capsule)
                                }
                                .buttonStyle(PressFeedbackButtonStyle())
                                .accessibilityLabel("Copy the diagnostics to the clipboard")
                                // The identical tick-as-confirmation shape is wired to this
                                // everywhere else it appears (UndoBar, MaxesView's save) — this
                                // was the dropped wire-up, and the label alone (which used to
                                // flip once and stay "Copied" forever) gave repeated copies no
                                // acknowledgement at all.
                                .sensoryFeedback(.success, trigger: copiedTick)
                            }
                            Text("Recent connection and signal breadcrumbs, kept on this device in memory only — they are lost if the app is force-quit.")
                                .font(.system(.caption))
                                .foregroundStyle(Ink.tertiary)
                                .fixedSize(horizontal: false, vertical: true)
                            ScrollView(.vertical) {
                                LazyVStack(alignment: .leading, spacing: 8) {
                                    ForEach(Array(device.diagnosticEntries.reversed())) { entry in
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(entry.date.formatted(date: .abbreviated, time: .standard))
                                                .font(.system(.caption2))
                                                .monospacedDigit()
                                                .foregroundStyle(Ink.tertiary)
                                            Text(entry.text)
                                                .font(.system(.footnote))
                                                .foregroundStyle(Ink.secondary)
                                        }
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    }
                                }
                            }
                            .frame(maxHeight: 240)
                        }
                    }
                }

                SettingsDisclosure("Guides and tours") {
                    VStack(alignment: .leading, spacing: 10) {
                        // TWO DIFFERENT THINGS, and the old labels ("Show the setup guide again"
                        // / "Take the tour again") were close enough to read as one feature listed
                        // twice. This one is the step-by-step hints printed INSIDE the routine
                        // builder; the one below is the spotlight walkthrough of the whole app.
                        Button {
                            settings.builderGuideDone = false
                            guideReset = true
                            // Back to Today, or the reset happens two tabs away from anywhere you
                            // could see it and reads as a dead button.
                            tour.requestedTab = 0
                        } label: {
                            Label(guideReset ? "Hints reset — open a routine to see them"
                                             : "Show the builder's hints again",
                                  systemImage: guideReset ? "checkmark" : "arrow.counterclockwise")
                                .font(.system(.footnote, weight: .semibold))
                                .foregroundStyle(guideReset ? Ink.secondary : Accent.graphite)
                                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                                // Holds a Label and draws full-width — without a content shape
                                // only the glyph and glyph-width of text would be tappable.
                                .contentShape(Rectangle())
                        }
                        // `scales: false`, matching every other bare row on a shared
                        // `MaterialCard` (`FineTuningSection`, `SetRowView`, `MaxesView`): a
                        // row with no background of its own scaling on press shrinks its
                        // content while the card's backdrop stays put.
                        .buttonStyle(PressFeedbackButtonStyle(scales: false))
                        .disabled(guideReset)
                        .accessibilityLabel(guideReset ? "Builder hints reset"
                                                       : "Show the builder's hints again")

                        // The spotlight tour, not the builder's inline guide above. Both exist and
                        // teach different things, which is why they are two rows rather than one.
                        Button {
                            tour.replay(hasRoutine: hasRoutine)
                            tourReset = true
                        } label: {
                            Label(tourReset ? "Tour restarted — it is running on Today"
                                            : "Take the spotlight tour again",
                                  systemImage: tourReset ? "checkmark" : "sparkles")
                                .font(.system(.footnote, weight: .semibold))
                                .foregroundStyle(tourReset ? Ink.secondary : Accent.graphite)
                                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                                .contentShape(Rectangle())
                        }
                        // Same reasoning as `guideReset` above.
                        .buttonStyle(PressFeedbackButtonStyle(scales: false))
                        .disabled(tourReset)
                        .accessibilityLabel(tourReset ? "Tour restarted" : "Take the spotlight tour again")

                        Text("The hints are written into the routine builder. The tour dims the screen and walks you through Today, the builder and a session.")
                            .font(.system(.caption, weight: .medium))
                            .foregroundStyle(Ink.tertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// Where the routines actually ended up. Someone who believes their training is in
    /// iCloud when it is device-only finds out at the moment they lose the phone, so
    /// this states the real mode rather than asserting the happy one.
    ///
    /// `.isolated` is not a footnote-shaped fact: that fallback container is a
    /// DIFFERENT store file, so routines saved earlier are genuinely absent from it.
    /// It gets amber and an icon. The other two modes are statements, not warnings.
    @ViewBuilder
    private var storageLine: some View {
        switch templates.storageMode {
        case .cloud, .localOnly:
            Text(templates.storageMode.aboutLine)
                .font(.system(.footnote))
                .foregroundStyle(Ink.secondary)
        case .isolated:
            Label(templates.storageMode.aboutLine, systemImage: "exclamationmark.triangle")
                .font(.system(.footnote, weight: .medium))
                .foregroundStyle(StatusTint.armed)
        }
    }
}

/// Native disclosure semantics, a full-width tap target, and the shared motion curve.
/// Longer explanations stay available without filling the initial Settings screen.
private struct SettingsDisclosure<Content: View>: View {
    let title: LocalizedStringKey
    @ViewBuilder var content: () -> Content
    @State private var expanded = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(_ title: LocalizedStringKey, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.content = content
    }

    var body: some View {
        DisclosureGroup(isExpanded: $expanded.animation(Motion.state(reduceMotion))) {
            content().padding(.top, 6)
        } label: {
            Text(title)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Accent.graphite)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
        }
        .tint(Accent.graphite)
    }
}

/// Its own leaf, following the pattern `SupportCard` set for presentation-scoped
/// environment values on this screen.
private struct PrivacyPolicyLink: View {
    @Environment(\.openURL) private var openURL

    var body: some View {
        Button {
            openURL(URL(string: "https://nuri.run/getagrip/privacy")!)
        } label: {
            Label("Privacy policy", systemImage: "hand.raised")
                .font(.system(.footnote, weight: .semibold))
                .foregroundStyle(Accent.graphite)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressFeedbackButtonStyle(scales: false))
        .accessibilityLabel("Privacy policy. Opens in your browser.")
    }
}

// MARK: - Support

/// A frozen mail request, the same shape as `RoutineShareRequest`: what the composer
/// carries is decided at the TAP. A breadcrumb landing while the sheet is open, or a
/// gauge waking up in a bag, must not rewrite a message somebody is already typing.
private struct MailRequest: Identifiable {
    let id = UUID()
    var subject: String
    var body: String
    var attachment: MailComposer.Attachment?
}

/// A door out to a person: a feature request or a bug report, as an email.
///
/// Its own leaf view for two reasons. `@Environment(\.openURL)` is a presentation-scoped
/// value whose identity moves with the presentation — the trap `OpenSettingsButton`
/// already carries a warning about — so it is held by the smallest view that needs it.
/// And the store is read ONLY inside the tap closures, never in `body`, so Observation
/// registers nothing and a breadcrumb arriving mid-session cannot invalidate Settings.
///
/// Nothing here reaches into `DeviceStore` beyond READING what it already publishes: the
/// store does not know that mail exists, and it must not learn.
private struct SupportCard: View {
    @Environment(DeviceStore.self) private var device
    @Environment(\.openURL) private var openURL
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var typeSize

    /// Non-nil presents the composer; the composer's own `onFinish` clears it, which is
    /// what dismisses the sheet.
    @State private var mail: MailRequest?
    @State private var askingDiagnostics = false
    /// Revealed only when the mail route genuinely failed — see `revealAddress()`.
    @State private var showsAddress = false
    @State private var copiedTick = 0
    /// Same two-second revert as the diagnostics Copy button: an address worth copying
    /// once is worth copying twice, and a label stuck on "Copied" acknowledges neither.
    @State private var justCopied = false
    @State private var copyResetTask: Task<Void, Never>?

    private static let address = "support@nuri.run"
    private static let featureSubject = String(localized: "Get a Grip — Feature request")
    private static let bugSubject = String(localized: "Get a Grip — Bug report")

    var body: some View {
        MaterialCard {
            VStack(alignment: .leading, spacing: 10) {
                CapsLabel(String(localized: "Support"))

                row(title: String(localized: "Request a feature"), systemImage: "lightbulb") {
                    compose(subject: Self.featureSubject, withDiagnostics: false)
                }
                row(title: String(localized: "Report a bug"), systemImage: "ladybug") {
                    startBugReport()
                }

                SettingsDisclosure("Email details") {
                    Text("Both open an email. Your app version, iOS version, device and selected gauge are added at the end of the message, so a report arrives with the context to act on it.")
                        .font(.system(.caption, weight: .medium))
                        .foregroundStyle(Ink.tertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                if showsAddress {
                    addressRow
                }
            }
            .fixedSize(horizontal: false, vertical: true)
        }
        // Asked BEFORE the composer, because the answer decides what the composer is
        // built with — and asked only for a bug report: a feature request has nothing a
        // breadcrumb could explain.
        .confirmationDialog("Include gauge diagnostics?",
                            isPresented: $askingDiagnostics,
                            titleVisibility: .visible) {
            Button("Include gauge diagnostics") {
                compose(subject: Self.bugSubject, withDiagnostics: true)
            }
            Button("Send without") {
                compose(subject: Self.bugSubject, withDiagnostics: false)
            }
        } message: {
            Text("A text file of the recent connection and signal breadcrumbs — what the gauge and the app did in the last few minutes.")
        }
        .sheet(item: $mail) { request in
            MailComposer(recipients: [Self.address],
                         subject: request.subject,
                         body: request.body,
                         attachment: request.attachment) {
                mail = nil
            }
        }
    }

    // MARK: Rows

    /// The bare-row shape every other row on a shared `MaterialCard` uses: 44 pt tall, an
    /// explicit content shape because it draws full-width, and `scales: false` so pressing
    /// it does not shrink its content off the card's own backdrop.
    private func row(title: String, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.system(.footnote, weight: .semibold))
                .foregroundStyle(Accent.graphite)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressFeedbackButtonStyle(scales: false))
        .accessibilityLabel("\(title). Opens an email to \(Self.address).")
    }

    /// THE LAST RESORT, and the reason no control here can look tappable and do nothing:
    /// with no mail account and no app willing to take a `mailto:`, the two buttons above
    /// would otherwise be dead. The address itself is the fallback, copyable in one tap.
    ///
    /// SIDE BY SIDE until `.accessibility1`, stacked after it. Squeezed against the
    /// button at accessibility sizes the address broke mid-token — "support@nuri.r / un"
    /// — and this row exists to be READ and retyped, so it is the one string in the card
    /// that cannot be allowed to wrap arbitrarily. Same swap `RoutineImportSheet` makes:
    /// big text is served by full width, and the horizontal pairing is what it spares.
    @ViewBuilder
    private var addressRow: some View {
        let address = Text("Email \(Self.address)")
            .font(.system(.footnote))
            .foregroundStyle(Ink.secondary)
        if typeSize >= .accessibility1 {
            VStack(alignment: .leading, spacing: 4) {
                address.fixedSize(horizontal: false, vertical: true)
                copyAddressButton
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            HStack(alignment: .firstTextBaseline) {
                address.fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                copyAddressButton
            }
        }
    }

    private var copyAddressButton: some View {
        Button {
            UIPasteboard.general.string = Self.address
            copiedTick += 1
            justCopied = true
            copyResetTask?.cancel()
            copyResetTask = Task {
                try? await Task.sleep(for: .seconds(2))
                guard !Task.isCancelled else { return }
                justCopied = false
            }
        } label: {
            Text(justCopied ? "Copied" : "Copy")
                .font(.system(.footnote, weight: .semibold))
                .foregroundStyle(Accent.graphite)
                .padding(.horizontal, 12)
                .frame(minHeight: 44)
                .accessibleGlass(nil, in: .capsule)
                .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel("Copy the support address to the clipboard")
        // The identical tick-as-confirmation wiring the diagnostics Copy button uses,
        // and for the same reason: the label alone acknowledges only the first tap.
        .sensoryFeedback(.success, trigger: copiedTick)
    }

    // MARK: Routing

    private func startBugReport() {
        // The dialog exists to ask about an ATTACHMENT. With no breadcrumbs to attach —
        // or no mail account, where the `mailto:` fallback cannot carry a file at all —
        // there is nothing to ask, so the question is skipped rather than posed and then
        // silently ignored.
        guard MailComposer.canSend, !device.diagnosticEntries.isEmpty else {
            compose(subject: Self.bugSubject, withDiagnostics: false)
            return
        }
        askingDiagnostics = true
    }

    private func compose(subject: String, withDiagnostics: Bool) {
        let body = messageBody()
        guard MailComposer.canSend else {
            openFallback(subject: subject, body: body)
            return
        }
        var attachment: MailComposer.Attachment?
        if withDiagnostics,
           let data = (DiagnosticReport.text(from: device.diagnosticEntries) + "\n\n" + device.pipelineDiagnostics.report).data(using: .utf8) {
            attachment = MailComposer.Attachment(data: data,
                                                 mimeType: "text/plain",
                                                 fileName: "get-a-grip-diagnostics.txt")
        }
        mail = MailRequest(subject: subject, body: body, attachment: attachment)
    }

    /// No composer, so hand the message to whatever mail app the phone does have. An
    /// attachment cannot survive this route, which is why the diagnostics question is
    /// never asked when it is the one available.
    private func openFallback(subject: String, body: String) {
        guard let url = Self.mailtoURL(subject: subject, body: body) else {
            revealAddress()
            return
        }
        openURL(url) { accepted in
            if !accepted { revealAddress() }
        }
    }

    private func revealAddress() {
        withAnimation(Motion.state(reduceMotion)) { showsAddress = true }
    }

    // MARK: The message

    /// A blank line to write in, then the facts a report needs, fenced off behind a rule
    /// so nobody has to read around them to answer.
    private func messageBody() -> String {
        "\n\n" + String(repeating: "-", count: 24) + "\n" + footerLines().joined(separator: "\n")
    }

    private func footerLines() -> [String] {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "unknown"
        let build = info?["CFBundleVersion"] as? String ?? "unknown"
        // Demo mode is a CLIENT, not a kind, so it is stated alongside the selected gauge
        // rather than instead of it — a bug report from a demo session that read as a real
        // Progressor would send somebody hunting firmware for numbers a timer invented.
        let gauge = device.isMock
            ? "\(device.gaugeKind.displayName) (demo device active)"
            : device.gaugeKind.displayName
        return [
            "Get a Grip \(version) (\(build))",
            "iOS \(UIDevice.current.systemVersion) · \(Self.deviceModel)",
            "Gauge: \(gauge)",
            "Locale: \(Locale.current.identifier)",
        ]
    }

    /// The model IDENTIFIER ("iPhone17,1"), not the marketing name — it is what a hardware
    /// quirk is looked up by. In the Simulator `uname` reports the HOST's architecture, so
    /// the simulated model is read from the environment first; otherwise a report filed
    /// from a simulator build claims to come from an "arm64".
    private static var deviceModel: String {
        if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] {
            return simulated
        }
        var system = utsname()
        uname(&system)
        // Copied OUT of the buffer inside the closure: the slice is a view onto memory
        // that does not outlive it, and `machine` is a fixed-size C tuple whose bytes end
        // at the first NUL rather than filling it.
        let bytes = withUnsafeBytes(of: system.machine) { Array($0.prefix { $0 != 0 }) }
        return String(decoding: bytes, as: UTF8.self)
    }

    private static func mailtoURL(subject: String, body: String) -> URL? {
        var components = URLComponents()
        components.scheme = "mailto"
        components.path = address
        components.queryItems = [
            URLQueryItem(name: "subject", value: subject),
            URLQueryItem(name: "body", value: body),
        ]
        return components.url
    }
}

// MARK: - Gauge picker

/// Which device Doigt measures with.
///
/// A pushed screen rather than a card on Settings: eight rows, each owing a maker and —
/// for seven of them — the same honest caveat, is 400 pt that has no business on a screen
/// you open to check a battery level. It is also NOT a menu: a menu can show the names and
/// nothing else, and the one thing this list has to carry is which of these devices has
/// actually been tested.
///
/// Selecting is one tap and pops back, the same "a tap applies and dismisses" rule the
/// grip picker follows. Nothing connects: `selectGaugeKind` deliberately leaves that to a
/// Connect tap, because constructing a client is what raises the Bluetooth prompt.
private struct GaugePickerView: View {
    @Environment(DeviceStore.self) private var device
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.spacing) {
                MaterialCard {
                    VStack(spacing: 0) {
                        ForEach(Array(GaugeKind.selectable.enumerated()), id: \.element) { index, kind in
                            if index > 0 {
                                Divider().padding(.vertical, 2)
                            }
                            row(for: kind)
                        }
                    }
                }
                footnotes
            }
            .padding(.horizontal, Metrics.hPadding)
            .padding(.bottom, Metrics.spacing)
            .frame(maxWidth: Metrics.maxContentWidth)
            .frame(maxWidth: .infinity)
        }
        .background { AppBackground() }
        .scrollEdgeEffectStyle(.soft, for: .bottom)
        .navigationTitle("Gauge")
        .navigationBarTitleDisplayMode(.inline)
        .sensoryFeedback(.selection, trigger: device.gaugeKind)
    }

    private func row(for kind: GaugeKind) -> some View {
        // Demo mode is a client, not a kind, so nothing reads as selected while it runs —
        // and tapping the gauge you already had selected is how you leave it.
        let isSelected = device.gaugeKind == kind && !device.isMock
        return Button {
            device.selectGaugeKind(kind)
            dismiss()
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(kind.displayName)
                        .font(.system(.body, weight: isSelected ? .semibold : .regular))
                        .foregroundStyle(Ink.primary)
                    Text(detail(for: kind))
                        .font(.system(.footnote))
                        .foregroundStyle(Ink.secondary)
                }
                Spacer(minLength: 8)
                // Graphite, the INTERACTIVE ink — bleu is reserved for live force.
                Image(systemName: "checkmark")
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(Accent.graphite)
                    .opacity(isSelected ? 1 : 0)
                    .accessibilityHidden(true)
            }
            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            // Holds a Spacer and draws full-width: without this only the words are
            // tappable and half of every row is dead.
            .contentShape(.rect)
        }
        // `scales: false`: this row shares ONE `MaterialCard` with every other gauge in
        // the list, and scaling it on press would shrink the row's content while the
        // card behind all eight rows stays put — the same shape `FineTuningSection`,
        // `SetRowView` and `MaxesView` already guard for.
        .buttonStyle(PressFeedbackButtonStyle(scales: false))
        .accessibilityLabel("\(kind.displayName). \(detail(for: kind)).")
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
    }

    /// Maker, plus the one fact that changes how much to trust the numbers.
    private func detail(for kind: GaugeKind) -> String {
        var parts = [kind.maker]
        if !kind.capabilities.hardwareVerified { parts.append(String(localized: "ported protocol")) }
        return parts.joined(separator: " · ")
    }

    /// ONE shared footnote for every unverified row, not a warning repeated eight times —
    /// and phrased from the capability flags, so the day a device is verified the sentence
    /// changes with it instead of quietly lying.
    @ViewBuilder
    private var footnotes: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(verificationNote)
            if let broadcastNote {
                Text(broadcastNote)
            }
        }
        .font(.system(.footnote))
        .foregroundStyle(Ink.tertiary)
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var verificationNote: String {
        let verified = GaugeKind.selectable
            .filter { $0.capabilities.hardwareVerified }
            .map(\.displayName)
        let ported = String(localized: "Anything marked as a ported protocol speaks a protocol taken from the open-source hangtime-grip-connect project and has never been tested against that hardware here. Check the first pull on one against a number you already trust.")
        guard !verified.isEmpty else { return ported }
        return String(localized: "\(verified.formatted(.list(type: .and))) is the gauge this app has been verified against on real hardware. \(ported)")
    }

    /// Broadcast gauges are a different shape of device, not a worse one, and the two
    /// consequences a climber actually meets are worth one sentence.
    private var broadcastNote: String? {
        let names = GaugeKind.selectable
            .filter { $0.capabilities.isBroadcast }
            .map(\.displayName)
        guard !names.isEmpty else { return nil }
        return String(localized: "\(names.formatted(.list(type: .and))) broadcasts its weight instead of connecting, so there is nothing to pair and nothing to zero on the device — Tare subtracts what is hanging on it. iOS stops delivering broadcasts while Get a Grip is in the background, so a session on one pauses when you leave the app.")
    }
}
