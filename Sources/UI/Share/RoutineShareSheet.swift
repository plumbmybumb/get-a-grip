// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import CoreTransferable
import Foundation
import SwiftUI
import UniformTypeIdentifiers
import UIKit

/// A frozen share request, like `ShareCalendarRequest`: the deck behind the sheet can
/// move (a swipe, a merge, an edit) and the screen still shows the routine whose menu was
/// tapped. The sheet observes NO store, so the code and the name describe one thing.
struct RoutineShareRequest: Identifiable {
    let id = UUID()
    let name: String
    let metaLine: String
    let signatureFingers: FingerSet?
    /// So the mark wears the SAME rung colour as the card it came from — bleu means "nothing
    /// resolves", and a card burning red must not turn bleu here.
    let peakIntensity: Double?
    let url: URL
}

/// The routine as a QR code. The code IS the routine: no server, no account, no link to
/// rot. Everything rides in the payload, and percentage targets resolve against THEIR
/// maxes — the point of prescribing a fraction.
struct RoutineShareSheet: View {
    let request: RoutineShareRequest
    var onClose: () -> Void

    @State private var rendered: RenderedRoutineShare?
    /// The one render attempt failed — terminal, and said in words: a permanently disabled
    /// "Preparing image…" is a spinner that lies.
    @State private var renderFailed = false

    private var payload: String { request.url.absoluteString }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header

                    QRCodeView(string: payload)
                        .frame(maxWidth: .infinity)

                    Text("To add \(request.name), scan with Camera on iPhone. On Android, open Get a Grip and choose Scan a routine in the Today menu.")
                        .font(.system(.footnote))
                        .foregroundStyle(Ink.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, Metrics.hPadding)
                .padding(.top, 12)
                .padding(.bottom, 12)
                .frame(maxWidth: Metrics.maxContentWidth)
                .frame(maxWidth: .infinity)
            }
            .scrollBounceBehavior(.basedOnSize)
            .scrollEdgeEffectStyle(.soft, for: .bottom)
            .background { AppBackground() }
            .navigationTitle("Share routine")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Close") { onClose() }
                }
            }
            // In the safe area, not at the foot of the scroll: at the `.medium` detent
            // an in-document button sat 33 pt into the home-indicator strip, and
            // dragging resized the sheet instead of scrolling to it.
            .safeAreaInset(edge: .bottom) { shareAction }
        }
        .presentationDetents([.medium, .large])
        .task {
            // After the slide-up, never during it — see `ShareRenderTiming`.
            guard rendered == nil, !renderFailed,
                  await ShareRenderTiming.wait(ShareRenderTiming.afterPresentation) else { return }
            await renderImage()
        }
    }

    /// The card's own anatomy — mark, name, plan line — so the sheet reads as that card.
    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                EdgeMark(fingers: request.signatureFingers ?? .four,
                         rungTint: PlanMath.IntensityBand.band(for: request.peakIntensity).tint)
                Text(request.name)
                    .font(.system(.title2, weight: .semibold))
                    .foregroundStyle(Ink.primary)
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
                Spacer(minLength: 0)
            }
            Text(request.metaLine)
                .font(.system(.footnote))
                .monospacedDigit()
                .foregroundStyle(Ink.secondary)
                .lineLimit(2)
                .minimumScaleFactor(0.85)
        }
        // One VoiceOver stop, like the import sheet's header.
        .accessibilityElement(children: .combine)
    }

    /// Glass is allowed here: a sheet carries no `.contextMenu`, so nothing is lifted.
    @ViewBuilder
    private var shareAction: some View {
        Group {
            if let rendered {
                ShareLink(
                    item: rendered.file,
                    preview: SharePreview(
                        "Get a Grip — \(request.name)",
                        image: Image(uiImage: rendered.image))
                ) {
                    actionLabel(String(localized: "Share the code"), systemImage: "square.and.arrow.up",
                                prominent: true)
                }
                .buttonStyle(.glassProminent)
                .tint(Accent.graphite)
            } else if renderFailed {
                // The on-screen QR still works; only the PNG failed. A sentence beats a
                // control that looks tappable and never will be.
                Text("Couldn't prepare a shareable image. The code above still scans.")
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(Ink.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, minHeight: Metrics.buttonHeight)
            } else {
                Button {} label: {
                    actionLabel(String(localized: "Preparing image…"), systemImage: nil, prominent: false)
                }
                .buttonStyle(.glass)
                .tint(Accent.graphite)
                .disabled(true)
            }
        }
        .padding(.horizontal, Metrics.hPadding)
        .padding(.bottom, 4)
        .frame(maxWidth: Metrics.maxContentWidth)
        .frame(maxWidth: .infinity)
    }

    /// `prominent` picks the ink: INVERSE ink is right only over `.glassProminent`'s opaque
    /// graphite fill — on plain glass it rendered white on the light field, the same
    /// white-on-white trap `Accent.graphite` sets for any fill without its own label colour.
    private func actionLabel(_ title: String, systemImage: String?,
                             prominent: Bool) -> some View {
        HStack(spacing: 8) {
            if let systemImage { Image(systemName: systemImage) }
            Text(title)
        }
        .font(.system(.headline, weight: .semibold))
        .foregroundStyle(prominent
                         ? Color.adaptive(Color(hex: "FFFFFF"), Color(hex: "1B1F25"))
                         : Accent.graphite)
        // Full-width and padded: the drawn label is not the hit area.
        .actionLabelLayout(minHeight: Metrics.buttonHeight, fullWidth: true)
        .contentShape(.rect)
    }

    private var exportCard: RoutineShareExportCard {
        RoutineShareExportCard(name: request.name, payload: payload)
    }

    /// Rendered once the sheet has settled (the request is frozen). `isOpaque = false` keeps
    /// the rounded corners transparent instead of squaring the PNG off with white.
    @MainActor
    private func renderImage() async {
        // The QR must have drawn before baking: a failed generation renders the
        // fallback SENTENCE, and a card carrying only an error must not be shared.
        guard QRCodeView.canRender(payload) else {
            renderFailed = true
            return
        }
        let renderer = ImageRenderer(content: exportCard)
        renderer.isOpaque = false
        renderer.scale = 3
        renderer.proposedSize = ProposedViewSize(
            width: RoutineShareExportCard.width,
            height: RoutineShareExportCard.height)
        guard let cgImage = renderer.cgImage else {
            renderFailed = true
            return
        }
        let png = await ShareImageEncoder.shared.pngData(for: cgImage)
        guard !Task.isCancelled else { return }
        guard let png else {
            renderFailed = true
            return
        }
        rendered = RenderedRoutineShare(
            image: UIImage(cgImage: cgImage, scale: 3, orientation: .up),
            file: SharePNG(data: png, filename: "get-a-grip-routine.png"))
    }
}

private struct RenderedRoutineShare {
    let image: UIImage
    let file: SharePNG
}

/// The shared asset. Every colour is FIXED: `ImageRenderer` resolves adaptive colours
/// against whatever traits it inherits, so ink could flip with the sharer's appearance
/// and export dark modules on a dark field. Also why `EdgeMark` (in inverting
/// `Accent.graphite`) stays off this card.
private struct RoutineShareExportCard: View {
    static let width: CGFloat = 360
    static let height: CGFloat = 448

    let name: String
    let payload: String

    private static let ink = Color(hex: "1B1F25")

    var body: some View {
        VStack(spacing: 16) {
            QRCodeView(string: payload, size: 236)

            Text(name)
                .font(.system(.title3, weight: .semibold))
                .lineLimit(2)
                .multilineTextAlignment(.center)
                .minimumScaleFactor(0.6)

            // The image travels alone to people who may not know the app; "GET A GRIP"
            // names the sender, not the action.
            Text("iPhone: Camera. Android: Get a Grip → Today menu → Scan a routine.")
                .font(.system(.caption))
                .multilineTextAlignment(.center)
                .opacity(0.7)

            Spacer(minLength: 8)

            Text("GET A GRIP")
                .font(.labelCaps())
                .tracking(0.8)
                .opacity(0.7)
        }
        .foregroundStyle(Self.ink)
        .padding(22)
        .frame(width: Self.width, height: Self.height, alignment: .top)
        .background {
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .fill(Color.white)
        }
        .accessibilityHidden(true)
    }
}

#Preview {
    RoutineShareSheet(
        request: RoutineShareRequest(
            name: "Daily no-hangs",
            metaLine: "20 mm · 6 sets · 36 pulls · ≈21 min",
            signatureFingers: .four,
            peakIntensity: 0.2,
            url: URL(string: "getagrip://routine#preview-payload")!),
        onClose: {})
}
