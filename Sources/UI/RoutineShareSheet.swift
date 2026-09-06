// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import CoreTransferable
import Foundation
import SwiftUI
import UniformTypeIdentifiers
import UIKit

/// A frozen share request, exactly like `ShareCalendarRequest`. The deck behind the sheet
/// can keep moving — a swipe, a CloudKit merge, an edit landing — and what is on screen
/// stays the routine whose menu was tapped. The sheet observes NO store for the same
/// reason: the code it draws and the name beside it must describe one thing.
struct RoutineShareRequest: Identifiable {
    let id = UUID()
    let name: String
    let metaLine: String
    let signatureFingers: FingerSet?
    /// Carried so the mark here wears the SAME rung colour as the card it was opened
    /// from — bleu specifically means "nothing resolves", and a max-day routine whose
    /// card burns red must not turn bleu one presentation later.
    let peakIntensity: Double?
    let url: URL
}

/// The routine as a QR code. The code IS the routine — there is no server, no account and
/// no link that can rot: everything the recipient's app needs rides in the payload, and
/// percentage targets resolve against THEIR maxes, which is the point of prescribing a
/// fraction rather than a kilogram.
struct RoutineShareSheet: View {
    let request: RoutineShareRequest
    var onClose: () -> Void

    @State private var rendered: RenderedRoutineShare?
    /// The one render attempt failed — terminal for this presentation, and said in
    /// words: a permanently disabled "Preparing image…" is a spinner that lies.
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
            // In the safe area, not at the foot of the scroll — the import sheet's own
            // rule, learned here the measured way: at the `.medium` detent this sheet
            // opens on, an in-document button sat 33 pt into the home-indicator strip,
            // and dragging the content resized the sheet instead of scrolling to it.
            .safeAreaInset(edge: .bottom) { shareAction }
        }
        .presentationDetents([.medium, .large])
        .task {
            if rendered == nil, !renderFailed { await renderImage() }
        }
    }

    /// The card's own anatomy — mark, name, plan line — so the sheet reads as the card it
    /// was opened from rather than as a second description of the same routine.
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
        // One VoiceOver stop, like the import sheet's twin of this header — same
        // content, same shape under the rotor.
        .accessibilityElement(children: .combine)
    }

    /// Glass is allowed here: a sheet is its own presentation and carries no
    /// `.contextMenu`, so nothing in this tree is ever lifted out from under it.
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
                // The on-screen QR above still works — only the PNG could not be baked,
                // and a sentence beats a control that looks tappable and never will be.
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

    /// `prominent` picks the ink: the INVERSE ink is correct only over
    /// `.glassProminent`'s opaque graphite fill — on plain glass it rendered white on
    /// the light slate field, the exact white-on-white trap CLAUDE.md records, one
    /// colour scheme over.
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
        .lineLimit(1)
        .minimumScaleFactor(0.85)
        // Full-width and padded, so the drawn label is nowhere near the tappable area
        // SwiftUI would infer from it on its own.
        .frame(maxWidth: .infinity, minHeight: Metrics.buttonHeight)
        .contentShape(.rect)
    }

    private var exportCard: RoutineShareExportCard {
        RoutineShareExportCard(name: request.name, payload: payload)
    }

    /// Rendered once, when the sheet appears — the request is frozen, so there is no
    /// input that could change under it. `isOpaque = false` keeps the card's rounded
    /// corners transparent instead of squaring the PNG off with white.
    @MainActor
    private func renderImage() async {
        // The QR must have drawn before the card is baked — a failed generation renders
        // the fallback SENTENCE, and exporting a white card whose only content is an
        // error message would hand a friend exactly that.
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
            file: RoutineSharePNG(data: png))
    }
}

private struct RenderedRoutineShare {
    let image: UIImage
    let file: RoutineSharePNG
}

/// A file-backed transfer keeps the `.png` extension as well as the content type — and
/// carries the renderer's ORIGINAL bytes, so nothing re-encodes the image on the way to
/// whichever app the share sheet hands it to.
private struct RoutineSharePNG: Transferable {
    let data: Data

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .png) { item in
            let directory = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString, isDirectory: true)
            try FileManager.default.createDirectory(
                at: directory,
                withIntermediateDirectories: true)
            let url = directory.appendingPathComponent("get-a-grip-routine.png")
            try item.data.write(to: url, options: .atomic)
            return SentTransferredFile(url)
        }
    }
}

/// The shared asset. Every colour here is FIXED rather than adaptive: `ImageRenderer`
/// resolves an adaptive colour against whatever trait collection it happens to inherit,
/// and a card whose ink flipped with the sharer's appearance setting would export dark
/// modules on a dark field. It is also the reason `EdgeMark` stays off this card — the
/// mark draws in `Accent.graphite`, which inverts.
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

            // The image travels on its own, to someone who may never have heard of the
            // app. "GET A GRIP" alone names the sender, not the action.
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
