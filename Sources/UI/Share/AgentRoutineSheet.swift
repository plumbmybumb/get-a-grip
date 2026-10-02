// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI
import UIKit

/// "Create with AI" (Nuri, 2026-10-02): two steps, both on one screen. Copy the
/// instructions into any AI chat, describe the routine there, paste the reply back.
///
/// The reply is read HERE, so a paste that is not a routine says so where the climber can
/// paste again. A readable one leaves through Today's import inbox, like a scanned code,
/// and lands in the same preview — nothing is saved until "Add to my routines".
///
/// The paste is a system `PasteButton`: reading the clipboard from an ordinary button
/// would raise iOS's "allow paste" alert on every attempt.
struct AgentRoutineSheet: View {
    var onRoutine: (AgentRoutine.Reading) -> Void
    var onClose: () -> Void

    @State private var copied = false
    @State private var failure: AgentRoutine.Failure?
    @State private var copyTick = 0

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text("Describe your routine to ChatGPT, Claude or any AI chat in your own words. It writes the routine in a form Get a Grip can read.")
                        .font(.system(.subheadline))
                        .foregroundStyle(Ink.secondary)
                        .fixedSize(horizontal: false, vertical: true)

                    stepOne
                    stepTwo
                }
                .padding(.horizontal, Metrics.hPadding)
                .padding(.top, 12)
                .padding(.bottom, 28)
                .frame(maxWidth: Metrics.maxContentWidth)
                .frame(maxWidth: .infinity)
            }
            .background { AppBackground() }
            .scrollBounceBehavior(.basedOnSize)
            .navigationTitle("Create with AI")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close", action: onClose)
                }
            }
            .sensoryFeedback(.success, trigger: copyTick)
        }
    }

    // MARK: - 1 · Instructions out

    private var stepOne: some View {
        MaterialCard(surface: .flat) {
            VStack(alignment: .leading, spacing: 12) {
                CapsLabel(String(localized: "1 · GIVE THE AI THE INSTRUCTIONS"))
                Text("Paste them into a new chat. The AI asks how you want to train, then writes the routine.")
                    .font(.system(.footnote))
                    .foregroundStyle(Ink.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                PrimaryGlassButton(title: copied ? String(localized: "Copied") : String(localized: "Copy instructions"),
                                   systemImage: copied ? "checkmark" : "doc.on.doc",
                                   tint: Accent.graphite) {
                    UIPasteboard.general.string = AgentRoutine.instructions
                    copied = true
                    copyTick += 1
                }
                .accessibilityIdentifier("agent.copy")

                // Straight into an AI app's share extension, for anyone whose assistant
                // takes text that way.
                ShareLink(item: AgentRoutine.instructions) {
                    Label("Share to an app", systemImage: "square.and.arrow.up")
                        .font(.system(.footnote, weight: .semibold))
                        .foregroundStyle(Accent.graphite)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .contentShape(.rect)
                }
                .buttonStyle(PressFeedbackButtonStyle())
            }
        }
    }

    // MARK: - 2 · Routine in

    private var stepTwo: some View {
        MaterialCard(surface: .flat) {
            VStack(alignment: .leading, spacing: 12) {
                CapsLabel(String(localized: "2 · PASTE THE ROUTINE"))
                Text("When the AI has written the code block, copy it and paste it here. You'll see the routine before it's saved.")
                    .font(.system(.footnote))
                    .foregroundStyle(Ink.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                PasteButton(payloadType: String.self) { strings in
                    let text = strings.joined(separator: "\n")
                    Task { @MainActor in read(text) }
                }
                .labelStyle(.titleAndIcon)
                .buttonBorderShape(.capsule)
                .controlSize(.large)
                .tint(Accent.graphite)
                .frame(maxWidth: .infinity)
                .accessibilityHint(Text("Reads the routine the AI wrote."))
                .accessibilityIdentifier("agent.paste")

                if let failure {
                    Text(failure.message)
                        .font(.system(.footnote, weight: .medium))
                        .foregroundStyle(Accent.alarm)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityAddTraits(.updatesFrequently)
                        .accessibilityIdentifier("agent.failure")
                }
            }
        }
        .animation(Motion.state(false), value: failure)
    }

    private func read(_ text: String) {
        switch AgentRoutine.read(text) {
        case .success(let reading):
            failure = nil
            onRoutine(reading)
        case .failure(let reason):
            failure = reason
            UIAccessibility.post(notification: .announcement, argument: reason.message)
        }
    }
}
