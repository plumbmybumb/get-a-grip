// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import SwiftUI
import UIKit

/// ONE rendering of the breadcrumb ring, for every way it can leave the phone.
///
/// The clipboard button and the bug report's attachment call the same function, so they
/// cannot drift; the stale copy is always the one nobody looks at.
///
/// OLDEST FIRST, unlike the list on screen (newest-first, because the thing you just saw
/// is what you came for): a pasted report is read as a narrative, and a narrative runs
/// forwards. Seconds included — the question is whether two events happened together.
enum DiagnosticReport {
    static func text(from entries: [DiagnosticBreadcrumbEntry]) -> String {
        let stamp = Date.FormatStyle(date: .numeric, time: .standard)
        return entries
            .map { "\($0.date.formatted(stamp))  \($0.text)" }
            .joined(separator: "\n")
    }
}

/// Which gauge, what it is doing, the preferences, and the statements the app owes
/// whoever is using it.
///
/// **The gauge picker comes FIRST, with a rim** (Nuri, 2026-09-20): as a plain row it read
/// as a status line, and nobody with a WH-C06 could tell it was where to say so. The
/// Device card that follows says what the chosen one is doing.
///
/// The live gauge moved to a button on Today's bar once people turned out to use the gauge
/// with no routine at all (2026-09-20) — one door, not two. The all-time tally moved to
/// History, beside the sessions it adds up.
struct SettingsView: View {
    @Environment(DeviceStore.self) private var device
    @Environment(TemplateStore.self) private var templates
    @Environment(TourController.self) private var tour
    /// Whether routines exist decides if replaying the tour opens with the build-one act.
    @Query private var routines: [SessionTemplate]
    @Environment(SettingsStore.self) private var settings

    /// Confirmation for the tap that just happened — not persisted, so reopening Settings
    /// offers the reset again.
    @State private var guideReset = false
    @State private var tourReset = false

    var body: some View {
        ScreenScaffold(title: String(localized: "Settings")) {
            // WHICH gauge first: everything below describes whatever it selects.
            gaugeKindRow.staggerIn(0)
            deviceCard.staggerIn(1)
            weightUnitsCard.staggerIn(2)
            remindersCard.staggerIn(3)
            // ABOVE About: About is the statements the app OWES you (storage,
            // attribution, licence); a door out to a person is an action, not small print.
            SupportCard().staggerIn(4)
            openSourceCard.staggerIn(5)
            aboutCard.staggerIn(6)
        }
    }

    /// Which device rings. Routines sync; reminders must not, or every device on the account
    /// fires the same 19:30 — see `SettingsStore.remindsOnThisDevice`.
    private var remindersCard: some View {
        @Bindable var settings = settings
        let isPad = UIDevice.current.userInterfaceIdiom == .pad
        return MaterialCard(surface: .flat) {
            VStack(alignment: .leading, spacing: 12) {
                Toggle(isOn: $settings.remindsOnThisDevice) {
                    Text(isPad ? "Remind on this iPad" : "Remind on this iPhone")
                        .font(.system(.headline, weight: .semibold))
                }
                .accessibilityIdentifier("settings.remindsOnThisDevice")
                Text("Routine reminders are scheduled on each device separately. A session logged on any of them silences the day’s reminders everywhere once it syncs.")
                    .font(.system(.caption)).foregroundStyle(Ink.secondary)
            }
        }
        .onChange(of: settings.remindsOnThisDevice) { _, _ in
            templates.reminderDeviceSettingChanged()
        }
    }

    private var weightUnitsCard: some View {
        @Bindable var settings = settings
        return MaterialCard(surface: .flat) {
            VStack(alignment: .leading, spacing: 12) {
                Text("Weight units").font(.system(.headline, weight: .semibold))
                Picker("Weight units", selection: $settings.weightUnit) {
                    ForEach(WeightUnit.allCases, id: \.self) { unit in
                        Text("\(unit.name) (\(unit.symbol))").tag(unit)
                    }
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("settings.weightUnits")
                Text("Changes how weights are shown and entered. Your scale’s own unit setting is separate.")
                    .font(.system(.caption)).foregroundStyle(Ink.secondary)
            }
        }
    }

    // MARK: - Device

    private var deviceCard: some View {
        MaterialCard(surface: .flat) {
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
                        Text("\(BatteryDisplay.percentage(battery)) %")
                            .monospacedDigit()
                            .contentTransition(.numericText())
                    }
                }
                // A synthetic number that looks like a measurement is worse than none, so
                // demo mode is never ambiguous. Otherwise this states the SELECTED gauge,
                // not a hardcoded "Tindeq Progressor".
                LabeledContent("Source") {
                    Text(device.isMock ? String(localized: "Demo device") : device.gaugeKind.displayName)
                }
            }
            .font(.system(.subheadline))
        }
    }

    // MARK: - Which gauge

    /// The one card with a rim: bleu, two points (a hairline is mostly antialiased edge and
    /// measured under 3:1), drawn INSIDE the label so it presses with the card. The line
    /// under the name says there is something to pick from.
    private var gaugeKindRow: some View {
        NavigationLink {
            GaugePickerView()
        } label: {
            MaterialCard(surface: .flat) {
                HStack(spacing: 14) {
                    Image(systemName: "dot.radiowaves.left.and.right")
                        .font(.system(.title2))
                        .foregroundStyle(Accent.bleu)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Gauge")
                            .font(.system(.headline, weight: .semibold))
                            .foregroundStyle(Ink.primary)
                        Text(device.isMock ? String(localized: "Demo device") : device.gaugeKind.displayName)
                            .font(.system(.subheadline))
                            .foregroundStyle(Ink.secondary)
                        Text(gaugeChoiceNote)
                            .font(.system(.footnote))
                            .foregroundStyle(Ink.tertiary)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.top, 3)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right")
                        .font(.system(.footnote, weight: .semibold))
                        .foregroundStyle(Ink.tertiary)
                        .accessibilityHidden(true)
                }
                .fixedSize(horizontal: false, vertical: true)
            }
            .overlay {
                RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous)
                    .strokeBorder(Accent.bleu, lineWidth: 2)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
        // Full-width row, full-width hit area: material and Spacer are not hit-tested.
        .contentShape(RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel("Gauge. Currently \(device.isMock ? "the demo device" : device.gaugeKind.displayName). \(gaugeChoiceNote)")
    }

    /// Counted from the registry, like the makers sentence in About, so a ninth gauge
    /// cannot leave this line claiming eight.
    private var gaugeChoiceNote: String {
        String(localized: "Tap to choose yours — Get a Grip works with \(GaugeKind.selectable.count) different gauges.")
    }

    private var hasRoutine: Bool { !routines.isEmpty }

    /// Every maker the app speaks to, in picker order, de-duplicated so the sentence stays
    /// right the day two devices share one.
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
        MaterialCard(surface: .flat) {
            SettingsDisclosure("Open source & community") {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Get a Grip is open source under the Mozilla Public License 2.0.")
                        .font(.footnote).foregroundStyle(Ink.secondary)
                    sourceLink("Source code", symbol: "chevron.left.forwardslash.chevron.right",
                               path: "")
                    sourceLink("MPL 2.0", symbol: "doc.text", path: "/blob/main/LICENSE")
                    sourceLink("Open-source licenses", symbol: "doc.on.doc",
                               path: "/blob/main/THIRD_PARTY_NOTICES.txt")

                    Divider()

                    communityLink("Grip Connect", symbol: "wave.3.right",
                                  description: "Gauge protocols by Stevie-Ray Hartog (© 2024, BSD-2-Clause).",
                                  destination: "https://github.com/Stevie-Ray/hangtime-grip-connect")
                        .accessibilityIdentifier("settings.community.gripConnect")
                    communityLink("Crimpdeq", symbol: "wrench.and.screwdriver",
                                  description: "Open-source force sensor. Thanks to its creator for testing Get a Grip.",
                                  destination: "https://crimpdeq.com/")
                        .accessibilityIdentifier("settings.community.crimpdeq")
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

    private func communityLink(_ name: String, symbol: String,
                               description: LocalizedStringKey, destination: String) -> some View {
        Link(destination: URL(string: destination)!) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Image(systemName: symbol)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: name)
                        .font(.subheadline.weight(.medium))
                    Text(description)
                        .font(.footnote)
                        .foregroundStyle(Ink.secondary)
                }
                .fixedSize(horizontal: false, vertical: true)
                .multilineTextAlignment(.leading)
                Spacer(minLength: 8)
                Image(systemName: "arrow.up.right")
                    .font(.caption)
                    .foregroundStyle(Ink.tertiary)
                    .accessibilityHidden(true)
            }
            .font(.subheadline)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .tint(Accent.graphite)
        .accessibilityElement(children: .combine)
    }

    // MARK: - About

    private var aboutCard: some View {
        MaterialCard(surface: .flat) {
            VStack(alignment: .leading, spacing: 10) {
                CapsLabel(String(localized: "About"))

                storageLine

                LegalSettingsLinks()

                SettingsDisclosure("Device compatibility") {
                    VStack(alignment: .leading, spacing: 10) {

                        // Descriptive use only, and unconditional: the app is not made by,
                        // affiliated with or endorsed by these makers. **Built from the registry**,
                        // so adding a gauge cannot leave a maker out of the one legal line that
                        // must name them all.
                        Text("Get a Grip works with force gauges from \(makersSentence). It is not made by, affiliated with, or endorsed by any of them.")
                            .font(.system(.footnote))
                            .foregroundStyle(Ink.secondary)
                    }
                }

                if !device.diagnosticEntries.isEmpty {
                    SettingsDisclosure("Diagnostics") {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack(alignment: .firstTextBaseline) {
                                // WITHOUT THIS THE FEATURE DOES NOT WORK: the ring exists to travel to
                                // whoever is diagnosing the drop, and read-only evidence that cannot leave
                                // the device means retyping timestamps by hand.
                                CopyButton(text: { diagnosticReport },
                                           accessibilityLabel: "Copy the diagnostics to the clipboard") {
                                    CompactCopyLabel(copied: $0)
                                }
                                .buttonStyle(PressFeedbackButtonStyle())
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
                        // TWO DIFFERENT THINGS: this is the step-by-step hints INSIDE the builder;
                        // the row below is the spotlight walkthrough of the whole app. Their old
                        // labels read as one feature listed twice.
                        Button {
                            settings.builderGuideDone = false
                            guideReset = true
                            // Back to Today, or the reset happens two tabs away and reads as dead.
                            tour.requestedTab = 0
                        } label: {
                            Label(guideReset ? "Hints reset — open a routine to see them"
                                             : "Show the builder's hints again",
                                  systemImage: guideReset ? "checkmark" : "arrow.counterclockwise")
                                .font(.system(.footnote, weight: .semibold))
                                .foregroundStyle(guideReset ? Ink.secondary : Accent.graphite)
                                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                                // Holds a Label and draws full-width, so the shape must be declared.
                                .contentShape(Rectangle())
                        }
                        // `scales: false`, like every bare row on a shared `MaterialCard`: scaling
                        // shrinks the content while the card's backdrop stays put.
                        .buttonStyle(PressFeedbackButtonStyle(scales: false))
                        .disabled(guideReset)
                        .accessibilityLabel(guideReset ? "Builder hints reset"
                                                       : "Show the builder's hints again")

                        // The spotlight tour, not the builder's inline guide above.
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
    /// iCloud when it is device-only finds out when they lose the phone, so this states the
    /// real mode.
    ///
    /// `.isolated` gets amber and an icon: that fallback is a DIFFERENT store file, so
    /// routines saved earlier are genuinely absent from it. The other two are statements.
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

// MARK: - Support

/// A frozen mail request, like `RoutineShareRequest`: decided at the TAP, so a breadcrumb
/// or a gauge waking in a bag cannot rewrite a message somebody is typing.
private struct MailRequest: Identifiable {
    let id = UUID()
    var subject: String
    var body: String
    var attachment: MailComposer.Attachment?
}

/// A door out to a person: a feature request or a bug report, as an email.
///
/// Its own leaf view because `@Environment(\.openURL)` is presentation-scoped and its
/// identity moves (see `OpenSettingsButton`), so the smallest view holds it. The store is
/// read ONLY inside tap closures, never in `body`, so a breadcrumb arriving mid-session
/// cannot invalidate Settings.
///
/// Only READS what `DeviceStore` publishes: the store does not know mail exists, and
/// must not learn.
private struct SupportCard: View {
    @Environment(DeviceStore.self) private var device
    @Environment(\.openURL) private var openURL
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var typeSize

    /// Non-nil presents the composer; its `onFinish` clears it, dismissing the sheet.
    @State private var mail: MailRequest?
    @State private var askingDiagnostics = false
    /// Revealed only when the mail route genuinely failed — see `revealAddress()`.
    @State private var showsAddress = false

    private static let address = "support@nuri.run"
    private static let featureSubject = String(localized: "Get a Grip — Feature request")
    private static let bugSubject = String(localized: "Get a Grip — Bug report")

    var body: some View {
        MaterialCard(surface: .flat) {
            VStack(alignment: .leading, spacing: 10) {
                CapsLabel(String(localized: "Support"))

                row(title: String(localized: "Request a feature"), systemImage: "lightbulb") {
                    compose(subject: Self.featureSubject, withDiagnostics: false)
                }
                row(title: String(localized: "Report a bug"), systemImage: "ladybug") {
                    startBugReport()
                }
                rateRow

                if showsAddress {
                    addressRow
                }
            }
            .fixedSize(horizontal: false, vertical: true)
        }
        // Asked BEFORE the composer, because the answer decides what it is built
        // with — and only for a bug report.
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

    /// The persistent link Apple allows on a settings screen (StoreKit ›
    /// `RequestReviewAction`), straight to the write-a-review page, and the one place the ask
    /// can say WHY. See `ReviewRequestPolicy` for the prompt itself.
    private static let writeReviewURL = URL(string: "https://apps.apple.com/app/id6804236185?action=write-review")!

    private var rateRow: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                openURL(Self.writeReviewURL)
            } label: {
                Label(String(localized: "Rate on the App Store"), systemImage: "star")
                    .font(.system(.footnote, weight: .semibold))
                    .foregroundStyle(Accent.graphite)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressFeedbackButtonStyle(scales: false))
            .accessibilityLabel(String(localized: "Rate on the App Store. Opens the App Store."))
            Text("Get a Grip is free and open source. A rating helps other climbers find it.")
                .font(.system(.caption))
                .foregroundStyle(Ink.tertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

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

    /// THE LAST RESORT: with no mail account and no app taking a `mailto:`, the buttons
    /// above would be dead, so the address itself is the fallback, copyable in one tap.
    ///
    /// SIDE BY SIDE until `.accessibility1`, stacked after. Squeezed at accessibility sizes
    /// the address broke mid-token ("support@nuri.r / un"), and this string exists to be
    /// read and retyped. Same swap as `RoutineImportSheet`.
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
        CopyButton(text: { Self.address },
                   accessibilityLabel: "Copy the support address to the clipboard") {
            CompactCopyLabel(copied: $0)
        }
        .buttonStyle(PressFeedbackButtonStyle())
    }

    // MARK: Routing

    private func startBugReport() {
        // The dialog asks about an ATTACHMENT. With no breadcrumbs, or no mail
        // account (a `mailto:` cannot carry a file), there is nothing to ask.
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

    /// No composer, so hand the message to whatever mail app exists. No attachment survives
    /// this route, which is why the diagnostics question is skipped here.
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
        // Demo mode is a CLIENT, not a kind, so it is stated alongside the gauge: a
        // demo report read as a real Progressor sends somebody hunting firmware for
        // numbers a timer invented.
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

    /// The model IDENTIFIER ("iPhone17,1"), which is what a hardware quirk is looked up by.
    /// In the Simulator `uname` reports the HOST's architecture, so the simulated model is
    /// read from the environment first.
    private static var deviceModel: String {
        if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] {
            return simulated
        }
        var system = utsname()
        uname(&system)
        // Copied OUT inside the closure: the slice does not outlive it, and
        // `machine` is a fixed-size C tuple whose bytes end at the first NUL.
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

/// Which device the app measures with.
///
/// A pushed screen, not a card: eight rows with makers and caveats are 400 pt that do not
/// belong on a screen opened to check a battery. Not a menu either, which shows names and
/// nothing else, when this list must say which devices have actually been tested.
///
/// A tap applies and pops back, like the grip picker. Nothing connects:
/// `selectGaugeKind` leaves that to a Connect tap, because constructing a client raises
/// the Bluetooth prompt.
private struct GaugePickerView: View {
    @Environment(DeviceStore.self) private var device
    @Environment(SettingsStore.self) private var settings
    @Environment(\.dismiss) private var dismiss

    /// The kind whose first selection waits on its maker's note being read. Only the Frez
    /// Dyno has one, shown once per device; after Next it selects like any other.
    @State private var kindAwaitingIntro: GaugeKind?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.spacing) {
                MaterialCard(surface: .flat) {
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
        .sheet(item: $kindAwaitingIntro) { kind in
            // Next is the only way through, and completes the selection the tap started.
            FrezIntroSheet {
                settings.frezIntroSeen = true
                kindAwaitingIntro = nil
                device.selectGaugeKind(kind)
                dismiss()
            }
        }
    }

    private func row(for kind: GaugeKind) -> some View {
        // Demo mode is a client, not a kind, so nothing is selected while it runs;
        // tapping the selected gauge is how you leave it.
        let isSelected = device.gaugeKind == kind && !device.isMock
        return Button {
            if kind.capabilities.requiresRemoteCalibration, !settings.frezIntroSeen {
                kindAwaitingIntro = kind
                return
            }
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
            // Holds a Spacer and draws full-width: without this half of every row is dead.
            .contentShape(.rect)
        }
        // `scales: false`: one `MaterialCard` holds all eight rows — see the guide row.
        .buttonStyle(PressFeedbackButtonStyle(scales: false))
        .accessibilityLabel("\(kind.displayName). \(detail(for: kind)).")
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
    }

    /// Maker, plus the one fact that changes how much to trust the numbers — and, for a
    /// protocol its maker published, that the caveat is about testing, not provenance.
    private func detail(for kind: GaugeKind) -> String {
        var parts = [kind.maker]
        if !kind.capabilities.hardwareVerified {
            parts.append(kind.capabilities.protocolSource == .ported
                         ? String(localized: "ported protocol")
                         : String(localized: "official protocol, untested here"))
        }
        return parts.joined(separator: " · ")
    }

    /// ONE shared footnote for every unverified row, not eight warnings — phrased from the
    /// capability flags, so it changes the day a device is verified.
    @ViewBuilder
    private var footnotes: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(verificationNote)
            if let officialNote {
                Text(officialNote)
            }
            if let calibrationNote {
                Text(calibrationNote)
            }
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

    /// A protocol the maker published is a different kind of unknown from a port: the
    /// bytes are documented, only the device has not been in hand.
    private var officialNote: String? {
        let names = GaugeKind.selectable
            .filter { $0.capabilities.protocolSource == .vendorDocumented && !$0.capabilities.hardwareVerified }
            .map(\.displayName)
        guard !names.isEmpty else { return nil }
        return String(localized: "\(names.formatted(.list(type: .and))) speaks a protocol its maker published, but no unit has been tried on this app yet.")
    }

    /// The one gauge that needs a lookup, and the only time the app talks to a server
    /// other than Apple's — said here, once, in the place the choice is made.
    private var calibrationNote: String? {
        let names = GaugeKind.selectable
            .filter { $0.capabilities.requiresRemoteCalibration }
            .map(\.displayName)
        guard !names.isEmpty else { return nil }
        return String(localized: "\(names.formatted(.list(type: .and))) sends raw sensor counts, so the first time a unit connects the app looks up its calibration once from its maker, by serial number. The answer is kept on this phone and never asked for again; no other gauge involves a server.")
    }
}
