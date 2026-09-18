// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import AVFoundation
import SwiftUI
import VisionKit

/// The camera hands back text only. Today dismisses this sheet before feeding that
/// text into the same import inbox used by links opened from the system Camera.
struct RoutineScannerSheet: View {
    var onScanned: (String) -> Void
    var onClose: () -> Void

    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    @State private var access: Access = .checking

    private enum Access { case checking, ready, denied, unavailable }

    var body: some View {
        NavigationStack {
            Group {
                switch access {
                case .checking:
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                case .ready:
                    RoutineCamera(isActive: scenePhase == .active,
                                  onScanned: onScanned,
                                  onUnavailable: { access = .unavailable })
                        .ignoresSafeArea(edges: .bottom)
                case .denied:
                    ContentUnavailableView {
                        Label("Camera access is off", systemImage: "camera")
                    } description: {
                        Text("Allow camera access in Settings to scan a shared routine.")
                    } actions: {
                        Button("Open Settings") {
                            if let url = URL(string: UIApplication.openSettingsURLString) {
                                openURL(url)
                            }
                        }
                    }
                case .unavailable:
                    ContentUnavailableView {
                        Label("Scanner unavailable", systemImage: "qrcode.viewfinder")
                    } description: {
                        Text("The camera scanner isn't available right now. Try again when the camera is available, or open the routine link directly.")
                    }
                }
            }
            .background { AppBackground() }
            .navigationTitle("Scan a routine")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close", action: onClose)
                }
            }
        }
        .presentationDetents([.large])
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            // In particular, the Simulator cannot provide this camera. Never attempt
            // to construct a scanner on a device VisionKit says it cannot support.
            guard DataScannerViewController.isSupported else {
                access = .unavailable
                return
            }
            if AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined {
                _ = await AVCaptureDevice.requestAccess(for: .video)
            }
            guard !Task.isCancelled else { return }
            let status = AVCaptureDevice.authorizationStatus(for: .video)
            if status == .denied {
                access = .denied
            } else if status == .authorized && DataScannerViewController.isAvailable {
                access = .ready
            } else {
                access = .unavailable
            }
        }
    }
}

private struct RoutineCamera: UIViewControllerRepresentable {
    var isActive: Bool
    var onScanned: (String) -> Void
    var onUnavailable: () -> Void

    func makeUIViewController(context: Context) -> RoutineCameraController {
        RoutineCameraController(onScanned: onScanned, onUnavailable: onUnavailable)
    }

    func updateUIViewController(_ controller: RoutineCameraController, context: Context) {
        controller.onScanned = onScanned
        controller.onUnavailable = onUnavailable
        controller.wantsScanning = isActive
        controller.updateScanning()
    }

    static func dismantleUIViewController(_ controller: RoutineCameraController, coordinator: ()) {
        controller.finish()
    }
}

@MainActor
private final class RoutineCameraController: UIViewController, DataScannerViewControllerDelegate {
    var onScanned: (String) -> Void
    var onUnavailable: () -> Void
    var wantsScanning = false
    private var appeared = false
    private var finished = false
    private let scanner = DataScannerViewController(
        recognizedDataTypes: [.barcode(symbologies: [.qr])],
        qualityLevel: .accurate,
        recognizesMultipleItems: false,
        isHighFrameRateTrackingEnabled: false,
        isPinchToZoomEnabled: true,
        isGuidanceEnabled: true,
        isHighlightingEnabled: true)

    init(onScanned: @escaping (String) -> Void, onUnavailable: @escaping () -> Void) {
        self.onScanned = onScanned
        self.onUnavailable = onUnavailable
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        scanner.delegate = self
        addChild(scanner)
        scanner.view.frame = view.bounds
        scanner.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(scanner.view)
        scanner.didMove(toParent: self)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        appeared = true
        updateScanning()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        appeared = false
        scanner.stopScanning()
    }

    func updateScanning() {
        guard appeared, wantsScanning, !finished else {
            scanner.stopScanning()
            return
        }
        guard !scanner.isScanning else { return }
        do { try scanner.startScanning() }
        catch { fail() }
    }

    func finish() {
        finished = true
        scanner.stopScanning()
        scanner.delegate = nil
    }

    private func fail() {
        guard !finished else { return }
        finish()
        // Starting may fail during a SwiftUI update; publish on the next actor turn.
        Task { @MainActor [onUnavailable] in onUnavailable() }
    }

    private func consume(_ items: [RecognizedItem]) {
        guard appeared, wantsScanning, !finished else { return }
        for case .barcode(let barcode) in items {
            guard let raw = barcode.payloadStringValue, !raw.isEmpty else { continue }
            // One result per presentation, even if VisionKit reports it again before
            // dismissal finishes. Foreign codes reach the importer's existing error.
            finish()
            onScanned(raw)
            return
        }
    }

    func dataScanner(_ dataScanner: DataScannerViewController,
                     didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
        consume(addedItems)
    }

    func dataScanner(_ dataScanner: DataScannerViewController,
                     didUpdate updatedItems: [RecognizedItem], allItems: [RecognizedItem]) {
        consume(updatedItems)
    }

    func dataScanner(_ dataScanner: DataScannerViewController, didTapOn item: RecognizedItem) {
        consume([item])
    }

    func dataScanner(_ dataScanner: DataScannerViewController,
                     becameUnavailableWithError error: DataScannerViewController.ScanningUnavailable) {
        fail()
    }
}
