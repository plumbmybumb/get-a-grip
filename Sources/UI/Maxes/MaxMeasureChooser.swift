// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// What a measurement will be, chosen BEFORE the gauge screen opens (Nuri, 2026-10-01:
/// "you decide what type of max you're doing before you get to the measurement screen").
///
/// Replaced a three-button dialog (one hand / both hands / critical force) once a max grew
/// a LENGTH: the peak, or a timed max — your average over 5, 10 or 15 s, or any length on
/// the dial. A dialog cannot hold a dial, and a length picked on the gauge screen meant a
/// pull already logged could be measuring the wrong thing. Here the kind is settled first,
/// and the gauge screen only states it.
///
/// The two hand buttons ARE the start: choosing how you pull is the last decision, so it
/// commits the rest in the same tap.
struct MaxMeasureChooser: View {
    let grip: GripSpec
    /// The length to open on — what a routine is waiting for (`TemplateStore.missingTimedLength`).
    let initialSeconds: Int
    /// false hides critical force: a new-max form is about a max.
    var offersCriticalForce: Bool
    var onChoose: (Side, Int) -> Void
    var onCriticalForce: () -> Void
    var onCancel: () -> Void

    private enum Kind: Hashable { case max, criticalForce }
    @State private var kind: Kind = .max
    @State private var seconds: Int
    /// "Other" is sticky once chosen, so dragging the dial onto 10 does not snap it shut.
    @State private var other: Bool
    /// Measured, so the sheet is exactly as tall as what it holds (Nuri, 2026-10-01: a
    /// full-height sheet was mostly dead space). It grows when the dial appears.
    @State private var contentHeight: CGFloat = 0
    @State private var actionsHeight: CGFloat = 0
    @Environment(\.dynamicTypeSize) private var typeSize

    private static let presets = [5, 10, 15]
    static let range = 3...60

    init(grip: GripSpec, initialSeconds: Int, offersCriticalForce: Bool,
         onChoose: @escaping (Side, Int) -> Void, onCriticalForce: @escaping () -> Void,
         onCancel: @escaping () -> Void) {
        self.grip = grip
        self.initialSeconds = initialSeconds
        self.offersCriticalForce = offersCriticalForce
        self.onChoose = onChoose
        self.onCriticalForce = onCriticalForce
        self.onCancel = onCancel
        _seconds = State(initialValue: initialSeconds)
        _other = State(initialValue: initialSeconds > 0 && !Self.presets.contains(initialSeconds))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if offersCriticalForce {
                        Picker("Measure", selection: $kind) {
                            Text("Max").tag(Kind.max)
                            Text("Critical force").tag(Kind.criticalForce)
                        }
                        .pickerStyle(.segmented)
                        .accessibilityIdentifier("max.chooser.kind")
                    }
                    if kind == .max { maxOptions } else { criticalForceNote }
                }
                .padding(.horizontal, Metrics.hPadding)
                .padding(.vertical, 8)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
            }
            .scrollBounceBehavior(.basedOnSize)
            .background { AppBackground() }
            .safeAreaInset(edge: .bottom) {
                actions.onGeometryChange(for: CGFloat.self) { $0.size.height } action: { actionsHeight = $0 }
            }
            .navigationTitle(offersCriticalForce ? "What are you measuring?" : "Measure a max")
            .navigationSubtitle(grip.displayName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                        .tint(Accent.graphite)
                        .accessibilityIdentifier("max.chooser.cancel")
                }
            }
        }
        .presentationDetents([detent])
    }

    /// Content + actions + the navigation bar. Large at accessibility sizes, where the
    /// content may need to scroll.
    private var detent: PresentationDetent {
        guard !typeSize.isAccessibilitySize else { return .large }
        // Before the first measurement: a close estimate, so the sheet does not open tall
        // and then visibly shrink.
        guard contentHeight > 0, actionsHeight > 0 else { return .height(Self.estimatedHeight) }
        return .height(contentHeight + actionsHeight + Self.chromeHeight)
    }

    /// The inline navigation bar and the sheet's top margin.
    private static let chromeHeight: CGFloat = 72
    private static let estimatedHeight: CGFloat = 480

    private var maxOptions: some View {
        VStack(alignment: .leading, spacing: 12) {
            ChipGrid(base: 5) {
                Chip(title: String(localized: "Peak"), isSelected: seconds == 0 && !other) {
                    other = false
                    seconds = 0
                }
                .accessibilityIdentifier("max.chooser.peak")
                ForEach(Self.presets, id: \.self) { preset in
                    Chip(title: String(localized: "\(preset) s"), isSelected: seconds == preset && !other) {
                        other = false
                        seconds = preset
                    }
                    .accessibilityIdentifier("max.chooser.\(preset)")
                }
                Chip(title: String(localized: "Other"), isSelected: other) {
                    other = true
                    if seconds == 0 { seconds = 20 }
                }
                .accessibilityIdentifier("max.chooser.other")
            }
            if other {
                // The builder's dial: one detent per second, so 17 s is a drag, and the
                // number is tappable to type.
                IntValueRow(title: String(localized: "Hold for"), unit: "s",
                            value: $seconds, range: Self.range, limit: Self.range,
                            control: .dial(Self.range.map(Double.init)),
                            spokenUnit: String(localized: "seconds"))
                    .accessibilityIdentifier("max.chooser.dial")
            }
            Text(seconds == 0
                 ? String(localized: "Your hardest single reading.")
                 : String(localized: "Your average over \(seconds) s. Letting go early doesn't count."))
                .font(.system(.footnote))
                .foregroundStyle(Ink.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var criticalForceNote: some View {
        Text("The four-minute endurance test: all-out pulls until your force levels off.")
            .font(.system(.footnote))
            .foregroundStyle(Ink.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var actions: some View {
        VStack(spacing: 10) {
            if kind == .max {
                PrimaryGlassButton(title: String(localized: "One hand at a time"), tint: Accent.graphite) {
                    onChoose(.left, seconds)
                }
                .accessibilityIdentifier("max.mode.hands")
                SecondaryGlassButton(title: String(localized: "Both hands together")) {
                    onChoose(.both, seconds)
                }
                .accessibilityIdentifier("max.mode.both")
            } else {
                PrimaryGlassButton(title: String(localized: "Start the test"), tint: Accent.graphite) {
                    onCriticalForce()
                }
                .accessibilityIdentifier("max.mode.criticalForce")
            }
        }
        .padding(.horizontal, Metrics.hPadding)
        .padding(.top, 10)
        .padding(.bottom, 6)
        .frame(maxWidth: Metrics.maxContentWidth)
        .frame(maxWidth: .infinity)
    }
}

extension View {
    /// The question before every measurement on a grip — see `MaxMeasureChooser`.
    /// `onCriticalForce` nil hides that choice: a new-max form is about a max.
    func maxMeasureChooser(for grip: Binding<GripSpec?>,
                           initialSeconds: @escaping (GripSpec) -> Int,
                           onChoose: @escaping (GripSpec, Side, Int) -> Void,
                           onCriticalForce: ((GripSpec) -> Void)? = nil) -> some View {
        modifier(MaxMeasureChooserPresenter(grip: grip, initialSeconds: initialSeconds,
                                            onChoose: onChoose, onCriticalForce: onCriticalForce))
    }
}

/// Holds the choice until the sheet has GONE: the caller answers with a full-screen
/// cover, and presenting one while this sheet is still dismissing is dropped silently.
private struct MaxMeasureChooserPresenter: ViewModifier {
    @Binding var grip: GripSpec?
    var initialSeconds: (GripSpec) -> Int
    var onChoose: (GripSpec, Side, Int) -> Void
    var onCriticalForce: ((GripSpec) -> Void)?

    @State private var pending: (() -> Void)?

    func body(content: Content) -> some View {
        content.sheet(isPresented: Binding(get: { grip != nil }, set: { if !$0 { grip = nil } }),
                      onDismiss: {
                          let action = pending
                          pending = nil
                          action?()
                      }) {
            if let chosen = grip {
                MaxMeasureChooser(grip: chosen, initialSeconds: initialSeconds(chosen),
                                  offersCriticalForce: onCriticalForce != nil,
                                  onChoose: { side, seconds in
                                      pending = { onChoose(chosen, side, seconds) }
                                      grip = nil
                                  },
                                  onCriticalForce: {
                                      pending = { onCriticalForce?(chosen) }
                                      grip = nil
                                  },
                                  onCancel: { grip = nil })
            }
        }
    }
}
