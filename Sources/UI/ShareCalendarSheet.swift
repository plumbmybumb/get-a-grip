// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import CoreTransferable
import Foundation
import Photos
import SwiftUI
import UniformTypeIdentifiers
import UIKit

/// A frozen share request. The month deck can keep moving while the sheet is open;
/// what gets exported remains the exact five-week window whose button was tapped.
struct ShareCalendarRequest: Identifiable {
    let id = UUID()
    let days: [DayStamp]
    let ledger: HistoryView.DayLedger
    let title: String
    let today: DayStamp
    let bestPull: ShareCalendarBestPull?
}

/// A value snapshot of the winning current max. The export sheet does not observe a
/// store, so recording or syncing a max behind it cannot change the preview and shared
/// file out from under the person looking at them.
struct ShareCalendarBestPull {
    let kg: Double
    let grip: GripSpec
    let side: Side
    let recordedAt: Date

    init(_ record: MaxRecord) {
        kg = record.kg
        grip = record.grip
        side = record.side
        recordedAt = record.recordedAt
    }

    var sortKey: String { "\(grip.key)|\(side.rawValue)" }

    var line: String {
        let amount = kg.formatted(.number.precision(.fractionLength(1)))
        let hand = switch side {
        case .left:  String(localized: " · L")
        case .right: String(localized: " · R")
        case .both:  ""
        }
        return String(localized: "BEST PULL \(amount) KG · \(grip.line)\(hand)")
    }
}

enum ShareCardStyle: String, CaseIterable {
    case white
    case dark
    case frosted

    var title: String {
        switch self {
        case .white:   String(localized: "White")
        case .dark:    String(localized: "Dark")
        case .frosted: String(localized: "Frosted")
        }
    }

    var accessibilityLabel: String {
        switch self {
        case .white:   String(localized: "White ink")
        case .dark:    String(localized: "Dark ink")
        case .frosted: String(localized: "Frosted card")
        }
    }

    fileprivate var spec: StyleSpec {
        switch self {
        case .white:
            StyleSpec(
                ink: .white,
                elementShadow: StyleSpec.Shadow(
                    color: .black.opacity(0.35), radius: 2, y: 1),
                panel: nil)
        case .dark:
            StyleSpec(
                ink: Color(hex: "1B1F25"),
                elementShadow: StyleSpec.Shadow(
                    color: .white.opacity(0.5), radius: 2, y: 1),
                panel: nil)
        case .frosted:
            StyleSpec(
                ink: Color(hex: "1B1F25"),
                elementShadow: nil,
                panel: StyleSpec.Panel(
                    fill: .white.opacity(0.72),
                    border: .white.opacity(0.9),
                    shadow: StyleSpec.Shadow(
                        color: .black.opacity(0.25), radius: 6, y: 2)))
        }
    }
}

/// The one resolved drawing vocabulary for an export style. The frosted panel is
/// translucency, NOT blur: a flat PNG cannot blur what is behind it, and a Material
/// would render grey when captured by ImageRenderer.
fileprivate struct StyleSpec {
    struct Shadow {
        let color: Color
        let radius: CGFloat
        var x: CGFloat = 0
        let y: CGFloat
    }

    struct Panel {
        let fill: Color
        let border: Color
        let shadow: Shadow
    }

    let ink: Color
    let elementShadow: Shadow?
    let panel: Panel?
}

/// Preview and export for one five-week History card.
struct ShareCalendarSheet: View {
    // Meta requires a registered Facebook App ID since 2023-01-30; paste it here.
    private static let instagramAppID: String? = nil

    let request: ShareCalendarRequest
    var onClose: () -> Void

    @AppStorage("shareCardStyle") private var cardStyle: ShareCardStyle = .white
    @State private var includeBestPull = true
    @State private var renderedImage: RenderedShareCalendar?
    @State private var savingToPhotos = false
    @State private var savedToPhotos = false
    @State private var photosAccessDenied = false
    @State private var photoSaveSuccessTick = 0

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    preview

                    stylePicker

                    if request.bestPull != nil {
                        Toggle("Include your best pull", isOn: $includeBestPull)
                            .font(.system(.subheadline, weight: .medium))
                            .foregroundStyle(Ink.primary)
                            .tint(Accent.graphite)
                            .disabled(savingToPhotos)
                    }

                    actionStack

                    Text("Transparent PNG — flattened if added from the gallery inside Instagram; use Save to Photos for everything else.")
                        .font(.system(.footnote))
                        .foregroundStyle(Ink.tertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, Metrics.hPadding)
                .padding(.top, 12)
                .padding(.bottom, 28)
                .frame(maxWidth: Metrics.maxContentWidth)
                .frame(maxWidth: .infinity)
            }
            .scrollBounceBehavior(.basedOnSize)
            .scrollEdgeEffectStyle(.soft, for: .bottom)
            .background { AppBackground() }
            .navigationTitle("Share calendar")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Close") { onClose() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .onAppear {
            if renderedImage == nil { renderImage() }
        }
        .onChange(of: includeBestPull) { _, _ in
            renderImage()
        }
        .onChange(of: cardStyle) { _, _ in
            renderImage()
        }
        .sensoryFeedback(.success, trigger: photoSaveSuccessTick)
    }

    /// The transparent export shown over a deliberately image-like tonal field. Its
    /// square layout scales as one unit, so it never clips on narrow phones.
    private var preview: some View {
        ZStack {
            LinearGradient(
                colors: [Color(white: 0.62), Color(white: 0.16)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing)

            GeometryReader { proxy in
                let scale = min(proxy.size.width / ShareCalendarExportCard.width,
                                proxy.size.height / ShareCalendarExportCard.height)
                exportCard
                    .scaleEffect(scale)
                    .frame(width: proxy.size.width, height: proxy.size.height)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous)
                .strokeBorder(Color.white.opacity(0.18), lineWidth: 1)
        }
    }

    private var stylePicker: some View {
        HStack(spacing: 8) {
            ForEach(ShareCardStyle.allCases, id: \.self) { style in
                Chip(title: style.title, isSelected: cardStyle == style) {
                    cardStyle = style
                }
                .disabled(savingToPhotos)
                .accessibilityLabel(style.accessibilityLabel)
            }
        }
    }

    private var actionStack: some View {
        let instagramURL = instagramShareURL
        return VStack(spacing: 10) {
            if let instagramURL {
                Button {
                    shareToInstagram(at: instagramURL)
                } label: {
                    primaryActionLabel(
                        String(localized: "Share to Instagram story"),
                        systemImage: "camera")
                }
                .buttonStyle(.glassProminent)
                .tint(Accent.graphite)
                .disabled(renderedImage == nil)
            }

            saveToPhotosButton(instagramAvailable: instagramURL != nil)

            if photosAccessDenied {
                Text("Photos access is off for Get a Grip — Settings > Privacy > Photos.")
                    .font(.system(.footnote))
                    .foregroundStyle(Ink.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            genericShareAction
        }
    }

    @ViewBuilder
    private func saveToPhotosButton(instagramAvailable: Bool) -> some View {
        if instagramAvailable {
            Button(action: saveToPhotos) {
                secondaryActionLabel(photoSaveTitle, systemImage: photoSaveSystemImage)
            }
            .buttonStyle(.glass)
            .tint(Accent.graphite)
            .disabled(renderedImage == nil || savingToPhotos)
        } else {
            Button(action: saveToPhotos) {
                primaryActionLabel(photoSaveTitle, systemImage: photoSaveSystemImage)
            }
            .buttonStyle(.glassProminent)
            .tint(Accent.graphite)
            .disabled(renderedImage == nil || savingToPhotos)
        }
    }

    @ViewBuilder
    private var genericShareAction: some View {
        if let renderedImage {
            ShareLink(
                item: renderedImage.file,
                preview: SharePreview(
                    "Get a Grip — \(request.title)",
                    image: Image(uiImage: renderedImage.image))
            ) {
                secondaryActionLabel(String(localized: "Share image"), systemImage: "square.and.arrow.up")
            }
            .buttonStyle(.glass)
            .tint(Accent.graphite)
        } else {
            Button {} label: {
                secondaryActionLabel(String(localized: "Preparing image…"), systemImage: nil)
            }
            .buttonStyle(.glass)
            .tint(Accent.graphite)
            .disabled(true)
        }
    }

    private func primaryActionLabel(_ title: String, systemImage: String?) -> some View {
        HStack(spacing: 8) {
            if let systemImage { Image(systemName: systemImage) }
            Text(title)
        }
        .font(.system(.headline, weight: .semibold))
        .foregroundStyle(primaryLabelColor)
        .frame(maxWidth: .infinity, minHeight: Metrics.buttonHeight)
        .contentShape(.rect)
    }

    private func secondaryActionLabel(_ title: String, systemImage: String?) -> some View {
        HStack(spacing: 8) {
            if let systemImage { Image(systemName: systemImage) }
            Text(title)
        }
        .font(.system(.headline, weight: .semibold))
        .foregroundStyle(Accent.graphite)
        .frame(maxWidth: .infinity, minHeight: Metrics.buttonHeight)
        .contentShape(.rect)
    }

    private var primaryLabelColor: Color {
        .adaptive(Color(hex: "FFFFFF"), Color(hex: "1B1F25"))
    }

    private var photoSaveTitle: String {
        if savingToPhotos { return String(localized: "Saving…") }
        if savedToPhotos { return String(localized: "Saved to Photos") }
        return String(localized: "Save to Photos")
    }

    private var photoSaveSystemImage: String? {
        savedToPhotos ? "checkmark" : "square.and.arrow.down"
    }

    /// The direct delivery path exists only after a Meta app id has been supplied and
    /// Instagram has registered its Stories URL scheme on this device.
    private var instagramShareURL: URL? {
        guard let appID = Self.instagramAppID else { return nil }
        var components = URLComponents()
        components.scheme = "instagram-stories"
        components.host = "share"
        components.queryItems = [URLQueryItem(name: "source_application", value: appID)]
        guard let url = components.url,
              UIApplication.shared.canOpenURL(url) else { return nil }
        return url
    }

    /// Instagram reads this exact PNG from its documented shared-sticker pasteboard
    /// key. A short, device-local lifetime avoids leaving the calendar on the global
    /// pasteboard after the handoff has finished.
    private func shareToInstagram(at url: URL) {
        guard let pngData = renderedImage?.file.data else { return }
        UIPasteboard.general.setItems(
            [["com.instagram.sharedSticker.stickerImage": pngData]],
            options: [
                .expirationDate: Date().addingTimeInterval(5 * 60),
                .localOnly: true,
            ])
        UIApplication.shared.open(url)
    }

    /// Photos receives the renderer's original PNG bytes as an asset resource. Passing
    /// data rather than a UIImage is load-bearing: it preserves alpha and never asks
    /// UIKit to encode the image a second time.
    private func saveToPhotos() {
        guard let pngData = renderedImage?.file.data, !savingToPhotos else { return }
        savingToPhotos = true
        savedToPhotos = false
        photosAccessDenied = false

        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                Task { @MainActor in
                    savingToPhotos = false
                    photosAccessDenied = true
                }
                return
            }

            PHPhotoLibrary.shared().performChanges {
                PHAssetCreationRequest.forAsset()
                    .addResource(with: .photo, data: pngData, options: nil)
            } completionHandler: { success, _ in
                Task { @MainActor in
                    savingToPhotos = false
                    guard success else { return }
                    savedToPhotos = true
                    photoSaveSuccessTick += 1
                }
            }
        }
    }

    private var exportCard: ShareCalendarExportCard {
        ShareCalendarExportCard(
            days: request.days,
            ledger: request.ledger,
            title: request.title,
            today: request.today,
            bestPull: includeBestPull ? request.bestPull : nil,
            style: cardStyle)
    }

    /// Rendering happens only when the sheet appears or the one export option changes.
    /// The renderer is explicitly non-opaque, so the clear canvas — including the
    /// frosted panel's 0.72 alpha — survives all the way to the share sheet.
    @MainActor
    private func renderImage() {
        savedToPhotos = false
        photosAccessDenied = false
        let renderer = ImageRenderer(content: exportCard)
        renderer.isOpaque = false
        renderer.scale = 3
        renderer.proposedSize = ProposedViewSize(
            width: ShareCalendarExportCard.width,
            height: ShareCalendarExportCard.height)
        guard let image = renderer.uiImage, let png = image.pngData() else {
            renderedImage = nil
            return
        }
        renderedImage = RenderedShareCalendar(
            image: image,
            file: ShareCalendarPNG(data: png))
    }
}

private struct RenderedShareCalendar {
    let image: UIImage
    let file: ShareCalendarPNG
}

/// A file-backed transfer keeps the `.png` extension as well as the PNG content type.
private struct ShareCalendarPNG: Transferable {
    let data: Data

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .png) { item in
            let directory = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString, isDirectory: true)
            try FileManager.default.createDirectory(
                at: directory,
                withIntermediateDirectories: true)
            let url = directory.appendingPathComponent("get-a-grip-5-weeks.png")
            try item.data.write(to: url, options: .atomic)
            return SentTransferredFile(url)
        }
    }
}

/// The alpha-preserving asset itself. Preview and ImageRenderer use this same view, so
/// the sheet cannot drift from the file it hands to the system share sheet.
private struct ShareCalendarExportCard: View {
    static let width: CGFloat = 360
    static let height: CGFloat = 360

    let days: [DayStamp]
    let ledger: HistoryView.DayLedger
    let title: String
    let today: DayStamp
    let bestPull: ShareCalendarBestPull?
    let style: ShareCardStyle

    private let columns = Array(
        repeating: GridItem(.fixed(36), spacing: 7),
        count: 7)

    /// The grid's exact span — 7 fixed cells plus 6 gaps. The WHOLE content column is
    /// constrained to this width and centred, so the title, summary, best-pull line and
    /// wordmark all share the grid's edges. Text aligned to the card's padding instead
    /// sat ~13 pt left of the first column and read as pushed into the corner
    /// (Nuri, 2026-08-12).
    private static let gridWidth: CGFloat = 7 * 36 + 6 * 7

    var body: some View {
        let spec = style.spec
        VStack(alignment: .leading, spacing: 0) {
            Text(title)
                .font(.labelCaps())
                .tracking(0.8)
                .lineLimit(1)
                .minimumScaleFactor(0.75)

            Spacer().frame(height: 12)

            LazyVGrid(columns: columns, spacing: 7) {
                ForEach(days, id: \.self) { day in
                    ShareCalendarDayCell(
                        fraction: ledger.fraction(on: day),
                        isToday: day == today,
                        isTracked: day >= ledger.trackingSince,
                        climbed: ledger.climbed(on: day),
                        benchmarked: ledger.benchmarked(on: day),
                        ink: spec.ink)
                }
            }

            Spacer().frame(height: 12)

            Text(summary)
                .font(.system(.footnote))
                .monospacedDigit()

            if let bestPull {
                Spacer().frame(height: 8)
                HStack(spacing: 7) {
                    Text(bestPull.line)
                        .font(.system(.caption2, weight: .semibold))
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.55)
                    ExportHandMark(fingers: bestPull.grip.fingers, ink: spec.ink)
                        .fixedSize()
                }
            }

            Spacer(minLength: 10)

            HStack {
                Spacer()
                Text("DOIGT")
                    .font(.labelCaps())
                    .tracking(0.8)
                    .opacity(0.7)
            }
        }
        .foregroundStyle(spec.ink)
        // Width pinned to the grid, then centred by the outer frame; vertical padding
        // still proposes the full height so the bottom spacer keeps DOIGT at the foot.
        .frame(width: Self.gridWidth)
        .padding(.vertical, 20)
        .frame(width: Self.width, height: Self.height, alignment: .top)
        .background { exportPanel(spec.panel) }
        .modifier(ExportElementShadow(shadow: spec.elementShadow))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spokenSummary)
    }

    @ViewBuilder
    private func exportPanel(_ panel: StyleSpec.Panel?) -> some View {
        if let panel {
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .fill(panel.fill)
                .overlay {
                    RoundedRectangle(cornerRadius: 28, style: .continuous)
                        .strokeBorder(panel.border, lineWidth: 1)
                }
                .shadow(color: panel.shadow.color,
                        radius: panel.shadow.radius,
                        x: panel.shadow.x,
                        y: panel.shadow.y)
                // Keeps the panel's drop shadow fully inside the 360 × 360 PNG.
                .padding(10)
        } else {
            Color.clear
        }
    }

    private var summary: String {
        let tracked = days.filter { $0 >= ledger.trackingSince }
        let trained = tracked.filter { ledger.fraction(on: $0) > 0 }.count
        return String(localized: "\(trained) of \(tracked.count) days trained")
    }

    private var spokenSummary: String {
        if let bestPull {
            return String(localized: "\(title). \(summary). \(bestPull.line).")
        }
        return String(localized: "\(title). \(summary).")
    }
}

/// The source grid's three zero-fraction states and rising-fill language, redrawn in
/// the selected ink. The climb notch wins over the benchmark bore.
private struct ShareCalendarDayCell: View {
    let fraction: Double
    let isToday: Bool
    let isTracked: Bool
    let climbed: Bool
    let benchmarked: Bool
    let ink: Color

    private var showsBenchmark: Bool { benchmarked && !climbed }

    var body: some View {
        ZStack {
            if isTracked {
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(ink.opacity(0.18))
            } else {
                Capsule()
                    .fill(ink.opacity(0.35))
                    .frame(width: 18, height: 2)
            }

            if fraction > 0 {
                GeometryReader { proxy in
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(ink)
                        .frame(height: proxy.size.height * min(1, fraction))
                        .frame(maxHeight: .infinity, alignment: .bottom)
                }
                .climbNotch(climbed)
                .benchmarkBore(showsBenchmark, size: 9)
                // A same-ink ring needs a low-opacity moat around the solid fill to
                // remain a ring; the source grid gets that separation from colour.
                .padding(isToday ? 4 : 0)
            }

            if isToday {
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .strokeBorder(ink, lineWidth: 2)
            }
        }
        .frame(width: 36, height: 36)
        .accessibilityHidden(true)
    }
}

/// Export-local version of the app's hand mark. Selected fingers use solid ink;
/// excluded fingers remain capsule outlines, so the grip survives monochrome.
private struct ExportHandMark: View {
    let fingers: FingerSet
    let ink: Color

    private static let lengthFactor: [CGFloat] = [0.86, 1.0, 0.94, 0.80]
    private let barWidth: CGFloat = 5
    private let gap: CGFloat = 2.5

    private var barHeight: CGFloat { barWidth * 1.75 }

    var body: some View {
        VStack(alignment: .leading, spacing: max(1.5, gap * 0.6)) {
            HStack(alignment: .bottom, spacing: gap) {
                ForEach(Array(fingers.occupied.indices), id: \.self) { index in
                    finger(on: fingers.occupied[index], index: index)
                }
            }
            if fingers.hasThumb {
                RoundedRectangle(cornerRadius: barWidth / 2, style: .continuous)
                    .fill(ink)
                    .frame(width: barWidth * 2 + gap, height: barWidth * 0.62)
            }
        }
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private func finger(on: Bool, index: Int) -> some View {
        let shape = RoundedRectangle(cornerRadius: barWidth / 2, style: .continuous)
        Group {
            if on {
                shape.fill(ink)
            } else {
                shape.strokeBorder(ink.opacity(0.45), lineWidth: 1)
            }
        }
        .frame(width: barWidth, height: barHeight * Self.lengthFactor[index])
    }
}

private struct ExportElementShadow: ViewModifier {
    let shadow: StyleSpec.Shadow?

    @ViewBuilder
    func body(content: Content) -> some View {
        if let shadow {
            content.shadow(color: shadow.color,
                           radius: shadow.radius,
                           x: shadow.x,
                           y: shadow.y)
        } else {
            content
        }
    }
}
