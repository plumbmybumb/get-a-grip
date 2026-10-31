// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The live force gauge — the runner's screen with no routine on it.
///
/// Some people use nothing else (2026-09-20), so it is one tap from Today and looks like
/// the app's working screen. Same anatomy as `RunnerView`'s stacked layout: the trace IS
/// the screen, numbers on one Liquid Glass panel over a state-coloured wash (bleu while
/// reading, steel otherwise), actions in one glass dock. A connected gauge reads at once;
/// Stop and Start measuring remain for pausing the stream. It still shows the raw truth
/// (current, peak, one-second mean, battery): this screen retires every hardware risk.
///
/// A stopwatch and an ESTIMATED pull count ride on the same panel (2026-09-28): the
/// numbers in the glass, Start/Pause/Reset in the dock. The count keeps going whenever
/// the gauge reads; Reset zeroes both.
///
/// A full-screen cover from Today's bar; `presentedAsCover` adds Done. The DEBUG
/// `-previewGauge` launch pushes it bare for screenshots.
struct GaugeView: View {
    var presentedAsCover = false

    @Environment(\.weightUnit) private var weightUnit
    @Environment(DeviceStore.self) private var device
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize

    /// Scaled, never a bare point size, or it stays fixed while the controls around it grow.
    @ScaledMetric(relativeTo: .largeTitle) private var heroSize: CGFloat = 78
    @ScaledMetric(relativeTo: .title3) private var unitSize: CGFloat = 22

    /// Where the open graph and canvas lie, so the wash hangs down to the graph's upper edge.
    @State private var traceGeometry = BackgroundTraceGeometry()
    /// Armed a beat after Start, or "Waiting for the gauge" flashes on every tap before the
    /// first packet.
    @State private var waitingForSignal = false
    /// The stopwatch and the estimated pull count — fed by `onTracePoint` for as long as
    /// the screen is open.
    @State private var tally = GaugeTally()
    /// Bumped per timer action, so the haptic names its cause (the tap), not a value.
    @State private var timerTick = 0
    /// Where Reset put the average's floor: the one-second mean counts only readings
    /// after it, so it restarts from the tap instead of carrying the last second over.
    @State private var averageSince: TimeInterval?

    /// The keeper's name for this screen — see `DeviceStore.keepStreamAlive`.
    private static let keepAliveHolder = "gauge"

    private static let dockSpacing: CGFloat = 8

    var body: some View {
        GeometryReader { geometry in
            if typeSize.isAccessibilitySize {
                // Scroll rather than clip labels or shrink type — the runner's rule.
                ScrollView {
                    content.frame(minHeight: geometry.size.height, alignment: .top)
                }
                .scrollBounceBehavior(.basedOnSize)
            } else {
                content.frame(width: geometry.size.width, height: geometry.size.height)
            }
        }
        .background { AppBackground() }
        .navigationTitle("Gauge")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if presentedAsCover {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                        .tint(Accent.graphite)
                        .accessibilityIdentifier("gauge.done")
                }
            }
        }
        .sensoryFeedback(.selection, trigger: timerTick)
        .task(id: device.isStreaming) {
            waitingForSignal = false
            guard device.isStreaming else { return }
            try? await Task.sleep(for: .seconds(1.5))
            guard !Task.isCancelled else { return }
            waitingForSignal = true
        }
        .keepsScreenAwake()
        .onAppear {
            // Capture the tally object, never a changing lookup through `self`.
            let tally = tally
            device.onTracePoint = { point in tally.receive(point) }
            // This screen exists to show the force: a silent stream is re-kicked here,
            // never handed to the person as "Wake" — see `DeviceStore.keepStreamAlive`.
            device.keepStreamAlive(Self.keepAliveHolder, true)
            startReadingIfConnected()
        }
        .onChange(of: device.state.isConnected) { _, connected in
            if connected { startReadingIfConnected() }
        }
        .onChange(of: device.isStreaming) { _, streaming in
            // How a pull cut off by Stop would have ended is unknowable.
            if !streaming { tally.cancelPull() }
        }
        .onDisappear {
            device.onTracePoint = nil
            device.keepStreamAlive(Self.keepAliveHolder, false)
            // Never leave the device streaming: it drains its battery and the radio.
            if device.isStreaming { device.stopStreaming(cause: .screenClosed) }
        }
    }

    // MARK: - The stacked layout: the graph is the screen, the numbers are glass

    /// One `GlassEffectContainer` for both glass surfaces, so the live canvas is blurred in
    /// one pass, not twice.
    private var content: some View {
        GlassEffectContainer(spacing: 24) {
            VStack(spacing: 12) {
                infoPanel
                graphRegion
                dock
            }
        }
        .padding(.horizontal, Metrics.hPadding)
        .padding(.top, 8)
        .padding(.bottom, Metrics.spacing)
        // The phone's width even on a regular-width screen — the runner's column rule.
        .frame(maxWidth: Metrics.maxContentWidth)
        .frame(maxWidth: .infinity)
        .background { wash }
    }

    /// The state colour under the glass, from the screen's top to the open graph — measured
    /// INSIDE `ignoresSafeArea`, as the runner's is.
    private var wash: some View {
        PhaseWash(tint: tint, edge: .top, length: traceGeometry.regionTopInCanvas)
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: {
                traceGeometry.canvas = $0
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)
    }

    /// Bleu while a reading is live, steel otherwise. Never alarm: not connected is the
    /// starting state here, not a fault.
    private var tint: Color {
        device.isStreaming ? StatusTint.engaged : StatusTint.calm
    }

    /// **The information panel** — hero, state, three readouts and the calibration line on
    /// one glass surface. `accessibleGlass`, never raw `.glassEffect`: under Reduce
    /// Transparency it goes opaque, the only way the numbers stay legible over a live curve.
    private var infoPanel: some View {
        VStack(spacing: 14) {
            // LEAVES: `currentKg`, `peakKg` and `trace` mutate per sample, and read from
            // THIS body would invalidate the whole screen 80×/second.
            GaugeHero(heroSize: heroSize, unitSize: unitSize)
            GaugeReadouts(averageSince: averageSince)
            GaugeTimerRow(tally: tally)
            // A remotely calibrated gauge can be connected with no force to show.
            // Frez's rule is to say why rather than guess.
            if let calibrationNote {
                Text(calibrationNote)
                    .font(.system(.footnote))
                    .foregroundStyle(device.calibrationStatus.isReady ? Ink.tertiary : StatusTint.armed)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("gauge.calibration")
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 14)
        .frame(maxWidth: .infinity)
        .accessibleGlass(nil, in: RunnerGlass.surfaceShape)
        .runnerFloatingShadow()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("gauge.panel")
    }

    /// **The open graph** — between panel and dock, the curve runs in the clear. It draws
    /// only the two notices that belong on a graph: what to do when nothing is connected,
    /// and the warning when a connected gauge is silent.
    private var graphRegion: some View {
        ZStack {
            Color.clear
            if !device.state.isConnected {
                notice(String(localized: "Connect a gauge and pull to see the force."))
            } else if device.isStreaming, waitingForSignal, !device.isSignalFresh {
                // The keeper is re-kicking; Wake is named only once it has given up.
                notice(device.streamRekickExhausted
                       ? String(localized: "No readings from the gauge. Check it's on, then tap Wake.")
                       : String(localized: "Connected. Waiting for readings…"))
                    .accessibilityIdentifier("gauge.signalWarning")
            }
        }
        .frame(minHeight: typeSize.isAccessibilitySize ? 200 : nil, maxHeight: .infinity)
        .background {
            GaugeTrace(plot: ForceTraceView.PlotInsets(top: 12, bottom: 6, trailing: 8))
                .padding(.horizontal, -Metrics.hPadding)
        }
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: {
            traceGeometry.region = $0
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("gauge.graph")
    }

    private func notice(_ text: String) -> some View {
        Text(text)
            .font(.system(.subheadline))
            .foregroundStyle(Ink.secondary)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 24)
    }

    // MARK: - The dock

    /// **The dock** — every action on ONE glass surface, each in a quiet ink well, the
    /// primary one tinted (Start in bleu, Stop in alarm, Connect in bleu). No
    /// `PrimaryGlassButton` in here: glass on glass is the layering Liquid Glass forbids.
    private var dock: some View {
        VStack(spacing: Self.dockSpacing) {
            if device.state.isConnected {
                OneLineActionRow(spacing: Self.dockSpacing) {
                    // A new zero under a pull in progress would read as the pull ending,
                    // so the pull is dropped rather than counted.
                    GaugeTareDockButton(fillsRowHeight: true, willTare: { tally.cancelPull() })
                        .accessibilityIdentifier("gauge.tare")
                    DockButton(String(localized: "Disconnect"), fillsRowHeight: true) { device.disconnect() }
                        .accessibilityIdentifier("gauge.disconnect")
                }
                timerRow
                DockTintedButton(device.isStreaming ? String(localized: "Stop") : String(localized: "Start measuring"),
                                 systemImage: device.isStreaming ? "stop.fill" : "play.fill",
                                 tint: device.isStreaming ? .alarm : .bleu) {
                    device.isStreaming
                        ? device.stopStreaming(cause: .userStopped)
                        : device.startStreaming(cause: .manualMeasurement)
                }
                .accessibilityIdentifier("gauge.measure")
            } else {
                // A broadcast search can wait for the scale to wake, so it stays
                // cancellable; every other attempt keeps its busy state.
                DockTintedButton(connectTitle,
                                 systemImage: device.canCancelBroadcastSearch ? "xmark" : "dot.radiowaves.left.and.right",
                                 tint: .bleu,
                                 enabled: !device.state.isBusy || device.canCancelBroadcastSearch) {
                    if device.canCancelBroadcastSearch {
                        device.disconnect()
                    } else if !device.state.isBusy, !device.state.isConnected {
                        device.connect()
                    }
                }
                .accessibilityLabel(connectTitle)
                .accessibilityValue(device.canCancelBroadcastSearch ? device.state.label : "")
                .accessibilityIdentifier("gauge.connectionAction")

                timerRow

                if case .unsupported = device.state {
                    Text("This device has no Bluetooth radio.")
                        .font(.system(.footnote))
                        .foregroundStyle(Ink.tertiary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 8)
                        .padding(.bottom, 4)
                }
            }
        }
        .padding(Self.dockSpacing)
        .accessibleGlass(nil, in: RunnerGlass.surfaceShape)
        .runnerFloatingShadow()
        // `.contain`, explicitly: an identifier on a bare container COMBINES its
        // children into one element.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("gauge.dock")
    }

    /// **A gauge that connects here starts reading** (Nuri, 2026-09-28): this screen
    /// exists to show the force, so a separate Start after Connect was a step with no
    /// decision in it. Only on opening and on connecting — after Stop it stays stopped.
    private func startReadingIfConnected() {
        guard device.state.isConnected, !device.isStreaming else { return }
        device.startStreaming(cause: .manualMeasurement)
    }

    /// Start/Pause and Reset for the stopwatch. Start also starts reading a connected
    /// gauge — pressing play on a gauge screen that shows nothing would be a timer
    /// running beside a dead graph.
    private var timerRow: some View {
        let running = tally.stopwatch.isRunning
        return OneLineActionRow(spacing: Self.dockSpacing) {
            DockButton(running ? String(localized: "Pause timer") : String(localized: "Start timer"),
                       systemImage: running ? "pause.fill" : "play.fill",
                       fillsRowHeight: true) {
                if running {
                    tally.pauseTimer()
                } else {
                    tally.startTimer()
                    if device.state.isConnected, !device.isStreaming {
                        device.startStreaming(cause: .manualMeasurement)
                    }
                }
                timerTick += 1
            }
            .accessibilityIdentifier("gauge.timer")
            // **Reset is the screen's statistics, all of them** (a Progressor user,
            // 2026-10-31): peak, average, timer and pulls start over together. No
            // confirmation — nothing here is a record — and never a tare or a stream
            // change: the zero and the reading are untouched.
            DockButton(String(localized: "Reset"), systemImage: "arrow.counterclockwise",
                       enabled: tally.canReset || device.hasPeak,
                       disabledReason: String(localized: "Resets peak, average, timer and pulls. Doesn't tare."),
                       fillsRowHeight: true) {
                resetStatistics()
            }
            .accessibilityIdentifier("gauge.timerReset")
        }
    }

    private func resetStatistics() {
        tally.reset()
        device.resetDisplayStatistics()
        averageSince = Date().timeIntervalSinceReferenceDate
        timerTick += 1
    }

    private var connectTitle: String {
        if device.canCancelBroadcastSearch { return String(localized: "Cancel") }
        return device.state.isBusy ? device.state.label : String(localized: "Connect gauge")
    }

    /// Nil for gauges that report kilograms, and for a calibrated one once its coefficient
    /// is in hand.
    private var calibrationNote: String? {
        switch device.calibrationStatus {
        case .notRequired, .ready:
            nil
        case .waitingForSerial, .resolving:
            String(localized: "Looking up this Dyno's calibration…")
        case .failed(_, let failure):
            failure.label
        }
    }
}

// MARK: - Live leaves

/// The big number. Its own view so a sample redraws THIS and nothing around it.
private struct GaugeHero: View {
    @Environment(\.weightUnit) private var weightUnit
    @Environment(DeviceStore.self) private var device
    var heroSize: CGFloat
    var unitSize: CGFloat

    var body: some View {
        VStack(spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(weightUnit.number(device.currentKg))
                    .font(.system(size: heroSize, weight: .thin))
                    .displayTracking(heroSize)
                    .monospacedDigit()
                    // A measurement snaps; see RunnerView.readout.
                    .contentTransition(.identity)
                Text(weightUnit.symbol)
                    .font(.system(size: unitSize, weight: .regular))
                    .foregroundStyle(Ink.tertiary)
            }
            .foregroundStyle(device.isStreaming ? StatusTint.engaged : Ink.primary)
            .animation(Motion.live, value: device.currentKg)
            // A monospaced decimal cannot wrap, so without this floor it clipped at AX3.
            .lineLimit(1)
            .minimumScaleFactor(0.6)

            CapsLabel(device.isStreaming ? String(localized: "Live") : String(localized: "Idle"))
        }
        .frame(maxWidth: .infinity)
        // A numeral changing 80×/sec is unusable under VoiceOver; the summary below
        // is the accessible channel.
        .accessibilityHidden(true)
    }
}

/// The trace. `device.trace` grows per sample; nothing else here does. Drawn LIT, like
/// the runner's — see `ForceTraceView.lit`.
private struct GaugeTrace: View {
    @Environment(DeviceStore.self) private var device
    var plot: ForceTraceView.PlotInsets

    var body: some View {
        ForceTraceView(samples: device.trace,
                       tint: device.isStreaming ? StatusTint.engaged : Ink.tertiary,
                       nominalSampleRate: device.gaugeCapabilities.nominalSampleRate,
                       bridgesSparseDelivery: device.gaugeCapabilities.isBroadcast,
                       diagnostics: device.pipelineDiagnostics,
                       plot: plot, lit: true)
    }
}

/// Peak, one-second mean and battery, as three quiet columns. The mean walks the whole
/// trace, so it wants to be alone in here.
private struct GaugeReadouts: View {
    /// Readings before this playback time are left out of the mean — Reset's floor.
    var averageSince: TimeInterval?

    @Environment(\.weightUnit) private var weightUnit
    @Environment(DeviceStore.self) private var device

    /// Rolling one-second average — the number you read when checking a steady hold. nil
    /// when idle, so the column shows "—" rather than a stale mean.
    private var averageKg: Double? {
        guard device.isStreaming, let newest = device.trace.last else { return nil }
        var sum = 0.0, count = 0.0
        for sample in device.trace.reversed() {
            // Playback-time age, same convention as the trace's own drawing.
            guard newest.t - sample.t <= 1.0 else { break }
            if let averageSince, sample.t < averageSince { break }
            sum += sample.kg
            count += 1
        }
        return count > 0 ? sum / count : nil
    }

    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let average = averageKg
        let items = [
            Item(title: String(localized: "Peak"), value: weightUnit.number(device.peakKg), unit: weightUnit.symbol),
            // The reading the flickering hero can't give: hang steady, read the
            // average. (Firmware version lives in Settings.)
            Item(title: String(localized: "Average"),
                 value: average.map { weightUnit.number($0) } ?? "—",
                 unit: average == nil ? "" : weightUnit.symbol),
            Item(title: String(localized: "Battery"),
                 value: device.batteryFraction.map { "\(BatteryDisplay.percentage($0))" } ?? "—",
                 unit: device.batteryFraction == nil ? "" : String(localized: "%")),
        ]
        return Group {
            // Accessibility sizes LIST them, label then value: three columns at AX3
            // hyphenated the labels ("AV- / ER- / AGE") and cut the numbers to "2…kg".
            if typeSize.isAccessibilitySize {
                VStack(spacing: 8) {
                    ForEach(items, id: \.title) { stackedReadout($0) }
                }
            } else {
                HStack(spacing: 8) {
                    ForEach(items, id: \.title) { readout($0.title, value: $0.value, unit: $0.unit) }
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("""
            Current \(weightUnit.number(device.currentKg)) \(weightUnit.spokenName), \
            peak \(weightUnit.number(device.peakKg)) \(weightUnit.spokenName)\
            \(average.map { String(localized: ", one-second average \(weightUnit.number($0)) \(weightUnit.spokenName)") } ?? "")
            """)
    }

    private func readout(_ title: String, value: String, unit: String) -> some View {
        VStack(spacing: 4) {
            CapsLabel(title)
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text(value)
                    .font(.system(.title3, weight: .medium))
                    .monospacedDigit()
                    // A measurement snaps; see RunnerView.readout.
                    .contentTransition(.identity)
                if !unit.isEmpty {
                    Text(unit).font(.system(.caption)).foregroundStyle(Ink.tertiary)
                }
            }
            .foregroundStyle(Ink.primary)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity)
    }

    private struct Item {
        let title: String
        let value: String
        let unit: String
    }

    /// One readout as a row, label leading and value trailing, or the label over the
    /// value when even the row does not fit. Only the NUMBER may shrink: a label is
    /// words, and words wrap at their own boundaries.
    private func stackedReadout(_ item: Item) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                CapsLabel(item.title)
                Spacer(minLength: 0)
                stackedValue(item, shrinks: false)
            }
            VStack(alignment: .leading, spacing: 2) {
                CapsLabel(item.title)
                stackedValue(item, shrinks: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func stackedValue(_ item: Item, shrinks: Bool) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 2) {
            Text(item.value)
                .font(.system(.title3, weight: .medium))
                .monospacedDigit()
                .contentTransition(.identity)
                .lineLimit(1)
                .minimumScaleFactor(shrinks ? 0.7 : 1)
            if !item.unit.isEmpty {
                Text(item.unit).font(.system(.caption)).foregroundStyle(Ink.tertiary)
                    .fixedSize()
            }
        }
        .foregroundStyle(Ink.primary)
    }
}

// MARK: - Stopwatch and pull count

/// The gauge screen's stopwatch and ESTIMATED pull count.
///
/// Only what is drawn is observable, and it is written only when it CHANGES —
/// Observation fires on every set, and `receive` runs ~80×/second.
@Observable @MainActor
final class GaugeTally {
    private(set) var stopwatch = Stopwatch()
    private(set) var pulls = 0
    @ObservationIgnored private var estimator = RepEstimator()

    /// Seconds on a clock that keeps running while the phone sleeps, so a locked screen
    /// cannot lose stopwatch time.
    static func now() -> TimeInterval {
        let elapsed = ContinuousClock.now - origin
        return Double(elapsed.components.seconds) + Double(elapsed.components.attoseconds) * 1e-18
    }
    private static let origin = ContinuousClock.now

    var canReset: Bool {
        stopwatch.isRunning || stopwatch.accumulated > 0 || pulls > 0
    }

    func receive(_ point: DeviceStore.TracePoint) {
        guard estimator.add(point.kg, at: point.t) else { return }
        pulls = estimator.count
    }

    func cancelPull() {
        estimator.cancelPull()
    }

    func startTimer() {
        stopwatch.start(at: Self.now())
    }

    func pauseTimer() {
        stopwatch.pause(at: Self.now())
    }

    /// Zeroes both: a new set starts from nothing.
    func reset() {
        stopwatch.reset()
        estimator.reset()
        if pulls != 0 { pulls = 0 }
    }
}

/// The stopwatch and the pull count, in the panel under the readouts. A LEAF: the
/// running clock ticks ten times a second and must redraw nothing else.
private struct GaugeTimerRow: View {
    var tally: GaugeTally

    var body: some View {
        HStack(alignment: .lastTextBaseline, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                CapsLabel(String(localized: "Timer"))
                if tally.stopwatch.isRunning {
                    TimelineView(.periodic(from: .now, by: 0.1)) { _ in
                        clock(tally.stopwatch.elapsed(at: GaugeTally.now()))
                    }
                } else {
                    clock(tally.stopwatch.accumulated)
                }
            }
            Spacer(minLength: 0)
            VStack(alignment: .trailing, spacing: 4) {
                CapsLabel(String(localized: "Pulls"))
                Text(verbatim: "\(tally.pulls)")
                    .font(.system(.largeTitle, weight: .medium))
                    .monospacedDigit()
                    // A count counts, so it rolls — unlike the snapping measurements.
                    .contentTransition(.numericText())
                    .animation(Motion.live, value: tally.pulls)
                    .foregroundStyle(Ink.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
            }
        }
        .padding(.top, 2)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "Timer \(spoken(tally.stopwatch.elapsed(at: GaugeTally.now()))). Pulls counted: \(tally.pulls)"))
        .accessibilityIdentifier("gauge.tally")
    }

    /// Tenths drawn smaller: they change ten times a second and would otherwise pull
    /// the eye off the seconds.
    private func clock(_ seconds: TimeInterval) -> some View {
        let label = Stopwatch.label(seconds)
        let split = label.lastIndex(of: ".") ?? label.endIndex
        return HStack(alignment: .firstTextBaseline, spacing: 0) {
            Text(label[..<split])
                .font(.system(.largeTitle, weight: .medium))
            Text(label[split...])
                .font(.system(.title2, weight: .medium))
                .foregroundStyle(Ink.secondary)
        }
        .monospacedDigit()
        // A stopwatch at ten ticks a second would blur if it rolled.
        .contentTransition(.identity)
        .foregroundStyle(tally.stopwatch.isRunning ? Ink.primary : Ink.secondary)
        .lineLimit(1)
        .minimumScaleFactor(0.6)
    }

    private func spoken(_ seconds: TimeInterval) -> String {
        Duration.seconds(Int(seconds)).formatted(.units(allowed: [.hours, .minutes, .seconds], width: .wide))
    }
}

/// The gauge dock's paired actions: side by side while every label fits on ONE line at
/// an equal share of the row, one per line otherwise.
///
/// `AdaptiveActionRow` keeps two columns through a two-line label, which on a 13 mini
/// at XXXL broke "Start / timer" while "Reset" beside it, and Tare and Disconnect above
/// it, each stayed on one line: one row of the dock wrapping and its neighbours not.
/// Measured from the labels' own ideal widths, so it holds in any language. Whenever
/// both fit it places exactly what `AdaptiveActionRow` places.
private struct OneLineActionRow: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? subviews.map { $0.sizeThatFits(.unspecified).width }.reduce(0, +)
            + spacing * CGFloat(max(0, subviews.count - 1))
        let rows = rows(width: width, subviews: subviews)
        return CGSize(width: width, height: rows.last.map { $0.y + $0.height } ?? 0)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for row in rows(width: bounds.width, subviews: subviews) {
            for (column, index) in row.indices.enumerated() {
                subviews[index].place(at: CGPoint(x: bounds.minX + CGFloat(column) * (row.width + spacing),
                                                 y: bounds.minY + row.y),
                                      anchor: .topLeading,
                                      proposal: ProposedViewSize(width: row.width, height: row.height))
            }
        }
    }

    private struct Row {
        let indices: Range<Int>
        let width: CGFloat
        let height: CGFloat
        let y: CGFloat
    }

    private func rows(width: CGFloat, subviews: Subviews) -> [Row] {
        guard !subviews.isEmpty else { return [] }
        let shared = max(0, (width - spacing * CGFloat(subviews.count - 1)) / CGFloat(subviews.count))
        let columns = subviews.allSatisfy { $0.sizeThatFits(.unspecified).width <= shared }
            ? subviews.count : 1
        var rows: [Row] = []
        var y: CGFloat = 0
        for start in stride(from: 0, to: subviews.count, by: columns) {
            let end = min(start + columns, subviews.count)
            let cellWidth = max(0, (width - spacing * CGFloat(end - start - 1)) / CGFloat(end - start))
            let height = (start..<end).map {
                subviews[$0].sizeThatFits(ProposedViewSize(width: cellWidth, height: nil)).height
            }.max() ?? 0
            rows.append(Row(indices: start..<end, width: cellWidth, height: height, y: y))
            y += height + spacing
        }
        return rows
    }
}
